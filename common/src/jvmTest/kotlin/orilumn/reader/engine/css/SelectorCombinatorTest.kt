package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Combinator support in [Selector]: `>` (child), `+` (adjacent sibling), `~` (general sibling),
 *  alongside the descendant (whitespace) combinator. */
class SelectorCombinatorTest {

    private fun el(tag: String, attrs: Map<String, String> = emptyMap(), text: String = ""): MarkupElement =
        MarkupElement(tag, attrs, if (text.isEmpty()) emptyList() else listOf(MarkupElement("#text", text = text)))

    private fun textNode(s: String) = MarkupElement("#text", text = s)

    /** Builds `body > [h2, p.a, pre, p.caption]`, wiring parents so sibling combinators can resolve. */
    private fun tree(): List<MarkupElement> {
        val h2 = el("h2")
        val pa = el("p", mapOf("class" to "a"))
        val pre = el("pre")
        val caption = el("p", mapOf("class" to "caption"))
        val body = MarkupElement("body", children = listOf(h2, pa, pre, caption))
        for (c in body.children) c.parent = body
        return listOf(h2, pa, pre, caption)
    }

    /** Ancestor chain (nearest last) for a direct child of [body] — just `[body]`. */
    private fun ancOf(body: MarkupElement): List<MarkupElement> = listOf(body)

    @Test
    fun `adjacent sibling matches the exact previous sibling`() {
        val (_, pa, _, caption) = tree().let { (h, a, p, c) -> arrayOf(h, a, p, c) }
        val (_, _, _, cap) = tree()
        // `pre + .caption`: caption's previous sibling is pre → match; p.a's previous sibling is h2 → no.
        val sel = Selector.parse("pre + p.caption")!!
        assertTrue("caption follows pre", sel.matches(cap, ancOf(cap.parent!!)))
        assertFalse("p.a does not follow pre", sel.matches(pa, ancOf(pa.parent!!)))
    }

    @Test
    fun `adjacent sibling skips whitespace text nodes between elements`() {
        // jsoup preserves the whitespace between `</div>` and `<pre>` as a `#text` sibling; per CSS that
        // must NOT break `+` (Rust book: `.filename + pre` inside a blockquote loses the pre's top margin
        // when it does). Non-whitespace text still forms an intervening inline box and must block.
        val filename = el("div", mapOf("class" to "filename"))
        val pre = el("pre")
        val spacer = el("div", mapOf("class" to "filename"))
        val wsNewline = textNode("\n")
        val betweenPre = el("pre")
        val realText = textNode("some real text")
        val preAfterText = el("pre")
        val body = MarkupElement("body", children = listOf(spacer, wsNewline, pre, realText, preAfterText, filename))
        for (c in body.children) c.parent = body
        val anc = ancOf(body)

        assertTrue("ws text must not break +", Selector.parse(".filename + pre")!!.matches(pre, anc))
        assertFalse("non-ws text breaks the adjacency", Selector.parse(".filename + pre")!!.matches(preAfterText, anc))
    }

    @Test
    fun `general sibling skips whitespace text nodes too`() {
        // `~` uses the same previous-sibling walk: `.filename ~ pre` must match a pre two `#text` gaps
        // later, exactly when the intervening nodes are whitespace.
        val filename = el("div", mapOf("class" to "filename"))
        val mid = el("div")
        val pre = el("pre")
        val body = MarkupElement("body", children = listOf(filename, textNode("\n"), mid, textNode("\n\n"), pre))
        for (c in body.children) c.parent = body
        assertTrue("ws text must not break ~", Selector.parse(".filename ~ pre")!!.matches(pre, ancOf(body)))
    }

    @Test
    fun `child combinator only matches a direct child`() {
        val (h2, _, _, caption) = tree().let { (h, a, p, c) -> arrayOf(h, a, p, c) }
        val sel = Selector.parse("body > .caption")!!
        assertTrue("caption is a direct child of body", sel.matches(caption, ancOf(caption.parent!!)))
        assertFalse("h2 has no class caption", sel.matches(h2, ancOf(h2.parent!!) - h2))
    }

    @Test
    fun `descendant and general-sibling both match later siblings`() {
        val (h2, pa, _, caption) = tree().let { (h, a, p, c) -> arrayOf(h, a, p, c) }
        assertTrue("h2 ~ p matches every later p", Selector.parse("h2 ~ p")!!.matches(caption, ancOf(caption.parent!!)))
        // h2 ~ p must NOT match a p before h2 (not present) nor h2 itself; here caption follows h2 at distance.
        assertTrue("body p matches caption", Selector.parse("body p")!!.matches(caption, ancOf(caption.parent!!)))
    }

    @Test
    fun `orphan and trailing combinators are rejected`() {
        assertTrue(Selector.parse("> p") == null)
        assertTrue(Selector.parse("p +") == null)
        // A bare whitespace descendant is still valid.
        assertTrue(Selector.parse("body p .caption") != null)
    }
}