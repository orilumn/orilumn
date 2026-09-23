package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement

/** A single table row's resolved 2D grid: absolute column x positions + the row's cells. */
class TableRowLayout(
    val columnXs: IntArray,
    val columnWidths: IntArray,
    val cells: List<TableCellLayout>,
    /** P1-2 `empty-cells: hide`（空单元格绘制时跳过边框；见 `BoxPageRenderer.drawTableRow`）。 */
    val emptyCellsHide: Boolean = false,
)

/** One table cell's draw slot: its element, grid anchor/span and placed geometry. */
class TableCellLayout(
    val el: MarkupElement,
    val col: Int,
    val colSpan: Int,
    val x: Int,
    val width: Int,
    /** The cell's own outer height (content + padding + border); the shaping pass fills/refreshes it. */
    var height: Int,
    val isHeader: Boolean,
    /** The cell's shaped text (filled by the shaping pass before drawing). */
    var shape: ParagraphShapeRef? = null,
    /** Vertical span in rows (from the markup `rowspan`; 1 = own row only). */
    val rowSpan: Int = 1,
)

/**
 * One node of the box-model tree produced by [NormalFlowLayout].
 *
 * A node is either a **text block** (a leaf: it owns the [ranges]/[lineHeights] of its broken lines)
 * or a **container** (it owns [childBoxes], the nested block boxes). The two are mutually exclusive:
 * a block's inline/text descendants are absorbed into its leaf text, while its block-level
 * descendants become container children — mirroring the CSS2.1 anonymous-block substitution that
 * separates text runs from nested blocks. Both shapes carry the box [style] and its resolved
 * border-box geometry for background/border drawing.
 *
 * @property style the computed style (font, color, margins/padding/border, background/border color).
 * @property contentLeft absolute x of the border-box left edge.
 * @property contentWidth border-box width (px); the leaf's line-breaking width is this minus its own horizontal padding/border.
 * @property contentTop absolute y of the border-box top; filled by the flow pass.
 * @property contentBottom absolute y of the border-box bottom; filled by the flow pass.
 * @property ranges (leaf only) the char ranges this text block breaks into, relative to its own text.
 * @property textLength (leaf only) the length of this block's absorbed text (char advancement).
 * @property lineHeights (leaf only) each line's height (px), aligned with [ranges]; the box flow
 *   uses these to place lines, so they must match the drawing shaper's per-line metrics.
 * @property childBoxes (container only) the nested block boxes, in order.
 */
class LayoutBox(
    val el: MarkupElement?,
    val style: ComputedStyle,
    val contentLeft: Int,
    val contentWidth: Int,
    val ranges: List<IntRange> = emptyList(),
    val textLength: Int = 0,
    val lineHeights: List<Int> = emptyList(),
    val childBoxes: List<LayoutBox> = emptyList(),
    /** For a replaceable ("img") block: its pixel height; 0 for text blocks. An img is still a leaf
     *  (no childBoxes), but it owns no text and its [textLength] is 1 (one char-slot `[k,k+1)`). The
     *  box flow emits a single un-splittable line of this height. */
    val replaceableHeight: Int = 0,
    /** For a table row leaf: the resolved 2D grid to draw (columns + cells). Rows are emitted like a
     *  replaceable single line of [replaceableHeight], and are drawn as a grid instead of shaped text. */
    val table: TableRowLayout? = null,
    /**
     * P4-a2: 悬浮环绕前导（仅紧随同容器悬浮 img 的文本叶；null = 无环绕旧路径）。
     * 前 [lines] 行按 [widthPx] 收缩断行并右移 [xOffPx]（左浮动），余行全宽。
     */
    val floatLead: FloatLead? = null,
) {
    var contentTop: Int = 0

    var contentBottom: Int = 0

    /** Global start line index of this box's first line in the whole stream (leaf); -1 when it has no lines. */
    var firstLineIndex: Int = -1

    /** Global exclusive end line index of this box's last line in the whole stream (leaf); -1 when empty. */
    var lastLineExclusive: Int = -1

    /** Whether this box (its whole line range) is not to be torn across pages. */
    val breakInsideAvoid: Boolean get() = style.breakInside == BreakRule.AVOID

    /** Whether this node is a container (has nested blocks) rather than a text leaf. */
    val isContainer: Boolean get() = childBoxes.isNotEmpty()

    /** Char length of the text immediately before this box in document order (used by the flow pass). */
    val ownTextLength: Int get() = if (isContainer) 0 else textLength
}

/**
 * P4-a2: 悬浮环绕前导（仅紧随同容器悬浮 img 的文本叶）。
 *
 * @property lines 收缩断行的前导行数（含 1 行保守余量，只多不少——宁可多窄一行，不与悬浮重叠）。
 * @property widthPx 前导行的断行宽（`contentW - floatW - gap`）。
 * @property xOffPx 前导行的额外 x 偏移（左浮动 = `floatW + gap`，右浮动 = 0）。
 */
data class FloatLead(val lines: Int, val widthPx: Int, val xOffPx: Float)

/** P3-b: 盒背景图（url + 平铺 + 定位；无 `background-size`，恒 1:1 原尺寸平铺）。 */
data class BackgroundImage(
    val url: String,
    val repeat: orilumn.reader.engine.css.BackgroundRepeat = orilumn.reader.engine.css.BackgroundRepeat.REPEAT,
    val position: orilumn.reader.engine.css.BackgroundPosition = orilumn.reader.engine.css.BackgroundPosition(),
)

/** A single resolved geometry rectangle to draw (background fills, or one border edge band). */
data class DrawRect(
    val kind: DrawKind,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val colorHex: String,
    /** P3-a: 圆角（零即方形旧路径，逐字节一致）；边框环与背景同圆角。 */
    val radii: orilumn.reader.engine.css.CornerRadius = orilumn.reader.engine.css.CornerRadius(),
    /** P3-a: 祖先链 opacity 连乘（1＝不透明旧路径）。 */
    val alpha: Float = 1f,
    /** P3-a: 盒阴影（仅背景/描边环携带；颜色已解 currentColor）。 */
    val shadow: orilumn.reader.engine.css.BoxShadow? = null,
    /**
     * P3-a: 边框环描边宽（>0＝均匀边框＋圆角的描边环，外框 radii；
     * 0＝填充带旧路径）。非均匀/虚线边框＋圆角回方形带（文档近似）。
     */
    val strokeWidthPx: Float = 0f,
    /** P3-b: 背景图（null＝无图旧路径；有图无色时本 rect 为透明承载，只画阴影＋图）。 */
    val bgImage: BackgroundImage? = null,
    /**
     * P3-b: 背景图平铺锚盒（未裁剪的盒上下缘；仅 [bgImage] 非空时有效）。
     * 跨页撕裂的块按页裁剪后仍以整盒原点平铺，翻页不断纹。
     */
    val bgBoxTop: Int = 0,
    val bgBoxBottom: Int = 0,
)

enum class DrawKind { BACKGROUND, BORDER }