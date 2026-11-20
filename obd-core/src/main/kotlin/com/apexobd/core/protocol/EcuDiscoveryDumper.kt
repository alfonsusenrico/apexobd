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

    suspend fun runFullReadOnlyScan(
        transport: ObdTransport,
        onProgress: (String) -> Unit = {}
    ): String {
        val sb = StringBuilder()
        fun log(line: String = "") {
            sb.appendLine(line)
        }

        val client = Elm327Client(transport)
        log("=== APEXOBD READ-ONLY DEEP ECU DISCOVERY ===")
        log("Endpoint: ${transport.endpointLabel}")

        val tStart = System.currentTimeMillis()
        onProgress("Deep Scan (1/6): Connecting & initializing ELM327...")
        val handshake = client.initialize(aggressiveTiming = false)
        log("Adapter ATZ: ${handshake.adapterVersion}")
        log("Protocol ATDP: ${handshake.protocolName} (ATDPN=${handshake.protocolCode}, isCan=${handshake.isCanProtocol})")

        for (at in listOf("ATI", "AT@1", "ATRV")) {
            val r = client.execute(at)
            log("$at -> ${formatResult(r)}")
        }

        // Enable headers briefly to discover all responding ECUs on Mode 01 00
        onProgress("Deep Scan (2/6): Probing CAN headers & Mode 01 bitmasks...")
        log("\n--- ECU ADDRESS DISCOVERY (ATH1 + 0100..C0) ---")
        client.execute("ATH1")
        for (block in listOf("00", "20", "40", "60", "80", "A0", "C0")) {
            val r = client.execute("01$block")
            log("01$block (ATH1) -> ${formatResult(r)}")
        }
        client.execute("ATH0")

        // Discover supported Mode 01 PIDs
        log("\n--- MODE 01 SUPPORTED PIDS & LIVE VALUES ---")
        val supported = ObdPidRegistry.discoverSupportedPids(client)
        log("Supported Mode 01 PIDs (${supported.size}): ${supported.joinToString(", ")}")

        val skipBlocks = setOf("00", "20", "40", "60", "80", "A0", "C0")
        val valuePids = supported.filter { it !in skipBlocks }
        for ((idx, pidHex) in valuePids.withIndex()) {
            onProgress("Deep Scan (3/6): Sampling Mode 01 ${idx + 1}/${valuePids.size} (01$pidHex)...")
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

        // Benchmark CAN single-frame 2-PID pack, multi-frame 4-PID pack, vs single-PID
        onProgress("Deep Scan (4/6): Running CAN multi-PID benchmark...")
        log("\n--- PERFORMANCE BENCHMARK ---")
        if (handshake.isCanProtocol) {
            val tSf0 = System.currentTimeMillis()
            val sfRes = client.execute("010C451")
            val rttSf = System.currentTimeMillis() - tSf0
            val sfDecoded = if (sfRes is ObdCommandResult.Success) {
                ObdPidRegistry.decodeMultiMode01Response(sfRes.lines)
            } else emptyList()
            log("Single-Frame 2-PID 010C451 (${rttSf}ms) -> ${formatResult(sfRes)} | decodedCount=${sfDecoded.size}")

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
        onProgress("Deep Scan (5/6): Scanning Modes 02 / 03 / 06 / 09...")
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

        // Safe read-only UDS Mode 22 & OEM Mode 21 identification/telemetry probe (including 7C0 Combination Meter fuel level)
        onProgress("Deep Scan (6/6): Probing 7E0 / 7C0 Meter / 750 Body Mode 21 & UDS...")
        log("\n--- READ-ONLY MODE 22 & MODE 21 PROBE ---")
        if (handshake.isCanProtocol) {
            client.execute("ATST19", timeoutMs = 1000L)
        }
        try {
            for (did in listOf("22F190", "22F187", "22F40C", "22F42F")) {
                log("$did -> ${formatResult(client.execute(did, timeoutMs = 800L))}")
            }

            // 1. Engine ECM (7E0 -> 7E8): walk Mode 21 bitmasks (2100..21E0) and dump supported PIDs
            log("\n--- ECM 7E0 MODE 21 BITMASK WALK ---")
            client.execute("ATSH7E0", timeoutMs = 800L)
            if (handshake.isCanProtocol) client.execute("ATCRA7E8", timeoutMs = 800L)
            val ecm21Supported = discoverMode21BitmaskPids(client, "7E0", ::log)
            val ecm21Targets = if (ecm21Supported.isNotEmpty()) {
                ecm21Supported.filter { it !in skipBlocks }.take(48)
            } else {
                listOf("01", "03", "04", "05", "06", "23", "25", "26", "29", "2F", "30")
            }
            for (pidHex in ecm21Targets) {
                log("7E0 21$pidHex -> ${formatResult(client.execute("21$pidHex", timeoutMs = 800L))}")
            }

            // 2. Combination Meter / Instrument Cluster (7C0 -> 7C8): Toyota/Daihatsu digital fuel level lives here
            onProgress("Deep Scan (6/6): Probing 7C0 Combination Meter (Fuel Level)...")
            log("\n--- COMBINATION METER 7C0 (CLUSTER / FUEL LEVEL) ---")
            client.execute("ATSH7C0", timeoutMs = 800L)
            if (handshake.isCanProtocol) client.execute("ATCRA7C8", timeoutMs = 800L)
            val meter21Supported = discoverMode21BitmaskPids(client, "7C0", ::log)
            val meterDirectProbes = linkedSetOf(
                "29", "24", "2F", "01", "02", "03", "04", "05", "06", "07", "08", "09", "0A",
                "10", "11", "21", "22", "23", "25", "26", "27", "28", "2A", "2B", "2C", "30", "31", "32"
            )
            meter21Supported.filter { it !in skipBlocks }.forEach { meterDirectProbes.add(it) }
            var meterResponded = meter21Supported.isNotEmpty()
            for (pidHex in meterDirectProbes) {
                val res = client.execute("21$pidHex", timeoutMs = 700L)
                if (res is ObdCommandResult.Success) {
                    meterResponded = true
                    log("7C0 21$pidHex -> ${formatResult(res)}")
                } else if (pidHex in setOf("29", "24", "2F", "01") || meterResponded) {
                    log("7C0 21$pidHex -> ${formatResult(res)}")
                }
            }
            for (did in listOf("22F42F", "22F190", "22F187", "221001", "221002", "222000")) {
                log("7C0 $did -> ${formatResult(client.execute(did, timeoutMs = 700L))}")
            }

            // 3. Main Body ECU (750 -> 758), TCM (7E1 -> 7E9), Secondary (7E2 -> 7EA)
            log("\n--- BODY 750 / TCM 7E1 / SECONDARY 7E2 ---")
            client.execute("ATSH750", timeoutMs = 800L)
            if (handshake.isCanProtocol) client.execute("ATCRA758", timeoutMs = 800L)
            for (cmd in listOf("2100", "2129", "212F", "22F42F")) {
                log("750 $cmd -> ${formatResult(client.execute(cmd, timeoutMs = 700L))}")
            }

            client.execute("ATSH7E1", timeoutMs = 800L)
            if (handshake.isCanProtocol) client.execute("ATCRA7E9", timeoutMs = 800L)
            for (cmd in listOf("0100", "2100", "2101", "221E1C")) {
                log("7E1 $cmd -> ${formatResult(client.execute(cmd, timeoutMs = 700L))}")
            }

            client.execute("ATSH7E2", timeoutMs = 800L)
            if (handshake.isCanProtocol) client.execute("ATCRA7EA", timeoutMs = 800L)
            for (cmd in listOf("0100", "2100", "2129")) {
                log("7E2 $cmd -> ${formatResult(client.execute(cmd, timeoutMs = 700L))}")
            }
        } finally {
            if (handshake.isCanProtocol) {
                client.execute("ATCRA", timeoutMs = 800L)
            }
            client.execute("ATSH7DF", timeoutMs = 800L)
        }

        log("\n=== SCAN COMPLETE in ${System.currentTimeMillis() - tStart}ms ===")
        return sb.toString()
    }

    private suspend fun discoverMode21BitmaskPids(
        client: Elm327Client,
        ecuLabel: String,
        log: (String) -> Unit
    ): Set<String> {
        val supported = linkedSetOf<String>()
        val blocks = listOf("00", "20", "40", "60", "80", "A0", "C0", "E0")
        for (blockHex in blocks) {
            val res = client.execute("21$blockHex", timeoutMs = 800L)
            log("$ecuLabel 21$blockHex -> ${formatResult(res)}")
            if (res !is ObdCommandResult.Success) break
            val blockInt = blockHex.toInt(16)
            val payload = Elm327Client.extractPidPayload(res.lines, 0x61, blockInt) ?: break
            if (payload.size < 4) break
            val blockSupported = ObdPidRegistry.decodeBitmask(blockInt, payload.take(4))
            supported.addAll(blockSupported)
            val nextBlockCode = "%02X".format((blockInt + 0x20) and 0xFF)
            if (blockInt >= 0xE0 || !blockSupported.contains(nextBlockCode)) break
        }
        if (supported.isNotEmpty()) {
            log("$ecuLabel Supported Mode 21 PIDs (${supported.size}): ${supported.joinToString(", ")}")
        }
        return supported
    }

    private fun formatResult(res: ObdCommandResult): String = when (res) {
        is ObdCommandResult.Success -> res.lines.joinToString(" | ")
        is ObdCommandResult.NoData -> "NO DATA"
        is ObdCommandResult.Error -> "ERROR(${res.message})"
    }
}
