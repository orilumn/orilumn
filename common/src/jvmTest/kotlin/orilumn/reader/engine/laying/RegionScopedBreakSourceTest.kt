package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RegionScopedBreakSource] 的正向锁（Q18）——**逐位置**钉死它标了哪些断点。
 *
 * ## 为什么必须钉「位置」而不是「行」
 *
 * 本类唯一的可错点是**分区边界处的字符可见性**与**子串坐标换算**（见被测类里那两段注释）。
 * 第一版有两处错：① 换算 `t = pos - i + 1` ⇒ 断点集整体左移一位；
 * ② 子串不带左邻域 ⇒ **每个分区的首个位置**算不出来，其中「代码 run 之后紧跟的空格」
 * 丢了断点（该空格本被 ZH_EN 放行）⇒ 贪心越过它继续填，必要时改走 R1 core 在下一处硬切。
 * 这两处**都不改变「一行切成几行」的形状**，且第二处在断行器输出里往往**完全观察不到**
 * （R1 core 会在同一处切出同样的行区间）⇒ 端到端锁抓不到，只能在位置层钉。
 * ⇒ 这里的断言全部落在**位置集合**上，样本专挑能被这两种算术错误挪动的形状。
 *
 * 位置语义（[BreakOpportunitySet]）：位置 `i` 指 `text[i-1]` 与 `text[i]` 之间。
 */
class RegionScopedBreakSourceTest {

    /** 构造「位置掩码」：列出属于内区的位置。长度 = `n + 1`。 */
    private fun mask(n: Int, vararg innerPositions: Int): BooleanArray {
        val m = BooleanArray(n + 1)
        for (i in innerPositions) m[i] = true
        return m
    }

    private fun maskAll(n: Int): BooleanArray = mask(n, *(1 until n).toList().toIntArray())

    private fun setOf(text: String, source: BreakOpportunitySource): BreakOpportunitySet =
        BreakOpportunitySet.of(text, listOf(source))

    private fun marked(text: String, source: BreakOpportunitySource): List<Int> {
        val s = setOf(text, source)
        return (1 until text.length).filter { s.opportunityAt(it) }
    }

    private fun markedHyphen(text: String, source: BreakOpportunitySource): List<Int> {
        val s = setOf(text, source)
        return (1 until text.length).filter { s.isHyphenAt(it) }
    }

    /**
     * 正向：`_` 后的断点必须落在**下划线自己后面那一位**。
     *
     * `a_b`：`_` 在下标 1 ⇒ 断点位置 = **2**。整段皆内区（走快速路径）。
     * 换算错成 `+1` 时会标到 1（`a|_b`），本锁即红。
     */
    @Test
    fun identifierBreakLandsRightAfterTheSeparator() {
        val text = "a_b"
        val src = RegionScopedBreakSource(maskAll(text.length), inner = listOf(CodeIdentifierBreakSource), outer = emptyList())
        assertEquals("`_` 之后的断点位置必须是 2", listOf(2), marked(text, src))
    }

    /**
     * **代码 run 之后紧跟的空格必须仍是断点**（本类第一版的真缺陷）。
     *
     * `aa xx bb`，代码 run = `[3,5)`（`xx`）⇒ 内区 = 位置 `{4}`（run 内第 1、2 字符之间）。
     * 分区 = `[1,4) 外 / [4,5) 内 / [5,8) 外`。
     *
     * 位置 5 = 「`x` ‖ 空格」，它落在**外区**且是该区**首个位置** —— 第一版的子串 `text[i, j)`
     * 缺左邻域，算不出它，于是**这个空格不再是断点**。而 [KinsokuRules.ZH_EN] 对它**本来就放行**
     * （`isDocumentSpace(next)`，见 `allowsBreakAt`）。
     *
     * 丢它的后果**不是溢出**（贪心恒有 `hang <= avail`，见被测类那段注释），而是「少一个断点」：
     * 贪心会越过该位置继续填，必要时改走 R1 core 在**下一处**硬切。
     *
     * 这条之所以只能在**位置层**钉：这类丢位在断行器输出里往往**观察不到**
     * （R1 core 会在同一处切出同样的行区间）⇒ 端到端锁抓不到它。
     *
     * 期望 = `{2,3,5,6}`：位置 1、7 是 `a|a`/`b|b`（两侧都非空白 ⇒ 禁则不放行），位置 4 是 `x|x`。
     */
    @Test
    fun spaceRightAfterCodeRunIsStillABreakOpportunity() {
        val text = "aa xx bb"
        val src = RegionScopedBreakSource(
            mask(text.length, 4),
            inner = listOf(KinsokuBreakSource, CodeIdentifierBreakSource),
            outer = listOf(KinsokuBreakSource),
        )
        assertEquals(
            "断点集（含位置 5 = 'x'‖空格）", listOf(2, 3, 5, 6), marked(text, src),
        )
        assertTrue(
            "位置 5 必须是断点（第一版会丢）",
            setOf(text, src).opportunityAt(5),
        )
    }

    /**
     * 跨分区换算：`x a_b y`，内区 = 位置 `{3,4}`（`a|_`、`_|b` 两侧都在代码内）。
     *
     * 全局：0=x 1=' ' 2=a 3=_ 4=b 5=' ' 6=y。
     * 位置 2（` |a`）与 5（`b| `）是**边界** ⇒ 归外区。分区 = `[1,3) 外 / [3,5) 内 / [5,7) 外`。
     *
     * - 内区：`CodeIdentifierBreakSource` 在 `_` 后标一个断点 ⇒ 子集位置 2 ⇒ 全局 `2 + 2 = 4`。
     *   换算若写成 `pos - i` 配 `sub = text[i, j)`（第一版的形状）会落到 3；写成 `pos - i + 1` 会落到 5。
     * - 外区：一个「空格后皆可断」的探针源，在 `[1,3)` 的子串 `x a` 上标位置 1，在 `[5,7)` 的子串
     *   ` y` 上标位置 1 ⇒ 全局 1 与 5（**位置 5 只有带左邻域 `from=4` 才算得出来**）。
     *
     * 期望 = `{1, 4, 5}`。
     */
    @Test
    fun crossRegionOffsetIsCorrect() {
        val text = "x a_b y"
        // 内容驱动的探针源（不能用「标死绝对位置」的写法：那会在子串上越界，
        // 也测不出子串起点取错）。
        val outerSrc = BreakOpportunitySource { t, into ->
            for (k in 1 until t.length) if (t[k] == ' ') into.mark(k)
        }
        val src = RegionScopedBreakSource(
            mask(text.length, 3, 4),
            inner = listOf(CodeIdentifierBreakSource), outer = listOf(outerSrc),
        )
        assertEquals("内区给 4、外区给 1/5", listOf(1, 4, 5), marked(text, src))
    }

    /**
     * 内区**不跑**外区源（这才是分区的意义：音节断词进不了代码 run）。
     *
     * 样本 `hyphenation`：用真源 [EnglishHyphenationSource] 先确认它**确实**标了音节断点，
     * 再断言整段皆内区时那些位置一个都不出现。
     */
    @Test
    fun innerRegionDoesNotInheritOuterSources() {
        val text = "hyphenation"
        val kOnly = setOf(text, EnglishHyphenationSource.forLang("en"))
        assertTrue("前提：外区源在这个词上确实标了音节断点", (1 until text.length).any { kOnly.opportunityAt(it) })

        val src = RegionScopedBreakSource(
            maskAll(text.length),
            inner = listOf(CodeIdentifierBreakSource), outer = listOf(EnglishHyphenationSource.forLang("en")),
        )
        assertEquals(
            "内区不得出现音节断点", emptyList<Int>(),
            (1 until text.length).filter { setOf(text, src).opportunityAt(it) },
        )
    }

    /** 反向：外区不注入代码标识符断点（散文的 `a_b` 不断）。 */
    @Test
    fun outerRegionDoesNotInheritInnerSources() {
        val text = "a_b"
        val src = RegionScopedBreakSource(
            mask(text.length), inner = listOf(CodeIdentifierBreakSource), outer = listOf(KinsokuBreakSource),
        )
        assertEquals("散文区的 `a_b` 不该有 `_` 断点", emptyList<Int>(), marked(text, src))
    }

    /**
     * `hyphens` 那一维必须**跟着断点一起平移**（`mark` 与 `markHyphen` 是两维信息，漏一维就前功尽弃）。
     *
     * 样本 `ab­cd`（`­` = U+00AD SHY，下标 2）：断点位置 3，且 `isHyphenAt(3) == true`。
     * 掩码取 `{3}`（位置 3 两侧 `­` 与 `c` 都在内区）⇒ 走**慢路径**，验证平移后仍带 hyphens 位。
     */
    @Test
    fun hyphenBitIsShiftedTogetherWithTheFlag() {
        val shy = '­'
        val text = "ab" + shy + "cd"
        assertEquals("样本前置条件：SHY 在下标 2", 2, text.indexOf(shy))
        val src = RegionScopedBreakSource(
            mask(text.length, 3),
            inner = listOf(SoftHyphenBreakSource), outer = emptyList(),
        )
        assertEquals("SHY 之后的断点位置 = 3", listOf(3), marked(text, src))
        assertEquals("且该位置必须标『需补连字符』", listOf(3), markedHyphen(text, src))
    }

    /**
     * 快速路径与「直接跑同一套 source」**逐值一致**：整段同区时走的是另一条代码路径，
     * 形状不同结果必须相同（否则「有没有行内代码」会改变同一段的断行，纯属幽灵差异）。
     */
    @Test
    fun uniformFastPathEqualsRunningTheSameSourcesDirectly() {
        val text = "hyphenation_and_more"
        val innerList = listOf(KinsokuBreakSource, SoftHyphenBreakSource, CodeIdentifierBreakSource)
        val outerList = listOf(KinsokuBreakSource, SoftHyphenBreakSource, EnglishHyphenationSource.forLang("en"))
        assertEquals(
            "内区快速路径", true,
            setOf(text, RegionScopedBreakSource(maskAll(text.length), innerList, outerList))
                .contentEquals(BreakOpportunitySet.of(text, innerList)),
        )
        assertEquals(
            "外区快速路径", true,
            setOf(text, RegionScopedBreakSource(mask(text.length), innerList, outerList))
                .contentEquals(BreakOpportunitySet.of(text, outerList)),
        )
    }

    /** 无行内代码（掩码全假）时必须**完全透明**：结果与「只给外区源」逐值相同。 */
    @Test
    fun noInnerRegionIsTransparent() {
        val text = "hello world"
        val src = RegionScopedBreakSource(
            mask(text.length), inner = listOf(CodeIdentifierBreakSource), outer = listOf(KinsokuBreakSource),
        )
        assertEquals(
            true,
            setOf(text, src).contentEquals(BreakOpportunitySet.of(text, listOf(KinsokuBreakSource))),
        )
    }

    /**
     * **多个**代码 run 交错时，每个 run 的**左右两个边界位置**都不许丢。
     *
     * `a xx b yy c`：run1 = `[2,4)`、run2 = `[7,9)` ⇒ 内区 = `{3, 8}`（各自第 1、2 个字符之间）。
     * 分区 = `[1,3) 外 / [3,4) 内 / [4,7) 外 / [8,9) 内 / [9,11) 外` —— 注意 `[4,7)` 与 `[9,11)`
     * 这两段的**首个位置 4、9** 正是「代码末字符 ‖ 后随空格」，也是外区的**首个位置**。
     *
     * 第一版（子串不带左邻域）会把**每个分区的首个位置**全部丢掉 ⇒ 位置 1、4、9 全丢
     * ⇒ 这三个空格不再是断点。本锁逐个断言它们必须在。
     */
    @Test
    fun everyRegionFirstPositionKeepsItsSpaceBreak() {
        val text = "a xx b yy c"
        assertEquals(11, text.length)
        val src = RegionScopedBreakSource(
            mask(text.length, 3, 8),
            inner = listOf(KinsokuBreakSource), outer = listOf(KinsokuBreakSource),
        )
        val got = marked(text, src)
        // 外区各段的**首个**位置 1 / 4 / 9（都是「字母 ‖ 空格」），以及末段末尾 10。
        for (p in listOf(1, 4, 9, 10)) {
            assertTrue("位置 $p（'${text[p - 1]}|${text[p]}'）必须是断点，实得 $got", got.contains(p))
        }
        // 对照：与「整段直接跑 KinsokuBreakSource」逐值相同（内区也是 Kinsoku，无差别）。
        assertEquals(
            "有行内代码时结果必须与整段同一套 source 逐值相同（本例两区 source 相同）",
            true,
            setOf(text, src).contentEquals(BreakOpportunitySet.of(text, listOf(KinsokuBreakSource))),
        )
    }

    // ---- maskOf：位置掩码的判据（Q18 的第二条可错判据） ----

    /**
     * **边界位置归外区**（[RegionScopedBreakSource.maskOf] 的判据 `inCode[i-1] && inCode[i]`）。
     *
     * `fooBar tail`，代码 run = `[3,6)`（`Bar`）。内区只能是 run 的**严格内部** = 位置 `{4, 5}`；
     * 位置 3（`o|B`）与 6（`r|␣`）是边界 ⇒ 归外区。
     *
     * ⚠ 这条判据**端到端观察不到**（实测：把 `&&` 换成 `||`，全链路测试**全绿**）。
     * `||` 会把两类边界位置也标成内区、多出断点（典型：`foo|Bar` 凭驼峰规则被标上），
     * 但那些断点**全部落在 min-content 单元内部**，而生产恒有 `widthPx ≥ ceil(minContentWidth)`
     * ⇒ 贪心永远选不到它们。**任何按行/按版心的锁都抓不到它** ⇒ 只能在这里钉。
     * 这也正是该判据被搬进 companion（而不是留在调用方）的唯一理由。
     */
    @Test
    fun maskOfPutsBothRunBoundariesInTheOuterRegion() {
        val text = "fooBar tail"
        val mask = RegionScopedBreakSource.maskOf(text.length, listOf(3 until 6))!!
        assertEquals("样本前置条件", "Bar", text.substring(3, 6))
        assertEquals("内区只能是 run 的严格内部 {4,5}", listOf(4, 5), (1 until text.length).filter { mask[it] })
        assertTrue("位置 3（o|B，run 的左边界）必须归外区", !mask[3])
        assertTrue("位置 6（r|␣，run 的右边界）必须归外区", !mask[6])
    }

    /** 无代码字符时返回 `null`（调用点据此走整段老路径，热路径零额外开销）。 */
    @Test
    fun maskOfReturnsNullWhenThereIsNoCode() {
        assertEquals("空区间表", null, RegionScopedBreakSource.maskOf(10, emptyList()))
        assertEquals("区间为零宽", null, RegionScopedBreakSource.maskOf(10, listOf(20 until 20)))
        assertEquals("区间整体在段外", null, RegionScopedBreakSource.maskOf(10, listOf(20 until 25)))
        assertEquals("段长 < 2", null, RegionScopedBreakSource.maskOf(1, listOf(0 until 1)))
    }

    /** 掩码长度恒为 `n + 1`（与 [BreakOpportunitySet] 同一条下标约定，下标 0 不使用）。 */
    @Test
    fun maskOfHasTheSameLengthConventionAsTheBreakSet() {
        for (n in intArrayOf(2, 3, 7, 50)) {
            assertEquals(
                "mask 长度必须 = n + 1", n + 1,
                RegionScopedBreakSource.maskOf(n, listOf(0 until n - 1))!!.size,
            )
        }
    }

    /** 多个 / 重叠 / 乱序的区间取并集，不依赖输入顺序。 */
    @Test
    fun maskOfUnionsSpansRegardlessOfOrderOrOverlap() {
        val n = 12
        val a = RegionScopedBreakSource.maskOf(n, listOf(2 until 5, 7 until 9))!!
        val b = RegionScopedBreakSource.maskOf(n, listOf(7 until 9, 2 until 5))!!
        val c = RegionScopedBreakSource.maskOf(n, listOf(2 until 8))!!
        for (i in 1 until n) {
            assertEquals("乱序不得影响掩码（位置 $i）", a[i], b[i])
        }
        assertEquals("两个不交区间 [2,5)∪[7,9) 的内区", listOf(3, 4, 8), (1 until n).filter { a[it] })
        assertEquals("大区间 [2,8) 覆盖两个小区间", listOf(3, 4, 5, 6, 7), (1 until n).filter { c[it] })
    }
}
