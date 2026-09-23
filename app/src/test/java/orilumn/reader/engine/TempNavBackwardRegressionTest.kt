package orilumn.reader.engine

import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.FakeBookLayout
import orilumn.reader.engine.paging.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the two page-flip defects fixed in temp navigation / cross-chapter landing:
 *
 *  - 缺陷A: the old `tempNav headReached && dir<0` shortcut fired regardless of the CURRENT pointer
 *    position, so once a large chapter's head had ever been pre-shaped (headReached), a backward flip
 *    from mid-forwardPages jumped CROSS-chapter (previous chapter's last page) instead of the
 *    in-chapter page before it. [tempNavBackwardPointerStep] must decide from pointer state alone —
 *    headReached is not even on its input surface — and only a real endInc/endFor<=0 packing front
 *    yields [TempNavBackwardStep.Boundary].
 *  - 缺陷B: cross-chapter backward landing built a not-yet-laid-out large chapter anchored at char 0,
 *    so the entry page was the chapter HEAD instead of its END. [backwardEntryAnchorChar] must anchor
 *    a fresh chapter at its tail (textLength−1), or at the already-known LAST page's charStart.
 *  - 缺陷C: the incremental disk-hit page lookup `indexOfFirst{...}.coerceAtLeast(0)` degraded a char
 *    that fell OUTSIDE the disk table's coverage (markup.textLength counts display:none/br that the
 *    table's visible-char stream excludes) to page 0 — so a backward landing shaped the chapter HEAD
 *    window and returned the LAST table page as an unshaped `[-1,-1)` stub → blank page.
 *    [pageIndexForChar] clamps out-of-range chars to the LAST page instead.
 */
class TempNavBackwardRegressionTest {

    private fun step(
        curIsForward: Boolean,
        curIndex: Int,
        anchorBlockStart: Int = 100,
        anchorLineCharStart: Int = 0,
        backFrontBlock: Int = anchorBlockStart,
        anchorBlockCharStart: Int = 0,
        backwardPageCount: Int = 0,
        deepestBackwardBlockStart: Int? = null,
        deepestBackwardStartChar: Int = -1,
        deepestBackwardBlockCharStart: Int = -1,
    ) = tempNavBackwardPointerStep(
        curIsForward, curIndex, anchorBlockStart, anchorLineCharStart, backFrontBlock,
        anchorBlockCharStart, backwardPageCount, deepestBackwardBlockStart,
        deepestBackwardStartChar, deepestBackwardBlockCharStart,
    )

    @Test
    fun `headReached 后从中段前向页回翻仍在章内`() {
        // Reader on forwardPages[2] whose chapter head was already pre-shaped to block 0. The old
        // `headReached && dir<0` guard turned this into a Boundary → cross-chapter (前章末页); the
        // fixed decision must return the in-chapter page before it.
        assertEquals(TempNavBackwardStep.Move(true, 1), step(curIsForward = true, curIndex = 2))
    }

    @Test
    fun `锚点页上无后向页且锚点在章内时向前收一整页`() {
        // Anchor inside the chapter, no backward page shaped yet → shape the page right before the
        // anchor, whole-block front = backFrontBlock (== anchorBlockStart).
        assertEquals(TempNavBackwardStep.Shape(5, -1),
            step(curIsForward = true, curIndex = 0, anchorBlockStart = 5, backFrontBlock = 5))
    }

    @Test
    fun `锚点行切块时向后页多收锚块前导行`() {
        // Anchor page cuts mid-block → endInc = anchorBlock+1, lineCut = the anchor line's char start,
        // so the anchor block's leading lines are covered and never lost.
        assertEquals(TempNavBackwardStep.Shape(6, 120),
            step(curIsForward = true, curIndex = 0, anchorBlockStart = 5, anchorLineCharStart = 120,
                anchorBlockCharStart = 100, backFrontBlock = 5))
    }

    @Test
    fun `章头锚点页继续回翻是真实边界`() {
        // Anchor at block 0 with a whole-block bottom (backFrontBlock == 0) → no in-chapter page can
        // precede the anchor → the caller goes cross-chapter. (the fix keeps this S5 behavior intact)
        assertEquals(TempNavBackwardStep.Boundary,
            step(curIsForward = true, curIndex = 0, anchorBlockStart = 0, anchorLineCharStart = 0,
                backFrontBlock = 0))
    }

    @Test
    fun `后向页最深已到章头时继续回翻是边界`() {
        // Reader on the head page (deepest backward block == 0); one more backward → Boundary.
        assertEquals(TempNavBackwardStep.Boundary,
            step(curIsForward = false, curIndex = 0, anchorBlockStart = 5, backwardPageCount = 1,
                deepestBackwardBlockStart = 0))
    }

    @Test
    fun `后向页序列内继续翻按指针步进不重复成形`() {
        assertEquals(TempNavBackwardStep.Move(false, 4),
            step(curIsForward = false, curIndex = 3, backwardPageCount = 5))
    }

    @Test
    fun `最深后向页从块中途开始时下一页补上该块前导行`() {
        // Seam fix: the deepest backward page's first line begins mid-block (startChar 120 > block char
        // start 100). The next page further back must END at char 120 — i.e. still include that block's
        // leading lines (endExclusive = block+1, lineCut = 120). The old code returned the bare block
        // boundary (Shape(5, -1)), dropping chars 100..119 → a page-seam gap.
        assertEquals(TempNavBackwardStep.Shape(6, 120),
            step(curIsForward = false, curIndex = 0, backwardPageCount = 1,
                deepestBackwardBlockStart = 5, deepestBackwardStartChar = 120,
                deepestBackwardBlockCharStart = 100))
    }

    @Test
    fun `最深后向页从块首开始时下一页停在块边界`() {
        assertEquals(TempNavBackwardStep.Shape(5, -1),
            step(curIsForward = false, curIndex = 0, backwardPageCount = 1,
                deepestBackwardBlockStart = 5, deepestBackwardStartChar = 100,
                deepestBackwardBlockCharStart = 100))
    }

    @Test
    fun `跨章回翻未排大章锚定章尾而非章头`() {
        val markup = MarkupElement("#text", text = "x".repeat(60))
        val fresh = ChapterUnit(0) // no disk table, no slices
        // textLength=60 → anchor at char 59 (the last content char), NOT char 0.
        assertEquals(59, backwardEntryAnchorChar(fresh, markup))
    }

    @Test
    fun `磁盘表就绪的回翻锚定末页起点_charStart`() {
        val unit = ChapterUnit(1)
        unit.bindPaginationTable(
            ChapterPaginationTable(
                chapterIndex = 1,
                paramHash = 1L,
                totalBlocks = 3,
                totalChars = 90,
                pages = listOf(
                    ChapterPaginationTable.PageRecord(0, 30, 0, 1),
                    ChapterPaginationTable.PageRecord(30, 60, 1, 2),
                    ChapterPaginationTable.PageRecord(60, 90, 2, 3),
                ),
            ),
        )
        assertEquals(60, backwardEntryAnchorChar(unit, MarkupElement("#text", text = "y".repeat(90))))
    }

    @Test
    fun `已排小章的回翻锚定最后一个页切片起点`() {
        val unit = ChapterUnit(2)
        unit.bind(
            FakeBookLayout.ofLines("a"),
            listOf(
                PageSlice(charStart = 0, charEnd = 3),
                PageSlice(charStart = 3, charEnd = 7),
                PageSlice(charStart = 7, charEnd = 9),
            ),
        )
        assertEquals(7, backwardEntryAnchorChar(unit, MarkupElement("#text", text = "z".repeat(9))))
    }

    @Test
    fun `文本长度锚点超出磁盘表覆盖时归到尾页而非章头`() {
        // 缺陷C: markup.textLength can EXCEED the disk table's char stream (which excludes
        // display:none and counts img as one slot), so textLength−1 lands past the last page's
        // charEnd. pageIndexForChar must clamp to the LAST page (tail landing), not page 0 (head).
        val pages = listOf(
            ChapterPaginationTable.PageRecord(0, 30, 0, 1),
            ChapterPaginationTable.PageRecord(30, 60, 1, 2),
            ChapterPaginationTable.PageRecord(60, 90, 2, 3),
        )
        assertEquals(2, pageIndexForChar(pages, targetChar = 89)) // inside last page
        assertEquals(2, pageIndexForChar(pages, targetChar = 90)) // == totalChars → tail
        assertEquals(2, pageIndexForChar(pages, targetChar = 100000)) // far past → tail
        assertEquals(0, pageIndexForChar(pages, targetChar = 0)) // head in range
    }

    @Test
    fun `空磁盘表归零页`() {
        assertEquals(0, pageIndexForChar(emptyList(), targetChar = 42))
    }

    @Test
    fun `表内正常锚点命中所含页`() {
        val pages = listOf(
            ChapterPaginationTable.PageRecord(0, 30, 0, 1),
            ChapterPaginationTable.PageRecord(30, 60, 1, 2),
            ChapterPaginationTable.PageRecord(60, 90, 2, 3),
        )
        assertEquals(1, pageIndexForChar(pages, targetChar = 30)) // page boundary → page 1
    }
}