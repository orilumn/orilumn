package orilumn.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P4-c1: `href` → (chapter, fragment) resolution. */
class LinkTargetsTest {

    private val index = mapOf(
        "oebps/ch1.xhtml" to 0,
        "oebps/ch2.xhtml" to 1,
        "oebps/notes.xhtml" to 2,
    )

    @Test
    fun `bare fragment stays in the current chapter`() {
        assertEquals(LinkTarget(1, "sec3"), LinkTargets.resolveLinkTarget("#sec3", 1, "OEBPS/ch2.xhtml", index))
    }

    @Test
    fun `empty fragment means chapter head`() {
        assertEquals(LinkTarget(1, null), LinkTargets.resolveLinkTarget("#", 1, "OEBPS/ch2.xhtml", index))
        assertEquals(LinkTarget(0, null), LinkTargets.resolveLinkTarget("ch1.xhtml", 1, "OEBPS/ch2.xhtml", index))
    }

    @Test
    fun `cross-chapter relative href resolves`() {
        assertEquals(
            LinkTarget(2, "n1"),
            LinkTargets.resolveLinkTarget("notes.xhtml#n1", 1, "OEBPS/ch2.xhtml", index),
        )
    }

    @Test
    fun `dotdot and casing normalize like the parser`() {
        assertEquals(
            LinkTarget(0, null),
            LinkTargets.resolveLinkTarget("../CH1.XHTML", 2, "OEBPS/sub/notes.xhtml", index),
        )
    }

    @Test
    fun `fragment case is preserved`() {
        assertEquals(LinkTarget(1, "SecA"), LinkTargets.resolveLinkTarget("#SecA", 1, "OEBPS/ch2.xhtml", index))
    }

    @Test
    fun `external urls and blanks resolve to null`() {
        assertNull(LinkTargets.resolveLinkTarget("", 1, "OEBPS/ch2.xhtml", index))
        assertNull(LinkTargets.resolveLinkTarget("https://example.com/x#y", 1, "OEBPS/ch2.xhtml", index))
        assertNull(LinkTargets.resolveLinkTarget("mailto:a@b.c", 1, "OEBPS/ch2.xhtml", index))
        assertNull(LinkTargets.resolveLinkTarget("data:text/plain,hi", 1, "OEBPS/ch2.xhtml", index))
        assertNull(LinkTargets.resolveLinkTarget("missing.xhtml#f", 1, "OEBPS/ch2.xhtml", index))
    }
}
