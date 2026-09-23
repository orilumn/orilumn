package orilumn.reader.engine

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-c1: `a name` anchors resolve like `id`; styled `*With` variants agree with the raw walk
 *  when no normalization/exclusion applies. */
class P4cAnchorTest {

    private fun text(s: String) = MarkupElement("#text", text = s)

    private fun tree(): MarkupElement {
        val p1 = MarkupElement("p", children = listOf(text("intro ")))
        val named = MarkupElement("a", attrs = mapOf("name" to "old-anchor"), children = listOf(text("named")))
        val p2 = MarkupElement("p", children = listOf(text("pre "), named, text(" post")))
        val h = MarkupElement("h2", attrs = mapOf("id" to "sec-a"), children = listOf(text("head")))
        return MarkupElement("body", children = listOf(p1, p2, h)).also { fill(it, null) }
    }

    private fun fill(n: MarkupElement, p: MarkupElement?) {
        n.parent = p
        for (c in n.children) fill(c, n)
    }

    @Test
    fun `a name resolves to its content start`() {
        val m = tree()
        // "intro "(6) + "pre "(4) = 10
        assertEquals(10, contentFragmentIdCharStart(m, "old-anchor"))
        assertTrue(contentFragmentIdsInPage(m, 10, 11).contains("old-anchor"))
    }

    @Test
    fun `id still wins on the same element`() {
        val a = MarkupElement("a", attrs = mapOf("id" to "new", "name" to "old"), children = listOf(text("x")))
        val m = MarkupElement("body", children = listOf(a)).also { fill(it, null) }
        assertEquals(0, contentFragmentIdCharStart(m, "new"))
        assertEquals(0, contentFragmentIdCharStart(m, "old"))
    }

    @Test
    fun `styled variants agree with raw when lengths are raw and nothing excluded`() {
        val m = tree()
        for (id in listOf("old-anchor", "sec-a")) {
            val raw = contentFragmentIdCharStart(m, id)
            val styled = contentFragmentIdCharStartWith(m, id, { it.text.length.toLong() }, { false })
            assertEquals(raw, styled)
            assertTrue(contentFragmentIdsInPageWith(m, styled!!, styled + 1, { it.text.length.toLong() }, { false }).contains(id))
        }
    }

    @Test
    fun `excluded subtree contributes no chars`() {
        val m = tree()
        // Exclude the first paragraph: "intro "(6) vanishes, anchor shifts to 4.
        val hidden = setOf(m.children[0])
        fun excl(el: MarkupElement): Boolean = el in hidden
        assertEquals(4, contentFragmentIdCharStartWith(m, "old-anchor", { it.text.length.toLong() }, ::excl))
        assertTrue(contentFragmentIdsInPageWith(m, 4, 5, { it.text.length.toLong() }, ::excl).contains("old-anchor"))
    }
}
