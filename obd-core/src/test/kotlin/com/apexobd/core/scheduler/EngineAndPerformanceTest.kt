package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.transport.SimulatedObdTransport
import com.apexobd.core.transport.WifiTcpTransport
import kotlinx.coroutines.Dispatchers
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
}
