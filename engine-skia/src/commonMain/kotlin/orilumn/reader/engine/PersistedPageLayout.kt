package orilumn.reader.engine

import orilumn.reader.engine.paging.BreakAwareBookLayout
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.engine.skia.PagedLayout

/**
 * 由 [PageSnapshot] 直接供料的绘制布局：**不经塑形**回答"这一页画什么"。
 *
 * 只服务快照里那一页。放在 `unit.layout` 上的作用与一次正常增量排版完全相同——
 * 控制器/阅读面的回读口径（[skiaLineWindow] / [pageBackgrounds] / [pageImages] /
 * [tableCellLines]）与 [orilumn.reader.engine.paging.BookLayout] 的行几何逐项同签名，
 * 因此绘制、选区、点按链接、页首缺口计算全都不用分叉。
 *
 * ## 边界（为什么这里给空表是安全的）
 *
 * - `break-inside: avoid` 等分页约束一律空：本布局**只用于显示已经分好页的那一页**，
 *   分页（[orilumn.reader.engine.paging.Paginator]）从不在它上面跑——磁盘表已经是正典，
 *   需要重排时会先 `invalidateLayout` 再走正常塑形路径。
 * - 请求的区间不在本页内时返回空表（与窗口布局"窗外没有内容"同义），调用方据此走原有的
 *   `ensurePageRangeShaped` 重新成形，不会拿本页内容冒充别页。
 */
class PersistedPageLayout(val snapshot: PageSnapshot) : PagedLayout, BreakAwareBookLayout {

    /** 窗口行数（= 快照里存下的行几何条数）。 */
    override val lineCount: Int get() = snapshot.lineTop.size

    /** 末行行尾字符（与 `PartialDrawableLayout.length` 同式）。 */
    override val length: Int get() = snapshot.lineEnd.lastOrNull() ?: 0

    override fun getLineTop(i: Int): Int = snapshot.lineTop.getOrElse(i) { 0 }

    override fun getLineBottom(i: Int): Int = snapshot.lineBottom.getOrElse(i) { getLineTop(i) }

    override fun getLineStart(i: Int): Int = snapshot.lineStart.getOrElse(i) { 0 }

    override fun getLineEnd(i: Int): Int = snapshot.lineEnd.getOrElse(i) { getLineStart(i) }

    override fun isParagraphBoundaryLine(i: Int): Boolean = snapshot.lineParagraph.getOrElse(i) { false }

    // 帧上下沿与页首缺口**按行取存下来的值**，不再走默认递归式：默认式要"上一行帧底"，
    // 而快照只保证本页那些行可信；直接存结果既省一次递归，也避免边界行取到窗口外的值。
    override fun getPageFrameTop(i: Int): Int = snapshot.frameTop.getOrElse(i) { getLineTop(i) }

    override fun getPageFrameBottom(i: Int): Int = snapshot.frameBottom.getOrElse(i) { getLineBottom(i) }

    override fun getPageTopLead(i: Int): Int = snapshot.topLead.getOrElse(i) { 0 }

    /** 行下标 → 行。快照只存本页那些行，窗口里其它行（若有）不提供。 */
    private val window: Map<Int, DrawLine> = buildMap {
        val n = minOf(snapshot.lines.size, snapshot.lineIndex.size)
        for (k in 0 until n) put(snapshot.lineIndex[k], snapshot.lines[k])
    }

    override fun skiaLineWindow(): Map<Int, DrawLine> = window

    override fun tableCellLines(firstLine: Int, lastLineExclusive: Int): List<DrawLine> =
        if (covers(firstLine, lastLineExclusive)) snapshot.tableCells else emptyList()

    override fun pageBackgrounds(firstLine: Int, lastLineExclusive: Int): List<PageBackground> =
        if (covers(firstLine, lastLineExclusive)) snapshot.backgrounds else emptyList()

    override fun pageImages(firstLine: Int, lastLineExclusive: Int): List<PageImage> =
        if (covers(firstLine, lastLineExclusive)) snapshot.images else emptyList()

    /** 请求区间落在本页内（含边界相接）才供料。 */
    private fun covers(firstLine: Int, lastLineExclusive: Int): Boolean =
        firstLine >= snapshot.firstLine && lastLineExclusive <= snapshot.lastLineExclusive

    // 分页约束：分页器不在本布局上运行（见类注释），一律空。
    override val breakInsideAvoidRanges: List<IntRange> get() = emptyList()

    override val forbiddenCutLines: Set<Int> get() = emptySet()

    override val forcedBreakBeforeLines: Set<Int> get() = emptySet()
}
