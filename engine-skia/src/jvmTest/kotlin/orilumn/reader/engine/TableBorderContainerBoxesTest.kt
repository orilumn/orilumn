package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.DrawKind
import orilumn.reader.engine.laying.FlowedLine
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核层回归：只带边框、无背景的容器（Rust 书全横线表格：`table{border-bottom}`）
 * 在轻量路径必须有边框盒，否则表底边无处画（`7bdfc9f` 把属主改背景-only 后的回归）。
 * 经 [BoxChapterLayouter.buildBackgroundDrawBoxes]（增量/临时窗同源）+ [BoxDrawer] 端到端。
 */
class TableBorderContainerBoxesTest {

    private val html = """
        <html><body>
        <div class="table-wrapper"><table>
        <thead><tr><th>长度</th><th>有符号</th></tr></thead>
        <tbody>
        <tr><td>8-bit</td><td><code>i8</code></td></tr>
        <tr><td>16-bit</td><td><code>i16</code></td></tr>
        </tbody>
        </table></div>
        <p>后文。</p>
        </body></html>
    """.trimIndent()

    private val css = """
        * { margin: 0; padding: 0; border: 0; }
        table { width: 90%; margin: 1rem auto; border-collapse: collapse; border-bottom: 1px solid #000; }
        .table-wrapper { width: 80%; margin: 0 auto; }
        th, td { border-top: 1px solid #000; text-align: left; padding: 0.3em 0.5em; }
    """.trimIndent()

    @Test
    fun `border-only table gets a border box spanning its rows`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(useOriginalStyle = true))
        val layouter = BoxChapterLayouter()
        val root = HtmlTreeConverter().convert(html)!!
        val bundle = CssBundle(listOf(css))
        val structure = ChapterStructureCache()
        val prepare = layouter.prepareLight(root, bundle, profile, 1000, structure, 1400)

        val leaves = prepare.blocks(0, prepare.totalBlocks)
        assertTrue("表行叶应存在", leaves.isNotEmpty())
        // 每叶一行：y 递增，行高 20。
        val lines = leaves.indices.map { i ->
            FlowedLine(charStart = i * 10, charEnd = i * 10 + 9, yTop = i * 30, yBottom = i * 30 + 20, paragraphStart = true)
        }
        val first = IntArray(leaves.size) { it }
        val last = IntArray(leaves.size) { it + 1 }
        val boxes = layouter.buildBackgroundDrawBoxes(prepare, leaves, lines, first, last)

        val tableBox = boxes.singleOrNull { it.el?.tag == "table" }
            ?: error("表容器边框盒缺失，boxes=${boxes.map { it.el?.tag }}")
        // 跨行：顶在首行上（含自身上 edge 0），底在末行下 + 自身下边框。
        assertTrue(tableBox.contentBottom > tableBox.contentTop)

        // 经 BoxDrawer：底边带落在表盒底、表宽内（无竖框口径由 Ferris 端到端锁，这里锁底边存在）。
        val rects = BoxDrawer.draw(listOf(tableBox)).filter { it.kind == DrawKind.BORDER }
        assertTrue("表底边缺失", rects.any { it.bottom == tableBox.contentBottom && it.right <= tableBox.contentLeft + tableBox.contentWidth })
    }

    @Test
    fun `table with its own background is not duplicated`() {
        // 自带背景的表只出背景盒（画自身边框），不另出边框盒。
        val cssBg = css + "\ntable { background-color: #eeeeee; }"
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(useOriginalStyle = true))
        val layouter = BoxChapterLayouter()
        val root = HtmlTreeConverter().convert(html)!!
        val structure = ChapterStructureCache()
        val prepare = layouter.prepareLight(root, CssBundle(listOf(cssBg)), profile, 1000, structure, 1400)
        val leaves = prepare.blocks(0, prepare.totalBlocks)
        val lines = leaves.indices.map { i ->
            FlowedLine(charStart = i * 10, charEnd = i * 10 + 9, yTop = i * 30, yBottom = i * 30 + 20, paragraphStart = true)
        }
        val boxes = layouter.buildBackgroundDrawBoxes(
            prepare, leaves, lines, IntArray(leaves.size) { it }, IntArray(leaves.size) { it + 1 },
        )
        assertEquals("自带背景的表只应有一个盒", 1, boxes.count { it.el?.tag == "table" })
    }
}
