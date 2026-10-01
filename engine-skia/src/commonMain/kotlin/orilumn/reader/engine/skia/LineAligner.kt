package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.lineHeightPx
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
        /**
         * 本行**可见右边界** = 最后一个非文档空白字符的**右边缘**（含该字符本体的宽，
         * **不含**它之后那个不可见的 `lsPx`）。末行/全空白行退到行首。
         *
         * 判据用途：行尾空区（`x > visibleRight` 不该吸附到行尾字形）、下划线/着重号的区间右界。
         */
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
        //
        // ⚠ **必须传本行的子串**，不能传整段 `text`：本方法后续一律用**行内局部下标**
        // （`adv[k]`，k 从 0 起），而 [SkiaRunMeasurer.advances] 返回的数组与其 `text` 形参
        // **同一坐标**——传全串就得到全串绝对下标，再拿局部下标去索引即**整段错位**。
        // [sliceRuns] 把 run 坐标平移成局部，也正是配套「局部子串」的做法。
        //
        // 这个 bug 的暴露方式很隐蔽：**首行 `start == 0` 时恰好正确**，所以只测首行/
        // 只测单行的用例全绿；一旦有第二行，该行的逐字宽就全取自别的字符 ——
        // 实测超长单词在版心 80 下每个单字符行都量成 `30.46`（那是 `'D'` 的宽，`'f'` 实为 14.05），
        // 于是凭空冒出 11~65px 的「溢出」（`NoLineExceedsContentWidthTest` 抓到）。
        val adv = measurer.advances(
            text.subSequence(start, endExcl), fontSizePx, letterSpacingEm, tag, families,
            weight, italic, monospace, sliceRuns(fontRuns, start, endExcl),
        )

        // 尾随文档空白：计入 range、画（无墨无害）、但**不计入可见宽度**。
        var visEnd = endExcl
        while (visEnd > start && isDocumentSpace(text[visEnd - 1])) visEnd--
        val trailLen = endExcl - visEnd
        val visibleCount = visEnd - start
        // **对齐的水平定位**（S5 第一版漏了，只做了 JUSTIFY ⇒ CENTER 行左缘起墨，`LineWindowDrawerTest`
        // 1 把红）。四种语义在此**一次性**算清，绘制侧不再各自为政：
        //  - LEFT   ：x0 = 缩进。
        //  - JUSTIFY：x0 = 缩进，可见行末恰好贴版心右缘（slack 均摊，见下）。
        //  - CENTER ：整体右移 (lineWidth - 可见宽)/2。
        //  - RIGHT  ：整体右移 (lineWidth - 可见宽)。
        // 可见宽在拉伸**之后**才是最终宽，故 CENTER/RIGHT 的偏移必须用拉伸后的值 ——
        // 顺序上：先按 JUSTIFY 算 natural + extra 得「最终可见宽」，再据此定位。
        val x0Raw = firstLineIndentPx.coerceAtLeast(0f)

        if (visibleCount == 0) {
            // 整行皆文档空白：无可见字符，placement 仍铺满区间（区间无缝），右边界退到行首。
            return Placement(FloatArray(n) { x0Raw }, FloatArray(n), x0Raw, x0Raw)
        }

        /**
         * **末字右边缘**（= 可见行宽）= `x0 + Σ_{j<visibleCount-1} adv[j]`。
         *
         * 刻意**不是** `Σ_{j<visibleCount} adv[j]`：末字那一份 advance 里含它自己的 `lsPx`，
         * 那是排在末字**之后**的不可见附加量，计入就等于把行末的不可见宽度算进「可见右边界」，
         * 实测直接溢出版心（`907.248 > 900`）。同理行末尾随空白的起点就是这里。
         */
        /**
         * **可见行宽** = `Σ_{j<N-1} advance_j + w_{N-1}` = `Σ_{j<N} advance_j − ls_{N-1}`。
         *
         * 语义（本轮踩了三次才对齐，**三个量必须分清**）：
         * - `Σ_{j<N-1} advance_j` = **末字左缘**：前 N−1 个字符各自的 `ls_j` 是它们**之后**确实
         *   存在的间隙（字符 j 与 j+1 之间），**该计入**。
         * - `w_{N-1}` = 末字的**纯字宽**：它的 `ls_{N-1}` 排在末字**之后**、无后继字符，
         *   **不可见、不该计入**。
         * - 故可见右缘 = 末字左缘 + 纯字宽，**不是** `+ adv[末字]`（那多算一个 `lsPx`）。
         *
         * 这是 JUSTIFY 的**拉伸基数**，也是 `visibleRight`。两处必须同源，否则
         * 「拉伸后铺满版心」与「算出的可见右缘」会差一个身位（实测 907.248 / 944.4）。
         */
        val lastLs = if (letterSpacingEm == 0f) 0f
            else letterSpacingEm * lastRunSizePx(text, start, visibleCount - 1, fontSizePx, fontRuns)
        var natural = x0Raw
        for (k in 0 until visibleCount) natural += adv[k]
        natural -= lastLs

        // JUSTIFY 拉伸：均摊到**可见字符之间的 N−1 个间隙**；末行不拉伸（两端对齐的定义本身）。
        val doJustify = align == TextAlign.JUSTIFY && !isLastLine && visibleCount > 1
        val slack = lineWidthPx - natural
        val extra = if (doJustify && slack > 0f) slack / (visibleCount - 1) else 0f
        // 拉伸后总宽恒等于 natural + extra×(N−1)，故 JUSTIFY 时正好铺满版心。
        val finalVisible = natural + extra * (visibleCount - 1)
        // CENTER/RIGHT 按**最终**可见宽定位（用拉伸前的 natural 会偏）。
        val x0 = when (align) {
            TextAlign.CENTER -> x0Raw + (lineWidthPx - finalVisible).coerceAtLeast(0f) / 2f
            TextAlign.RIGHT -> x0Raw + (lineWidthPx - finalVisible).coerceAtLeast(0f)
            else -> x0Raw
        }

        val xs = FloatArray(n)
        var x = x0
        for (k in 0 until n) {
            xs[k] = x
            x += adv[k]
            // 拉伸只加在**可见字符之间**：k 是前一个字符的本地下标，末字之后不加。
            if (extra != 0f && k < visibleCount - 1) x += extra
        }

                // 行末尾随空白紧贴**可见右缘**（含对齐偏移 x0）。
        val trailStartX = x0 + finalVisible
        return Placement(xs, adv.copyOf(n), trailStartX, trailStartX)
    }

    /**
     * 末字所属 run 的**字号**（逐 run 查回，而非用全局值）。
     *
     * 行内换面 run 有各自字号（`fontRuns[].fontSizePx`），用全局 `fontSizePx` 会在换面行上
     * 算错末字的 `lsPx` ⇒ 拉伸基数偏 ⇒ JUSTIFY 铺不满或溢出。
     */
    private fun lastRunSizePx(
        text: CharSequence, start: Int, lastLocal: Int, fontSizePx: Float, fontRuns: List<FontRun>,
    ): Float {
        if (fontRuns.isEmpty()) return fontSizePx
        val abs = start + lastLocal
        return fontRuns.firstOrNull { it.start <= abs && abs < it.endExclusive }?.fontPxOr(fontSizePx) ?: fontSizePx
    }

    /** 行末圆整右边界（版心对齐判据用，避免 899.9997 判成未铺满）。 */
    fun visibleRightInt(p: Placement): Int = p.visibleRight.roundToInt()

    /**
     * S5 逐字绘制的**基线 y**（相对行顶 `yTop`）。
     *
     * CSS 2.2 §10.8.1 半行距居中：`baseline = halfLeading + ascent`，
     * 其中 `halfLeading = (lineHeightPx - (ascent + descent)) / 2`（可负，负值即行盒装不下时的溢出）。
     *
     * ## 为什么必须自己算（第一版漏了它，7 把锁全红「行无墨」）
     *
     * 旧路径 `Paragraph.paint` **内部**按 `setHeight` + `setHalfLeading(true)` 落基线，
     * 绘制侧只需给 `yTop`；逐字 `drawString` 画的原点**就是基线**，少加这两项就等于
     * 把整行字画在 `yTop`（yTop=0 时画到画布顶边被裁），表现为「行没有墨」。
     * 插桩打出来是 `y=0.0` 才定案的 —— 字体/颜色/整栈回退当时都是对的。
     *
     * @param ascentDescent `font.getMetrics()` 的 `ascent - descent`（Skia 约定 ascent 为负、
     *   descent 为正，故字体盒高 = `descent - ascent`；本函数按**传入的盒高**算，不假设符号）。
     */
    fun baselineOffset(
        yTop: Float,
        fontSizePx: Float,
        lineHeightRatio: Float,
        ascentPx: Float,
        descentPx: Float,
        extraTop: Float = 0f,
    ): Float {
        val lineH = lineHeightPx(fontSizePx, lineHeightRatio)
        val boxH = ascentPx + descentPx
        val halfLeading = (lineH - boxH) / 2f
        return yTop + extraTop + halfLeading + ascentPx
    }

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