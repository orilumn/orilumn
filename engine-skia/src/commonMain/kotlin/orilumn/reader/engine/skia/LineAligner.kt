package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import kotlin.math.roundToInt

/**
 * S4 行内几何对齐器（**渲染层**）：把一行文本的字符区间落成逐字 x 坐标，并给出
 * 「行末可见右边界」。这是 S5 逐字绘制的前置件 —— 有了 placement，S5 只需按 x 逐字
 * `drawString`，不必再让 `ParagraphBuilder` 二次整形。
 *
 * ## 为什么需要它（真机实测缺陷，不是设想）
 *
 * 端到端跑真书（`Rust 程序设计语言`，`p { text-align: justify }`）时量到：
 * 版心 900、`STSong/serif` fs=47，中部行右边界在 **900.0 / 846.0 / 900.0 / 750.4** 之间跳。
 * 逐行尾码位打印定位到规律：**行末是文档空白（`U+0020`）时 JUSTIFY 拉伸量为 `+0.0`**，
 * 行末是汉字时才拉伸到版心。断行侧（[InhouseParagraphBreaker] 的 `hang`/`trail` 记账）
 * 判定宽**已经扣掉**尾空白，但那份信息没传下来 —— 绘制侧
 * （[LineWindowDrawer.paintText]）把 range 原样喂 `ParagraphBuilder`，Skia 按 CSS 2.1 §3.1
 * 挂起尾随空白、不拉伸，缺口也不消 ⇒ 视觉参差。**信息在断行→绘制之间丢了。**
 *
 * ## 本类就是那个传递通道
 *
 * 断行侧的「行末空白不占版心」由 [isDocumentSpace]（与断点判定**同一份单源**）在此复现：
 * 尾随文档空白**计入 range 但不计入可见宽度、且不参与拉伸**。这样 JUSTIFY 的拉伸基数
 * 回到「最后一个可见字形的右边缘」，两端对齐才看得到。
 *
 * ## 与断行侧的两条口径（**不要单方面「修正」**）
 *
 * 1. **`lsPx` 按码本数、含末字符**（`Σadv + n·lsPx`，[SkiaRunMeasurer] KDoc 已冻结）。
 *    直觉上「间隙只有 n−1 个」，但这套模型把 `lsPx` 当**每码本附加量**记账。
 *    本类的 `x_i = x_0 + Σ(w_j + lsPx_j)` 必须沿用同一口径，否则量画失配（教训 20）。
 *    —— 已有人栽过：看到「末字后多算一个 ls」就去改 `advances`，那会让 S2(a)/S4/S5 三处口径分家。
 * 2. **尾随文档空白不进可见宽度**，但**仍在 range 内**（字形仍要画，见下）。
 *
 * ## 尾随空白「不计入可见宽度」但仍要绘制
 *
 * 空白字形本身不可见（无墨），绘制它无害；而若把它从 range 剔除，下一行的
 * `first` 与本行的 `last` 就不再相接，`DrawLineBuilder` 拼行会出现**无主字符**。
 * 故本类保留它的 placement（x = 前一字右边缘），只是**不把它算进 `visibleRight`**，
 * 也不让它参与拉伸分配。**区间无缝是硬约束**，改 range 是 S4 明确不做的事。
 */
internal class LineAligner(
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
) {

    /** 一行逐字落位结果。`x[i]` 对应 `range[i]` 的字符（与 [range] 同坐标、同长度）。 */
    data class Placement(
        /** 行内逐字 x（首字为 `x0`，末字为 `x0 + Σ(w+ls)`）。与 range 同长。 */
        val xs: FloatArray,
        /**
         * 与 [xs] 同长的**逐字 advance**（已含该码本的 `lsPx`，未含 JUSTIFY 拉伸量）。
         *
         * 单独存一份而不是从 `xs` 相减反推：ruby 注音要**每个基字的精确字形盒宽**来居中，
         * 反推会在末字（无后继）与含拉伸的区间上失真（教训：能用原始量就别用二次推算）。
         */
        val advs: FloatArray,
        /** 本行可见右边界：**最后一个非文档空白字符**的右边缘。末行/全空白行为最后字符右边缘。 */
        val visibleRight: Float,
        /** 行末连续文档空白的 x 起点（= 尾随空白**之前**那个字符的右边缘；无尾空白则 = [visibleRight]）。 */
        val trailStartX: Float,
    )

    /**
     * 把 `text[range]` 对齐落位。
     *
     * @param lineWidthPx 本行整形宽（[orilumn.reader.engine.skia.DrawLine.lineWidthPx]）。
     * @param justify 是否拉伸（[TextAlign.JUSTIFY] 且**非末行**时为 true —— 末行按定义左对齐）。
     * @param isLastLine 该行是否为段落末行。末行不拉伸（CSS 两端对齐的定义本身）。
     */
    fun align(
        text: CharSequence,
        range: IntRange,
        fontSizePx: Float,
        lineWidthPx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        align: TextAlign = TextAlign.LEFT,
        firstLineIndentPx: Float = 0f,
        isLastLine: Boolean = false,
    ): Placement {
        val start = range.first.coerceIn(0, text.length)
        val endExcl = (range.last + 1).coerceIn(start, text.length)
        val n = endExcl - start
        if (n <= 0) {
            return Placement(FloatArray(0), FloatArray(0), firstLineIndentPx, firstLineIndentPx)
        }

        // 取宽与断行侧同一出口（`adv[i]` 已含该码本的 `lsPx`，见类 KDoc 第 1 条）。
        val adv = measurer.advances(
            text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace,
            sliceRuns(fontRuns, start, endExcl),
        )

        // 尾随文档空白：计入 range、画（无墨无害）、但**不计入可见宽度**。
        var visEnd = endExcl
        while (visEnd > start && isDocumentSpace(text[visEnd - 1])) visEnd--
        val trailLen = endExcl - visEnd
        val visibleCount = visEnd - start
        val x0 = firstLineIndentPx.coerceAtLeast(0f)

        if (visibleCount == 0) {
            // 整行皆文档空白：无可见字符，placement 仍铺满区间（区间无缝），右边界退到行首。
            return Placement(FloatArray(n) { x0 }, FloatArray(n), x0, x0)
        }

        /**
         * **末字右边缘**（= 可见行宽）= `x0 + Σ_{j<visibleCount-1} adv[j]`。
         *
         * 刻意**不是** `Σ_{j<visibleCount} adv[j]`：末字那一份 advance 里含它自己的 `lsPx`，
         * 那是排在末字**之后**的不可见附加量，计入就等于把行末的不可见宽度算进「可见右边界」，
         * 实测直接溢出版心（`907.248 > 900`）。同理行末尾随空白的起点就是这里。
         */
        var natural = x0
        for (k in 0 until visibleCount - 1) natural += adv[k]

        // JUSTIFY 拉伸：均摊到**可见字符之间的 N-1 个间隙**；末行不拉伸（两端对齐的定义本身）。
        val doJustify = align == TextAlign.JUSTIFY && !isLastLine && visibleCount > 1
        val slack = lineWidthPx - natural
        val extra = if (doJustify && slack > 0f) slack / (visibleCount - 1) else 0f

        val xs = FloatArray(n)
        var x = x0
        for (k in 0 until n) {
            xs[k] = x
            x += adv[k]
            // 拉伸只加在**可见字符之间**：k 是前一个字符的本地下标，末字之后不加。
            if (extra != 0f && k < visibleCount - 1) x += extra
        }

        // 行末尾随空白紧贴末字右边缘；无尾随空白时 trailStartX 即 natural。
        val trailStartX = natural + extra * (visibleCount - 1)
        return Placement(xs, adv.copyOf(n), trailStartX, trailStartX)
    }

    /** 行末圆整右边界（版心对齐判据用，避免 899.9997 判成未铺满）。 */
    fun visibleRightInt(p: Placement): Int = p.visibleRight.roundToInt()

    /**
     * `[from, to)`（**range 本地坐标**）的可见左右并集，供 ruby 注音居中 / 着重号定位用。
     *
     * 取代 Skia `Paragraph.getRectsForRange(RectWidthMode.TIGHT)`：那个量的是 Skia 整形
     * 后的墨盒，与本仓「裸 cmap 量宽」口径不同源；[Placement.xs] 才是落墨真用的 x。
     * **量画同源由此保证**（教训 20）。返回 null 表示区间无有效字形。
     */
    fun visibleSpan(p: Placement, rangeLen: Int, from: Int, to: Int): Pair<Float, Float>? {
        val lo = from.coerceIn(0, rangeLen)
        val hi = to.coerceIn(lo, rangeLen)
        if (hi <= lo) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (k in lo until hi) {
            val x = p.xs.getOrNull(k) ?: continue
            left = minOf(left, x)
            right = maxOf(right, x + (p.advs.getOrNull(k) ?: 0f))
        }
        if (right <= left) return null
        return left to right
    }

    private fun sliceRuns(runs: List<FontRun>, start: Int, endExcl: Int): List<FontRun> {
        if (runs.isEmpty()) return runs
        val len = endExcl - start
        val out = ArrayList<FontRun>(runs.size)
        for (r in runs) {
            val s = (r.start - start).coerceAtLeast(0)
            val e = (r.endExclusive - start).coerceAtMost(len)
            if (e > s && s < len) out.add(r.copy(start = s, endExclusive = e))
        }
        return out
    }
}