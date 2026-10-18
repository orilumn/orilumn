package orilumn.reader.engine

import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.TableCellBlock
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E15 断行侧的**重轻量画同源锁**：格内行内盒的横向 `padding`/`border` 必须折进
 * **重路径**格内断行（`buildTableRows` → `breakLeafLines` 收
 * [TableCellBlock.inlineBoxRuns]），与轻路径塑形（`shapeGeometry` 一直自收同源 runs）同账。
 *
 * ## 缺陷本体
 *
 * 修复前重路径格内断行**不传 runs**：行高预算按「行内盒没有横向内边距」切行，
 * 与轻路径塑形（有内边距 ⇒ 切得更碎）分家 ⇒ 同一格重轻行数不同、行高预算偏小。
 *
 * ## 宽度为什么自适应（`wFit` 二分）
 *
 * 断言「第几行」必须先知道断点阈值，而阈值 = 文本实测宽 ＋ 内边距 ＋ 缩进等各家加项，
 * **随字体/字号漂移，不能写死**。故先二分出「重路径恰好单行」的最小版心 `wFit`
 * （它把所有加项都吸收进去），再在 `wFit - 30` 处取点：
 *  - 不计内边距（修复前）：文本宽 ≤ `wFit - 30` ⇒ 单行；
 *  - 计内边距（修复后）：文本宽 + 60 > `wFit - 30` ⇒ 必须断成 2 行。
 * 两侧差 30px，远小于 60px 内边距 ⇒ 对字体度量误差稳健。
 *
 * 版式钉死 `table-layout: fixed`：auto 会把列按内容回撑（量/排互喂，测不出差）。
 */
class TableCellInlineBoxBreakHeavyLightTest {

    private val contentH = 3000
    private val chapterHref = "OEBPS/Text/Ch01.htm"

    private val text = "wrap me around the page with many small words to break cleanly"

    private val html = """
        <html><body><table>
        <tr><td>PRE<span style="padding:0 30px;background:#ffe082">$text</span></td></tr>
        </table></body></html>
    """.trimIndent()

    private val authorCss =
        "table{table-layout:fixed;border-collapse:collapse;border-spacing:0} td{padding:0;border:0}"

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"))

    private fun layouter(): BoxChapterLayouter =
        BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile()))
            .also { it.bindChapterContext(chapterHref) }

    private fun root() = HtmlTreeConverter().convert(html)!!

    /** 重路径在版心 [w] 下的格内单块（新起 layouter，避免跨版心状态串扰）。 */
    private fun heavyBlockAt(w: Int): TableCellBlock {
        val h = layouter().prepare(root(), CssBundle(listOf(authorCss)), profile(), w, contentH)
        val row = h.leaves.firstOrNull { it.table != null } ?: error("重路径没找到表格行")
        return row.table!!.cells.single().blocks.single()
    }

    @Test
    fun `重路径格内断行折入行内盒内边距且与轻路径行数一致`() {
        // 二分求「重路径恰好单行」的最小版心 wFit（吸收文本实测宽＋各家加项）。
        var lo = 40
        var hi = 4096
        assertTrue("宽版心下应单行（版心过窄或文本过长会破此前提）", heavyBlockAt(hi).ranges.size == 1)
        assertTrue("窄版心下必须多行", heavyBlockAt(lo).ranges.size >= 2)
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (heavyBlockAt(mid).ranges.size == 1) hi = mid else lo = mid
        }
        val wFit = hi
        val w = wFit - 30

        val heavy = heavyBlockAt(w)
        assertEquals(
            "行内盒横向内边距没折进断行宽（少传 run）：版心 $w 比单行阈值只窄 30px，" +
                "而盒内边距 60px —— 不计内边距就会按单行放行。",
            2,
            heavy.ranges.size,
        )

        // 轻路径同一版心塑形取行数：与重路径必须逐行同账。
        val light = layouter()
        val prep = light.prepareLight(root(), CssBundle(listOf(authorCss)), profile(), w, ChapterStructureCache(), contentH)
        val idx = (0 until prep.totalBlocks).firstOrNull { prep.block(it).table != null }
            ?: error("轻路径没找到表格行")
        light.tempShape(null, prep, idx, profile())
        val lightBlock = prep.block(idx).table!!.cells.single().blocks.single()
        assertEquals(
            "重轻两路格内行数必须一致（量画同源）：重=${heavy.ranges.size} 轻=${lightBlock.shape?.lineCount}",
            heavy.ranges.size,
            lightBlock.shape!!.lineCount,
        )
    }
}
