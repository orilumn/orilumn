package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BreakAwareBookLayout
import kotlin.math.roundToInt

/**
 * [BookLayout] backed by the box-flow line stream produced by [NormalFlowLayout].
 *
 * The paging algorithm consumes only [BookLayout], so [BoxLayouter] can feed the Paginator entirely
 * through this type without touching rendering. As a [BreakAwareBookLayout] it also surfaces the
 * pagination break constraints — the avoid ranges (`break-inside: avoid` union `break-*: avoid` edge
 * clusters) and the forced page-start lines — so the Paginator both keeps clusters whole and honours
 * `break-*: always`.
 *
 * Coordinates follow the BookLayout convention (y relative to the content-area top, char offsets
 * into the whole stream).
 */
class BoxBookLayout(
    private val result: BoxLayoutResult,
) : BreakAwareBookLayout {

    private val lines: List<FlowedLine> = result.lines

    override val lineCount: Int get() = lines.size

    override val length: Int get() = lines.lastOrNull()?.charEnd ?: 0

    override fun getLineTop(i: Int): Int = lines[i].yTop

    override fun getLineBottom(i: Int): Int = lines[i].yBottom

    override fun getLineStart(i: Int): Int = lines[i].charStart

    override fun getLineEnd(i: Int): Int = lines[i].charEnd

    override fun isParagraphBoundaryLine(i: Int): Boolean = lines[i].paragraphStart

    /**
     * 行下标 → 该行为页尾行时**页尾叶自身**的下边界高（`padding-bottom + border-bottom`）。
     * 文本叶恒 0（重路径文本叶盒的盒属性被清零，见 `NormalFlowLayout.emit`），与绘制侧
     * `BoxChapterLayouter.ownBottomEdges` 同判据；容器链的底边不计（重/轻路径必须同值，口径见
     * [BookLayout.getPageFrameBottom]）。
     */
    private val leafBottomEdgesByLastLine: IntArray by lazy {
        val out = IntArray(lineCount)
        fun walk(box: LayoutBox) {
            if (box.isContainer) {
                for (c in box.childBoxes) walk(c)
                return
            }
            if (box.firstLineIndex < 0 || box.lastLineExclusive <= box.firstLineIndex) return
            val last = box.lastLineExclusive - 1
            if (last in out.indices) {
                out[last] = if (box.el?.isText == true) 0
                else (box.style.padding.bottom + box.style.border.bottom).roundToInt()
            }
        }
        for (b in result.boxes) walk(b)
        out
    }

    override fun getPageFrameBottom(i: Int): Int =
        getLineBottom(i) + if (i in 0 until lineCount) leafBottomEdgesByLastLine[i] else 0

    /**
     * Line ranges that must stay whole across a page break. A box (leaf or container) contributes a
     * range when it opts into `break-inside: avoid` and actually owns lines. Whether a range
     * physically fits a page is left to the Paginator (the only party that knows the page height),
     * which retreats to the range start only when the whole range fits.
     */
    override val breakInsideAvoidRanges: List<IntRange> by lazy {
        val out = ArrayList<IntRange>()
        for (box in result.boxes) collectBreakRanges(box, out)
        out
    }

    /**
     * P4：分页侧的**断页约束单源**（[BreakClusters.plan]）——扁平块叶序列上的
     * `break-inside: avoid` 原子区间（[avoidSplitRanges]）、`break-*: avoid` 边的禁止切点
     * （[forbiddenCutLines]）与强制边右侧的页首行（[forcedBreakBeforeLines]）。
     *
     * 边判据与 P3 的插图预算 [ReplacedFitBudget.clusterRange] 同源（`BreakClusters.joins`），
     * 保证「预算按 avoid 边链缩图」与「分页不跨这条边」用的是同一个判据。
     *
     * `by lazy`：只在分页真的消费时才走查盒树；[breakInsideAvoidRanges] 是其 break-inside 子集，
     * 保留为独立属性（旧调用点/测试的既有语义不变）。
     */
    private val breakPlan: BreakClusters.Plan by lazy { BreakClusters.plan(BreakClusters.leavesOf(result.boxes)) }

    override val avoidSplitRanges: List<IntRange> get() = breakPlan.atomicRanges

    override val forbiddenCutLines: Set<Int> get() = breakPlan.forbiddenCutLines

    override val forcedBreakBeforeLines: Set<Int> get() = breakPlan.forcedBeforeLines

    private fun collectBreakRanges(box: LayoutBox, out: MutableList<IntRange>) {
        if (box.breakInsideAvoid && box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            out.add(box.firstLineIndex until box.lastLineExclusive)
        }
        for (child in box.childBoxes) collectBreakRanges(child, out)
    }
}