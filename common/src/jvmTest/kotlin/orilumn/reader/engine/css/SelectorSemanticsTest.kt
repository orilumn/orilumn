package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二刀：**选择器语义**（对齐浏览器）。
 *
 * 起因是两条画错级欠账：
 * - 伪类条件被整条丢弃 —— `:not()`/`:is()`/`:where()`/`:has()` 与结构伪类**恒真**，
 *   `p:first-child{margin-top:0}` 会套到全部段落；
 * - 非 `::before/::after` 的伪元素泄漏到元素本体 —— `p::first-letter{font-size:3em}` 让**整段**放大。
 *
 * 判据只有一条：**规则必须落在浏览器认定的那批元素上**（不套错）。
 * 未知/有状态伪类一律**不匹配**（宁可丢规则，不可过度匹配）。
 */
class SelectorSemanticsTest {

    // ------------------------------------------------------------------
    // 骨架
    // ------------------------------------------------------------------

    private fun tree(xhtml: String): MarkupElement = HtmlTreeConverter().convert(xhtml)!!

    private fun sel(text: String): Selector = requireNotNull(Selector.parse(text)) { "选择器解析失败: $text" }

    /** 祖先链（root → parent，nearest last），与 [StyleComputer] 传给级联的形状一致。 */
    private fun ancestors(el: MarkupElement): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        var n = el.parent
        while (n != null) { out.add(n); n = n.parent }
        out.reverse()
        return out
    }

    private fun matches(selector: String, el: MarkupElement): Boolean = sel(selector).matches(el, ancestors(el))

    private fun MarkupElement.descendants(): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        fun walk(n: MarkupElement) {
            for (c in n.children) { out.add(c); walk(c) }
        }
        walk(this)
        return out
    }

    private fun MarkupElement.ofTag(tag: String): List<MarkupElement> = descendants().filter { it.tag == tag }

    private fun MarkupElement.elementChildren(): List<MarkupElement> = children.filter { it.tag != "#text" }

    private fun cascade(author: String, xhtml: String): Pair<MarkupElement, Map<MarkupElement, ComputedStyle>> {
        val root = tree(xhtml)
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse(author)))
        return root to engine.compute(root)
    }

    // ------------------------------------------------------------------
    // :not / :is / :where
    // ------------------------------------------------------------------

    @Test
    fun `not really negates`() {
        val root = tree("<p class='k'>a</p><p>b</p>")
        val ps = root.ofTag("p")
        assertFalse(":not(.k) 不该命中 .k", matches("p:not(.k)", ps[0]))
        assertTrue(matches("p:not(.k)", ps[1]))
    }

    @Test
    fun `not accepts a selector list and combinators`() {
        val root = tree("<div class='box'><p class='a'>1</p></div><p>2</p>")
        val inner = root.ofTag("p").first { it.attrs["class"] == "a" }
        val outer = root.ofTag("p").first { it.attrs["class"] == null }

        assertFalse("列表里任一命中即取反", matches("p:not(.a, .b)", inner))
        assertTrue(matches("p:not(.b)", inner))
        assertTrue("无 class 的 p 当然不是 .b", matches("p:not(.b)", outer))
        assertTrue(matches("p:not(.a, .b)", outer))
        // 组合器作参数（括号内的空白不能被当成组合器切分）
        assertFalse("div 内的 p 被排除", matches("p:not(div p)", inner))
        assertTrue(matches("p:not(div p)", outer))
    }

    @Test
    fun `is and where take the union`() {
        val root = tree("<h1>t</h1><h2>s</h2><h3>u</h3>")
        val h1 = root.ofTag("h1").first()
        val h3 = root.ofTag("h3").first()
        assertTrue(matches(":is(h1, h2)", h1))
        assertTrue(matches(":matches(h1, h2)", root.ofTag("h2").first()))
        assertTrue(matches(":where(h1, h2)", h1))
        assertFalse(matches(":is(h1, h2)", h3))
        assertFalse(matches(":where(h2, h2)", h3))
        // 参数全无效 ⇒ 整条不匹配（不外溢到全部元素）
        assertFalse(matches(":is(> p)", h3))
        assertFalse(matches(":where()", h3))
    }

    @Test
    fun `has searches the right direction`() {
        val root = tree(
            "<div id='a'><section><p>x</p></section></div>" +
                "<div id='b'><span>y</span></div>" +
                "<p id='c'>1</p><p id='d'>2</p>",
        )
        val a = root.descendants().first { it.attrs["id"] == "a" }
        val b = root.descendants().first { it.attrs["id"] == "b" }
        val c = root.descendants().first { it.attrs["id"] == "c" }
        val d = root.descendants().first { it.attrs["id"] == "d" }

        // 后代（缺省）与子代
        assertTrue(matches("div:has(p)", a))
        assertTrue(matches("div:has(section p)", a))
        assertFalse(matches("div:has(> p)", a))
        assertFalse(matches("div:has(p)", b))
        assertTrue(matches("div:has(> span)", b))
        // 相邻兄弟 / 后续兄弟
        assertTrue("a 的下一个元素兄弟是 div", matches("div:has(+ div)", a))
        assertFalse(matches("div:has(+ div)", b))
        assertTrue(matches("div:has(+ p)", b))
        assertTrue(matches("div:has(~ p)", a))
        assertTrue(matches("p:has(+ p)", c))
        assertFalse(matches("p:has(+ p)", d))
        assertFalse(matches("p:has(~ p)", d))
        // 空参数不匹配
        assertFalse(matches("div:has()", a))
    }

    // ---------- 结构伪类 ----------

    @Test
    fun `structural pseudo classes follow document order`() {
        val root = tree("<div><h1>t</h1><p>1</p><p>2</p><span>s</span></div>")
        val div = root.ofTag("div").first()
        val kids = div.elementChildren()
        val h1 = kids[0]
        val p1 = kids[1]
        val p2 = kids[2]
        val span = kids[3]

        assertTrue(matches(":first-child", h1))
        assertFalse(matches(":first-child", p1))
        assertTrue(matches(":last-child", span))
        assertFalse(matches(":last-child", p2))
        assertTrue(matches(":nth-child(2)", p1))
        assertTrue(matches(":nth-child(3)", p2))
        assertFalse(matches(":nth-child(3)", p1))
        assertTrue(matches(":nth-child(odd)", h1))
        assertTrue(matches(":nth-child(even)", p1))
        assertFalse(matches(":nth-child(even)", h1))
        assertTrue(matches(":nth-child(2n+1)", p2))
        assertTrue(matches(":nth-child(-n+2)", p1))
        assertFalse(matches(":nth-child(-n+2)", p2))
        assertTrue(matches(":nth-last-child(1)", span))
        assertTrue(matches(":nth-last-child(2)", p2))
        assertFalse(matches(":only-child", h1))
        // 类型序（同标签才计数）
        assertTrue(matches("h1:first-of-type", h1))
        assertTrue(matches("h1:last-of-type", h1))
        assertTrue(matches("p:first-of-type", p1))
        assertFalse(matches("p:first-of-type", p2))
        assertTrue(matches("p:nth-of-type(2)", p2))
        assertFalse(matches("p:nth-of-type(2)", p1))
        assertTrue(matches("p:nth-last-of-type(1)", p2))
        assertTrue(matches("span:last-of-type", span))
        assertFalse(matches("p:only-of-type", p1))
    }

    @Test
    fun `only-child and only-of-type on the sole element`() {
        val root = tree("<div><p>only</p></div>")
        val p = root.ofTag("p").first()
        assertTrue(matches(":only-child", p))
        assertTrue(matches("p:only-of-type", p))
        assertTrue(matches("p:first-child", p))
        assertTrue(matches("p:last-child", p))
    }

    @Test
    fun `nth-child tolerates spaces and rejects the of clause`() {
        val root = tree("<div><p>1</p><p>2</p><p>3</p></div>")
        val ps = root.ofTag("p")
        assertTrue(matches(":nth-child(2n + 1)", ps[0]))
        assertFalse(matches(":nth-child(2n + 1)", ps[1]))
        assertTrue(matches(":nth-child( 3 )", ps[2]))
        // Selectors 4 的 `of S` 子句不支持 ⇒ 恒假（宁可丢规则，不做过匹配）
        assertFalse(matches(":nth-child(2 of .x)", ps[1]))
        assertFalse(matches(":nth-child(bogus)", ps[1]))
    }

    @Test
    fun `text nodes do not count as element siblings`() {
        val root = tree("<div>leading <b>x</b> gap <i>y</i></div>")
        val b = root.ofTag("b").first()
        val i = root.ofTag("i").first()
        assertTrue(matches("b:first-child", b))
        assertTrue(matches("b:first-of-type", b))
        assertTrue(matches("i:last-child", i))
        assertTrue(matches(":nth-child(2)", i))
        assertFalse(matches(":nth-child(2)", b))
    }

    @Test
    fun `root scope and empty`() {
        val root = tree("<p></p><p> </p><div><span></span></div>")
        assertTrue(matches("body:root", root))
        assertTrue(matches(":scope", root))
        assertFalse(matches(":root", root.ofTag("p").first()))
        val ps = root.ofTag("p")
        assertTrue("无子节点即空", matches("p:empty", ps[0]))
        // `<p> </p>` 的空白文本算内容（CSS Selectors 3 口径）
        assertFalse(matches("p:empty", ps[1]))
        assertFalse(matches("div:empty", root.ofTag("div").first()))
        assertTrue(matches("span:empty", root.ofTag("span").first()))
    }

    @Test
    fun `lang matches itself or an ancestor with prefix filtering`() {
        val root = tree("<div lang='en-GB'><p>x</p></div><p>y</p>")
        val ps = root.ofTag("p")
        assertTrue(matches("p:lang(en)", ps[0]))
        assertTrue(matches("p:lang(en-gb)", ps[0]))
        assertFalse(matches("p:lang(en)", ps[1]))
        assertFalse("前缀不是任意子串", matches("p:lang(gb)", ps[0]))
        assertTrue(matches("div:lang('en')", root.ofTag("div").first()))
    }

    @Test
    fun `stateful and unknown pseudo classes never match`() {
        val root = tree("<p id='p1'>x</p>")
        val p = root.ofTag("p").first()
        val names = listOf(
            "target", "target-within", "focus-visible", "enabled", "disabled", "checked",
            "required", "valid", "placeholder-shown", "playing", "bogus", "future",
        )
        for (name in names) assertFalse(":$name 不该命中", matches("p:$name", p))
    }

    @Test
    fun `non before after pseudo elements never match the element itself`() {
        val root = tree("<p>x</p><li>y</li>")
        val p = root.ofTag("p").first()
        val li = root.ofTag("li").first()
        assertTrue(matches("p::first-letter", p))
        assertFalse(matches("p::first-line", p))
        assertTrue("单冒号旧写法同样按伪元素", matches("p:first-letter", p))
        assertFalse(matches("p::selection", p))
        assertFalse(matches("li::marker", li))
        assertNull("非 before/after 不参与伪元素分流", sel("p::first-line").pseudoElement)
        assertEquals("first-letter", sel("p::first-letter").pseudoElement)
    }

    @Test
    fun `before and after still route to their pseudo element`() {
        val root = tree("<p>x</p>")
        val p = root.ofTag("p").first()
        assertEquals("before", sel("p::before").pseudoElement)
        assertEquals("after", sel("p:after").pseudoElement)
        assertTrue("分流靠级联按伪元素筛，matches 本身要认", sel("p::before").matches(p, ancestors(p)))
        assertTrue(sel("p::after").matches(p, ancestors(p)))
    }

    @Test
    fun `specificity follows selectors level 4`() {
        assertEquals(Selector.Specificity(0, 0, 0), sel(":where(#a)").specificity)
        assertEquals(Selector.Specificity(1, 0, 0), sel(":is(#a, .b)").specificity)
        assertEquals(Selector.Specificity(0, 1, 0), sel(":is(.a, .b)").specificity)
        assertEquals(Selector.Specificity(0, 1, 0), sel(":not(.a)").specificity)
        assertEquals(Selector.Specificity(0, 1, 1), sel("p:not(.a)").specificity)
        assertEquals(Selector.Specificity(1, 0, 0), sel(":has(#a)").specificity)
        assertEquals(Selector.Specificity(0, 1, 1), sel("p:nth-child(2n+1)").specificity)
        assertEquals(Selector.Specificity(0, 0, 2), sel("p::before").specificity)
    }

    // ---------- 级联级（规则真的要落在对的那批元素上） ----------

    @Test
    fun `cascade - where has zero specificity so the class wins`() {
        val (root, style) = cascade(":where(#x) { color: #111111 } .c { color: #222222 }", "<p id='x' class='c'>t</p>")
        val p = root.ofTag("p").first()
        assertEquals("#ff222222", style[p]!!.colorHex)
    }

    @Test
    fun `cascade - is keeps the id specificity so it wins over the class`() {
        val (root, style) = cascade(":is(#x) { color: #111111 } .c { color: #222222 }", "<p id='x' class='c'>t</p>")
        val p = root.ofTag("p").first()
        assertEquals("#ff111111", style[p]!!.colorHex)
    }

    @Test
    fun `cascade - first-child only hits the first paragraph`() {
        val (root, style) = cascade("p { margin-top: 8px } p:first-child { margin-top: 0 }", "<p>a</p><p>b</p>")
        val ps = root.ofTag("p")
        assertEquals(0f, style[ps[0]]!!.margin.top, 0.01f)
        assertEquals(8f, style[ps[1]]!!.margin.top, 0.01f)
    }

    @Test
    fun `cascade - not exempts the flagged paragraph`() {
        val (root, style) = cascade(
            "p { text-indent: 2em } p:not(.noindent) { text-indent: 0 }",
            "<p class='noindent'>a</p><p>b</p>",
        )
        val ps = root.ofTag("p")
        assertEquals(32f, style[ps[0]]!!.textIndentPx, 0.01f) // 2em × 16px
        assertEquals(0f, style[ps[1]]!!.textIndentPx, 0.01f)
    }

    @Test
    fun `cascade - first-letter and first-line no longer touch the paragraph`() {
        val (root, style) = cascade(
            "p::first-line { font-size: 3em } p::first-letter { font-size: 3em; color: #ff0000 }",
            "<p>abc</p>",
        )
        val p = root.ofTag("p").first()
        assertEquals("整段字号不变", 16f, style[p]!!.fontSizePx, 0.01f)
        assertNull("整段颜色不变", style[p]!!.colorHex)
    }

    @Test
    fun `cascade - root reaches the chapter body`() {
        val (root, style) = cascade(":root { color: #00aa00 }", "<p>x</p>")
        assertEquals("#ff00aa00", style[root]!!.colorHex)
    }

    @Test
    fun `cascade - before still applies to its pseudo element only`() {
        val root = tree("<p>x</p>")
        val p = root.ofTag("p").first()
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse("p::before { color: #123456 }")))
        val base = engine.compute(p)
        assertNull("元素本体不吃 ::before 规则", base[p]!!.colorHex)
        val pseudo = engine.pseudoStyle(p, ancestors(p), base[p]!!, "before")
        assertEquals("#ff123456", pseudo.colorHex)
    }
}
