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
 * 四级瀑布（词间空格 → CJK 字间 → 中英注入间隙 → 词内字母缝兜底）才是
 * 「两端对齐靠撑词距」这条排版常识的实现。
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
 * `"中文abc中"`（6 字、5 槽）**开滑块**：级 1 = 槽 0，级 2 = 槽 1/4，级 3 = 槽 2/3；
 * **关滑块**：级 1 = 槽 0/1/4，级 3 = 槽 2/3。
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
        // 账：级 1 有 1 槽（容量 0.25em×40 = 10）先吃 10，余 90 全给唯一的级 3 槽。
        // SHY 槽**不占级 3 的份额** —— 若它被算成可拉伸槽，这里会变成 45。
        assertEquals("级 1 先吃满 0.25em", 0.25f * fs, plan.per[l1], 1e-3f)
        assertEquals("级 3 独吞余下的 90（SHY 槽不占份额）", 90f, plan.per[l3], 1e-3f)
        assertEquals("全额落位", 100f, plan.placed, 1e-3f)
    }

    /**
     * **逐级瀑布**：吃满一级再进下一级，且各级额度就是 `CAP_EM × 字号`。
     *
     * `"ab cd ef gh"` 的槽位账：级 0 三槽、级 1 三槽、级 3 四槽。
     * `slack = 120` 故意大于前两级容量之和（`60 + 30 = 90`）⇒ **三级同时非零**，
     * 于是这一把同时钉住 `0.5 / 0.25 / ∞` 三个数字与「按这个顺序」的次序。
     */
    @Test
    fun `逐级瀑布吃满一级再进下一级`() {
        val plan = JustifySlack.plan("ab cd ef gh", 0, 10, emptyList(), fs, 120f)
        assertEquals("级 0 每槽封顶 0.5em", 0.5f * fs, plan.per[l0], 1e-3f)
        assertEquals("级 1 每槽封顶 0.25em", 0.25f * fs, plan.per[l1], 1e-3f)
        assertEquals("级 3 分到余下的 30/4", 7.5f, plan.per[l3], 1e-3f)
        assertEquals("slack 被全额落位", 120f, plan.placed, 1e-3f)
    }

    /** `slack` 小于级 0 容量时，**级 1/2/3 必须一分不拿**（这是「吃满再进下一级」的字面含义）。 */
    @Test
    fun `一级没吃满就不往下分`() {
        val plan = JustifySlack.plan("ab cd ef gh", 0, 10, emptyList(), fs, 10f)
        assertEquals("级 0 独吞 10/3", 10f / 3f, plan.per[l0], 1e-3f)
        assertEquals("级 1 不得分到", 0f, plan.per[l1], 0f)
        assertEquals("级 2 不得分到", 0f, plan.per[l2], 0f)
        assertEquals("级 3 不得分到", 0f, plan.per[l3], 0f)
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
        // 同一段文本，关掉滑块（不传 gaps）时必须退回级 1。
        val off = JustifySlack.plan("中a", 0, 1, emptyList(), fs, 30f)
        assertEquals("关滑块 ⇒ 槽 0 是普通 CJK 级", l1, off.levels[0])
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
     * **核心锁：词间槽拿到的增量远大于词内槽，词内槽一个字节都不许拿。**
     *
     * `slack = 20`，级 0 容量 `3 × 0.5em × 40 = 60` 够 ⇒ 级 0 独吞 `20/3`。
     * 旧均摊会给 10 个槽各 `2.0`（含 4 个词内槽）⇒ 这把锁红。
     */
    @Test
    fun `词间槽独吞词内槽一分不拿`() {
        val text = "ab cd ef gh"
        val nat = natural(text)
        val p = alignOne(text, nat + 20f, TextAlign.JUSTIFY)
        val inc = increments(p)
        for (k in listOf(2, 5, 8)) {
            assertEquals("词间槽 $k 应拿到 slack/3", 20f / 3f, inc[k], 1e-2f)
        }
        for (k in listOf(0, 3, 6, 9)) {
            assertEquals("词内槽 $k 一级没吃满就不该被撑", 0f, inc[k], 1e-3f)
        }
        assertEquals("右缘仍贴版心", nat + 20f, p.visibleRight, 0.05f)
    }

    /**
     * **端到端三级瀑布**（纯判据锁那组数的真字体复核）。
     *
     * `slack = 120` ⇒ 级 0 = 20/槽、级 1 = 10/槽、级 3 = 7.5/槽，右缘贴版心。
     */
    @Test
    fun `端到端三级瀑布`() {
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
        val text = "中文字ab" // 槽 0/1/2 = CJK 级（容量 30），槽 3 = a|b = 词内（兜底）
        val nat = natural(text)
        val p = alignOne(text, nat + 400f, TextAlign.JUSTIFY)
        assertEquals("有级 3 ⇒ 全额铺满", nat + 400f, p.visibleRight, 0.05f)
        val inc = increments(p)
        assertEquals("CJK 级封顶", 0.25f * fs, inc[0], 1e-2f)
        assertEquals("兜底级独吞余下的 370", 370f, inc[3], 1e-2f)
    }

    /**
     * **中英注入间隙参与拉伸，且排在 CJK 字间之后**（产品裁决 2026-10-03 第 3 条）。
     *
     * `"中文abc中"` 开滑块 0.25em：级 1 = 槽 0，级 2 = 槽 1/4，级 3 = 槽 2/3。
     * `slack = 60` ⇒ 级 1 吃 10（封顶）→ 级 2 吃 40（两槽 × 0.5em = 40，恰好吃满）
     * → 级 3 分 10。**三级同时非零**，所以「注入间隙没被认出来」这种退化一定会被抓到
     * （那时槽 1 会与槽 0 同级，拿到同一个数）。
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
     * **A/B 反向锁：关掉滑块，同一个 `文|a` 边界退回 CJK 级**（拿到 10 而不是 20）。
     *
     * 防「注入间隙恒真」的实现：若 [JustifySlack.plan] 漏传 `cjkGaps`（或调用点恒传非空），
     * 上一把会红但这里不会 —— 两把合起来才把「开则级 2、关则级 1」钉死。
     */
    @Test
    fun `关掉滑块时同一边界退回CJK级`() {
        val text = "中文abc中"
        val nat = natural(text, cjkEm = 0f)
        val p = alignOne(text, nat + 60f, TextAlign.JUSTIFY, cjkEm = 0f)
        val inc = increments(p)
        // 关滑块：级 1 = 槽 0/1/4（容量 30，各 10）→ 吃满 30，余 30 给级 3（2 槽各 15）。
        assertEquals("槽 0（中|文）", 10f, inc[0], 1e-2f)
        assertEquals("槽 1（文|a）现在只是普通 CJK 级", 10f, inc[1], 1e-2f)
        assertEquals("槽 4（c|中）同理", 10f, inc[4], 1e-2f)
        assertEquals("词内槽分余量", 15f, inc[2], 1e-2f)
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
     * **全部 10 项已实跑，括号内是实际变红的锁名（节选，其余同样红）**：
     * - 把 [JustifySlack.plan] 换回均摊（`per = slack / 总槽数` 广播给每级）
     *   ⇒ `词间槽独吞词内槽一分不拿`、`端到端三级瀑布`、`逐级瀑布吃满一级再进下一级`、
     *   `一级没吃满就不往下分`、`有兜底级时巨大slack也铺满`（10 把中的 9 把红）。
     * - 把 `CAP_EM` 四项全设 `+∞` ⇒ `纯汉字行封顶后留缺口而不是撑字缝`、
     *   `中英注入间隙参与拉伸且排第三级`。
     * - 把级 0 / 级 2 的额度对调（`0.5 ↔ 1.0`）
     *   ⇒ `端到端三级瀑布`、`逐级瀑布吃满一级再进下一级`、
     *   `中英注入间隙参与拉伸且排第三级`、`注入间隙被认成级二`。
     * - 删掉 `isSoftHyphen` 那个 `-1` 分支 ⇒ `SHY 槽标为不可拉伸但其余槽照常分级`（单把红）。
     * - 删掉 `isDocumentSpace(c)` 那一支 ⇒ `槽位分类的判据逐条钉死`、
     *   `连字符两侧与空格左侧都不算词间`、`词间槽独吞词内槽一分不拿`、`端到端三级瀑布`。
     * - 删掉 `levelOf` 的第 ③ 支（两侧都西文 ⇒ 词内）⇒ `槽位分类的判据逐条钉死`、
     *   `有兜底级时巨大slack也铺满`、`中英注入间隙参与拉伸且排第三级`。
     * - `JustifySlack.plan` 忽略 `slotLimit`（把 `levels` 铺到 `text.length`）
     *   ⇒ 9 把红（末字之后的槽白吃预算，右缘随之变短）。
     * - [LineAligner.align] 漏传 `cjkGaps` 给 `JustifySlack.plan`（恒 `emptyList`）
     *   ⇒ `中英注入间隙参与拉伸且排第三级`（**单把红**，正是它该负责的那件事）。
     * - [LineAligner.align] 的 `finalContent` 用 `slack` 而不是 `plan.placed`
     *   ⇒ `纯汉字行封顶后留缺口而不是撑字缝`（**单把红**）。
     * - `graftKerningOnto` 的回填改回 `per = deficit / lv` ⇒ 见
     *   [GraftKerningOntoTest] 的同名锁（**单把红**）。
     */
}