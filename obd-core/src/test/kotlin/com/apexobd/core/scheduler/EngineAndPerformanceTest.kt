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
            val cmds = java.util.Collections.synchronizedList(mutableListOf<String>())
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
}
