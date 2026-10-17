package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内盒单源：**有可见装饰的行内元素**投影成叶文本坐标系的 [InlineBoxRun]。
 *
 * 关键纪律（与 [UnderlineRunsTest] 对照）：
 *  - **盒属性不继承**（与下划线相反）：`<mark><em>a</em></mark>` 只有 `<mark>` 一个盒；
 *  - **块元素不发 run**（块有自己的盒路径，重复发就是双画）；
 *  - 无装饰行内元素（`<em>`/`<a>`）回空表（零开销）；
 *  - 嵌套盒各发一条（外先内后）。
 */
class InlineBoxRunsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun node(
        tag: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<MarkupElement> = emptyList(),
    ): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse(uaSheet()),
            authorSheets = listOf(LightCssParser().parse(author)),
        ).compute(root)

    private fun cls(styleMap: Map<MarkupElement, ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hid(styleMap: Map<MarkupElement, ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    private fun runs(root: MarkupElement, author: String = ""): List<InlineBoxRun> {
        val sm = styles(root, author)
        return NormalFlowLayout.leafInlineBoxRuns(root, sm, cls(sm), hid(sm))
    }

    @Test
    fun `mark gets a box via UA background`() {
        val m = node("mark", children = listOf(text("重点")))
        val p = node("p", children = listOf(text("见"), m, text("。尾")))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(1, out.size)
        val r = out.single()
        assertEquals(1, r.start)
        assertEquals(3, r.endExclusive)
        assertTrue("UA 给 <mark> 铺了底色", r.bgHex != null)
        assertTrue(r.visible)
    }

    @Test
    fun `span background box covers only its own text`() {
        val s = node("span", mapOf("style" to "background-color:#ffcc00"), listOf(text("hi")))
        val p = node("p", children = listOf(text("a"), s, text("b")))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(listOf(1 to 3), out.map { it.start to it.endExclusive })
    }

    @Test
    fun `box attribute does not inherit to descendants`() {
        val inner = node("em", children = listOf(text("cd")))
        val m = node("mark", children = listOf(text("ab"), inner, text("ef")))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        // 只有 <mark> 一个盒，覆盖整段（后代文本都属于它的盒）；<em> 无装饰不发 run。
        val out = runs(p)
        assertEquals(1, out.size)
        assertEquals(0, out.single().start)
        assertEquals(6, out.single().endExclusive)
    }

    @Test
    fun `nested boxes both emitted outer first`() {
        val inner = node("em", mapOf("style" to "background-color:#00ff00"), listOf(text("cd")))
        val m = node("mark", children = listOf(text("ab"), inner))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(2, out.size)
        // 同起点时外层盒（end 大）在前：绘制外层背景先落。
        assertEquals(0 to 4, out[0].start to out[0].endExclusive)
        assertEquals(2 to 4, out[1].start to out[1].endExclusive)
    }

    @Test
    fun `block background is not emitted as an inline box`() {
        // 块的背景走盒路径；这里绝不能重复发 run。
        val p = node("p", mapOf("style" to "background-color:#eeeeee"), listOf(text("all")))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }

    @Test
    fun `border only element is emitted`() {
        val s = node(
            "span",
            mapOf("style" to "border-bottom:1px solid #000"),
            listOf(text("ul")),
        )
        val p = node("p", children = listOf(s))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(1, out.size)
        assertTrue(out.single().hasBorderWidth)
        assertEquals(0f, out.single().bgHex?.let { 1f } ?: 0f, 0f)
    }

    @Test
    fun `plain leaf has no runs`() {
        val p = node("p", children = listOf(text("plain"), node("em", children = listOf(text("x")))))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }

    @Test
    fun `box spans sibling text nodes contiguously`() {
        val m = node("mark", children = listOf(text("ab"), text("cd")))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(listOf(0 to 4), out.map { it.start to it.endExclusive })
    }
}
