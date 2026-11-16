package com.apexobd.core.formula

import com.apexobd.core.elm.ObdCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaEvaluatorTest {

    @Test
    fun `evaluate computes arithmetic, bitwise, and SIGNED expressions`() {
        assertEquals(76.0, FormulaEvaluator.evaluate("((A*256)+B)/10-40", listOf(0x04, 0x88)), 0.001)
        assertEquals(-10.0, FormulaEvaluator.evaluate("SIGNED(A)", listOf(246)), 0.001)
        assertEquals(258.0, FormulaEvaluator.evaluate("(A<<8)|B", listOf(0x01, 0x02)), 0.001)
    }

    @Test(expected = ArithmeticException::class)
    fun `evaluate throws on division by zero`() {
        FormulaEvaluator.evaluate("A/0", listOf(10))
    }

    @Test
    fun `TorqueCsvImporter parses quoted CSV and decodes UDS Mode 22 response`() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
            "Trans Temp","TFT","221E1C","((A*256)+B)/10-40","0","150","°C","7E1"
        """.trimIndent()
        val defs = TorqueCsvImporter.parseCsv(csv)
        assertEquals(1, defs.size)
        val def = defs.first()
        assertEquals("221E1C", def.modeAndPidHex)
        assertEquals("7E1", def.headerHex)

        val decoded = FormulaEvaluator.decodeCustomPid(
            def,
            ObdCommandResult.Success("62 1E 1C 04 88", listOf("62 1E 1C 04 88"))
        )
        assertEquals(76.0, decoded!!, 0.001)
    }
}
