package orilumn.reader.engine.skia

import kotlin.math.max
import kotlin.math.min
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.isSoftHyphen
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

/**
 * **JUSTIFY `slack` 的四级额度分配**（渲染层·几何测量）。
 *
 * ## 它要修的缺陷（实测，不是设想）
 *
 * 原实现是**均摊**：`extra = slack / gapCount`，所有可见字缝同权。后果是**钱花错了地方**：
 * 一行 `some English words here` 的槽位里，**词内字母缝 15–19 个、词间空格只有 3–4 个**，
 * 均摊 ⇒ 80% 以上的拉伸落进**单词内部**，把 `some` 拉成 `s o m e`，
 * 而**该宽的词间空格只拿到零头**。字被拆散、词反而挤在一起 —— 这正是「两端对齐把字拉散」的观感来源。
 *
 * ## 四级的性质与上限（**产品裁决 2026-10-03**，上限 `CAP_EM` 不变）
 *
 * | 级 | 槽位性质 | 每槽上限 | 为什么是这个上限 |
 * |---|---|---|---|
 * | **0** | **西文词间空格**（左字符是文档空白） | **0.5em** | 排版里「两端对齐靠撑词距」是公认的第一手段；0.5em 使空格（约 0.25em 宽）加宽到三倍，仍读作「词距」 |
 * | **1** | **CJK 字间** | **0.25em** | 汉字方形、侧边距小，加 0.25em 已接近「插了个空档」；再大就从「字间」变成「字距」（实施方案 S4 原定的全局 cap 就是这个值） |
 * | **2** | **中英注入间隙** | **0.5em** | 这个间隙是**设计出来的**（中西字距滑块给的就是 0~0.5em 量级），它的自然宽本就是滑块值；翻倍仍在「中英之间该有距离」的认知内 |
 * | **3** | **西文词内字母缝** | **无上限** | 兜底级。剩余全部归它 —— 不给它上限正是「撑词距不够时宁可字松也不让行短」的意思 |
 *
 * **上限是「这一类最多能动多少」，不是「先动谁」**（2026-10-03 改，见下）。
 *
 * ## 【2026-10-03 产品裁决】「大家都按比例均匀挤压」——**不再是逐级瀑布**
 *
 * 原实现是**逐级瀑布**：级 0 吃满 `0.5em` 再看级 1，级 1 吃满 `0.25em` 再看级 2……
 * 后果是「优先级」变成了**开关**而不是**权重**：
 *
 * ```
 * remain = slack
 * for lvl in 0..3:                       // 旧：吃满一级再进下一级
 *     take = min(remain, counts[lvl] × cap[lvl])
 *     per[lvl] = take / counts[lvl]      // 级内均分
 *     remain -= take
 * ```
 *
 * 那一版实测（25 篇真书）：词内缝从 682281px 掉到 54215px（−92%），
 * 而**词间空格从 119817px 涨到 268324px（+124%）** —— 一个字缝被压到 0.008em，
 * 另一个被撑到 0.5em。同一条行里两种缝差 60 倍，那不是「优先」，那是**把字拆散**。
 *
 * ### 新口径：**按各自额度同一比例均摊，总额只取需要的量**
 *
 * ```
 * C_finite = Σ_{lvl∈{0,1,2}} counts[lvl] × cap[lvl]     // 三个有限级**按满额**的总容量
 * r        = min(1, slack / C_finite)                   // 全局比例
 * per[lvl] = r × cap[lvl]                               // 有限级：各拿自己那一份的 r
 * per[3]   = (slack − Σ_{lvl∈{0,1,2}} per[lvl]×counts[lvl]) / counts[3]   // 余量兜底
 * ```
 *
 * **一句话**：所有有限级**同时**按同一个比例 `r` 出资，`r ≤ 1`；
 * 只有当 `slack` 大过它们的满额容量时，差额才落到无上限的级 3。
 *
 * ### 为什么这个形状比瀑布好（三条，不是「看起来更公平」）
 *
 *  1. **优先级变成了权重，不是开关**。`slack` 只够级 0 的 1/3 时，
 *     瀑布给「级 0 满、其余 0」，比例法给「级 0 拿 1/3 份、级 1/2 各拿 1/3 份、级 3 拿 0」。
 *     后者保住了 `cap` 的**唯一含义**（每类最多能动多少），前者让 `cap` 变成了「开关阈值」。
 *  2. **`r` 一个数决定全行**，所以同一行里同类槽必然等量、异类槽之比恒等于 `cap` 之比
 *     —— 这条**不变量**可被逐格验证，而瀑布没有这种不变量（它依赖 `counts` 的具体数值）。
 *  3. **级 3 仍然是兜底**：`slack > C_finite` 时它独吞差额，行**照样铺满**
 *     （`有兜底级时巨大slack也铺满` 那把锁守着）。`slack ≤ C_finite` 时它拿 0，
 *     于是「词内字母缝永远不会被无端撑开」这个观感底线也保住了。
 *
 * ### 什么时候会「铺不满」（留缺口）
 *
 * `slack > C_finite` 且行里**一个级 3 槽都没有** —— 即「整行纯汉字且 slack 大到每缝都
 * 超过 0.25em」。此时**宁可右缘短一点也不把每个汉字都撑开**（实施方案 S4 当年写的
 * 「cap = 0.25em，超出即放弃该行」被 2026-10-03 的实测推翻：2.9% 的槽位超过 0.25em；
 * 现在只在**确实没有兜底级**时才放弃）。
 *
 * ## 量画同源：分类与额度**只有这一份实现**
 *
 * [LineAligner]（主拉伸）与 [graftKerningOnto]（kerning 补偿回填）**两处都在补 slack**，
 * 两处若各写一份分类 / 分配，回填就会「撑词距」而主拉伸「撑词内」——
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
     * **最后一个有限级之后**的级号（= [LV_WORD_INNER]）。
     *
     * [plan] 的比例分配要遍历「所有有限级」= `[0, it)`。写成具名常量而不是散落的 `3`，
     * 是为了让「级 3 无上限、其余有限」这条规则**只有一个出处**。
     */
    const val FIRST_UNBOUNDED: Int = LV_WORD_INNER

    /**
     * 各级每槽上限（em）。级 3 = `+∞` ⇒ **不参与比例分配**（[plan] 里它是兜底桶，
     * 按差额拿，不按 `cap` 拿）。
     *
     * ⚠ 这四项是**产品口径**，不是可调参数。改任何一个都要同步改
     *   `docs/自建断行引擎-实施方案.md` S4 的 cap 条与 `docs/TODO-未尽事宜.md` Q22/Q25。
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
        //
        // ⚠ **`gapEm = 0` 也照样是级 2**（产品口径 2026-10-03：0 档是「参数为 0 的那一档」，
        //   不是「没有间隙」那一档）。边界照检、间隙照样存在，只是它的**自然宽**是 0。
        //   混排字距滑块给的是「中英之间该有多宽」，**给 0 不等于取消这个接缝的性质** ——
        //   取消性质会让 0 档的拉伸行为与其它档分叉，那正是本轮删掉的特例（另一种形式）。
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
     * ## 口径（2026-10-03：**按比例均摊**，不是逐级瀑布）
     *
     * ```
     * C_finite = Σ_{lvl ∈ [0, FIRST_UNBOUNDED)} counts[lvl] × CAP_EM[lvl] × size
     * r        = min(1, max(0, slack / C_finite))        // C_finite == 0 时 r 无意义
     * per[lvl] = r × CAP_EM[lvl] × size                   // 有限级
     * per[3]   = max(0, slack − Σ_{有限级} per×counts) / counts[3]   // 兜底（counts[3] == 0 ⇒ 0）
     * ```
     *
     * ## 三条边界（都不是「随手加的 if」，每条都对应一个可观测形状）
     *
     *  - **`slack ≤ 0`**：`r` 夹到 0，级 3 的差额也夹到 0 ⇒ `placed = 0`。
     *    （正常路径根本不会走到：`LineAligner` 只在 `slack > 0` 时才调本函数；
     *    但 [graftKerningOnto] 的回填会传 `deficit`，那里可能是 0。）
     *  - **`C_finite == 0`**（整行只有级 3 槽，如纯西文长词）：有限级一个都没有 ⇒
     *    `r` 无意义，级 3 独吞 `slack`。
     *  - **`counts[3] == 0`**（纯汉字行）：差额**无处可去** ⇒ `placed < slack` ⇒
     *    **留缺口**（见类 KDoc「什么时候会铺不满」）。这是刻意的：
     *    把每个汉字都撑开比行短一点更难读。
     *
     * @param gapsForRange 与 `adv` 同坐标系（**行内局部**）的中英注入间隙；空 = 该行没有中西接缝。
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
        // ---- 有限级：同一个比例 `r`，各拿自己满额容量的 `r` 份 ----
        var capFinite = 0f
        for (lvl in 0 until FIRST_UNBOUNDED) capFinite += counts[lvl] * CAP_EM[lvl] * fontSizePx
        val r = if (capFinite > 0f) min(1f, max(0f, slack / capFinite)) else 0f
        var placedFinite = 0f
        for (lvl in 0 until FIRST_UNBOUNDED) {
            if (counts[lvl] <= 0) continue
            val cap = CAP_EM[lvl] * fontSizePx
            per[lvl] = r * cap
            placedFinite += per[lvl] * counts[lvl]
        }
        // ---- 级 3：无上限，只接「有限级按比例出完之后还差的那部分」 ----
        val n3 = counts[LV_WORD_INNER]
        if (n3 > 0) per[LV_WORD_INNER] = max(0f, slack - placedFinite) / n3
        // ⚠ 用**与逐槽求和完全相同的乘加顺序**算 `placed`：级 3 独吞差额时
        //   `per[3] × counts[3]` 与 `slack − placedFinite` 差一个 ulp，
        //   而 `visibleRight` 是硬边界（`NoLineExceedsContentWidthTest` 钉它），不差这一丁点。
        var placed = 0f
        for (lvl in 0 until LEVELS) if (counts[lvl] > 0) placed += per[lvl] * counts[lvl]
        return Plan(levels, per, placed)
    }
}
