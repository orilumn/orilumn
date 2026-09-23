package orilumn.reader.engine.layout

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.ParagraphShapeRef
import kotlin.math.roundToInt

/**
 * The shaping of a single paragraph block: its text broken into lines by the shared
 * skia breaker ([orilumn.reader.engine.skia.SkiaParagraphBreaker] via [ParagraphShapes]) —
 * the same single source the canonical box flow ([orilumn.reader.engine.laying.NormalFlowLayout])
 * breaks with, so incremental/temp shaping and canonical pagination can never drift.
 *
 * C1-0: the Android `StaticLayout` shaping primitive is gone. Geometry is plain data
 * (`text` + per-line ranges/heights); [drawPaint] exists only for the Android-canvas
 * fallback drawing (table cells / list markers) and never influences breaks.
 *
 * A leaf may also be a **replaceable** block (an `img`) or a table row: it owns no
 * breakable text, so the shape instead reports a single synthetic line of
 * [replaceableBottom] pixels. Line geometry helpers ([lineCount]/[lineTop]/[lineBottom]/
 * [lineStart]/[lineEnd]/[originTop]) branch on [isReplaceable], so the flow/pagination
 * logic and the drawing layer both treat it uniformly.
 */
class ParagraphShape(
    /** The exact plain text the breaker consumed (spans never affect the string). */
    override val text: String = "",
    /** Per-line char ranges into [text] (exclusive-end `until` ranges, breaker output). */
    private val lineRanges: List<IntRange> = emptyList(),
    /** Per-line heights (px): the CSS line box, uniform across first/interior/last lines. */
    private val lineHeights: List<Int> = emptyList(),
    /** True when this shape represents a replaceable (img) block or table row (no text). */
    override val isReplaceable: Boolean = false,
    /** The single line's height for a replaceable block (ignored, 0, for text blocks).
     *  Mutable: the light path's rowspan redistribution resolves table-row heights after shaping. */
    override var replaceableBottom: Int = 0,
    /** The single line's exclusive char end for a replaceable block (1 char slot for img, a row's
     *  text length for a table row). Only affects [lineEnd]; text blocks ignore it. */
    override val replaceableCharEnd: Int = 1,
    /** Optional list marker drawn as an overlay in the shape's gutter (null = no marker). The marker
     *  is NOT part of the text; it does not affect char positions or line counting. */
    override val listMarker: ListMarkers.ListMarker? = null,
    /** Paint for Android-canvas fallback drawing (table cells / list markers). Shaping never
     *  consults it; null = nothing to draw with (replaceable/table-row shapes). */
    val drawPaint: TextPaint? = null,
    /** Block alignment, honored by the fallback per-line drawing (center/right offsets). */
    override val alignment: TextAlign = TextAlign.LEFT,
    /** Explicitly-colored spans over [text] (same coordinates; empty = all theme ink).
     *  Set by [ParagraphShapes.shapeOf] from the same walk that built [text], so indices
     *  always align; the skia window copies them into each DrawLine. */
    override val colorRuns: List<orilumn.reader.engine.css.ColorRun> = emptyList(),
    /** 行内 face 段 over [text]（同一坐标系，空 = 全段用块自身 face）：`<code>/<kbd>/<strong>` 等
     *  异 face 段，由 [ParagraphShapes.shapeOf] 从与 [text] 同构的遍历产出，索引恒对齐；断行器按
     *  它整形、绘制层（[orilumn.reader.engine.skia.LineWindowDrawer]）按它逐段着色。 */
    override val fontRuns: List<orilumn.reader.engine.css.FontRun> = emptyList(),
    /** 行内基线位移段 over [text]（同一坐标系，空 = 纯基线）：sub/sup 等，由 [ParagraphShapes.shapeOf]
     *  从与 [text] 同构的遍历产出，索引恒对齐；不断行几何，随段整形使量画一致；Android-canvas
     *  回退绘制按它逐段偏置基线。 */
    override val baselineShifts: List<orilumn.reader.engine.laying.BaselineShift> = emptyList(),
    /** P3-a 行阴影（颜色已解；null 即无；canvas 回退按它偏置复画一行）。 */
    override val textShadow: orilumn.reader.engine.css.TextShadow? = null,
    /** P3-a 着重号（NONE 即无；canvas 回退逐字置点/圈）。 */
    override val emphasis: orilumn.reader.engine.css.EmphasisStyle = orilumn.reader.engine.css.EmphasisStyle.NONE,
    /** P3-a 着重号位置（false＝行上方，true＝行下方）。 */
    override val emphasisUnder: Boolean = false,
    /** P3-a 祖先链 opacity 连乘（1 即旧路径；回退按它盖画笔 alpha）。 */
    override val alpha: Float = 1f,
    /** P6-b 叠排注音 runs over [text]（同一坐标系，空 = 无注音旧路径）：
     *  由 [ParagraphShapes.shapeOf] 从与 [text] 同构的遍历产出，索引恒对齐；
     *  行高已含注音增量，绘制把 rt 居中画在基字上方（双端同式）。 */
    override val rubyRuns: List<orilumn.reader.engine.laying.RubyRun> = emptyList(),
    /** 下划线区间 over [text]（同一坐标系，空 = 无下划线旧路径）：
     *  由 [ParagraphShapes.shapeOf] 从与 [text] 同构的遍历产出（声明元素整段传播）；
     *  回退绘制按段盖 `underlineText`。 */
    override val underlineRuns: List<orilumn.reader.engine.laying.UnderlineRun> = emptyList(),
    /** 取画笔请求（C2-P2b-3：`shapeOf` 包装装配，与几何同源；未来卷曲凭它取 face）。 */
    override val fontRequest: orilumn.reader.engine.laying.ShapeFontRequest =
        orilumn.reader.engine.laying.ShapeFontRequest(null, emptyList(), 400, false, false),
) : ParagraphShapeRef {
    // ---- ParagraphShapeRef（common 可读契约，表行展开进 DrawLine 窗用；纯委托） ----
    override val shapeText: String get() = text
    override val shapeLineCount: Int get() = lineCount
    override fun shapeLineStart(k: Int): Int = lineStart(k)
    override fun shapeLineEnd(k: Int): Int = lineEnd(k)
    override fun shapeLineTop(k: Int): Int = lineTop(k)
    override fun shapeLineBottom(k: Int): Int = lineBottom(k)
    override val shapeFontSizePx: Float get() = drawPaint?.textSize ?: 0f
    override val shapeAlignment: TextAlign get() = alignment
    override val shapeColorRuns: List<orilumn.reader.engine.css.ColorRun> get() = colorRuns
    override val shapeFontRuns: List<orilumn.reader.engine.css.FontRun> get() = fontRuns
    override val shapeBaselineShifts: List<orilumn.reader.engine.laying.BaselineShift> get() = baselineShifts
    override val shapeRubyRuns: List<orilumn.reader.engine.laying.RubyRun> get() = rubyRuns
    override val shapeUnderlineRuns: List<orilumn.reader.engine.laying.UnderlineRun> get() = underlineRuns
    override val shapeAlpha: Float get() = alpha
    override val shapeEmphasis: orilumn.reader.engine.css.EmphasisStyle get() = emphasis
    override val shapeEmphasisUnder: Boolean get() = emphasisUnder
    override val shapeTextShadow: orilumn.reader.engine.css.TextShadow? get() = textShadow
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

    /** Baseline offset inside a line box (paint ascent; 3/4 line height when paint is absent). */
    private val baselineOffset: Int by lazy {
        drawPaint?.let { runCatching { -it.fontMetricsInt.ascent }.getOrNull() }
            ?: (lineHeights.firstOrNull()?.let { it * 3 / 4 } ?: 0)
    }

    override val lineCount: Int get() = if (isReplaceable) 1 else lineRanges.size

    /** Raw y of the first line in the shape's own coordinates (always 0; kept for callers). */
    override fun originTop(): Int = 0

    /** Absolute y top of line [k] in the shape's own coordinates. */
    override fun lineTop(k: Int): Int = if (isReplaceable) 0 else lineTops.getOrElse(k) { 0 }

    /** Absolute y bottom of line [k] in the shape's own coordinates. */
    override fun lineBottom(k: Int): Int =
        if (isReplaceable) replaceableBottom
        else lineTop(k) + lineHeights.getOrElse(k) { 1 }.coerceAtLeast(1)

    /** Char start of line [k], relative to the shape's own text. */
    override fun lineStart(k: Int): Int = if (isReplaceable) 0 else lineRanges.getOrElse(k) { 0..0 }.first

    /** Exclusive char end of line [k], relative to the shape's own text. */
    override fun lineEnd(k: Int): Int = if (isReplaceable) replaceableCharEnd else lineRanges.getOrElse(k) { 0..0 }.last + 1

    /** Baseline y of line [k] in the shape's own coordinates (for drawing the list marker). */
    fun lineBaseline(k: Int): Int = if (isReplaceable) 0 else lineTop(k) + baselineOffset

    /** Draws this shape's list marker (if any) in the current canvas frame, aligned with the first
     *  line's baseline and the gutter the marker reserves. Call after the shape
     *  has been translated so shape-line y coords equal the frame's global y.
     *
     *  Bullet/shape kinds (disc/circle/square) are drawn as **vector shapes** sized relative to the
     *  font (not glyphs), so they look correct regardless of the font — fixing the "bullet rendered
     *  like a small period" issue. Ordered kinds (decimal/alpha/roman) are drawn as text with the
     *  shape's own paint (same face/color as the item text). */
    fun drawListMarker(canvas: Canvas) {
        val m = listMarker ?: return
        val paint = drawPaint ?: return
        val fm = paint.fontMetrics
        // Marker advance + gap; used to hang the OUTSIDE marker in the gutter left of the text.
        val markerW = if (m.isShapeKind) ListMarkers.shapeMarkerWidthPx(paint.textSize)
        else paint.measureText(m.text).roundToInt().coerceAtLeast(1)
        // OUTSIDE: marker hangs at a negative frame x (left of the text, in the ul/ol padding gutter);
        // text itself sits at contentLeft (no marker-driven indent). INSIDE: marker inline at line start.
        val x = if (m.position == ListMarkers.Position.INSIDE) 0 else -markerW - ListMarkers.markerGapPx(paint.textSize)
        if (m.isShapeKind) drawShapeMarker(canvas, m, paint, fm, x)
        else canvas.drawText(m.text, x.toFloat(), lineBaseline(0).toFloat(), paint)
    }

    /** Frame-x (≤0) left bound that must be visible for this shape: the hanging OUTSIDE gutter width
     *  (marker + gap), or 0 for inside/none. Drawers use this to widen the leaf clip so the marker is
     *  not cut off. */
    val listMarkerClipLeft: Float get() {
        val m = listMarker ?: return 0f
        if (m.position != ListMarkers.Position.OUTSIDE) return 0f
        val paint = drawPaint ?: return 0f
        val markerW = if (m.isShapeKind) ListMarkers.shapeMarkerWidthPx(paint.textSize)
        else paint.measureText(m.text).roundToInt().coerceAtLeast(1)
        return -(markerW + ListMarkers.markerGapPx(paint.textSize)).toFloat()
    }

    /** Draws a disc (filled circle) / circle (outline) / square at [x], vertically centered on line 0. */
    private fun drawShapeMarker(canvas: Canvas, m: ListMarkers.ListMarker, paint: Paint, fm: android.graphics.Paint.FontMetrics, x: Int) {
        val fx = x.toFloat()
        val size = paint.textSize * ListMarkers.SHAPE_MARKER_EM
        val half = size / 2f
        // Vertical center of the font's glyph span (baseline is at lineBaseline(0)).
        val midline = lineBaseline(0) + (fm.ascent + fm.descent) / 2f
        when (m.kind) {
            ListMarkers.Kind.SQUARE -> {
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawRect(fx, midline - half, fx + size, midline + half, fill)
            }
            ListMarkers.Kind.CIRCLE -> {
                val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = size / 7f }
                canvas.drawCircle(fx + half, midline, half - stroke.strokeWidth / 2f, stroke)
            }
            else -> { // DISC
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawCircle(fx + half, midline, half, fill)
            }
        }
    }
}
