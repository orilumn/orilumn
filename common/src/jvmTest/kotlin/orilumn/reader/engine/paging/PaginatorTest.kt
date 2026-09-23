package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Test

class PaginatorTest {

    @Test
    fun `两行满一页`() {
        // Each line is 20 high, page height 40 → 2 lines per page (4 chars per line → 8 chars per page)
        val layout = FakeBookLayout.ofLines("aaaa", "bbbb", "cccc", "dddd")
        val pages = Paginator.paginate(layout, contentH = 40)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].charStart)
        assertEquals(8, pages[0].charEnd)
        assertEquals(0, pages[0].firstLine)
        assertEquals(2, pages[0].lastLineExclusive)
        assertEquals(8, pages[1].charStart)
        assertEquals(16, pages[1].charEnd)
        assertEquals(2, pages[1].firstLine)
        assertEquals(4, pages[1].lastLineExclusive)
    }

    @Test
    fun `页高大到最后一行只留一行不丢字`() {
        // 5 lines of 20 each; page height 100 fits exactly 5 lines
        val layout = FakeBookLayout.ofLines("0", "1", "2", "3", "4")
        val pages = Paginator.paginate(layout, contentH = 100)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].charStart)
        assertEquals(5, pages[0].charEnd)
        assertEquals(5, pages[0].lastLineExclusive)
    }

    @Test
    fun `段落保边回退到最近段首`() {
        // 5 lines, page height 20 (1 line per page). Lines 2 and 3 (start=1,2) belong to the same
        // continuous paragraph (not paragraph starts); the paragraph ends after line 3 (index=2). To keep
        // page 2 starting at a paragraph start, the page breaks at line 1.
        // line (text, is paragraph start): 0(start), 1(no), 2(no), 3(start), 4(no)
        val layout = FakeBookLayout.ofLines(
            "0", "1", "2", "3", "4",
            lineHeight = 20,
            boundaryLines = setOf(0, 3),
        )
        val pages = Paginator.paginate(layout, contentH = 20)
        // page0: [0,1) line 0 (the paragraph start keeps itself for the next page → the page must advance at
        // least from paragraph start 3). More precisely: each page can hold only 1 line; line 0 and line 3 are starts.
        assertEquals(5, pages.size)
        // page0 starts at line 0, charStart=0
        assertEquals(0, pages[0].charStart)
        // after page0 (from line 1) charStart should be 1
        assertEquals(1, pages[1].charStart)
    }

    @Test
    fun `整页都是长段时掐断不无限循环`() {
        // 10 lines all in one paragraph (only line 0 is a start); page height 40 holds 2 lines → 5 pages, no infinite loop
        val layout = FakeBookLayout.ofLines(
            *Array(10) { "x".repeat(3) },
            lineHeight = 20,
            boundaryLines = setOf(0),
        )
        val pages = Paginator.paginate(layout, contentH = 40)
        assertEquals(5, pages.size)
        // each page holds 2 lines (long paragraph cut short), characters stay contiguous
        assertEquals(0, pages[0].charStart)
        assertEquals(6, pages[0].charEnd)
        assertEquals(6, pages[1].charStart)
    }

    @Test
    fun `空布局返回空`() {
        // P1-2: 空章给一空页（可翻页可落位），不再返回空。
        val pages = Paginator.paginate(FakeBookLayout.ofLines(), contentH = 100)
        assertEquals(1, pages.size)
        assertEquals(PageSlice(0, 0, 0, 0), pages[0])
    }

    @Test
    fun `页高为 0 返回空`() {
        val layout = FakeBookLayout.ofLines("aa", "bb")
        assertEquals(emptyList<PageSlice>(), Paginator.paginate(layout, contentH = 0))
    }
}