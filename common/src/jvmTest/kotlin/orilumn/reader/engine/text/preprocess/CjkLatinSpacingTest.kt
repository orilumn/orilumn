package orilumn.reader.engine.text.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkLatinSpacingTest {

    private fun gapsOf(text: String, em: Float = 0.25f): List<CjkLatinGap> =
        CjkLatinSpacing.gaps(text, em)

    @Test
    fun `cjk then latin gets a gap`() {
        val gaps = gapsOf("中文English")
        assertEquals(listOf(CjkLatinGap(leftIndex = 1, gapEm = 0.25f, suppressSpace = false)), gaps)
    }

    @Test
    fun `latin then cjk gets a gap`() {
        val gaps = gapsOf("English中文")
        assertEquals(listOf(CjkLatinGap(leftIndex = 6, gapEm = 0.25f, suppressSpace = false)), gaps)
    }

    @Test
    fun `digit counts as western side`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f, false)), gapsOf("值3"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, false)), gapsOf("3值"))
    }

    @Test
    fun `half-width space between cjk and latin is suppressed into a gap`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f, true)), gapsOf("中 A"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, true)), gapsOf("A 中"))
    }

    @Test
    fun `nbsp between cjk and latin is suppressed into a gap`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f, true)), gapsOf("中\u00A0A"))
    }

    @Test
    fun `no gap inside pure cjk or pure latin runs`() {
        assertTrue(gapsOf("中文中文").isEmpty())
        assertTrue(gapsOf("English").isEmpty())
        assertTrue(gapsOf("12345678").isEmpty())
    }

    @Test
    fun `fullwidth forms and cjk punctuation count as cjk side`() {
        // 「 」(U+300C/D) and fullwidth '(' (U+FF08) are all CJK sides against Latin.
        assertEquals(listOf(CjkLatinGap(1, 0.25f, false)), gapsOf("「」x"))
        assertEquals(emptyList<CjkLatinGap>(), gapsOf("（）"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, false)), gapsOf("（x"))
    }

    @Test
    fun `cjk extension b surrogates do not produce spurious gaps`() {
        // 𠀀 (U+20000, surrogate pair) then Latin — one gap, left indexing in code units.
        val text = "\uD840\uDC00A"
        assertEquals(listOf(CjkLatinGap(1, 0.25f, false)), gapsOf(text))
    }

    @Test
    fun `custom gap em is honored`() {
        assertEquals(listOf(CjkLatinGap(0, 0.3f, false)), gapsOf("中A", em = 0.3f))
    }

    @Test
    fun `multiple boundaries yield multiple sorted non-overlapping gaps`() {
        // 中 文 E n g l i s h 中 文 — pairs (1→2 CJK/E) and (8→9 h/中).
        val gaps = gapsOf("中文English中文")
        assertEquals(
            listOf(
                CjkLatinGap(leftIndex = 1, gapEm = 0.25f, suppressSpace = false),
                CjkLatinGap(leftIndex = 8, gapEm = 0.25f, suppressSpace = false),
            ),
            gaps,
        )
    }

    @Test
    fun `repeated spaces keep their separation and yield no gap`() {
        assertTrue(gapsOf("中  A").isEmpty())
    }
}