package orilumn.reader.engine.layout

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM specs for [ListMarkers]: node attribution, ol numbering (start/reversed/floor), and the
 * five ordered-list formats + bullets. No Android dependency, so it runs without Robolectric.
 */
class ListMarkersTest {

    private fun li(text: String, index: Int = 0) = MarkupElement("li", children = listOf(MarkupElement("#text", text = text)))

    private fun ul(vararg items: MarkupElement) = MarkupElement("ul", children = items.toList())

    private fun ol(vararg items: MarkupElement, start: Int? = null, reversed: Boolean = false): MarkupElement {
        val attrs = HashMap<String, String>()
        start?.let { attrs["start"] = "$it" }
        if (reversed) attrs["reversed"] = "true"
        return MarkupElement("ol", attrs = attrs, children = items.toList())
    }

    /* ------------------------------------------------ attribution ------------------------------------------------ */

    @Test
    fun `liOf returns the li for a simple li leaf and the parent for its anonymous text leaf`() {
        val liEl = li("a")
        assertEquals(liEl, ListMarkers.liOf(liEl))
        assertNull(ListMarkers.liOf(MarkupElement("p")))
        assertNull(ListMarkers.liOf(MarkupElement("#text", text = "x"))) // no parent chain
        val body = MarkupElement("body")
        val anon = MarkupElement("#text", text = "a").apply { parent = liEl }
        liEl.parent = body
        assertEquals(liEl, ListMarkers.liOf(anon))
    }

    /* ------------------------------------------------ ul bullets + defaults ------------------------------------------------ */

    @Test
    fun `ul defaults to disc and honors explicit types`() {
        val liEl = li("a"); val listEl = ul(liEl); liEl.parent = listEl
        assertNull(ListMarkers.kindOf(listEl, null))
        assertEquals("undefined keyword → null (specOf falls back to the type default)",
            null, ListMarkers.kindOf(listEl, ""))
        assertEquals(ListMarkers.Kind.DISC, ListMarkers.defaultKind(listEl))
        assertEquals(ListMarkers.Kind.CIRCLE, ListMarkers.kindOf(listEl, "circle"))
        assertEquals(ListMarkers.Kind.SQUARE, ListMarkers.kindOf(listEl, "square"))
        assertNull(ListMarkers.kindOf(listEl, "none"))
        assertNull(ListMarkers.kindOf(listEl, "bogus"))
    }

    @Test
    fun `ol defaults to decimal`() {
        val liEl = li("a"); val listEl = ol(liEl); liEl.parent = listEl
        assertEquals(ListMarkers.Kind.DECIMAL, ListMarkers.defaultKind(listEl))
    }

    @Test
    fun `specOf resolves markers and respects list-style-type none`() {
        val liEl = li("a"); val listEl = ul(liEl); liEl.parent = listEl
        val m = ListMarkers.specOf(liEl, { null })
        assertEquals(ListMarkers.Kind.DISC, m?.kind)
        assertEquals("•", m?.text)
        assertEquals(1, m?.level)
        // ul with list-style-type: none → no marker.
        assertNull(ListMarkers.specOf(liEl, { ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, listStyleType = "none") }))
    }

    /* ------------------------------------------------ ol numbering ------------------------------------------------ */

    @Test
    fun `ol numbers start at 1 and increment`() {
        val a = li("a"); val b = li("b"); val listEl = ol(a, b); a.parent = listEl!!; b.parent = listEl
        assertEquals(1, ListMarkers.numberFor(listEl, 1))
        assertEquals(2, ListMarkers.numberFor(listEl, 2))
        assertEquals("2", ListMarkers.specOf(b, { null })?.text)
    }

    @Test
    fun `ol respects start attribute`() {
        val a = li("a"); val listEl = ol(a, start = 7); a.parent = listEl
        assertEquals(7, ListMarkers.numberFor(listEl, 1))
        assertEquals(8, ListMarkers.numberFor(listEl, 2))
        assertEquals("7", ListMarkers.specOf(a, { null })?.text)
    }

    @Test
    fun `ol reversed counts down from start and floors at 1`() {
        val a = li("a"); val b = li("b"); val listEl = ol(a, b, start = 3, reversed = true); a.parent = listEl!!; b.parent = listEl
        assertEquals(3, ListMarkers.numberFor(listEl, 1))
        assertEquals(2, ListMarkers.numberFor(listEl, 2))
        assertEquals(1, ListMarkers.numberFor(listEl, 3))
        assertEquals("3", ListMarkers.specOf(a, { null })?.text)
        assertEquals("2", ListMarkers.specOf(b, { null })?.text)
    }

    @Test
    fun `reversed never goes below 1`() {
        val a = li("a"); val listEl = ol(a, start = 1, reversed = true); a.parent = listEl
        assertEquals(1, ListMarkers.numberFor(listEl, 1))
        assertEquals(1, ListMarkers.numberFor(listEl, 2))
    }

    @Test
    fun `li value 改号从该项起生效且后续顺延`() {
        fun liV(text: String, value: Int? = null) = MarkupElement(
            "li",
            attrs = value?.let { mapOf("value" to "$it") } ?: emptyMap(),
            children = listOf(MarkupElement("#text", text = text)),
        )
        // <ol><li value=5>…</li><li>…</li></ol>：value 覆盖 start，该项显示 5，后续顺延 6。
        val a = liV("a", value = 5)
        val b = liV("b")
        val listEl = ol(a, b)
        a.parent = listEl; b.parent = listEl
        assertEquals(5, ListMarkers.numberForItem(listEl, a))
        assertEquals(6, ListMarkers.numberForItem(listEl, b))
        assertEquals("5", ListMarkers.specOf(a, { null })?.text)
        assertEquals("6", ListMarkers.specOf(b, { null })?.text)
        // reversed + value：从 value 向下递减（后续项 8）。
        val c = liV("c", value = 9)
        val d = liV("d")
        val rev = ol(c, d, start = 12, reversed = true)
        c.parent = rev; d.parent = rev
        assertEquals(9, ListMarkers.numberForItem(rev, c))
        assertEquals(8, ListMarkers.numberForItem(rev, d))
    }

    @Test
    fun `decimal-leading-zero pads`() {
        assertEquals("07", ListMarkers.markerText(ListMarkers.Kind.DECIMAL_LEADING_ZERO, 7))
        assertEquals("10", ListMarkers.markerText(ListMarkers.Kind.DECIMAL_LEADING_ZERO, 10))
    }

    /* ------------------------------------------------ alpha / roman ------------------------------------------------ */

    @Test
    fun `alpha is bijective base-26`() {
        assertEquals("a", ListMarkers.toAlpha(1))
        assertEquals("z", ListMarkers.toAlpha(26))
        assertEquals("aa", ListMarkers.toAlpha(27))
        assertEquals("ab", ListMarkers.toAlpha(28))
        assertEquals("abc", ListMarkers.toAlpha(731))
        assertEquals("A", ListMarkers.markerText(ListMarkers.Kind.UPPER_ALPHA, 1))
        assertEquals("Z", ListMarkers.markerText(ListMarkers.Kind.UPPER_ALPHA, 26))
        assertEquals("i", ListMarkers.markerText(ListMarkers.Kind.LOWER_ALPHA, 9))
    }

    @Test
    fun `roman handles subtractive forms`() {
        assertEquals("i", ListMarkers.toRoman(1))
        assertEquals("iv", ListMarkers.toRoman(4))
        assertEquals("ix", ListMarkers.toRoman(9))
        assertEquals("xl", ListMarkers.toRoman(40))
        assertEquals("xc", ListMarkers.toRoman(90))
        assertEquals("cdxcix", ListMarkers.toRoman(499))
        assertEquals("mcmxcix", ListMarkers.toRoman(1999))
        assertEquals("V", ListMarkers.markerText(ListMarkers.Kind.UPPER_ROMAN, 5))
    }

    @Test
    fun `marker count ignores sibling li ordering for ul`() {
        // A circle ul: all items share the symbol.
        val a = li("a"); val b = li("b"); val listEl = ul(a, b); a.parent = listEl!!; b.parent = listEl
        assertEquals("•", ListMarkers.specOf(b, { null })?.text)
    }

    /* ------------------------------------------------ li > p attribution + first-carrier gating ------------------------------------------------ */

    @Test
    fun `liOf walks ancestors to the owning li for a p-wrapped item`() {
        val pEl = p("a")
        val liEl = li(pEl); pEl.parent = liEl
        assertEquals(liEl, ListMarkers.liOf(pEl))
        // Nested item: the nearest li wins, never the outer one.
        val innerText = MarkupElement("#text", text = "b")
        val innerLi = MarkupElement("li", children = listOf(innerText))
        val innerUl = MarkupElement("ul", children = listOf(innerLi))
        innerText.parent = innerLi; innerLi.parent = innerUl
        assertEquals(innerLi, ListMarkers.liOf(innerText))
    }

    @Test
    fun `only the first leaf per li carries a marker`() {
        // <ul><li>text</li><li><p>a</p><p>b</p></li></ul> — the second <p> must NOT get a second bullet.
        val li1 = li("x")
        val pA = p("a"); val pB = p("b")
        val li2 = li(pA, pB); pA.parent = li2; pB.parent = li2
        val ul = ul(li1, li2); li1.parent = ul; li2.parent = ul
        val carriers = ListMarkers.firstCarrierSet(listOf(li1, pA, pB))
        assertEquals(setOf(li1, pA), carriers)
        assertFalse(pB in carriers)
        // specOf still resolves the marker for the first p leaf (the engine gates who reaches it).
        val m = ListMarkers.specOf(pA, { null })
        assertEquals(ListMarkers.Kind.DISC, m?.kind)
        assertEquals("•", m?.text)
    }

    @Test
    fun `nested list leaves carry their own markers`() {
        // <li><p>outer</p><ul><li><p>inner</p></li></ul></li>
        val outerP = p("outer")
        val innerP = p("inner")
        val innerLi = li(innerP); innerP.parent = innerLi
        val innerUl = ul(innerLi); innerLi.parent = innerUl
        val outerLi = li(outerP, innerUl); outerP.parent = outerLi; innerUl.parent = outerLi
        val carriers = ListMarkers.firstCarrierSet(listOf(outerP, innerP))
        assertEquals(setOf(outerP, innerP), carriers)
    }

    @Test
    fun `markerForLeaf gates on the first carrier leaf only`() {
        // <li><p>a</p><p>b</p></li>：只有首个载体叶（a 所在 p）拿 marker，P2 两端共用同一标.
        val outerP = p("a"); val second = p("b")
        val outerLi = li(outerP, second); outerP.parent = outerLi; second.parent = outerLi
        val listEl = ul(outerLi); outerLi.parent = listEl
        val carriers = ListMarkers.firstCarrierSet(listOf(outerP, second))
        assertEquals(setOf(outerP), carriers)
        assertEquals(ListMarkers.Kind.DISC, ListMarkers.markerForLeaf(outerP, carriers, { null })?.kind)
        assertNull("同一 li 的后续叶不挂 marker", ListMarkers.markerForLeaf(second, carriers, { null }))
        assertNull("null 叶直接 null", ListMarkers.markerForLeaf(null, carriers, { null }))
        assertNull("非 li 上下文 null", ListMarkers.markerForLeaf(MarkupElement("p"), setOf(MarkupElement("p")), { null }))
        // list-style-type: none 仍经 specOf 落到 null.
        assertNull(
            ListMarkers.markerForLeaf(
                outerP, carriers,
                { ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, listStyleType = "none") },
            ),
        )
    }

    private fun p(text: String) =
        MarkupElement("p", children = listOf(MarkupElement("#text", text = text)))

    private fun li(vararg children: MarkupElement) = MarkupElement("li", children = children.toList())
}