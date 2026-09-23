package orilumn.reader.engine.laying

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import kotlin.math.roundToInt

/**
 * The text-shaping seam (pure JVM, implementation-swappable).
 *
 * The block-flow layouter ([orilumn.reader.engine.laying.NormalFlowLayout]) only depends on this interface to turn a styled run
 * of text into line break offsets. The production real implementation is engine-skia's
 * [orilumn.reader.engine.skia.SkiaParagraphBreaker] (the app-side `StaticLayoutBreaker` retired in Q1);
 * tests may substitute a deterministic fake to exercise flow/box logic on
 * the JVM without a font stack. This is the seam that lets a future in-house breaker replace Skia later
 * with zero impact on surrounding code.
 *
 * [alignment] must be applied by the shaper exactly as the drawing layer applies it, so the measured
 * geometry (line count / char ranges) always matches what the drawing layer paints — otherwise
 * page cuts and the drawn glyph positions fall out of phase.
 */
interface ParagraphBreaker {
    /**
     * Breaks a single text run into line char-ranges **and per-line heights** for the given text
     * size, line-height ratio and content width.
     *
     * The height is the authority the box flow uses to lay lines out, so the drawing shaper and the
     *  geometry must agree on it:
     *  - the production SkiaParagraphBreaker returns the CSS line box `round(fontSizePx x
     *    lineHeightRatio)` for EVERY line (first/interior/last), exactly matching what the
     *    drawing layer paints;
     *  - a deterministic fake (JVM tests) returns `round(fontSizePx x lineHeightRatio)`.
     *
     * @param text the run's text.
     * @param fontSizePx the run's font size (px), informing how many chars fit per line.
     * @param lineHeightRatio the run's CSS line-height ratio (relative to font-size).
     * @param widthPx the content width available for this run (px).
     * @param alignment the run's CSS `text-align`; must match the draw shaper (JUSTIFY affects char ranges).
     * @param tag the run's block tag (`h2`, `p`, `pre`, `code`, `#text`, …), for reader font pairing.
     * @param families the inherited CSS `font-family` stack in author order (generic keywords kept),
     *   for typeface pairing with fallback past uninstalled names.
     * @param weight the numeric CSS `font-weight` (100–900).
     * @param italic the CSS `font-style` italic flag.
     * @param monospace whether the run resolves to a monospace face; both paths must agree so line
     *   widths (and thus breaks) match what [orilumn.reader.engine.layout.ParagraphShapes] draws.
     * @return one [BrokenLine] per line, each carrying its char range into [text] and its height (px).
     */
    fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine>

    /**
     * [breakLines] with CSS `text-indent` for the run's first line only (px, >= 0; hang/negative
     * out of scope) — the shaper shortens the first line by it, the drawer offsets the first line
     * by it. Non-first lines are unaffected on both sides.
     *
     * Default implementation delegates to the indent-agnostic overload (test fakes keep working
     * untouched); production [orilumn.reader.engine.skia.SkiaParagraphBreaker] overrides with real
     * `TextIndent` shaping. Callers that care about indent MUST call this overload.
     */
    fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace)

    /**
     * [breakLines] with per-substring [FontRun]s ([orilumn.reader.engine.css.FontRun], same text
     * coordinates) — browser inline-run semantics: the run's substring is shaped under ITS face
     * (families/tag/weight/italic/mono), the rest under the base face. Breaking MUST shape under the
     * runs so line ranges match what [orilumn.reader.engine.skia.LineWindowDrawer] paints (it shapes the
     * same runs); shapers that never see runs stay on the simpler overloads via the default that
     * ignores them.
     *
     * Empty [fontRuns] ≡ the whole text uses [families]/[weight]/[italic]/[monospace] (byte-identical
     * to the single-style overloads — run-less callers are unaffected).
     *
     * @param baselineShifts P1-2 行内基线位移（[BaselineShift]，同文本坐标系；位移不改变
     *   宽度/断行，只随段整形使量画一致；空 = 纯基线）。
     */
    fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float, fontRuns: List<FontRun>, baselineShifts: List<BaselineShift> = emptyList()): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx)

    /**
     * P6-a: 不换行时的首选宽度（px，shrink-to-fit 依据）。生产 Skia 实现真测字形宽度；
     * 默认实现按 1em/char 保守估计（CJK 精确、拉丁偏宽——永不窄于实需，双路恒一致）。
     */
    fun preferredWidth(
        text: CharSequence,
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
    ): Float = text.length * fontSizePx

    /**
     * min-content 宽（px）：内容可在任意断点换行、且**不溢出**时所需的最小可用宽——浏览器
     * `width: min-content` 口径，即「最长不可断单元」的宽。
     *
     * 与 [preferredWidth]（max-content）同为 auto 分列 `cellPref` 的输入：先按 [minContentSegments]
     * 纯函数切出断行机会，再逐段用 [preferredWidth] 真测取最大。同一 [text]/[fontRuns] 下
     * `min ≤ max` 恒成立，且重/轻两路恒同（同断行器、同输入即同值）。
     *
     * 默认实现（测试 fake / 无字体栈）每段退化为 `段长 x fontSizePx`，仍确定性；
     * 生产 [orilumn.reader.engine.skia.SkiaParagraphBreaker] 的 [preferredWidth] 是真字形宽，
     * 故本方法在 Skia 下即真度量（Chrome 实测对齐，见 [minContentSegments]）。
     */
    fun minContentWidth(
        text: CharSequence,
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
    ): Float {
        if (text.isEmpty() || fontSizePx <= 0f) return 0f
        var best = 0f
        for (seg in minContentSegments(text)) {
            val w = preferredWidth(
                text.subSequence(seg.first, seg.last + 1),
                fontSizePx, families, weight, italic, monospace,
                fontRunsWithin(fontRuns, seg.first, seg.last + 1),
            )
            if (w > best) best = w
        }
        return best
    }
}

/** [runs] 裁剪到 `[from, to)` 并平移到子串坐标系（[ParagraphBreaker.minContentWidth] 逐段度量用）。 */
private fun fontRunsWithin(runs: List<FontRun>, from: Int, to: Int): List<FontRun> {
    if (runs.isEmpty()) return emptyList()
    var out: ArrayList<FontRun>? = null
    for (r in runs) {
        val s = maxOf(r.start, from)
        val e = minOf(r.endExclusive, to)
        if (e <= s) continue
        if (out == null) out = ArrayList(runs.size)
        out.add(r.copy(start = s - from, endExclusive = e - from))
    }
    return out ?: emptyList()
}

/** One produced text line of a run: its char range into the run's text plus its height in px. */
class BrokenLine(val range: IntRange, val heightPx: Int)

/**
 * Pure, **single-source** form of the uniform line height for a `fontSizePx x lineHeightRatio` run.
 * Both line-rebuild paths ([orilumn.reader.engine.BoxChapterLayouter.rebuildLinesFromShapes] and `rebuildLocalLines`)
 * compute a paragraph's last-line height from this, so the heavy and light paths can never fall out
 * of phase on the last line.
 */
fun lineHeightPx(fontSizePx: Float, lineHeightRatio: Float): Int {
    val ratio = if (lineHeightRatio > 0f) lineHeightRatio else 1f
    return (fontSizePx * ratio).roundToInt().coerceAtLeast(1)
}

/**
 * One produced text line in the global layout: char offsets refer to the whole laid-out stream, and
 * y offsets are relative to the content-area top.
 */
class FlowedLine(
    val charStart: Int,
    val charEnd: Int,
    val yTop: Int,
    val yBottom: Int,
    /** Whether this line begins a paragraph (block boundary) — the page-break anchor. */
    val paragraphStart: Boolean,
)