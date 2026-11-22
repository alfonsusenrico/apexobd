package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.SimulatedObdTransport
import com.apexobd.core.transport.WifiTcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class EngineAndPerformanceTest {

    @Test
    fun `SimulatedObdTransport completes handshake, bitmask discovery, and DTC clear`() = runTest {
        val transport = SimulatedObdTransport(responseDelayMs = 0L)
        transport.connect()
        val client = Elm327Client(transport)

        val info = client.initialize()
        assertEquals("ELM327 v1.5", info.adapterVersion)
        assertTrue(info.protocolName.contains("CAN"))
        assertTrue(info.isCanProtocol)

        val multiRes = client.execute("010C0D10") as ObdCommandResult.Success
        val multiDecoded = ObdPidRegistry.decodeMultiMode01Response(multiRes.lines)
        assertEquals(3, multiDecoded.size)

        val pids = ObdPidRegistry.discoverSupportedPids(client)
        assertTrue("0C" in pids)
        assertTrue("42" in pids)

        val before = DtcDecoder.scanAll(client)
        assertEquals(2, before.confirmed.size)
        assertEquals(1, before.pending.size)

        assertTrue(DtcDecoder.clearDtcs(client))
        val after = DtcDecoder.scanAll(client)
        assertTrue(after.confirmed.isEmpty())
        assertTrue(after.pending.isEmpty())
    }

    @Test
    fun `PerformanceCalculator computes HP and Torque from ECU PIDs and MAF fallback`() {
        val (tqEcu, hpEcu) = PerformanceCalculator.estimateTorqueAndHp(
            rpm = 5000.0,
            mafGramsPerSec = null,
            actualTorquePct = 80.0,
            refTorqueNm = 300.0
        )
        assertEquals(240.0, tqEcu!!, 0.1)
        assertEquals(168.37, hpEcu!!, 0.1)

        val (tqMaf, hpMaf) = PerformanceCalculator.estimateTorqueAndHp(
            rpm = 4000.0,
            mafGramsPerSec = 80.0,
            actualTorquePct = null,
            refTorqueNm = null,
            drivetrainLossPct = 0.0
        )
        assertEquals(100.0, hpMaf!!, 0.1)
        assertEquals(178.17, tqMaf!!, 0.1)

        val (_, wheelHp) = PerformanceCalculator.estimateTorqueAndHp(
            rpm = 4000.0,
            mafGramsPerSec = 80.0,
            actualTorquePct = null,
            refTorqueNm = null,
            drivetrainLossPct = 15.0
        )
        assertEquals(85.0, wheelHp!!, 0.1)
    }

    @Test
    fun `Timer0100Tracker records 0 to 100 kmh sprint accurately`() {
        val tracker = Timer0100Tracker()
        assertTrue(tracker.onSpeedSample(0.0, 1000L).armed)
        val running = tracker.onSpeedSample(5.0, 2000L)
        assertTrue(running.running)
        val done = tracker.onSpeedSample(101.0, 8840L)
        assertFalse(done.running)
        assertEquals(6.84, done.bestRunSec!!, 0.001)
    }

    @Test
    fun `WifiTcpTransport reads blocking prompt immediately over TCP`() = runTest {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { sock ->
                val inp = sock.getInputStream().bufferedReader()
                val out = sock.getOutputStream()
                val line = inp.readLine()
                if (line == "010C1") {
                    out.write("410C1AF8\r>".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            }
        }
        val transport = WifiTcpTransport(host = "127.0.0.1", port = server.localPort)
        transport.connect()
        val resp = transport.sendCommand("010C1", timeoutMs = 1500L)
        assertTrue(resp.contains("410C1AF8"))
        transport.disconnect()
        serverJob.join()
        server.close()
    }

    @Test
    fun `Elm327Client and DtcDecoder handle disconnected transport safely without throwing`() = runTest {
        val disconnectedTransport = WifiTcpTransport(host = "127.0.0.1", port = 35000)
        val client = Elm327Client(disconnectedTransport)
        val res = client.execute("03")
        assertTrue(res is ObdCommandResult.Error)
        val report = DtcDecoder.scanAll(client)
        assertTrue(report.confirmed.isEmpty())
        assertTrue(report.pending.isEmpty())
        assertFalse(DtcDecoder.clearDtcs(client))
    }

    @Test
    fun `ObdPollingEngine stop cancels SimulatedObdTransport polling loop promptly without hanging`() {
        kotlinx.coroutines.runBlocking {
            val engineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(SimulatedObdTransport(responseDelayMs = 15L), engineScope)
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(100L)
            kotlinx.coroutines.withTimeout(1000L) {
                engine.stop()
            }
            assertEquals(ConnectionState.Disconnected, engine.connectionState.value)
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `Elm327Client detects CAN when clone adapter reports ATDP Auto and ATDPN AUTO ISO 15765-4 CAN`() = runTest {
        assertTrue(Elm327Client.detectCanProtocol("Auto", "AUTO, ISO 15765-4 (CAN 11/500)"))
        assertTrue(Elm327Client.detectCanProtocol("ISO 15765-4 (CAN 11/500)", "A6"))
        assertTrue(Elm327Client.detectCanProtocol("AUTO", "6"))
        assertFalse(Elm327Client.detectCanProtocol("ISO 9141-2", "3"))
        assertFalse(Elm327Client.detectCanProtocol("ISO 14230-4 (KWP fast)", "5"))

        val commandsSeen = mutableListOf<String>()
        val cloneTransport = object : ObdTransport {
            private val _connected = MutableStateFlow(true)
            override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
            override val endpointLabel: String = "clone-test"
            override suspend fun connect() { _connected.value = true }
            override suspend fun disconnect() { _connected.value = false }
            override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                val cmd = command.trim()
                commandsSeen += cmd
                return when (cmd) {
                    "ATZ" -> "ELM327 v1.5\r"
                    "0100" -> "4100BE1EA813\r"
                    "ATDP" -> "Auto\r"
                    "ATDPN" -> "AUTO, ISO 15765-4 (CAN 11/500)\r"
                    else -> "OK\r"
                }
            }
        }
        val handshake = Elm327Client(cloneTransport).initialize(aggressiveTiming = true)
        assertTrue(handshake.isCanProtocol)
        assertTrue("ATST19" in commandsSeen)
    }

    @Test
    fun `single-PID CAN scheduler polls visible throttle, engine_load, and speed in high-priority cycle`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            val mockTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "single-pid-test"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    return when {
                        cmd == "ATZ" -> "ELM327 v1.5\r"
                        cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        cmd == "0100" -> "41 00 18 18 80 01\r" // 04, 05, 0C, 0D, 11, 20
                        cmd == "0120" -> "41 20 00 00 00 01\r" // 40
                        cmd == "0140" -> "41 40 08 00 00 00\r" // 45 (rel_throttle)
                        cmd.startsWith("01") && cmd.length > 5 -> "NO DATA\r" // force single-PID mode like Avanza ECU
                        cmd.startsWith("010C") -> "41 0C 1A F8\r"
                        cmd.startsWith("010D") -> "41 0D 3C\r"
                        cmd.startsWith("0104") -> "41 04 80\r"
                        cmd.startsWith("0111") -> "41 11 40\r"
                        cmd.startsWith("0145") -> "41 45 50\r"
                        cmd.startsWith("0105") -> "41 05 78\r"
                        else -> "NO DATA\r"
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(mockTransport, scope)
            engine.setActiveSensors(setOf("rpm", "obd_speed", "throttle", "engine_load", "rel_throttle", "coolant"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(180L)
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            val polledPids = cmds.filter { it.startsWith("01") && it.length in 4..5 }.map { it.substring(2, 4) }
            assertTrue("Expected 0C in $polledPids", "0C" in polledPids)
            assertTrue("Expected 0D in $polledPids", "0D" in polledPids)
            assertTrue("Expected 04 in $polledPids", "04" in polledPids)
            assertTrue("Expected 11 in $polledPids", "11" in polledPids)
            assertTrue("Expected 45 in $polledPids", "45" in polledPids)
        }
    }

    @Test
    fun `background recording keys do not steal HIGH-tier frequency from visible gauges`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            val mockTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "bg-sched-test"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    return when {
                        cmd == "ATZ" -> "ELM327 v1.5\r"
                        cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        cmd == "0100" -> "41 00 18 19 80 01\r" // 04, 05, 0C, 0D, 10, 11, 20
                        cmd == "0120" -> "41 20 00 00 00 01\r" // 40
                        cmd == "0140" -> "41 40 48 00 00 00\r" // 42 (voltage), 45 (rel_throttle)
                        cmd.startsWith("01") && cmd.length > 5 -> "NO DATA\r"
                        cmd.startsWith("010C") -> "41 0C 1A F8\r"
                        cmd.startsWith("010D") -> "41 0D 3C\r"
                        cmd.startsWith("0110") -> "41 10 02 80\r"
                        cmd.startsWith("0145") -> "41 45 50\r"
                        cmd.startsWith("0111") -> "41 11 40\r"
                        cmd.startsWith("0142") -> "41 42 36 B0\r"
                        cmd.startsWith("0105") -> "41 05 78\r"
                        else -> "NO DATA\r"
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(mockTransport, scope)
            engine.setPollingProfile(PollingProfile.MAX_PERFORMANCE)
            // Visible HUD wants RPM, RelThr, Voltage; background logger wants AbsThr (11) & Coolant (05)
            engine.setActiveSensors(setOf("rpm", "rel_throttle", "voltage"))
            engine.setBackgroundRecordKeys(setOf("throttle", "coolant"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(220L)
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            val polledPids = cmds.filter { it.startsWith("01") && it.length in 4..5 }.map { it.substring(2, 4) }
            val relThrCount = polledPids.count { it == "45" }
            val redundantThrCount = polledPids.count { it == "11" }
            assertTrue("Expected 45 polled often ($relThrCount) while redundant 11 ($redundantThrCount) stays backgrounded", relThrCount > redundantThrCount)
            assertTrue("Expected visible dynamic secondary 42 (voltage) in $polledPids", "42" in polledPids)
        }
    }

    @Test
    fun `CAN multi-PID recovers from single transient failure, sends trailing 1, and isolates slow 0142 voltage`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            var droppedOnce = false
            val canTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "can-multipid-test"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    return when {
                        cmd == "ATZ" -> "ELM327 v1.5\r"
                        cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        cmd == "0100" -> "41 00 18 18 80 01\r" // 04, 05, 0C, 0D, 11, 20
                        cmd == "0120" -> "41 20 00 00 00 01\r" // 40
                        cmd == "0140" -> "41 40 48 00 00 00\r" // 42 (voltage), 45 (rel_throttle)
                        cmd == "010C451" -> {
                            if (!droppedOnce) {
                                droppedOnce = true
                                "NO DATA\r"
                            } else {
                                "410C0D3E4528\r"
                            }
                        }
                        cmd == "01040D1" -> "4104590D3C\r"
                        cmd.startsWith("010C") -> "41 0C 0D 3E\r"
                        cmd.startsWith("0145") -> "41 45 28\r"
                        cmd.startsWith("0104") -> "41 04 59\r"
                        cmd.startsWith("010D") -> "41 0D 3C\r"
                        cmd.startsWith("0142") -> "41 42 37 15\r"
                        else -> "NO DATA\r"
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(canTransport, scope)
            engine.setPollingProfile(PollingProfile.MAX_PERFORMANCE)
            engine.setActiveSensors(setOf("rpm", "engine_load", "obd_speed", "rel_throttle", "voltage"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(160L)
            val snap = engine.liveValues.value
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            assertTrue("Expected ATCAF1 during init in $cmds", "ATCAF1" in cmds)
            assertTrue("Expected reliable ATAT1 during init in $cmds", "ATAT1" in cmds)
            assertFalse("Expected ATAT2 removed to prevent ELM327 v1.5 NO DATA drops", "ATAT2" in cmds)
            val pack1Count = cmds.count { it == "010C451" }
            val pack2Count = cmds.count { it == "01040D1" }
            val voltCount = cmds.count { it == "01421" }
            assertTrue("Expected CAN multi-PID recovery after transient drop, got 010C451=$pack1Count", pack1Count >= 4)
            assertTrue("Expected CAN load+speed pack 01040D1=$pack2Count", pack2Count >= 4)
            assertEquals("Expected slow 0142 polled once during 160ms cooldown window", 1, voltCount)
            assertTrue((snap["rpm"] ?: 0.0) > 500.0)
            assertTrue((snap["engine_load"] ?: 0.0) > 20.0)
            assertTrue((snap["voltage"] ?: 0.0) > 12.0)
        }
    }

    @Test
    fun `custom PID with headerHex switches CAN header to 7C0, decodes Mode 21 fuel level, and restores 7DF`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            val meterTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "meter-7c0-test"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    return when {
                        cmd == "ATZ" -> "ELM327 v1.5\r"
                        cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        cmd == "0100" -> "41 00 18 18 00 00\r" // 04, 05, 0C, 0D
                        cmd == "2129" -> "61 29 4A\r" // 0x4A = 74 -> 74 * 0.5 = 37.0 L
                        cmd.startsWith("010C") -> "41 0C 0D 3E\r"
                        cmd.startsWith("010D") -> "41 0D 28\r"
                        cmd.startsWith("0104") -> "41 04 59\r"
                        else -> "NO DATA\r"
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(meterTransport, scope)
            engine.setCustomPids(
                listOf(
                    com.apexobd.core.formula.CustomPidDefinition(
                        key = "custom_meter_fuel",
                        name = "Cluster Fuel Level",
                        shortName = "Fuel7C0",
                        modeAndPidHex = "2129",
                        formula = "A*0.5",
                        minValue = 0.0,
                        maxValue = 50.0,
                        unit = "L",
                        headerHex = "7C0"
                    )
                )
            )
            engine.setActiveSensors(setOf("rpm", "custom_meter_fuel"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(140L)
            val snap = engine.liveValues.value
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            assertTrue("Expected ATSH7C0 in $cmds", "ATSH7C0" in cmds)
            assertTrue("Expected ATCRA7C8 in $cmds", "ATCRA7C8" in cmds)
            assertTrue("Expected 2129 in $cmds", "2129" in cmds)
            assertTrue("Expected ATCRA restore in $cmds", "ATCRA" in cmds)
            assertTrue("Expected ATSH7DF restore in $cmds", "ATSH7DF" in cmds)
            assertEquals(37.0, snap["custom_meter_fuel"] ?: -1.0, 0.01)
        }
    }

    @Test
    fun `EcuDiscoveryDumper probes 7C0 combination meter, walks 7E0 Mode 21 bitmasks, and restores 7DF`() = runTest {
        val cmds = mutableListOf<String>()
        val mockTransport = object : ObdTransport {
            private val _connected = MutableStateFlow(true)
            override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
            override val endpointLabel: String = "deep-scan-7c0"
            override suspend fun connect() { _connected.value = true }
            override suspend fun disconnect() { _connected.value = false }
            override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                val cmd = command.trim()
                cmds += cmd
                return when (cmd) {
                    "ATZ" -> "ELM327 v1.5\r"
                    "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                    "ATDPN" -> "A6\r"
                    "0100" -> "41 00 18 00 00 00\r"
                    "2100" -> "61 00 80 00 00 00\r"
                    "2129" -> "61 29 50\r"
                    else -> if (cmd.startsWith("AT")) "OK\r" else "NO DATA\r"
                }
            }
        }
        val report = com.apexobd.core.protocol.EcuDiscoveryDumper.runFullReadOnlyScan(mockTransport)
        assertTrue("Expected 7C0 header probe in $cmds", "ATSH7C0" in cmds)
        assertTrue("Expected 7C8 reply filter in $cmds", "ATCRA7C8" in cmds)
        assertTrue("Expected 750 body probe in $cmds", "ATSH750" in cmds)
        assertEquals("ATSH7DF", cmds.last())
        assertTrue(report.contains("COMBINATION METER 7C0"))
        assertTrue(report.contains("7C0 2129 -> 61 29 50"))
    }

    @Test
    fun `ToyotaMeterProtocol decodes real 2024 Avanza 7C0 fuel level, odometer, and cluster channels accurately`() {
        assertEquals(18.5, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeFuelLiters(listOf("612925"))!!, 0.001)
        assertEquals(null, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeFuelLiters(listOf("6129FF")))
        assertEquals(null, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeFuelLiters(listOf("7F2112")))

        val odoLines = listOf("013", "0:61E10013FF00", "1:0003BA0000770D", "2:26112050153200")
        assertEquals(30477L, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeOdometerKm(odoLines))
        assertEquals(0.0, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeClusterSpeedKmh(listOf("612100"))!!, 0.001)
        assertEquals(750.0, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeClusterRpm(listOf("61220F"))!!, 0.001)
        assertEquals(56.5, com.apexobd.core.protocol.ToyotaMeterProtocol.decodeClusterCoolantC(listOf("612371"))!!, 0.001)
    }

    @Test
    fun `ObdPollingEngine auto-discovers 7C0 fuel level and odometer on Avanza without Mode 01 2F`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            var activeHeader = "7DF"
            val avanzaTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "avanza-7c0-test"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    if (cmd.startsWith("ATSH")) {
                        activeHeader = cmd.removePrefix("ATSH")
                        return "OK\r"
                    }
                    return when {
                        cmd == "ATZ" -> "ELM327 v1.5\r"
                        cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        activeHeader == "7C0" && cmd == "2129" -> "612925\r"
                        activeHeader == "7C0" && cmd == "21E1" -> "013\r0:61E10013FF00\r1:0003BA0000770D\r2:26112050153200\r"
                        activeHeader == "7C0" && cmd == "2121" -> "612128\r"
                        activeHeader == "7C0" && cmd == "2122" -> "61220F\r"
                        activeHeader == "7C0" && cmd == "2123" -> "612371\r"
                        cmd == "0100" -> "4100BE1EA813\r"
                        cmd == "0120" -> "41209005B015\r" // No 2F!
                        cmd == "0140" -> "41405EDC8000\r"
                        cmd == "010C451" -> "410C0C314500\r"
                        cmd == "01040D1" -> "4104580D00\r"
                        cmd.startsWith("010C") -> "410C0C31\r"
                        cmd.startsWith("0104") -> "410458\r"
                        cmd.startsWith("010D") -> "410D00\r"
                        cmd.startsWith("0145") -> "414500\r"
                        else -> "NO DATA\r"
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(avanzaTransport, scope)
            engine.setVehicleProfile(
                com.apexobd.core.model.VehicleProfile(
                    id = 1L,
                    name = "Avanza 2024",
                    tankCapacityLiters = 43.0,
                    estimatedRemainingFuelLiters = 10.0,
                    allTimeOdometerKm = 30000.0
                ),
                resetFuelSession = true
            )
            engine.setActiveSensors(setOf("rpm", "fuel_level", "fuel_remaining", "ecu_odometer"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(140L)
            val snap = engine.liveValues.value
            val avail = engine.availableSensorKeys.value
            val odo = engine.ecuOdometerKm.value
            val liters = engine.ecuFuelLiters.value
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            assertTrue("fuel_level should be unlocked via 7C0 2129 in $avail", "fuel_level" in avail)
            assertTrue("ecu_odometer should be unlocked via 7C0 21E1 in $avail", "ecu_odometer" in avail)
            assertTrue("cluster_speed should be unlocked in $avail", "cluster_speed" in avail)
            assertEquals(null, snap["cluster_speed"])
            assertEquals(null, snap["cluster_rpm"])
            assertEquals(null, snap["cluster_coolant"])
            assertEquals(18.5, liters!!, 0.01)
            assertEquals(30477.0, odo!!, 0.01)
            assertEquals(18.5, snap["fuel_remaining"]!!, 0.05)
            assertEquals((18.5 / 43.0) * 100.0, snap["fuel_level"]!!, 0.1)
            assertEquals(30477.0, snap["ecu_odometer"]!!, 0.01)
            assertTrue("Expected ATSH7DF restore after 7C0 discovery", "ATSH7DF" in cmds)
        }
    }

    @Test
    fun `sticky ATCRA ELM327 v1_5 clone recovers Mode 01 streaming via ATCRA7E8 fallback after 7C0 discovery`() {
        kotlinx.coroutines.runBlocking {
            val cmds = java.util.concurrent.CopyOnWriteArrayList<String>()
            var activeHeader = "7DF"
            var activeRxFilter: String? = null // null = auto (7E8)
            val stickyCloneTransport = object : ObdTransport {
                private val _connected = MutableStateFlow(false)
                override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
                override val endpointLabel: String = "sticky-atcra-clone"
                override suspend fun connect() { _connected.value = true }
                override suspend fun disconnect() { _connected.value = false }
                override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                    val cmd = command.trim()
                    cmds += cmd
                    return when {
                        cmd == "ATZ" -> {
                            activeHeader = "7DF"
                            activeRxFilter = null
                            "ELM327 v1.5\r"
                        }
                        cmd.startsWith("ATSH") -> {
                            activeHeader = cmd.removePrefix("ATSH")
                            "OK\r"
                        }
                        // Reproduce ELM327 v1.5 clone: parameterless ATCRA returns '?', leaving filter stuck on 7C8!
                        cmd == "ATCRA" -> "?\r"
                        cmd.startsWith("ATCRA") && cmd.length == 8 -> {
                            activeRxFilter = cmd.removePrefix("ATCRA")
                            "OK\r"
                        }
                        // Clone also ignores ATCM/ATCF/ATAR once ATCRAhhh is set
                        cmd == "ATCM000" || cmd == "ATCF000" || cmd == "ATAR" -> "OK\r"
                        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
                        cmd == "ATDPN" -> "A6\r"
                        cmd.startsWith("AT") -> "OK\r"
                        activeHeader == "7C0" && (activeRxFilter == null || activeRxFilter == "7C8") -> when (cmd) {
                            "2129" -> "612925\r"
                            "21E1" -> "013\r0:61E10013FF00\r1:0003BA0000770D\r2:26112050153200\r"
                            else -> "NO DATA\r"
                        }
                        // Engine ECU replies on 7E8: blocked whenever activeRxFilter is stuck on 7C8!
                        activeHeader == "7DF" && (activeRxFilter == null || activeRxFilter == "7E8") -> when {
                            cmd.startsWith("0100") -> "4100BE1EA813\r"
                            cmd == "0120" -> "41209005B015\r"
                            cmd == "0140" -> "41405EDC8000\r"
                            cmd.startsWith("010C") -> "410C0C30\r" // 780 RPM
                            cmd.startsWith("0105") -> "410578\r"   // 80 °C
                            cmd.startsWith("0142") -> "414236B0\r" // 14.0 V
                            else -> "NO DATA\r"
                        }
                        else -> "NO DATA\r"
                    }
                }
            }

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val engine = ObdPollingEngine(stickyCloneTransport, scope)
            engine.setActiveSensors(setOf("rpm", "coolant", "voltage", "fuel_level"))
            engine.start()
            var waited = 0
            while (engine.connectionState.value !is ConnectionState.Connected && waited < 60) {
                kotlinx.coroutines.delay(50L)
                waited++
            }
            assertTrue(engine.connectionState.value is ConnectionState.Connected)
            kotlinx.coroutines.delay(250L)
            val snap = engine.liveValues.value
            kotlinx.coroutines.withTimeout(1000L) { engine.stop() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            assertTrue("Expected ATCM000 fallback after ATCRA ?", "ATCM000" in cmds)
            assertTrue("Expected ATAR restore attempt", "ATAR" in cmds)
            assertTrue("Expected ATCRA7E8 recovery when 01001 failed", "ATCRA7E8" in cmds)
            assertEquals(780.0, snap["rpm"] ?: -1.0, 1.0)
            assertEquals(80.0, snap["coolant"] ?: -1.0, 0.5)
        }
    }

    @Test
    fun `FuelFloatSloshFilter rejects moving cornering slosh spikes and snaps cleanly on verified stationary refuel`() {
        val filter = com.apexobd.core.protocol.FuelFloatSloshFilter()
        assertEquals(16.5, filter.seed(16.5, 43.0), 0.001)

        // Wild moving slosh (8.5 L and 21.5 L like real Session #18) must be rejected completely
        assertEquals(16.5, filter.onRawSample(8.5, speedKmh = 35.0, accelG = 0.05, tankCapacityLiters = 43.0), 0.001)
        assertEquals(16.5, filter.onRawSample(21.5, speedKmh = 40.0, accelG = -0.04, tankCapacityLiters = 43.0), 0.001)
        // High G braking/acceleration while moving is ignored even for smaller swings
        assertEquals(16.5, filter.onRawSample(14.5, speedKmh = 30.0, accelG = -0.25, tankCapacityLiters = 43.0), 0.001)

        // Gradual real burn at stationary stops tracks downward smoothly
        var lvl = 16.5
        repeat(6) {
            lvl = filter.onRawSample(15.5, speedKmh = 0.0, accelG = 0.0, tankCapacityLiters = 43.0)
        }
        assertTrue("Expected smooth convergence toward 15.5 L, got $lvl", lvl in 15.45..16.05)

        // Single stationary jump (+18 L) waits for confirmation; 2nd consecutive stationary sample snaps immediately
        val afterFirst = filter.onRawSample(34.0, speedKmh = 0.0, accelG = 0.0, tankCapacityLiters = 43.0)
        assertTrue("Single sample should not immediately snap to 34 L, got $afterFirst", afterFirst < 20.0)
        val afterSecond = filter.onRawSample(34.0, speedKmh = 0.0, accelG = 0.0, tankCapacityLiters = 43.0)
        assertEquals(34.0, afterSecond, 0.001)
    }
}
