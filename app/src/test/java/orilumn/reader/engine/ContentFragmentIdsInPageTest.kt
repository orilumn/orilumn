package orilumn.reader.engine

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFragmentIdsInPageTest {

    // ch01=0, intro p=10, sec-a=26, body p=36, sec-b=46 (text lengths 10,16,10,10,11)
    private fun tree(): MarkupElement = MarkupElement(
        "body",
        children = listOf(
            MarkupElement("h1", attrs = mapOf("id" to "ch01"), children = listOf(MarkupElement("#text", text = "Section A\n"))),
            MarkupElement("p", children = listOf(MarkupElement("#text", text = "intro paragraph\n"))),
            MarkupElement("h2", attrs = mapOf("id" to "sec-a"), children = listOf(MarkupElement("#text", text = "First sub\n"))),
            MarkupElement("p", children = listOf(MarkupElement("#text", text = "body text\n"))),
            MarkupElement("h2", attrs = mapOf("id" to "sec-b"), children = listOf(MarkupElement("#text", text = "Second sub\n"))),
        ),
    )

    @Test
    fun picksIdsWhoseTextStartFallsInsideRange() {
        val m = tree()
        // A range containing only the sec-a heading start (26).
        assertEquals(setOf("sec-a"), contentFragmentIdsInPage(m, 26, 27))
        // Inclusive of the page start.
        assertEquals(setOf("sec-a"), contentFragmentIdsInPage(m, 26, 36))
        // A range spanning both sub-headings.
        assertEquals(setOf("sec-a", "sec-b"), contentFragmentIdsInPage(m, 20, 60))
        // Exclusion: boundaries don't leak (sec-b at 46; range [40,45) misses it).
        assertEquals(emptySet<String>(), contentFragmentIdsInPage(m, 40, 45))
    }

    @Test
    fun emptyTagContainerWithIdCountsItsContentStart() {
        // A section container with an id but non-text children still anchors at its content start (0).
        val sec = MarkupElement(
            "section",
            attrs = mapOf("id" to "secwrap"),
            children = listOf(MarkupElement("p", children = listOf(MarkupElement("#text", text = "hello\n")))),
        )
        assertTrue(contentFragmentIdsInPage(sec, 0, 1).contains("secwrap"))
    }

    @Test
    fun idCharStartResolvesToTheElementTextStart() {
        val m = tree()
        // ch01=0, intro p=10, sec-a=26, body p=36, sec-b=46
        assertEquals(26, contentFragmentIdCharStart(m, "sec-a"))
        assertEquals(46, contentFragmentIdCharStart(m, "sec-b"))
        assertEquals(0, contentFragmentIdCharStart(m, "ch01"))
    }

    @Test
    fun idCharStartNullForMissingId() {
        assertEquals(null, contentFragmentIdCharStart(tree(), "no-such-id"))
    }

    @Test
    fun idCharStartAgreesWithPageSetLookup() {
        // The resolved char must fall inside the range contentFragmentIdsInPage reports the id for,
        // so a TOC jump and the heading highlight land at the same document position.
        val m = tree()
        for (id in listOf("ch01", "sec-a", "sec-b")) {
            val start = contentFragmentIdCharStart(m, id)
            assertTrue(id + " unresolved", start != null)
            assertTrue(id + " off-page", contentFragmentIdsInPage(m, start!!, start + 1).contains(id))
        }
    }
}