package orilumn.reader.engine.paging

/**
 * Cuts a fully laid-out [BookLayout] into pages according to the content-area height.
 *
 * Pagination is **line-coherent**: a page is a contiguous run of *whole* lines filled top to bottom.
 * A line is added to the current page only if it fully fits in the remaining content height —
 * otherwise that line is carried over entirely to the next page, so a page **never ends with a
 * partially visible (half) line**. Paragraphs flow continuously and are allowed to straddle page
 * boundaries; only the CSS Fragmentation constraints of a [BreakAwareBookLayout] are honoured:
 * `break-inside: avoid` atomic ranges ([BreakAwareBookLayout.avoidSplitRanges]), `break-before/after:
 * avoid` forbidden cut points ([BreakAwareBookLayout.forbiddenCutLines]) and forced page starts
 * ([BreakAwareBookLayout.forcedBreakBeforeLines]).
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
        // 帧上沿/下沿（[BookLayout.getPageFrameTop]/[getPageFrameBottom]）而不是行顶/行底：页首元素的
        // margin-top 与页首块的 padding/border 占本页页首空间，页尾叶自身边占本页页尾空间
        // （块边界缺口只归下一页，口径与收敛条件见 BookLayout 两函数的 KDoc）。少了这一段，绘制侧
        // 把页面从帧顶对齐时末行就被推出栅格裁掉 —— 这正是「最后一行半行」的真根因。
        val frameTop = layout.getPageFrameTop(startLine)
        while (end < layout.lineCount && layout.getPageFrameBottom(end) - frameTop <= contentH) end++
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
     * **断页规则**（[BreakAwareBookLayout]）：先按强制行
     * （[BreakAwareBookLayout.forcedBreakBeforeLines]）把行流**切段**，段首必为页首（上一页在该行
     * 之前结束，可能不满页）；段内整行填充后用 [BreakAwareBookLayout.avoidSplitRanges]（原子区间）与
     * [BreakAwareBookLayout.forbiddenCutLines]（avoid 边的禁止切点）修正切点。段尾是硬边界：avoid
     * 即使跨过强制行也不得越过它（强制断页压过 avoid —— 与 CSS 一致）。
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
        val forbiddenCutLines = aware?.forbiddenCutLines ?: emptySet()
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
                // Honour the break rules: a cut landing inside a `break-inside: avoid` block (that
                // fits a page) or on a `break-*: avoid` edge retreats to the previous legal break.
                // 段尾是硬边界（强制断页压过 avoid），故再夹一次。
                val cut = applyAvoidRanges(layout, avoidRanges, forbiddenCutLines, pageStartLine, baseCut, contentH)
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
     * Applies the two avoid constraints of a [BreakAwareBookLayout] to [baseCut], then guarantees
     * forward progress. See [resolveAvoidCut] for the exact rule.
     */
    private fun applyAvoidRanges(
        layout: BookLayout,
        avoidRanges: List<IntRange>,
        forbiddenCutLines: Set<Int>,
        pageStartLine: Int,
        baseCut: Int,
        contentH: Int,
    ): Int = resolveAvoidCut(
        avoidRanges, forbiddenCutLines, pageStartLine, baseCut, contentH,
        // 原子区间「装得下一页」的判据同样走**页面绘制帧**（含页首/页尾块的 padding 与 margin），
        // 否则带装饰的整块会被判成装得下、放上去仍溢出被裁。
        topAt = layout::getPageFrameTop, bottomAt = layout::getPageFrameBottom,
    )

    /**
     * [applyAvoidRanges] 的**几何无关**版本（单源）：行顶/行底由 `topAt`/`bottomAt` 注入，于是
     * canonical 全章分页与临时页填充（`BoxChapterLayouter.shapeTempPageForward/Backward`）在各自的
     * 行空间里调用**同一段逻辑**，对两条 avoid 约束给出逐字相同的页边界。
     *
     * 两类约束**正交**（CSS Fragmentation §4.4）：
     *
     * 1. **原子区间** [avoidRanges]（`break-inside: avoid`）：cut 落在区间内部且整区间装得下一页时
     *    退到区间首（整块搬到下一页）；装不下允许撕裂（oversized，与浏览器一致）。
     * 2. **禁止切点** [forbiddenCutLines]（`break-*: avoid` 边）：命中时退到**前一个合法断点** ——
     *    规范只否掉那一条 class A 断点，左侧块内部的行缝仍合法，所以逐行回退（连续回退越过一串
     *    1 行条目 = 把整串 avoid 边链顶到下一页）。
     *
     * 页内已无合法断点时，按规范放弃 discourage（drop rule 1/2/4），仍切在自然填充点——避免丢内容。
     */
    fun resolveAvoidCut(
        avoidRanges: List<IntRange>,
        forbiddenCutLines: Set<Int>,
        pageStartLine: Int,
        baseCut: Int,
        contentH: Int,
        topAt: (Int) -> Int,
        bottomAt: (Int) -> Int,
    ): Int {
        if (baseCut <= pageStartLine) return baseCut
        if (avoidRanges.isEmpty() && forbiddenCutLines.isEmpty()) return baseCut
        var cut = baseCut
        while (cut > pageStartLine) {
            if (cut !in forbiddenCutLines && !tornByAtomic(cut, avoidRanges, contentH, topAt, bottomAt)) {
                return cut
            }
            cut--
        }
        // 页内已无合法断点 ⇒ 按规范放弃 discourage，切在自然填充点（允许撕裂 / 跨过 avoid 边）。
        return baseCut
    }

    /**
     * [cut] 是否落在某个**装得下一页**的原子区间内部（区间 `[first, last]` 的内部 = 行缝
     * `first < cut <= last`）。装不下整页的区间（oversized）允许撕裂 ⇒ 不算被撕。
     */
    private fun tornByAtomic(
        cut: Int,
        ranges: List<IntRange>,
        contentH: Int,
        topAt: (Int) -> Int,
        bottomAt: (Int) -> Int,
    ): Boolean {
        for (r in ranges) {
            if (cut <= r.first || cut > r.last) continue
            if (bottomAt(r.last) - topAt(r.first) > contentH) continue
            return true
        }
        return false
    }
}