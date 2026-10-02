package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BrokenLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **行内 `<code>` 断行规则**锁（Q18）——钉死 [InhouseParagraphBreaker.breakOpportunities] 的三条路径。
 *
 * ## 为什么钉「不变量」而不是逐版心钉死行文本
 *
 * Q18 的实测表（Rust 真源码 `<li>`，版心 360/420/480/520/640）显示：**改动只影响 360/640 两档**，
 * 其余三档**逐值不变**。逐版心钉死虽然可行，但字体一换（各机器族回退不同）数值就飘。
 * ⇒ 这里改钉**与字体无关的不变量**：「行首落点」必须满足的性质，再配一条**活性断言**
 * （该性质的断点**确实能被取到**），否则性质会因「什么都不断」而空过。
 *
 * ## 三条路径各自钉什么
 *
 * | 路径 | 触发条件 | 锁 |
 * | --- | --- | --- |
 * | 整段代码 | 叶块 tag ∈ `CODE_TAGS` | [codeLeafIsNeverPartitionedByItsInnerRuns] |
 * | 按位置分区 | 叶块非代码 + 有代码 run | [inlineCodeRunOnlyBreaksAfterSeparator] / [inlineCodeRunKeepsTheSpaceAfterIt] |
 * | 整段散文 | 无代码 run | [proseKeepsSyllableBreaks] / [nonCodeRunsAreTransparent] |
 *
 * ## 字体
 *
 * 本锁只断言**断点位置**（字符级），不断言任何像素值，故族回退差异不影响结论；
 * 仍随 [InhouseParagraphBreakerTest] 一起要求机器装有可用拉丁面族。
 */
class InlineCodeBreakRulesTest {

    private val fam = listOf("Georgia", "STSong", "serif")
    private val size = 20f
    private val lh = 1.5f

    /** 探针正文：`如 wrapping_add 方法`。`wrapping_add` = `[2,14)`，`_` 在下标 10，`_` 后 = 位置 11。 */
    private val probe = "如 wrapping_add 方法"
    private val codeStart = 2
    private val codeEnd = 14
    private val afterUnderscore = 11

    private fun codeRun(start: Int, end: Int, tag: String = "code") =
        FontRun(start, end, fam, tag, 400, false, true)

    private fun lines(
        text: String,
        widthPx: Int,
        tag: String? = "p",
        runs: List<FontRun> = emptyList(),
    ): List<BrokenLine> = InhouseParagraphBreaker(0f).breakLines(
        text, size, lh, widthPx, TextAlign.LEFT, tag, fam, 400, false, false,
        0f, runs, emptyList(),
    )

    /** 逐行行首位置（首行恒 0 已在 [lines] 里天然体现：首行起点就是 0）。 */
    private fun lineStarts(text: String, widthPx: Int, tag: String?, runs: List<FontRun>): List<Int> =
        lines(text, widthPx, tag, runs).map { it.range.first }

    /**
     * **生产地板**：`ceil(minContentWidth)` —— 版心不得窄于此，否则 R1 core 接管。
     *
     * 扫描版心时**必须**从地板起，否则结论会被 R1 core 污染：R1 的语义是「每个位置都算断点」
     * （[BreakOpportunitySet.markAll]），于是「代码内不许在字母中间断」这条性质会被 R1 直接作废
     * ——实测第一次写锁时扫 `1..420` 就在版心 1 上拿到 `[3,4,…,13]` 全落点，全是 R1 的产物。
     * 这条地板就是 `InhouseParagraphBreakerTest` 里 [floorPx] 用的同一条生产不变式。
     */
    private fun floor(text: String, tag: String?, runs: List<FontRun>): Int {
        val w = InhouseParagraphBreaker(0f).minContentWidth(text, size, fam, 400, false, false, runs)
        return kotlin.math.ceil(w).toInt()
    }

    /** 版心扫描：从生产地板扫到 420，返回所有行首位置（去重、升序）。 */
    private fun allLineStarts(text: String, tag: String?, runs: List<FontRun>): Set<Int> {
        val lo = floor(text, tag, runs)
        return (lo..420).flatMapTo(sortedSetOf()) { lineStarts(text, it, tag, runs) }
    }

    /** 生产地板之上的全部版心（供「活性」断言遍历用，避免重复写区间）。 */
    private fun widthsAbove(text: String, tag: String?, runs: List<FontRun>): IntProgression =
        floor(text, tag, runs)..420

    // ---- 样本前置条件（写死，任何一条不成立都说明样本被改动，先修样本再谈锁） ----

    @Test
    fun probeFixtureIsWhatTheAssertionsAssume() {
        assertEquals("样本 = `如 wrapping_add 方法`", 17, probe.length)
        assertEquals("代码 run = [2,14)", "wrapping_add", probe.substring(codeStart, codeEnd))
        assertEquals("`_` 在下标 10", '_', probe[10])
        assertEquals("`_` 之后的位置 = 11", 'a', probe[11])
        assertEquals("run 之后紧跟一个空格", ' ', probe[codeEnd])
    }

    // ---- 路径 2：按位置分区 ----

    /**
     * **行内代码 run 内的行首落点，只允许落在标识符分隔符之后**（本锁的核心）。
     *
     * 扫描生产地板之上的每个版心，收集所有落在 run **严格内部**（`2 < p < 14`）的行首位置，
     * 断言它们**全部**等于 11（`_` 之后）。任何非 11 的落点都是「在字母中间断」——
     * 要么是音节断词泄漏进了代码 run（Q18 的缺陷本体），要么是代码分隔符集漏了某个字符。
     *
     * ## 活性断言（否则性质会空过）
     *
     * 若连 `_` 断点都取不到（贪心总在别处断开），本锁就只是「什么都没发生」。
     * 故另断言：确实存在某个版心使行首落在 11。
     */
    @Test
    fun inlineCodeRunOnlyBreaksAfterSeparator() {
        val runs = listOf(codeRun(codeStart, codeEnd))
        val interior = allLineStarts(probe, "p", runs).filter { it > codeStart && it < codeEnd }
        assertEquals(
            "行内代码 run 内的行首落点只能是 `_` 之后（位置 11），实得 $interior",
            listOf(afterUnderscore), interior,
        )
        assertTrue(
            "活性：`_` 断点必须真的能被取到（否则本锁空过）",
            widthsAbove(probe, "p", runs).any { lineStarts(probe, it, "p", runs).contains(afterUnderscore) },
        )
    }

    /**
     * **代码内的断点一律不带连字符**，而**散文的音节断点必须带**（同一维信息的正反对照）。
     *
     * - 当作行内 `<code>` 时，[orilumn.reader.engine.laying.CodeIdentifierBreakSource] 走 `mark`
     *   ⇒ 断在 `_` 之后（位置 11）的行，行尾**不出现** `-`；
     * - 当作散文时 [orilumn.reader.engine.laying.EnglishHyphenationSource] 走 `markHyphen`
     *   ⇒ 断在 run 内部音节点上的行，行尾**必须**出现 `-`。
     *
     * 钉这一维是因为 `hyphenAtEnd` 与 `opportunity` 是**两个独立的位**（`mark` vs `markHyphen`，
     * 写错一维就前功尽弃），且行内代码与整块 `<pre>` 必须同口径（Q18② 求的统一）。
     */
    @Test
    fun codeInternalBreaksCarryNoHyphenWhileProseSyllableBreaksDo() {
        val runs = listOf(codeRun(codeStart, codeEnd))
        var codeBreaks = 0
        for (w in widthsAbove(probe, "p", runs)) {
            for (ln in lines(probe, w, "p", runs)) {
                if (ln.range.last + 1 != afterUnderscore) continue
                codeBreaks++
                assertTrue("代码内 `_` 断点不得补连字符（版心 $w）", !ln.hyphenAtEnd)
            }
        }
        assertTrue("活性：`_` 断点必须能被取到（命中 $codeBreaks 行）", codeBreaks > 0)

        var proseHyphens = 0
        for (w in widthsAbove(probe, "p", emptyList())) {
            for (ln in lines(probe, w, "p", emptyList())) {
                val end = ln.range.last + 1
                if (end > codeStart && end < codeEnd && ln.hyphenAtEnd) proseHyphens++
            }
        }
        assertTrue("对照：散文里的音节断点必须补连字符（实得 $proseHyphens 行）", proseHyphens > 0)
    }

    // ---- 路径 3：整段散文（逐值不变的证据） ----

    /**
     * **散文路径不受 Q18 影响**：同一个词不作 `<code>` 时，音节断点**必须仍然生效**。
     *
     * 断言存在某个版心使行首落在 run 内部且**不等于** 11 —— 那只可能来自 K-L 音节断词。
     * 这条同时钉住「外区仍用 [orilumn.reader.engine.laying.EnglishHyphenationSource]」：
     * 若有人把外区也换成代码 source（内区外区搞反），本锁即红。
     */
    @Test
    fun proseKeepsSyllableBreaks() {
        val interior = allLineStarts(probe, "p", emptyList()).filter { it > codeStart && it < codeEnd }
        assertTrue(
            "散文里 `wrapping_add` 必须仍可被音节断词切开（活性），实得 $interior",
            interior.any { it != afterUnderscore },
        )
    }
    /**
     * **非代码 run 对断行完全透明**：只有 `<em>`/`<span>` 这类 run 而无代码 run 时，
     * 结果必须与 `fontRuns = emptyList()` **逐值相同**（同样本、同版心的每一行区间与 `hyphenAtEnd`）。
     *
     * 这条钉的是 `codeInteriorMask` 的早退：没有代码 run 就整段走散文老路径，
     * 不该因为「有 run」这件事本身多分区一次（否则纯排版 run 会改变断行，幽灵差异）。
     */
    @Test
    fun nonCodeRunsAreTransparent() {
        val runs = listOf(FontRun(codeStart, codeEnd, fam, "em", 400, false, false))
        for (w in intArrayOf(60, 100, 142, 200, 280, 360, 420)) {
            assertEquals(
                "版心 $w：非代码 run 不得改变断行（区间 + hyphenAtEnd）",
                lines(probe, w, "p", emptyList()).map { it.range to it.hyphenAtEnd },
                lines(probe, w, "p", runs).map { it.range to it.hyphenAtEnd },
            )
        }
    }

    // ---- 路径 1：叶块本身是代码 ⇒ 不分区 ----

    /**
     * **叶块本身是代码时**（tag ∈ `CODE_TAGS`）**刻意不分区**，即使它的 run 里混着非代码 tag。
     *
     * `<pre>` 里可以嵌 `<em>`/`<span>`（那些 run 的 `tag` 不是代码 tag）。一旦分区，
     * 它们就会落进散文规则、被音节断词切开 —— 而 `pre` 整块都是代码，**不能**切。
     *
     * 断言：tag = `pre` 时，带 `[tag=em]` 的 run 与 `fontRuns = emptyList()` **逐值相同**；
     * 且带 `[tag=code]` 的 run 也与之相同（同走整段 `codeSources()`，分区与否都不参与）。
     */
    @Test
    fun codeLeafIsNeverPartitionedByItsInnerRuns() {
        val mixedRuns = listOf(
            FontRun(codeStart, codeEnd, fam, "em", 400, false, false),
            FontRun(15, 17, fam, "span", 400, false, false),
        )
        val codeRuns = listOf(codeRun(codeStart, codeEnd), codeRun(15, 17))
        for (w in intArrayOf(60, 100, 142, 200, 280, 360, 420)) {
            val plain = lines(probe, w, "pre", emptyList()).map { it.range to it.hyphenAtEnd }
            assertEquals(
                "版心 $w：`<pre>` 里的 `<em>` run 不得把散文规则引进来",
                plain, lines(probe, w, "pre", mixedRuns).map { it.range to it.hyphenAtEnd },
            )
            assertEquals(
                "版心 $w：`<pre>` 里的 `<code>` run 与空 run 同结果（都不分区）",
                plain, lines(probe, w, "pre", codeRuns).map { it.range to it.hyphenAtEnd },
            )
        }
        // 活性：`<pre>` 整段是代码 ⇒ `wrapping_add` 内**不得**出现非 `_` 的行首落点。
        val interior = allLineStarts(probe, "pre", codeRuns).filter { it > codeStart && it < codeEnd }
        assertEquals("`<pre>` 内只允许 `_` 断点，实得 $interior", listOf(afterUnderscore), interior)
    }

    /**
     * 叶块 tag ∈ `CODE_TAGS` 且**根本没有 run** 时，也必须走代码规则（不得因 `fontRuns` 为空而退成散文）。
     *
     * 这正是真机上 `<pre>…</pre>` 里纯文本代码块的形状（`collectFontRuns` 对 uniform 叶不产 run）。
     * 断言：tag=`pre` + 空 run 的结果与 tag=`p` + 空 run 的结果**不同**，且前者无音节断点。
     */
    @Test
    fun codeLeafWithNoRunsStillUsesCodeRules() {
        val preStarts = allLineStarts(probe, "pre", emptyList()).filter { it > codeStart && it < codeEnd }
        val pStarts = allLineStarts(probe, "p", emptyList()).filter { it > codeStart && it < codeEnd }
        assertEquals("`<pre>` 无 run 仍按代码规则：只允许 `_` 断点", listOf(afterUnderscore), preStarts)
        assertTrue("对照：`p` 无 run 走散文，音节断点必须生效（否则本对照无意义）", pStarts.any { it != afterUnderscore })
    }
}
