package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-7 选区单位 + D-8 单章区间：英文词、中文句、手柄拖动按字符。 */
class TextSelectionTest {

    private fun range(text: String, offset: Int): String {
        val r = selectionUnitAt(text, offset)
        return text.substring(r.first, r.last + 1)
    }

    private fun range(text: String, offset: Int, blocks: IntArray): String {
        val r = selectionUnitAt(text, offset, blocks)
        return text.substring(r.first, r.last + 1)
    }

    @Test
    fun `english selects the whole word`() {
        assertEquals("hello", range("hello world", 1))
        assertEquals("hello", range("say hello now", 6))
        assertEquals("don't", range("don't stop", 2))
        assertEquals("well-known", range("a well-known name", 5))
        assertEquals("hello", range("  hello  ", 3))
    }

    @Test
    fun `punctuation alone falls back to one code point`() {
        assertEquals(".", range("hi. there", 2))
    }

    @Test
    fun `cjk selects the whole sentence and keeps closing quote`() {
        assertEquals("他说：“你好。”", range("他说：“你好。”下一句。", 4))
        assertEquals("他说：“你好。”", range("他说：“你好。”下一句。", 7))
        assertEquals("第二句！", range("第一句。第二句！第三句。", 5))
    }

    @Test
    fun `newline is a sentence boundary`() {
        assertEquals("乙。", range("甲。\n乙。", 3))
    }

    @Test
    fun `surrogate pair is never split`() {
        val text = "a😀b"
        val r = selectionUnitAt(text, 1)
        assertEquals(1, r.first)
        assertEquals(2, r.last)
        assertEquals("😀", text.substring(r.first, r.last + 1))
    }

    @Test
    fun `block ends stop a sentence or word at the paragraph edge`() {
        val text = "标题本章内容第一段。第二句。"
        val blocks = intArrayOf(2, 6, 14)
        // 偏移 7 = 第三块的“一”，不能把标题/小标题一起吃进来。
        assertEquals("第一段。", range(text, 7, blocks))

        val english = "TitleHello world"
        val englishBlocks = intArrayOf(5, 16)
        assertEquals("Hello", range(english, 6, englishBlocks))
    }

    @Test
    fun `copy text inserts newlines only between blocks`() {
        val text = "Hello worldsecond"
        val blocks = intArrayOf(11, 17)
        assertEquals("world\nsecond", copyTextOf(text, 6, 17, blocks))
        assertEquals("Hello world\nsecond", copyTextOf(text, 0, 17, blocks))
        // 选区正好停在块尾：不要多一个尾随换行。
        assertEquals("Hello world", copyTextOf(text, 0, 11, blocks))
        // 没有块表时保持原文（旧宿主/测试）。
        assertEquals("worldsecond", copyTextOf(text, 6, 17, null))
    }

    @Test
    fun `state begins with a unit and extends by character`() {
        val s = TextSelectionState()
        assertTrue(s.begin(chapter = 2, text = "hello world", offset = 1))
        assertTrue(s.hasSelection)
        assertEquals(2, s.chapter)
        assertEquals(0, s.start)
        assertEquals(5, s.endExclusive)

        s.extendTo(8)
        assertEquals(0, s.start)
        assertEquals(9, s.endExclusive)
        assertEquals(0..8, s.range)
    }

    @Test
    fun `dragging the start handle moves only that end`() {
        val s = TextSelectionState()
        s.begin(chapter = 2, text = "hello world", offset = 1)
        s.beginHandleDrag(SelectionHandle.Start)
        s.extendTo(3)
        assertEquals(3, s.start)
        assertEquals(5, s.endExclusive)

        s.beginHandleDrag(SelectionHandle.End)
        s.extendTo(8)
        assertEquals(3, s.start)
        assertEquals(9, s.endExclusive)
    }

    @Test
    fun `clear drops the whole selection`() {
        val s = TextSelectionState()
        s.begin(chapter = 2, text = "hello", offset = 1)
        s.clear()
        assertFalse(s.hasSelection)
        assertEquals(-1, s.chapter)
        assertEquals(IntRange.EMPTY, s.range)
    }
}
