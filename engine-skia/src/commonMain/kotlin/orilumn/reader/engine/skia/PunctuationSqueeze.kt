package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.laying.SOFT_HYPHEN
import orilumn.reader.engine.laying.isSqueezableClosingPunct

/**
 * **标点挤压**（渲染层·几何测量）：把全角收尾标点的字位**收窄**，换出版心宽度。
 *
 * ## 它是「供给侧」，与 JUSTIFY 的 slack 分配是串联不是并列
 *
 * 两端对齐要铺满版心，缺的那段宽（`slack`）得有人出。出钱的只有两类东西：
 *
 * | | 作用对象 | 几何动作 | 额度规则 |
 * |---|---|---|---|
 * | **挤压**（本对象） | **字位内部的留白**（1 槽） | 改**宽度** | 硬上限（[DEFAULT_MAX_EM]）+ 墨迹余量 |
 * | **优先级分配**（[LineAligner]） | **字位之间的缝**（N−1 槽） | 改**位置** | 逐级回落，无上限 |
 *
 * 「逐级回落」的前提是**同一种资源可在多个槽上重复分配**，挤压不是那个资源 ——
 * 它是**造**slack 的。于是代数是串联的：
 * ```
 * natural' = natural − S     ⇒     slack' = slack + S     ⇒     extra' = extra + S / gapCount
 * ```
 * 而 `xs'[N−1] = xs[N−1] + S`：末字 advance 右缘**不动**（仍等于版心），**墨迹**右缘右移 S。
 * ⇒ **行中挤压省行数（可能改变断点），行尾挤压只修右缘参差**。两者收益不同，来源是同一个 S。
 *
 * ## 为什么必须进**断行侧**预留，不能只在画侧（用户裁决）
 *
 * 挤压是「让出一段宽度」，而断行决策是「这一行放不放得下」。只在画侧挤压 ⇒ 断行器按未挤的宽度
 * 判定放得下、画出来少一截 ⇒ 该省的一行没省（`。` 在行尾时这一行本来能多塞一个字）。
 * 用户点出的正是这条：**适当挤压后行末往往就不需要断词了**。
 * 故两侧必须算**同一个 S**（[widths] 是唯一实现，断行器与 [LineAligner] 都调它）⇒ **画 == 量**。
 *
 * ## 额度公式
 *
 * ```
 * S(i) = min(maxEm × size(i),  max(0, w(i) − inkRight(i)) + inkLeft(i+1))
 * w(i) = adv(i) − lsPx(i)
 * ```
 *
 * 三项各有不可省的来由：
 *
 *  - **`maxEm × size`**：产品裁决 **0.5em**。它是**美学**上限，不是物理上限 ——
 *    全角标点收到 0.5em 是「半角化」，再窄就压到相邻字身上了。
 *  - **`w − inkRight`**：物理余量。Skia **允许墨溢出 advance**（墨盒可以比 advance 宽），
 *    压过头墨会出版心被裁 —— 分页阅读器里那不是难看，是**内容丢失**。
 *    实测这一项**不可省**：Source Han Sans SC @43.75 的 `？` 墨迹 0.503em、`…` 0.869em，
 *    固定收 0.5em 会让 `…` 的墨叠到后一个字身上。
 *  - **`inkLeft(i+1)`**：相邻两字**墨迹不相接**的那条余量。第 i 槽收窄 S 后，
 *    第 i+1 槽左移到 `x_i + w' + inkLeft(i+1)`；要它不早于第 i 字的墨迹右缘，
 *    需 `S ≤ w − inkRight + inkLeft(i+1)`。缺了它就会在「下一字左伸（负边距）」时压出墨迹重叠。
 *
 * ### 为什么用 `w = adv − lsPx` 而不是 `adv`
 *
 * 末可见字那一份 `lsPx` 排在它**之后、无后继**，是**不可见**的（[LineAligner] 的 `natural`
 * 明确减掉它）。拿 `adv` 当盒宽会把这份不可见宽度也当成可压的余量 ⇒ 末字墨迹能超出版心一个 `lsPx`。
 * 代价是行中字符少压了 `lsPx`（保守，方向安全），换来**末字那条硬边界**成立。
 *
 * ## 上界
 *
 * [appliedMaxEm] 在 [AbSwitch.inhouseBreak] 关（回退到 Skia 断行器）时返 **0**。
 * **这一条不是溢出防护，是回退保真**：Skia 断行器按未挤的宽度决定断点，
 * 照挤只会让它的每一行都短掉一截（LEFT 对齐时右缘提前）。挤压**只减不增**，
 * 所以最坏也只是排得松一点，**不可能超出版心**（与 [orilumn.reader.engine.text.preprocess.CjkLatinSpacing]
 * 那种「注入宽度」的相反性质 —— 那个必须两侧同进同退，这个只需要两侧同进）。
 */
internal object PunctuationSqueeze {

    /** 上限（em）—— 产品裁决 0.5em：全角收尾标点最多收到半角。 */
    const val DEFAULT_MAX_EM: Float = 0.5f

    /**
     * **真正施加到版面上的上限**（em）：回退阀关时为 0。
     *
     * 与 [orilumn.reader.engine.text.TypographicProfile.cjkLatinSpacingEmApplied] 同一个闸门
     * （[AbSwitch.inhouseBreak]），但**不需要进 `paramHash`**：本值是编译期常量，
     * 不随设置变化 ⇒ 断点集合不随它变 ⇒ 磁盘表键不必含它。
     * ⚠ 一旦它变成滑块（用户可调），**必须**同时进 [orilumn.reader.engine.text.LayoutParamKey]，
     * 否则拨滑块会命中按另一个额度算出的旧磁盘表。
     */
    fun appliedMaxEm(): Float = if (AbSwitch.inhouseBreak()) DEFAULT_MAX_EM else 0f

    /**
     * **逐字位的可释放宽度**（px，数组与 `[from, to)` 同长且同坐标；不可压的位置恒 `0f`）。
     *
     * 断行器（预留版心，[orilumn.reader.engine.skia.InhouseParagraphBreaker]）与
     * [LineAligner]（落 x 位）**都调本方法**，且两侧传**同一份 `adv` 与同一套形参**
     * ⇒ 同一个位置在两侧得到同一个 S ⇒ 画 == 量。
     *
     * @param adv 该位置的 advance（**含 `lsPx` 与混排间隙**，即 [SkiaRunMeasurer.advances] 的原样输出）。
     * @param advBase `adv[0]` 对应的**绝对**下标 —— [LineAligner] 传的是**行内局部** `adv`，
     *   断行器传整段 `adv` 时为 0。这个形参是两侧能共用本方法的关键，别删。
     * @param maxEm 上限（em）。0 = 整条关掉，返回全 0（调用点仍会走一遍筛选，无副作用）。
     */
    fun widths(
        text: CharSequence,
        from: Int,
        to: Int,
        adv: FloatArray,
        advBase: Int,
        measurer: SkiaRunMeasurer,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
        maxEm: Float,
    ): FloatArray {
        val out = FloatArray((to - from).coerceAtLeast(0))
        if (maxEm <= 0f) return out
        // 缓存键必须覆盖**全部**决定 face 的参数 —— 与 [InhouseParagraphBreaker.hyphenWidths] 同款理由
        // （`font-size` 相同但族不同时按字号单独缓存会让等宽段里的标点按正文字体量墨迹）。
        // 额外含 `cp`：本表一次可能量多个不同码本（同一段里 `。` 与 `，` 共存是常态）。
        val cache = HashMap<String, GlyphInkBox?>()
        for (i in from until to) {
            if (!isSqueezableClosingPunct(text, i)) continue
            val local = i - advBase
            if (local < 0 || local >= adv.size) continue
            val r = fontRuns.firstOrNull { it.start <= i && i < it.endExclusive }
            val size = r?.fontPxOr(fontSizePx) ?: fontSizePx
            val cp = text[i].code
            val cpNext = if (i + 1 < text.length && text[i + 1] != SOFT_HYPHEN) codePointAt(text, i + 1).cp else -1

            fun inkOf(which: Int, at: Int): GlyphInkBox? {
                if (which < 0) return null
                val key = buildString {
                    append(which).append('\u0001')
                    append(size.toRawBits()).append('\u0001')
                    append(r?.tag ?: tag).append('\u0001')
                    append(r?.families ?: families).append('\u0001')
                    append(r?.weight ?: weight).append('\u0001')
                    append(r?.italic ?: italic).append('\u0001')
                    append(r?.monospace ?: monospace)
                }
                return cache.getOrPut(key) {
                    measurer.glyphInkBoxPx(
                        which, fontSizePx, letterSpacingEm, r?.tag ?: tag, r?.families ?: families,
                        r?.weight ?: weight, r?.italic ?: italic, r?.monospace ?: monospace, fontRuns, at,
                    )
                }
            }

            val lsPx = letterSpacingEm * size
            val own = inkOf(cp, i)
            // 量不到自己的墨迹 ⇒ 不许压。安全的一侧，且实测为零命中（候选全是常见全角标点）。
            if (own == null) continue
            val nextInk = inkOf(cpNext, i + 1)
            val headroom = (adv[local] - lsPx - own.right) + (nextInk?.left ?: 0f)
            out[i - from] = minOf(maxEm * size, headroom).coerceAtLeast(0f)
        }
        return out
    }
}