package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BreakOpportunitySet
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.CodeIdentifierBreakSource
import orilumn.reader.engine.laying.EnglishHyphenationSource
import orilumn.reader.engine.laying.HYPHEN_GLYPH
import orilumn.reader.engine.laying.KinsokuBreakSource
import orilumn.reader.engine.laying.SOFT_HYPHEN
import orilumn.reader.engine.laying.SoftHyphenBreakSource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **软连字符 + 音节断词连字符**（`&shy;` / `hyphens: auto`）的落墨锁。
 *
 * ## 这锁守的是哪条用户可见的行为
 *
 * 一个行尾的 `-`。它必须**同时**满足三件事，缺一件就是可见缺陷：
 *
 * 1. **不换行时看不见** —— `&shy;` 是零宽占位符（CSS Text 3 §5.2），不占宽也不绘制。
 * 2. **换行时显形** —— 行恰断在软连字符/音节边界上，行尾补一个 `-`。
 * 3. **不撑破版心** —— 连字符是**真实占版心的墨**，断行侧必须**预留**它。
 *
 * ## 改造前的四个缺陷（本类的存在理由，全部由改造前的探针量出）
 *
 * | 缺陷 | 实测 |
 * |---|---|
 * | SHY 占 1em 全宽 | `adv[U+00AD] = 42.18` @fs=42.18 |
 * | SHY 被画出来 | `faceForCp(0x00AD)` = STSong glyph **271**（有真字形）|
 * | SHY 后不能断 | `hy­phen­ation` @W=120 → `hy­p` / `hen­` / `ation`（**R1 硬切在字母中间**）|
 * | 音节断词不补连字符 | `hyphenation` @W=120 → `hy` / `phen` / `ation`，行尾无 `-` |
 *
 * 修完后同两例分别得到 `hy­` / `phen­` / `ation` 与 `hy` / `phen` / `ation`（带连字符）。
 *
 * ## 逐层拆锁（每层只验一件事，便于定位是哪层坏了）
 *
 * ① 取宽层 ② 断点层 ③ 断行层 ④ 对齐层 ⑤ 绘制层（像素）。
 *
 * ## 变异验证记录（本类每一把锁都做过「改坏它，看锁红不红」）
 *
 * | 变异 | 变坏了什么 | 结果 |
 * |---|---|---|
 * | `lastOppWidth = hang` | 断行侧不预留连字符宽 | **2 把红** ✅ |
 * | `hyphenAtEnd = opp.isHyphenAt(brk)` | R1 core 也挂连字符 ⇒ 溢出 | **2 把红** ✅ |
 * | `gapCount` 还原成第一版公式 | SHY 情形少算一个间隙 ⇒ 假洞 | **1 把红** ✅ |
 * | `SHY data` 在 [KinsokuBreakSource] 之前无条件 `markHyphen` | 破禁则 | （`SHY does not break past kinsoku starters` ✅）|
 * | `natural -= lastLs` 改回无条件 | 槽位复用 + `ls>0` ⇒ 连字符内缩一个 `lsPx` | **1 把红** ✅ |
 * | `xsExtraLimit` 改成「与 `gapCount` 同式」 | 把一份 `extra` 加在最后一个字的 x **之后** | **全绿** ⚠ 见下 |
 *
 * 最后一行是**已知覆盖不到**的一处：那一份 `extra` 加在 `xs[n-1]` 记完之后、`x` 随即被丢弃，
 * 两版可观测几何逐位相同（实测 `hyphenation` @300 `gap` 同为 6.4780）。
 * 结论已写在 `LineAligner` 的 `xsExtraLimit` 处 —— 记下来是为了**下次别再把它"修"回去**，
 * 也说明「变异没红」≠「那行代码多余」，可能是**真的不可观测**（判据见教训㉜）。
 */
class SoftHyphenTest {

    private val fam = listOf("STSong", "serif")
    private val fs = 42.18f
    private val m = SkiaRunMeasurer()
    private val al = LineAligner()

    private fun broken(text: String, w: Int, align: TextAlign = TextAlign.LEFT): List<BrokenLine> =
        InhouseParagraphBreaker(0f).breakLines(text, fs, 1.6f, w, align, "p", fam, 400, false, false)

    /** 禁则「不可置于行首」的字符（与 [KinsokuRules.ZH_EN.noBreakBefore] 同一张表）。 */
    private fun isKinsokuStarter(c: Char): Boolean =
        orilumn.reader.engine.laying.KinsokuRules.ZH_EN.noBreakBefore.indexOf(c) >= 0

    // ---- ① 取宽层：SHY 严格 0 宽，且**不含** letterSpacing ----

    @Test
    fun `SHY is strictly zero-width with and without letterSpacing`() {
        for (ls in listOf(0f, 0.05f)) {
            val adv = m.advances("a${SOFT_HYPHEN}b", fs, ls, "p", fam, 400, false, false)
            assertEquals("SHY 必须 0 宽（letterSpacing=$ls）", 0f, adv[1], 0f)
            // 连 letterSpacing 也不给：SHY 是「不占位」的占位符，给 ls 会多出一个看不见的洞。
            val a = m.advances("a", fs, ls, "p", fam, 400, false, false)[0]
            val b = m.advances("b", fs, ls, "p", fam, 400, false, false)[0]
            assertEquals("SHY 不得计入整段宽（letterSpacing=$ls）", a + b, adv[0] + adv[2], 1e-3f)
        }
        // 对照：`-` 是**真**有宽的（连字符槽位画的就是它）。这个数差着量，量错就撑破版心。
        val dash = m.advances(HYPHEN_GLYPH.toString(), fs, 0f, "p", fam, 400, false, false)[0]
        assertTrue("连字符应有可见宽（实测 $dash）", dash > 1f)
    }

    // ---- ② 断点层：SHY 之后可断、且标记为「需补连字符」；别处不得误标 ----

    @Test
    fun `break after SHY only and those are the hyphen bearing points`() {
        val text = "hy${SOFT_HYPHEN}phen"
        val opp = BreakOpportunitySet.of(
            text, listOf(KinsokuBreakSource, SoftHyphenBreakSource, EnglishHyphenationSource.forLang("en")),
        )
        assertEquals("只有 SHY 之后（=3）该是断点", listOf(3), (1 until text.length).filter { opp.opportunityAt(it) })
        assertEquals("该断点必须标记需补连字符", listOf(3), (1 until text.length).filter { opp.isHyphenAt(it) })
        // 段末不是断点（否则整段装下时也会凭空多一个连字符）。
        assertFalse(opp.opportunityAt(text.length))
        assertFalse(opp.isHyphenAt(text.length))
    }

    @Test
    fun `SHY does not break past kinsoku starters`() {
        // `a&shy;。` —— 若 SHY 无条件标断点，句号会被丢到行首（破禁则）。
        // 这是实测抓到的真 bug：`markHyphen` 只置位不清位，一维 `hyphens[i]`
        // 能凭空造出 `KinsokuBreakSource` 坚决否决的断点。
        val text = "a${SOFT_HYPHEN}。"
        val opp = BreakOpportunitySet.of(text, listOf(KinsokuBreakSource, SoftHyphenBreakSource))
        assertFalse("SHY 不得越过禁则把句号丢到行首", opp.opportunityAt(2))
        assertFalse("禁则否决处也不得挂 hyphens 标记", opp.isHyphenAt(2))
        // 对照：`b&shy;。` 的句号之后（位置 3，**段末之前**）可断 —— 说明 2 处被否决的是
        // 禁则而不是「位置 3 不可断」（段末位置 3 恒不可断，不能拿它当对照）。
        val ok = BreakOpportunitySet.of("b${SOFT_HYPHEN}。c", listOf(KinsokuBreakSource, SoftHyphenBreakSource))
        assertTrue("对照：句号之后（=3）应可断", ok.opportunityAt(3))
        assertFalse("对照：句号之前（=2）禁断", ok.opportunityAt(2))
        // 端到端：断点集合里没有「SHY 后」这个位置 ⇒ 断行器不许拿它断行。
        // 语料加宽到**存在别的合法断点**（空格），否则窄版心会走 R1 core 兜底
        // （退不到任何断点即 `brk = i`），那条路径按设计会把 `。` 推到行首 —— 与禁则无关。
        val wide = "ab${SOFT_HYPHEN}。 cd"
        // **只用「合法替代断点够得着」的版心档**（实测 `ab­。` 需 82.21px 才能装下，
        // 装下后空格处（位置 4/5）才是可达断点）。更窄的档位上「SHY 后」是**唯一**
        // 够得着的位置之外没有任何断点，断行器按设计走 **R1 core 兜底**（`brk = i`）
        // —— 那条路径与禁则无关（它根本不是「选了」那个断点，而是没得选），
        // 把它算进这把锁就是在测错的东西。
        for (w in listOf(90, 100, 110, 120, 140, 160, 180, 200, 240)) {
            for (bl in broken(wide, w)) {
                assertTrue(
                    "版心 $w 断出 ${bl.range}「${wide.substring(bl.range)}」—— " +
                        "行首是禁则字符（句号/闭括号等），SHY 不得越过禁则制造这个断点",
                    !isKinsokuStarter(wide[bl.range.first]),
                )
            }
        }
    }

    @Test
    fun `identifier break points are not hyphen-bearing`() {
        // `maxWidth` 这类代码断点**不补连字符** —— 浏览器对代码也是 `overflow-wrap` 硬切。
        val text = "maxWidth"
        val opp = BreakOpportunitySet.of(text, listOf(CodeIdentifierBreakSource))
        val points = (1 until text.length).filter { opp.opportunityAt(it) }
        assertTrue("驼峰交界应是断点（实测 $points）", points.isNotEmpty())
        assertEquals("代码断点一个都不该补连字符", emptyList<Int>(), points.filter { opp.isHyphenAt(it) })
    }

    // ---- ③ 断行层：断点正确 + 连字符宽已预留 ----

    @Test
    fun `SHY text breaks at soft hyphens and not mid-letter`() {
        val text = "hy${SOFT_HYPHEN}phen${SOFT_HYPHEN}ation"
        val lines = broken(text, 120)
        val glyphs = lines.map { text.substring(it.range).filter { c -> c != SOFT_HYPHEN } }
        assertEquals(
            "应断成 hy / phen / ation（改造前是 R1 硬切：hy p / hen / ation）",
            listOf("hy", "phen", "ation"), glyphs,
        )
        assertTrue("前两行断在 SHY 上，应标记补连字符", lines[0].hyphenAtEnd && lines[1].hyphenAtEnd)
        assertFalse("末行不是断词行", lines.last().hyphenAtEnd)
        // 断在 SHY 上 ⇒ SHY 那个字位落在**本行内**（`&shy;` 的槽位就是它自己）。
        assertTrue("首行区间必须含 SHY", text.substring(lines[0].range).contains(SOFT_HYPHEN))
    }

    @Test
    fun `hyphen width is reserved in the breaking decision`() {
        // 这是「连字符不撑破版心」的**根因锁**：断行器必须把连字符宽算进判定宽。
        // 去掉预留（焊进 adv 或干脆不预留）都会让断点行的判定宽少一个连字符宽。
        val text = "hyphenation"
        // 用「窄一档刚好卡住预留」的版心：实测 @200 首行断成 `hyphen`（断词行）。
        val narrow = 200
        val lines = broken(text, narrow)
        val l0 = lines[0]
        assertTrue("首行应断词收尾（实测 ${lines.map { it.range to it.hyphenAtEnd }}）", l0.hyphenAtEnd)
        for (bl in lines) {
            val chars = m.advances(text.substring(bl.range), fs, 0f, "p", fam, 400, false, false).sum()
            val hy = if (bl.hyphenAtEnd) {
                m.hyphenWidthPx(fs, 0f, "p", fam, 400, false, false, emptyList(), bl.range.last)
            } else 0f
            assertTrue(
                "版心 $narrow 下行 ${bl.range} 判定宽 ${chars + hy} 超出版心（连字符宽未预留？）",
                chars + hy <= narrow + 1e-3f,
            )
        }
    }

    @Test
    fun `hyphen width is not charged to lines that do not break there`() {
        // 预留实现的**另一半**：断点行的判定宽含连字符，但**不含连字符的行不许被多算**。
        // 第一版把连字符宽焊进 `adv[i-1]`，一行里有几个断词点就算几份 ⇒ 提前断行。
        // 回归信号：`line count fairness holds on production configurations` 最差单格比值
        // 由 1.500 涨到 **2.0**、行数比值 0.9924 → 1.0102。
        //
        // 语料里放**多个**音节断点。整行只在其中一处断开 ⇒ 正确实现的判定宽
        // 只多一份连字符宽；焊进 `adv` 的实现会按断点数多算 ⇒ 提前断行。
        val text = "hyphenation extraordinarily"
        val hyW = m.hyphenWidthPx(fs, 0f, "p", fam, 400, false, false, emptyList(), 0)
        // 版心档**由断行结果反推**：取「确实产生了断词行」的档位，避免把
        // 「这个宽度整段装得下」误报成 bug（同 JUSTIFY 那把锁的道理）。
        val ws = (80..420 step 10).filter { w ->
            broken(text, w).any { it.hyphenAtEnd }
        }
        assertTrue("至少要有几个版心档产生断词行，否则这把锁验的是空气", ws.size >= 3)
        for (w in ws) for (bl in broken(text, w)) {
            // 判定宽必须与断行器同口径：**扣掉行尾文档空白**（UAX#14 LB SP 的悬挂）。
            // 直接 `sum(advances)` 会把行尾空格算进去，于是「空格后断行」的行一律误报溢出
            // （实测 `n o` @80：含空格 83.73、扣空格 44.63）。
            val sub = text.substring(bl.range)
            var vis = sub.length
            while (vis > 0 && orilumn.reader.engine.laying.isDocumentSpace(sub[vis - 1])) vis--
            val chars = m.advances(sub.substring(0, vis), fs, 0f, "p", fam, 400, false, false).sum()
            val hy = if (bl.hyphenAtEnd) {
                m.hyphenWidthPx(fs, 0f, "p", fam, 400, false, false, emptyList(), bl.range.last)
            } else 0f
            assertTrue(
                "行 ${bl.range} 判定宽 ${chars + hy} 超版心 $w", chars + hy <= w + 1e-3f,
            )
            // 断词行：判定宽**恰好**多一个连字符宽（不多不少）。
            // 「不多」是关键：焊进 `adv` 的实现会按行内断词点个数多算，第一版就是这么错的。
            if (bl.hyphenAtEnd) {
                assertEquals(
                    "行 ${bl.range} 断词行判定宽应恰好 = 字符宽 + 一份连字符宽 $hyW（多算=提前断行）",
                    chars + hyW, chars + hy, 1e-3f,
                )
            } else {
                assertEquals(
                    "行 ${bl.range} 未断词，判定宽必须**不含**任何连字符宽", chars, chars + hy, 1e-3f,
                )
            }
        }
    }

    @Test
    fun `R1 core fallback line must not carry a hyphen it has no room for`() {
        // **实测抓到的真溢出 bug**：R1 core 兜底（`brk = i`，版心窄到断点都装不下）
        // 与硬换行这两条路径，行宽**从未把连字符宽算进可用性判定**。
        // 若产出处照抄 `opp.isHyphenAt(brk)`，就会给一条放不下连字符的行补上连字符。
        // 实测：`hyphenation extraordinarily` @版心 80 的 `n ex` 行
        // 字符宽 70.53、`isHyphenAt(14)=true` 但判定宽 83.73 > 80（故走 R1 core）
        // ⇒ 补 13.20 的连字符后行宽 **83.73 > 80，溢出 3.73px**。
        //
        // 判据：**逐行判「字符宽 + 连字符宽 ≤ 版心」，对所有版心档扫一遍。**
        val text = "hyphenation extraordinarily"
        val hyW = m.hyphenWidthPx(fs, 0f, "p", fam, 400, false, false, emptyList(), 0)
        var checked = 0
        for (w in (60..460 step 10)) {
            for (bl in broken(text, w)) {
                val sub = text.substring(bl.range)
                var vis = sub.length
                while (vis > 0 && orilumn.reader.engine.laying.isDocumentSpace(sub[vis - 1])) vis--
                val chars = m.advances(sub.substring(0, vis), fs, 0f, "p", fam, 400, false, false).sum()
                val hy = if (bl.hyphenAtEnd) hyW else 0f
                assertTrue(
                    "版心 $w 行 ${bl.range}「$sub」宽 ${chars + hy} 超版心（hyphenAtEnd=${bl.hyphenAtEnd}）",
                    chars + hy <= w + 1e-3f,
                )
                if (bl.hyphenAtEnd) checked++
            }
        }
        assertTrue("至少要有几行带连字符，否则这把锁验的是空气", checked >= 3)
    }

    // ---- ④ 对齐层：连字符进拉伸基数，JUSTIFY 仍铺满，且不超版心 ----

    @Test
    fun `JUSTIFY still fills the measure when a line ends with a hyphen`() {
        val text = "hyphenation"
        // 版心档**由断行结果反推**：只有真正产生断词行的档位才进断言集合，
        // 否则「本该有连字符却没有」会把「语料在这个宽度装得下」误报成 bug。
        // （实测 @200/@240 断词，@220/@300 整段一行 —— 这正是断行器的正常行为。）
        val widths = listOf(100f, 140f, 180f, 200f, 240f, 280f).filter { w ->
            broken(text, w.toInt(), TextAlign.JUSTIFY).any { it.hyphenAtEnd }
        }
        assertTrue("至少要有几个版心档产生断词行，否则这把锁验的是空气", widths.size >= 3)
        for (w in widths) {
            val lines = broken(text, w.toInt(), TextAlign.JUSTIFY)
            var checked = 0
            for ((i, bl) in lines.withIndex()) {
                val p = al.align(
                    text, bl.range, fs, w, 0f, "p", fam, 400, false, false,
                    align = TextAlign.JUSTIFY, isLastLine = i == lines.size - 1,
                    hyphenAtEnd = bl.hyphenAtEnd,
                )
                assertTrue(
                    "版心 $w 行 ${bl.range} 可见右缘 ${p.visibleRight} 溢出（分页阅读器不能溢出：会被裁）",
                    p.visibleRight <= w + 0.5f,
                )
                if (bl.hyphenAtEnd) {
                    assertTrue("断词行应带连字符宽（实测 ${p.hyphenWidth}）", p.hyphenWidth > 0f)
                    checked++
                }
                if (i < lines.size - 1) {
                    assertEquals(
                        "含连字符的 JUSTIFY 中部行也必须铺满版心（版心 $w 行 ${bl.range}）",
                        w, p.visibleRight, 0.5f,
                    )
                }
            }
            assertTrue("版心 $w 下至少要有一行断词收尾，否则这把锁验的是空气（断点 ${lines.map { it.range }}）", checked >= 1)
        }
    }

    /**
     * **连字符前的空当必须「至多一个拉伸间隙」，且不得为负。**
     *
     * ## 这条锁守的正是本轮踩到的两个真 bug
     *
     * [LineAligner] 里「`extra` 该分给几份」（`gapCount`）与「`xs` 循环里实际放了几份」
     * 曾经**各错一边、方向相反**：`gapCount` 多算 1 而 `xs` 少放 1 ⇒ 凭空多出的那一份
     * `extra`（实测 **10.25px**）被当成「末字到连字符之间的空当」留在行里，
     * 同时 `visibleRight` 照样报告版心 200.0 —— **墨停在 176.55、连字符从 186.80 起**。
     * 反向的那一档则整行少铺 `slack/gapCount`，两端对齐肉眼可见地不到边。
     *
     * ## 为什么「至多一个间隙」是对的判据
     *
     * 本引擎的 JUSTIFY 是**逐字形间隙均摊**（不分「只拉空格」），所以连字符前那一个间隙
     * 与词内间隙**同级**：`hy­phen` @300 JUSTIFY 实测每份 `extra = 26.913`，
     * 末字右缘 259.884 → 连字符左缘 286.798，正好一份。
     * ⇒ 判据 = `0 ≤ (hyphenX − 末可见字形右缘) ≤ 一份 extra`，多一份就是 bug。
     *
     * `extra` 由本测试**独立**按「可见字形数 − 1」重算（不复用被测代码的 `gapCount`），
     * 故 `gapCount` 若算错、`xs` 若少放，这份独立重算的值就会与实际空当对不上。
     */
    @Test
    fun `space before the hyphen is at most one justification gap`() {
        val shy = SOFT_HYPHEN
        // 五种槽位/拉伸组合各来一遍：K-L 外接槽位、SHY 在行末（槽位复用）、
        // SHY 在中段、行内多处 SHY、以及完全无连字符（对照）。
        val cases = listOf(
            "K-L 外接槽位" to ("hyphenation" to true),
            "SHY 在行末(槽位复用)" to ("hyphen$shy" to true),
            "SHY 在中段" to ("hy${shy}phen" to true),
            "行内多处 SHY" to ("hy${shy}p${shy}hen" to true),
            "无连字符(对照)" to ("hy${shy}phen" to false),
        )
        for ((label, tc) in cases) {
            val text = tc.first
            val hyphenAtEnd = tc.second
            for (w in listOf(300f, 360f, 420f)) {
                for (ls in listOf(0f, 0.05f)) {
                    val p = al.align(
                        text, text.indices, fs, w, ls, "p", fam, 400, false, false,
                        align = TextAlign.JUSTIFY, isLastLine = false, hyphenAtEnd = hyphenAtEnd,
                    )
                    val visibleGlyphs = text.count { it != shy } + (if (hyphenAtEnd) 1 else 0)
                    if (hyphenAtEnd) {
                        assertTrue(
                            "[$label w=$w ls=$ls] 断词行应有连字符宽（实测 ${p.hyphenWidth}）",
                            p.hyphenWidth > 0f,
                        )
                        val hyX = al.hyphenXOf(p)
                        assertEquals(
                            "[$label w=$w ls=$ls] 连字符右缘必须等于版心右缘",
                            w, hyX + p.hyphenWidth, 0.5f,
                        )
                        // 槽位复用时，连字符**就是**最后一个可见字位（SHY 槽位被改写成
                    // 连字符宽），故不存在「它前面的空当」，`gap` 天然为 −hyphenWidth。
                    // 只有「外接字位」（K-L 音节断词）才有真正的前置间隙可比。
                    val slotReused = text.last() == shy
                    val oneGap = if (visibleGlyphs > 1) {
                            (w - p.advs.sumOf { it.toDouble() }.toFloat()) / (visibleGlyphs - 1)
                        } else 0f
                        if (slotReused) {
                            assertEquals(
                                "[$label w=$w ls=$ls] 槽位复用时连字符左缘 = 末可见字形左缘",
                                p.xs[text.length - 1], hyX, 0.01f,
                            )
                            assertTrue(
                                "[$label w=$w ls=$ls] 槽位复用的连字符宽应已写进 adv（实测 adv[${text.length - 1}]=${p.advs[text.length - 1]}）",
                                Math.abs(p.advs[text.length - 1] - p.hyphenWidth) < 0.01f,
                            )
                        }
                        // 取连字符**之前**那个字形的右缘。外接字位（K-L）时连字符在区间之外，
                        // 故基准是区间末字；槽位复用时连字符**就是**末字，基准要退一格。
                        var prevVis = -1
                        val scanTo = if (slotReused) text.length - 1 else text.length
                        for (k in 0 until scanTo) if (p.advs[k] > 0f) prevVis = k
                        assertTrue("语料须至少两个可见字形（实测 $prevVis）", prevVis >= 0)
                        val prevRight = p.xs[prevVis] + p.advs[prevVis]
                        val gap = hyX - prevRight
                        assertTrue(
                            "[$label w=$w ls=$ls] 连字符压在字上（空当 $gap < 0，前字右缘 $prevRight）",
                            gap >= -0.5f,
                        )
                        assertTrue(
                            "[$label w=$w ls=$ls] 连字符前空当 $gap 超过一份拉伸间隙 oneGap=$oneGap —— " +
                                "gapCount 与 xs 实际放置处数不一致（凭空多出的 extra 变成了假洞）",
                            gap <= oneGap + 0.5f,
                        )
                    } else {
                        assertEquals(
                            "[$label w=$w ls=$ls] 无连字符行不该有连字符宽", 0f, p.hyphenWidth, 0f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `SHY must not widen the placement or add a stretch gap`() {
        // 连字符**不新增字位**（`xs`/`advs` 与 range 等长是区间无缝的硬约束），
        // 且 `&shy;` 未断行时**不占版心**：带 SHY 的整段与去掉 SHY 的整段自然宽应逐字相等。
        val shy = SOFT_HYPHEN
        for (hyphenAtEnd in listOf(false, true)) {
            val withShy = "hy${shy}phen"
            val without = "hyphen"
            val pw = al.align(withShy, withShy.indices, fs, 400f, 0f, "p", fam, 400, false, false,
                align = TextAlign.LEFT, hyphenAtEnd = hyphenAtEnd)
            val pNo = al.align(without, without.indices, fs, 400f, 0f, "p", fam, 400, false, false,
                align = TextAlign.LEFT, hyphenAtEnd = hyphenAtEnd)
            assertEquals("连字符不得改变 xs 的长度（hyphenAtEnd=$hyphenAtEnd）", withShy.length, pw.xs.size)
            assertEquals("连字符不得改变 advs 的长度", withShy.length, pw.advs.size)
            if (!hyphenAtEnd) {
                assertEquals(
                    "SHY 未断行时整段自然宽必须与去掉 SHY 一致（SHY 严格零宽）",
                    pNo.visibleRight, pw.visibleRight, 1e-3f,
                )
            }
        }
    }

    // ---- ⑤ 绘制层（像素）：不换行时无墨，换行时行尾有墨 ----

    private fun drawLine(text: String, range: IntRange, hyphenAtEnd: Boolean, width: Int): Bitmap {
        val bmp = Bitmap()
        bmp.allocN32Pixels(width, 120, true)
        val canvas = Canvas(bmp)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(
            canvas, 0f,
            listOf(
                DrawLine(
                    text = text, range = range, yTop = 0, yBottom = 100,
                    alignment = TextAlign.LEFT, fontSizePx = fs, lineHeightRatio = 2f,
                    tag = "p", families = fam, weight = 400, italic = false, monospace = false,
                    letterSpacingEm = 0f, lineWidthPx = width, hyphenAtEnd = hyphenAtEnd,
                ),
            ), null,
        )
        return bmp
    }

    private fun hasInk(bmp: Bitmap, xLo: Int, xHi: Int, yLo: Int = 0, yHi: Int = 120): Boolean {
        val px = requireNotNull(bmp.peekPixels())
        for (y in yLo until yHi) for (x in xLo.coerceAtLeast(0) until xHi.coerceAtMost(bmp.width)) {
            if (px.getColor(x, y) != Color.WHITE) return true
        }
        return false
    }

    @Test
    fun `unbroken SHY paints exactly nothing`() {
        // **像素全等**是最强判据：`hy­phen` 与 `hyphen` 画出来必须逐像素一模一样。
        // SHY 在 STSong 里有真字形（实测 glyph 271），只检查「某个窗口无墨」会漏掉
        // 「它被画到别处去了」；全等则连位置错都逃不掉。
        val withShy = "hy${SOFT_HYPHEN}phen"
        val without = "hyphen"
        val a = drawLine(withShy, withShy.indices, hyphenAtEnd = false, width = 400)
        val b = drawLine(without, without.indices, hyphenAtEnd = false, width = 400)
        val pa = requireNotNull(a.peekPixels())
        val pb = requireNotNull(b.peekPixels())
        var diff = 0
        var firstDiff = -1
        for (y in 0 until 120) for (x in 0 until 400) {
            if (pa.getColor(x, y) != pb.getColor(x, y)) {
                diff++
                if (firstDiff < 0) firstDiff = x * 1000 + y
            }
        }
        assertEquals("SHY 未断行时不得产生任何墨（它有真字形 glyph 271）；首个差异在 $firstDiff", 0, diff)
        // 对照：那一段确实有字（否则「全等」是因为都没画出来，锁是空的）。
        assertTrue("对照：h/y 必须有墨", hasInk(a, 0, 40))
    }

    @Test
    fun `broken SHY draws a hyphen inked at the placement position`() {
        val text = "hy${SOFT_HYPHEN}phen"
        // 断在 SHY 上：`hy­` 独占一行 ⇒ SHY 字位被复用成连字符槽位。
        val brkRange = 0 until 3
        val p = al.align(
            text, brkRange, fs, 400f, 0f, "p", fam, 400, false, false, hyphenAtEnd = true,
        )
        assertTrue("断词行应有连字符宽（实测 ${p.hyphenWidth}）", p.hyphenWidth > 0f)
        val hyX = al.hyphenXOf(p)
        assertTrue("连字符左缘应在行内（实测 $hyX）", hyX > 0f)
        assertTrue(
            "连字符位置必须有墨（hyphenX=$hyX hyphenWidth=${p.hyphenWidth}）",
            hasInk(drawLine(text, brkRange, hyphenAtEnd = true, width = 400), hyX.toInt(), hyX.toInt() + p.hyphenWidth.toInt() + 4),
        )
        // 反证：同一区间但**不补连字符** ⇒ SHY 槽位零宽、行末停在 `y` 的右缘，
        // 那个连字符窗口内既无连字符也无后继字符（下一行才有的 `p` 不在此区间），故无墨。
        // （不能拿「整行墨分布」当反证：`y` 与 `p` 的字形本身会落在该 x 窗口里。）
        val pNo = al.align(
            text, brkRange, fs, 400f, 0f, "p", fam, 400, false, false, hyphenAtEnd = false,
        )
        assertEquals("不补连字符时 hyphenWidth 恒 0", 0f, pNo.hyphenWidth, 0f)
        assertEquals("不补连字符时行末停在 y 右缘", pNo.visibleRight, pNo.xs[1] + pNo.advs[1], 1e-3f)
        assertFalse(
            "不补连字符时连字符窗口内不得有墨（hyX=$hyX，末字右缘 ${pNo.visibleRight}）",
            hasInk(
                drawLine(text, brkRange, hyphenAtEnd = false, width = 400),
                hyX.toInt() + 2, hyX.toInt() + p.hyphenWidth.toInt(),
            ),
        )
    }
}
