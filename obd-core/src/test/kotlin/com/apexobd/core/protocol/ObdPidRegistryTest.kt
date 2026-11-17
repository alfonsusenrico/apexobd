package com.apexobd.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidRegistryTest {

    @Test
    fun `decodeBitmask extracts exact PIDs from SAE J1979 4-byte mask`() {
        // BE 3F A8 13 -> bit 31..0 from base 0x00
        // 0xBE = 1011 1110 -> 01, 03, 04, 05, 06, 07
        // 0x3F = 0011 1111 -> 0B, 0C, 0D, 0E, 0F, 10
        // 0xA8 = 1010 1000 -> 11, 13, 15
        // 0x13 = 0001 0011 -> 1C, 1F, 20
        val pids = ObdPidRegistry.decodeBitmask(0x00, listOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue("01" in pids)
        assertTrue("05" in pids)
        assertTrue("0C" in pids)
        assertTrue("10" in pids)
        assertTrue("11" in pids)
        assertTrue("20" in pids)
        assertEquals(18, pids.size)
    }

    @Test
    fun `decodeMode01Response accurately decodes owner core PIDs`() {
        val rpm = ObdPidRegistry.decodeMode01Response("0C", listOf("41 0C 1A F8"))
        assertEquals(1726.0, rpm!!.value, 0.01)

        val coolant = ObdPidRegistry.decodeMode01Response("05", listOf("41 05 82"))
        assertEquals(90.0, coolant!!.value, 0.01)

        val iat = ObdPidRegistry.decodeMode01Response("0F", listOf("41 0F 4A"))
        assertEquals(34.0, iat!!.value, 0.01)

        val maf = ObdPidRegistry.decodeMode01Response("10", listOf("41 10 0B 18"))
        assertEquals(28.4, maf!!.value, 0.01)

        val timing = ObdPidRegistry.decodeMode01Response("0E", listOf("41 0E A1"))
        assertEquals(16.5, timing!!.value, 0.01)

        val volt = ObdPidRegistry.decodeMode01Response("42", listOf("41 42 37 28"))
        assertEquals(14.12, volt!!.value, 0.01)
    }

    @Test
    fun `decodeMode01Response returns null on truncated or mismatched lines`() {
        assertNull(ObdPidRegistry.decodeMode01Response("0C", listOf("NO DATA")))
        assertNull(ObdPidRegistry.decodeMode01Response("0C", listOf("41 0C 1A")))
        assertNull(ObdPidRegistry.decodeMode01Response("0C", listOf("41 05 82")))
    }

    @Test
    fun `decodeMultiMode01Response extracts multiple CAN PIDs from single frame`() {
        val readings = ObdPidRegistry.decodeMultiMode01Response(
            listOf("41 0C 1A F8 0D 54 11 80 10 0B 18")
        )
        val byKey = readings.associateBy { it.key }
        assertEquals(1726.0, byKey["rpm"]!!.value, 0.01)
        assertEquals(84.0, byKey["obd_speed"]!!.value, 0.01)
        assertEquals(50.19, byKey["throttle"]!!.value, 0.1)
        assertEquals(28.4, byKey["maf"]!!.value, 0.01)
    }

    @Test
    fun `decodeAllMode01Responses extracts both O2 voltage and fuel trim from 0114`() {
        val readings = ObdPidRegistry.decodeAllMode01Responses("14", listOf("41140C9B")).associateBy { it.key }
        assertEquals(0.06, readings["o2_b1s1_v"]!!.value, 0.001)
        assertEquals(21.09, readings["o2_b1s1_stft"]!!.value, 0.05)
    }
}
