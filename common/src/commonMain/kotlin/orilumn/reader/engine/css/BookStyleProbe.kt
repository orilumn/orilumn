package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * Reads a book's own typography back out of its CSS (pure, JVM-testable).
 *
 * Used when switching to 原书设置: the preset overwrites the conflicting UI slider values with the
 * book's real ones (a 2em-indent book → slider 2, a 0.3rem-gap book → slider 0.3), because the UI
 * layer always wins over the book at render time and blind neutrals would wipe the book's look.
 * Afterwards the normal flow applies (UI wins, user drags to override, incremental relayout) with
 * no render-time special cases. Sliders keep absolute semantics.
 *
 * The input [styles] must be computed WITHOUT the reader upper layers (no theme/settings/UI), i.e.
 * UA + author only, so the result is the book's own voice rather than an echo of the current sliders.
 * Every measure is the most frequent value over all `<p>` elements (document-order tie-break),
 * quantized to its slider step.
 */
object BookStyleProbe {

    /** One snapshot of the book's paragraph typography, ready to write into the UI sliders. */
    class Snapshot(
        /** 首行缩进 slider value (whole em, 0..10). */
        val firstLineIndent: Double,
        /** 段间距 slider value (em, 0..2, 0.1 step). */
        val paragraphSpacing: Double,
        /** 行距 slider value (ratio, 0.5..2.5, 0.1 step). */
        val lineSpacing: Double,
    )

    /** Snapshots [firstLineIndentEm], [paragraphSpacingEm] and [lineSpacingRatio] in one pass. */
    fun snapshot(styles: Map<MarkupElement, ComputedStyle>): Snapshot = Snapshot(
        firstLineIndent = firstLineIndentEm(styles),
        paragraphSpacing = paragraphSpacingEm(styles),
        lineSpacing = lineSpacingRatio(styles),
    )

    /**
     * The book's body-paragraph first-line indent in em: (`text-indent` / `font-size`),
     * quantized to whole em to match the 首行缩进 slider step (0..10).
     * 0.0 = the book has no first-line indent.
     */
    fun firstLineIndentEm(styles: Map<MarkupElement, ComputedStyle>): Double =
        modeOf(styles) { st ->
            if (st.fontSizePx <= 0f) null
            else (st.textIndentPx / st.fontSizePx).roundToInt().coerceIn(0, 10).toDouble()
        } ?: 0.0

    /**
     * The book's inter-paragraph gap in em: `margin-bottom` / `font-size`, quantized to 0.1 to
     * match the 段间距 slider (0..2). Bottom-only is the gap heuristic: books space paragraphs
     * with bottom margins (top 0), and the UI applies one value top+bottom whose collapse equals
     * that same gap — so writing the bottom mode reproduces the book's rhythm.
     */
    fun paragraphSpacingEm(styles: Map<MarkupElement, ComputedStyle>): Double =
        modeOf(styles) { st ->
            if (st.fontSizePx <= 0f) null
            else round1((st.margin.bottom / st.fontSizePx).toDouble()).coerceIn(0.0, 2.0)
        } ?: 0.0

    /**
     * The book's paragraph line-height ratio, quantized to 0.1 to match the 行距 slider
     * (0.5..2.5). Falls back to the engine default when the book declares nothing (which equals
     * the neutral preset, so the snapshot is a no-op there).
     */
    fun lineSpacingRatio(styles: Map<MarkupElement, ComputedStyle>): Double =
        modeOf(styles) { st -> round1(st.lineHeightRatio.toDouble()).coerceIn(0.5, 2.5) }
            ?: CssDefaults.DEFAULT_LINE_HEIGHT.toDouble()

    /** Most frequent non-null projection over `<p>` styles; ties → first seen (document order). */
    private fun modeOf(
        styles: Map<MarkupElement, ComputedStyle>,
        proj: (ComputedStyle) -> Double?,
    ): Double? {
        val counts = LinkedHashMap<Double, Int>()
        for ((el, st) in styles) {
            if (el.tag != "p") continue
            val v = proj(st) ?: continue
            counts[v] = (counts[v] ?: 0) + 1
        }
        var best: Double? = null
        var bestCount = 0
        for ((v, n) in counts) {
            if (n > bestCount) {
                best = v
                bestCount = n
            }
        }
        return best
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
