package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.text.BoxPageRenderer
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q1-a②③：canonical 重排的文本绘制窗口（engine-skia `DrawLineBuilder`）与盒流几何逐行自洽——
 * range 即 `leafText` 塑形输入、yTop/yBottom 即页行几何、xLeft = contentLeft + border.left + padding.left、
 * 每个 `<li>` 首个载体叶的首行恰一个 bullet。
 *
 * 文本渲染层（LineWindowDrawer 像素桥）不在此断言像素：页面上层的投影与测度同为 engine-skia
 * 单源，绘层只搬像素、绝不重排，因此窗口 = 屏上几何即闭环。Q1-c 后断行器恒定
 * SkiaParagraphBreaker，无旧管线回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkiaDrawLineWindowCoherenceTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    private fun prepare(html: String, css: String): ChapterPrepareResult =
        layouter.prepare(converter.convert(html) ?: error("chapter parse failed"), CssBundle(listOf(css)), profile(), contentW, contentH)

    @Test
    fun skiaWindowMatchesLeafGeometry() {
        val p = prepare(
            """
            <html><body>
              <p style="padding-left:20px;border-left:4px solid #000">first paragraph</p>
              <pre>mono block</pre>
              <ul><li>first item</li><li>second item</li></ul>
              <p>last paragraph</p>
            </body></html>
            """.trimIndent(),
            "",
        )
        val product = layouter.fullLayout(p, profile(), contentW, contentH)
        val renderer = product.layout as orilumn.reader.engine.skia.PagedLayout
        val window = renderer.skiaLineWindow()
        assertNotNull("canonical must carry the skia window", window)
        val lines = window!!

        // 1) 每个文本叶的每一行都在窗口里：range/text == leafText 单源、yTop/yBottom == 页行几何。
        for (leaf in p.leaves) {
            if (leaf.ranges.isEmpty() || leaf.replaceableHeight != 0 || leaf.table != null) continue
            val el = leaf.el ?: continue
            val text = NormalFlowLayout.leafText(el, p.styleMap, p.classify, p.hidden)
            for ((i, r) in leaf.ranges.withIndex()) {
                val g = leaf.firstLineIndex + i
                val dl = lines[g] ?: error("missing draw line for line $g (${el.tag})")
                assertEquals("$g range", r, dl.range)
                assertEquals("$g text", text, dl.text)
                assertEquals("$g yTop", renderer.getLineTop(g), dl.yTop)
                assertEquals("$g yBottom", renderer.getLineBottom(g), dl.yBottom)
                assertEquals(
                    "$g xLeft",
                    leaf.contentLeft + (leaf.style.border.left + leaf.style.padding.left).roundToInt(),
                    dl.xLeft,
                )
            }
        }

        // 2) 每个 <li> 载体叶的首行恰一个 bullet；其余行无 marker。
        val carriers = ListMarkers.firstCarrierSet(p.leaves.mapNotNull { it.el })
        val carrierFirstLines = p.leaves.mapNotNull { l ->
            if (l.el != null && l.el in carriers && l.ranges.isNotEmpty()) l.firstLineIndex else null
        }
        val marked = lines.values.mapNotNull { it.listMarker?.let { m -> it.yTop to m } }
        for (m in marked) assertTrue("marker must sit on a carrier first line (yTop=${m.first})", m.first in carrierFirstLines.map { renderer.getLineTop(it) })
        assertEquals("exactly one bullet per carrier", carrierFirstLines.size, lines.values.count { it.listMarker != null })

        // 3) 页面切片恰好平铺整个窗口（无空洞、无重叠、首行/末行对齐）。
        val slices = product.slices
        var prev = 0
        for (s in slices) {
            assertEquals("slices tile contiguously", prev, s.firstLine)
            prev = s.lastLineExclusive
        }
        assertEquals("window covers every line", renderer.lineCount, prev)
        assertTrue(renderer.debugPageText(0, renderer.lineCount).contains("mono block"))
        assertFalse(renderer.debugPageText(0, renderer.lineCount).contains("[img]"))
    }

    /**
     * Q1-b：增量（disk-hit）与临时（anchor/temp）路径的局部窗口也投影 skia DrawLine，
     * 供 ReaderScreen pageLines 合流——key = 局部行序，与切片 / drawPageSlice 同一坐标系，
     * 逐行几何与 drawable 行流一致、range 合法。无 img/table 时窗口应覆盖切片每一行。
     */
    @Test
    fun partialWindowsCarrySkiaLines_incrAndTemp() {
        val html = """
            <html><body>
              <p style="padding-left:20px;border-left:4px solid #000">first paragraph</p>
              <pre>mono block</pre>
              <ul><li>first item</li><li>second item</li></ul>
              <p>last paragraph</p>
            </body></html>
        """.trimIndent()
        val markup = converter.convert(html) ?: error("chapter parse failed")
        val profile = profile()
        val structure = orilumn.reader.engine.ChapterStructureCache()
        val light = layouter.prepareLight(markup, CssBundle(listOf("")), profile, contentW, structure, contentH)
        val heavy = prepare(html, "")
        val canonical = layouter.fullLayout(heavy, profile, contentW, contentH)
        val table = ChapterPaginationTable.fromSlices(
            0, 1L, canonical.slices, light.totalBlocks, light.totalChars,
        )

        // ── 增量（disk-hit）窗口 ──
        val inc = layouter.incrementalLayoutForPage(light, profile, contentW, contentH, table, 0, pagesToShape = 4)
        val incRenderer = inc.layout as orilumn.reader.engine.skia.PagedLayout
        val incWindow = incRenderer.skiaLineWindow()
        assertNotNull("incremental window must exist under the skia pipeline", incWindow)
        checkShapedLines(incRenderer, incWindow!!, inc.slices)

        // ── 临时前向页窗口 ──
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("temp fwd page failed")
        val fwdRenderer = fwd.page.layout as orilumn.reader.engine.skia.PagedLayout
        val fwdWindow = fwdRenderer.skiaLineWindow()
        assertNotNull("temp window must exist under the skia pipeline", fwdWindow)
        checkShapedLines(fwdRenderer, fwdWindow!!, listOf(fwd.page.slice))
    }

    /**
     * 表格 `rowspan` 行高：跨行格的高度由所跨越各行**分摊**（浏览器口径），不再整体压进首行。
     * canonical 与增量窗口逐行几何一致（两路同源 [orilumn.reader.engine.laying.TableGridModel.resolveRowHeights]）。
     */
    @Test
    fun rowspanTableSharesSpanningCellHeightAcrossRows() {
        val css = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; width: 300px; } " +
            ".tbl table th, .tbl table td { padding: .5em; border: 1px solid #c0c0c0; }"
        val html = """
            <html><body>
              <p>表1･1 メジャーなEPUBリーダのMathJaxサポート。</p>
              <div class="gext tbl"><table>
                <thead>
                  <tr>
                    <th rowspan="2">Kindle Previewer 2.7<br>(Kindle Paperwhite モード）<br>line3<br>line4</th>
                    <th>Readium 0.5.3</th>
                  </tr>
                  <tr><th>MS明朝</th></tr>
                </thead>
                <tbody><tr><td>MathMLサポート</td><td>MathJaxによる</td></tr></tbody>
              </table></div>
            </body></html>
        """.trimIndent()
        val p = prepare(html, css)
        val rows = p.leaves.filter { it.el?.tag == "tr" }
        assertEquals(3, rows.size)
        val kindle = rows[0].table!!.cells[0].height
        val readium = rows[0].table!!.cells[1].height
        val meiji = rows[1].table!!.cells[0].height
        assertTrue("跨行格须高于所跨两行自身高: $kindle vs ${readium + meiji}", kindle > readium + meiji)
        // 首行不再等于跨行格整高，且两行之和恰为跨行格高（collapse 无 border-spacing）。
        assertTrue("首行不应被跨行格撑满: ${rows[0].replaceableHeight} vs $kindle", rows[0].replaceableHeight < kindle)
        assertEquals(kindle, rows[0].replaceableHeight + rows[1].replaceableHeight)

        // 单元格首行盒顶 = 行顶 + border.top + padding.top（用户批的“顶部内缩”；浏览器同式）。
        val canW = layouter.fullLayout(p, profile(), contentW, contentH).layout as orilumn.reader.engine.skia.PagedLayout
        val cellLines = canW.tableCellLines(0, canW.lineCount)
        assertTrue(cellLines.isNotEmpty())
        val thStyle = p.styleMap[rows[0].table!!.cells[1].el]
        assertNotNull("th 风格须可查", thStyle)
        val expectedTop = rows[0].contentTop + (thStyle!!.border.top + thStyle.padding.top).roundToInt()
        assertEquals("单元格首行盒顶 = 行顶 + border + padding", expectedTop, cellLines[0].yTop)

        // ── canonical ↔ 增量（disk-hit）窗口逐行几何一致（同源行高）──
        val prof = profile()
        val canonical = layouter.fullLayout(p, prof, contentW, contentH)
        val structure = orilumn.reader.engine.ChapterStructureCache()
        val light = layouter.prepareLight(
            converter.convert(html) ?: error("chapter parse failed"),
            CssBundle(listOf(css)), prof, contentW, structure, contentH,
        )
        val table = ChapterPaginationTable.fromSlices(0, 1L, canonical.slices, light.totalBlocks, light.totalChars)
        val inc = layouter.incrementalLayoutForPage(light, prof, contentW, contentH, table, 0, pagesToShape = 4)
        val canLayout = canonical.layout as? orilumn.reader.engine.skia.PagedLayout
        val incLayout = inc.layout as? orilumn.reader.engine.skia.PagedLayout
        assertNotNull("canonical layout must be a PagedLayout", canLayout)
        assertNotNull("incremental layout must be a PagedLayout", incLayout)
        // 表格单元格文本行（章节绝对 Y）——canonical 与增量必须逐行一致（同源行高分摊）。
        val canCells = canLayout!!.tableCellLines(0, canLayout.lineCount)
        val incCells = incLayout!!.tableCellLines(0, incLayout.lineCount)
        assertTrue("canonical 须展开单元格行: ${canCells.size}", canCells.size >= 4)
        assertEquals("单元格文本行数不同", canCells.size, incCells.size)
        // 两窗口原点不同（增量窗相对窗口顶），逐行比较**相对**几何：间隔与行高必须一致。
        val canBase = canCells.first().yTop
        val incBase = incCells.first().yTop
        for (i in canCells.indices) {
            assertEquals("$i yTop(rel)", canCells[i].yTop - canBase, incCells[i].yTop - incBase)
            assertEquals("$i height", canCells[i].yBottom - canCells[i].yTop, incCells[i].yBottom - incCells[i].yTop)
        }
    }

    /** 断言：每个已塑形切片内的每一行都在窗口里，且几何/range 与 drawable 行流一致。 */
    private fun checkShapedLines(renderer: orilumn.reader.engine.paging.BookLayout, window: Map<Int, DrawLine>, slices: List<PageSlice>) {
        for (s in slices) {
            if (s.firstLine < 0 || s.lastLineExclusive <= s.firstLine) continue
            for (g in s.firstLine until s.lastLineExclusive) {
                val dl = window[g] ?: error("missing draw line for line $g in slice [${s.firstLine},${s.lastLineExclusive})")
                assertEquals("$g yTop", renderer.getLineTop(g), dl.yTop)
                assertEquals("$g yBottom", renderer.getLineBottom(g), dl.yBottom)
                assertTrue(
                    "$g range ${dl.range} vs len ${dl.text.length}",
                    dl.range.first >= 0 && dl.range.last > dl.range.first && dl.range.last <= dl.text.length,
                )
            }
        }
    }
}