package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.transport.SimulatedObdTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAndPerformanceTest {

    @Test
    fun `SimulatedObdTransport completes handshake, bitmask discovery, and DTC clear`() = runTest {
        val transport = SimulatedObdTransport(responseDelayMs = 0L)
        transport.connect()
        val client = Elm327Client(transport)

        val info = client.initialize()
        assertEquals("ELM327 v1.5", info.adapterVersion)
        assertTrue(info.protocolName.contains("CAN"))

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
            refTorqueNm = null
        )
        assertEquals(100.0, hpMaf!!, 0.1)
        assertEquals(178.17, tqMaf!!, 0.1)
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
}
