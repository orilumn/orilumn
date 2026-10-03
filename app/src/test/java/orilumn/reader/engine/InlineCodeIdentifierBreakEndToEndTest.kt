package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PagedLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Q18 端到端锁**：行内 `<code>` 里的标识符**不再被音节断词切开**，且切点只落在分隔符后。
 *
 * ## 缺陷本体（用户真机报 `wrap(断行)ping_add`）
 *
 * Rust 书真源码 `<li>所有模式下都可以使用 <code>wrapping_*</code> 方法进行 wrapping，
 * 如 <code>wrapping_add</code></li>` 在版心 640 下断成 `…如 wrap` ‖ `ping_add` ——
 * 切点落在**行内 `<code>` run 内部**且是**音节**断点，还补了一个连字符。
 * 接线只判**叶块 tag**（`<li>` 不在 `CODE_TAGS`）⇒ 整段按散文规则断，
 * 违反 [orilumn.reader.engine.laying.CodeIdentifierBreakSource] 与
 * [orilumn.reader.engine.laying.EnglishHyphenationSource] **各自 KDoc 里写明的规则**。
 *
 * 单测层的锁在 [orilumn.reader.engine.skia.InlineCodeBreakRulesTest]（断行器 × 三条路径）
 * 与 [orilumn.reader.engine.laying.RegionScopedBreakSourceTest]（断点集逐位置）；本锁验整条链路：
 * HTML → 归一化叶文本 → `fontRuns` → 断行区间 → 行窗投影。
 *
 * ## 为什么逐版心钉死字符串
 *
 * 断行**位置**的分布取决于默认阅读配置的字号与族回退（各机器可能不同），所以「哪些切点可能发生」
 * 交给上面那两把锁去钉；这里钉的是**真实书源码上的具体结果**——它同时充当**零回归证据**：
 * 同一版心下同一切点、同一个连字符位。若哪天真出现改动把某档挪了，本锁会逼改动被显式看见并解释。
 *
 * ## 2026-10-03：混排字距接线后 420 / 160 两档被**显式改写**（相关改动，不是回归）
 *
 * 本类用 `ReaderSettings.DEFAULT` 排版，也就是**生产默认配置**；混排字距（`cjkLatinSpacing`）
 * 默认 25（0.25em）随之生效，而本类语料正是「中文 + 拉丁标识符」的混排形态 ⇒
 * 两条行比关掉时更宽 ⇒ 断点前移。实测（同一份源码、同一批版心）：

 * | 版心 | 混排字距 0 | 混排字距 25（默认，生产） |
 * |---|---|---|
 * | rustLi 420 | `…方法进行 ` ‖ `wrapping，如 wrapping_add` | `…方法进` ‖ `行 wrapping，如 wrapping_add` |
 * | longIdent 160 | `…capacity_check ` ‖ `即可` | `…capacity_` ‖ `check 即可` |
 * | 其余七档 | — | **逐值相同** |
 *
 * 性质没变：行内 `<code>` 的标识符**仍只在 `_` 处断**、**行尾仍不进代码 run**
 * （`noLineEndsWithAHyphenInsideACodeRun` 与 `longIdentifierBreaksOnlyAtUnderscores` 的
 * 不变式部分全绿），变的只是「断在哪个 `_`」。
 *
 * 顺带钉住一条容易被误判的现象：**行尾那个作者空格仍然存在**（360 档的 `"…wrapping_* "`）。
 * 混排字距的「一律删除」只在**边界两侧都在本行**时才吃到那个空格；跨行的边界
 * （`wrapping_*` ‖ `方法`）在行子串上看不到右邻码本 ⇒ 不发间隙、空格照旧。
 *
 * ## 2026-10-03 第二次：标点不再参与 ⇒ **640 那一档被显式改写**（相关改动，不是回归）
 *
 * 真机报「`Kotlin` 与后面的冒号之间也受混排间距影响」⇒ 边界判据收紧为**两侧都必须是字母数字**。
 * 本类语料里唯一受影响的边界是 `wrapping` 的 `g` 与全角逗号 `，`（U+FF0C）之间那一条
 * （旧口径把 `，` 当 CJK ⇒ 发间隙；新口径它是标点 ⇒ 不发）。
 * 那条间隙**不靠空格分隔**，所以它消失 = 断行侧少预留一个 `gap` ⇒ 第一行能多装下 `wrapping_`：
 *
 * | 版心 | 上一版 | 本版 |
 * |---|---|---|
 * | rustLi 640 | `…方法进行 wrapping，如 ` ‖ `wrapping_add` | `…方法进行 wrapping，如 wrapping_` ‖ `add` |
 * | 其余九档 | — | **逐值相同** |
 *
 * ## 480 那一档的 `wrap` ‖ `ping` **不是** bug（写在这里免得后人误"修"）
 *
 * 480 的切点落在**正文的裸词** `wrapping`（码位 `[27,35)`，不在任何 `code` run 内），
 * 是合法的英文音节断词（`hy=true` 也对）。行内代码里的 `wrapping_add` 在这一档里是完整的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineCodeIdentifierBreakEndToEndTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentH = 640

    /** Rust 书真源码（`docs/TODO-未尽事宜.md` Q18 登记的那一行）。 */
    private val rustLi = """
        <html><body><ul>
        <li>所有模式下都可以使用 <code>wrapping_*</code> 方法进行 wrapping，如 <code>wrapping_add</code></li>
        </ul></body></html>
    """.trimIndent()

    /** 窄版心长标识符：Q18② 的决策依据（不注入 `_` 断点时 R1 会硬切在字母中间）。 */
    private val longIdent = """
        <html><body><p>调用 <code>wrapping_add_with_capacity_check</code> 即可</p></body></html>
    """.trimIndent()

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    private data class Laid(val leafText: String, val runs: List<Pair<IntRange, String?>>, val window: Map<Int, DrawLine>)

    private fun lay(html: String, widthPx: Int): Laid {
        val prof = profile()
        val p = layouter.prepare(
            converter.convert(html) ?: error("chapter parse failed"),
            CssBundle(listOf("")), prof, widthPx, contentH,
        )
        val leaf = p.leaves.firstOrNull { it.ranges.isNotEmpty() } ?: error("no leaf with lines")
        val el = leaf.el!!
        val text = NormalFlowLayout.leafText(el, p.styleMap, p.classify, p.hidden)
        val runs = NormalFlowLayout.leafFontRuns(el, p.styleMap, p.classify, p.hidden)
            .map { (it.start until it.endExclusive) to it.tag }
        val layout = layouter.fullLayout(p, prof, widthPx, contentH).layout as PagedLayout
        val win = layout.skiaLineWindow()
        assertNotNull("canonical must carry the skia window", win)
        return Laid(text, runs, win!!)
    }

    /** 行窗里的逐行可见文本（`DrawLine.text` 是整叶文本，`range` 才是这一行）。 */
    private fun DrawLine.visible(): String =
        if (range.isEmpty()) "" else text.substring(range.first, minOf(range.last + 1, text.length))

    private fun visibleLines(l: Laid): List<String> = l.window.toSortedMap().values.map { it.visible() }

    private fun hyphenLines(l: Laid): List<String> =
        l.window.toSortedMap().values.filter { it.hyphenAtEnd }.map { it.visible() }

    // ---- 前提：fontRuns 必须真的带 tag = "code"（Q18 整套机制的地基） ----

    @Test
    fun fontRunsCarryTheCodeTagThatDrivesThePartition() {
        val l = lay(rustLi, 640)
        assertEquals("叶文本（归一化后）", "所有模式下都可以使用 wrapping_* 方法进行 wrapping，如 wrapping_add", l.leafText)
        assertEquals(
            "两个 `<code>` run 的范围与 tag（Q18 判据的输入，改了它整套机制静默失效）",
            listOf((11 until 21) to "code", (38 until 50) to "code"),
            l.runs,
        )
    }

    // ---- 主锁：五档逐值 ----

    @Test
    fun rustSourceIsPinnedAtFiveWidths() {
        assertEquals(
            "版心 360：`wrapping_add` 在 `_` 处断（Q18②），不是被音节切开",
            listOf(
                "所有模式下都可以使用 wrapping_* ",
                "方法进行 wrapping，如 wrapping_",
                "add",
            ),
            visibleLines(lay(rustLi, 360)),
        )
        // 420：**混排字距上线后被显式改写**（行更宽 ⇒ 断点前移一个字），见类 KDoc 的对照表。
        assertEquals(
            "版心 420（混排字距默认 25 下的值；关掉时是 `…方法进行 ` ‖ `wrapping，如 wrapping_add`）",
            listOf(
                "所有模式下都可以使用 wrapping_* 方法进",
                "行 wrapping，如 wrapping_add",
            ),
            visibleLines(lay(rustLi, 420)),
        )
        assertEquals(
            "版心 480（混排字距**不影响**这一档；切点 `wrap|ping` 在**正文裸词**里，是合法音节断词）",
            listOf(
                "所有模式下都可以使用 wrapping_* 方法进行 wrap",
                "ping，如 wrapping_add",
            ),
            visibleLines(lay(rustLi, 480)),
        )
        assertEquals(
            "版心 520（混排字距**不影响**这一档）",
            listOf(
                "所有模式下都可以使用 wrapping_* 方法进行 wrapping，",
                "如 wrapping_add",
            ),
            visibleLines(lay(rustLi, 520)),
        )
        assertEquals(
            "版心 640：**用户报的那一行**。`wrapping_add` 不再完整成行 —— 断点从 `，` 之后" +
                "前移到了 `wrapping_` 之后（原因见类 KDoc「2026-10-03 第二次」那一节）",
            listOf(
                "所有模式下都可以使用 wrapping_* 方法进行 wrapping，如 wrapping_",
                "add",
            ),
            visibleLines(lay(rustLi, 640)),
        )
    }

    // ---- 不变量锁（与字体无关的那一半：连字符不得出现在代码 run 内） ----

    @Test
    fun noLineEndsWithAHyphenInsideACodeRun() {
        for (w in intArrayOf(200, 260, 320, 360, 420, 480, 520, 640, 800, 1200)) {
            val l = lay(rustLi, w)
            for (dl in l.window.toSortedMap().values) {
                if (!dl.hyphenAtEnd) continue
                // ⚠ 切点必须用 `range` 拿，**不能**用 `leafText.indexOf(...)`（那会命中别处的同字符）。
                val cut = dl.range.last + 1
                val insideCode = l.runs.any { (r, tag) -> tag == "code" && cut > r.first && cut <= r.last }
                assertTrue(
                    "版心 $w：行尾不得在行内代码 run 内补连字符（行 [${dl.visible()}]，切点 $cut）",
                    !insideCode,
                )
            }
        }
    }

    /**
     * 窄版心长标识符：**只在 `_` 处断**，不得切在字母中间。
     *
     * 这组四档就是 Q18② 的决策依据：不注入 `_` 断点时同一份输入实测是
     * `wrapping_add_w` ‖ `ith_capacity_c` ‖ `heck` —— 正是用户报的那一类难看。
     */
    @Test
    fun longIdentifierBreaksOnlyAtUnderscores() {
        assertEquals(
            // 160：混排字距上线后切点从「`capacity_check ` ‖ `即可`」前移到「`capacity_` ‖ `check 即可`」
            // （仍然**只在 `_` 之后**，性质未变），见类 KDoc 的对照表。
            "版心 160（混排字距默认 25 下的值；关掉时是 `capacity_check ` ‖ `即可`）",
            listOf("调用 wrapping_", "add_with_", "capacity_", "check 即可"),
            visibleLines(lay(longIdent, 160)),
        )
        assertEquals(
            "版心 200",
            listOf("调用 wrapping_add_", "with_capacity_", "check 即可"),
            visibleLines(lay(longIdent, 200)),
        )
        assertEquals(
            "版心 240",
            listOf("调用 wrapping_add_", "with_capacity_check 即", "可"),
            visibleLines(lay(longIdent, 240)),
        )
        assertEquals(
            "版心 300",
            listOf("调用 wrapping_add_with_", "capacity_check 即可"),
            visibleLines(lay(longIdent, 300)),
        )
    }

    /**
     * 整块 `<pre>` 的既有行为不变：Q18 只改**行内** `<code>` 的接线，
     * `<pre>` 走的仍是同一个 `codeSources()`（本仓实测 Q18 前 `pre` 就已是这个行为）。
     *
     * 断言用「**每一处行尾切点都落在 `_` 之后**」而不是钉死字符串：
     * `<pre>` 的行首缩进、字体回退在不同机器上会挪动切点，但「不按音节断词」这条性质不会。
     */
    @Test
    fun preBlockAlsoBreaksOnlyAtUnderscores() {
        val html = """
            <html><body><pre>let x = wrapping_add_with_capacity_check;
            done</pre></body></html>
        """.trimIndent()
        for (w in intArrayOf(160, 200, 300, 480)) {
            val l = lay(html, w)
            val identStart = l.leafText.indexOf("wrapping_add_with_capacity_check")
            val identEnd = identStart + "wrapping_add_with_capacity_check".length
            assertTrue("样本里必须找得到标识符（版心 $w）", identStart >= 0)
            val badCuts = l.window.toSortedMap().values
                .map { it.range.last + 1 }
                .filter { it > identStart && it < identEnd }
                .filter { l.leafText[it - 1] != '_' }
            assertEquals("版心 $w：`<pre>` 内的切点必须都在 `_` 之后，实得 $badCuts", emptyList<Int>(), badCuts)
            assertEquals("版心 $w：`<pre>` 里不得补连字符", emptyList<String>(), hyphenLines(l))
        }
    }
}
