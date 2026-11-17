package com.apexobd.core.protocol

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.transport.ObdTransport

/**
 * Strictly read-only ECU capability & telemetry dumper.
 * Queries adapter identity, protocol, Mode 01 bitmasks & all supported PIDs,
 * CAN multi-PID capability + RTT benchmark, Mode 02 Freeze Frame, Modes 03/07/0A DTCs,
 * Mode 06 monitors, Mode 09 vehicle info, and safe read-only UDS Mode 22 identifiers.
 */
object EcuDiscoveryDumper {

    suspend fun runFullReadOnlyScan(transport: ObdTransport): String {
        val sb = StringBuilder()
        fun log(line: String = "") {
            sb.appendLine(line)
        }

        val client = Elm327Client(transport)
        log("=== APEXOBD READ-ONLY DEEP ECU DISCOVERY ===")
        log("Endpoint: ${transport.endpointLabel}")

        val tStart = System.currentTimeMillis()
        val handshake = client.initialize(aggressiveTiming = false)
        log("Adapter ATZ: ${handshake.adapterVersion}")
        log("Protocol ATDP: ${handshake.protocolName} (ATDPN=${handshake.protocolCode}, isCan=${handshake.isCanProtocol})")

        for (at in listOf("ATI", "AT@1", "ATRV")) {
            val r = client.execute(at)
            log("$at -> ${formatResult(r)}")
        }

        // Enable headers briefly to discover all responding ECUs on Mode 01 00
        log("\n--- ECU ADDRESS DISCOVERY (ATH1 + 0100) ---")
        client.execute("ATH1")
        for (block in listOf("00", "20", "40", "60", "80", "A0")) {
            val r = client.execute("01$block")
            log("01$block (ATH1) -> ${formatResult(r)}")
        }
        client.execute("ATH0")

        // Discover supported Mode 01 PIDs
        log("\n--- MODE 01 SUPPORTED PIDS & LIVE VALUES ---")
        val supported = ObdPidRegistry.discoverSupportedPids(client)
        log("Supported Mode 01 PIDs (${supported.size}): ${supported.joinToString(", ")}")

        val skipBlocks = setOf("00", "20", "40", "60", "80", "A0", "C0")
        for (pidHex in supported) {
            if (pidHex in skipBlocks) continue
            val cmd = if (handshake.isCanProtocol) "01${pidHex}1" else "01$pidHex"
            val t0 = System.currentTimeMillis()
            val r = client.execute(cmd)
            val rtt = System.currentTimeMillis() - t0
            val decoded = if (r is ObdCommandResult.Success) {
                ObdPidRegistry.decodeMode01Response(pidHex, r.lines)
            } else null
            val def = ObdPidRegistry.findByPidHex(pidHex)
            val label = def?.let { "${it.name} (${it.key})" } ?: "Unmapped PID"
            val valStr = decoded?.let { "%.3f %s".format(it.value, it.unit) } ?: "raw"
            log("PID 01$pidHex [$label] (${rtt}ms) -> ${formatResult(r)} | decoded=$valStr")
        }

        // Benchmark CAN multi-PID batching vs single-PID
        log("\n--- PERFORMANCE BENCHMARK ---")
        if (handshake.isCanProtocol) {
            val tMulti0 = System.currentTimeMillis()
            val multiRes = client.execute("010C0D1011")
            val rttMulti = System.currentTimeMillis() - tMulti0
            val multiDecoded = if (multiRes is ObdCommandResult.Success) {
                ObdPidRegistry.decodeMultiMode01Response(multiRes.lines)
            } else emptyList()
            log("Multi-PID 010C0D1011 (${rttMulti}ms) -> ${formatResult(multiRes)} | decodedCount=${multiDecoded.size}")

            val tBatch2 = System.currentTimeMillis()
            val multi2 = client.execute("0104050E0F")
            val rttMulti2 = System.currentTimeMillis() - tBatch2
            log("Multi-PID 0104050E0F (${rttMulti2}ms) -> ${formatResult(multi2)}")
        }
        val tSingle0 = System.currentTimeMillis()
        val singleRes = client.execute("010C1")
        val rttSingle = System.currentTimeMillis() - tSingle0
        log("Single-PID 010C1 (${rttSingle}ms) -> ${formatResult(singleRes)}")

        // Mode 02 Freeze Frame
        log("\n--- MODE 02 FREEZE FRAME ---")
        for (cmd in listOf("0200", "0202", "0204", "0205", "0206", "020C", "020D", "020F", "0210", "0211")) {
            val r = client.execute(cmd)
            log("$cmd -> ${formatResult(r)}")
        }

        // Modes 03, 07, 0A DTCs (Read-only)
        log("\n--- MODES 03 / 07 / 0A DTCS ---")
        val report = DtcDecoder.scanAll(client)
        log("03 Raw -> ${formatResult(client.execute("03"))}")
        log("07 Raw -> ${formatResult(client.execute("07"))}")
        log("0A Raw -> ${formatResult(client.execute("0A"))}")
        log("Decoded Confirmed=${report.confirmed.map { it.code }}, Pending=${report.pending.map { it.code }}, Permanent=${report.permanent.map { it.code }}")
        log("Readiness -> ${report.readiness}")

        // Mode 06 On-Board Monitoring
        log("\n--- MODE 06 ON-BOARD MONITORS ---")
        val m06Supported = mutableSetOf<String>()
        for (block in listOf("00", "20", "40", "60", "80", "A0")) {
            val r = client.execute("06$block")
            log("06$block -> ${formatResult(r)}")
            if (r is ObdCommandResult.Success) {
                val payload = Elm327Client.extractPidPayload(r.lines, 0x46, block.toInt(16))
                if (payload != null && payload.size >= 4) {
                    m06Supported.addAll(ObdPidRegistry.decodeBitmask(block.toInt(16), payload.take(4)))
                } else break
            } else break
        }
        for (tid in m06Supported.filter { it !in skipBlocks }.take(12)) {
            log("06$tid -> ${formatResult(client.execute("06$tid"))}")
        }

        // Mode 09 Vehicle Info
        log("\n--- MODE 09 VEHICLE INFO ---")
        val m09Res = client.execute("0900")
        log("0900 -> ${formatResult(m09Res)}")
        for (pid in listOf("0902", "0904", "0906", "090A")) {
            log("$pid -> ${formatResult(client.execute(pid, timeoutMs = 3000L))}")
        }
        log("Parsed VIN -> ${DtcDecoder.readVin(client)}")

        // Safe read-only UDS Mode 22 & OEM Mode 21 identification/telemetry probe
        log("\n--- READ-ONLY MODE 22 & MODE 21 PROBE ---")
        for (did in listOf("22F190", "22F187", "22F40C")) {
            log("$did -> ${formatResult(client.execute(did, timeoutMs = 1200L))}")
        }
        client.execute("ATSH7E0")
        for (pid21 in listOf("2100", "2101", "2102", "2103", "2104", "2110", "2120", "2130")) {
            log("7E0 $pid21 -> ${formatResult(client.execute(pid21, timeoutMs = 1200L))}")
        }
        client.execute("ATSH7E1")
        for (cmd in listOf("0100", "2101", "221E1C")) {
            log("7E1 $cmd -> ${formatResult(client.execute(cmd, timeoutMs = 1200L))}")
        }
        client.execute("ATSH7DF")

        log("\n=== SCAN COMPLETE in ${System.currentTimeMillis() - tStart}ms ===")
        return sb.toString()
    }

    private fun formatResult(res: ObdCommandResult): String = when (res) {
        is ObdCommandResult.Success -> res.lines.joinToString(" | ")
        is ObdCommandResult.NoData -> "NO DATA"
        is ObdCommandResult.Error -> "ERROR(${res.message})"
    }
}
