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
}
