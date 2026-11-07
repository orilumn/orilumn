package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 全书累计页码的纯加和口径（用户层「显示页码」）：
 *  - 页码 = 本章之前各章页数之和 + 章内下标 + 1；
 *  - **未知就不出数**：本章之前任一章没有页数 → 整条 null（页号会算错，宁可留白）；
 *  - 之后的章缺页数 → 页号照给，总数置 null（「共几页」不能拿半本书的和冒充）。
 */
class BookPageNumberTest {

    private val threeChapters = mapOf(0 to 10, 1 to 20, 2 to 30)

    @Test
    fun `index sums all preceding chapters and is 1-based`() {
        val n = bookPageNumberOf(chapter = 1, pageInChapter = 2, chapterCount = 3) { threeChapters[it] }
        assertEquals(10 + 2 + 1, n!!.index)
        assertEquals(60, n.total)
        assertEquals("13 / 60", n.label)
    }

    @Test
    fun `first page of the first chapter is page one`() {
        val n = bookPageNumberOf(chapter = 0, pageInChapter = 0, chapterCount = 3) { threeChapters[it] }
        assertEquals(1, n!!.index)
        assertEquals(60, n.total)
    }

    @Test
    fun `unknown chapter before this one hides the number entirely`() {
        val counts = mapOf(1 to 20, 2 to 30) // 第 0 章还没正典分页
        assertNull(bookPageNumberOf(chapter = 1, pageInChapter = 2, chapterCount = 3) { counts[it] })
    }

    @Test
    fun `unknown chapter after this one keeps the index but drops the total`() {
        val counts = mapOf(0 to 10, 1 to 20) // 第 2 章在排
        val n = bookPageNumberOf(chapter = 0, pageInChapter = 4, chapterCount = 3) { counts[it] }
        assertEquals(5, n!!.index)
        assertNull("总页数不估：缺一章就只显示页号", n.total)
        assertEquals("5", n.label)
    }

    @Test
    fun `out-of-range chapter or negative page gives null`() {
        assertNull(bookPageNumberOf(-1, 0, 3) { threeChapters[it] })
        assertNull(bookPageNumberOf(3, 0, 3) { threeChapters[it] })
        assertNull(bookPageNumberOf(0, -1, 3) { threeChapters[it] })
        assertNull(bookPageNumberOf(0, 0, 0) { null })
    }

    // ---- 目录页码：各章首页（能算的都给，遇未知即停） ----

    @Test
    fun `chapter starts accumulate from page one`() {
        val starts = chapterStartPagesOf(3) { threeChapters[it] }
        assertEquals(1, starts[0])
        assertEquals(11, starts[1])
        assertEquals(31, starts[2])
        assertEquals(60, bookTotalPagesOf(3) { threeChapters[it] })
    }

    @Test
    fun `chapter starts stop at the first unknown chapter`() {
        // 前两章排完、第三章在排：前两章首页照给，第三章首页也能算（只要前面各章页数），
        // 再往后的章依赖第三章的页数，不可算 → 0。
        val counts = mapOf(0 to 10, 1 to 20)
        val starts = chapterStartPagesOf(4) { counts[it] }
        assertEquals(1, starts[0])
        assertEquals(11, starts[1])
        assertEquals(31, starts[2])
        assertEquals(0, starts[3])
        assertNull("缺章不估总数", bookTotalPagesOf(4) { counts[it] })
    }

    @Test
    fun `empty chapter counts as known zero pages`() {
        // 空章从不落表，但它是「0 页」，不能当成未知把后面全掐掉。
        val counts = mapOf(0 to 5, 1 to 0, 2 to 7)
        val starts = chapterStartPagesOf(3) { counts[it] }
        assertEquals(1, starts[0])
        assertEquals(6, starts[1])
        assertEquals(6, starts[2])
        assertEquals(12, bookTotalPagesOf(3) { counts[it] })
    }

    @Test
    fun `nothing known still knows the first chapter starts at page one`() {
        // 第 0 章首页 = 全书第一页，这是定义，不依赖任何页数；后面的章才不可算。
        val starts = chapterStartPagesOf(3) { null }
        assertEquals(1, starts[0])
        assertEquals(0, starts[1])
        assertEquals(0, starts[2])
        assertNull(bookTotalPagesOf(3) { null })
    }
}
