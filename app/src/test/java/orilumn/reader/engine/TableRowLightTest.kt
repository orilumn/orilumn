package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 表格行轻路径：thead/tbody 包裹 + rowspan 下 tr 行叶仍带 table（书内表格 CSS 原样）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TableRowLightTest {

    private lateinit var bc: BoxChapterLayouter
    private lateinit var profile: TypographicProfile

    @Before
    fun setUp() {
        bc = BoxChapterLayouter()
        profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    }

    private val tableCss = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; } " +
        ".tbl table th, .tbl table td { padding: .5em; border: 1px solid #c0c0c0; }"

    private val html = "<html><head><style>$tableCss</style></head><body>" +
        "<div class=\"gext tbl\"><div><table>" +
        "<thead><tr><th rowspan=\"2\">項目</th><th>Readium 0.5.3</th></tr><tr><th>MS明朝</th></tr></thead>" +
        "<tbody><tr><td>MathMLサポート</td><td>MathJaxによる</td></tr></tbody>" +
        "</table></div><div class=\"caption\">表1･1</div></div></body></html>"

    @Test
    fun `light tr leaves carry table`() {
        val markup = HtmlTreeConverter().convert(html)!!
        val light = bc.prepareLight(markup, null, profile, 600, ChapterStructureCache(), 2000)
        val tags = (0 until light.totalBlocks).map { light.block(it).el?.tag }
        val trIdx = (0 until light.totalBlocks).filter { light.block(it).el?.tag == "tr" }
        assertTrue("tr 行叶存在", trIdx.isNotEmpty())
        for (i in trIdx) {
            assertNotNull("block $i tr 须带 table", light.block(i).table)
        }
    }

    @Test
    fun `heavy tr leaves carry table with height`() {
        val markup = HtmlTreeConverter().convert(html)!!
        val heavy = bc.prepare(markup, null, profile, 600, 2000)
        val rows = heavy.leaves.filter { it.el?.tag == "tr" }
        assertTrue("thead2行 + tbody1行", rows.size == 3)
        for (r in rows) {
            assertNotNull("tr 行叶须带 table", r.table)
            assertTrue("行高>0", r.replaceableHeight > 0)
        }
    }
}
