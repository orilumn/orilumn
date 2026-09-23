package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.laying.BoxDrawer
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
 * 逐行一致（`shape.originTop()` 恒为 0，见 `ParagraphShape.originTop`，此处内联）；
 * 画笔/绘制留在 `:app` 基类，本类只做数据。行几何（`BookLayout`）由子类按行流实现。
 */
abstract class WindowedBookLayout : PagedLayout, BreakAwareBookLayout {
    protected abstract val leaves: List<LayoutBox>
    protected abstract val shapes: List<ParagraphShapeRef>
    protected abstract val leafLocalFirstLine: IntArray
    protected abstract val boxesForDraw: List<LayoutBox>
    protected abstract val imageLoader: ImageLoader?
    protected abstract val chapterHref: String
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
        val out = BoxDrawer.drawOnPage(boxesForDraw, firstLine, pageEnd, bandTop, bandBottom).mapNotNull { r ->
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
            val gearIndex = leafLocalFirstLine[si]
            if (gearIndex < firstLine || gearIndex >= pageEnd) continue
            val src = el.attrs["src"] ?: continue
            if (chapterHref.isBlank()) continue
            val xOff = (leaf.contentLeft + (leaf.style.border.left + leaf.style.padding.left)).roundToInt()
            val leafBreakW = NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth)
            val usedW = NormalFlowLayout.replacedUsedSize(
                el, leaf.style, leafBreakW, imageLoader, chapterHref,
            ).first.coerceAtLeast(1)
            // `originTop()` 恒 0（见 `ParagraphShape.originTop`），直接取行顶。
            val yTop = getLineTop(gearIndex)
            val h = shape.shapeLineBottom(0).coerceAtLeast(1)
            out.add(
                PageImage(
                    src = src,
                    chapterHref = chapterHref,
                    xLeft = xOff,
                    yTop = yTop,
                    yBottom = yTop + h,
                    widthPx = usedW,
                    heightPx = h,
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
