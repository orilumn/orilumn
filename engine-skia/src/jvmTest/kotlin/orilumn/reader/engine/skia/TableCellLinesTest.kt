package orilumn.reader.engine.skia

import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.laying.ShapeFontRequest
import orilumn.reader.engine.laying.TableCellLayout
import orilumn.reader.engine.laying.TableRowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表格行展开进窗：单元格文本成行（章内基址连续）+ 边框矩形；空形/隐藏空格跳过。 */
class TableCellLinesTest {

    private class StubShape(
        override val shapeText: String,
        private val ranges: List<IntRange>,
        private val heights: List<Int>,
        override val shapeFontSizePx: Float = 10f,
    ) : ParagraphShapeRef {
        private val tops: IntArray = IntArray(ranges.size).also { arr ->
            var y = 0
            for (i in ranges.indices) {
                arr[i] = y
                y += heights.getOrElse(i) { 1 }
            }
        }
        override val shapeLineCount: Int get() = ranges.size
        override fun shapeLineStart(k: Int): Int = ranges[k].first
        override fun shapeLineEnd(k: Int): Int = ranges[k].last + 1
        override fun shapeLineTop(k: Int): Int = tops[k]
        override fun shapeLineBottom(k: Int): Int = tops[k] + heights.getOrElse(k) { 1 }
        override val shapeAlignment: TextAlign get() = TextAlign.LEFT
        override val shapeColorRuns get() = emptyList<orilumn.reader.engine.css.ColorRun>()
        override val shapeFontRuns get() = emptyList<orilumn.reader.engine.css.FontRun>()
        override val shapeBaselineShifts get() = emptyList<orilumn.reader.engine.laying.BaselineShift>()
        override val shapeRubyRuns get() = emptyList<orilumn.reader.engine.laying.RubyRun>()
        override val shapeUnderlineRuns get() = emptyList<orilumn.reader.engine.laying.UnderlineRun>()
        override val shapeAlpha: Float get() = 1f
        override val shapeEmphasis get() = orilumn.reader.engine.css.EmphasisStyle.NONE
        override val shapeEmphasisUnder: Boolean get() = false
        override val shapeTextShadow get() = null
        override val isReplaceable: Boolean get() = false
        override var replaceableBottom: Int = 0
        override val replaceableCharEnd: Int get() = 1
        override val listMarker get() = null
        override val fontRequest get() = ShapeFontRequest(null, emptyList(), 400, false, false)
    }

    private fun cell(tag: String, text: String, x: Int, w: Int, shape: ParagraphShapeRef?): TableCellLayout =
        TableCellLayout(MarkupElement(tag), 0, 1, x, w, 20, tag == "th", shape)

    private val style = ComputedStyle(10f, 1.5f)

    @Test
    fun `cells expand with continuous charBase`() {
        val s1 = StubShape("項目iBooks", listOf(0..3, 4..7), listOf(15, 15))
        val s2 = StubShape("Readium", listOf(0..6), listOf(15))
        val table = TableRowLayout(intArrayOf(0, 300), intArrayOf(300, 300), listOf(cell("th", "x", 0, 300, s1), cell("td", "x", 300, 300, s2)))
        val win = TableCellLines.expand(table, 100, 30, 1000, { style }, style, 0f)
        assertEquals(3, win.lines.size)
        assertEquals(100, win.lines[0].yTop)
        assertEquals(115, win.lines[1].yTop)
        assertEquals(100, win.lines[2].yTop)
        // 行首 + 格累计：格1全文 8 字占 [1000,1008)，格2起 1008。
        assertEquals(1000, win.lines[0].charBase)
        assertEquals(1000, win.lines[1].charBase)
        assertEquals(1008, win.lines[2].charBase)
        assertEquals(0..3, win.lines[0].range)
        assertEquals(4..7, win.lines[1].range)
        // 边框：两格 × 行带。
        assertEquals(2, win.borders.size)
        assertEquals(100, win.borders[0].yTop)
        assertEquals(130, win.borders[0].yBottom)
        assertTrue(win.borders.all { it.border && it.strokeWidthPx == 1f })
    }

    @Test
    fun `null shape and hidden empty cell are skipped`() {        val table = TableRowLayout(
            intArrayOf(0), intArrayOf(300),
            listOf(cell("td", "x", 0, 300, null)),
            emptyCellsHide = true,
        )
        val win = TableCellLines.expand(table, 0, 15, 0, { style }, style, 0f)
        assertTrue(win.lines.isEmpty())
        assertTrue(win.borders.isEmpty())
        val empty = StubShape("", emptyList(), emptyList())
        val table2 = TableRowLayout(intArrayOf(0), intArrayOf(300), listOf(cell("td", "x", 0, 300, empty)), emptyCellsHide = true)
        val win2 = TableCellLines.expand(table2, 0, 15, 0, { style }, style, 0f)
        assertTrue(win2.lines.isEmpty())
        assertTrue(win2.borders.isEmpty())
    }

    @Test
    fun `cell border follows css border-color`() {
        val s = StubShape("x", listOf(0..0), listOf(15))
        val table = TableRowLayout(intArrayOf(0), intArrayOf(300), listOf(cell("td", "x", 0, 300, s)))
        // 声明了 border-color → 用 CSS 色（internallinks 的 #c0c0c0 场景）。
        val declared = ComputedStyle(10f, 1.5f, colorHex = "#ff111111", borderColors = BorderColorEdges("#ffc0c0c0"))
        assertEquals(0xFFC0C0C0.toInt(), TableCellLines.expand(table, 0, 15, 0, { declared }, declared, 0f).borders[0].argb)
        // 未声明 → currentColor（本元素文字色，CSS 默认）。
        val current = ComputedStyle(10f, 1.5f, colorHex = "#ff222222")
        assertEquals(0xFF222222.toInt(), TableCellLines.expand(table, 0, 15, 0, { current }, current, 0f).borders[0].argb)
        // 连文字色都没有 → 回退遗留中性灰。
        assertEquals(TableCellLines.BORDER_ARGB, TableCellLines.expand(table, 0, 15, 0, { style }, style, 0f).borders[0].argb)
    }

    @Test
    fun `rowspan border spans covered rows`() {
        // 表1·1 形：首行 4 格 rowspan=2 + 1 普通格，次行 1 格；跨行格边框直画到末行底。
        val span = StubShape("項目", listOf(0..1), listOf(15))
        val top = StubShape("MS", listOf(0..1), listOf(15))
        val bot = StubShape("朝", listOf(0..0), listOf(15))
        fun spanCell(): TableCellLayout =
            TableCellLayout(MarkupElement("th"), 0, 1, 0, 150, 15, true, span, 2)
        val row0 = TableRowLayout(intArrayOf(0, 150), intArrayOf(150, 150), listOf(spanCell(), TableCellLayout(MarkupElement("th"), 1, 1, 150, 150, 15, true, top)))
        val row1 = TableRowLayout(intArrayOf(0, 150), intArrayOf(150, 150), listOf(TableCellLayout(MarkupElement("th"), 1, 1, 150, 150, 15, true, bot)))
        val tableEl = MarkupElement("table")
        val win = TableCellLines.expandTable(
            listOf(
                TableCellLines.RowFrame(row0, 0, 0, 15, 0, style, tableEl),
                TableCellLines.RowFrame(row1, 1, 15, 15, 2, style, tableEl),
            ),
            { style }, 0f,
        )
        // 文本：首行两格 + 次行一格，基址连续。
        assertEquals(2, win.lines[0]!!.size)
        assertEquals(1, win.lines[1]!!.size)
        // 边框：跨行格 0..30，普通格各守本行带。
        val spanned = win.borders.first { it.left == 0 }
        assertEquals(0, spanned.yTop)
        assertEquals(30, spanned.yBottom)
        val single = win.borders.first { it.left == 150 && it.yTop == 0 }
        assertEquals(15, single.yBottom)
    }
}
