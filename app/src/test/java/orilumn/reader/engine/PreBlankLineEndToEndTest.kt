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
 * **Q16 端到端锁**：`<pre><code>` 里的**空行**在整条链路上真的占一行，且 canonical / 增量（disk-hit）
 * 两路逐行同文同位。
 *
 * ## 缺陷本体（用户真机报「`pre code` 块里的空行全部消失」）
 *
 * 断行器把「连续硬换行之间的空段」整个丢了：`pre` 被 [orilumn.reader.engine.css.StyleComputer.resolveWhiteSpace]
 * 降级成 `PRE_WRAP`（分页阅读器无横向滚动，见 Q13）⇒ 一定走断行器那条路，而断行器**两侧**都丢。
 * 仓内**早就有正确的那份实现** —— [orilumn.reader.engine.laying.breakLeafLines] 不折行分支一直为
 * 空段产零宽行；只有 `pre` 走不到它。本锁同时钉「两条分支可见文本一致」。
 *
 * 单测层的锁在 [orilumn.reader.engine.skia.WhiteSpaceBlankLineParityTest]（断行器 × white-space 分支）；
 * 本锁验**整条链路**：归一化后的叶文本、断行区间、行几何、行窗投影，三者对得上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreBlankLineEndToEndTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    private fun prepare(html: String, css: String = ""): ChapterPrepareResult =
        layouter.prepare(converter.convert(html) ?: error("chapter parse failed"), CssBundle(listOf(css)), profile(), contentW, contentH)

    /** 某叶在行窗里逐行的可见文本（`DrawLine.text` 是整叶文本，`range` 才是这一行）。 */
    private fun DrawLine.visible(): String =
        if (range.isEmpty()) "" else text.substring(range.first, minOf(range.last + 1, text.length))

    /** canonical 全章重排的行窗。 */
    private fun canonicalWindow(p: ChapterPrepareResult): Map<Int, DrawLine> {
        val layout = layouter.fullLayout(p, profile(), contentW, contentH).layout as PagedLayout
        val window = layout.skiaLineWindow()
        assertNotNull("canonical must carry the skia window", window)
        return window!!
    }

    /** 增量（disk-hit）路径的行窗（窗口局部坐标）。 */
    private fun incrementalWindow(html: String, css: String = ""): Map<Int, DrawLine> {
        val prof = profile()
        val canonical = layouter.fullLayout(prepare(html, css), prof, contentW, contentH)
        val light = layouter.prepareLight(
            converter.convert(html) ?: error("chapter parse failed"),
            CssBundle(listOf(css)), prof, contentW, ChapterStructureCache(), contentH,
        )
        val table = ChapterPaginationTable.fromSlices(0, 1L, canonical.slices, light.totalBlocks, light.totalChars)
        val layout = layouter.incrementalLayoutForPage(light, prof, contentW, contentH, table, 0, pagesToShape = 4)
            .layout as PagedLayout
        return layout.skiaLineWindow() ?: emptyMap()
    }

    /** 一章里全部文本叶的逐行可见文本（按行序）。 */
    private fun visibleLines(window: Map<Int, DrawLine>): List<String> =
        window.entries.sortedBy { it.key }.map { it.value.visible() }

    /**
     * 正向：`a();` / 空行 / `b();` 三行，空行**占一整行高**（几何证明它不是「一个 0 高的空行」）。
     */
    @Test
    fun preCodeKeepsBlankLineInCanonicalWindow() {
        val p = prepare(
            """
            <html><body><pre><code>a();

b();
</code></pre></body></html>
            """.trimIndent(),
        )
        val window = canonicalWindow(p)

        // 叶文本：换行必须活到断行器（`pre` 子树里 `pre-wrap` 保留换行）。
        val preLeaf = p.leaves.firstOrNull { it.ranges.isNotEmpty() && it.el?.tag in setOf("pre", "code") }
            ?: error("no pre/code leaf with ranges")
        val leafText = NormalFlowLayout.leafText(preLeaf.el!!, p.styleMap, p.classify, p.hidden)
        assertEquals("叶文本必须原样保留换行（含空行那一处）", "a();\n\nb();\n", leafText)

        // 断行区间：3 行，第 2 行是**零宽**区间（首末字符位重合）。
        assertEquals("断行应 3 行（空行占一行）", 3, preLeaf.ranges.size)
        assertTrue("第 2 行必须是零宽空行区间", preLeaf.ranges[1].isEmpty())

        // 行窗：逐行可见文本 = ["a();", "", "b();"]，且窗口覆盖这三行。
        val first = preLeaf.firstLineIndex
        assertEquals(listOf("a();", "", "b();"), (0 until 3).map { window[first + it]?.visible() })
        assertEquals("DrawLine 的 range 必须就是叶的断行区间", preLeaf.ranges, (0 until 3).map { window[first + it]!!.range })

        // 几何：空行有高度，且三行等距（yTop 逐行 +固定行高）。
        val layout = layouter.fullLayout(p, profile(), contentW, contentH).layout as PagedLayout
        val tops = (0 until 3).map { layout.getLineTop(first + it) }
        val lh = tops[1] - tops[0]
        assertTrue("行高必须为正（空行不是 0 高）", lh > 0)
        assertEquals("三行应等距（行高 $lh）", listOf(0, lh, 2 * lh), tops.map { it - tops[0] })
        for (i in 0 until 3) {
            val dl = window[first + i]!!
            assertEquals("第 ${i + 1} 行 yTop", layout.getLineTop(first + i), dl.yTop)
            assertEquals("第 ${i + 1} 行 yBottom", layout.getLineBottom(first + i), dl.yBottom)
            assertTrue("第 ${i + 1} 行必须有高度", dl.yBottom > dl.yTop)
        }
    }

    /**
     * 增量（disk-hit，实际翻页走的那条）必须逐行同文 —— 空行在轻路径也不能少。
     * 轻路径的断行只发生在塑形那一步，故这条独立于 canonical 的区间断言。
     */
    @Test
    fun preCodeKeepsBlankLineOnIncrementalPath() {
        val html = """
            <html><body><pre><code>a();

b();
</code></pre></body></html>
        """.trimIndent()
        val incr = incrementalWindow(html)
        assertTrue("增量路径必须产出文本行窗（got ${incr.size}）", incr.isNotEmpty())
        assertEquals(listOf("a();", "", "b();"), visibleLines(incr).filter { it == "a();" || it == "b();" || it == "" })
    }

    /**
     * 两条 `white-space` 分支的**可见文本一致**：`pre`（被降级成 pre-wrap ⇒ 走断行器）与
     * `white-space: pre`（不折行 ⇒ `breakLeafLines` 自己切段）必须给出同一屏字。
     * 这正是缺陷的形状：仓内正确的那份实现永远轮不到，只有两条分支可见文本一致才谈得上「保留」。
     */
    @Test
    fun preWrapAndNoWrapPathsAgreeOnVisibleText() {
        fun text(html: String): List<String> {
            val p = prepare(html)
            val layout = layouter.fullLayout(p, profile(), contentW, contentH).layout as PagedLayout
            return layout.debugPageText(0, layout.lineCount).split('\n').dropLastWhile { it.isEmpty() }
        }
        val preTag = text("<html><body><pre><code>a();\n\nb();\n</code></pre></body></html>")
        val noWrap = text("<html><body><div style=\"white-space:pre\">a();\n\nb();\n</div></body></html>")
        assertEquals("pre 与 white-space:pre 的可见文本必须逐行一致", preTag, noWrap)
        assertEquals("三条可见行（中间是空行）", listOf("a();", "", "b();"), preTag)
    }

    /** `<br><br>` 在 `normal` 语境下同样是空行（`<br>` 折成 `\n`，断行器只认字符）。 */
    @Test
    fun consecutiveBreProduceBlankLineInNormalText() {
        val p = prepare("<html><body><p>a<br/><br/>b</p></body></html>")
        val window = canonicalWindow(p)
        val leaf = p.leaves.firstOrNull { it.ranges.isNotEmpty() } ?: error("no text leaf")
        val first = leaf.firstLineIndex
        assertEquals("断行应 3 行", 3, leaf.ranges.size)
        assertTrue("第 2 行必须是零宽空行区间", leaf.ranges[1].isEmpty())
        assertEquals(listOf("a", "", "b"), (0 until 3).map { window[first + it]?.visible() })
    }
}