package com.apexobd.core.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElmResponseBufferTest {

    @Test
    fun `appendByte accumulates ASCII payload until prompt and resets buffer`() {
        val buffer = ElmResponseBuffer()
        val first = "41 0C 1A F8\r\r>"
        var completed: String? = null
        for (ch in first) {
            val out = buffer.appendByte(ch.code)
            if (out != null) completed = out
        }
        assertEquals("41 0C 1A F8\r\r", completed)

        // Buffer must be empty for the next response frame
        assertNull(buffer.appendByte('4'.code))
        assertNull(buffer.appendByte('1'.code))
        assertEquals("41", buffer.appendByte('>'.code))
    }

    @Test
    fun `appendBytes skips NUL padding and handles partial reads`() {
        val buffer = ElmResponseBuffer()
        val chunk1 = byteArrayOf(0x00, '4'.code.toByte(), '1'.code.toByte(), ' '.code.toByte(), '0'.code.toByte())
        val chunk2 = byteArrayOf('5'.code.toByte(), ' '.code.toByte(), '8'.code.toByte(), '2'.code.toByte(), 0x00, '>'.code.toByte(), 'X'.code.toByte())

        assertNull(buffer.appendBytes(chunk1))
        val out = buffer.appendBytes(chunk2, length = 6)
        assertEquals("41 05 82", out)

        buffer.reset()
        assertNull(buffer.appendByte('A'.code))
    }

    @Test
    fun `formatCommandBytes trims whitespace and appends CR`() {
        assertArrayEquals("010C\r".toByteArray(Charsets.US_ASCII), ElmResponseBuffer.formatCommandBytes("  010C  "))
    }

    @Test
    fun `ObdConnectionType covers all supported transport mechanisms`() {
        assertEquals(
            listOf("Wi-Fi", "BT", "BLE", "USB"),
            ObdConnectionType.entries.map { it.shortLabel }
        )
    }
}
