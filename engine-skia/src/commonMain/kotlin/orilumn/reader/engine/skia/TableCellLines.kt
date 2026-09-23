package orilumn.reader.engine.skia

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
 * 同 helper 双路一致；边框色跟随书内 CSS `border-color`（未声明回退 `currentColor`，
 * 见 [borderArgbOf]；遗留 Android `drawTableRow` 仍为固定 `#ff999999`）。
 *
 * - 断行几何不动：行宽/对齐/字号与塑形侧（`ParagraphShapes.shapeOf` 的 cell 宽）同源，
 *   此处只投影、不重排；
 * - 字符流不动：`charBase` 按行首 + 单元格累计文本长换算，与分页 `rowChars` 同账；
 * - 无 shape 的格（桌面旧路径未塑形）/ `empty-cells: hide` 空格直接跳过（与遗留绘制同式）。
 */
object TableCellLines {

    /**
     * 单元格边框色的**未声明回退**（`BoxPageRenderer.tableBorderPaint` 同值）。
     * 书内 CSS 声明了 `border-color` 时一律以 CSS 为准（见 [borderArgbOf]）；此值仅用于
     * 书未声明颜色、而引擎仍合成 1px 实线框的格（遗留绘制同式）。
     */
    const val BORDER_ARGB: Int = 0xFF999999.toInt()

    /**
     * 单元格边框色：跟随 CSS `border-color`（任一侧声明即可；四侧不同时取 top，与遗留单框同式）。
     * 未声明 → `currentColor`（本元素文字色）。单框无法表达四侧异色，属已知近似。
     */
    private fun borderArgbOf(cs: ComputedStyle): Int {
        val c = cs.borderColors
        val declared = c?.top ?: c?.right ?: c?.bottom ?: c?.left
        val hex = declared ?: cs.colorHex
        return hex?.let { cssHexToArgb(it) } ?: BORDER_ARGB
    }

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
    ): CellWindow {
        val lines = ArrayList<DrawLine>()
        val borders = ArrayList<PageBackground>()
        var acc = 0
        for (cell in table.cells) {
            acc = emitCell(cell, rowTop, rowTop + rowHeight.coerceAtLeast(1), rowCharBase + acc, table.emptyCellsHide, styleOf, fallback, letterSpacingEm, inkColor, lines, borders, acc)
        }
        return CellWindow(lines, borders)
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
    ): TableWindow {
        val byLine = HashMap<Int, List<DrawLine>>()
        val borders = ArrayList<PageBackground>()
        var i = 0
        while (i < frames.size) {
            var j = i
            // 同表成组（tableEl 恒等；null 各自成组，跨表不串跨度）。
            while (j + 1 < frames.size && frames[j + 1].tableEl != null && frames[j + 1].tableEl === frames[i].tableEl) j++
            for (k in i..j) {
                val f = frames[k]
                val lines = ArrayList<DrawLine>()
                var acc = 0
                for (cell in f.table.cells) {
                    val spanLast = (k + cell.rowSpan.coerceAtLeast(1) - 1).coerceAtMost(j)
                    val spanBottom = frames[spanLast].rowTop + frames[spanLast].rowHeight.coerceAtLeast(1)
                    acc = emitCell(cell, f.rowTop, spanBottom, f.rowCharBase + acc, f.table.emptyCellsHide, styleOf, f.fallbackStyle, letterSpacingEm, inkColor, lines, borders, acc)
                }
                if (lines.isNotEmpty()) byLine[f.lineIdx] = lines
            }
            i = j + 1
        }
        return TableWindow(byLine, borders)
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

    /** 多行展开结果：行下标 → 单元格文本行；边框矩形（均章节绝对 Y）。 */
    data class TableWindow(
        val lines: Map<Int, List<DrawLine>>,
        val borders: List<PageBackground>,
    )

    /** 所属 `<table>`（逐级上找；无即 null，调用方分组用）。 */
    fun tableAncestorOf(el: MarkupElement?): MarkupElement? {
        var n = el?.parent
        while (n != null && n.tag != "table") n = n.parent
        return n
    }

    /**
     * 单格发射：文本行进 [lines]（首行带内顶端对齐，并含 border+padding 顶/左内缩），边框
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
        acc: Int,
    ): Int {
        val shape = cell.shape ?: return acc
        val text = shape.shapeText
        if (hideEmpty && text.isEmpty()) return acc
        val cs = styleOf(cell.el) ?: fallback
        val baseSize = shape.shapeFontSizePx.takeIf { it > 0f } ?: cs.fontSizePx
        // 单元格内容框相对行带顶/左的内缩 = 边框 + 内边距（与行高预算 padding.vertical +
        // border.vertical 对称；缺此则文本贴顶、内边距全堆在行带底部）。
        val insetTop = (cs.border.top + cs.padding.top).roundToInt()
        val xLeft = cell.x + (cs.border.left + cs.padding.left).roundToInt()
        val lineW = NormalFlowLayout.innerBreakWidth(cs, cell.width)
        val nowrap = !WhiteSpaceNormalize.wraps(cs.whiteSpace)
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
                ),
            )
        }
        borders.add(
            PageBackground(
                left = cell.x,
                yTop = rowTop,
                yBottom = spanBottom.coerceAtLeast(rowTop + 1),
                right = cell.x + cell.width.coerceAtLeast(1),
                argb = borderArgbOf(cs),
                border = true,
                strokeWidthPx = 1f,
            ),
        )
        return acc + text.length
    }

    /** 一行的展开结果：单元格文本行 + 单元格边框矩形（均章节绝对 Y）。 */
    data class CellWindow(
        val lines: List<DrawLine>,
        val borders: List<PageBackground>,
    )
}
