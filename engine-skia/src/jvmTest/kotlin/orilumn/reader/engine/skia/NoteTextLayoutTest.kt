package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旁注短文本走自研引擎：断行、截断省略号、字距与中西混排间距。
 *
 * 这些是「量画同源」的前半段：布点问 [NoteTextLayout.breakText]，覆盖层用
 * [NoteTextLayout.drawLines] 落墨，两者必须对同一段文本给出同一组行与同一行宽。
 */
class NoteTextLayoutTest {

    private val spec = NoteTextSpec(families = emptyList())

    @Test
    fun wideWidthKeepsWholeSentenceOnOneLine() {
        val text = "短批注"
        val broken = NoteTextLayout.breakText(
            spec = spec,
            text = text,
            fontPx = 28f,
            lineHeightRatio = 1.38f,
            widthPx = 500,
            maxLines = 3,
        )
        assertEquals(1, broken.lines.size)
        assertEquals(1, broken.naturalLineCount)
        assertTrue(broken.complete)
        assertEquals(text, broken.text)
        assertEquals(3, broken.shownChars)
    }

    @Test
    fun narrowWidthTruncatesToMaxLinesWithEllipsis() {
        val text = "这一段批注要读完才看得明白，不能只剩几个字。"
        val broken = NoteTextLayout.breakText(
            spec = spec,
            text = text,
            fontPx = 28f,
            lineHeightRatio = 1.38f,
            widthPx = 100,
            maxLines = 2,
        )
        assertEquals(2, broken.lines.size)
        assertTrue("自然行数应当超过上限：${broken.naturalLineCount}", broken.naturalLineCount > 2)
        assertFalse(broken.complete)
        assertTrue("截断后末字是省略号：${broken.text}", broken.text.endsWith(NoteTextLayout.ELLIPSIS))
        assertTrue("渲染文本是原文前缀：${broken.text}", text.startsWith(broken.text.dropLast(1)))
        assertTrue("显示字数应当少于原文", broken.shownChars < broken.totalChars)
    }

    @Test
    fun ellipsisLineIsNotJustifiedPastTheBox() {
        val text = "这一段批注要读完才看得明白，不能只剩几个字。"
        val width = 100
        val broken = NoteTextLayout.breakText(
            spec = spec,
            text = text,
            fontPx = 28f,
            lineHeightRatio = 1.38f,
            widthPx = width,
            maxLines = 2,
        )
        val draw = NoteTextLayout.drawLines(
            spec = spec,
            text = text,
            fontPx = 28f,
            lineHeightRatio = 1.38f,
            widthPx = width,
            maxLines = 2,
            inkArgb = 0xFF000000.toInt(),
        )
        assertEquals(broken.lines.size, draw.lines.size)
        assertEquals(broken.lines.map { it.range }, draw.lines.map { it.range })
        // 末行是「前缀 + …」，末行不再两端对齐（`isLastLine`），行尾不会越过框宽。
        val last = draw.lines.last()
        assertEquals(width, last.lineWidthPx)
        assertEquals(broken.text.length - 1, last.range.last)
        assertTrue("Y 从 0 起、逐行堆叠", draw.lines.first().yTop == 0)
        assertEquals(draw.lines.size * draw.lineHeightPx, draw.heightPx)
    }

    @Test
    fun letterSpacingWidensNaturalWidth() {
        val text = "abcdefghij"
        val plain = NoteTextSpec(families = emptyList(), letterSpacingEm = 0f)
        val spaced = NoteTextSpec(families = emptyList(), letterSpacingEm = 0.1f)
        assertTrue(
            NoteTextLayout.naturalWidth(plain, text, 20f) <
                NoteTextLayout.naturalWidth(spaced, text, 20f),
        )
    }

    @Test
    fun cjkLatinSpacingWidensNaturalWidth() {
        val text = "中文abc中文"
        val tight = NoteTextSpec(families = emptyList(), cjkLatinSpacingEm = 0f)
        val loose = NoteTextSpec(families = emptyList(), cjkLatinSpacingEm = 0.25f)
        assertTrue(
            NoteTextLayout.naturalWidth(tight, text, 20f) <
                NoteTextLayout.naturalWidth(loose, text, 20f),
        )
    }

    @Test
    fun hardNewlineBreaksParagraphsAndWidthTakesWidest() {
        val text = "短\n这一行明显更长一些"
        val broken = NoteTextLayout.breakText(
            spec = spec,
            text = text,
            fontPx = 20f,
            lineHeightRatio = 1.38f,
            widthPx = 1000,
            maxLines = 10,
        )
        assertEquals(2, broken.naturalLineCount)
        assertEquals(2, broken.lines.size)
        assertTrue(broken.complete)
        assertEquals(10, broken.totalChars)
        val w1 = NoteTextLayout.naturalWidth(spec, "短", 20f)
        val w2 = NoteTextLayout.naturalWidth(spec, "这一行明显更长一些", 20f)
        assertEquals(maxOf(w1, w2), NoteTextLayout.naturalWidth(spec, text, 20f))
    }
}
