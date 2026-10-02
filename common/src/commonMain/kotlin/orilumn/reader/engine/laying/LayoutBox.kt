package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.html.MarkupElement

/** A single table row's resolved 2D grid: absolute column x positions + the row's cells. */
class TableRowLayout(
    val columnXs: IntArray,
    val columnWidths: IntArray,
    val cells: List<TableCellLayout>,
    /** P1-2 `empty-cells: hide`（空单元格绘制时跳过边框；见 `BoxPageRenderer.drawTableRow`）。 */
    val emptyCellsHide: Boolean = false,
)

/**
 * Q15：表格单元格内的**一个块**（CSS 2.1 §16.3「单元格内容是块级流」）。
 *
 * ## 为什么必须有这个类型
 *
 * 修复前单元格只挂**一个** shape（[TableCellLayout.shape]），格内文本由
 * `NormalFlowLayout.absorbStyled(cell.el, …)` 一把吸收 —— 而该吸收按
 * [orilumn.reader.engine.laying.styledSegments] 的规则**跳过块级子节点**
 * （`StyledText.kt` 的 `isBlock(c) -> Unit`）⇒ `<td><p>…</p></td>` 整格文本为空，
 * 塑形出零行、绘制侧 [orilumn.reader.engine.skia.TableCellLines] 整格丢弃：
 * 格撑住了空间、边框照画，**里面一个字都没有**。实测 4 本书 2558/4309 = **59.4%** 的单元格如此
 * （Rust 书两本 92%/90%，GIMP 手册 47%）。
 *
 * ## 结构
 *
 * 格内**块级**子节点（`<p>`/`<div>`/`<ul>`…）各自成块；格的**直接内联内容**合成一个匿名块
 * （沿用普通容器的 stray `#text` 表示，见 `NormalFlowLayout.flowChildren`）⇒
 * `<td>字</td>` 与 `<td><p>字</p></td>` 得到同构的「一块」结果，`<td>字<p>段</p></td>`
 * 得到与普通容器一样的「前/中/后」三段。中间容器（`<td><div><p>a</p></div></td>` 的 `div`）
 * 自身不产行，只贡献自己的 padding/border（横向折进 [edgeH]，纵向折进 [gapBefore]/格尾）
 * 与外边距（首子顶、末子底各一次）。
 *
 * @property el 块元素；**匿名块 = 所在容器**（格或中间容器），本块文本是它的一段「非块级子节点」
 *   连续 run（`<td>前<p>中</p>后</td>` 的 `前`/`后` 两块 `el` 同为 `td`）。之所以不给匿名 run 造
 *   合成 `#text` 元素：塑形侧要从**真实子树**重抽一遍文本（`shapeGeometry` → `styledSegments`），
 *   合成空节点的子树为空，`<br>` 的硬换行会被 `white-space:normal` 折成空格（实测 Kindle 跨行格
 *   5 行掉成 3 行）。带 [outsideSiblings] 的匿名块，塑形侧靠「排掉 run 外的兄弟」复原同一段文本。
 * @property outsideSiblings 匿名块要额外排掉的**原始兄弟节点**（容器 `children` 里不在本 run
 *   区间内的项；按身份命中）。命名块恒空集。塑形/断行侧的块判据须把它并入 `isBlock`
 *   （见 `BoxChapterLayouter.fillTableRowCells`）。
 * @property imageRoot 行内图配对的扫描根：命名块 = [el]；**匿名块 = 所在容器**（行内 `<img>` 不
 *   独立成块，`<td>字<img></td>` 的图就在格上）。行内 U+FFFC 占位按本根的子树文档序配对 `img`。
 * @property style 该块的计算样式（匿名块 = 所在容器样式；塑形侧据此取 face/tag）。
 * @property text 归一后的块文本（断行与塑形的**同一份**输入，不容第二条路径）。
 * @property runs 行内 face 段（`<code>`/`<strong>` 等；与断行同喂；匿名/替换块恒空）。
 * @property gapBefore 本块**边框盒顶**相对**格内容框顶**的静态前置间隙 = 逐层容器的
 *   border/padding ＋ 与前一块折叠后的外边距（§8.3.1 相邻块取大）。不含前一块的高度，故
 *   「排版」与「塑形」两侧都能独立算出一致的 [top]。
 * @property edgeH 横向 inset：逐层容器的 border/padding 累加 ＋ 块自身的横向边。
 *   断行宽 = 格内容宽 − [edgeH]，绘制左沿 = 格内容左 + [edgeH]。
 *   匿名块恒为「容器链横向边」（自身边由容器承担）。
 * @property edgeV 块自身的纵向边（border+padding；匿名块恒 0，同上）。格**尾部**容器的下内边距
 *   （`<td><div style="padding:8px">…</div></td>` 的 div，没有后继块可挂）并入末块的此值。
 * @property top 块**内容**顶相对格内容框顶的 y（塑形行顶再加它；由 [stackTableCellBlocks] 写）。
 * @property height 块内容总高 = 各行高之和 ＋ [edgeV]（塑形侧按 `lineBottom−lineTop` 复算）。
 * @property ranges / hyphenAtEnd / lineHeights 断行结果，三者**同长同序**。
 * @property shape 塑形结果（`BoxChapterLayouter.fillTableRowCells` 灌；绘制侧读）。
 */
class TableCellBlock(
    val el: MarkupElement,
    val style: ComputedStyle,
    val text: String,
    val runs: List<FontRun>,
    /** 行内图配对扫描根（见 @property）；缺省 = [el]。 */
    val imageRoot: MarkupElement = el,
    /** 匿名块要排掉的 run 外原始兄弟节点（见 @property）；命名块恒空集。 */
    val outsideSiblings: Set<MarkupElement> = emptySet(),
    val gapBefore: Int = 0,
    val edgeH: Int = 0,
    var edgeV: Int = 0,
    /** 塑形/断行侧写：块内容顶相对格内容框顶的 y。 */
    var top: Int = 0,
    /** 塑形/断行侧写：块内容总高（行高和 ＋ [edgeV]）。 */
    var height: Int = 0,
    var ranges: List<IntRange> = emptyList(),
    var hyphenAtEnd: List<Boolean> = emptyList(),
    var lineHeights: List<Int> = emptyList(),
    var shape: ParagraphShapeRef? = null,
) {
    /** 行数（与 [ranges]/[lineHeights]/[hyphenAtEnd] 同长）。 */
    val lineCount: Int get() = ranges.size

    /** 归一文本长度（章内 char 记账；与 `NormalFlowLayout.styledCharAdvance` 同账）。 */
    val textLength: Int get() = text.length

    /** 该块是否带任何文本/占位（空块的 [shape] 塑形出零行，绘制侧自然无输出）。 */
    val hasText: Boolean get() = text.isNotEmpty()
}

/**
 * Q15：按 [TableCellBlock.gapBefore]（静态间隙）与各块高把格内块纵向堆叠，写回
 * [TableCellBlock.top] 并返回内容总高。
 *
 * **排版层·上与塑形层共用的唯一定位式**（`buildTableRows` 与 `fillTableRowCells` 各调一次，
 * 两侧算出的 [TableCellBlock.top] 必须逐值相同 —— 否则行高预算与实际绘制错位）。
 * 只依赖静态 [TableCellBlock.gapBefore]，故不依赖行高，可在塑形前先排一次。
 */
fun stackTableCellBlocks(blocks: List<TableCellBlock>): Int {
    var y = 0
    for (b in blocks) {
        y += b.gapBefore
        b.top = y
        y += b.height.coerceAtLeast(0)
    }
    return y
}

/**
 * One table cell's draw slot: its element, grid anchor/span and placed geometry.
 *
 * @property blocks Q15：格内块序列（首个块即格的直接内联内容合成出的匿名块）。**纯内联格
 *   恒一块**（匿名块），故与修复前的单 shape 路径同构。
 */
class TableCellLayout(
    val el: MarkupElement,
    val col: Int,
    val colSpan: Int,
    val x: Int,
    val width: Int,
    /** The cell's own outer height (content + padding + border); the shaping pass fills/refreshes it. */
    var height: Int,
    val isHeader: Boolean,
    val blocks: List<TableCellBlock> = emptyList(),
    /** Vertical span in rows (from the markup `rowspan`; 1 = own row only). */
    val rowSpan: Int = 1,
) {
    /**
     * 便捷视图：**首块**的塑形（修复前单 shape 读点的同义物；多块格只有第 0 块可见）。
     * 绘制侧请直接遍历 [blocks]（`TableCellLines.emitCell`）—— 单块语义在多块格上是错的。
     */
    val shape: ParagraphShapeRef? get() = blocks.firstOrNull()?.shape

    /** 格内全部块归一文本长度之和（章内 char 记账；与 `styledCharAdvance` 同账）。 */
    val textLength: Int get() = blocks.sumOf { it.textLength }
}

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
 * @property hyphenAtEnd (leaf only) 与 [ranges] **逐项对应**：该行是否断词收尾（行尾要补一个
 *   [HYPHEN_GLYPH]）。断行侧已为它预留了版心（[BrokenLine.hyphenAtEnd]），绘制侧靠这个字段才知道
 *   行尾要落墨 —— 漏传 = 连字符被裁掉（分页阅读器不能横向滚动）。
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
    /**
     * **行尾连字符位**（与 [ranges] 同长同序）：该行是否断词收尾（行尾要补一个
     * [orilumn.reader.engine.laying.HYPHEN_GLYPH]）。断行侧已为它预留了版心
     * （[orilumn.reader.engine.laying.BrokenLine.hyphenAtEnd]），绘制侧靠这个字段才知行尾要落墨；
     * 漏传 = 连字符被裁掉（分页阅读器不能横向滚动）。
     *
     * 放在**参数表末尾**：本类有大量按位置传参的调用点，插在中间会打断它们。
     */
    val hyphenAtEnd: List<Boolean> = emptyList(),
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