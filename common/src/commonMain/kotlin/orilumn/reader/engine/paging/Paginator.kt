package orilumn.reader.engine.paging

/**
 * Cuts a fully laid-out [BookLayout] into pages according to the content-area height.
 *
 * Pagination is **line-coherent**: a page is a contiguous run of *whole* lines filled top to bottom.
 * A line is added to the current page only if it fully fits in the remaining content height —
 * otherwise that line is carried over entirely to the next page, so a page **never ends with a
 * partially visible (half) line**. Paragraphs flow continuously and are allowed to straddle page
 * boundaries; only `break-inside: avoid` blocks ([BreakAwareBookLayout]) are kept whole.
 *
 * Because pages are filled by lines (not retro-fitted to paragraph starts), a trailing paragraph is
 * no longer pushed alone onto a near-empty page — the second-to-last page stays densely filled.
 *
 * @param layout the laid-out whole-chapter layout.
 * @param contentH visible content-area height (px); must share the same coordinate system as [BookLayout.getLineTop/Bottom].
 * @return the list of pages in front-to-back order.
 */
object Paginator {

    /**
     * The single shared "how many whole lines fit a page" rule, used by BOTH the canonical
     * full-chapter paginator and the incremental per-window paginator
     * ([BoxChapterLayouter.incrementalLayoutForPage]). Both paths must cut pages with the exact same
     * whole-line fill, or the same lines stack to different page extents on the two paths — that is
     * the device's "page available height vs laid-out height" deviation (page under-fill / slight
     * overflow / clipped last line).
     *
     * Fills from [startLine] with whole lines whose bottom stays within the content area (`contentH`
     * below the first included line's top) and returns the first line NOT included. The last included
     * line is guaranteed fully visible (never a clipped half line); a single line taller than the whole
     * page always returns at least `startLine + 1` (the cursor never stalls).
     */
    fun fillWholeLines(layout: BookLayout, startLine: Int, contentH: Int): Int {
        var end = startLine
        val firstTop = layout.getLineTop(startLine)
        while (end < layout.lineCount && layout.getLineBottom(end) - firstTop <= contentH) end++
        if (end == startLine) end = startLine + 1
        return end
    }

    fun paginate(layout: BookLayout, contentH: Int): List<PageSlice> = paginateFrom(layout, 0, contentH)

    /**
     * Same whole-line + `break-inside: avoid` rule as [paginate], but starting the first page at
     * [startLine] instead of line 0.
     *
     * This is the incremental window's variant: the disk table's page boundary pins the first line
     * ([startLine] = the line containing the table's charStart), then every page after it flows by the
     * exact same cut rule the canonical path used — so with matching geometry the two paths produce
     * identical boundaries instead of drifting wherever the shared fill rule is re-implemented.
     */
    fun paginateFrom(layout: BookLayout, startLine: Int, contentH: Int): List<PageSlice> {
        // P1-2: 空章（纯空白/封面 svg 等归一化后零行）仍给一空页，保证可翻页、可落位、
        // 可写穿磁盘表；绘制循环 over 零行天然为空，无需特判。
        if (layout.lineCount <= 0) return listOf(PageSlice(0, 0, 0, 0))
        if (contentH <= 0 || startLine < 0) return emptyList()
        if (startLine >= layout.lineCount) return emptyList()
        val pages = ArrayList<PageSlice>()
        val n = layout.lineCount
        var cursorLine = startLine
        while (cursorLine < n) {
            val pageStartLine = cursorLine
            val end = fillWholeLines(layout, pageStartLine, contentH)
            // Honour break-inside: avoid — a whole block that would be torn by this cut moves intact
            // to the next page (only when it fits a page); oversized blocks are allowed to tear.
            val cut = applyBreakInsideAvoid(layout, pageStartLine, end, contentH)

            val charStart = layout.getLineStart(pageStartLine)
            val charEnd = if (cut >= n) layout.length else layout.getLineStart(cut)
            pages.add(PageSlice(charStart, charEnd, pageStartLine, cut))
            cursorLine = cut
        }
        return pages
    }

    /**
     * Adjusts [baseCut] so a `break-inside: avoid` block is never torn across a page, while
     * guaranteeing the page cursor always advances (no stall).
     *
     * For each avoid-range `[bs, be)` whose interior the current cut lands in:
     *  - if the block starts **after** this page's first line, retreat `cut` to `bs` — the whole
     *    block moves intact to the next page;
     *  - if the block starts exactly **at** this page's first line, extend `cut` to `be` — the
     *    whole block fills this page instead of a line cut splitting it.
     *
     * Only blocks that fit a single page are moved; oversized blocks are allowed to tear, matching
     * browser behavior. Non-[BreakAwareBookLayout] layouts are untouched.
     */
    private fun applyBreakInsideAvoid(layout: BookLayout, pageStartLine: Int, baseCut: Int, contentH: Int): Int {
        val aware = layout as? BreakAwareBookLayout ?: return baseCut
        var cut = baseCut
        for (range in aware.breakInsideAvoidRanges) {
            val bs = range.first
            val be = range.last + 1
            if (cut < bs || cut >= be) continue      // cut not inside this block's range
            val blockH = layout.getLineBottom(be - 1) - layout.getLineTop(bs)
            if (blockH > contentH) continue           // oversized block → allowed to tear
            if (bs > pageStartLine) {
                cut = minOf(cut, bs)                 // block starts below this page → move it whole to the next page
            } else if (bs == pageStartLine) {
                cut = maxOf(cut, be)                 // block already starts this page → fill this page with it whole
            }
        }
        return cut
    }
}