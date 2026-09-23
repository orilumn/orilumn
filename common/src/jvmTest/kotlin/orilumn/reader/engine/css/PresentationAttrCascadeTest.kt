package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P0-B 验收：HTML4 表示型属性经 [Cascade.PRESENTATION_ATTRS] 以 low-tier(15) 进级联。
 * 已消费属性（align→text-align、bgcolor→background-color、cellpadding→padding、
 * border→border）断言 ComputedStyle；尚未消费的（valign→vertical-align、nowrap→white-space、
 * cellspacing→border-spacing，消费属 P1）断言 cascade 胜出层；并验证作者样式恒压该层。
 */
class PresentationAttrCascadeTest {

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    private fun compute(root: MarkupElement, ua: String = "", author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(16f, LightCssParser().parse(ua), listOf(LightCssParser().parse(author))).compute(root)

    /** 一棵 table > tr > td 的三代树，带好 parent 链（lazy/重两路共享）。 */
    private fun cellTree(tdAttrs: Map<String, String>, tableAttrs: Map<String, String> = emptyMap()): Triple<MarkupElement, MarkupElement, MarkupElement> {
        val td = node("td", tdAttrs)
        val tr = node("tr", children = listOf(td))
        td.parent = tr
        val table = node("table", tableAttrs, listOf(tr))
        tr.parent = table
        return Triple(table, tr, td)
    }

    @Test
    fun `td align 进级联为 text-align`() {
        val (table, _, td) = cellTree(mapOf("align" to "center"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(TextAlign.CENTER, out[td]?.textAlign)
    }

    @Test
    fun `td bgcolor 进级联为 background-color`() {
        val (table, tr, td) = cellTree(mapOf("bgcolor" to "#ffeeff"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals("#ffffeeff", out[td]?.backgroundColorHex)
        // 背景作用于整行（tr 也继承了该呈现，但 td/th 才是绘制载体；此处仅验 tr 未误吞）。
        assertNotNull(out[tr])
    }

    @Test
    fun `table cellpadding 进级联为四边 padding`() {
        val (table, _, td) = cellTree(emptyMap(), mapOf("cellpadding" to "6"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(6f, out[table]?.padding?.top!!, 1e-3f)
        assertEquals(6f, out[table]?.padding?.left!!, 1e-3f)
    }

    @Test
    fun `table border 进级联为四边 border 宽度`() {
        val (table, _, td) = cellTree(emptyMap(), mapOf("border" to "2"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(2f, out[table]?.border?.right!!, 1e-3f)
        assertEquals(2f, out[table]?.border?.bottom!!, 1e-3f)
    }

    @Test
    fun `尚未消费的映射进入级联胜出层`() {
        // valign/nowrap/cellspacing 消费点在 P1；此处验证映射已产生低层级声明。
        val (table, tr, td) = cellTree(
            mapOf("valign" to "middle", "nowrap" to "true"),
            mapOf("cellspacing" to "4"),
        )
        val body = node("body", children = listOf(table)); table.parent = body
        val cascade = Cascade(null, emptyList())
        val tdWinners = cascade.winningDeclarations(td, listOf(tr, table, body), emptyList())
        assertEquals("middle", tdWinners["vertical-align"])
        assertEquals("nowrap", tdWinners["white-space"])
        val tableWinners = cascade.winningDeclarations(table, listOf(body), emptyList())
        assertEquals("4px", tableWinners["border-spacing"])
    }

    @Test
    fun `作者样式恒压表示型属性低层级`() {
        // low-tier(15) < author normal(20)：书里写 CSS 时属性不再生效（浏览器语义）。
        val (table, _, td) = cellTree(mapOf("align" to "center", "bgcolor" to "#ffeeff"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body, author = "td { text-align: right; background-color: #000000 }")
        assertEquals(TextAlign.RIGHT, out[td]?.textAlign)
        assertEquals("#ff000000", out[td]?.backgroundColorHex)
    }

    @Test
    fun `width height 表示型属性照旧进级联`() {
        val (table, _, td) = cellTree(mapOf("width" to "40"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(40f, out[td]?.widthPx!!, 1e-3f)
    }
}