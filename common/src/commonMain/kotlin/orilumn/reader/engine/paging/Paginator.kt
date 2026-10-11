package orilumn.reader.engine.paging

/**
 * Cuts a fully laid-out [BookLayout] into pages according to the content-area height.
 *
 * Pagination is **line-coherent**: a page is a contiguous run of *whole* lines filled top to bottom.
 * A line is added to the current page only if it fully fits in the remaining content height —
 * otherwise that line is carried over entirely to the next page, so a page **never ends with a
 * partially visible (half) line**. Paragraphs flow continuously and are allowed to straddle page
 * boundaries; only the avoid ranges of a [BreakAwareBookLayout] (whole `break-inside: avoid` blocks
 * and `break-*: avoid` edge clusters) are kept whole, and its
 * [BreakAwareBookLayout.forcedBreakBeforeLines] are hard page starts.
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
     * Same whole-line + break-rule rule as [paginate], but starting the first page at
     * [startLine] instead of line 0.
     *
     * This is the incremental window's variant: the disk table's page boundary pins the first line
     * ([startLine] = the line containing the table's charStart), then every page after it flows by the
     * exact same cut rule the canonical path used — so with matching geometry the two paths produce
     * identical boundaries instead of drifting wherever the shared fill rule is re-implemented.
     *
     * **强制分页**（[BreakAwareBookLayout.forcedBreakBeforeLines]）：先按强制行把行流**切段**，段首
     * 必为页首（上一页在该行之前结束，可能不满页），段内再走整行填充 + [BreakAwareBookLayout.avoidSplitRanges]。
     * 段尾是硬边界：avoid 簇即使跨过强制行也不得越过它（强制断页压过 avoid —— CSS 同一口径）。
     */
    fun paginateFrom(layout: BookLayout, startLine: Int, contentH: Int): List<PageSlice> {
        // P1-2: 空章（纯空白/封面 svg 等归一化后零行）仍给一空页，保证可翻页、可落位、
        // 可写穿磁盘表；绘制循环 over 零行天然为空，无需特判。
        if (layout.lineCount <= 0) return listOf(PageSlice(0, 0, 0, 0))
        if (contentH <= 0 || startLine < 0) return emptyList()
        if (startLine >= layout.lineCount) return emptyList()
        val n = layout.lineCount
        val aware = layout as? BreakAwareBookLayout
        val avoidRanges = aware?.avoidSplitRanges ?: emptyList()
        // 强制断页行：只取 (startLine, n) 内的，升序去重（startLine 自身是页首，无意义）。
        val forced = aware?.forcedBreakBeforeLines
            ?.filter { it > startLine && it < n }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        val pages = ArrayList<PageSlice>()
        var segStart = startLine
        var fi = 0
        while (segStart < n) {
            val segEnd = if (fi < forced.size) forced[fi] else n
            var cursorLine = segStart
            while (cursorLine < segEnd) {
                val pageStartLine = cursorLine
                val end = fillWholeLines(layout, pageStartLine, contentH)
                // 段内整行填充不越段尾：撞到强制行即提前收盘（该页允许不满）。
                val baseCut = if (end < segEnd) end else segEnd
                // Honour the break rules: a whole cluster that would be torn by this cut moves intact
                // to the next page (only when it fits a page); oversized clusters are allowed to tear.
                // 段尾是硬边界（强制断页压过 avoid），故再夹一次。
                val cut = applyAvoidRanges(layout, avoidRanges, pageStartLine, baseCut, contentH)
                    .coerceIn(pageStartLine + 1, segEnd)

                val charStart = layout.getLineStart(pageStartLine)
                val charEnd = if (cut >= n) layout.length else layout.getLineStart(cut)
                pages.add(PageSlice(charStart, charEnd, pageStartLine, cut))
                cursorLine = cut
            }
            segStart = segEnd
            fi++
            while (fi < forced.size && forced[fi] <= segStart) fi++
        }
        return pages
    }

    /**
     * Adjusts [baseCut] so an avoid range is never torn across a page, while guaranteeing the page
     * cursor always advances (no stall).
     *
     * For each avoid-range `[bs, be)` whose interior the current cut lands in:
     *  - if the range starts **after** this page's first line, retreat `cut` to `bs` — the whole
     *    range moves intact to the next page;
     *  - if the range starts exactly **at** this page's first line, extend `cut` to `be` — the
     *    whole range fills this page instead of a line cut splitting it.
     *
     * Only ranges that fit a single page are moved; oversized ranges are allowed to tear, matching
     * browser behavior. The caller clamps the result to the current forced segment.
     */
    private fun applyAvoidRanges(
        layout: BookLayout,
        avoidRanges: List<IntRange>,
        pageStartLine: Int,
        baseCut: Int,
        contentH: Int,
    ): Int = resolveAvoidCut(
        avoidRanges, pageStartLine, baseCut, contentH,
        topAt = layout::getLineTop, bottomAt = layout::getLineBottom,
    )

    /**
     * [applyAvoidRanges] 的**几何无关**版本（单源）：行顶/行底由 `topAt`/`bottomAt` 注入，于是
     * canonical 全章分页与临时页填充（`BoxChapterLayouter.shapeTempPageForward/Backward`）在各自的
     * 行空间里调用**同一段逻辑**，对 avoid 簇给出逐字相同的页边界。
     */
    fun resolveAvoidCut(
        avoidRanges: List<IntRange>,
        pageStartLine: Int,
        baseCut: Int,
        contentH: Int,
        topAt: (Int) -> Int,
        bottomAt: (Int) -> Int,
    ): Int {
        if (avoidRanges.isEmpty() || baseCut <= pageStartLine) return baseCut
        var cut = baseCut
        for (range in avoidRanges) {
            val bs = range.first
            val be = range.last + 1
            if (cut < bs || cut >= be) continue      // cut not inside this range
            val rangeH = bottomAt(be - 1) - topAt(bs)
            cut = retreatFor(cut, bs, be, rangeH, pageStartLine, contentH)
        }
        return cut.coerceAtLeast(pageStartLine + 1)
    }

    /**
     * **单簇内核**（几何无关）：[cut] 落在簇 `[bs, be)` 内部时按同一口径调整 —— 整簇装得下一页
     * （`clusterHeight <= contentH`）才动：簇首在页首之下的退到簇首（整簇搬到下一页），簇首恰为页首
     * 的装到簇尾（整行填充下恒为惰性）；装不下则允许撕裂（与浏览器 oversized 口径一致）。
     *
     * 独立暴露是因为**临时页填充**按块链自算簇的局部区间与实测高（没有全章行几何），但仍必须与
     * canonical 走同一条判据 —— 否则锚章临时表与磁盘表在 avoid 上漂移。
     */
    fun retreatFor(
        cut: Int,
        bs: Int,
        be: Int,
        clusterHeight: Int,
        pageStartLine: Int,
        contentH: Int,
    ): Int {
        if (cut < bs || cut >= be) return cut
        if (clusterHeight > contentH) return cut      // oversized cluster → allowed to tear
        if (bs > pageStartLine) return minOf(cut, bs)  // cluster starts below this page → move it whole to the next page
        if (bs == pageStartLine) return maxOf(cut, be) // cluster starts at this page → whole range fills this page
        return cut
    }
}