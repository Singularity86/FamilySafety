package com.example.familysafety.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test
    fun `round trip, lowercase out, either case in`() {
        val bytes = byteArrayOf(0, 1, 0x7f, -0x80, -1, 0x0a)
        assertEquals("00017f80ff0a", bytes.toHex())
        assertArrayEquals(bytes, "00017f80ff0a".hexToBytes())
        assertArrayEquals(bytes, "00017F80FF0A".hexToBytes())
        assertEquals("", ByteArray(0).toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd length is refused`() {
        "abc".hexToBytes()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-hex character is refused`() {
        "0g".hexToBytes()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sign is not a digit`() {
        // The old per-file helpers parsed "-1" as 0xff.
        "-1".hexToBytes()
    }
}
