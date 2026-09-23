package orilumn.reader.engine.text

import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.FlowedLine
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.skia.WindowedBookLayout

/**
 * The new box engine as a **[WindowedBookLayout]**: backs the geometry ([BookLayout]) shared with
 * the pure-JVM engine; skia windows come from the shared base. Glyphs go through the skia
 * [DrawLine] window. (C2-P2b-3: rebased off the Android `BoxPageRenderer` — canvas drawing of
 * these products only had the dormant curl caller, which now no-ops; shapes are interface-typed.)
 *
 * This is the **full-chapter** renderer (one box tree for the whole chapter); the incremental
 * `PartialDrawableLayout` in [orilumn.reader.engine.BoxChapterLayouter] shares the same base.
 *
 * @param result full-chapter layout: line stream + box tree.
 * @param shapeList one shaped paragraph per leaf (index-aligned with the collected leaves).
 * @param loader optional EPUB image loader; when non-null, `<img>` blocks draw real bitmaps.
 * @param href the current chapter's spine href, used to resolve relative `src` paths.
 */
class BoxDrawableLayout(
    private val result: BoxLayoutResult,
    private val shapeList: List<ParagraphShapeRef>,
    private val loader: ImageLoader? = null,
    private val href: String = "",
    /** Q1-a：skia 绘制的每行 [orilumn.reader.engine.skia.DrawLine] 窗口
     *  （行下标 = 本 drawable 的 lineCount 域）; null → canvas 兜底逐行绘制。 */
    protected override val skiaLines: Map<Int, orilumn.reader.engine.skia.DrawLine>? = null,
    /** 表格行展开（行下标 → 单元格文本行；Compose 阅读面随行窗绘制，见基类）。 */
    protected override val tableCells: Map<Int, List<orilumn.reader.engine.skia.DrawLine>> = emptyMap(),
    /** 表格单元格边框（章节绝对 Y；随背景绘制）。 */
    protected override val tableBorders: List<orilumn.reader.engine.skia.PageBackground> = emptyList(),
) : WindowedBookLayout() {

    private val lines: List<FlowedLine> = result.lines

    init {
        require(collectLeaves(result.boxes).size == shapeList.size) {
            "shapes count(${shapeList.size}) != leaf count(${collectLeaves(result.boxes).size})"
        }
    }

    // ---- WindowedBookLayout structure ----

    protected override val shapes: List<ParagraphShapeRef> get() = shapeList
    protected override val leaves: List<LayoutBox> = collectLeaves(result.boxes)
    protected override val leafLocalFirstLine: IntArray = IntArray(leaves.size) { i -> leaves[i].firstLineIndex.coerceAtLeast(0) }
    protected override val boxesForDraw: List<LayoutBox> get() = result.boxes
    protected override val imageLoader: ImageLoader? get() = loader
    protected override val chapterHref: String get() = href

    private fun collectLeaves(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(collectLeaves(b.childBoxes)) else out.add(b)
        return out
    }

    // ---- BreakAwareBookLayout ----

    override val breakInsideAvoidRanges: List<IntRange> by lazy {
        val out = ArrayList<IntRange>()
        for (box in result.boxes) collectBreakRanges(box, out)
        out
    }

    private fun collectBreakRanges(box: LayoutBox, out: MutableList<IntRange>) {
        if (box.breakInsideAvoid && box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            out.add(box.firstLineIndex until box.lastLineExclusive)
        }
        for (child in box.childBoxes) collectBreakRanges(child, out)
    }

    // ---- BookLayout geometry ----

    override val lineCount: Int get() = lines.size

    override val length: Int get() = lines.lastOrNull()?.charEnd ?: 0

    override fun getLineTop(i: Int): Int = lines[i].yTop

    override fun getLineBottom(i: Int): Int = lines[i].yBottom

    override fun getLineStart(i: Int): Int = lines[i].charStart

    override fun getLineEnd(i: Int): Int = lines[i].charEnd

    override fun isParagraphBoundaryLine(i: Int): Boolean = lines[i].paragraphStart
}