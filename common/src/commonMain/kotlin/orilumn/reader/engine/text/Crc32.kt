package orilumn.reader.engine.text

/**
 * Minimal portable CRC-32 (IEEE 802.3, polynomial 0x04C11DB7 reflected → 0xEDB88320) with the same
 * byte-feed semantics as the `java.util.zip.CRC32` tables used by [LayoutParamKey] before the S19
 * migration: standard init (0xFFFFFFFF), per-byte reflected update, standard final inversion.
 * Implemented with a lazily-built 256-entry table, no JVM dependency, so it stays usable once the
 * module grows iOS/desktop targets.
 */
internal object Crc32 {

    /** Initial register value of the standard CRC-32. */
    const val INIT: Int = 0xFFFFFFFF.toInt()

    private val table: IntArray = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
            t[n] = c
        }
    }

    /** Feeds one byte ([byte] as 0..255) into [crc] and returns the updated register. */
    fun update(crc: Int, byte: Int): Int = table[(crc xor byte) and 0xFF] xor (crc ushr 8)

    /** Feeds the low 32 bits of [v] big-endian (4 bytes) — mirrors the original 4-byte-per-number feed. */
    fun update4(crc: Int, v: Long): Int {
        val b0 = ((v shr 24) and 0xFF).toInt()
        val b1 = ((v shr 16) and 0xFF).toInt()
        val b2 = ((v shr 8) and 0xFF).toInt()
        val b3 = (v and 0xFF).toInt()
        return update(update(update(update(crc, b0), b1), b2), b3)
    }

    /** Feeds a string as 4 bytes per UTF-16 code unit plus a 4-byte zero terminator (mirrors the
     * original feed and distinctness of `"ab"` vs `"a"` + `"b"`). */
    fun updateString(crc: Int, s: String): Int {
        var c = crc
        for (ch in s) c = update4(c, ch.code.toLong())
        return update4(c, 0L)
    }

    /** Produces the final unsigned CRC-32 value from the register (standard final inversion). */
    fun finish(crc: Int): Long = ((crc xor 0xFFFFFFFF.toInt()).toLong()) and 0xFFFFFFFFL
}