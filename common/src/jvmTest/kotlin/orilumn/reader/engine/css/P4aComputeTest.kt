package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-a1: `float`/`clear` 计算层（纯数据，零行为变化）+ 表示型属性进级联
 * （`br clear` → clear，`img align` → float）。
 */
class P4aComputeTest {

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun compute(root: MarkupElement, ua: String = "", author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(16f, LightCssParser().parse(ua), listOf(LightCssParser().parse(author))).compute(root)

    private fun solo(tag: String, attrs: Map<String, String> = emptyMap(), author: String = ""): ComputedStyle {
        val el = node(tag, attrs)
        val root = node("body", children = listOf(el))
        return compute(root, author = author)[el]!!
    }

    @Test
    fun `float 解析与默认值`() {
        assertEquals(FloatSide.NONE, solo("img").floatSide)
        assertEquals(FloatSide.LEFT, solo("img", author = "img { float: left; }").floatSide)
        assertEquals(FloatSide.RIGHT, solo("img", author = "img { float: RIGHT; }").floatSide)
        assertEquals(FloatSide.NONE, solo("img", author = "img { float: none; }").floatSide)
        assertEquals(FloatSide.NONE, solo("img", author = "img { float: center; }").floatSide)
    }

    @Test
    fun `clear 解析与逻辑别名`() {
        assertEquals(ClearSide.NONE, solo("p").clearSide)
        assertEquals(ClearSide.BOTH, solo("p", author = "p { clear: both; }").clearSide)
        assertEquals(ClearSide.LEFT, solo("p", author = "p { clear: left; }").clearSide)
        assertEquals(ClearSide.RIGHT, solo("p", author = "p { clear: right; }").clearSide)
        assertEquals(ClearSide.LEFT, solo("p", author = "p { clear: inline-start; }").clearSide)
        assertEquals(ClearSide.RIGHT, solo("p", author = "p { clear: inline-end; }").clearSide)
        assertEquals(ClearSide.NONE, solo("p", author = "p { clear: all; }").clearSide)
    }

    @Test
    fun `float 与 clear 不继承`() {
        val kid = node("span")
        val parent = node("div", children = listOf(kid))
        val root = node("body", children = listOf(parent))
        val out = compute(root, author = "div { float: left; clear: both; }")
        assertEquals(FloatSide.LEFT, out[parent]?.floatSide)
        assertEquals(ClearSide.BOTH, out[parent]?.clearSide)
        assertEquals(FloatSide.NONE, out[kid]?.floatSide)
        assertEquals(ClearSide.NONE, out[kid]?.clearSide)
    }

    @Test
    fun `br clear 属性进级联`() {
        assertEquals(ClearSide.BOTH, solo("br", mapOf("clear" to "all")).clearSide)
        assertEquals(ClearSide.LEFT, solo("br", mapOf("clear" to "left")).clearSide)
        assertEquals(ClearSide.RIGHT, solo("br", mapOf("clear" to "right")).clearSide)
        assertEquals(ClearSide.NONE, solo("br", mapOf("clear" to "nonsense")).clearSide)
        // 作者样式恒压表示层。
        assertEquals(
            ClearSide.NONE,
            solo("br", mapOf("clear" to "all"), author = "br { clear: none; }").clearSide,
        )
    }

    @Test
    fun `img align 属性进级联为 float`() {
        assertEquals(FloatSide.LEFT, solo("img", mapOf("align" to "left")).floatSide)
        assertEquals(FloatSide.RIGHT, solo("img", mapOf("align" to "right")).floatSide)
        // center 无浮动等价（通用 align→text-align 映射不受影响，此处只验 float 为空）。
        assertEquals(FloatSide.NONE, solo("img", mapOf("align" to "center")).floatSide)
        // 作者 float 压过 align 表示层。
        assertEquals(
            FloatSide.RIGHT,
            solo("img", mapOf("align" to "left"), author = "img { float: right; }").floatSide,
        )
    }

    @Test
    fun `解析层保留 img align`() {
        val root = HtmlTreeConverter().convert("<p>a<img align=\"left\" src=\"x.png\"/>b</p>")!!
        val p = root.children.first { it.tag == "p" }
        val img = p.children.first { it.tag == "img" }
        assertEquals("left", img.attrs["align"])
        assertTrue(img.attrs.containsKey("src"))
    }
}
