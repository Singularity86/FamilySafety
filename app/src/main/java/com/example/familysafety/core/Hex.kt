package com.example.familysafety.core

/*
 * Lowercase hex, the encoding of every key, signature and hash on the wire (spec §2.4).
 * One copy for the whole app; there used to be one per file that needed it.
 */

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
    }
    return String(out)
}

/** Inverse of [toHex]; either case. Throws [IllegalArgumentException] on odd length or a non-hex character. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "not hex" }
        ((hi shl 4) or lo).toByte()
    }
}
