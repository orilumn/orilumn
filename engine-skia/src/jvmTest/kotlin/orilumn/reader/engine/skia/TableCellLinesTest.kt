package orilumn.reader.engine.skia

import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyle
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
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
        /** 逐行挤压比例（格内那一路的 `DrawLine.squeezeRatio` 源）。缺省全 0 = 不挤。 */
        private val squeezeRatios: List<Float> = emptyList(),
        private val hyphenFlags: List<Boolean> = emptyList(),
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
        override fun shapeLineHyphenAtEnd(k: Int): Boolean = hyphenFlags.getOrElse(k) { false }
        override fun shapeLineSqueezeRatio(k: Int): Float = squeezeRatios.getOrElse(k) { 0f }
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

    /** Q15：格内块序列。纯内联格恒一块（匿名块），故单块构造即旧式「一格一 shape」。 */
    private fun cell(tag: String, text: String, x: Int, w: Int, shape: ParagraphShapeRef?): TableCellLayout =
        TableCellLayout(MarkupElement(tag), 0, 1, x, w, 20, tag == "th", blocks(tag, text, shape))

    private fun blocks(tag: String, text: String, shape: ParagraphShapeRef?): List<orilumn.reader.engine.laying.TableCellBlock> =
        listOf(
            orilumn.reader.engine.laying.TableCellBlock(
                MarkupElement(tag), ComputedStyle(10f, 1.5f), text, emptyList(),
            ).also { it.shape = shape },
        )

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
        // 边框：无声明边框宽度即无边框（旧整框矩形已删）。
        assertTrue(win.borders.isEmpty())
    }

    /**
     * **格内行的 `squeezeRatio` / `hyphenAtEnd` 逐行取自 shape**（canonical 之外的第三路）。
     *
     * ## 为什么这把锁不可省
     *
     * [orilumn.reader.engine.skia.DrawLine] 有**三个**构造点，三个各漏一个字段都会静默降级：
     * [orilumn.reader.engine.skia.DrawLineBuilder]（canonical，走 `LayoutBox`）、
     * 本文件这一路（表格格内）、`BoxChapterLayouter`（增量/临时页，走 shape）。
     * 另两路各有 `SkiaDrawLineWindowCoherenceTest` 的两条锁盯着（它们都要跑整章重排，很贵），
     * **只有这一路此前一个字段都没锁** —— 表格章节的连字符与挤压比例全靠肉眼。
     *
     * 这里直接构造 [ParagraphShapeRef] 桩喂非零值 ⇒ 漏传立刻现形，不必跑整章。
     *
     * 漏 `squeezeRatio` 的症状是**画比量宽 ⇒ 右溢被裁**（分页阅读器页宽固定、无横向滚动条）；
     * 漏 `hyphenAtEnd` 的症状是连字符画不出来（已修过一次，见
     * [orilumn.reader.engine.laying.ParagraphShapeRef.shapeLineHyphenAtEnd] 的 KDoc）。
     */
    @Test
    fun `cell lines carry squeezeRatio and hyphenAtEnd from the shape`() {
        val s1 = StubShape(
            "項目iBooks", listOf(0..3, 4..7), listOf(15, 15),
            squeezeRatios = listOf(0f, 0.375f),
            hyphenFlags = listOf(false, true),
        )
        val s2 = StubShape(
            "Readium", listOf(0..6), listOf(15),
            squeezeRatios = listOf(0.5f),
            hyphenFlags = listOf(true),
        )
        val table = TableRowLayout(
            intArrayOf(0, 300), intArrayOf(300, 300),
            listOf(cell("th", "x", 0, 300, s1), cell("td", "x", 300, 300, s2)),
        )
        val win = TableCellLines.expand(table, 100, 30, 1000, { style }, style, 0f)
        assertEquals("行数", 3, win.lines.size)
        assertEquals(
            "逐行挤压比例必须逐值取自 shape（格 1 = [0, 0.375]，格 2 = [0.5]）",
            listOf(0f, 0.375f, 0.5f), win.lines.map { it.squeezeRatio },
        )
        assertEquals(
            "逐行断词收尾标志必须逐值取自 shape（格 1 = [false, true]，格 2 = [true]）",
            listOf(false, true, true), win.lines.map { it.hyphenAtEnd },
        )
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
    fun `cell borders emit declared sides only`() {
        // 只声明 border-top 的格只出顶带（Rust 简介表 regression：旧整框画成四面）。
        val s = StubShape("x", listOf(0..0), listOf(15))
        val topOnly = ComputedStyle(
            10f, 1.5f, border = Edges(top = 1f),
            borderColors = BorderColorEdges(top = "#ff000000", right = null, bottom = null, left = null),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        val table = TableRowLayout(intArrayOf(0), intArrayOf(300), listOf(cell("td", "x", 0, 300, s)))
        val win = TableCellLines.expand(table, 100, 15, 0, { topOnly }, topOnly, 0f)
        assertEquals(1, win.borders.size)
        val top = win.borders[0]
        assertEquals(0, top.left)
        assertEquals(100, top.yTop)
        assertEquals(300, top.right)
        assertEquals(101, top.yBottom)
        assertEquals(0xFF000000.toInt(), top.argb)
        // 四边全声明即四带。
        val all = ComputedStyle(
            10f, 1.5f, border = Edges(top = 1f, right = 1f, bottom = 1f, left = 1f),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        val win4 = TableCellLines.expand(table, 100, 15, 0, { all }, all, 0f)
        assertEquals(4, win4.borders.size)
        // style NONE 即使有宽也不画。
        val none = ComputedStyle(
            10f, 1.5f, border = Edges(top = 1f),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.NONE),
        )
        assertTrue(TableCellLines.expand(table, 100, 15, 0, { none }, none, 0f).borders.isEmpty())
    }

    @Test
    fun `cell border follows css border-color`() {
        val s = StubShape("x", listOf(0..0), listOf(15))
        val table = TableRowLayout(intArrayOf(0), intArrayOf(300), listOf(cell("td", "x", 0, 300, s)))
        // 声明了 border-color → 用 CSS 色（internallinks 的 #c0c0c0 场景）。
        val declared = ComputedStyle(
            10f, 1.5f, colorHex = "#ff111111",
            border = Edges(top = 1f), borderColors = BorderColorEdges("#ffc0c0c0"),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        assertEquals(0xFFC0C0C0.toInt(), TableCellLines.expand(table, 0, 15, 0, { declared }, declared, 0f).borders[0].argb)
        // 未声明 → currentColor（本元素文字色，CSS 默认）。
        val current = ComputedStyle(
            10f, 1.5f, colorHex = "#ff222222",
            border = Edges(top = 1f), borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        assertEquals(0xFF222222.toInt(), TableCellLines.expand(table, 0, 15, 0, { current }, current, 0f).borders[0].argb)
        // 连文字色都没有 → 回退遗留中性灰。
        val bare = ComputedStyle(
            10f, 1.5f, border = Edges(top = 1f),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        assertEquals(TableCellLines.BORDER_ARGB, TableCellLines.expand(table, 0, 15, 0, { bare }, bare, 0f).borders[0].argb)
    }

    @Test
    fun `cell image emits PageImage`() {
        // Rust 简介 Ferris 表回归：td 内独占 img（shape 文本仅 U+FFFC 占位）必须进 PageImage。
        val img = MarkupElement("img", mapOf("src" to "Images/a.png"))
        val td = MarkupElement("td", emptyMap(), listOf(img))
        val shape = StubShape("￼", listOf(0..0), listOf(40))
        val table = TableRowLayout(
            intArrayOf(0), intArrayOf(300),
            // 行内 `<img>` 留在匿名 run 内（Q15 决定：`<td><img></td>` 是「一块 + 一个 U+FFFC
            // 占位」，与修复前同式），扫描根 `imageRoot` 是容器 `td`。
            listOf(
                TableCellLayout(
                    td, 0, 1, 10, 300, 44, false,
                    listOf(
                        orilumn.reader.engine.laying.TableCellBlock(
                            td, ComputedStyle(10f, 1.5f), "￼", emptyList(), imageRoot = td,
                        ).also { it.shape = shape },
                    ),
                ),
            ),
        )
        val loader = orilumn.reader.engine.ImageBoundsReader { _, _ -> 120 to 80 }
        val win = TableCellLines.expand(
            table, 100, 44, 0, { style }, style, 0f,
            imageLoader = loader, chapterHref = "Text/00.xhtml",
        )
        assertEquals(1, win.images.size)
        val im = win.images[0]
        assertEquals("Images/a.png", im.src)
        assertEquals("Text/00.xhtml", im.chapterHref)
        assertEquals(120, im.widthPx)
        assertEquals(80, im.heightPx)
        assertEquals(10, im.xLeft)
        assertEquals(100, im.yTop)
        assertEquals(180, im.yBottom)
        // 占位隐藏区间：shape 文本唯一的 U+FFFC（0..0）随行记录。
        assertEquals(listOf(0..0), win.lines[0].imgHidden)
        // 无 loader/href 时不产出（旧调用口径不变）。
        val bare = TableCellLines.expand(table, 100, 44, 0, { style }, style, 0f)
        assertTrue(bare.images.isEmpty())
    }

    @Test
    fun `vertical-align middle 下移内容`() {
        // 本书第二列 `vertical-align: middle`：15px 行在 100px 行带内居中，下移 (100-15)/2=42。
        val s = StubShape("x", listOf(0..0), listOf(15))
        val middle = ComputedStyle(10f, 1.5f, verticalAlign = orilumn.reader.engine.css.VerticalAlign.MIDDLE)
        val table = TableRowLayout(intArrayOf(0), intArrayOf(300), listOf(cell("td", "x", 0, 300, s)))
        val win = TableCellLines.expand(table, 100, 100, 0, { middle }, middle, 0f)
        assertEquals(142, win.lines[0].yTop)
        assertEquals(157, win.lines[0].yBottom)
        // 默认顶端对齐不动。
        val top = TableCellLines.expand(table, 100, 100, 0, { style }, style, 0f)
        assertEquals(100, top.lines[0].yTop)
    }

    @Test
    fun `rowspan border spans covered rows`() {
        // 表1·1 形：首行 4 格 rowspan=2 + 1 普通格，次行 1 格；跨行格边框直画到末行底。
        val span = StubShape("項目", listOf(0..1), listOf(15))
        val top = StubShape("MS", listOf(0..1), listOf(15))
        val bot = StubShape("朝", listOf(0..0), listOf(15))
        fun spanCell(): TableCellLayout =
            TableCellLayout(MarkupElement("th"), 0, 1, 0, 150, 15, true, blocks("th", "項目", span), 2)
        val row0 = TableRowLayout(intArrayOf(0, 150), intArrayOf(150, 150), listOf(spanCell(), TableCellLayout(MarkupElement("th"), 1, 1, 150, 150, 15, true, blocks("th", "MS", top))))
        val row1 = TableRowLayout(intArrayOf(0, 150), intArrayOf(150, 150), listOf(TableCellLayout(MarkupElement("th"), 1, 1, 150, 150, 15, true, blocks("th", "朝", bot))))
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
        // 边框：跨行格顶带在首行顶，普通格顶带各守本行带（逐边带，只画声明边）。
        val topBordered = ComputedStyle(
            10f, 1.5f, border = Edges(top = 1f),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        val winB = TableCellLines.expandTable(
            listOf(
                TableCellLines.RowFrame(row0, 0, 0, 15, 0, topBordered, tableEl),
                TableCellLines.RowFrame(row1, 1, 15, 15, 2, topBordered, tableEl),
            ),
            { topBordered }, 0f,
        )
        val tops = winB.borders.filter { it.yBottom - it.yTop == 1 }
        assertEquals(listOf(0, 0, 15), tops.map { it.yTop }.sorted())
    }
}
