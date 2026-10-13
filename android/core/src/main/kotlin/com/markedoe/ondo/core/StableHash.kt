package com.markedoe.ondo.core

/**
 * Deterministic hash (FNV-1a 64-bit over the UTF-8 bytes), used for ids that are persisted
 * (favourites, history…). Identical to the Swift implementation, so ids match across platforms.
 */
object StableHash {
    private const val OFFSET_BASIS = 0xcbf29ce484222325uL
    private const val PRIME = 0x00000100000001B3uL

    fun fnv1a(string: String): ULong {
        var hash = OFFSET_BASIS
        fun mix(byte: Int) {
            hash = (hash xor byte.toULong()) * PRIME
        }
        var i = 0
        val n = string.length
        while (i < n) {
            val c = string[i].code
            when {
                c < 0x80 -> mix(c)
                c < 0x800 -> {
                    mix(0xC0 or (c shr 6))
                    mix(0x80 or (c and 0x3F))
                }
                Character.isHighSurrogate(string[i]) && i + 1 < n && Character.isLowSurrogate(string[i + 1]) -> {
                    val cp = Character.toCodePoint(string[i], string[i + 1])
                    i++
                    mix(0xF0 or (cp shr 18))
                    mix(0x80 or ((cp shr 12) and 0x3F))
                    mix(0x80 or ((cp shr 6) and 0x3F))
                    mix(0x80 or (cp and 0x3F))
                }
                Character.isSurrogate(string[i]) -> mix('?'.code) // unpaired surrogate, as String.encodeToByteArray
                else -> {
                    mix(0xE0 or (c shr 12))
                    mix(0x80 or ((c shr 6) and 0x3F))
                    mix(0x80 or (c and 0x3F))
                }
            }
            i++
        }
        return hash
    }

    /** Lowercase hexadecimal, without leading zeros (Swift `String(_, radix: 16)`). */
    fun hex(string: String): String = fnv1a(string).toString(16)
}
