package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * LayoutParamKey must change whenever the first-line indent changes. BookDocumentController.prepareFor
 * reuses the cached chapter prepare (whose styleMap embeds `textIndentPx`) unless the param hash
 * differs — an indent-only slider change therefore MUST produce a new hash, or the light re-layout
 * keeps shaping with the stale indent (Rust 简介 regression: 0→1 applies, every further change stuck).
 *
 * Migrated from app Robolectric to common jvmTest with S19: `TypographicProfile.build` is now pure
 * (no TextPaint), so no Android runtime is needed.
 */
class LayoutParamKeyTest {

    private fun hash(settings: ReaderSettings): Long {
        val p = TypographicProfile.build(settings)
        return LayoutParamKey.fromProfile(p, 1920, 2400).hash()
    }

    @Test
    fun `first-line indent changes the param hash`() {
        val a = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 0.0))
        val b = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 1.0))
        assertNotEquals(a, b)
    }

    @Test
    fun `same indent yields the same hash`() {
        val a = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 2.0))
        val b = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 2.0))
        assertEquals(a, b)
    }

    @Test
    fun `portable crc32 is byte-identical to java util zip crc32`() {
        val base = LayoutParamKey(
            bodyPx = 18.5f, lineSpacing = 1.5f, firstLineIndentEm = 2f, letterSpacingEm = 0f,
            paragraphSpacingPx = 28, paragraphGapScale = 1f,
            fontBody = "霞鹜文楷", fontTitle = "LXGW WenKai", fontCode = "",
            useOriginalStyle = false, contentW = 1080, contentH = 1920, userCssHash = 123456789,
        )
        assertEquals(javaCrc32(base), base.hash())

        val emptyFonts = base.copy(fontBody = "", fontTitle = "", fontCode = "")
        assertEquals(javaCrc32(emptyFonts), emptyFonts.hash())

        val flipped = base.copy(useOriginalStyle = true, contentW = 0, userCssHash = 0)
        assertEquals(javaCrc32(flipped), flipped.hash())
    }

    /** Reference: feed the same byte stream through java.util.zip.CRC32 (the pre-S19 implementation). */
    private fun javaCrc32(k: LayoutParamKey): Long {
        val j = java.util.zip.CRC32()
        fun Float.feed4() {
            val v = toBits().toLong()
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        fun Int.feed4() {
            val v = toLong() and 0xFFFFFFFFL
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        fun String.feed() {
            for (ch in this) {
                val v = ch.code.toLong()
                j.update(((v shr 24) and 0xFF).toInt())
                j.update(((v shr 16) and 0xFF).toInt())
                j.update(((v shr 8) and 0xFF).toInt())
                j.update((v and 0xFF).toInt())
            }
            j.update(0); j.update(0); j.update(0); j.update(0)
        }
        k.bodyPx.feed4(); k.lineSpacing.feed4(); k.firstLineIndentEm.feed4()
        k.letterSpacingEm.feed4(); k.paragraphSpacingPx.feed4()
        k.paragraphGapScale.feed4()
        k.fontBody.feed(); k.fontTitle.feed(); k.fontCode.feed()
        (if (k.useOriginalStyle) 1L else 0L).let { v ->
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        k.contentW.feed4(); k.contentH.feed4(); k.userCssHash.feed4()
        return j.value
    }
}