package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe T3 (P2): whole-book relayout epoch-ization.
 *
 * Guards the R4 defect: `otherChaptersJob` used to be dispatched with the panel-close-time params and
 * only re-run on the next `finalizeRelayoutAll`, so a further param change left the canonical thread
 * churning old params. The fix: [BookDocumentController.prepareRelayout] increments a global
 * `layoutEpoch`; [BookDocumentController.requestWholeBookRelayout] (the B2 trigger) cancels any in-flight
 * whole-book job and re-dispatches with the CURRENT params, skipping the active chapter, exiting within
 * ≤1 chapter when cancelled. This test drives two consecutive param changes without waiting for the
 * first B2 to finish — the final state MUST be the LAST paramHash on every non-active chapter (if the
 * old job survived cancellation, it would overwrite a stale hash afterwards and fail the assertion).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeBookRelayoutEpochProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        cacheDir = temp.newFolder("cache")
        controller.cacheRoot = cacheDir.absolutePath.toPath()
    }

    private fun paramHash(settings: ReaderSettings): Long {
        val profile = TypographicProfile.build(settings)
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    }

    /**
     * One P2 cycle: apply the new settings, `prepareRelayout` the active chapter (must bump the epoch),
     * then fire the B2 trigger. Returns the paramHash the B2 dispatch *should* be using.
     */
    private suspend fun cycle(settings: ReaderSettings): Long {
        controller.profile = TypographicProfile.build(settings)
        val r = controller.prepareRelayout(1, anchorChar = 0)
        assertNotNull("prepareRelayout must succeed for a small chapter", r)
        controller.requestWholeBookRelayout()
        return paramHash(settings)
    }

    /** Polls until the chapter's bound pagination table carries exactly [want]. */
    private suspend fun awaitParamHash(chapter: Int, want: Long) {
        val unit = controller.unitAt(chapter) ?: error("no unit $chapter")
        withTimeout(15_000) {
            while (unit.paginationTable?.paramHash != want) delay(20)
        }
    }

    @Test
    fun `two consecutive param changes persist the LAST paramHash and cancel the old whole-book job`() = runBlocking {
        val bookId = 5L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        // Lay out all three chapters so markup exists and ch1 carries the DEFAULT-profile table.
        for (ch in 0..2) assertNotNull(controller.ensureChapterLayout(ch, 0))

        val defaultHash = paramHash(ReaderSettings.DEFAULT)

        // Cycle 1: DEFAULT params (B2-H1 may remain in flight).
        val startEpoch = controller.layoutEpoch
        val h1 = cycle(ReaderSettings.DEFAULT)
        assertEquals(h1, defaultHash)
        assertTrue("prepareRelayout must bump the layout epoch", controller.layoutEpoch > startEpoch)

        // Cycle 2: immediate second param change — B2-H1 gets cancelled mid-flight.
        val secondSettings = ReaderSettings.DEFAULT.copy(fontScale = 55.0)
        val h2 = cycle(secondSettings)
        assertTrue("two different param hashes", h1 != h2)
        val epochAfter = controller.layoutEpoch
        assertTrue("two cycles must bump the epoch twice", epochAfter >= startEpoch + 2)

        // Final state MUST be the last hash on every non-active chapter. The old B2 must not land a
        // stale (h1) write after B2-H2 completed — if it did, this await would fail/overshoot.
        awaitParamHash(0, h2)
        awaitParamHash(2, h2)

        // The active chapter (ch1) is owned by B1/anchoring; B2 must have skipped it, so its
        // DEFAULT-table remains untouched.
        val ch1 = controller.unitAt(1) ?: error("no ch1")
        assertEquals("B2 must skip the active chapter", defaultHash, ch1.paginationTable?.paramHash)

            // The whole-book disk files carry the last hash too.
            // (C1-2: probed through the shared okio store — the same bytes the controller wrote.)
            val disk = PaginationCacheStore(FileSystem.SYSTEM, cacheDir.absolutePath.toPath())
            val c0 = disk.file("book_$bookId", 0, h2)
            assertTrue("B2 must persist chapter 0 on disk with the last hash", FileSystem.SYSTEM.exists(c0))
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P2</dc:title><dc:identifier id="bookid">urn:test:p2</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="Text/c3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>"""
        val chapters = (1..3).associate { n ->
            val body = (1..20).joinToString("\n") { p ->
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