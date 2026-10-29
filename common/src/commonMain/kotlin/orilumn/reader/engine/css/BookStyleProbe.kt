package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * Reads a book's own typography back out of its CSS (pure, JVM-testable).
 *
 * 唯一用途是**面板读数**（`BookDocumentController.bookStyleReadings`，`docs/设置模型整改计划.md`
 * §3.3 / §3.4）：读者打开设置面板时量一次，滑块显示「原书实际是多少」——读者没配该项时
 * 看到的就是书的真实值（行高书没写就是引擎初值 1.5，段距书没写就是 UA 基线 1em）。读数只进
 * `probeIndent` / `probeLineSpacing` / `probeParagraphSpacing` 三个会话只读字段：
 * **不改版式、不进分页键、不落盘**。
 *
 * 开书探针（`probeOriginalOnOpen`）与「切原书设置时回填滑块」都已退役（§3.7）：新模型里
 * 「每本书先读原书」靠**没配就不发声明**让级联自己读，不需要量出来再写进设置。
 *
 * 段间距读数**含 UA 基线**（`ua.css` 的 `p { margin: 1em 0 }`）：书没写 margin 时引擎实际
 * 用的就是这 1em，面板得显示它而不是 0（与行高书没写显示 1.5 同口径）。夹在 0..2em。
 *
 * The input [styles] must be computed WITHOUT the reader upper layers (no theme/settings/UI), i.e.
 * UA + author only, so the result is the book's own voice rather than an echo of the current sliders.
 * Every measure is the most frequent value over all `<p>` elements (document-order tie-break),
 * quantized to its slider step.
 */
object BookStyleProbe {

    /** One snapshot of the book's paragraph typography, ready to show on the UI sliders. */
    class Snapshot(
        /** 首行缩进 slider value (whole em, 0..10). */
        val firstLineIndent: Double,
        /** 行距 slider value (ratio, 0.5..2.5, 0.1 step). */
        val lineSpacing: Double,
        /** 段间距 slider value (em, 0..2, 0.1 step); includes the UA 1em baseline. */
        val paragraphSpacing: Double,
        /** 字间距 slider value (em, -0.2..0.2)：引擎实际施加的正文字距。 */
        val letterSpacing: Double,
    )

    /** Snapshots [firstLineIndentEm], [lineSpacingRatio], [paragraphSpacingEm] and
     *  [bodyLetterSpacingEm] in one pass. [root] 给出时按**与引擎同一边取样**（树序第一个 `<p>`）。 */
    fun snapshot(styles: Map<MarkupElement, ComputedStyle>, root: MarkupElement? = null): Snapshot = Snapshot(
        firstLineIndent = firstLineIndentEm(styles),
        lineSpacing = lineSpacingRatio(styles),
        paragraphSpacing = paragraphSpacingEm(styles),
        letterSpacing = if (root != null) bodyLetterSpacingEm(root) { styles[it] }.toDouble() else letterSpacingEm(styles),
    )

    /**
     * 书自带的字距（em，`letter-spacing / font-size`）：取**文档序第一个 `<p>`**（引擎全章共用
     * 一个值，见 `BoxChapterLayouter.chapterLetterSpacingEm`）；一个 `<p>` 都没有时读根元素
     * （书常把字距写在 `body` 上，继承下来同一个 px 值，换算到段落的 em 后与段内一致）。
     * 都没有就是 0。
     */
    fun bodyLetterSpacingEm(root: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle?): Float {
        var found: MarkupElement? = null
        fun walk(el: MarkupElement) {
            if (found != null) return
            if (el.tag == "p") {
                found = el
                return
            }
            el.children.forEach { walk(it) }
        }
        walk(root)
        val st = found?.let(styleOf) ?: styleOf(root) ?: return 0f
        if (st.fontSizePx <= 0f) return 0f
        return st.letterSpacingPx / st.fontSizePx
    }

    /** 同上，但只拿得到级联结果（面板测试/无树调用方）：文档序第一个 `<p>`。 */
    fun letterSpacingEm(styles: Map<MarkupElement, ComputedStyle>): Double {
        val st = styles.entries.firstOrNull { it.key.tag == "p" && it.value.fontSizePx > 0f }?.value
            ?: return 0.0
        return (st.letterSpacingPx / st.fontSizePx).toDouble()
    }

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
     * The book's paragraph line-height ratio, quantized to 0.1 to match the 行距 slider
     * (0.5..2.5). Falls back to the engine default when the book declares nothing (which equals
     * the neutral preset, so the snapshot is a no-op there).
     */
    fun lineSpacingRatio(styles: Map<MarkupElement, ComputedStyle>): Double =
        modeOf(styles) { st -> round1(st.lineHeightRatio.toDouble()).coerceIn(0.5, 2.5) }
            ?: CssDefaults.DEFAULT_LINE_HEIGHT.toDouble()

    /**
     * 书的正文段间距（em，0..2，量化到 0.1 与滑块一致）：`<p>` 的
     * `max(margin-top, margin-bottom) / font-size` 众数 —— 垂直外边距相邻折叠取较大者，
     * 两段之间的距离就是这个值。书没写 margin 时读到 UA 基线的 1em（引擎的实际默认节奏）。
     */
    fun paragraphSpacingEm(styles: Map<MarkupElement, ComputedStyle>): Double =
        modeOf(styles) { st ->
            if (st.fontSizePx <= 0f) null
            else round1((maxOf(st.margin.top, st.margin.bottom) / st.fontSizePx).toDouble())
                .coerceIn(0.0, 2.0)
        } ?: 0.0

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
