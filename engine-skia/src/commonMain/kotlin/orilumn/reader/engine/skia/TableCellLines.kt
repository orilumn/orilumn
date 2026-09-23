package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpaceNormalize
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.laying.TableCellLayout
import orilumn.reader.engine.laying.TableRowLayout
import kotlin.math.roundToInt

/**
 * 表格行 → Skia 窗：把已塑形的单元格逐行展开成 [DrawLine]（文本）+ [PageBackground]
 * 边框矩形，随正文行窗一起交付双端绘制/点按。
 *
 * 背景：Compose 阅读面（shared-ui `ReaderPageCanvas`）只消费行窗 + 背景 + 插图，
 * 表行从不产出 [DrawLine]（替换叶），故此前表体零像素（仅 caption 块可见）。
 * 本展开让单元格文本走与正文完全同一的量画路径（`LineWindowDrawer` + `LineHitTest`），
 * 同 helper 双路一致；边框逐边绘制（只画有宽且 style 非 NONE 的边，色跟随书内 CSS
 * `border-color`，未声明回退 `currentColor`；遗留 Android `drawTableRow` 仍为固定 `#ff999999`）。
 *
 * - 断行几何不动：行宽/对齐/字号与塑形侧（`ParagraphShapes.shapeOf` 的 cell 宽）同源，
 *   此处只投影、不重排；
 * - 字符流不动：`charBase` 按行首 + 单元格累计文本长换算，与分页 `rowChars` 同账；
 * - 无 shape 的格（桌面旧路径未塑形）/ `empty-cells: hide` 空格直接跳过（与遗留绘制同式）。
 */
object TableCellLines {

    /**
     * 单元格边框色的**未声明回退**（`BoxPageRenderer.tableBorderPaint` 同值）。
     * 书内 CSS 声明了 `border-color` 时一律以 CSS 为准（见 [emitCellBorders]）；此值仅用于
     * 书未声明颜色、而有宽度的边（遗留绘制同式）。
     */
    const val BORDER_ARGB: Int = 0xFF999999.toInt()

    /**
     * @param rowTop 行顶的章节绝对 Y（行 FlowedLine.yTop）。
     * @param rowHeight 行高（行 `replaceableHeight`；边框框高）。
     * @param rowCharBase 行文本[0]的章内 char（行 FlowedLine.charStart）。
     * @param styleOf 任一元素的计算样式（重路径喂整章表，轻路径喂懒级联）。
     * @param fallback 行/叶样式（格样式缺失时的字号/字族回退）。
     */
    fun expand(
        table: TableRowLayout,
        rowTop: Int,
        rowHeight: Int,
        rowCharBase: Int,
        styleOf: (MarkupElement) -> ComputedStyle?,
        fallback: ComputedStyle,
        letterSpacingEm: Float,
        inkColor: Int = 0xFF000000.toInt(),
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): CellWindow {
        val lines = ArrayList<DrawLine>()
        val borders = ArrayList<PageBackground>()
        val images = ArrayList<PageImage>()
        var acc = 0
        for (cell in table.cells) {
            acc = emitCell(cell, rowTop, rowTop + rowHeight.coerceAtLeast(1), rowCharBase + acc, table.emptyCellsHide, styleOf, fallback, letterSpacingEm, inkColor, lines, borders, images, imageLoader, chapterHref, acc)
        }
        return CellWindow(lines, borders, images)
    }

    /**
     * 多行表展开（含 `rowspan` 视觉跨度）：相邻同表行叶按文档序成组，
     * 跨行格的边框从首行顶直画到末行底（文本仍顶端对齐，只占首行带）。
     * 窗口外缺失的后续行按已有行钳制（保守单带回退）。
     */
    fun expandTable(
        frames: List<RowFrame>,
        styleOf: (MarkupElement) -> ComputedStyle?,
        letterSpacingEm: Float,
        inkColor: Int = 0xFF000000.toInt(),
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): TableWindow {
        val byLine = HashMap<Int, List<DrawLine>>()
        val borders = ArrayList<PageBackground>()
        val imagesByLine = HashMap<Int, List<PageImage>>()
        var i = 0
        while (i < frames.size) {
            var j = i
            // 同表成组（tableEl 恒等；null 各自成组，跨表不串跨度）。
            while (j + 1 < frames.size && frames[j + 1].tableEl != null && frames[j + 1].tableEl === frames[i].tableEl) j++
            for (k in i..j) {
                val f = frames[k]
                val lines = ArrayList<DrawLine>()
                val frameImages = ArrayList<PageImage>()
                var acc = 0
                for (cell in f.table.cells) {
                    val spanLast = (k + cell.rowSpan.coerceAtLeast(1) - 1).coerceAtMost(j)
                    val spanBottom = frames[spanLast].rowTop + frames[spanLast].rowHeight.coerceAtLeast(1)
                    acc = emitCell(cell, f.rowTop, spanBottom, f.rowCharBase + acc, f.table.emptyCellsHide, styleOf, f.fallbackStyle, letterSpacingEm, inkColor, lines, borders, frameImages, imageLoader, chapterHref, acc)
                }
                if (lines.isNotEmpty()) byLine[f.lineIdx] = lines
                if (frameImages.isNotEmpty()) imagesByLine[f.lineIdx] = frameImages
            }
            i = j + 1
        }
        return TableWindow(byLine, borders, imagesByLine)
    }

    /** 一表行的展开输入（调用方按文档序供给；`tableEl` 为所属 `<table>`，分组防跨表）。 */
    data class RowFrame(
        val table: TableRowLayout,
        val lineIdx: Int,
        val rowTop: Int,
        val rowHeight: Int,
        val rowCharBase: Int,
        val fallbackStyle: ComputedStyle,
        val tableEl: MarkupElement?,
    )

    /** 多行展开结果：行下标 → 单元格文本行；行下标 → 单元格图片；边框矩形（均章节绝对 Y）。 */
    data class TableWindow(
        val lines: Map<Int, List<DrawLine>>,
        val borders: List<PageBackground>,
        val images: Map<Int, List<PageImage>> = emptyMap(),
    )

    /** 所属 `<table>`（逐级上找；无即 null，调用方分组用）。 */
    fun tableAncestorOf(el: MarkupElement?): MarkupElement? {
        var n = el?.parent
        while (n != null && n.tag != "table") n = n.parent
        return n
    }

    /**
     * 单格发射：文本行进 [lines]（默认首行带内顶端对齐；`vertical-align: middle/bottom`
     * 在行带内整体下移，并含 border+padding 顶/左内缩），边框
     * `[rowTop, spanBottom)` 进 [borders]；返回推进后的累计字符偏移（供下一格 `charBase`）。
     */
    private fun emitCell(
        cell: TableCellLayout,
        rowTop: Int,
        spanBottom: Int,
        cellBase: Int,
        hideEmpty: Boolean,
        styleOf: (MarkupElement) -> ComputedStyle?,
        fallback: ComputedStyle,
        letterSpacingEm: Float,
        inkColor: Int,
        lines: MutableList<DrawLine>,
        borders: MutableList<PageBackground>,
        images: MutableList<PageImage>,
        imageLoader: ImageBoundsReader?,
        chapterHref: String,
        acc: Int,
    ): Int {
        val shape = cell.shape ?: return acc
        val text = shape.shapeText
        if (hideEmpty && text.isEmpty()) return acc
        val cs = styleOf(cell.el) ?: fallback
        val lineStart = lines.size
        val imgStart = images.size
        val baseSize = shape.shapeFontSizePx.takeIf { it > 0f } ?: cs.fontSizePx
        // 单元格内容框相对行带顶/左的内缩 = 边框 + 内边距（与行高预算 padding.vertical +
        // border.vertical 对称；缺此则文本贴顶、内边距全堆在行带底部）。
        val insetTop = (cs.border.top + cs.padding.top).roundToInt()
        val xLeft = cell.x + (cs.border.left + cs.padding.left).roundToInt()
        val lineW = NormalFlowLayout.innerBreakWidth(cs, cell.width)
        val nowrap = !WhiteSpaceNormalize.wraps(cs.whiteSpace)
        val hidden = emitCellImages(cell, text, shape, cs, styleOf, rowTop, insetTop, xLeft, lineW, imageLoader, chapterHref, images)
        for (k in 0 until shape.shapeLineCount) {
            val s = shape.shapeLineStart(k)
            val e = shape.shapeLineEnd(k)
            if (s < 0 || e <= s || e > text.length) continue
            lines.add(
                DrawLine(
                    text = text,
                    range = s until e,
                    yTop = rowTop + insetTop + shape.shapeLineTop(k),
                    yBottom = rowTop + insetTop + shape.shapeLineBottom(k),
                    alignment = shape.shapeAlignment,
                    fontSizePx = baseSize,
                    lineHeightRatio = cs.lineHeightRatio,
                    tag = cell.el.tag,
                    families = cs.fontFamilies,
                    weight = cs.fontWeight,
                    italic = cs.italic,
                    monospace = cs.monospace || cell.el.tag == "pre",
                    letterSpacingEm = letterSpacingEm,
                    lineWidthPx = lineW,
                    xLeft = xLeft,
                    listMarker = null,
                    inkColor = inkColor,
                    colorRuns = shape.shapeColorRuns,
                    fontRuns = shape.shapeFontRuns,
                    firstLineIndentPx = 0f,
                    nowrap = nowrap,
                    baselineShifts = shape.shapeBaselineShifts,
                    textShadow = shape.shapeTextShadow,
                    emphasis = shape.shapeEmphasis,
                    emphasisUnder = shape.shapeEmphasisUnder,
                    alpha = shape.shapeAlpha,
                    charBase = cellBase,
                    rubyRuns = shape.shapeRubyRuns,
                    underlineRuns = shape.shapeUnderlineRuns,
                    imgHidden = hidden[k] ?: emptyList(),
                ),
            )
        }
        emitCellBorders(cell, rowTop, spanBottom, cs, borders)
        // 单元格垂直对齐（`vertical-align: middle/bottom`；默认顶端）：内容整体在
        // [行顶+内缩, spanBottom) 内下移；跨行格 spanBottom 已是所跨末行底。
        val align = cs.verticalAlign
        if ((align == orilumn.reader.engine.css.VerticalAlign.MIDDLE || align == orilumn.reader.engine.css.VerticalAlign.BOTTOM) && spanBottom > rowTop + insetTop) {
            var contentBottom = rowTop + insetTop
            for (i in lineStart until lines.size) contentBottom = maxOf(contentBottom, lines[i].yBottom)
            for (i in imgStart until images.size) contentBottom = maxOf(contentBottom, images[i].yBottom)
            val avail = spanBottom - (rowTop + insetTop)
            val off = if (align == orilumn.reader.engine.css.VerticalAlign.MIDDLE) {
                (avail - (contentBottom - (rowTop + insetTop))) / 2
            } else {
                avail - (contentBottom - (rowTop + insetTop))
            }
            if (off > 0) {
                for (i in lineStart until lines.size) {
                    val l = lines[i]
                    lines[i] = l.copy(yTop = l.yTop + off, yBottom = l.yBottom + off)
                }
                for (i in imgStart until images.size) {
                    val im = images[i]
                    images[i] = im.copy(yTop = im.yTop + off, yBottom = im.yBottom + off)
                }
            }
        }
        return acc + text.length
    }

    /**
     * 单元格内图片进 [PageImage]（表格图缺口的补齐）：shape 文本内 U+FFFC 占位按文档序
     * 配对单元格内 `img` 后代；x 取格内容左（与正文行窗叶级近似同级），y/h 取占位所在行，
     * 宽高经 [NormalFlowLayout.replacedUsedSize] 与正文图同口径。无 loader/href 时不产出。
     */
    private fun emitCellImages(
        cell: TableCellLayout,
        text: String,
        shape: ParagraphShapeRef,
        cs: ComputedStyle,
        styleOf: (MarkupElement) -> ComputedStyle?,
        rowTop: Int,
        insetTop: Int,
        xLeft: Int,
        lineW: Int,
        imageLoader: ImageBoundsReader?,
        chapterHref: String,
        images: MutableList<PageImage>,
    ): Map<Int, List<IntRange>> {
        val hidden = HashMap<Int, MutableList<IntRange>>()
        if (imageLoader == null || chapterHref.isBlank()) return hidden
        val imgEls = ArrayList<MarkupElement>()
        fun walk(n: MarkupElement) {
            for (c in n.children) {
                if (c.tag == "img") imgEls.add(c)
                walk(c)
            }
        }
        walk(cell.el)
        if (imgEls.isEmpty()) return hidden
        var imgIdx = 0
        for (k in 0 until shape.shapeLineCount) {
            val s = shape.shapeLineStart(k)
            val e = shape.shapeLineEnd(k)
            if (s < 0 || e <= s || e > text.length) continue
            for (j in s until e) {
                if (imgIdx >= imgEls.size) return hidden
                if (text[j] != '￼') continue
                val imgEl = imgEls[imgIdx++]
                val src = imgEl.attrs["src"] ?: continue
                val imgStyle = styleOf(imgEl) ?: cs
                val used = NormalFlowLayout.replacedUsedSize(
                    imgEl, imgStyle,
                    NormalFlowLayout.innerBreakWidth(imgStyle, cell.width),
                    imageLoader, chapterHref,
                )
                val w = used.first.coerceAtLeast(1)
                val h = used.second.coerceAtLeast(1)
                // x 按占位行内比例推进（独占图 fraction=0 即格左；与叶级近似同级）。
                // y/h 取占位所在行。
                val x = xLeft + ((j - s).toFloat() / (e - s).coerceAtLeast(1) * lineW).roundToInt()
                val yTop = rowTop + insetTop + shape.shapeLineTop(k)
                images.add(
                    PageImage(
                        src = src,
                        chapterHref = chapterHref,
                        xLeft = x,
                        yTop = yTop,
                        yBottom = yTop + h,
                        widthPx = w,
                        heightPx = h,
                    ),
                )
                hidden.getOrPut(k) { ArrayList() }.add(j..j)
            }
        }
        return hidden
    }

    /**
     * 单元格逐边框（与 [BoxDrawer.emitEdge] 同口径）：只画有宽且 style 非 NONE 的边，
     * 每边一条细填充带（色取该边声明色，无则 currentColor，再无则中性灰）。
     * 旧整框矩形会把只声明 `border-top` 的格画成四面（Rust 简介表 regression）。
     */
    private fun emitCellBorders(
        cell: TableCellLayout,
        rowTop: Int,
        spanBottom: Int,
        cs: ComputedStyle,
        borders: MutableList<PageBackground>,
    ) {
        val bw = cs.border
        val bc = cs.borderColors
        val bs = cs.borderStyles
        val cur = cs.colorHex
        fun argbOf(c: String?): Int = c?.let { cssHexToArgb(it) }
            ?: cur?.let { cssHexToArgb(it) } ?: BORDER_ARGB
        // 缺 style 声明按 SOLID（与 BoxDrawer.emitEdge 同口径）。
        fun solid(s: orilumn.reader.engine.css.BorderStyle?) =
            (s ?: orilumn.reader.engine.css.BorderStyle.SOLID) != orilumn.reader.engine.css.BorderStyle.NONE
        val l = cell.x
        val r = cell.x + cell.width.coerceAtLeast(1)
        val b = spanBottom.coerceAtLeast(rowTop + 1)
        val tw = bw.top.roundToInt()
        if (tw > 0 && solid(bs?.top)) {
            borders.add(PageBackground(left = l, yTop = rowTop, yBottom = rowTop + tw, right = r, argb = argbOf(bc?.top), border = true))
        }
        val bw2 = bw.bottom.roundToInt()
        if (bw2 > 0 && solid(bs?.bottom)) {
            borders.add(PageBackground(left = l, yTop = b - bw2, yBottom = b, right = r, argb = argbOf(bc?.bottom), border = true))
        }
        val lw = bw.left.roundToInt()
        if (lw > 0 && solid(bs?.left)) {
            borders.add(PageBackground(left = l, yTop = rowTop, yBottom = b, right = l + lw, argb = argbOf(bc?.left), border = true))
        }
        val rw = bw.right.roundToInt()
        if (rw > 0 && solid(bs?.right)) {
            borders.add(PageBackground(left = r - rw, yTop = rowTop, yBottom = b, right = r, argb = argbOf(bc?.right), border = true))
        }
    }

    /** 一行的展开结果：单元格文本行 + 单元格边框矩形 + 单元格图片（均章节绝对 Y）。 */
    data class CellWindow(
        val lines: List<DrawLine>,
        val borders: List<PageBackground>,
        val images: List<PageImage> = emptyList(),
    )
}
