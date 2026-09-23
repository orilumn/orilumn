package orilumn.reader.engine.text

/**
 * A stable, reproducible key that uniquely identifies a "layout parameter combination" — i.e. the
 * exact set of inputs that determine how a chapter paginates. Two chapters with the same
 * [LayoutParamKey] will produce identical pagination tables (identical shaping inputs →
 * identical line breaks → identical page cuts).
 *
 * Only fields that affect **shaping or pagination** are included. Purely visual fields
 * (foreground/background color) are intentionally left out — changing theme colors must not
 * invalidate the pagination cache.
 *
 * The [hash] is a 64-bit CRC-32 over the serialized fields (standard IEEE 802.3, byte-for-byte
 * identical to the pre-S19 `java.util.zip.CRC32` feed, see [Crc32]). Good enough for a
 * content-addressable cache filename; the collision probability at this scale (a few dozen
 * parameter combinations per book) is negligible.
 */
data class LayoutParamKey(
    val bodyPx: Float,
    val lineSpacing: Float,
    val firstLineIndentEm: Float,
    val letterSpacingEm: Float,
    val paragraphSpacingPx: Int,
    val paragraphGapScale: Float,
    val fontBody: String,
    val fontTitle: String,
    val fontCode: String,
    val useOriginalStyle: Boolean,
    val contentW: Int,
    val contentH: Int,
    val userCssHash: Int,
) {
    /** Deterministic 64-bit hash over all layout-affecting fields. */
    fun hash(): Long {
        var crc = Crc32.INIT
        // Field order matters: fixed schema → fixed hash.
        crc = Crc32.update4(crc, bodyPx.bits())
        crc = Crc32.update4(crc, lineSpacing.bits())
        crc = Crc32.update4(crc, firstLineIndentEm.bits())
        crc = Crc32.update4(crc, letterSpacingEm.bits())
        crc = Crc32.update4(crc, paragraphSpacingPx.bits())
        crc = Crc32.update4(crc, paragraphGapScale.bits())
        crc = Crc32.updateString(crc, fontBody)
        crc = Crc32.updateString(crc, fontTitle)
        crc = Crc32.updateString(crc, fontCode)
        crc = Crc32.update4(crc, if (useOriginalStyle) 1L else 0L)
        crc = Crc32.update4(crc, contentW.bits())
        crc = Crc32.update4(crc, contentH.bits())
        crc = Crc32.update4(crc, userCssHash.bits())
        return Crc32.finish(crc)
    }

    companion object {
        /** Builds a [LayoutParamKey] from a [TypographicProfile] + the physical page dimensions
         *  and an optional hash of any user-provided CSS overrides. */
        fun fromProfile(
            profile: TypographicProfile,
            contentW: Int,
            contentH: Int,
            userCssHash: Int = 0,
        ): LayoutParamKey = LayoutParamKey(
            bodyPx = profile.bodyPx,
            lineSpacing = profile.lineSpacing,
            firstLineIndentEm = profile.firstLineIndentEm,
            letterSpacingEm = profile.letterSpacingEm,
            paragraphSpacingPx = profile.paragraphSpacingPx,
            paragraphGapScale = profile.paragraphGapScale,
            fontBody = profile.fontBody,
            fontTitle = profile.fontTitle,
            fontCode = profile.fontCode,
            useOriginalStyle = profile.useOriginalStyle,
            contentW = contentW,
            contentH = contentH,
            userCssHash = userCssHash,
        )
    }
}

private fun Float.bits(): Long = this.toBits().toLong()

private fun Int.bits(): Long = this.toLong() and 0xFFFFFFFFL