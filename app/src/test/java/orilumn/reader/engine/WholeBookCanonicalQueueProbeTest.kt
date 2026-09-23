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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe T4 (P3): per-chapter canonical slot semantics — "queue-not-kill" cross-chapter, re-layout
 * only on same-chapter new params.
 *
 * Guards the R3 defect: an `anchorBackfillJob?.cancel()` at every new canonical dispatch killed the
 * just-tuned chapter's B1 the moment the reader flipped to another chapter. `finalizeTempOnLeave`
 * then invalidated everything and the return re-temp'd instead of hitting the fresh disk table.
 * The fix keys B1 by chapterIndex (`canonicalJobs: MutableMap<Int, Job?>`): a dispatch cancels only
 * ITS OWN chapter's previous slot (genuinely stale params), leaving any other chapter's B1 running
 * on the single canonical thread's FIFO queue so it finishes its persist.
 *
 * Both chapters are LARGE (> SMALL_CHAPTER_BLOCKS = 120 leaves) so each goes down the anchor path
 * and dispatches its canonical to the background thread rather than shaping in the foreground. The
 * leave ([finalizeTempOnLeave]) is private, so the probe invokes it via reflection — the reader's
 * flip-exhaust path is geometry-dependent and non-deterministic for a probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeBookCanonicalQueueProbeTest {

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

    /** Polls until the chapter's bound pagination table carries exactly [want]. */
    private suspend fun awaitParamHash(chapter: Int, want: Long) {
        val unit = controller.unitAt(chapter) ?: error("no unit $chapter")
        withTimeout(15_000) {
            while (unit.paginationTable?.paramHash != want) delay(20)
        }
    }

    private fun chapterUnit(chapter: Int): ChapterUnit =
        controller.unitAt(chapter) ?: error("no unit $chapter")

    private fun tempSession(chapter: Int): InProgressPagination =
        chapterUnit(chapter).inProgress ?: error("no in-progress temp session on ch$chapter")

    /** Drives the reader's leave path [BookDocumentController.finalizeTempOnLeave] (private) on the
     *  current temp session. The public flips only reach it through geometry-dependent temp
     *  exhaustion, so the probe calls it directly (same package, reflection). */
    private fun finalizeTempOnLeave(unit: ChapterUnit, ip: InProgressPagination) {
        val m = BookDocumentController::class.java.getDeclaredMethod(
            "finalizeTempOnLeave", ChapterUnit::class.java, InProgressPagination::class.java,
        )
        m.isAccessible = true
        m.invoke(controller, unit, ip)
    }

    @Test
    fun `cross-chapter dispatch queues-not-kills the old chapter canonical and re-entry hits disk`() = runBlocking {
        val bookId = 7L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        val h = paramHash(ReaderSettings.DEFAULT)

        // Both chapters must be LARGE (> SMALL_CHAPTER_BLOCKS = 120 leaves) so each dispatches its
        // canonical (B1) to the single background canonical thread instead of shaping in foreground.
        val u0 = controller.ensureChapterLayout(0, 0) ?: error("no ch0")
        val u1 = controller.ensureChapterLayout(1, 0) ?: error("no ch1")
        assertNotNull("ch0 must hold an anchor temp session initially", u0.inProgress)
        assertNotNull("ch1 must hold an anchor temp session initially", u1.inProgress)

        // The OLD design cancelled ch0's B1 exactly at this ch1 dispatch (anchorBackfillJob?.cancel()).
        // P3 keys B1 per chapter: ch1's dispatch leaves ch0's B1 running on the FIFO canonical queue,
        // so it must STILL complete, bind the disk table and persist — even though the reader has
        // already moved to ch1. (In the old design this await would time out.)
        awaitParamHash(0, h)
        assertEquals("ch0 table carries the paramHash it was dispatched under",
            h, chapterUnit(0).paginationTable?.paramHash)
            val f0 = PaginationCacheStore(FileSystem.SYSTEM, cacheDir.absolutePath.toPath())
                .file("book_$bookId", 0, h)
            assertTrue("ch0 canonical must persist to disk despite the cross-chapter flip", FileSystem.SYSTEM.exists(f0))

        // The leave-time hand-off can now BIND disk (canonical ready) instead of invalidating/clearing.
        val ip0 = tempSession(0)
        assertNotNull("B1 must have attached the canonical layout to the temp session", ip0.canonicalLayout)
        finalizeTempOnLeave(chapterUnit(0), ip0)
        assertNull("leave must drop the temp session", chapterUnit(0).inProgress)
        assertTrue("leave must bind the disk canonical page slices", chapterUnit(0).pageSlices.isNotEmpty())

        // Re-entry is now a DISK HIT (S5): no new anchor stream (inProgress stays null), same table,
        // pages still served — the old design's repeated re-temp on return is gone.
        val re0 = controller.ensureChapterLayout(0, 0) ?: error("no ch0 on return")
        assertNull("return must not start a re-temp anchor stream", re0.inProgress)
        assertEquals("return is a disk hit with the same table", h, re0.paginationTable?.paramHash)
        assertTrue("return serves pages from disk", re0.pageSlices.isNotEmpty())
    }

    @Test
    fun `same-chapter new params cancel and re-dispatch the stale canonical slot`() = runBlocking {
        val bookId = 8L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        val h1 = paramHash(ReaderSettings.DEFAULT)

        // Dispatch ch0's B1 under the DEFAULT params and let it settle.
        controller.ensureChapterLayout(0, 0)
        awaitParamHash(0, h1)

        // Re-shape the SAME chapter with new params without leaving. Same-chapter dispatch must
        // replace that chapter's slot (the DEFAULT table is stale now) and the canonical must settle
        // on the LAST paramHash — the old-design global cancel never covered this chapter-ed slot.
        val s2 = ReaderSettings.DEFAULT.copy(fontScale = 55.0)
        controller.profile = TypographicProfile.build(s2)
        val h2 = paramHash(s2)
        assertTrue("two different param hashes", h1 != h2)
        controller.prepareRelayout(0, 0)
        awaitParamHash(0, h2)
        assertEquals("same-chapter re-shape settles on the NEW params",
            h2, chapterUnit(0).paginationTable?.paramHash)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P3</dc:title><dc:identifier id="bookid">urn:test:p3</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // ~140 leaves per chapter (a paragraph is one leaf): safely above the SMALL_CHAPTER_BLOCKS=120
        // threshold so ch0/ch1 dispatch background canonicals instead of foreground full-layouts.
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