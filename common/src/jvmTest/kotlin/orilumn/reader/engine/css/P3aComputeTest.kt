package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-a 计算层 guard：圆角 px/% 双轨、box/text-shadow、text-emphasis（含 -epub- 前缀）。
 */
class P3aComputeTest {

    private fun styles(root: MarkupElement, author: String): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun node(tag: String, children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, children = children)
        for (c in children) c.parent = el
        return el
    }

    @Test
    fun `radius px 与 pct 双轨`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { border-radius: 4px 50% }")[p]!!
        assertEquals(4f, m.borderRadius.topLeft, 1e-4f)
        assertEquals(4f, m.borderRadius.bottomRight, 1e-4f)
        assertEquals(0f, m.borderRadius.topRight, 1e-4f)
        assertEquals(0.5f, m.borderRadiusPct.topRight, 1e-4f)
        assertEquals(0.5f, m.borderRadiusPct.bottomLeft, 1e-4f)
        // clamp：50% OF min(w,h) 在 10x10 盒 = 5。
        val r = m.borderRadius.resolved(10, 10, m.borderRadiusPct)
        assertEquals(5f, r.topRight, 1e-4f)
        // overflow 折叠：8+8 > 10 → 等比压到 5+5。
        val big = CornerRadius(8f, 8f, 0f, 0f).resolved(10, 100)
        assertEquals(5f, big.topLeft, 1e-4f)
        assertEquals(5f, big.topRight, 1e-4f)
    }

    @Test
    fun `box-shadow 解析与 inset 丢弃`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { box-shadow: 2px 3px 4px #112233 }")[p]!!
        assertEquals(BoxShadow(2f, 3f, 4f, "#112233"), m.boxShadow)
        val inset = styles(root, "p { box-shadow: inset 2px 2px }")[p]!!
        assertNull("inset 无绘制口径，整声明丢弃", inset.boxShadow)
        val none = styles(root, "p { box-shadow: none }")[p]!!
        assertNull(none.boxShadow)
    }

    @Test
    fun `text-shadow 继承与 currentColor`() {
        val leaf = MarkupElement("#text", text = "x")
        val p = node("p", children = listOf(leaf))
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { text-shadow: 1px 1px }")
        // 无颜色即 currentColor（null），子文本节点继承。
        assertEquals(TextShadow(1f, 1f, 0f, null), m[p]!!.textShadow)
        assertEquals(TextShadow(1f, 1f, 0f, null), m[leaf]!!.textShadow)
    }

    @Test
    fun `emphasis 前缀与位置`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { -epub-text-emphasis-style: filled dot; -epub-text-emphasis-position: under }")[p]!!
        assertEquals(EmphasisStyle.DOT, m.emphasisStyle)
        assertTrue(m.emphasisUnder)
        val off = styles(root, "")[p]!!
        assertEquals(EmphasisStyle.NONE, off.emphasisStyle)
    }
}
