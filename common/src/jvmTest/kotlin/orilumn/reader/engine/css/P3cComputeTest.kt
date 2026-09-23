package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.GeneratedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-c 计算层 guard：`content`/`quotes`/`counter-*` 解析＋继承＋伪元素分流。
 */
class P3cComputeTest {

    private fun engineFor(css: String): StyleComputer =
        StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs = attrs, children = children)
        for (c in children) c.parent = el
        return el
    }

    @Test
    fun `content 字符串与关键字`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = engineFor("p::before { content: \"注：\" }").compute(root)
        // 元素自身不受伪规则泄漏（P3-c 分流）。
        assertNull(m[p]!!.content)
        val engine = engineFor("p::before { content: \"注：\" }")
        val base = engine.compute(root)[p]!!
        val pseudo = engine.pseudoStyle(p, listOf(root), base, "before")
        assertEquals(listOf(ContentItem.Str("注：")), pseudo.content)
        // none/normal 即空。
        val none = engineFor("p::before { content: none }")
        val nbase = none.compute(root)[p]!!
        assertNull(none.pseudoStyle(p, listOf(root), nbase, "before").content)
    }

    @Test
    fun `content 函数项与非法整声明丢弃`() {
        val engine = engineFor(
            "a::after { content: \"[\" attr(href) \"]\" }" +
                "li::before { content: counter(item, upper-roman) \".\" }" +
                "ol::before { content: counters(sec, \".\") }" +
                "p::before { content: open-quote \"x\" close-quote }" +
                "b::before { content: url(x.png) }",
        )
        val a = node("a", attrs = mapOf("href" to "c1"))
        val li = node("li")
        val ol = node("ol")
        val p = node("p")
        val b = node("b")
        val root = node("body", children = listOf(a, li, ol, p, b))
        val map = engine.compute(root)
        fun pseudo(el: MarkupElement, w: String) = engine.pseudoStyle(el, listOf(root), map[el]!!, w).content
        assertEquals(
            listOf(ContentItem.Str("["), ContentItem.Attr("href"), ContentItem.Str("]")),
            pseudo(a, "after"),
        )
        assertEquals(
            listOf(ContentItem.Counter("item", "upper-roman"), ContentItem.Str(".")),
            pseudo(li, "before"),
        )
        assertEquals(listOf(ContentItem.Counters("sec", ".", "decimal")), pseudo(ol, "before"))
        assertEquals(
            listOf(ContentItem.OpenQuote, ContentItem.Str("x"), ContentItem.CloseQuote),
            pseudo(p, "before"),
        )
        // url() 无字符口径：整声明丢弃。
        assertNull(pseudo(b, "before"))
    }

    @Test
    fun `quotes 成对与继承`() {
        val leaf = MarkupElement("#text", text = "x")
        val q = node("q", children = listOf(leaf))
        val root = node("body", children = listOf(q))
        val m = engineFor("body { quotes: \"«\" \"»\" \"‹\" \"›\" }").compute(root)
        assertEquals(listOf("«", "»", "‹", "›"), m[q]!!.quotes)
        assertEquals("继承到文本节点", listOf("«", "»", "‹", "›"), m[leaf]!!.quotes)
        val def = engineFor("").compute(root)[q]
        assertNull("默认无声明", def!!.quotes)
    }

    @Test
    fun `counter reset increment 与默认值`() {
        val span = node("span")
        val li = node("li", children = listOf(span))
        val ol = node("ol", children = listOf(li))
        val root = node("body", children = listOf(ol))
        val css = "ol { counter-reset: item 0 sec } li { counter-increment: item }"
        val m = engineFor(css).compute(root)
        assertEquals(mapOf("item" to 0, "sec" to 0), m[ol]!!.counterReset)
        assertEquals(mapOf("item" to 1), m[li]!!.counterIncrement)
        // 非继承：子 span 与文本侧无值。
        assertNull(m[span]!!.counterReset)
        assertNull(m[span]!!.counterIncrement)
    }

    @Test
    fun `伪元素继承原发元素 before after 互不串`() {        val engine = engineFor("p::before { content: \"A\" } p::after { content: \"B\" } p { color: #ff0000 }")
        val p = node("p")
        val root = node("body", children = listOf(p))
        val map = engine.compute(root)
        val base = map[p]!!
        val before = engine.pseudoStyle(p, listOf(root), base, "before")
        val after = engine.pseudoStyle(p, listOf(root), base, "after")
        assertEquals(listOf(ContentItem.Str("A")), before.content)
        assertEquals(listOf(ContentItem.Str("B")), after.content)
        // 伪元素继承原发色（浏览器同式）。
        assertEquals("#ffff0000", before.colorHex)
        assertEquals("#ffff0000", after.colorHex)
        // 无伪规则即回基址（同一实例）。
        val bare = engineFor("p { color: #ff0000 }")
        val bmap = bare.compute(root)
        assertTrue(bare.pseudoStyle(p, listOf(root), bmap[p]!!, "before") === bmap[p])
    }

    @Test
    fun `phase 门控两个版本同向`() {
        val css = "li::before { content: counter(item) }"
        assertTrue(GeneratedContent.needsPhase(listOf(LightCssParser().parse(css))))
        assertTrue(GeneratedContent.needsPhaseTexts(listOf(css)))
        assertFalse(GeneratedContent.needsPhase(listOf(LightCssParser().parse("p { color: red }"))))
        assertFalse(GeneratedContent.needsPhaseTexts(listOf("p { color: red }")))
        // 元素级 content（无伪）不开 phase（伪元素消费不到，用不上求值）。
        assertFalse(GeneratedContent.needsPhase(listOf(LightCssParser().parse("p { content: \"x\" }"))))
    }
}
