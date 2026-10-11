package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BreakAwareBookLayout

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
    result: BoxLayoutResult,
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
     * P4：分页侧的**断页约束单源**（[BreakClusters.plan]）——扁平块叶序列上的 `break-*: avoid`
     * 边簇（[avoidSplitRanges]）∪ `break-inside: avoid` 原子簇，以及强制边右侧的页首行
     * （[forcedBreakBeforeLines]）。
     *
     * 判据与 P3 的插图预算 [ReplacedFitBudget.clusterRange] 同源（`BreakClusters.joins`），
     * 保证「预算按簇缩图」与「分页保整簇」用的是同一个簇定义。
     *
     * `by lazy`：只在分页真的消费时才走查盒树；[breakInsideAvoidRanges] 是其 break-inside 子集，
     * 保留为独立属性（旧调用点/测试的既有语义不变）。
     */
    private val breakPlan: BreakClusters.Plan by lazy { BreakClusters.plan(BreakClusters.leavesOf(result.boxes)) }

    override val avoidSplitRanges: List<IntRange> get() = breakPlan.clusters

    override val forcedBreakBeforeLines: Set<Int> get() = breakPlan.forcedBeforeLines

    private fun collectBreakRanges(box: LayoutBox, out: MutableList<IntRange>) {
        if (box.breakInsideAvoid && box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            out.add(box.firstLineIndex until box.lastLineExclusive)
        }
        for (child in box.childBoxes) collectBreakRanges(child, out)
    }
}