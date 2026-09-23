package orilumn.reader.engine

/**
 * Shared temp-pagination navigation strategy (C1-2): pure, platform-free decisions both the
 * Android orchestration shell and the desktop host resolve identically.
 *
 * Moved verbatim from the app shell — the only Android-adjacent input (`ChapterUnit`) is
 * projected to plain values at the call boundary (see [backwardEntryAnchor]), so this file
 * never touches layout, drawing, or IO and stays JVM-testable.
 */

/**
 * Backward cross-chapter entry anchor: the char where the target chapter's LAST page begins when the
 * chapter already has pagination/slices; otherwise the chapter's last content char (`textLength−1`),
 * so a fresh large chapter's incoming anchor stream opens at the chapter END instead of the default
 * char 0 (head) — that default is exactly the source of 缺陷B (backward into an un-laid-out big chapter
 * landed on its first page). Pure → JVM unit-testable.
 *
 * @param tailPageCharStart the persisted table's last page charStart, or null when no table is bound.
 * @param lastSliceCharStart the live slices' last page charStart, or null when nothing is laid out.
 * @param textLength the chapter markup's total text length.
 */
fun backwardEntryAnchor(
    tailPageCharStart: Int?,
    lastSliceCharStart: Int?,
    textLength: Long,
): Int {
    val tailPageStart = tailPageCharStart ?: lastSliceCharStart
    return tailPageStart ?: (textLength - 1).coerceAtLeast(0).toInt()
}

/** Page index containing [targetChar], clamped to the LAST page when the char lies past the table's
 *  coverage. The disk table's `charEnd` stream is the visible-char advance ([visibleCharAdvance]:
 *  excludes `display:none`, counts an `img` as one slot), which can be SHORTER than
 *  `markup.textLength` (sum of every leaf + br). A tail anchor such as [backwardEntryAnchor]'s
 *  `textLength−1` fallback can therefore fall beyond the final page → `indexOfFirst` returns −1 →
 *  the old `coerceAtLeast(0)` degraded a backward cross-chapter landing to the chapter HEAD (缺陷C:
 *  blank window, `page[-1,-1)` stub). Clamping to the tail keeps the landing at its END page. Pure →
 *  JVM unit-testable. */
fun pageIndexForChar(
    pages: List<ChapterPaginationTable.PageRecord>,
    targetChar: Int,
): Int {
    if (pages.isEmpty()) return 0
    val idx = pages.indexOfFirst { it.charStart <= targetChar && it.charEnd > targetChar }
    return if (idx >= 0) idx else pages.lastIndex
}

/** Pure decision for ONE backward temp-nav step, free of Android/layout dependencies so it is JVM
 *  unit-testable. Encodes 缺陷A's contract: the CURRENT POINTER POSITION alone decides whether a
 *  backward flip is an in-chapter step ([TempNavBackwardStep.Move]) — even after the chapter head was
 *  ever pre-shaped (headReached) — and only the actual backward packing front (endInc/endFor) decides
 *  the TRUE chapter-head boundary ([TempNavBackwardStep.Boundary]).
 *
 * P10 (§6 sliding window): the `anchor*` inputs stand for the WINDOW HEAD-EDGE page (the page at
 *  `forwardPages[0]`) — the true anchor page until the bounded window slides past it. The derivation
 *  is identical either way; only the block/line feeds change, so the same contract keeps working after
 *  far-end eviction redefines the head edge. Before any eviction the head edge IS the anchor page, so
 *  historical inputs stay valid. */
fun tempNavBackwardPointerStep(
    curIsForward: Boolean,
    curIndex: Int,
    anchorBlockStart: Int,
    anchorLineCharStart: Int,
    backFrontBlock: Int,
    anchorBlockCharStart: Int,
    backwardPageCount: Int,
    deepestBackwardBlockStart: Int?,
    deepestBackwardStartChar: Int = -1,
    deepestBackwardBlockCharStart: Int = -1,
): TempNavBackwardStep {
    if (curIsForward) {
        if (curIndex > 0) return TempNavBackwardStep.Move(true, curIndex - 1)
        // At the anchor page: step to the page right before it (backwardPages is nearest-anchor-first).
        if (backwardPageCount > 0) return TempNavBackwardStep.Move(false, 0)
        // No backward page shaped yet: the page right before the anchor ends at the anchor line (so the
        // anchor block's leading lines are covered and never lost); when the anchor page is whole-block
        // (anchor at a block start) it just ends at the block boundary.
        val lineCut = if (anchorLineCharStart > anchorBlockCharStart) anchorLineCharStart else -1
        val endInc = if (lineCut >= 0) anchorBlockStart + 1 else backFrontBlock
        return if (endInc <= 0) TempNavBackwardStep.Boundary else TempNavBackwardStep.Shape(endInc, lineCut)
    }
    val next = curIndex + 1
    if (next < backwardPageCount) return TempNavBackwardStep.Move(false, next)
    val endFor = deepestBackwardBlockStart ?: anchorBlockStart
    if (endFor <= 0) return TempNavBackwardStep.Boundary
    // 缝修复: the page extended further back must END exactly where the deepest backward page STARTS.
    // When that page's first line begins mid-block, this page must still include that block's leading
    // lines (endExcl = block+1, lineCut = the page's start char); using the bare block boundary would
    // drop the leading lines of the block the deepest page only partially covers — a page-seam gap.
    val cut = if (deepestBackwardBlockStart != null &&
        deepestBackwardBlockCharStart >= 0 &&
        deepestBackwardStartChar > deepestBackwardBlockCharStart
    ) deepestBackwardStartChar else -1
    val endExl = if (cut >= 0) endFor + 1 else endFor
    return TempNavBackwardStep.Shape(endExl, cut)
}

/** Result of [tempNavBackwardPointerStep]. */
sealed class TempNavBackwardStep {
    /** The next backward flip is inside the shaped page lists; move the pointer, no shaping. */
    data class Move(val curIsForward: Boolean, val curIndex: Int) : TempNavBackwardStep()

    /** Shape the next backward page ending at [endExclusive] ([lineCut] when the first page cuts the
     *  anchor block mid-line); then add it and move the pointer onto it. */
    data class Shape(val endExclusive: Int, val lineCut: Int) : TempNavBackwardStep()

    /** True chapter-head boundary: no in-chapter page lies before; the caller goes cross-chapter. */
    object Boundary : TempNavBackwardStep()
}
