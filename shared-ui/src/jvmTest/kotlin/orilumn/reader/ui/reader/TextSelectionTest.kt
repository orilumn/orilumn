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
    fun `selectRange restores an annotation range without expanding to a word or sentence`() {
        val s = TextSelectionState()
        // 编辑已有标注：区间的中间一段，不能被 begin() 扩成整句。
        assertTrue(s.selectRange(chapter = 2, text = "hello world", start = 1, endExclusive = 4))
        assertTrue(s.hasSelection)
        assertEquals(2, s.chapter)
        assertEquals(1, s.start)
        assertEquals(4, s.endExclusive)
        assertEquals(1..3, s.range)

        // 从恢复出来的选区继续拖右柄：仍按字符走。
        s.beginHandleDrag(SelectionHandle.End)
        s.extendTo(9)
        assertEquals(1, s.start)
        assertEquals(10, s.endExclusive)
    }

    @Test
    fun `mouse drag starts at the pressed character, not the sentence start`() {
        val s = TextSelectionState()
        val text = "第一句。第二句。第三句。"
        // 鼠标按在第二句中间（偏移 6）：不扩成整句，锚点就是按下那个字。
        assertTrue(s.begin(chapter = 0, text = text, offset = 6, selectUnit = false))
        assertEquals(6, s.start)
        assertEquals(7, s.endExclusive)

        // 往右拖：从按下那个字到手指，绝不是「行首 → 手指」。
        s.extendTo(9)
        assertEquals(6, s.start)
        assertEquals(10, s.endExclusive)
        // 往左拖回：同一锚点反向，仍不从句首起。
        s.extendTo(2)
        assertEquals(2, s.start)
        assertEquals(7, s.endExclusive)
    }

    @Test
    fun `long press keeps the unit until the finger really drags`() {
        val s = TextSelectionState()
        val text = "第一句。第二句。第三句。"
        // 长按先按 D-7 给整句（抬手不拖就是这个样子）。
        assertTrue(s.begin(chapter = 0, text = text, offset = 6))
        assertEquals(4, s.start)
        assertEquals(8, s.endExclusive)

        // 一动就把锚点挪到按下那个字，再跟手指走。
        s.anchorAt(6)
        s.extendTo(9)
        assertEquals(6, s.start)
        assertEquals(10, s.endExclusive)
    }

    @Test
    fun `anchorAt keeps the far end when the finger has not moved yet`() {
        val s = TextSelectionState()
        s.begin(chapter = 0, text = "hello world", offset = 6)
        assertEquals(6, s.start)
        assertEquals(11, s.endExclusive)
        // 锚点挪到已选范围内的某个字：另一端仍是手指当前那一端，不会凭空扩大。
        s.anchorAt(8)
        assertEquals(8, s.start)
        assertEquals(11, s.endExclusive)
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
