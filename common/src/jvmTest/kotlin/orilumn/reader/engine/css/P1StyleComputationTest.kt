package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 验收：T2 陷阱修复（border-width/style/异色/radius、page-break-* 别名、list-style 简写）
 * 与 T3 计算层（white-space/spacing/transform/align/sizing/opacity/visibility/overflow/
 * position/wrap/break/direction）的 [ComputedStyle] 断言。新增字段一律旧行为默认（§6 inv.3）。
 */
class P1StyleComputationTest {

    private val ua = StyleSheet(emptyList())

    private fun styled(tag: String, css: String, inline: String? = null): ComputedStyle {
        val author = LightCssParser().parse(css)
        val attrs = if (inline != null) mapOf("style" to inline) else emptyMap()
        val el = MarkupElement(tag, attrs, listOf(MarkupElement("#text", text = "x")))
        val body = MarkupElement("body", emptyMap(), listOf(el))
        el.parent = body
        el.children.forEach { it.parent = el }
        return StyleComputer(16f, ua, listOf(author)).compute(body)[el]!!
    }

    // ---- T2: border-width 全量源 ----

    @Test
    fun `border-width 简写参与宽度`() {
        val s = styled("div", "div { border-width: 3px }")
        assertEquals(3f, s.border.top, 1e-3f)
        assertEquals(3f, s.border.left, 1e-3f)
        // 只有宽度、未声明样式 → styles 为 null（solid 回退）；颜色 currentColor。
        assertNull(s.borderStyles)
        assertNull(s.borderColors)
    }

    @Test
    fun `border-width 槽位与 thin-medium-thick 关键字`() {
        val s = styled("div", "div { border-width: thin medium thick }")
        assertEquals(1f, s.border.top, 1e-3f)
        assertEquals(3f, s.border.right, 1e-3f)
        assertEquals(5f, s.border.bottom, 1e-3f)
        assertEquals(3f, s.border.left, 1e-3f)
    }

    @Test
    fun `border-*-width 覆盖简写`() {
        val s = styled("div", "div { border: 1px solid #000000; border-left-width: 5px }")
        assertEquals(5f, s.border.left, 1e-3f)
        assertEquals(1f, s.border.top, 1e-3f)
    }

    @Test
    fun `border 仍是最低均匀源`() {
        val s = styled("div", "div { border: 2px }")
        assertEquals(2f, s.border.top, 1e-3f)
        assertEquals(2f, s.border.bottom, 1e-3f)
    }

    // ---- T2: border-style 数据模型 ----

    @Test
    fun `border 简写样式落四边`() {
        val s = styled("div", "div { border: 1px solid #000000 }")
        assertEquals(BorderStyleEdges.uniform(BorderStyle.SOLID), s.borderStyles)
        assertEquals(BorderColorEdges("#ff000000"), s.borderColors)
    }

    @Test
    fun `border-style none 显式抑制`() {
        val s = styled("div", "div { border: 2px solid #000000; border-style: none }")
        assertEquals(BorderStyleEdges.uniform(BorderStyle.NONE), s.borderStyles)
    }

    @Test
    fun `border-style 槽位语义`() {
        val s = styled("div", "div { border-width: 2px; border-style: dashed solid }")
        assertEquals(BorderStyle.DASHED, s.borderStyles!!.top)
        assertEquals(BorderStyle.SOLID, s.borderStyles!!.right)
        assertEquals(BorderStyle.DASHED, s.borderStyles!!.bottom)
        assertEquals(BorderStyle.SOLID, s.borderStyles!!.left)
    }

    @Test
    fun `hidden 折叠为 none`() {
        val s = styled("div", "div { border: 1px hidden #000000 }")
        assertEquals(BorderStyle.NONE, s.borderStyles!!.top)
    }

    // ---- T2: 四边异色 ----

    @Test
    fun `border-color 简写槽位`() {
        val s = styled("div", "div { border-width: 1px; border-color: red blue }")
        assertEquals("#ffff0000", s.borderColors!!.top)
        assertEquals("#ff0000ff", s.borderColors!!.right)
        assertEquals("#ffff0000", s.borderColors!!.bottom)
        assertEquals("#ff0000ff", s.borderColors!!.left)
    }

    @Test
    fun `单边颜色声明仍覆盖`() {
        // blockquote 左规则常见模式：只写 border-left。
        val s = styled("blockquote", "blockquote { border-left: 3px solid rgba(0,0,0,0.5) }")
        assertEquals(3f, s.border.left, 1e-3f)
        assertEquals(0f, s.border.top, 1e-3f)
        assertTrue(s.borderColors!!.left!!.startsWith("#80"))
        assertNull(s.borderColors!!.top)
    }

    // ---- T2: border-radius ----

    @Test
    fun `border-radius 简写与单角覆盖`() {
        val s = styled("div", "div { border-radius: 10px 20px }")
        assertEquals(10f, s.borderRadius.topLeft, 1e-3f)
        assertEquals(20f, s.borderRadius.topRight, 1e-3f)
        assertEquals(10f, s.borderRadius.bottomRight, 1e-3f)
        assertEquals(20f, s.borderRadius.bottomLeft, 1e-3f)
        val s2 = styled("div", "div { border-radius: 4px; border-top-left-radius: 9px }")
        assertEquals(9f, s2.borderRadius.topLeft, 1e-3f)
        assertEquals(4f, s2.borderRadius.topRight, 1e-3f)
    }

    @Test
    fun `border-radius 百分比延后`() {
        val s = styled("div", "div { border-radius: 50% }")
        assertEquals(0f, s.borderRadius.topLeft, 1e-3f)
    }

    // ---- T2: page-break-* 别名 + list-style 简写 ----

    @Test
    fun `page-break 别名映射 BreakRule`() {
        val s = styled("h1", "h1 { page-break-before: avoid }")
        assertEquals(BreakRule.AVOID, s.breakBefore)
        val s2 = styled("p", "p { page-break-inside: avoid-page }")
        assertEquals(BreakRule.AVOID, s2.breakInside)
        // break-* 优先于别名。
        val s3 = styled("p", "p { page-break-after: avoid; break-after: auto }")
        assertEquals(BreakRule.AUTO, s3.breakAfter)
    }

    @Test
    fun `list-style 简写拆分`() {
        val s = styled("ul", "ul { list-style: square inside }")
        assertEquals("square", s.listStyleType)
        assertEquals("inside", s.listStylePosition)
        // 单属性仍优先。
        val s2 = styled("ul", "ul { list-style: square inside; list-style-type: disc }")
        assertEquals("disc", s2.listStyleType)
        assertEquals("inside", s2.listStylePosition)
    }

    // ---- T3: 计算层 ----

    @Test
    fun `T3 文本属性解析`() {
        val s = styled(
            "pre",
            "pre { white-space: pre; letter-spacing: 0.1em; word-spacing: 2px; " +
                "text-transform: uppercase; vertical-align: super }",
        )
        assertEquals(WhiteSpace.PRE, s.whiteSpace)
        assertEquals(1.6f, s.letterSpacingPx, 1e-3f)
        assertEquals(2f, s.wordSpacingPx, 1e-3f)
        assertEquals(TextTransform.UPPERCASE, s.textTransform)
        assertEquals(VerticalAlign.SUPER, s.verticalAlign)
    }

    @Test
    fun `T3 盒属性解析`() {
        val s = styled(
            "div",
            "div { box-sizing: border-box; opacity: 0.5; visibility: hidden; overflow: hidden; " +
                "position: relative; overflow-wrap: break-word; word-break: break-all; direction: rtl }",
        )
        assertEquals(BoxSizing.BORDER_BOX, s.boxSizing)
        assertEquals(0.5f, s.opacity, 1e-4f)
        assertTrue(s.visibilityHidden)
        assertEquals(OverflowValue.HIDDEN, s.overflow)
        assertTrue(s.positionRelative)
        assertEquals(OverflowWrap.BREAK_WORD, s.overflowWrap)
        assertEquals(WordBreak.BREAK_ALL, s.wordBreak)
        assertTrue(s.directionRtl)
    }

    @Test
    fun `T3 默认值等于旧行为`() {
        val s = styled("div", "div { margin: 1px }")
        assertEquals(WhiteSpace.NORMAL, s.whiteSpace)
        assertEquals(0f, s.letterSpacingPx, 1e-3f)
        assertEquals(0f, s.wordSpacingPx, 1e-3f)
        assertEquals(TextTransform.NONE, s.textTransform)
        assertEquals(VerticalAlign.BASELINE, s.verticalAlign)
        assertEquals(BoxSizing.CONTENT_BOX, s.boxSizing)
        assertEquals(1f, s.opacity, 1e-4f)
        assertEquals(false, s.visibilityHidden)
        assertEquals(OverflowValue.VISIBLE, s.overflow)
        assertEquals(false, s.positionRelative)
        assertEquals(OverflowWrap.NORMAL, s.overflowWrap)
        assertEquals(WordBreak.NORMAL, s.wordBreak)
        assertEquals(false, s.directionRtl)
    }

    @Test
    fun `T3 可继承属性透传`() {
        // white-space / letter-spacing / text-transform / direction 按 CSS 继承。
        val el = MarkupElement("span", emptyMap(), listOf(MarkupElement("#text", text = "x")))
        val pre = MarkupElement(
            "pre", mapOf("style" to "white-space: pre; letter-spacing: 2px; text-transform: lowercase"),
            listOf(el),
        )
        val body = MarkupElement("body", emptyMap(), listOf(pre))
        el.parent = pre; pre.parent = body
        el.children.forEach { it.parent = el }
        val out = StyleComputer(16f, ua, emptyList()).compute(body)
        val child = out[el]!!
        assertEquals(WhiteSpace.PRE, child.whiteSpace)
        assertEquals(2f, child.letterSpacingPx, 1e-3f)
        assertEquals(TextTransform.LOWERCASE, child.textTransform)
    }
}
