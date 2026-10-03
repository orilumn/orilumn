package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign

/**
 * [graftKerningOnto] 的锁：**簇位轨只收紧不放宽 + JUSTIFY 右缘补偿**。
 *
 * ## 为什么这是硬锁（用户报缺陷①「有行溢出、末字只显示半个」的真因）
 *
 * 分页阅读器页宽固定，超出版心的部分被页面裁掉 ⇒ **溢出的字不是「不好看」，是内容丢失**。
 * 簇位轨是「整形出来的自然轨」，我方排版基线是「裸 cmap 量出的 advance 轨」。两者在
 * **收紧方向**等价（kerning/连字只让字更近），在**放宽方向不等价**（Skia 整形出的 advance
 * 偶尔更大）。若把放宽也采纳，就是「画得比排版时量出的宽」⇒ 结构上右溢。
 *
 * 实测（Rust 书 21.xhtml）：数字 `1` 整形 23.199 vs 量出 19.867，一行两处右溢 **6.116px**。
 *
 * 锁法：**直接构造 [LineAligner.Placement] 与合成簇位轨**，不依赖任何系统字体 ⇒ 跨机器稳定。
 * 「放宽也采纳」这个变异会把 `onlyTighten` 那把锁打红（见类 KDoc 的变异验证记录）。
 */
class GraftKerningOntoTest {

    private val tag = "p"
    private val fam = listOf("serif")
    private val fs = 40f
    private val lineW = 400f

    /** 造一个 JUSTIFY 行：字宽全 20，共 8 字，版心 400 ⇒ slack=240、gapCount=7、extra=34.28。 */
    private fun placement(text: String, align: TextAlign = TextAlign.JUSTIFY): LineAligner.Placement {
        val p = LineAligner().align(
            text, text.indices.first..text.indices.last, fs, lineW, 0f,
            tag, fam, 400, false, false, emptyList(),
            align, 0f, isLastLine = false, hyphenAtEnd = false,
        )
        return p
    }

    private fun track(p: LineAligner.Placement, gaps: FloatArray): FloatArray {
        val n = p.xs.size
        val cnat = FloatArray(n)
        var x = 0f
        for (i in 0 until n) {
            cnat[i] = x
            x += if (i < gaps.size) gaps[i] else p.advs[i]
        }
        return cnat
    }

    private fun graft(
        p: LineAligner.Placement,
        cnat: FloatArray,
        text: String,
        justifyRight: Float,
    ) = graftKerningOnto(p, cnat, text, 0, fs, 0f, emptyList(), justifyRight)

    private fun text8() = "abcdefgh"

    /**
     * **只收紧、不放宽**：簇位轨某处比我方 advance 轨**宽**时，绘制落位必须**一点不动**。
     *
     * 这是缺陷①的核心不变式：`xs(i) ≤ placement.xs(i)` 恒成立 ⇒ 绘制宽 ≤ 量出宽 ≤ 版心。
     */
    @Test
    fun `簇位轨放宽时落位一点不动`() {
        val text = text8()
        val p = placement(text)
        // 第 3 个间隙比量出的宽 +5（整形放宽），其余按量出的走。
        val gaps = FloatArray(7) { p.advs[it] }.also { it[3] = p.advs[3] + 5f }
        val g = graft(p, track(p, gaps), text, Float.NaN)
        for (i in p.xs.indices) {
            assertEquals(
                "i=$i 放宽必须被丢弃", p.xs[i], g.xs[i], 1e-3f,
            )
        }
        assertEquals("endDelta 也应是 0（无收紧）", 0f, g.endDelta, 1e-3f)
    }

    /**
     * **收紧必须采纳**：簇位轨比我方 advance 轨**窄**的地方，字要真的挪过去。
     *
     * 反向锁：若有人把 `tighten < 0` 写成 `abs(tighten)` 或无条件采纳，这把锁会红。
     */
    @Test
    fun `簇位轨收紧时逐字采纳`() {
        val text = text8()
        val p = placement(text)
        val gaps = FloatArray(7) { p.advs[it] }.also {
            it[0] = p.advs[0] - 3f // 第 1 字收紧 3px
            it[2] = p.advs[2] - 2f // 第 3 字再收紧 2px ⇒ 累计 -5
        }
        val g = graft(p, track(p, gaps), text, Float.NaN)
        assertEquals("首字不挪", p.xs[0], g.xs[0], 1e-3f)
        assertEquals("第 2 字起挪 -3", p.xs[1] - 3f, g.xs[1], 1e-3f)
        assertEquals("第 4 字起挪 -5（两处累加）", p.xs[3] - 5f, g.xs[3], 1e-3f)
        assertEquals("末字也挪 -5", p.xs[7] - 5f, g.xs[7], 1e-3f)
        assertEquals("endDelta = 末槽累计收紧量", -5f, g.endDelta, 1e-3f)
    }

    /**
     * **收紧 + 放宽混合**：放宽被丢、收紧被收，末槽净偏移 == 收紧总量（不含放宽）。
     *
     * 这一把同时钉住「两件事同时发生时不互相污染」。
     */
    @Test
    fun `放宽与收紧混合时净偏移只算收紧`() {
        val text = text8()
        val p = placement(text)
        val gaps = FloatArray(7) { p.advs[it] }.also {
            it[0] = p.advs[0] + 4f // 放宽 4 ⇒ 丢
            it[1] = p.advs[1] - 6f // 收紧 6 ⇒ 收
            it[5] = p.advs[5] + 3f // 放宽 3 ⇒ 丢
        }
        val g = graft(p, track(p, gaps), text, Float.NaN)
        assertEquals("i=1 只受放宽影响 ⇒ 不动", p.xs[1], g.xs[1], 1e-3f)
        assertEquals("i=2 起挪 -6", p.xs[2] - 6f, g.xs[2], 1e-3f)
        assertEquals("i=6 仍挪 -6（后一处放宽被丢）", p.xs[6] - 6f, g.xs[6], 1e-3f)
        assertEquals("endDelta = -6", -6f, g.endDelta, 1e-3f)
    }

    /**
     * **结构不变式（真书全量口径）**：任意合成轨下 `xs(i) ≤ placement.xs(i)` 且**单调递增**。
     *
     * 单调性也要锁：`delta` 只减不增，故 `xs` 不会倒退（字序错乱）。
     */
    @Test
    fun `绘制轨恒不宽于量出轨且单调`() {
        val text = text8()
        val p = placement(text)
        val gaps = FloatArray(7) { p.advs[it] }.also {
            it[0] = p.advs[0] + 9f
            it[1] = p.advs[1] - 4f
            it[2] = p.advs[2] + 7f
            it[3] = p.advs[3] - 1f
            it[4] = p.advs[4] + 2f
            it[5] = p.advs[5] - 8f
            it[6] = p.advs[6] + 5f
        }
        val g = graft(p, track(p, gaps), text, Float.NaN)
        for (i in p.xs.indices) {
            assertTrue("i=$i 绘制宽于量出宽 ${g.xs[i]} > ${p.xs[i]}", g.xs[i] <= p.xs[i] + 1e-3f)
            if (i > 0) {
                assertTrue(
                    "i=$i 落位倒退 ${g.xs[i]} < ${g.xs[i - 1]}",
                    g.xs[i] >= g.xs[i - 1] - 1e-3f,
                )
            }
        }
    }

    /**
     * **JUSTIFY 补偿**：簇位轨收紧后末字右缘短了一截，必须**按间隙均摊补回到版心**。
     *
     * 改前真书实测：簇位 JUSTIFY 行 4876 行里 **965 行右缘缺口 > 1px**、最大 **17.29px**。
     * 这把锁钉住「补回」这件事（缺口 > 1px 时右缘必须仍 == 版心）。
     */
    @Test
    fun `JUSTIFY 补偿后末字右缘仍贴版心`() {
        val text = text8()
        val p = placement(text)
        val gaps = FloatArray(7) { p.advs[it] }.also {
            it[1] = p.advs[1] - 6f
            it[4] = p.advs[4] - 6f
        }
        val g = graft(p, track(p, gaps), text, lineW)
        val lv = 7
        val wLast = p.advs[lv] // ls=0 ⇒ 墨宽 == advance
        assertEquals("末字墨框右缘必须 == 版心", lineW, g.xs[lv] + wLast, 0.05f)
        // 补偿必须**按间隙线性均摊**，不能整截堆在末字前面（否则前 6 个字挤成一团）。
        //
        // 下标口径：`gaps[k]` 是**槽 k 与槽 k+1 之间**的间隙，故它影响的是 `i = k + 1`。
        // 收紧累计：i∈1 → 0；i∈2..4 → −6；i∈5..7 → −12。补偿 `per × i`，i=0 处为 0。
        val expected = floatArrayOf(0f, 0f, -6f, -6f, -6f, -12f, -12f, -12f)
        val per = 12f / 7f // 补偿量 = 收紧总量 12
        for (i in 0..7) {
            val want = expected[i] + per * i
            assertEquals("i=$i 偏移应为 累计收紧${expected[i]} + per×$i", want, g.xs[i] - p.xs[i], 0.05f)
        }
    }

    /**
     * **非 JUSTIFY / 末行不补偿**：传 `NaN` 即「不补」。
     *
     * 反向锁：若有人去掉 `justifyRightEdge` 判据、或改成对所有对齐都补，这把锁会红 ——
     * 末行补了就是「把末行拉宽到版心」，违反 CSS「末行不拉伸」。
     */
    @Test
    fun `末行与非 JUSTIFY 不补偿`() {
        val text = text8()
        val gapsOf = { p: LineAligner.Placement ->
            FloatArray(7) { p.advs[it] }.also { it[2] = p.advs[2] - 7f }
        }
        val pJustify = placement(text)
        val gNaN = graft(pJustify, track(pJustify, gapsOf(pJustify)), text, Float.NaN)
        assertEquals(
            "NaN = 不补，末槽原样跟随收紧", pJustify.xs[7] - 7f, gNaN.xs[7], 1e-3f,
        )

        // LEFT 对齐行即使传了版心也不该被 JUSTIFY 逻辑改动 —— 由调用侧判据保证，
        // 这里锁「传 NaN 时 LEFT 行落位 == 只收紧的结果」。
        val pLeft = placement(text, TextAlign.LEFT)
        val gLeft = graft(pLeft, track(pLeft, gapsOf(pLeft)), text, Float.NaN)
        assertEquals(pLeft.xs[7] - 7f, gLeft.xs[7], 1e-3f)
    }

    /**
     * **行尾有空白时补偿锚在末可见字**，不是末槽。
     *
     * HTML 源里行末常带空白；补偿若锚在末槽，空白会把缺口吃掉、末字反而右缘不齐。
     */
    @Test
    fun `行末空白时补偿锚在末可见字`() {
        val text = "abcdef  " // 6 可见字 + 2 尾随空白
        val p = placement(text)
        val gaps = FloatArray(7) { p.advs[it] }.also { it[1] = p.advs[1] - 6f }
        val g = graft(p, track(p, gaps), text, lineW)
        // Aligner 把**尾随空白排在版心之外**（实测 `xs[6] = 400`、右缘 410；`visibleRight = 400`
        // 取的是**末可见字** `xs[5] + advs[5]`）。⇒ 补偿锚点必须是末可见字 5：
        // 拿末槽 7（右缘 420）当目标会**多补 20px**，把空白推出版心；拿 `lv-1` 判空白会漏补。
        assertEquals("末可见字墨框右缘 == 版心", lineW, g.xs[5] + p.advs[5], 0.05f)
        assertTrue(
            "末可见字不得右溢（相对版心 $lineW，实得 ${g.xs[5] + p.advs[5]}）",
            g.xs[5] + p.advs[5] <= lineW + 0.05f,
        )
    }

    /**
     * **单字行不补偿**：`lv == 0` 时无间隙可均摊，直接跳过（否则除零）。
     */
    @Test
    fun `单字行不补偿`() {
        val text = "a"
        val p = placement(text, TextAlign.LEFT)
        val cnat = floatArrayOf(0f)
        val g = graft(p, cnat, text, lineW)
        assertEquals("单字行落位不动", p.xs[0], g.xs[0], 1e-3f)
    }

    /**
     * **带连字符的行不补偿**（`hyphenAtEnd = true`）。
     *
     * 连字符占版心，末可见字的目标右缘是 `trailStartX − hyphenWidth` 而非版心；
     * 两种形态（`&shy;` 槽位 / K-L 音节断词）目标不同而 [LineAligner.Placement] 不暴露是哪种
     * ⇒ 一律不补。补了会把末字推进连字符槽位（重叠）或把连字符推出版心（右溢）。
     */
    @Test
    fun `带连字符的行不补偿`() {
        val text = "abcdefgh"
        val p = LineAligner().align(
            text, text.indices.first..text.indices.last, fs, lineW, 0f,
            tag, fam, 400, false, false, emptyList(),
            TextAlign.JUSTIFY, 0f, isLastLine = false, hyphenAtEnd = true,
        )
        assertTrue("前提：本例确实有连字符", p.hyphenWidth > 0f)
        val gaps = FloatArray(7) { p.advs[it] }.also { it[1] = p.advs[1] - 6f }
        val g = graft(p, track(p, gaps), text, lineW)
        assertEquals("连字符行不补偿：末槽原样跟随收紧", p.xs[7] - 6f, g.xs[7], 1e-3f)
    }

    /**
     * **letterSpacing 行**：`lsPx` 挂在 advance 末尾且**不可见**，补偿的目标右缘要比 advance 少一个 `lsPx`。
     *
     * 这是 [LineAligner] 里 `lastLs` 那条「末字 `lsPx` 不可见 ⇒ 不计」规则的对称要求；
     * 补偿若按完整 advance 比，右缘会多出一个 `lsPx`（实测 fs=42.18、ls=0.05 ⇒ 2.109px）。
     */
    @Test
    fun `带 letterSpacing 时补偿扣掉末字 lsPx`() {
        val text = text8()
        val lsEm = 0.05f
        val p = LineAligner().align(
            text, text.indices.first..text.indices.last, fs, lineW, lsEm,
            tag, fam, 400, false, false, emptyList(),
            TextAlign.JUSTIFY, 0f, isLastLine = false, hyphenAtEnd = false,
        )
        val gaps = FloatArray(7) { p.advs[it] }.also { it[1] = p.advs[1] - 6f }
        val cnat = track(p, gaps)
        val g = graftKerningOnto(p, cnat, text, 0, fs, lsEm, emptyList(), lineW)
        val lsPx = lsEm * fs
        assertEquals("末字墨框右缘（advance 减 lsPx）== 版心", lineW, g.xs[7] + p.advs[7] - lsPx, 0.05f)
    }

    /**
     * **换面行**：末字属 `<code>` 段时 `lsPx` 按**该段字号**算，不是行字号。
     *
     * [lastRunSizePx] 就是为这件事从 [LineAligner] 里搬出来共用的（见其 KDoc）。
     */
    @Test
    fun `换面行按末字所在段字号算 lsPx`() {
        val text = "abcdefgh"
        val codeFs = fs * 0.95f
        val runs = listOf(FontRun(4, 8, listOf("monospace"), "code", 400, false, true, codeFs))
        val lsEm = 0.05f
        val p = LineAligner().align(
            text, text.indices.first..text.indices.last, fs, lineW, lsEm,
            tag, fam, 400, false, false, runs,
            TextAlign.JUSTIFY, 0f, isLastLine = false, hyphenAtEnd = false,
        )
        val gaps = FloatArray(7) { p.advs[it] }.also { it[1] = p.advs[1] - 6f }
        val cnat = track(p, gaps)
        val g = graftKerningOnto(p, cnat, text, 0, fs, lsEm, runs, lineW)
        assertEquals(
            "末字墨框右缘（按 code 段字号扣 lsPx）== 版心",
            lineW, g.xs[7] + p.advs[7] - lsEm * codeFs, 0.05f,
        )
    }

    /**
     * **补偿回填也走四级优先级**（2026-10-03，与 [JustifySlack] 主拉伸同源）。
     *
     * 语料 `"ab cd ef gh"`（11 字、可回填槽 0..9）：级 0 = 槽 2/5/8（三个词间空格）、
     * 级 1 = 槽 1/4/7（`字母|␣`）、级 3 = 槽 0/3/6/9（词内字母缝）。
     *
     * 簇位轨只在**字符 0** 上收紧 6px ⇒ `deficit = 6`；级 0 容量 `3 × 0.5em × 40 = 120` 够
     * ⇒ **级 0 独吞 `6/3 = 2`**，其余级各 0。
     *
     * 旧版 `per = deficit / lv = 6/10` 会给 10 个槽**各** 0.6 —— 4 个词内字母缝照样被撑开，
     * 观感与「均摊」没区别。这把锁是把回填改回均摊就会红的**唯一**守卫
     * （既有的 `JUSTIFY 补偿后末字右缘仍贴版心` 用的是 `"abcdefgh"`，7 个槽全是级 3，
     *  级 3 无上限 ⇒ 与均摊**同值**，故它抓不到这条退化 —— 这是分工，不是重复）。
     *
     * 下标口径：`out[i] += Σ_{k<i} per[k]`，故槽 `k` 的补偿体现在 `g.xs[k+1] − g.xs[k]` 上。
     * 这里用 LEFT 对齐的 placement 造 `p`（`out` 就是自然轨、无自身拉伸），只留字符 0 的收紧，
     * 于是「补偿增量」= `g.xs[k+1] − g.xs[k] − (p.xs[k+1] − p.xs[k])`，与收紧量正交、可直接读。
     */
    @Test
    fun `补偿回填也走四级优先级而不是均摊`() {
        val text = "ab cd ef gh"
        val p = placement(text, TextAlign.LEFT)
        // 目标右缘 = LEFT 的自然右缘（收紧 6px 之前）⇒ deficit = 6。
        val target = p.xs[p.xs.size - 1] + p.advs[p.xs.size - 1]
        val cnat = track(p, FloatArray(10) { p.advs[it] }.also { it[0] = p.advs[0] - 6f })
        val g = graftKerningOnto(p, cnat, text, 0, fs, 0f, emptyList(), target)
        // 整形轨把**字符 0** 的 advance 收窄 6px ⇒ 只有槽 0 带上这 −6（`tighten(i) = (cnat[i]−cnat[i−1]) − advs[i−1]`
        // 读的是**前一个字符**的 advance）。其余槽的整形 advance 与量出逐值相同 ⇒ 收紧量 0。
        fun tightenInto(k: Int) = if (k == 0) -6f else 0f
        fun backfillAt(k: Int) =
            (g.xs[k + 1] - g.xs[k]) - (p.xs[k + 1] - p.xs[k]) - tightenInto(k)
        for (k in listOf(2, 5, 8)) {
            assertEquals("词间槽 $k 独占补偿（6/3）", 2f, backfillAt(k), 1e-2f)
        }
        for (k in listOf(0, 3, 6, 9)) {
            assertEquals("词内槽 $k 一级没吃满就不该被撑", 0f, backfillAt(k), 1e-3f)
        }
        for (k in listOf(1, 4, 7)) {
            assertEquals("`字母|␣` 槽 $k 排在级 0 之后，一级没吃满就不该被撑", 0f, backfillAt(k), 1e-3f)
        }
        assertEquals(
            "回填把收紧的 6px 全补回目标右缘", target, g.xs[p.xs.size - 1] + p.advs[p.xs.size - 1], 0.05f,
        )
    }

    /**
     * 变异验证记录（改坏必须红，见 `docs/自建断行引擎-测试计划.md` 教训㉛）：
     * - 把回填改回 `per = deficit / lv` ⇒ `补偿回填也走四级优先级而不是均摊` **红**
     *   （**已实跑验证，2026-10-03**）。其余锁仍全绿 —— 既有的
     *   `JUSTIFY 补偿后末字右缘仍贴版心` 用的是全词内语料，新旧规则同值，抓不到这条退化。
     * - 把 `tighten < 0f` 改成无条件采纳 ⇒ `簇位轨放宽时落位一点不动` / `结构不变式` **红**；
     * - 删掉 JUSTIFY 补偿段（`if (!justifyRightEdge.isNaN() …)`）⇒ `JUSTIFY 补偿后末字右缘仍贴版心`
     *   / `行末空白时补偿锚在末可见字` / 两把 letterSpacing 锁 **红**；
     * - 把补偿锚点从末可见字改成末槽 ⇒ `行末空白时补偿锚在末可见字` **红**；
     * - 去掉 `hyphenWidth <= 0f` 判据 ⇒ `带连字符的行不补偿` **红**；
     * - `lastRunSizePx` 里去掉 run 查回（直接返回 `fontSizePx`）⇒ `换面行按末字所在段字号算 lsPx` **红**。
     */
}
