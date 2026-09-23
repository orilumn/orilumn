package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下划线单源：声明元素整段向后代传播（CSS 浏览器标准），无下划线叶零回归。
 * 回归 internallinks.epub：链接 `a{underline}` 在双端均无下划线。
 */
class UnderlineRunsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(uaSheet()), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun cls(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hid(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    @Test
    fun `link text is underlined via UA`() {
        val a = node("a", mapOf("href" to "#t"), listOf(text("表1")))
        val p = node("p", children = listOf(text("见"), a))
        node("body", children = listOf(p))
        val sm = styles(p.parent!!)
        assertEquals("见表1", NormalFlowLayout.leafText(p, sm, cls(sm), hid(sm)))
        assertEquals(listOf(UnderlineRun(1, 3)), NormalFlowLayout.leafUnderlineRuns(p, sm, cls(sm), hid(sm)))
    }

    @Test
    fun `underline propagates across nested inline`() {
        val u = node("u", children = listOf(text("ab"), node("span", children = listOf(text("cd"))), text("ef")))
        val p = node("p", children = listOf(text("x"), u, text("y")))
        node("body", children = listOf(p))
        val sm = styles(p.parent!!)
        assertEquals(listOf(UnderlineRun(1, 7)), NormalFlowLayout.leafUnderlineRuns(p, sm, cls(sm), hid(sm)))
    }

    @Test
    fun `block underline covers the whole leaf`() {
        val p = node("p", mapOf("style" to "text-decoration: underline"), listOf(text("all")))
        node("body", children = listOf(p))
        val sm = styles(p.parent!!)
        assertEquals(listOf(UnderlineRun(0, 3)), NormalFlowLayout.leafUnderlineRuns(p, sm, cls(sm), hid(sm)))
    }

    @Test
    fun `plain leaf has no runs`() {
        val p = node("p", children = listOf(text("plain")))
        node("body", children = listOf(p))
        val sm = styles(p.parent!!)
        assertTrue(NormalFlowLayout.leafUnderlineRuns(p, sm, cls(sm), hid(sm)).isEmpty())
    }
}
