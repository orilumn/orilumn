package orilumn.reader.engine

import orilumn.reader.engine.paging.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 正文「只换字、不增减行」时，分页表按改动所在页整体平移字符偏移。
 *
 * 这是「改完不重排」快路径的唯一一张算术表：页界由行决定，行数没变 ⇒ 每页装的是同一批行，
 * 只是改动之后的字挪了地方。平移规则错一格，页首字 / 页码 / 翻页都会整体错位。
 */
class PaginationTableShiftTest {

    private fun table() = ChapterPaginationTable.fromSlices(
        chapterIndex = 2,
        paramHash = 0xabcL,
        slices = listOf(
            PageSlice(0, 100, 0, 12, blockStart = 0, blockEndExclusive = 3),
            PageSlice(100, 220, 12, 26, blockStart = 3, blockEndExclusive = 6),
            PageSlice(220, 340, 26, 38, blockStart = 6, blockEndExclusive = 9),
        ),
        totalBlocks = 9,
        totalChars = 340,
        contentRev = 77L,
    )

    @Test
    fun `shift from middle page moves current page tail and every later page`() {
        val shifted = table().shiftCharsFrom(pageIndex = 1, delta = 5)
        // 之前的页一个字不动
        assertEquals(0, shifted.pages[0].charStart)
        assertEquals(100, shifted.pages[0].charEnd)
        // 当前页：页首不动（改动在它之后），页尾平移
        assertEquals(100, shifted.pages[1].charStart)
        assertEquals(225, shifted.pages[1].charEnd)
        // 之后的页：页首页尾都平移
        assertEquals(225, shifted.pages[2].charStart)
        assertEquals(345, shifted.pages[2].charEnd)
        assertEquals(345, shifted.totalChars)
        // 块区间与版本号不参与平移（段数没变，块下标原样成立）
        assertEquals(3, shifted.pages[1].blockStart)
        assertEquals(9, shifted.pages[2].blockEndExclusive)
        assertEquals(77L, shifted.contentRev)
    }

    @Test
    fun `shift from head moves every page`() {
        val shifted = table().shiftCharsFrom(pageIndex = 0, delta = -7)
        assertEquals(0, shifted.pages[0].charStart)
        assertEquals(93, shifted.pages[0].charEnd)
        assertEquals(93, shifted.pages[1].charStart)
        assertEquals(213, shifted.pages[1].charEnd)
        assertEquals(333, shifted.totalChars)
    }

    @Test
    fun `shift from last page leaves earlier pages untouched`() {
        val shifted = table().shiftCharsFrom(pageIndex = 2, delta = 3)
        assertEquals(100, shifted.pages[1].charStart)
        assertEquals(220, shifted.pages[1].charEnd)
        assertEquals(220, shifted.pages[2].charStart)
        assertEquals(343, shifted.pages[2].charEnd)
    }

    @Test
    fun `zero delta keeps the very same table`() {
        val t = table()
        assertSame(t, t.shiftCharsFrom(pageIndex = 1, delta = 0))
    }

    @Test
    fun `out of range page index is clamped`() {
        val shifted = table().shiftCharsFrom(pageIndex = 99, delta = 4)
        assertEquals(220, shifted.pages[1].charEnd)
        assertEquals(220, shifted.pages[2].charStart)
        assertEquals(344, shifted.pages[2].charEnd)
    }
}
