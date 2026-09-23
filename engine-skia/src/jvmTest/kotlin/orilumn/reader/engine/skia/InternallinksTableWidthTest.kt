package orilumn.reader.engine.skia

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.TableGridModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * internallinks.epub 表1･1 真书端到端：auto 分列「输入真测」后的收口回归。
 *
 * 原文（OEBPS/0001.xhtml）：thead 两行（含 `rowspan=2` + `<br/>` 表头）+ tbody 一行；
 * 书内 common.css：`.tbl table td{padding:.5em; border:1px solid}`（th 无 padding/border、
 * `font-weight:bold`）。此前临时探针手搓 prefs（全格统一 weight/edges/字族、`<br/>` 前空格
 * 未剥），与生产输入不一致，Chrome 像素只差 4–7px/列却无法归因——本测试改走生产单源：
 * 级联→吸收→[NormalFlowLayout.tableCellPref]→[TableGridModel.autoColumnLayout]，零手搓。
 *
 * 断言的是**接线不变量**（吸收文本、th/td 样式、min≤max、重路径列宽 == 单源重算、和锁三段式
 * 目标），Chrome 参照（1589 → 306/313/253/282/434，同字号 scale）只做 ±12% 宽容比对：
 * 像素级 parity 依赖系统字库（Hiragino/Mincho 有无），算法 parity 不依赖。
 */
class InternallinksTableWidthTest {

    private val bodyPx = 46.251f
    private val contentW = 1589
    private val chromeWidths = listOf(306, 313, 253, 282, 434)

    private val html = """
        <html><body>
        <div class="gext tbl"><div><table>
        <thead>
        <tr><th rowspan="2">項目</th><th rowspan="2">iBooks3.0 (iOS6)</th><th rowspan="2">Adobe Digita <br/>Editions 2.0</th><th>Readium 0.5.3</th><th rowspan="2">Kindle Previewer 2.7 <br/>(Kindle Paperwhite モード）</th></tr>
        <tr><th>MS明朝</th></tr>
        </thead>
        <tbody>
        <tr><td>MathMLサポート</td><td>MathML直接描画</td><td>未サポート</td><td>MathJaxによる</td><td>未サポート</td></tr>
        </tbody>
        </table></div></div>
        </body></html>
    """.trimIndent()

    /** 书内 common.css 表格子集（原文顺序）＋ body 字栈原文。 */
    private val css = """
        body { font-family: "HiraMinProN-W3", "ヒラギノ明朝 ProN W3", "HiraMinPro-W3", "ヒラギノ明朝 Pro W3", "ＭＳ Ｐ明朝", "ＭＳ 明朝", serif; }
        table { table-layout: auto; border-collapse: collapse; border-spacing: 0; }
        td { vertical-align: top; }
        .tbl table td { padding: .5em; border: 1px solid #c0c0c0; }
        .tbl table th { text-align: center; font-weight: bold; background-color: #f0f0f0; }
        .tbl table td { text-align: left; }
    """.trimIndent()

    private data class Setup(
        val root: MarkupElement,
        val table: MarkupElement,
        val styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: BlockClassify,
        val breaker: SkiaParagraphBreaker,
        val rows: List<LayoutBox>,
    )

    private fun setup(): Setup {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(bodyPx, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val breaker = SkiaParagraphBreaker(0f)
        val result = BoxLayouter(bodyPx, breaker).layoutBoxes(root, contentW, styles, classify)
        val table = findFirst(root) { it.tag == "table" }!!
        val rows = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.table != null && b.el?.tag == "tr") rows.add(b) else walk(b.childBoxes)
        }
        walk(result.boxes)
        assertEquals("thead 2 行 + tbody 1 行", 3, rows.size)
        return Setup(root, table, styles, classify, breaker, rows)
    }

    private fun findFirst(el: MarkupElement, pred: (MarkupElement) -> Boolean): MarkupElement? {
        if (pred(el)) return el
        for (c in el.children) {
            val hit = findFirst(c, pred)
            if (hit != null) return hit
        }
        return null
    }

    private fun cellTexts(s: Setup): List<List<String>> {
        val model = TableGridModel.build(s.table)
        return model.rows.map { row ->
            row.cells.map { cell -> NormalFlowLayout.absorbStyled(cell.el, s.styles, s.classify, HIDDEN_NONE).text }
        }
    }

    @Test
    fun `absorbed texts strip br-adjacent spaces`() {
        val texts = cellTexts(setup())
        assertEquals(
            listOf(
                listOf("項目", "iBooks3.0 (iOS6)", "Adobe Digita\nEditions 2.0", "Readium 0.5.3", "Kindle Previewer 2.7\n(Kindle Paperwhite モード）"),
                listOf("MS明朝"),
                listOf("MathMLサポート", "MathML直接描画", "未サポート", "MathJaxによる", "未サポート"),
            ),
            texts,
        )
    }

    @Test
    fun `header bold without edges, body padded and bordered`() {
        val s = setup()
        val model = TableGridModel.build(s.table)
        for (row in model.rows) for (cell in row.cells) {
            val cs = s.styles[cell.el]!!
            if (cell.isHeader) {
                assertEquals("th bold: <${cell.el.tag}> col${cell.col}", 700, cs.fontWeight)
                assertEquals("th 无 padding", 0f, cs.padding.horizontal, 1e-3f)
                assertEquals("th 无 border（书内 border 规则只写了 td）", 0f, cs.border.horizontal, 1e-3f)
            } else {
                assertEquals("td normal", 400, cs.fontWeight)
                assertEquals("td padding .5em x2", bodyPx, cs.padding.horizontal, 5e-2f)
                assertEquals("td border 1px x2", 2f, cs.border.horizontal, 1e-3f)
            }
            assertEquals(bodyPx, cs.fontSizePx, 1e-3f)
        }
    }

    @Test
    fun `heavy column widths equal single-source recomputation`() {
        val s = setup()
        val model = TableGridModel.build(s.table)
        assertEquals(5, model.columnCount)
        val measure = SkiaParagraphBreaker(0f)
        val prefs = ArrayList<TableGridModel.CellPref>()
        for (row in model.rows) for (cell in row.cells) {
            val cs = s.styles[cell.el]!!
            val text = NormalFlowLayout.absorbStyled(cell.el, s.styles, s.classify, HIDDEN_NONE).text
            val runs = NormalFlowLayout.leafFontRuns(cell.el, s.styles, s.classify, HIDDEN_NONE)
            val pref = NormalFlowLayout.tableCellPref(measure, cell.col, cell.colSpan, text, cs, cell.el.tag, runs)
            assertTrue("min≤max: $text", pref.min <= pref.pref + 1e-3f)
            prefs.add(pref)
        }
        val (xs, ws) = TableGridModel.autoColumnLayout(contentW, model.columnCount, 0f, 0, prefs)
        val heavy = s.rows.first().table!!
        assertEquals("重路径列宽 == 单源重算", ws.toList(), heavy.columnWidths.toList())
        assertEquals(xs.toList(), heavy.columnXs.toList())
        // 三段式和锁：列 min/max 取同列单元格最大者（与 autoColumnLayout 同式），和锁目标。
        val colMin = FloatArray(model.columnCount)
        val colMax = FloatArray(model.columnCount)
        for (p in prefs) {
            if (p.pref > colMax[p.col]) colMax[p.col] = p.pref
            if (p.min > colMin[p.col]) colMin[p.col] = p.min
        }
        val sumMin = colMin.sum()
        val sumMax = colMax.sum()
        val want = when {
            contentW >= sumMax -> sumMax
            contentW <= sumMin -> sumMin
            else -> contentW.toFloat()
        }.toInt()
        assertEquals(want, ws.sum())
        println("BOOKTABLE widths=${ws.joinToString("/")} sum=${ws.sum()} minSum=${sumMin.toInt()} maxSum=${sumMax.toInt()}")
    }

    @Test
    fun `widths near Chrome reference within font tolerance`() {
        val s = setup()
        val ws = s.rows.first().table!!.columnWidths.toList()
        for (i in ws.indices) {
            val dev = abs(ws[i] - chromeWidths[i]) / chromeWidths[i].toFloat()
            assertTrue("col$i ${ws[i]} vs Chrome ${chromeWidths[i]}（容差 12%，字库差异）", dev <= 0.12f)
        }
    }
}
