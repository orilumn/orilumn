package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.text.preprocess.CjkLatinGap

/**
 * **JUSTIFY `slack` 四级优先级分配**的锁（渲染层·行内几何，[JustifySlack]）。
 *
 * ## 它锁的是什么缺陷（真机观感，不是设想）
 *
 * 旧实现 `extra = slack / gapCount` **均摊**：一行 `ab cd ef gh` 的 10 个槽里
 * **词内字母缝 4 个、词间空格只有 3 个**，均摊 ⇒ 单词被拉散（`a b`）、词距反而没变宽。
 * 给每一类缝一个**每槽上限**（词间空格 0.5em / CJK 字间 0.25em / 中英注入间隙 0.5em /
 * 词内字母缝无上限兜底），并让它们**按各自额度的同一比例出资**，才是
 * 「两端对齐靠撑词距、撑不动才动字缝」这条排版常识的实现。
 *
 * ## 【2026-10-03】为什么从「逐级瀑布」改成「按比例均摊」
 *
 * 瀑布把 `cap` 变成了**开关阈值**而不是**权重**：slack 只够级 0 的 1/3 时，
 * 瀑布给「级 0 满、其余 0」，同一个字缝要么 0 要么 0.5em，比例法给「按 `r` 缩放」。
 * 真书实测（25 篇）：瀑布版词内缝被压到 0.008em、词间空格被撑到 0.5em，
 * **同一条行里两种缝差 60 倍** —— 那不是优先，那是把字拆散。详见 [JustifySlack] 类 KDoc。
 *
 * ## 为什么分成「纯判据锁」与「量画同源锁」两族
 *
 * - **纯判据锁**：[JustifySlack.levelOf] / [JustifySlack.plan] 是纯函数，**零字体依赖**、
 *   跨机器稳定，用来把**分类口径**与**各级额度**逐值钉死（`0.5 / 0.25 / 0.5 / ∞` em）。
 * - **量画同源锁**：走 [LineAligner]（**生产绘制器**），从 `xs` **反解**每槽实际增量。
 *   判据只认「`xs` 里到底加了多少」，**不看** [JustifySlack.Plan.per] 自己的说法 ——
 *   后者与 `xs` 分家正是这类分配逻辑最典型的失效方式。
 *
 * ## 额度是产品口径，写在这里免得被当魔数改掉
 *
 * 级 0/2 = `0.5em`、级 1 = `0.25em`、级 3 无上限。改任何一个都要同步改
 * `docs/自建断行引擎-实施方案.md` S4 的 cap 条与 `docs/TODO-未尽事宜.md` Q22。
 *
 * ## 语料的槽位账（改动判据时先对这张账）
 *
 * `"ab cd ef gh"`（11 字、10 槽）：级 0 = 槽 2/5/8，级 1 = 槽 1/4/7，级 3 = 槽 0/3/6/9。
 * `"中文字ab"`（6 字、5 槽）：级 1 = 槽 0/1/2，级 3 = 槽 3。
 * `"中文abc中"`（6 字、5 槽）开滑块 0.25em **与** 0 档**分级相同**：
 * 级 1 = 槽 0，级 2 = 槽 1/4，级 3 = 槽 2/3 —— 0 档只是间隙的自然宽为 0，
 * 接缝的性质与级别不变（见 `0 档下间隙宽度为零但仍记级二`）。
 * 只有**不传 gaps**（`cjkGaps` 漏传）时才是级 1 = 槽 0/1/4、级 3 = 槽 2/3。
 */
class JustifySlackPriorityTest {

    private val fam = listOf("STSong", "serif")
    private val fs = 40f
    private val l0 = JustifySlack.LV_WORD_SPACE
    private val l1 = JustifySlack.LV_CJK
    private val l2 = JustifySlack.LV_CJK_LATIN
    private val l3 = JustifySlack.LV_WORD_INNER

    // ================================================================
    // 一、纯判据锁（无字体依赖）
    // ================================================================

    /** 四级分类的逐条口径。任一条改坏 ⇒ 这里红，且能一眼看出是哪一级判错了。 */
    @Test
    fun `槽位分类的判据逐条钉死`() {
        fun lv(text: String, i: Int, injected: Boolean = false) =
            JustifySlack.levelOf(text, i, injected)

        // ① 西文词间空格：左字符是文档空白。
        assertEquals("空格之后的槽 = 词间空格", l0, lv("ab cd", 2))
        assertEquals("连续空格：每个空格各记一份", l0, lv("ab  cd", 3))

        // ② 中英注入间隙：由滑块插出来的缝，优先级低于词间空格。
        assertEquals("注入间隙认成级 2", l2, lv("中a", 0, injected = true))

        // ③ 西文词内字母缝：两侧都是西文字母/数字。
        assertEquals("ab 的 a|b", l3, lv("abcd", 0))
        assertEquals("2026 的 2|0（数字算西文）", l3, lv("2026", 0))

        // ④ 其余一律 CJK 字间。
        assertEquals("中|文", l1, lv("中文", 0))
        assertEquals("混排未开滑块时 中|a 也只是 CJK 字间", l1, lv("中a", 0))
        assertEquals("西文|全角标点", l1, lv("ab。", 1))
    }

    /**
     * 两处**有意**的非对称边界（改它们要连带改 [JustifySlack.levelOf] 的 KDoc，反过来也一样）。
     *
     * 记在案的原因：这两条都是「看起来该对称、实际不能对称」的选择，
     * 下一个读代码的人会以为写错了。
     */
    @Test
    fun `连字符两侧与空格左侧都不算词间`() {
        // (a) 连字符不是西文字母数字 ⇒ 两侧都落到 CJK 级（避免 `中文-中文` 被误判成词内）。
        assertEquals("c|-", l1, JustifySlack.levelOf("abc-ated", 2))
        assertEquals("-|a", l1, JustifySlack.levelOf("abc-ated", 3))
        // (b) 空格只有「它之后那个槽」记级 0，左侧不记 ⇒ 额度不翻倍。
        assertEquals("b|␣ 不记级 0（额度只记一次）", l1, JustifySlack.levelOf("ab cd", 1))
        assertEquals("␣|c 记级 0", l0, JustifySlack.levelOf("ab cd", 2))
    }

    /** 越界下标不得抛异常（末槽 / 负下标都退回 CJK 级，只是用不到而已）。 */
    @Test
    fun `越界槽位退回默认级不抛`() {
        val t = "ab"
        assertEquals(l1, JustifySlack.levelOf(t, -1, false))
        assertEquals(l1, JustifySlack.levelOf(t, t.length, false))
        assertEquals(l1, JustifySlack.levelOf(t, 99, false))
    }

    /**
     * **SHY 槽不是可拉伸槽**（`-1`）。
     *
     * SHY 零宽不可见，给它分一份拉伸就是在行里撑出一个看不见的洞。
     * 这条判据以前写在 [LineAligner] 的 `xs` 循环里，本轮收进 [JustifySlack.Plan.levels]
     * —— 收进来是因为回填路径也要用同一份（见 [GraftKerningOntoTest] 的对应锁）。
     */
    @Test
    fun `SHY 槽标为不可拉伸但其余槽照常分级`() {
        val shy = '­'
        val plan = JustifySlack.plan("ab${shy}c", 0, 3, emptyList(), fs, 100f)
        assertEquals("SHY 槽 = −1", -1, plan.levels[2])
        assertEquals("a|b 仍是词内", l3, plan.levels[0])
        assertEquals("b|␣ 是 CJK 级", l1, plan.levels[1])
        // 账：级 1 有 1 槽（满额 0.25em×40 = 10）⇒ `C_finite = 10`，`slack = 100 > 10` ⇒ `r = 1`
        // ⇒ 级 1 吃满 10，差额 90 全给唯一的级 3 槽。
        // SHY 槽**不占级 3 的份额** —— 若它被算成可拉伸槽，这里会变成 45。
        assertEquals("级 1 吃满 0.25em", 0.25f * fs, plan.per[l1], 1e-3f)
        assertEquals("级 3 独吞差额的 90（SHY 槽不占份额）", 90f, plan.per[l3], 1e-3f)
        assertEquals("全额落位", 100f, plan.placed, 1e-3f)
    }

    /**
     * **`slack` 大过满额容量**：有限级**全开**（各拿满 `CAP_EM`），级 3 接差额。
     *
     * `"ab cd ef gh"` 的槽位账：级 0 三槽、级 1 三槽、级 3 四槽。
     * 有限级满额容量 `C_finite = 3×0.5em + 3×0.25em = 90`，`slack = 120 > 90`
     * ⇒ `r = 1` ⇒ 级 0 = 20/槽、级 1 = 10/槽、差额 30 由级 3 四槽平分。
     *
     * 这一把钉住 `0.5 / 0.25 / ∞` 三个数字**以及**「差额归兜底」这条规则。
     */
    @Test
    fun `slack 大过满额容量时有限级全开且级三接差额`() {
        val plan = JustifySlack.plan("ab cd ef gh", 0, 10, emptyList(), fs, 120f)
        assertEquals("级 0 每槽封顶 0.5em", 0.5f * fs, plan.per[l0], 1e-3f)
        assertEquals("级 1 每槽封顶 0.25em", 0.25f * fs, plan.per[l1], 1e-3f)
        assertEquals("级 3 分到差额的 30/4", 7.5f, plan.per[l3], 1e-3f)
        assertEquals("slack 被全额落位", 120f, plan.placed, 1e-3f)
    }

    /**
     * **`slack` 只够一部分时，各级按同一个比例缩放，兜底级一分不拿**（2026-10-03 新口径核心）。
     *
     * `slack = 10`、`C_finite = 90` ⇒ `r = 1/9` ⇒ 级 0 = 20/9、级 1 = 10/9、
     * 差额 `10 − 10 = 0` ⇒ 级 3 = 0。
     *
     * **与旧口径的差别就是这把锁的全部意义**：旧版在这里是「级 0 独吞 10/3、其余全 0」，
     * 即 `cap` 变成了「开关阈值」；新版让 `cap` 回到它唯一的含义 ——「这一类最多能动多少」。
     * 异类槽之比恒等于 `cap` 之比（20 : 10 = `0.5em` : `0.25em`）这条不变量也是新的。
     */
    @Test
    fun `slack 只够一部分时各级按同一比例缩放且兜底级不拿`() {
        val plan = JustifySlack.plan("ab cd ef gh", 0, 10, emptyList(), fs, 10f)
        assertEquals("级 0 = 0.5em × r（r = 10/90）", 0.5f * fs / 9f, plan.per[l0], 1e-3f)
        assertEquals("级 1 = 0.25em × 同一个 r", 0.25f * fs / 9f, plan.per[l1], 1e-3f)
        assertEquals("级 2 本行无槽", 0f, plan.per[l2], 0f)
        assertEquals("级 3 不得分到（差额恰为 0）", 0f, plan.per[l3], 0f)
        assertEquals("有限级正好把 slack 用完", 10f, plan.placed, 1e-3f)
    }

    /**
     * **同一个 `r` 缩放所有有限级**（把上一把的比例从「算出来」变成「可推导」）。
     *
     * `slack = 45` ⇒ `r = 45/90 = 0.5` ⇒ 级 0 = 10、级 1 = 5。
     * 这把专门钉「**级 1 的 `per` 必须等于级 0 的一半**」——
     * 若有人把实现改成「级 0 先按 `cap` 出资、剩下的再按比例分给其余级」，
     * 上面的锁在 `slack=10` 时仍可能全绿（那时级 0 也拿不满），只有这一把会红。
     */
    @Test
    fun `所有有限级共用同一个比例且比值恒等于 cap 之比`() {
        val plan = JustifySlack.plan("ab cd ef gh", 0, 10, emptyList(), fs, 45f)
        assertEquals("级 0 = 0.5em × 0.5", 10f, plan.per[l0], 1e-3f)
        assertEquals("级 1 = 0.25em × 0.5（= 级 0 的一半）", 5f, plan.per[l1], 1e-3f)
        assertEquals("差额 45 − 45 = 0，级 3 不拿", 0f, plan.per[l3], 0f)
        assertEquals("全额落位", 45f, plan.placed, 1e-3f)
    }

    /**
     * **级 2 与级 0/1 共用同一个 `r`**（注入间隙不享受特殊待遇）。
     *
     * `"中a"` 开滑块只有 1 个级 2 槽（满额 20）⇒ `C_finite = 20`，`slack = 30` ⇒ `r = 1`
     * ⇒ 级 2 = 20、差额 10 **无处可去**（无级 3 槽）⇒ 留缺口。
     */
    @Test
    fun `只有一个有限级时比例退化为吃满且余量留缺口`() {
        val on = JustifySlack.plan("中a", 0, 1, listOf(CjkLatinGap(leftIndex = 0, gapEm = 0.25f)), fs, 30f)
        assertEquals("级 2 吃满 0.5em", 0.5f * fs, on.per[l2], 1e-3f)
        assertEquals("落位 = 20（余 10 留缺口）", 20f, on.placed, 1e-3f)
    }

    /**
     * **纯西文长词行（只有级 3 槽）**：`C_finite = 0` ⇒ `r` 无意义，级 3 独吞 `slack`。
     *
     * 这是比例公式唯一的除零入口，必须有一把锁钉它（否则 `slack / 0 = +∞` 会把
     * 有限级打成 `+∞` —— 本实现用 `capFinite > 0f` 挡住了）。
     */
    @Test
    fun `没有有限级时兜底级独吞全部 slack`() {
        val plan = JustifySlack.plan("abcdef", 0, 5, emptyList(), fs, 77f)
        for (k in 0 until 5) assertEquals("槽 $k 都是词内级", l3, plan.levels[k])
        assertEquals("级 3 独吞 77/5", 77f / 5f, plan.per[l3], 1e-3f)
        assertEquals("全额落位", 77f, plan.placed, 1e-3f)
    }

    /** 级 2（中英注入间隙）确实被认出来，而不是被当成普通 CJK 级。 */
    @Test
    fun `注入间隙被认成级二`() {
        val gaps = listOf(CjkLatinGap(leftIndex = 0, gapEm = 0.25f))
        val on = JustifySlack.plan("中a", 0, 1, gaps, fs, 30f)
        assertEquals("槽 0 认成注入间隙", l2, on.levels[0])
        // 级 2 上限 0.5em×40 = 20 ⇒ 30 里有 10 放不下；本例**没有兜底级槽**，
        // 剩下的 10 就是留缺口（与 `纯汉字行封顶后留缺口而不是撑字缝` 同一条规则）。
        assertEquals("级 2 封顶 0.5em", 0.5f * fs, on.per[l2], 1e-3f)
        assertEquals("落位 = 封顶值（余 10 留缺口）", 20f, on.placed, 1e-3f)
        // **不传 gaps** 时同一槽必须退回级 1 —— 这是「漏传 `cjkGaps`」这类退化的守卫。
        // ⚠ 注意口径：`cjkEm = 0`（0 档）**照样会传 gaps**，所以 0 档**不是**这一格；
        //   0 档下该槽仍是级 2（见 [JustifySlack.levelOf] 的注释与端到端那把锁）。
        val off = JustifySlack.plan("中a", 0, 1, emptyList(), fs, 30f)
        assertEquals("无 gaps ⇒ 槽 0 是普通 CJK 级", l1, off.levels[0])
        assertEquals("级 2 一分不拿", 0f, off.per[l2], 0f)
        assertEquals("级 1 只吃自己的 0.25em", 0.25f * fs, off.per[l1], 1e-3f)
    }

    // ================================================================
    // 二、量画同源锁（真字体，从 xs 反解）
    // ================================================================

    private fun alignOne(
        text: String,
        width: Float,
        align: TextAlign,
        cjkEm: Float = 0f,
        isLastLine: Boolean = false,
    ) = LineAligner().align(
        text, text.indices.first..text.indices.last, fs, width, 0f,
        "p", fam, 400, false, false, emptyList(),
        align, 0f, isLastLine, hyphenAtEnd = false,
        cjkLatinSpacingEm = cjkEm,
        // 标点挤压是**另一条**改版面的机制；本类只锁 slack 分配，一律关掉以免两件事互相掩盖。
        punctuationSqueezeMaxEm = 0f,
    )

    /** 自然宽（LEFT + 超宽版心 ⇒ `slack` 必为负 ⇒ 一点不拉，`visibleRight` 即自然宽）。 */
    private fun natural(text: String, cjkEm: Float = 0f): Float =
        alignOne(text, 1e7f, TextAlign.LEFT, cjkEm).visibleRight

    /** 每槽实际增量 = `xs[k+1] − xs[k] − advs[k]`（**只认 xs，不认 `plan.per`**）。 */
    private fun increments(p: LineAligner.Placement): FloatArray {
        val n = p.xs.size
        return FloatArray(n - 1) { p.xs[it + 1] - p.xs[it] - p.advs[it] }
    }

    /**
     * **核心锁：词间槽拿得最多、词内槽一个字节都不许拿**（真字体复核）。
     *
     * `slack = 20`，`C_finite = 90` ⇒ `r = 2/9` ⇒ 词间槽（cap 0.5em）各拿 `40/9 ≈ 4.444`，
     * `字母|␣` 槽（cap 0.25em）各拿 `20/9 ≈ 2.222`，差额 0 ⇒ 词内槽 0。
     *
     * **异类槽之比恒等于 `cap` 之比**（`4.444 / 2.222 = 2 = 0.5/0.25`）——
     * 这条不变量在旧瀑布口径下**不成立**（那时它们是 6.667 与 0）。
     * 旧均摊会给 10 个槽各 `2.0`（含 4 个词内槽）⇒ 这把锁红。
     */
    @Test
    fun `词间槽拿得最多而词内槽一分不拿`() {
        val text = "ab cd ef gh"
        val nat = natural(text)
        val p = alignOne(text, nat + 20f, TextAlign.JUSTIFY)
        val inc = increments(p)
        for (k in listOf(2, 5, 8)) {
            assertEquals("词间槽 $k = 0.5em × (20/90)", 40f / 9f, inc[k], 1e-2f)
        }
        for (k in listOf(1, 4, 7)) {
            assertEquals("`字母|␣` 槽 $k = 0.25em × (20/90)", 20f / 9f, inc[k], 1e-2f)
        }
        for (k in listOf(0, 3, 6, 9)) {
            assertEquals("词内槽 $k 不得被撑", 0f, inc[k], 1e-3f)
        }
        assertEquals("右缘仍贴版心", nat + 20f, p.visibleRight, 0.05f)
    }

    /**
     * **端到端：三级同时拿到额度**（纯判据锁那组数的真字体复核）。
     *
     * `slack = 120 > C_finite = 90` ⇒ `r = 1` ⇒ 级 0 = 20/槽、级 1 = 10/槽、
     * 级 3 接差额 30/4 = 7.5/槽，右缘贴版心。
     */
    @Test
    fun `端到端三级同时拿到额度`() {
        val text = "ab cd ef gh"
        val nat = natural(text)
        val p = alignOne(text, nat + 120f, TextAlign.JUSTIFY)
        val inc = increments(p)
        for (k in listOf(2, 5, 8)) {
            assertEquals("级 0 槽 $k = 0.5em", 0.5f * fs, inc[k], 1e-2f)
        }
        for (k in listOf(1, 4, 7)) {
            assertEquals("级 1 槽 $k = 0.25em", 0.25f * fs, inc[k], 1e-2f)
        }
        for (k in listOf(0, 3, 6, 9)) {
            assertEquals("级 3 槽 $k = 余量/4", 7.5f, inc[k], 1e-2f)
        }
        assertEquals("全额铺满 ⇒ 右缘 == 版心", nat + 120f, p.visibleRight, 0.05f)
    }

    /**
     * **纯汉字行封顶后宁可留缺口，也不把每个字缝都撑开。**
     *
     * `"中文字"` 只有 2 个级 1 槽，容量 `2 × 0.25em × 40 = 20`；给 `slack = 200`
     * ⇒ 只能吃掉 20，右缘停在 `natural + 20`。
     *
     * 这是实施方案 S4 当年写的「cap = 0.25em，**超出即放弃该行**」的现代版：
     * 当年那写法会在这里（以及真书里 2.9% 的超限槽位）让右缘参差；
     * 现在改成「先走完四级，**确实没有兜底级**才留缺口」。
     */
    @Test
    fun `纯汉字行封顶后留缺口而不是撑字缝`() {
        val text = "中文字"
        val nat = natural(text)
        val p = alignOne(text, nat + 200f, TextAlign.JUSTIFY)
        val inc = increments(p)
        assertEquals("只有 2 个槽", 2, inc.size)
        for (k in inc.indices) {
            assertEquals("字缝 $k 不得超 0.25em", 0.25f * fs, inc[k], 1e-2f)
        }
        assertEquals("右缘停在封顶总量上", nat + 20f, p.visibleRight, 0.05f)
        assertTrue("确实没铺满（本例就是要留缺口）", p.visibleRight < nat + 200f)
    }

    /**
     * **有兜底级就一定铺满**：即使 `slack` 巨大，末级（词内字母缝）无上限 ⇒ 右缘仍 == 版心。
     *
     * 这是「cap 封顶 ≠ 放弃该行」的正向锁，与上一把（无兜底级 ⇒ 留缺口）成对。
     */
    @Test
    fun `有兜底级时巨大slack也铺满`() {
        // 槽 0/1 = CJK 级（0.25em）、**槽 2 = 中英注入间隙**（`字|a`，0 档下宽为 0 但**仍是级 2**，
        // 0.5em）、槽 3 = `a|b` 词内（无上限）。C_finite = 2×10 + 20 = 40。
        val text = "中文字ab"
        val nat = natural(text)
        val p = alignOne(text, nat + 400f, TextAlign.JUSTIFY)
        assertEquals("有级 3 ⇒ 全额铺满", nat + 400f, p.visibleRight, 0.05f)
        val inc = increments(p)
        assertEquals("CJK 级封顶 0.25em", 0.25f * fs, inc[0], 1e-2f)
        assertEquals("注入间隙级封顶 0.5em", 0.5f * fs, inc[2], 1e-2f)
        assertEquals("兜底级独吞差额 400 − 40", 360f, inc[3], 1e-2f)
    }

    /**
     * **中英注入间隙参与拉伸，且排在 CJK 字间之后**（产品裁决 2026-10-03 第 3 条）。
     *
     * `"中文abc中"` 开滑块 0.25em：级 1 = 槽 0，级 2 = 槽 1/4，级 3 = 槽 2/3。
     * `C_finite = 10 + 2×20 = 50`，`slack = 60 > 50` ⇒ `r = 1` ⇒ 级 1 = 10、级 2 = 20/槽
     * → 差额 10 由级 3 两槽平分。**三级同时非零**，所以「注入间隙没被认出来」这种退化一定会被抓到
     * （那时槽 1 会与槽 0 同级，拿到同一个 `r × 0.25em`，而 `0 档下间隙宽度为零但仍记级二`
     * 那把锁的值也就与本把完全相同，两把一起失去分辨力）。
     */
    @Test
    fun `中英注入间隙参与拉伸且排第三级`() {
        val text = "中文abc中"
        val nat = natural(text, cjkEm = 0.25f)
        val p = alignOne(text, nat + 60f, TextAlign.JUSTIFY, cjkEm = 0.25f)
        val inc = increments(p)
        assertEquals("级 1 槽 0（中|文）封顶 0.25em", 0.25f * fs, inc[0], 1e-2f)
        assertEquals("级 2 槽 1（文|a）封顶 0.5em", 0.5f * fs, inc[1], 1e-2f)
        assertEquals("级 2 槽 4（c|中）封顶 0.5em", 0.5f * fs, inc[4], 1e-2f)
        assertEquals("级 3 槽 2（a|b）分余量", 5f, inc[2], 1e-2f)
        assertEquals("级 3 槽 3（b|c）分余量", 5f, inc[3], 1e-2f)
        assertEquals("全额铺满", nat + 60f, p.visibleRight, 0.05f)
    }

    /**
     * **0 档下间隙宽度为 0，但它**仍然**记级 2**（产品口径 2026-10-03）。
     *
     * 旧名「关掉滑块时同一边界退回 CJK 级」已经**失效**：混排字距 0 档不再是「不处理」，
     * 边界照检、间隙照样存在（只是自然宽 0），所以 `文|a` / `c|中` 仍是级 2。
     * 若把 0 档当成「没有间隙」，0 档的拉伸行为就会与其它档分叉 —— 那是被删掉的特例换了个形式。
     *
     * 本例 `slack = 60`、`C_finite = 10 + 2×20 = 50` ⇒ `r = 1` ⇒ 级 1 = 10、级 2 = 20/槽、
     * 差额 10 由级 3 两槽平分。与上一把（0.25em 档）的差别**只有槽 1/4 的级别来源**，
     * 数值恰好相同 —— 这正是「0 档 = 参数为 0 的那一档」的含义。
     *
     * **反向守卫**（不传 `gjkGaps` ⇒ 退回级 1）在 [注入间隙被认成级二] 的纯判据段里；
     * 「漏传 `cjkGaps`」这一变异由本把与上一把**一起**抓（上一把用 0.25em 档，
     * 若 `cjkGaps` 恒空则它的槽 1/4 会变成 10 ⇒ 红）。
     */
    @Test
    fun `0 档下间隙宽度为零但仍记级二`() {
        val text = "中文abc中"
        val nat = natural(text, cjkEm = 0f)
        val p = alignOne(text, nat + 60f, TextAlign.JUSTIFY, cjkEm = 0f)
        val inc = increments(p)
        assertEquals("槽 0（中|文）是 CJK 级 0.25em", 10f, inc[0], 1e-2f)
        assertEquals("槽 1（文|a）0 档下仍是注入间隙级 0.5em", 20f, inc[1], 1e-2f)
        assertEquals("槽 4（c|中）同理", 20f, inc[4], 1e-2f)
        assertEquals("词内槽分差额 10/2", 5f, inc[2], 1e-2f)
        assertEquals("全额铺满", nat + 60f, p.visibleRight, 0.05f)
    }

    /** 末行 / LEFT 行一个字节都不许被撑（优先级再高也不行）。 */
    @Test
    fun `末行与左对齐不拉伸`() {
        val text = "ab cd ef gh"
        val nat = natural(text)
        val last = alignOne(text, nat + 500f, TextAlign.JUSTIFY, isLastLine = true)
        assertEquals("末行不拉伸", nat, last.visibleRight, 0.05f)
        val incLast = increments(last)
        for (k in incLast.indices) {
            // ⚠ 容差 1e-3 而不是 0：末行不拉伸时 `x` 仍逐槽累加 `adv`，浮点误差会留下
            //   ~4e-6 的残差（实测槽 4 得 −3.8146973E−6）。**判据是「没有可见的拉伸量」**，
            //   不是「浮点逐位为零」—— 后者会把这条锁变成对浮点末位的检测。
            assertEquals("末行槽 $k 增量必须为 0", 0f, incLast[k], 1e-3f)
        }
        val left = alignOne(text, nat + 500f, TextAlign.LEFT)
        assertEquals("LEFT 不拉伸", nat, left.visibleRight, 0.05f)
        val incLeft = increments(left)
        for (k in incLeft.indices) {
            assertEquals("LEFT 槽 $k 增量必须为 0", 0f, incLeft[k], 1e-3f)
        }
    }

    /**
     * 变异验证记录（改坏必须红，见 `docs/自建断行引擎-测试计划.md` 教训㩼）。
     *
     * ## 【2026-10-03 比例化改写后】重跑，全部**实跑**，括号内是实际变红的锁数与代表锁名
     *
     * 分配口径的四个变异（新口径的核心，必须能区分「瀑布 / 均摊 / 比例」三形状）：
     * - **换回逐级瀑布**（级 0 吃满再进下一级）⇒ **4 把红**：
     *   `slack 只够一部分时各级按同一比例缩放且兜底级不拿`、`所有有限级共用同一个比例且比值恒等于 cap 之比`、
     *   `词间槽拿得最多而词内槽一分不拿`、`补偿回填也走四级比例而不是均摊`。
     * - **换回均摊**（`per = slack / 总槽数` 广播给每级）⇒ **13 把红**（本类 11 把 + 回填那把 + 分类那把）。
     * - **「共用比例」改成「各级各算各的」**（`per[lvl] = min(cap[lvl], slack/自身容量 × cap[lvl])`，
     *   看似等价、`slack` 远大于容量时**同值**）⇒ **7 把红**，且带出两条真书锁
     *   （`NoLineExceedsContentWidthTest` 的两把 + `LineAlignerTest` 的「铺满版心」）
     *   —— 这条变异在旧瀑布口径下**抓不到**（瀑布本来就是逐级算的），是新增分辨力的直接证据。
     * - `FIRST_UNBOUNDED` 从 `3` 改成 `4`（把无上限的级 3 也拖进比例分配 ⇒ 除以 `∞`）
     *   ⇒ **29 把红**。
     *
     * 额度与分类的变异（沿用旧记录，逐条仍成立）：
     * - `CAP_EM` 级 0 / 级 1 对调（`0.5 ↔ 0.25`）⇒ **12 把红**。
     * - 删掉 `isSoftHyphen` 那个 `-1` 分支 ⇒ `SHY 槽标为不可拉伸但其余槽照常分级`（单把红）。
     * - `JustifySlack.plan` 忽略 `slotLimit`（把 `levels` 铺到 `text.length`）
     *   ⇒ 9 把红（末字之后的槽白吃预算，右缘随之变短）。
     * - [LineAligner.align] 漏传 `cjkGaps` 给 [JustifySlack.plan]（恒 `emptyList`）⇒ **3 把红**。
     * - [LineAligner.align] 在 `cjkLatinSpacingEm <= 0` 时给 `plan` 传空 gaps
     *   （**把 0 档又退回「没有间隙」特例**）⇒ **2 把红**：
     *   `0 档下间隙宽度为零但仍记级二`、`有兜底级时巨大slack也铺满`
     *   （后者也红是因为 `中文字ab` 的 `字|a` 在 0 档下正是级 2 —— 这把锁顺带守住了 0 档口径）。
     * - [LineAligner.align] 的 `finalContent` 用 `slack` 而不是 `plan.placed`
     *   ⇒ `纯汉字行封顶后留缺口而不是撑字缝`（**单把红**）。
     * - `graftKerningOnto` 的回填改回 `per = deficit / lv`（均摊）或旧瀑布 ⇒ 见
     *   [GraftKerningOntoTest] 的同名锁（**单把红**）。
     */
}
