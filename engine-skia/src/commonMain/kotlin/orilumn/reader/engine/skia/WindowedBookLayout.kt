package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.ClipRect
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BreakAwareBookLayout
import kotlin.math.roundToInt

/**
 * C2-P2b-3: 分页产品公共类型 = 行几何（[BookLayout]）+ 回读窗口（[LayoutReadback]）。
 *
 * 搬走的编排代码只认它构造/持有产品；`:app` 的绘制桥（`BoxPageRenderer` 系）在 P2b-4 后
 * 不再是产品的基类（canvas 整页绘制本就只有休眠调用方，见 C2 文档 §6）。
 */
interface PagedLayout : BookLayout, LayoutReadback {
    /**
     * Debug: read back the painted text of lines `[firstLine, lastLineExclusive)` straight
     * from each leaf's shape — the very text the draw path feeds out. Default empty
     * (non-windowed products); [WindowedBookLayout] carries the real implementation.
     */
    fun debugPageText(firstLine: Int, lastLineExclusive: Int): String = ""
}

/**
 * C2-P2b-3: 窗口数据实现（从 `:app` `BoxPageRenderer` 原样搬运，只换形状类型）。
 *
 * `skiaLineWindow`/`tableCellLines`/`pageImages`/`pageBackgrounds` 四个回读口径与基类
 * 逐行一致（`shape.originTop()` 恒为 0，见 `ParagraphShapeRef.originTop`，此处内联）；
 * 画笔/绘制留在 `:app` 基类，本类只做数据。行几何（`BookLayout`）由子类按行流实现。
 */
abstract class WindowedBookLayout : PagedLayout, BreakAwareBookLayout {
    protected abstract val leaves: List<LayoutBox>
    protected abstract val shapes: List<ParagraphShapeRef>
    protected abstract val leafLocalFirstLine: IntArray
    protected abstract val boxesForDraw: List<LayoutBox>
    protected abstract val imageLoader: ImageLoader?
    protected abstract val chapterHref: String
    protected open val density: Float = 1f
    protected abstract val skiaLines: Map<Int, DrawLine>?
    protected abstract val tableCells: Map<Int, List<DrawLine>>
    protected abstract val tableCellImages: Map<Int, List<PageImage>>
    protected abstract val tableBorders: List<PageBackground>

    override fun skiaLineWindow(): Map<Int, DrawLine>? = skiaLines

    /** Line → owning leaf index, so readback resolves a line's leaf without a per-line scan. */
    protected val leavesByShape: IntArray by lazy {
        val out = IntArray(lineCount) { -1 }
        leaves.forEachIndexed { i, _ ->
            val lo = leafLocalFirstLine[i].coerceAtLeast(0)
            val hi = lo + shapes[i].lineCount.coerceAtLeast(0)
            for (g in lo until minOf(hi, lineCount)) out[g] = i
        }
        out
    }

    /**
     * 页尾叶自身边（`padding-bottom + border-bottom`；文本叶恒 0，与重路径文本叶盒盒属性清零、
     * 与 `BoxChapterLayouter.ownBottomEdges` 同判据）。
     */
    private fun ownLeafBottomEdges(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0
        else (leaf.style.padding.bottom + leaf.style.border.bottom).roundToInt()

    /**
     * 行下标 → 该行为**页尾行**时页尾叶自身占的下边界高（[BookLayout.getPageFrameBottom] 的覆盖）。
     *
     * 每条叶只在它的**末行**记一次：叶的行区间由 [leafLocalFirstLine] + 自身 shape 行数给出，
     * **不读 `LayoutBox.firstLineIndex/lastLineExclusive`** —— 相邻临时页共用同一批盒、会覆写这些字段
     * （见 [PartialDrawableLayout] 的注释）。叶若在窗口里被截断（末行在窗外）则落不进本流，
     * 自然不记（叶在窗外仍在继续，本页不欠它下边）。
     */
    private val leafBottomEdgesByLastLine: IntArray by lazy {
        val out = IntArray(lineCount)
        for (j in leaves.indices) {
            val n = shapes[j].lineCount
            if (n <= 0) continue
            val lo = leafLocalFirstLine[j]
            if (lo < 0) continue
            val last = lo + n - 1
            if (last in out.indices) out[last] = ownLeafBottomEdges(leaves[j])
        }
        out
    }

    override fun getPageFrameBottom(i: Int): Int =
        getLineBottom(i) + if (i in 0 until lineCount) leafBottomEdgesByLastLine[i] else 0

    /**
     * 叶自身 top 边（`padding-top + border-top`；文本叶恒 0，与 [ownLeafBottomEdges] 同判据）。
     */
    private fun ownLeafTopEdges(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0
        else (leaf.style.padding.top + leaf.style.border.top).roundToInt()

    /** 叶自身 `margin-top`（文本叶恒 0）。章首段前距用，见 [BookLayout.getPageTopLead]。 */
    private fun ownLeafMarginTop(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0 else leaf.style.margin.top.roundToInt()

    /**
     * 行下标 → 该行为**叶首行**时页首叶自身占的上边界高（[BookLayout.getPageTopLead] 的覆盖）。
     *
     * 与 [leafBottomEdgesByLastLine] 同款：每条叶只在它的**首行**记一次，行区间由
     * [leafLocalFirstLine] + 自身 shape 行数给出，**不读 `LayoutBox.firstLineIndex`**（相邻临时页
     * 共用同一批盒、会覆写这些字段）。叶的首行在窗口之上（本流里已被截掉）则不记——叶在本页
     * 仍是中段开页，页首无缺口，与 canonical 同判据。
     */
    private val leafTopEdgesByFirstLine: IntArray by lazy {
        val out = IntArray(lineCount)
        for (j in leaves.indices) {
            val n = shapes[j].lineCount
            if (n <= 0) continue
            val lo = leafLocalFirstLine[j]
            if (lo in out.indices) out[lo] = ownLeafTopEdges(leaves[j])
        }
        out
    }

    /**
     * 行 0 所属叶自身 `margin-top`：章首段前距**不与页边距合并**，照常画在章首页页首
     * （[BookLayout.getPageTopLead] 的 `i == 0` 分支）。
     *
     * 窗口（[PartialDrawableLayout]）另按调用方传入的窗外缺口 [getPageFrameTop] 覆盖 `i <= 0`，
     * 不看这里；本值只服务**整章**绘制布局（[orilumn.reader.engine.text.BoxDrawableLayout]），
     * 它的行 0 就是章首行。
     */
    private val chapterHeadMarginTop: Int by lazy {
        val si = if (lineCount > 0) leavesByShape[0] else -1
        if (si in leaves.indices) ownLeafMarginTop(leaves[si]) else 0
    }

    /**
     * 祖先元素的计算样式（v4 页首缺口要沿盒链上溯找**可见外沿盒**，见
     * [NormalFlowLayout.pageTopLead]）。null = 本实现没有样式源 ⇒ 退回「叶自身 top 边」口径
     * （[BoxDrawableLayout] 与 [PartialDrawableLayout] 各自覆盖；前者吃整章盒树，后者吃调用方的
     * `prepare`）。
     */
    protected open fun elementStyle(el: MarkupElement): ComputedStyle? = null

    /**
     * E11：元素**内容**（插图/替换叶）所受的 `overflow` 祖先裁剪（含元素自身裁剪盒）。
     * null = 本实现没有裁剪源（或该元素不受裁）⇒ 旧路径不裁。坐标与 [getLineTop] 同一 Y 口径
     * （增量/临时页是窗口局部 Y），故裁剪源必须与本次行流同源。
     */
    protected open fun elementClip(el: MarkupElement?): ClipRect? = null

    /** 行下标 → 该行为**叶首行**时该叶的元素（非叶首行 = null）。v4 页首缺口按盒链上溯要用它。 */
    private val leafElementByFirstLine: Array<MarkupElement?> by lazy {
        val out = arrayOfNulls<MarkupElement>(lineCount)
        for (j in leaves.indices) {
            val lo = leafLocalFirstLine[j]
            if (lo in out.indices) out[lo] = leaves[j].el
        }
        out
    }

    /**
     * [BookLayout.getPageTopLead]：章首（i == 0）叶自身 top 边 + 首叶自身 `margin-top`（章首段前距
     * 不与页边距合并）；其余页首按 [NormalFlowLayout.pageTopLead]（v4：**可见外沿盒**的边框顶到叶
     * 首行行顶，外沿盒**以下**一切保留，其上折叠 `margin-top` 并入页边距）。无样式源或样式缺项时
     * 退回叶自身 top 边（v3 口径）。
     */
    override fun getPageTopLead(i: Int): Int {
        if (i !in 0 until lineCount) return 0
        val edges = leafTopEdgesByFirstLine[i]
        if (i == 0) return edges + chapterHeadMarginTop
        val el = leafElementByFirstLine[i] ?: return edges
        if (elementStyle(el) == null) return edges
        return NormalFlowLayout.pageTopLead(el, ::elementStyle)
    }

    override fun tableCellLines(firstLine: Int, lastLineExclusive: Int): List<DrawLine> {
        if (firstLine < 0 || lastLineExclusive <= firstLine || tableCells.isEmpty()) return emptyList()
        val out = ArrayList<DrawLine>()
        for ((idx, cls) in tableCells.entries.sortedBy { it.key }) if (idx in firstLine until lastLineExclusive) out.addAll(cls)
        return out
    }

    override fun pageBackgrounds(firstLine: Int, lastLineExclusive: Int): List<PageBackground> {
        if (firstLine < 0 || lastLineExclusive <= firstLine) return emptyList()
        val pageEnd = minOf(lastLineExclusive, lineCount)
        if (firstLine >= pageEnd) return emptyList()
        val bandTop = getLineTop(firstLine)
        val bandBottom = getLineBottom(pageEnd - 1)
        if (bandBottom <= bandTop) return emptyList()
        val out = BoxDrawer.drawOnPage(boxesForDraw, firstLine, pageEnd, bandTop, bandBottom, density).mapNotNull { r ->
            val argb = orilumn.reader.engine.css.cssHexToArgb(r.colorHex) ?: return@mapNotNull null
            PageBackground(
                left = r.left,
                yTop = r.top,
                yBottom = r.bottom,
                right = r.right,
                argb = argb,
                border = r.kind == orilumn.reader.engine.laying.DrawKind.BORDER,
                radii = r.radii,
                alpha = r.alpha,
                shadow = r.shadow,
                strokeWidthPx = r.strokeWidthPx,
                bgSrc = r.bgImage?.url,
                bgChapterHref = chapterHref,
                bgRepeat = r.bgImage?.repeat ?: orilumn.reader.engine.css.BackgroundRepeat.REPEAT,
                bgPosition = r.bgImage?.position ?: orilumn.reader.engine.css.BackgroundPosition(),
                bgBoxTop = r.bgBoxTop,
                bgBoxBottom = r.bgBoxBottom,
                // E11：`overflow` 祖先裁剪随指令带上（渲染层画布裁剪；null = 不裁）。
                clip = r.clip,
            )
        }.toMutableList()
        // 表格单元格边框（与表行文本展开同源；只取与本页带相交者）。
        for (b in tableBorders) {
            if (b.yBottom > bandTop && b.yTop < bandBottom) out.add(b)
        }
        return out
    }

    override fun pageImages(firstLine: Int, lastLineExclusive: Int): List<PageImage> {
        if (firstLine < 0 || lastLineExclusive <= firstLine) return emptyList()
        val pageEnd = minOf(lastLineExclusive, lineCount)
        if (firstLine >= pageEnd) return emptyList()
        val out = ArrayList<PageImage>()
        for (si in leaves.indices) {
            val shape = shapes[si]
            if (!shape.isReplaceable) continue
            val leaf = leaves[si]
            if (leaf.table != null) continue
            val el = leaf.el
            if (el == null || el.tag != "img") continue
            // E10：隐藏的替换叶不落图（占位照旧）。
            if (leaf.style.visibilityHidden) continue
            val gearIndex = leafLocalFirstLine[si]
            if (gearIndex < firstLine || gearIndex >= pageEnd) continue
            val src = el.attrs["src"] ?: continue
            if (chapterHref.isBlank()) continue
            val baseX = (leaf.contentLeft + (leaf.style.border.left + leaf.style.padding.left)).roundToInt()
            val leafBreakW = NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth)
            // P3：绘制与测量读**同一**高度预算（盒上的 replaceableFitH）——量画同值的唯一渠道。
            val usedW = NormalFlowLayout.replacedUsedSize(
                el, leaf.style, leafBreakW, imageLoader, chapterHref, leaf.replaceableFitH, density,
            ).first.coerceAtLeast(1)
            // 块级替换元 `margin-left/right: auto` 水平居中（CSS 2.1 §10.3.3：剩余空间进 auto 边）；
            // 悬浮图不参与（左右对齐另有归属）。
            val spare = leafBreakW - usedW
            val centerOff = if (spare > 0 && leaf.style.floatSide == orilumn.reader.engine.css.FloatSide.NONE) {
                when {
                    leaf.style.marginLeftAuto && leaf.style.marginRightAuto -> spare / 2
                    leaf.style.marginLeftAuto -> spare
                    else -> 0
                }
            } else 0
            val xOff = baseX + centerOff
            // `originTop()` 恒 0（见 `ParagraphShapeRef.originTop`），直接取行顶。
            val yTop = getLineTop(gearIndex)
            val h = shape.shapeLineBottom(0).coerceAtLeast(1)
            out.add(
                PageImage(
                    src = src,
                    chapterHref = chapterHref,
                    // E12：`position: relative` 的绘制偏移（叶的累积偏移；两端为纯增量，与坐标系无关）。
                    xLeft = xOff + leaf.relOffsetX,
                    yTop = yTop + leaf.relOffsetY,
                    yBottom = yTop + leaf.relOffsetY + h,
                    widthPx = usedW,
                    heightPx = h,
                    // E11：`overflow` 祖先裁剪（重路径吃整章盒树、轻路径吃窗口聚合表）。
                    clip = elementClip(el),
                ),
            )
        }
        // 表格图（与表行文本同窗；行下标键与 tableCells 一致）。
        for ((idx, imgs) in tableCellImages.entries) {
            if (idx in firstLine until pageEnd) out.addAll(imgs)
        }
        return out
    }

    /**
     * Debug: read back the painted text of lines `[firstLine, lastLineExclusive)` straight from
     * each leaf's shape — the very text the draw path feeds out. Unlike a markup-tree walk this
     * lives in the engine's leaf char space, so it mirrors the screen exactly. Inline `U+FFFC`
     * object placeholders become `[img]`; replaceable blocks (img / table) become `[img]`/`[table]`.
     * (Moved verbatim from `:app BoxPageRenderer`; `:app` keeps only the canvas bridge.)
     */
    override fun debugPageText(firstLine: Int, lastLineExclusive: Int): String {
        if (firstLine < 0 || lastLineExclusive <= firstLine) return ""
        val pageEnd = minOf(lastLineExclusive, lineCount)
        if (firstLine >= pageEnd) return ""
        if (skiaLines != null) return skiaDebugPageText(firstLine, pageEnd)
        val sb = StringBuilder()
        val done = BooleanArray(leaves.size)
        for (g in firstLine until pageEnd) {
            val si = leavesByShape[g]
            if (si < 0 || si >= leaves.size || done[si]) continue
            done[si] = true
            val leaf = leaves[si]
            val shape = shapes[si]
            val gearIndex = leafLocalFirstLine[si]
            if (gearIndex < 0 || shape.lineCount <= 0) continue
            if (shape.isReplaceable) {
                sb.append(if (leaf.table != null) "[table]\n" else "[img]\n")
                continue
            }
            val text = shape.text
            if (text.isEmpty()) continue
            val k0 = (maxOf(gearIndex, firstLine) - gearIndex).coerceIn(0, shape.lineCount - 1)
            val k1 = (minOf(gearIndex + shape.lineCount, pageEnd) - 1 - gearIndex).coerceIn(0, shape.lineCount - 1)
            for (k in k0..k1) {
                val s = shape.lineStart(k)
                val e = shape.lineEnd(k)
                if (s in 0..e && e <= text.length) sb.append(text.subSequence(s, e))
                sb.append('\n')
            }
        }
        return sb.toString().replace("\uFFFC", "[img]")
    }

    /**
     * Q1-a 窗口模式的调试读回：直接从本页的 [DrawLine] 窗口切片文本（range 即屏幕画出的子串），
     * 替换块行（img/table）不在窗口内，仍按 [img]/[table] 回显。
     */
    private fun skiaDebugPageText(firstLine: Int, pageEnd: Int): String {
        val sb = StringBuilder()
        val done = BooleanArray(leaves.size)
        for (g in firstLine until pageEnd) {
            val dl = skiaLines?.get(g)
            if (dl != null) {
                sb.append(dl.text.substring(dl.range))
                sb.append('\n')
                continue
            }
            val si = leavesByShape[g]
            if (si < 0 || si >= leaves.size || done[si]) continue
            done[si] = true
            sb.append(if (leaves[si].table != null) "[table]\n" else "[img]\n")
        }
        return sb.toString()
    }
}
