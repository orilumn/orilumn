package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
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
 * **Q15 端到端锁**：表格单元格内的**块级**子节点（`<p>`/`<div>`/`<ul>`…）在整条绘图管线上
 * 真的画出字来，且 canonical 与增量（disk-hit）两路逐行同文同位。
 *
 * ## 缺陷本体（用户真机报「表格单元格内容消失」）
 *
 * 修复前每格只挂**一个** shape，格内文本由 `NormalFlowLayout.absorbStyled(cell.el, …)` 一把吸收，
 * 而该吸收按 `styledSegments` 的规则**跳过块级子节点** ⇒ `<td><p>…</p></td>` 整格文本为空、
 * 塑形零行、绘制侧 [orilumn.reader.engine.skia.TableCellLines.emitCell] 因 `text.isEmpty()` 静默
 * 跳过：格撑住空间、边框照画，**一个字都没有**。实测 4 本书 2558/4309 = **59.4%** 的单元格如此
 * （Rust 书两本 92%/90%，GIMP 手册 47%）。
 *
 * 正向锁 [orilumn.reader.engine.laying.TableCellBlocksTest] 验块枚举与几何口径（common 单源）；
 * 本锁验**整条链路真的出像素**（`prepareLight` → `incrementalLayoutForPage`/`shapeTempPageForward`
 * → `tableCellLines`），补上「块枚举对了但绘制侧丢行」这类缝隙。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TableCellBlocksEndToEndTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val tableCss = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; } " +
        "th, td { padding: .5em; border: 1px solid #c0c0c0; }"

    /** canonical 全章重排的单元格文本行。 */
    private fun canonicalCellLines(html: String, css: String = tableCss): List<DrawLine> {
        val p = prepare(html, css)
        val layout = layouter.fullLayout(p, profile(), contentW, contentH).layout as PagedLayout
        return layout.tableCellLines(0, layout.lineCount)
    }

    /** 增量（disk-hit）路径的单元格文本行（窗口局部坐标）。 */
    private fun incrementalCellLines(html: String, css: String = tableCss): List<DrawLine> {
        val prof = profile()
        val canonical = layouter.fullLayout(prepare(html, css), prof, contentW, contentH)
        val light = layouter.prepareLight(
            converter.convert(html) ?: error("chapter parse failed"),
            CssBundle(listOf(css)), prof, contentW, orilumn.reader.engine.ChapterStructureCache(), contentH,
        )
        val table = ChapterPaginationTable.fromSlices(0, 1L, canonical.slices, light.totalBlocks, light.totalChars)
        val layout = layouter.incrementalLayoutForPage(light, prof, contentW, contentH, table, 0, pagesToShape = 4)
            .layout as PagedLayout
        return layout.tableCellLines(0, layout.lineCount)
    }

    private fun prepare(html: String, css: String): ChapterPrepareResult =
        layouter.prepare(converter.convert(html) ?: error("chapter parse failed"), CssBundle(listOf(css)), profile(), contentW, contentH)

    private fun List<DrawLine>.texts() = map { it.text }

    /**
     * ① 缺陷正面：`<td><p>…</p></td>` 必须真的画出字（修复前整格 0 行）。
     *
     * 变异验证：`cellFlowItems` 拆掉块级子节点 → 格文本为空 → `emitCell` 的 `text.isEmpty()`
     * 跳过 → 本锁红（`expected 1 but was 0`）。
     */
    @Test
    fun cellWithBlockChildKeepsItsText_endToEnd() {
        val html = """
            <html><body>
              <table><tbody>
                <tr><td><p>块里的正文必须画出来</p></td><td>对照：直写格</td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val can = canonicalCellLines(html)
        assertTrue("canonical 必须展开单元格行", can.isNotEmpty())
        assertTrue("块级子节点的文字必须出现: ${can.texts()}", can.any { it.text.contains("块里的正文必须画出来") })
        assertTrue("直写格不能被牵连", can.any { it.text.contains("对照：直写格") })
        // 增量路径同文（轻路径块枚举走 `cellBlockPlan`，与重路径同源 `cellBlocks`）。
        val inc = incrementalCellLines(html)
        assertEquals("两路单元格行数必须一致", can.size, inc.size)
        assertEquals("两路单元格文本必须逐行一致", can.texts(), inc.texts())
    }

    /**
     * ② 真实书形（GIMP 手册 表1‑1，2 列 × 2 行、有 `rowspan` 无 `colspan`、图标格只放 `<img>`）。
     *
     * 修复前第二个数据格（`<td valign="top"><p>…</p></td>`）整格空白，本锁钉住它必须有字，
     * 且 `rowspan` 图标格不吃掉它的行（跨行分摊后各行仍各自出字）。
     */
    @Test
    fun gimpNoteTableWithRowspanAndBlockCell_paintsEveryCell() {
        val html = """
            <html><body>
              <p>表1･1 図の配置</p>
              <table border="0" summary="Note">
                <tbody>
                  <tr>
                    <td rowspan="2" align="center" valign="top" width="25"><img src="note.png"/></td>
                    <th align="left">注意</th>
                  </tr>
                  <tr><td align="left" valign="top"><p>この図は説明のためのものです。</p></td></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
        val can = canonicalCellLines(html)
        assertTrue("表头格必须有字: ${can.texts()}", can.any { it.text == "注意" })
        assertTrue("块级格必须有字: ${can.texts()}", can.any { it.text.contains("この図は説明のためのものです。") })
        val inc = incrementalCellLines(html)
        assertEquals("两路单元格行数必须一致", can.size, inc.size)
        assertEquals("两路单元格文本必须逐行一致", can.texts(), inc.texts())
        // 图标格（`<img>` 占位）占一行 U+FFFC；至少保证它没把同表其余格挤没。
        assertTrue("行内图占位必须保留一行: ${can.texts()}", can.any { it.text.contains('￼') })
    }

    /**
     * ③ 逐块纵向堆叠：格内两个 `<p>` 各成一块，第二块的首行顶必须**低于**第一块的末行底，
     * 且两块的行不交叠（修复前整格只有一块 ⇒ 断言前段缺失）。
     */
    @Test
    fun twoBlockChildrenStackVerticallyWithoutOverlap() {
        val css = tableCss + " p{margin:10px}"
        val html = """
            <html><body>
              <table><tbody><tr><td><p>上段文字</p><p>下段文字</p></td></tr></tbody></table>
            </body></html>
        """.trimIndent()
        val can = canonicalCellLines(html)
        val up = can.filter { it.text.contains("上段文字") }
        val down = can.filter { it.text.contains("下段文字") }
        assertTrue("上段必须有行: ${can.texts()}", up.isNotEmpty())
        assertTrue("下段必须有行: ${can.texts()}", down.isNotEmpty())
        assertTrue(
            "第二块的首行顶 ${down.first().yTop} 必须低于第一块末行底 ${up.last().yBottom}",
            down.first().yTop >= up.last().yBottom,
        )
    }

    /**
     * ④ 字符基址跨块接续：格内块 2 的 [DrawLine.charBase] 必须等于块 1 的 charBase ＋块 1 的文本长。
     *
     * 这是点按/选区落到章内字符的依据；修复前块 2 的 `charBase` 从头算 ⇒ 点按整格都落到格首。
     */
    @Test
    fun charBaseIsContinuousAcrossCellBlocks() {
        val html = """
            <html><body>
              <table><tbody><tr><td><p>第一块文字</p><p>第二块文字</p></td></tr></tbody></table>
            </body></html>
        """.trimIndent()
        val can = canonicalCellLines(html)
        val first = can.firstOrNull { it.text.contains("第一块文字") }
        val second = can.firstOrNull { it.text.contains("第二块文字") }
        assertNotNull("缺第一块行", first)
        assertNotNull("缺第二块行", second)
        assertEquals(
            "块 2 的 charBase 必须接在块 1 文本之后",
            first!!.charBase + first.text.length,
            second!!.charBase,
        )
    }

    /**
     * ⑤ 两路几何逐行一致（不只是「有几行」）：相对 yTop/yBottom 与 range 都必须相等。
     *
     * 变异验证：把 `fillTableRowCells` 的块判据漏掉 [orilumn.reader.engine.laying.TableCellBlock.outsideSiblings]
     * ⇒ 轻路径把 `<br>` 硬换行折成空格（行数 5→3）⇒ 本锁红。
     */
    @Test
    fun canonicalAndIncrementalCellLinesAgreeLineByLine() {
        val html = """
            <html><body>
              <table><tbody>
                <tr><td>Kindle Previewer 2.7<br>Paperwhite モード<br>line3</td><td>Readium</td></tr>
                <tr><td>行内<b>face</b>与<code>字面</code></td><td>行尾换行符<br>收尾</td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val can = canonicalCellLines(html)
        val inc = incrementalCellLines(html)
        assertTrue("语料必须真的产出多行（否则验的是空气）", can.size >= 6)
        assertEquals("行数", can.size, inc.size)
        val base = can.first().yTop
        val incBase = inc.first().yTop
        for (i in can.indices) {
            assertEquals("$i text", can[i].text, inc[i].text)
            assertEquals("$i range", can[i].range, inc[i].range)
            assertEquals("$i yTop(rel)", can[i].yTop - base, inc[i].yTop - incBase)
            assertEquals("$i height", can[i].yBottom - can[i].yTop, inc[i].yBottom - inc[i].yTop)
        }
    }
}
