package orilumn.reader.engine.laying

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure 2D grid-model tests: column counting, colspan/rowspan occupancy, row char ranges. */
class TableGridModelTest {

    private val converter = HtmlTreeConverter()

    private fun tableOf(rows: List<String>): TableGridModel {
        val root = converter.convert("<table>${rows.joinToString("")}</table>")!!
        return TableGridModel.build(root.children.first { it.tag == "table" })
    }

    @Test
    fun `simple grid has one column per max row`() {
        val m = tableOf(listOf("<tr><td>甲</td><td>乙</td></tr>", "<tr><td>丙</td><td>丁</td></tr>"))
        assertEquals(2, m.columnCount)
        assertEquals(2, m.rows.size)
        // Each row's two cells at columns 0..1 with a plain row char range.
        assertEquals(listOf("甲", "乙"), m.rows[0].cells.map { it.el.children.first { n -> n.isText }.text })
        assertEquals(listOf(0, 1), m.rows[0].cells.map { it.col })
        assertEquals(2, m.rows[0].len) // 甲 + 乙
        assertEquals(2, m.rows[1].startChar)
    }

    @Test
    fun `colspan occupies multiple columns and skips them in later cells`() {
        val m = tableOf(listOf("<tr><td colspan='2'>合并</td></tr>", "<tr><td>丙</td><td>丁</td></tr>"))
        assertEquals(2, m.columnCount)
        val (cell) = m.rows[0].cells
        assertEquals(0, cell.col); assertEquals(2, cell.colSpan)
        // Second row's cells land at columns 0 and 1 (nothing spanning into them).
        assertEquals(listOf(0, 1), m.rows[1].cells.map { it.col })
    }

    @Test
    fun `rowspan shifts later cells past the occupied column`() {
        val m = tableOf(listOf(
            "<tr><td rowspan='2'>跨行</td><td>甲</td></tr>",
            "<tr><td>乙</td></tr>", // first column is occupied vertically → second cell must go to col 1
        ))
        assertEquals(2, m.columnCount)
        assertEquals(0, m.rows[0].cells[0].col); assertEquals(2, m.rows[0].cells[0].rowSpan)
        assertEquals(1, m.rows[1].cells[0].col) // placed in the free column
    }

    @Test
    fun `row char ranges are cumulative across rows`() {
        val m = tableOf(listOf("<tr><td>ab</td><td>cd</td></tr>", "<tr><td>e</td></tr>"))
        assertEquals(4, m.rows[0].len)
        assertEquals(0, m.rows[0].startChar)
        assertEquals(4, m.rows[1].startChar)
        assertEquals(4 + 1, m.totalChars)
    }

    @Test
    fun `thead and tbody rows are collected in order`() {
        val m = tableOf(listOf("<thead><tr><th>头</th></tr></thead><tbody><tr><td>体</td></tr></tbody>"))
        assertEquals(2, m.rows.size)
        assertEquals(true, m.rows[0].cells[0].isHeader)
        assertEquals(false, m.rows[1].cells[0].isHeader)
    }

    @Test
    fun `caption is collected`() {
        val m = tableOf(listOf("<caption>题注</caption><tr><td>甲</td></tr>"))
        assertEquals("题注", m.caption?.children?.first { n -> n.isText }?.text)
        assertEquals(1, m.rows.size)
    }

    @Test
    fun `auto cell pref counts padding and border (border-box)`() {
        // 浏览器 auto 布局：单元格 max/min-content = 文本 + 自身 padding + border。
        val withEdges = TableGridModel.cellPref(0, 1, 20f, 20f, 4f)
        assertEquals(24f, withEdges.pref, 1e-4f) // 20（文本）+ 4（边距）
        assertEquals(24f, withEdges.min, 1e-4f)
        // min 钳到 max：最长不可断单元不可能宽于整段（浏览器不变量）。
        val clamped = TableGridModel.cellPref(0, 1, 20f, 99f, 0f)
        assertEquals(20f, clamped.min, 1e-4f)
        // 空文本格仍有 border-box 宽度（浏览器空单元格不塌到 0）。
        val blank = TableGridModel.cellPref(0, 1, 0f, 0f, 4f)
        assertEquals(4f, blank.pref, 1e-4f)
        assertEquals(4f, blank.min, 1e-4f)
    }

    @Test
    fun `auto does not stretch past max-content (browser regime 3)`() {
        // 浏览器三段式第三段：MAX < 容器 → 表宽 = MAX，列宽 = max-content，不撑满。
        val prefs = listOf(
            TableGridModel.CellPref(0, 1, 5f, 5f),
            TableGridModel.CellPref(1, 1, 55f, 10f),
        )
        val (xs, ws) = TableGridModel.autoColumnLayout(300, 2, 0f, 0, prefs)
        assertEquals(5, ws[0])
        assertEquals(55, ws[1])
        assertEquals(60, ws.sum()) // 不再是 300
        assertEquals(0, xs[0])
        assertEquals(5, xs[1])
    }

    @Test
    fun `auto interpolates by max-minus-min between MIN and MAX (Chrome-verified)`() {
        // Chrome 实测矩阵（monospace 20px）：min=[24,72,120]，max=[348,576,780]，MIN=216，MAX=1704。
        val prefs = listOf(
            TableGridModel.CellPref(0, 1, 348f, 24f),
            TableGridModel.CellPref(1, 1, 576f, 72f),
            TableGridModel.CellPref(2, 1, 780f, 120f),
        )
        // 中间段：w_i = min_i + (avail-MIN)*(max_i-min_i)/(MAX-MIN)。
        val (_, ws1000) = TableGridModel.autoColumnLayout(1000, 3, 0f, 0, prefs)
        assertEquals(1000, ws1000.sum())
        assertEquals(listOf(195, 338, 467), ws1000.toList()) // Chrome 194.6 / 337.5 / 467.8
        // 第一段：可用宽 < MIN → 列宽 = min，表溢出（不压缩）。
        val (_, ws100) = TableGridModel.autoColumnLayout(100, 3, 0f, 0, prefs)
        assertEquals(listOf(24, 72, 120), ws100.toList())
        assertEquals(216, ws100.sum())
        // 第三段：可用宽 > MAX → 列宽 = max，表不撑满。
        val (_, ws2000) = TableGridModel.autoColumnLayout(2000, 3, 0f, 0, prefs)
        assertEquals(listOf(348, 576, 780), ws2000.toList())
        assertEquals(1704, ws2000.sum())
    }

    @Test
    fun `auto shrinks proportionally when overflowing`() {
        val prefs = listOf(
            TableGridModel.CellPref(0, 1, 400f, 100f),
            TableGridModel.CellPref(1, 1, 200f, 50f),
        )
        val (_, ws) = TableGridModel.autoColumnLayout(300, 2, 0f, 0, prefs)
        assertEquals(300, ws.sum())
        org.junit.Assert.assertTrue("first col keeps larger share: ${ws.toList()}", ws[0] > ws[1])
    }

    // ---- rowspan row-height distribution (Chrome-verified) ----

    @Test
    fun `rowspan deficit is distributed proportionally to the spanned rows`() {
        // row0 own 100, row1 own 50; the rowspan=2 cell (200) needs 50 more, split 100:50.
        val h = TableGridModel.resolveRowHeights(
            listOf(
                listOf(TableGridModel.CellHeight(2, 200), TableGridModel.CellHeight(1, 100)),
                listOf(TableGridModel.CellHeight(1, 50)),
            ),
        )
        assertEquals(listOf(133, 67), h.toList())
        assertEquals(200, h.sum()) // sum(spanned) == rowspan cell height
    }

    @Test
    fun `rowspan cell equalises equally tall rows`() {
        val h = TableGridModel.resolveRowHeights(
            listOf(
                listOf(TableGridModel.CellHeight(2, 200), TableGridModel.CellHeight(1, 20)),
                listOf(TableGridModel.CellHeight(1, 20)),
            ),
        )
        assertEquals(listOf(100, 100), h.toList())
    }

    @Test
    fun `rowspan=3 spreads over all three spanned rows`() {
        val h = TableGridModel.resolveRowHeights(
            listOf(
                listOf(TableGridModel.CellHeight(1, 100), TableGridModel.CellHeight(3, 400), TableGridModel.CellHeight(1, 30)),
                listOf(TableGridModel.CellHeight(1, 30), TableGridModel.CellHeight(1, 70)),
                listOf(TableGridModel.CellHeight(1, 50), TableGridModel.CellHeight(1, 10)),
            ),
        )
        assertEquals(400, h.sum())
        assertEquals(181, h[0])
        assertEquals(127, h[1])
        assertEquals(92, h[2])
    }

    @Test
    fun `rowspan shorter than the spanned rows is a no-op`() {
        val h = TableGridModel.resolveRowHeights(
            listOf(
                listOf(TableGridModel.CellHeight(2, 100), TableGridModel.CellHeight(1, 120)),
                listOf(TableGridModel.CellHeight(1, 50)),
            ),
        )
        assertEquals(listOf(120, 50), h.toList())
    }

    @Test
    fun `a rowspan cell does not inflate the first of its spanned rows`() {
        // 首行自身只有 120，跨行格 400 摊到两行后首行应显著小于 400。
        val h = TableGridModel.resolveRowHeights(
            listOf(
                listOf(TableGridModel.CellHeight(2, 400)),
                listOf(TableGridModel.CellHeight(1, 60)),
            ),
        )
        org.junit.Assert.assertTrue("first row must stay below the rowspan cell: ${h.toList()}", h[0] < 400)
        assertEquals(400, h.sum())
    }
}