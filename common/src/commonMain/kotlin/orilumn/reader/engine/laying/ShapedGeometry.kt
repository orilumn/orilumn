package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.EmphasisStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.TextShadow
import orilumn.reader.engine.layout.ListMarkers

/**
 * C2-P2b-1: 纯几何形状（无画笔），[ParagraphShapeRef] 的 common 实现。
 *
 * `shapeGeometry()`（engine-skia）产出它；搬走的编排代码只认接口持有它；
 * `:app` 的 `ParagraphShape` 是同一几何另加 Android 画笔的视图（同一对象两种视图
 * 中的几何视图由本类承载，画笔视图由 `ParagraphShape` 承载，几何逐字节同源）。
 */
data class ShapedGeometry(
    override val text: String = "",
    val lineRanges: List<IntRange> = emptyList(),
    val lineHeights: List<Int> = emptyList(),
    override val isReplaceable: Boolean = false,
    /** Mutable: the light path's rowspan redistribution resolves table-row heights after shaping. */
    override var replaceableBottom: Int = 0,
    override val replaceableCharEnd: Int = 1,
    override val listMarker: ListMarkers.ListMarker? = null,
    override val alignment: TextAlign = TextAlign.LEFT,
    override val fontSizePx: Float = 0f,
    override val colorRuns: List<ColorRun> = emptyList(),
    override val fontRuns: List<FontRun> = emptyList(),
    override val baselineShifts: List<BaselineShift> = emptyList(),
    override val textShadow: TextShadow? = null,
    override val emphasis: EmphasisStyle = EmphasisStyle.NONE,
    override val emphasisUnder: Boolean = false,
    override val alpha: Float = 1f,
    override val rubyRuns: List<RubyRun> = emptyList(),
    override val underlineRuns: List<UnderlineRun> = emptyList(),
    override val fontRequest: ShapeFontRequest = ShapeFontRequest(null, emptyList(), 400, false, false),
) : ParagraphShapeRef {
    override val shapeText: String get() = text
    override val shapeLineCount: Int get() = lineCount
    override fun shapeLineStart(k: Int): Int = lineStart(k)
    override fun shapeLineEnd(k: Int): Int = lineEnd(k)
    override fun shapeLineTop(k: Int): Int = lineTop(k)
    override fun shapeLineBottom(k: Int): Int = lineBottom(k)
    override val shapeFontSizePx: Float get() = fontSizePx
    override val shapeAlignment: TextAlign get() = alignment
    override val shapeColorRuns: List<ColorRun> get() = colorRuns
    override val shapeFontRuns: List<FontRun> get() = fontRuns
    override val shapeBaselineShifts: List<BaselineShift> get() = baselineShifts
    override val shapeRubyRuns: List<RubyRun> get() = rubyRuns
    override val shapeUnderlineRuns: List<UnderlineRun> get() = underlineRuns
    override val shapeAlpha: Float get() = alpha
    override val shapeEmphasis: EmphasisStyle get() = emphasis
    override val shapeEmphasisUnder: Boolean get() = emphasisUnder
    override val shapeTextShadow: TextShadow? get() = textShadow

    /** Cumulative line tops in the shape's own coordinates (line 0 sits at 0). */
    private val lineTops: IntArray by lazy {
        val tops = IntArray(lineRanges.size)
        var y = 0
        for (i in lineRanges.indices) {
            tops[i] = y
            y += lineHeights.getOrElse(i) { 1 }.coerceAtLeast(1)
        }
        tops
    }

    override val lineCount: Int get() = if (isReplaceable) 1 else lineRanges.size

    override fun lineTop(k: Int): Int = if (isReplaceable) 0 else lineTops.getOrElse(k) { 0 }

    override fun lineBottom(k: Int): Int =
        if (isReplaceable) replaceableBottom
        else lineTop(k) + lineHeights.getOrElse(k) { 1 }.coerceAtLeast(1)

    override fun lineStart(k: Int): Int = if (isReplaceable) 0 else lineRanges.getOrElse(k) { 0..0 }.first

    override fun lineEnd(k: Int): Int =
        if (isReplaceable) replaceableCharEnd else lineRanges.getOrElse(k) { 0..0 }.last + 1
}
