package orilumn.reader.engine.paging

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressMapperTest {

    private fun lengths(vararg n: Int) = ProgressMapper.ChapterLengths(LongArray(n.size) { n[it].toLong() })

    @Test
    fun `bookProgress 累计字符`() {
        val l = lengths(100, 200) // chapter0=100 chars, chapter1=200 chars
        // chapter0 at char 50 → 50/300
        assertEquals(50.0 / 300.0, ProgressMapper.bookProgress(l, 0, 50), 1e-9)
        // chapter1 start → 100/300
        assertEquals(100.0 / 300.0, ProgressMapper.bookProgress(l, 1, 0), 1e-9)
        // chapter1 end → 300/300=1.0 (charOffset may be arbitrarily large, clamped)
        assertEquals(1.0, ProgressMapper.bookProgress(l, 1, Int.MAX_VALUE), 1e-9)
    }

    @Test
    fun `chapterFromBookProgress 定位章节`() {
        val l = lengths(100, 200, 100)
        assertEquals(0, ProgressMapper.chapterFromBookProgress(l, 0.0))
        // 100/400=0.25 is exactly the boundary at the end of chapter0 → belongs to chapter1
        assertEquals(1, ProgressMapper.chapterFromBookProgress(l, 0.25))
        assertEquals(2, ProgressMapper.chapterFromBookProgress(l, 0.75))
        assertEquals(2, ProgressMapper.chapterFromBookProgress(l, 1.0))
    }

    @Test
    fun `chapterChar 换算`() {
        val l = lengths(100, 100)
        // whole book 0.5 → 200*0.5=100 → offset 0 inside chapter1
        assertEquals(1, ProgressMapper.chapterFromBookProgress(l, 0.5))
        assertEquals(0, ProgressMapper.chapterChar(l, 1, 0.5))
    }

    @Test
    fun `ofChapters 统计文本长度`() {
        val spine = listOf(chapterTree("第一章", 40), chapterTree("第二章", 60))
        val l = ProgressMapper.ofChapters(spine)
        assertEquals(2, l.size)
        assertEquals(100L, l.totalChars)
        assertEquals(40L, l.chapterChars(0))
        assertEquals(40L, l.precedingChars(1))
    }

    /** Builds a tree with [lipsumN] text characters. */
    private fun chapterTree(label: String, charCount: Int): MarkupElement {
        val body = (0 until charCount).joinToString("") { "字" }
        return MarkupElement("body", children = listOf(MarkupElement("p", children = listOf(MarkupElement("#text", text = body)))))
    }
}