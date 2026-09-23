package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe T8 (P11): unified promotion on leave — the reader's jump paths finalize the old chapter's temp
 * session before resolving the target.
 *
 * Guards the finding: `tocJump`/`seekTo`/`jumpChapter` (and the reader's other jump routes) left WITHOUT
 * finalizing the old chapter, so its `inProgress` anchor session survived and re-entry served the OLD
 * anchor page (perceived as a position bounce). [BookDocumentController.finalizeOnLeave] is the public
 * seam the three `JumpGate.submit` lambdas call before their target resolution.
 *
 * Both chapters are LARGE (> SMALL_CHAPTER_BLOCKS = 120 leaves) so the first layout goes down the anchor
 * path and leaves a live temp session (the background canonical does NOT clear it mid-session).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JumpLeaveFinalizeProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @Test
    fun `jump-away finalizes the old chapter temp session so re-entry cannot land on the old anchor`() = runBlocking {
        val bookId = 11L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)

        // LARGE ch0 → anchor temp session; B1 is in flight and does NOT clear it mid-session.
        val u0 = controller.ensureChapterLayout(0, 0) ?: error("no ch0")
        val ip0 = u0.inProgress ?: error("ch0 must hold a temp session (large chapter, disk miss)")
        assertTrue("fixture must be a large chapter", ip0.anchorBlockStart >= 0)

        // Control: without a leave-finalize, re-entry keeps the SAME session — the pre-P11 bug. This
        // proves the discriminator below is meaningful (i.e. ensureChapterLayout does preserve a session).
        val sameReentry = controller.ensureChapterLayout(0, 0) ?: error("no ch0 re-entry")
        assertSame(
            "control: a live temp session survives re-entry when nothing finalized it",
            ip0, sameReentry.inProgress,
        )

        // P11: the reader's jump paths (tocJump/seekTo/jumpChapter) call this BEFORE resolving the target.
        controller.finalizeOnLeave(0)
        assertNull("jump-away must clear the old chapter's temp session", u0.inProgress)
        controller.finalizeOnLeave(0) // S5 idempotent: a repeated finalize has no side effects.
        assertNull(u0.inProgress)

        val target = controller.openChapterStart(1)
        assertNotNull("the jump target must resolve", target)
        assertEquals("the jump must land on the requested chapter", 1, target!!.first)

        // Re-entry at the head: either a DISK HIT (canonical landed) or a FRESH session — never the stale
        // ip0, so the old anchor page can never be served again.
        val re0 = controller.ensureChapterLayout(0, 0) ?: error("no ch0 return")
        assertNotSame("re-entry must not land on the old temp session/anchor", ip0, re0.inProgress)
        if (re0.inProgress != null) {
            assertEquals(
                "a fresh session must be anchored at the requested head char",
                0, re0.inProgress!!.anchorBlockStart,
            )
        }
    }

    @Test
    fun `finalizeOnLeave is a no-op for an unknown or session-less chapter`() = runBlocking {
        val bookId = 12L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0)

        controller.finalizeOnLeave(99) // out of range
        // ch1 has no session yet (never laid out) — same path must not throw or create one.
        assertNull(controller.unitAt(1)?.inProgress)
        controller.finalizeOnLeave(1)
        assertNull(controller.unitAt(1)?.inProgress)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P11</dc:title><dc:identifier id="bookid">urn:test:p11</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // ~140 leaves per chapter: safely above SMALL_CHAPTER_BLOCKS=120 so each first layout dispatches an
        // anchor temp session + a background canonical.
        val chapters = (1..2).associate { n ->
            val body = (1..140).joinToString("\n") { p ->
                "<p>Paragraph $p of chapter $n. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
            }
            "OEBPS/Text/c$n.xhtml" to ("<html><body><h1>Chapter $n</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }
}
