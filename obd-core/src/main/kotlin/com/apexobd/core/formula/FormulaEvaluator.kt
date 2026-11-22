package com.apexobd.core.formula

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult

data class CustomPidDefinition(
    val key: String,
    val name: String,
    val shortName: String,
    val modeAndPidHex: String, // e.g., "221E1C" or "015C"
    val formula: String,       // e.g., "((A*256)+B)/10-40"
    val minValue: Double,
    val maxValue: Double,
    val unit: String,
    val headerHex: String? = null
)

/**
 * Safe recursive-descent arithmetic and bitwise expression evaluator for Torque-style
 * byte formulas (`A`..`H`, `+`, `-`, `*`, `/`, `%`, `<<`, `>>`, `&`, `|`, `SIGNED(x)`).
 */
object FormulaEvaluator {

    fun evaluate(expression: String, bytes: List<Int>): Double {
        val tokens = tokenize(expression)
        val parser = Parser(tokens, bytes)
        val result = parser.parseBitOr()
        if (parser.hasMore()) {
            throw IllegalArgumentException("Unexpected token '${parser.peek()}' in formula '$expression'")
        }
        return result
    }

    fun decodeCustomPid(def: CustomPidDefinition, result: ObdCommandResult.Success): Double? {
        val cmd = def.modeAndPidHex.replace(" ", "").uppercase()
        if (cmd.length < 4 || cmd.length % 2 != 0) return null
        val modeReq = cmd.substring(0, 2).toIntOrNull(16) ?: return null
        val expectedResp = modeReq + 0x40
        val pidBytes = (2 until cmd.length step 2).map { cmd.substring(it, it + 2).toInt(16) }

        // Try flattened ISO-TP payload first when multi-frame lines ('0:', '1:') are present
        if (result.lines.any { it.contains(":") }) {
            val flat = Elm327Client.flattenIsoTpBytes(result.lines)
            if (flat.size > pidBytes.size + 1 && flat[0] == expectedResp) {
                val matchesPid = pidBytes.indices.all { idx -> flat[idx + 1] == pidBytes[idx] }
                if (matchesPid) {
                    val dataBytes = flat.drop(1 + pidBytes.size)
                    return runCatching { evaluate(def.formula, dataBytes) }.getOrNull()
                }
            }
        }

        for (line in result.lines) {
            val bytes = Elm327Client.parseHexBytes(line)
            if (bytes.size > pidBytes.size + 1 && bytes[0] == expectedResp) {
                val matchesPid = pidBytes.indices.all { idx -> bytes[idx + 1] == pidBytes[idx] }
                if (matchesPid) {
                    val dataBytes = bytes.drop(1 + pidBytes.size)
                    return runCatching { evaluate(def.formula, dataBytes) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun tokenize(expr: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    out.add(expr.substring(start, i))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < expr.length && expr[i].isLetterOrDigit()) i++
                    out.add(expr.substring(start, i).uppercase())
                }
                i + 1 < expr.length && (expr.substring(i, i + 2) == "<<" || expr.substring(i, i + 2) == ">>") -> {
                    out.add(expr.substring(i, i + 2))
                    i += 2
                }
                c in listOf('+', '-', '*', '/', '%', '(', ')', '&', '|') -> {
                    out.add(c.toString())
                    i++
                }
                else -> throw IllegalArgumentException("Unsupported character '$c' in formula")
            }
        }
        return out
    }

    private class Parser(
        private val tokens: List<String>,
        private val bytes: List<Int>
    ) {
        private var pos = 0

        fun hasMore() = pos < tokens.size
        fun peek(): String? = tokens.getOrNull(pos)
        private fun consume(): String = tokens[pos++]

        fun parseBitOr(): Double {
            var left = parseBitAnd()
            while (peek() == "|") {
                consume()
                left = (left.toLong() or parseBitAnd().toLong()).toDouble()
            }
            return left
        }

        private fun parseBitAnd(): Double {
            var left = parseShift()
            while (peek() == "&") {
                consume()
                left = (left.toLong() and parseShift().toLong()).toDouble()
            }
            return left
        }

        private fun parseShift(): Double {
            var left = parseAddSub()
            while (peek() == "<<" || peek() == ">>") {
                val op = consume()
                val right = parseAddSub().toInt()
                left = if (op == "<<") (left.toLong() shl right).toDouble() else (left.toLong() shr right).toDouble()
            }
            return left
        }

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (peek() == "+" || peek() == "-") {
                val op = consume()
                val right = parseMulDiv()
                left = if (op == "+") left + right else left - right
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parseUnary()
            while (peek() == "*" || peek() == "/" || peek() == "%") {
                val op = consume()
                val right = parseUnary()
                left = when (op) {
                    "*" -> left * right
                    "/" -> if (right == 0.0) throw ArithmeticException("Division by zero") else left / right
                    else -> left % right
                }
            }
            return left
        }

        private fun parseUnary(): Double {
            if (peek() == "-") {
                consume()
                return -parseUnary()
            }
            if (peek() == "+") {
                consume()
                return parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            val tok = consume()
            if (tok == "(") {
                val inner = parseBitOr()
                if (peek() != ")") throw IllegalArgumentException("Missing closing ')'")
                consume()
                return inner
            }
            if (tok == "SIGNED") {
                if (peek() != "(") throw IllegalArgumentException("Expected '(' after SIGNED")
                consume()
                val raw = parseBitOr().toInt() and 0xFF
                if (peek() != ")") throw IllegalArgumentException("Missing ')' after SIGNED")
                consume()
                return if (raw >= 128) (raw - 256).toDouble() else raw.toDouble()
            }
            if (tok.length == 1 && tok[0] in 'A'..'H') {
                val idx = tok[0] - 'A'
                return bytes.getOrNull(idx)?.toDouble()
                    ?: throw IllegalArgumentException("Formula references byte $tok but payload has ${bytes.size} bytes")
            }
            return tok.toDoubleOrNull()
                ?: throw IllegalArgumentException("Unknown token '$tok'")
        }
    }
}

/**
 * Parses Torque-compatible custom PID CSV rows:
 * Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
 */
object TorqueCsvImporter {

    fun parseCsv(csvContent: String): List<CustomPidDefinition> {
        val out = mutableListOf<CustomPidDefinition>()
        for (rawLine in csvContent.lines()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = splitCsvRow(line)
            if (cols.size < 7) continue
            if (cols[0].equals("Name", ignoreCase = true) && cols[2].contains("PID", ignoreCase = true)) continue

            val name = cols[0].trim()
            val shortName = cols[1].trim().ifEmpty { name.take(10) }
            val modePid = cols[2].replace(" ", "").uppercase()
            val eq = cols[3].trim()
            val min = cols[4].toDoubleOrNull() ?: 0.0
            val max = cols[5].toDoubleOrNull() ?: 100.0
            val unit = cols[6].trim()
            val header = cols.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() }

            if (name.isEmpty() || modePid.length < 4 || eq.isEmpty()) continue
            val safeKey = "custom_" + modePid.lowercase() + "_" + shortName.lowercase().replace(Regex("[^a-z0-9]"), "_")
            out += CustomPidDefinition(
                key = safeKey,
                name = name,
                shortName = shortName,
                modeAndPidHex = modePid,
                formula = eq,
                minValue = min,
                maxValue = max,
                unit = unit,
                headerHex = header
            )
        }
        return out
    }

    private fun splitCsvRow(line: String): List<String> {
        val cols = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cols.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        cols.add(cur.toString())
        return cols
    }
}
