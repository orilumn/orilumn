package orilumn.reader.engine.skia

import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.isSoftHyphen
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

/**
 * **JUSTIFY `slack` 的四级优先级分配**（渲染层·几何测量）。
 *
 * ## 它要修的缺陷（实测，不是设想）
 *
 * 原实现是**均摊**：`extra = slack / gapCount`，所有可见字缝同权。后果是**钱花错了地方**：
 * 一行 `some English words here` 的槽位里，**词内字母缝 15–19 个、词间空格只有 3–4 个**，
 * 均摊 ⇒ 80% 以上的拉伸落进**单词内部**，把 `some` 拉成 `s o m e`，
 * 而**该宽的词间空格只拿到零头**。字被拆散、词反而挤在一起 —— 这正是「两端对齐把字拉散」的观感来源。
 *
 * ## 四级顺序与额度（**产品裁决 2026-10-03**）
 *
 * | 级 | 槽位性质 | 每槽上限 | 为什么是这个顺序 / 这个上限 |
 * |---|---|---|---|
 * | **0** | **西文词间空格**（左字符是文档空白） | **0.5em** | 排版里「两端对齐靠撑词距」是公认的第一手段；0.5em 使空格（约 0.25em 宽）加宽到三倍，仍读作「词距」 |
 * | **1** | **CJK 字间** | **0.25em** | 汉字方形、侧边距小，加 0.25em 已接近「插了个空档」；再大就从「字间」变成「字距」（实施方案 S4 原定的全局 cap 就是这个值） |
 * | **2** | **中英注入间隙** | **0.5em** | 这个间隙是**设计出来的**（中西字距滑块给的就是 0~0.5em 量级），它的自然宽本就是滑块值；翻倍仍在「中英之间该有距离」的认知内 |
 * | **3** | **西文词内字母缝** | **无上限** | 兜底级。剩余全部归它 —— 不给它上限正是「撑词距不够时宁可字松也不让行短」的意思 |
 *
 * **顺序与上限是两个正交的轴**：顺序答「先动谁」，上限答「这一类最多能动多少」。
 * 级别高的未必上限大（级 0/2 都是 0.5em，级 1 只有 0.25em）—— 因为级 0 的槽位**少**
 * （一行 3–4 个 vs 十几个），先吃它既有效又便宜。
 *
 * ## 「吃满一级再进下一级」= 逐级瀑布，**级内均分**
 *
 * ```
 * remain = slack
 * for lvl in 0..3:
 *     n = counts[lvl];  if (n == 0) continue
 *     cap = CAP_EM[lvl] × size                       // 级 3 是 +∞
 *     take = min(remain, n × cap)
 *     per[lvl] = take / n                            // 级内均分
 *     remain -= take
 *     if (remain <= 0) break
 * ```
 *
 * ### 为什么 `slack > 0` 且槽位分类全为负时才可能「铺不满」
 *
 * 只要**行里至少有一个槽**，每一级都会把 `remain` 取到 0（末级无上限）⇒ 铺满。
 * 唯一铺不满的情形是**一级到三级全部封顶、而行里一个级 3 槽都没有** ——
 * 即「整行纯汉字且 slack 大到每缝都超过 0.25em」。此时**宁可右缘短一点也不把每个汉字都撑开**：
 * 实施方案 S4 当年写的「cap = 0.25em，超出即放弃该行」被本轮实测推翻
 * （2.9% 的槽位超过 0.25em），现在改成「剩余交给级 3 兜底、实在没有级 3 槽才留缺口」。
 *
 * ## 量画同源：分类与额度**只有这一份实现**
 *
 * [LineAligner]（主拉伸）与 [graftKerningOnto]（kerning 补偿回填）**两处都在补 slack**，
 * 两处若各写一份分类 / 瀑布，回填就会「撑词距」而主拉伸「撑词内」——
 * 两笔账叠在一起等于优先级失效（教训㩼 的同类：同一规则两处实现）。
 * ⇒ 两处都调 [plan]。
 */
internal object JustifySlack {

    /** 西文词间空格（左字符是文档空白）。 */
    const val LV_WORD_SPACE: Int = 0

    /** CJK 字间（含汉字与标点、西文与全角标点相邻等「非西文」缝）。 */
    const val LV_CJK: Int = 1

    /** 中英注入间隙（[CjkLatinSpacing] 在无空格处插出来的那个缝）。 */
    const val LV_CJK_LATIN: Int = 2

    /** 西文词内字母缝（兜底，无上限）。 */
    const val LV_WORD_INNER: Int = 3

    /** 级数（数组长度）。 */
    const val LEVELS: Int = 4

    /**
     * 各级每槽上限（em）。级 3 = `+∞` ⇒ `n × cap = +∞` ⇒ `take = remain` ⇒ 兜底全吃，
     * 代码里**不需要**为它开特例分支。
     */
    val CAP_EM: FloatArray = floatArrayOf(0.5f, 0.25f, 0.5f, Float.POSITIVE_INFINITY)

    /**
     * 槽 `absIndex`（**段级绝对下标**，即字符 `absIndex` 与 `absIndex + 1` 之间）的级别。
     *
     * @param injected 该槽是否是一个**中英注入间隙**（左槽绝对下标 ∈ [gaps] 的 `leftIndex`）。
     * @param gapsForRange 结果的 `leftIndex` 是**行内局部**下标（本方法的 `absIndex` 是绝对的），
     *   故调用点要么传绝对下标集合，要么先用 `start` 平移 —— [plan] 已经这么做了。
     */
    fun levelOf(text: CharSequence, absIndex: Int, injected: Boolean = false): Int {
        if (absIndex < 0 || absIndex + 1 >= text.length) return LV_CJK
        val c = text[absIndex]
        // ① 空格字形：**加宽它所在的字位 == 加宽词距**。
        //    空格无墨，「加在空格左边还是右边」在观感上等价（两者都只是把两词之间的距离
        //    拉长 `extra`），但**必须只算一个槽** —— 两个槽都算等于额度翻倍。
        //    取「空格之后那个槽」是因为 `xs[k+1] = xs[k] + adv[k] + extra` 在该槽上恰好等价于
        //    「空格字位变宽 `extra`」，账目最干净。
        if (isDocumentSpace(c)) return LV_WORD_SPACE
        // ② 注入间隙：由滑块显式设计出来的缝，性质上「中英之间该有的距离」，可加宽。
        if (injected) return LV_CJK_LATIN
        // ③ 西文词内：两侧**都是**西文字母/数字。
        //    ⚠ 边界是**有意的**，两处：
        //    (a) 连字符两侧算 [LV_CJK]：`abc-ated` 的 `c|-`、`-|a` 里 `-` 不是字母数字。
        //        这不划算错 —— 级 1 的上限 0.25em 比级 3 的无上限温和，少量连字符缝走级 1
        //        观感无差别，而把 `-` 认成「西文字符」会让 `中文-中文` 这种真 CJK 缝被误判成词内。
        //    (b) `字母 | 空格`（如 `ab␣cd` 的 `b|␣` 槽）也落 [LV_CJK]，**不是** [LV_WORD_SPACE] ——
        //        因为 ① 已经把「那个词间距」**整份**记在了空格之后那个槽上。
        //        同一个空格若在**两侧各记一次**，级 1 的额度就翻倍，「每词距上限 0.5em」这句话
        //        就不再成立。少记一次的代价：这一侧只吃级 1 的 0.25em，而两侧合计仍是 0.75em，
        //        **总量与「两侧都记」完全相同**（空格无墨，哪一侧开缝在观感上不可区分）。
        //    数字（`0-9`）算西文：`2026` 的字缝是词内缝，不是 CJK 缝。
        val nxt = text[absIndex + 1]
        if (CjkLatinSpacing.isWestern(c.code) && CjkLatinSpacing.isWestern(nxt.code)) return LV_WORD_INNER
        // ④ 其余（CJK|CJK、CJK|西文、西文|全角标点、代理对…）一律 CJK 字间。
        return LV_CJK
    }

    /**
     * 一行的分配结果：`levels[k]` = 槽 `k`（字符 `k` 与 `k+1` 之间）的级，
     * `per[lvl]` = 该级每槽加的量。
     */
    class Plan(
        /** 与 `xs` 同长；`-1` = **不是可拉伸槽**（越界的上界、或零宽不可见的 SHY 槽）。 */
        val levels: IntArray,
        val per: FloatArray,
        /** 实际落到 `xs` 上的总量 = `Σ per[lvl] × counts[lvl]`（`visibleRight` 必须用它，见调用点）。 */
        val placed: Float,
    )

    /**
     * 给定一段可拉伸槽（`[0, slotLimit)`，其中 `k` 指字符 `start + k` 与 `start + k + 1` 之间）
     * 分配 `slack`。
     *
     * @param gapsForRange 与 `adv` 同坐标系（**行内局部**）的中英注入间隙；空 = 关。
     * @param slotLimit 上界（[LineAligner] 的 `xsExtraLimit`）：只到「末可见字形之后」为止。
     */
    fun plan(
        text: CharSequence,
        start: Int,
        slotLimit: Int,
        gapsForRange: List<CjkLatinGap>,
        fontSizePx: Float,
        slack: Float,
    ): Plan {
        val n = slotLimit.coerceAtLeast(0)
        val levels = IntArray(n) { -1 }
        val counts = IntArray(LEVELS)
        // [CjkLatinGap.leftIndex] 按升序（见 `CjkLatinSpacing.gaps`），故用游标而不是建集合。
        var g = 0
        for (k in 0 until n) {
            // 零宽不可见的 SHY 槽：**不是可拉伸槽**（原实现在 `xs` 循环里用
            // `!isSoftHyphen(...)` 挡掉；分类收进 [Plan.levels] 后那处判据只剩这一次）。
            if (isSoftHyphen(text[start + k])) continue
            while (g < gapsForRange.size && gapsForRange[g].leftIndex < k) g++
            val injected = g < gapsForRange.size && gapsForRange[g].leftIndex == k
            val lv = levelOf(text, start + k, injected)
            levels[k] = lv
            counts[lv]++
        }
        val per = FloatArray(LEVELS)
        var remain = slack
        for (lvl in 0 until LEVELS) {
            val cnt = counts[lvl]
            if (cnt <= 0) continue
            val cap = CAP_EM[lvl] * fontSizePx
            val take = if (remain < cnt * cap) remain else cnt * cap
            per[lvl] = take / cnt
            remain -= take
            if (remain <= 0f) break
        }
        // ⚠ 用**与逐槽求和完全相同的乘加顺序**算 `placed`：末级无上限时
        //   `per[3] × counts[3]` 与 `remain_before − remain_after` 差一个 ulp，
        //   而 `visibleRight` 是硬边界（`NoLineExceedsContentWidthTest` 钉它），不差这一丁点。
        var placed = 0f
        for (lvl in 0 until LEVELS) if (counts[lvl] > 0) placed += per[lvl] * counts[lvl]
        return Plan(levels, per, placed)
    }
}
