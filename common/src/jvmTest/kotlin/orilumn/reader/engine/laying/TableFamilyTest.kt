package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 表格族 2D 消费 guard：border-spacing 列几何＋行间隔、collapse 归零、
 * caption 叶序、重轻双路字符一致。
 */
class TableFamilyTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private class FakeBreaker : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            return if (text.isEmpty()) emptyList() else listOf(BrokenLine(0 until text.length, h))
        }
    }

    /** 每字符一行：文本长度直接决定行数，便于构造可控的跨行高度差。 */
    private class PerCharBreaker : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            return List(text.length) { i -> BrokenLine(i until i + 1, h) }
        }
    }

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun tableTree(): MarkupElement {
        val tr = node("tr", children = listOf(
            node("td", children = listOf(text("a"))),
            node("td", children = listOf(text("b"))),
        ))
        return node("body", children = listOf(node("table", children = listOf(
            node("caption", children = listOf(text("Title"))),
            tr,
        ))))
    }

    private fun rowsOf(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>, breaker: ParagraphBreaker = FakeBreaker()): List<LayoutBox> {
        val cl = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        val result = BoxLayouter(10f, breaker).layoutBoxes(root, 300, styleMap, cl)
        val out = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) { for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
        walk(result.boxes)
        return out
    }

    @Test
    fun `columnLayout 均分加间隔`() {
        val (xs, ws) = TableGridModel.columnLayout(300, 2, 6f, 0)
        // 可用宽 300-18=282，均分 141；列 x = 6, 6+141+6=153。
        assertEquals(listOf(141, 141), ws.toList())
        assertEquals(listOf(6, 153), xs.toList())
        val (xs0, ws0) = TableGridModel.columnLayout(300, 2, 0f, 10)
        assertEquals(listOf(150, 150), ws0.toList())
        assertEquals(listOf(10, 160), xs0.toList())
    }

    @Test
    fun `border-spacing 落到行列几何`() {
        val root = tableTree()
        val plain = rowsOf(root, styles(root))
        val spaced = rowsOf(root, styles(root, "table { border-spacing: 6px }"))
        // caption + 1 行。
        assertEquals(listOf("caption", "tr"), spaced.map { it.el?.tag })
        val row0 = spaced.first { it.el?.tag == "tr" }
        val t = row0.table!!
        // 测试断行器下每格 max-content = 1 字 x 10px（三段式第三段：内容窄于可用宽时不拉伸）。
        // 列 x 各留 gap：6, 6+10+6 = 22。
        assertEquals(listOf(6, 22), t.columnXs.toList())
        assertEquals(listOf(10, 10), t.columnWidths.toList())
        // 行高 = 单元格行高 + 纵向间隔 6。
        val plainRow = plain.first { it.el?.tag == "tr" }
        assertEquals(plainRow.replaceableHeight + 6, row0.replaceableHeight)
        assertTrue("无 empty-cells:hide 时默认 false", !t.emptyCellsHide)
    }

    @Test
    fun `collapse 强制间距归零`() {
        val root = tableTree()
        val boxes = rowsOf(root, styles(root, "table { border-spacing: 6px; border-collapse: collapse }"))
        val row = boxes.first { it.el?.tag == "tr" }
        assertEquals(listOf(0, 10), row.table!!.columnXs.toList())
        // collapse 只清间距，行高即单元格行高。
        val plain = rowsOf(root, styles(root)).first { it.el?.tag == "tr" }
        assertEquals(plain.replaceableHeight, row.replaceableHeight)
    }

    @Test
    fun `caption 默认置顶且计入字符流`() {
        val root = tableTree()
        val styleMap = styles(root)
        val boxes = rowsOf(root, styleMap)
        assertEquals(listOf("caption", "tr"), boxes.map { it.el?.tag })
        val cl = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        val hd = HiddenCheck { styleMap[it]?.displayNone == true }
        // 重盒长度 == 轻计数（caption 5 + 行 2）。
        val heavy = boxes.sumOf { it.textLength.toLong() }
        val table = root.children.first { it.tag == "table" }
        assertEquals(7L, heavy)
        assertEquals(heavy, NormalFlowLayout.styledCharAdvance(table, { e -> styleMap[e]!! }, cl, hd))
    }

    @Test
    fun `caption-side bottom 沉底`() {
        val root = tableTree()
        val styleMap = styles(root, "table { caption-side: bottom }")
        val boxes = rowsOf(root, styleMap)
        assertEquals(listOf("tr", "caption"), boxes.map { it.el?.tag })
    }

    @Test
    fun `empty-cells hide 进 grid 标记`() {
        val root = tableTree()
        val boxes = rowsOf(root, styles(root, "table { empty-cells: hide }"))
        assertTrue(boxes.first { it.el?.tag == "tr" }.table!!.emptyCellsHide)
    }

    private fun wideTable(): MarkupElement {
        val tr = node("tr", children = listOf(
            node("td", children = listOf(text("a"))),
            node("td", children = listOf(text("much longer cell text"))),
        ))
        return node("body", children = listOf(node("table", children = listOf(tr))))
    }

    @Test
    fun `auto 默认按内容分列（不超 max-content 不拉伸）`() {
        val root = wideTable()
        val boxes = rowsOf(root, styles(root))
        val t = boxes.single().table!!
        val w0 = t.columnWidths[0]
        val w1 = t.columnWidths[1]
        assertTrue("wide col must exceed narrow col: ${t.columnWidths.toList()}", w1 > w0 * 2)
        // 浏览器三段式第三段：MAX(10+210=220) < 可用宽 300 → 表宽 = MAX，列宽 = max-content。
        assertEquals(10, w0)
        assertEquals(210, w1)
        assertEquals(220, w0 + w1)
        assertEquals(0, t.columnXs[0])
        assertEquals(w0, t.columnXs[1])
    }

    @Test
    fun `fixed 均分无视内容`() {
        val root = wideTable()
        val boxes = rowsOf(root, styles(root, "table { table-layout: fixed }"))
        val t = boxes.single().table!!
        assertEquals(listOf(150, 150), t.columnWidths.toList())
    }

    @Test
    fun `rowspan 跨行格高度分摊到所跨各行`() {
        // 首行：跨 2 行、8 行文本的格 ＋ 2 行文本的格；次行：4 行文本的格。
        // 旧口径会把跨行格整高（8 行）压进首行；正确口径按 2:4 比例分摊。
        val root = node("body", children = listOf(node("table", children = listOf(
            node("tr", children = listOf(
                node("td", attrs = mapOf("rowspan" to "2"), children = listOf(text("abcdefgh"))),
                node("td", children = listOf(text("ab"))),
            )),
            node("tr", children = listOf(node("td", children = listOf(text("abcd"))))),
        ))))
        val rows = rowsOf(root, styles(root), PerCharBreaker())
        assertEquals(listOf("tr", "tr"), rows.map { it.el?.tag })
        val row0 = rows[0]
        val row1 = rows[1]
        val t0 = row0.table!!
        val t1 = row1.table!!
        val base0 = t0.cells[1].height // 首行自身 2 行
        val base1 = t1.cells[0].height // 次行自身 4 行
        val spanH = t0.cells[0].height // 跨行格 8 行
        // 首行不再被跨行格撑满，两行之和恰为跨行格高（CSS 不变量；collapse 无 border-spacing）。
        assertTrue("首行应低于跨行格整高: row0=${row0.replaceableHeight} span=$spanH", row0.replaceableHeight < spanH)
        assertEquals(spanH, row0.replaceableHeight + row1.replaceableHeight)
        // 跨行格把多出的高度按 2:4 比例补进两行（Chrome 口径）。
        assertTrue("首行被抬高", row0.replaceableHeight > base0)
        assertTrue("次行被抬高", row1.replaceableHeight > base1)
        assertEquals(
            base0.toDouble() / base1,
            row0.replaceableHeight.toDouble() / row1.replaceableHeight,
            0.05,
        )
    }

    @Test
    fun `auto 指定宽为列下限`() {
        // `th width=100px`（表示型属性经级联进 widthPx）：列 min/pref 不低于指定宽。
        val cells = listOf(
            TableGridModel.CellPref(0, 1, 42f, 42f, 100f),
            TableGridModel.CellPref(1, 1, 221f, 60f, 0f),
        )
        val (_, ws) = TableGridModel.autoColumnLayout(700, 2, 0f, 0, cells)
        assertEquals(100, ws[0])
        assertEquals(221, ws[1])
    }

    @Test
    fun `tableOuterGeometry 指定宽与auto居中`() {
        val plain = ComputedStyle(10f, 1.5f)
        // 无指定：占满。
        val full = TableGridModel.tableOuterGeometry(660, 0, plain)
        assertEquals(660, full.outerW)
        assertEquals(0, full.tableLeft)
        // width:90% + margin auto：594 宽，偏移 33 居中。
        val centered = TableGridModel.tableOuterGeometry(
            660, 0, ComputedStyle(10f, 1.5f, widthPct = 90f, marginLeftAuto = true, marginRightAuto = true),
        )
        assertEquals(594, centered.outerW)
        assertEquals(33, centered.tableLeft)
        assertEquals(594, centered.contentW)
        // 仅左 auto：顶右边。
        val right = TableGridModel.tableOuterGeometry(
            660, 0, ComputedStyle(10f, 1.5f, widthPx = 600f, marginLeftAuto = true),
        )
        assertEquals(600, right.outerW)
        assertEquals(60, right.tableLeft)
    }
}
