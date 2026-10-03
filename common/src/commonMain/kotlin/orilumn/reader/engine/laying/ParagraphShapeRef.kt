package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.EmphasisStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.TextShadow
import orilumn.reader.engine.layout.ListMarkers

/**
 * Pure-JVM readable contract for a cell's shaped text, decoupling [TableCellLayout] from the
 * Android [orilumn.reader.engine.layout.ParagraphShape] class. The real [ParagraphShape] implements
 * this; table-cell expansion ([orilumn.reader.engine.skia.TableCellLines]) reads only these members,
 * so cell text joins the shared Skia [orilumn.reader.engine.skia.DrawLine] window on both platforms
 * with zero Android types involved.
 *
 * Coordinates mirror [orilumn.reader.engine.layout.ParagraphShape]: line ranges are exclusive-end
 * `until` ranges into [shapeText]; heights are per-line CSS line boxes.
 */
interface ParagraphShapeRef {
    /** The exact plain text the breaker consumed. */
    val shapeText: String
    /** Line count (1 for replaceable shapes). */
    val shapeLineCount: Int
    /** Char start of line [k], relative to [shapeText]. */
    fun shapeLineStart(k: Int): Int
    /** Exclusive char end of line [k], relative to [shapeText]. */
    fun shapeLineEnd(k: Int): Int
    /** Y top of line [k] in the shape's own coordinates. */
    fun shapeLineTop(k: Int): Int
    /** Y bottom of line [k] in the shape's own coordinates. */
    fun shapeLineBottom(k: Int): Int
    /**
     * **Q20**：行 [k] 是否断词收尾（行尾要补一个
     * [orilumn.reader.engine.laying.HYPHEN_GLYPH]）。断行器已为它预留版心，绘制侧漏读 ⇒
     * `LineAligner.hyphenW == 0` ⇒ `LineWindowDrawer` 整块跳过 ⇒ 连字符画不出来。
     *
     * 为什么挂在 shape 上而不只挂 [LayoutBox.hyphenAtEnd]：canonical 走盒流
     * （`DrawLineBuilder` 不塑形，只有 `LayoutBox.hyphenAtEnd`），增量/临时页走塑形
     * （`BoxChapterLayouter.buildPartialSkiaWindow` 手里只有 shape）。两路同断行器同宽，
     * 值逐项一致（`SkiaDrawLineWindowCoherenceTest` 同行锁 range + 连字符位）。
     *
     * 默认 false = 无断词行（哑实现/替换块/旧桩零改动）。
     */
    fun shapeLineHyphenAtEnd(k: Int): Boolean = false
    /**
     * 行 [k] 的**标点挤压比例**（`[0,1]`，默认 0 = 不挤）。与 [shapeLineHyphenAtEnd] 同款理由挂在
     * shape 上：canonical 走盒流（只有 [LayoutBox.squeezeRatios]）、增量/临时页走塑形（只有本方法），
     * 两路同断行器同宽、值逐项一致。
     *
     * 断行侧是「挤着算宽度」才敢把词完整留在同一行的（[orilumn.reader.engine.skia.InhouseParagraphBreaker]），
     * 绘制侧必须套**同一个比例**乘**同一份额度表**（[orilumn.reader.engine.skia.PunctuationSqueeze.widths]）
     * ⇒ 画 == 量（量画同源，教训 ⑩）。漏读的后果：绘制侧按比例 0 画 ⇒ 画比量**宽** ⇒ 右溢被裁
     * （分页阅读器不能横向滚动）。反向的错（画侧自己挤）更隐蔽：那会让字位宽度与断点决策依据的
     * 宽度不是一个数，省行与右溢会互相掩盖。
     */
    fun shapeLineSqueezeRatio(k: Int): Float = 0f
    /** Block font size (draw paint size; ≤0 when absent — caller falls back to the cell style). */
    val shapeFontSizePx: Float
    /** Block alignment (the cell style's `text-align` the shape was broken with). */
    val shapeAlignment: TextAlign
    /** Explicitly-colored spans over [shapeText] (empty = all theme ink). */
    val shapeColorRuns: List<ColorRun>
    /** Inline face spans over [shapeText] (empty = whole-paragraph base face). */
    val shapeFontRuns: List<FontRun>
    /** Baseline-shift spans over [shapeText] (empty = pure baseline). */
    val shapeBaselineShifts: List<BaselineShift>
    /** Stacked-ruby runs over [shapeText] (empty = no ruby). */
    val shapeRubyRuns: List<RubyRun>
    /** Underline spans over [shapeText] (empty = no underline). */
    val shapeUnderlineRuns: List<UnderlineRun>
    /** Ancestor-chain opacity product (1 = legacy path). */
    val shapeAlpha: Float
    /** Emphasis marks (NONE = none). */
    val shapeEmphasis: EmphasisStyle
    /** Emphasis position (false = above the line, true = below). */
    val shapeEmphasisUnder: Boolean
    /** Line text-shadow (null = none). */
    val shapeTextShadow: TextShadow?
    /** True when this shape is a replaceable block or table row (single synthetic line). */
    val isReplaceable: Boolean
    /** The single line's height for a replaceable block (0 for text blocks).
     *  Mutable: the light path's rowspan redistribution resolves table-row heights after shaping. */
    var replaceableBottom: Int
    /** The single line's exclusive char end for a replaceable block. */
    val replaceableCharEnd: Int
    /** List marker overlay in the shape's gutter (null = none; never in the text). */
    val listMarker: ListMarkers.ListMarker?
    /**
     * C2-P2b-3: 取画笔请求（tag/族名/粗斜体三件套）。几何不管画笔，但未来卷曲 canvas 回退
     * 要按叶取 face，请求必须跟几何同源（`shapeGeometry` 从同一 `pairTag`/`rootStyle` 装配）。
     */
    val fontRequest: ShapeFontRequest

    // ---- 分页数学短名（默认实现，全转调 shapeXxx；搬走的代码零成员改动，只换类型名） ----
    /** The exact plain text the breaker consumed. */
    val text: String get() = shapeText
    /** Line count (1 for replaceable shapes). */
    val lineCount: Int get() = shapeLineCount
    /** Y top of line [k] in the shape's own coordinates. */
    fun lineTop(k: Int): Int = shapeLineTop(k)
    /** Y bottom of line [k] in the shape's own coordinates. */
    fun lineBottom(k: Int): Int = shapeLineBottom(k)
    /** Char start of line [k], relative to [shapeText]. */
    fun lineStart(k: Int): Int = shapeLineStart(k)
    /** Exclusive char end of line [k], relative to [shapeText]. */
    fun lineEnd(k: Int): Int = shapeLineEnd(k)
    /** Raw y of the first line in the shape's own coordinates (always 0; kept for callers). */
    fun originTop(): Int = 0
    /** Block font size (draw paint size; ≤0 when absent — caller falls back to the cell style). */
    val fontSizePx: Float get() = shapeFontSizePx
    /** Block alignment (the cell style's `text-align` the shape was broken with). */
    val alignment: TextAlign get() = shapeAlignment
    /** Explicitly-colored spans over [shapeText] (empty = all theme ink). */
    val colorRuns: List<ColorRun> get() = shapeColorRuns
    /** Inline face spans over [shapeText] (empty = whole-paragraph base face). */
    val fontRuns: List<FontRun> get() = shapeFontRuns
    /** Baseline-shift spans over [shapeText] (empty = pure baseline). */
    val baselineShifts: List<BaselineShift> get() = shapeBaselineShifts
    /** Stacked-ruby runs over [shapeText] (empty = no ruby). */
    val rubyRuns: List<RubyRun> get() = shapeRubyRuns
    /** Underline spans over [shapeText] (empty = no underline). */
    val underlineRuns: List<UnderlineRun> get() = shapeUnderlineRuns
    /** Ancestor-chain opacity product (1 = legacy path). */
    val alpha: Float get() = shapeAlpha
    /** Emphasis marks (NONE = none). */
    val emphasis: EmphasisStyle get() = shapeEmphasis
    /** Emphasis position (false = above the line, true = below). */
    val emphasisUnder: Boolean get() = shapeEmphasisUnder
    /** Line text-shadow (null = none). */
    val textShadow: TextShadow? get() = shapeTextShadow
}

/**
 * 按叶取画笔的请求（C2-P2b-3）：`FontPool.resolve` 的参数包。几何侧只装配不消费；
 * `:app` 包装/未来卷曲凭它取 face，桌面忽略。
 */
data class ShapeFontRequest(
    val tag: String?,
    val families: List<String>,
    val weight: Int,
    val italic: Boolean,
    val monospace: Boolean,
)
