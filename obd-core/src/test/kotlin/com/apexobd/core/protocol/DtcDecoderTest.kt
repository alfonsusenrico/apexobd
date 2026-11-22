package com.apexobd.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    @Test
    fun `decodeTwoByteDtc handles P, C, B, and U prefixes`() {
        assertEquals("P0171", DtcDecoder.decodeTwoByteDtc(0x01, 0x71))
        assertEquals("C0035", DtcDecoder.decodeTwoByteDtc(0x40, 0x35))
        assertEquals("B1001", DtcDecoder.decodeTwoByteDtc(0x90, 0x01))
        assertEquals("U0100", DtcDecoder.decodeTwoByteDtc(0xC1, 0x00))
        assertNull(DtcDecoder.decodeTwoByteDtc(0x00, 0x00))
    }

    @Test
    fun `parseDtcResponse extracts CAN Mode 03 and Mode 07 fault codes`() {
        val confirmed = DtcDecoder.parseDtcResponse(listOf("43 02 01 71 04 20"), 0x43, DtcCategory.CONFIRMED)
        assertEquals(listOf("P0171", "P0420"), confirmed.map { it.code })

        val pending = DtcDecoder.parseDtcResponse(listOf("47 01 03 01"), 0x47, DtcCategory.PENDING)
        assertEquals(listOf("P0301"), pending.map { it.code })
    }

    @Test
    fun `parseReadiness decodes MIL status, DTC count, and monitors`() {
        // C = 0x65 (Catalyst, EVAP, O2 Sensor, O2 Heater supported), D = 0x20 (O2 Sensor incomplete)
        val status = DtcDecoder.parseReadiness(listOf("41 01 82 07 65 20"))!!
        assertTrue(status.milOn)
        assertEquals(2, status.dtcCount)
        assertFalse(status.compressionIgnition)
        assertTrue(status.monitors.first { it.name == "Misfire" }.ready)
        assertTrue(status.monitors.first { it.name == "Catalyst" }.ready)
        assertFalse(status.monitors.first { it.name == "O2 Sensor" }.ready)
    }

    @Test
    fun `parseVin extracts 17-char VIN from ISO-TP multi-frame Mode 09 response`() {
        val lines = listOf(
            "014",
            "0: 49 02 01 4A 54 44",
            "1: 4B 4E 33 44 55 35 41",
            "2: 30 31 32 33 34 35 36"
        )
        assertEquals("JTDKN3DU5A0123456", DtcDecoder.parseVin(lines))
    }

    @Test
    fun `parseDtcResponse decodes ISO-TP multi-frame and multi-ECU Mode 03 responses`() {
        val multiFrame = listOf(
            "00A",
            "0: 43 04 01 71 01 74",
            "1: 03 00 04 20 00 00"
        )
        val decoded = DtcDecoder.parseDtcResponse(multiFrame, 0x43, DtcCategory.CONFIRMED)
        assertEquals(listOf("P0171", "P0174", "P0300", "P0420"), decoded.map { it.code })

        val multiEcu = listOf(
            "43 01 01 71",
            "43 01 04 20"
        )
        val decodedMultiEcu = DtcDecoder.parseDtcResponse(multiEcu, 0x43, DtcCategory.CONFIRMED)
        assertEquals(listOf("P0171", "P0420"), decodedMultiEcu.map { it.code })
    }

    @Test
    fun `scanAll succeeds on 2014 Avanza K3-VE where 03 and 0101 return NO DATA but 07 and 2106 respond`() = kotlinx.coroutines.test.runTest {
        val k3veTransport = object : com.apexobd.core.transport.ObdTransport {
            private val _connected = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
            override val endpointLabel: String = "k3ve-dtc-test"
            override suspend fun connect() { _connected.value = true }
            override suspend fun disconnect() { _connected.value = false }
            override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                return when (command.trim()) {
                    "03" -> "NO DATA\r"
                    "07" -> "4700\r"
                    "0A" -> "NO DATA\r"
                    "0101" -> "NO DATA\r"
                    "2106" -> "00C\r0:61060007E100\r1:03060000000000\r"
                    else -> "NO DATA\r"
                }
            }
        }
        val client = com.apexobd.core.elm.Elm327Client(k3veTransport)
        val report = DtcDecoder.scanAll(client)
        assertTrue(report.ecuResponded)
        assertTrue(report.confirmed.isEmpty())
        assertTrue(report.pending.isEmpty())
        assertTrue(report.permanent.isEmpty())
        assertFalse(report.readiness!!.milOn)
        assertEquals(0, report.readiness!!.dtcCount)
        assertEquals(7, report.readiness!!.monitors.size)
        assertTrue(report.readiness!!.monitors.all { it.ready })
    }

    @Test
    fun `clearDtcs falls back to physical 7E0 header when functional 04 returns NO DATA`() = kotlinx.coroutines.test.runTest {
        val commands = mutableListOf<String>()
        var header = "7DF"
        val transport = object : com.apexobd.core.transport.ObdTransport {
            private val _connected = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
            override val endpointLabel: String = "clear-fallback-test"
            override suspend fun connect() { _connected.value = true }
            override suspend fun disconnect() { _connected.value = false }
            override suspend fun sendCommand(command: String, timeoutMs: Long): String {
                val cmd = command.trim()
                commands += cmd
                return when {
                    cmd == "ATSH7E0" -> { header = "7E0"; "OK\r" }
                    cmd == "ATSH7DF" -> { header = "7DF"; "OK\r" }
                    cmd == "04" && header == "7DF" -> "NO DATA\r"
                    cmd == "04" && header == "7E0" -> "44\r"
                    else -> "OK\r"
                }
            }
        }
        val client = com.apexobd.core.elm.Elm327Client(transport)
        assertTrue(DtcDecoder.clearDtcs(client))
        assertEquals("7DF", header)
        assertTrue("ATSH7E0" in commands)
    }
}
