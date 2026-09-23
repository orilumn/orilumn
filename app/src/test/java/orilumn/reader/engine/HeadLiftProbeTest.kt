package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (P8 / R2): the head lift.
 *
 * `startAnchorStream` defaulted to anchoring its temp table at a LINE-CUT mid-chapter page even when
 * the reader was only a few blocks into the chapter — a pagination source different from the canonical
 * (chapter-head) table, so head-area flips and "save→relocate" could diverge between the two tables.
 * P8 re-anchors at the TRUE chapter head whenever the requested anchor falls within the chapter's first
 * `HEAD_START_BLOCK_LIMIT` blocks. Locked in here:
 *
 *  1. A near-head anchor (block ≤ the limit) is LIFTED: the session's `anchorBlockStart` is 0 and the
 *     current page is the chapter-head page (block 0, char 0) — same source as the canonical head.
 *  2. A deep anchor (block well beyond the limit) is NOT lifted: the session anchors at that block and
 *     the current page starts at that block's char — deep jumps never shape the whole chapter.
 *  3. The boundary is exact: block `HEAD_START_BLOCK_LIMIT` is lifted; block `LIMIT + 1` is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadLiftProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val viewW = 720
    private val viewH = 1280

    private var cacheSeq = 0

    private fun newController(): BookDocumentController {
        val c = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        c.cacheRoot = temp.newFolder("cache${cacheSeq++}").absolutePath.toPath()
        return c
    }

    private suspend fun open(controller: BookDocumentController, anchorChar: Int): ChapterUnit {
        assertTrue(controller.open(41L, saved = null))
        controller.setViewport(viewW, viewH)
        val unit = controller.ensureChapterLayout(0, anchorChar) ?: error("no ch0")
        assertNotNull("large chapter must hold a temp session", unit.inProgress)
        return unit
    }

    private fun ipSession(unit: ChapterUnit): InProgressPagination = unit.inProgress ?: error("no session")

    @Test
    fun `near-head anchor is lifted to the chapter head`() = runBlocking {
        val c = newController()
        val unit = open(c, paraCharStart(50))
        val ip = ipSession(unit)
        assertEquals("anchor lifted to block 0", 0, ip.anchorBlockStart)
        val page = ip.currentSlice ?: error("no anchor page")
        assertEquals("current page is the chapter-head page", 0, page.blockStart)
        assertEquals("current page starts at char 0", 0, page.charStart)
    }

    @Test
    fun `deep anchor is not lifted and lands at the requested block`() = runBlocking {
        val c = newController()
        val deepChar = paraCharStart(200)
        val unit = open(c, deepChar)
        val ip = ipSession(unit)
        assertEquals("deep anchor stays at block 200", 200, ip.anchorBlockStart)
        val page = ip.currentSlice ?: error("no anchor page")
        assertEquals("page starts at the deep block", 200, page.blockStart)
        assertEquals("page starts where the requested block begins", deepChar, page.charStart)
    }

    @Test
    fun `lift boundary is exactly the head-start limit`() = runBlocking {
        val c = newController()
        val limit = c.headStartBlockLimit()
        // Block = HEAD_START_BLOCK_LIMIT (100): inside the first pages → lifted.
        val unit = open(c, paraCharStart(limit))
        assertEquals("block $limit is lifted", 0, ipSession(unit).anchorBlockStart)

        // Block = LIMIT + 1: a real mid-chapter anchor → not lifted. A FRESH controller/chapter so the
        // second ensure starts its own session (a repeat ensure on an existing session re-uses it).
        val c2 = newController()
        val unit2 = open(c2, paraCharStart(limit + 1))
        assertEquals("block ${limit + 1} is not lifted", limit + 1, ipSession(unit2).anchorBlockStart)
    }

    // ───────────────────────────────────────────────────────────────
    // Fixtures (h1 "Head", then PARAS ≥ 1 fixed-length paragraphs — block k ↔ para k)
    // ───────────────────────────────────────────────────────────────

    private fun pBody(n: Int): String =
        "Paragraph $n of the chapter. " +
            "some filler text for measuring line wraps and page breaks. ".repeat(20)

    // P1-2: 叶级收尾裁掉每段尾空格（pBody 末尾 ". " 的尾空格），归一化后每段少 1 字符。
    private fun paraCharStart(n: Int): Int = "Head".length + (1 until n).sumOf { pBody(it).length - 1 }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P8</dc:title><dc:identifier id="bookid">urn:test:p8</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        // 240 leaves > SMALL_CHAPTER_BLOCKS (120): the first layout dispatches an anchor temp session.
        val body = (1..PARAS).joinToString("\n") { "<p>${pBody(it)}</p>" }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Head</h1>" + body + "</body></html>").toByteArray(),
        )
    }

    private companion object {
        const val PARAS = 240
    }
}