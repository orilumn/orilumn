package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
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
 * Probe T7 (P10): the bounded contiguous sliding temp-pagination window.
 *
 * Guards design spec §6: after every temp flip the window shrinks back to ±1 around the current
 * pointer (far-end eviction only, `enforceTempWindow`), so a session on a LARGE chapter never grows past
 * ~3 pages — ≤4 when the anchor torn pair (backward[0] + forward[0] sharing the head block) is resident.
 * Three invariants are locked in here:
 *
 *  1. **Bounded + contiguous slide.** Forward/backward sweeping keeps the window ≤ 3 pages (≤ 4 when the
 *     torn pair is atomic), pages stay block-contiguous (blockEndExclusive == next blockStart), and the
 *     CURRENT page's charStart moves strictly monotonically — no skip, no bounce — including across the
 *     torn seam, where [BookDocumentController.locateTempPosition] must disambiguate the pair by
 *     (blockStart, charStart) and not re-route a backward-page slice onto the head-edge page.
 *  2. **Torn-pair atomicity.** Whenever the head edge is torn (its first line cuts its first block), its
 *     backward partner is resident at backward[0] — the pair is never split by eviction.
 *  3. **Window-outside discard.** A jump far outside the window (P11 finalize + re-ensure at a distant
 *     anchor) leaves no residue: the canonical table serves the target, or a FRESH session is anchored
 *     at the far block with a compact window — never the old head-edge window.
 *
 * The single chapter is LARGE (> SMALL_CHAPTER_BLOCKS = 120 leaves) so the first layout goes down the
 * anchor temp path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowSlideDiscardProbeTest {

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

    private suspend fun openChapter(anchorChar: Int): ChapterUnit {
        assertTrue(controller.open(13L, saved = null))
        controller.setViewport(viewW, viewH)
        val unit = controller.ensureChapterLayout(0, anchorChar) ?: error("no ch0")
        val ip = unit.inProgress ?: error("large chapter must hold a temp session")
        assertTrue("fixture must be a large chapter", ip.anchorBlockStart >= 0)
        return unit
    }

    private fun snap(): BookDocumentController.TempWindowSnapshot? = controller.tempWindowSnapshot(0)

    /** Asserts the window invariants on [s]: bounded, block-contiguous, torn-pair atomic.
     *  A null snapshot means the temp session was finalized to canonical (S5) — the window is gone and
     *  invariants are vacuously true. */
    private fun assertWindowOk(s: BookDocumentController.TempWindowSnapshot?) {
        if (s == null) return  // canonical-bound, no temp window to validate
        val total = s.forwardBlocks.size + s.backwardBlocks.size
        val msg = "window f=${s.forwardBlocks} b=${s.backwardBlocks} torn=${s.headEdgeTorn} cur=${s.curIsForward}/idx${s.curIndex}"
        assertTrue("window must stay bounded ($msg) = $total", total <= 4)
        if (!s.headEdgeTorn) {
            assertTrue("non-torn window must be ≤ 3 pages ($msg) = $total", total <= 3)
        }
        for (k in 0 until s.forwardBlocks.size - 1) {
            val prevF = s.forwardBlocks[k]
            val nextF = s.forwardBlocks[k + 1]
            assertTrue(
                "forward blocks must advance, never regress ($msg)",
                nextF.first > prevF.first,
            )
            assertTrue(
                "forward pages may share ONE line-cut block but must never skip a block ($msg)",
                nextF.first in prevF.second - 1..prevF.second,
            )
        }
        for (k in 1 until s.backwardBlocks.size) {
            // nearest-first: backwardBlocks[k] (further back) must end at or just inside the nearer page's
            // first block. It may include one line-cut block that the nearer page also starts within
            // (endExclusive == nearer.first + 1) — the seam-fix case where the nearer page's first line
            // begins mid-block; it must never skip a block or regress.
            val end = s.backwardBlocks[k].second
            val nearFirst = s.backwardBlocks[k - 1].first
            assertTrue(
                "backward blocks contiguous nearest-first, sharing ≤1 cut block ($msg): end=$end nearFirst=$nearFirst",
                end == nearFirst || end == nearFirst + 1,
            )
        }
        if (s.forwardBlocks.isNotEmpty() && s.headEdgeTorn) {
            if (s.backwardBlocks.isNotEmpty()) {
                assertEquals("torn partner shares the head block ($msg)", s.forwardBlocks.first().first + 1, s.backwardBlocks.first().second)
            }
        } else if (s.forwardBlocks.isNotEmpty() && s.backwardBlocks.isNotEmpty()) {
            assertEquals("whole head edge must be preceded by a block-boundary page ($msg)", s.forwardBlocks.first().first, s.backwardBlocks.first().second)
        }
    }

    @Test
    fun `window slides bounded and contiguous across forward and backward flips`() = runBlocking {
        val unit = openChapter(0)
        var slice = unit.inProgress!!.currentSlice ?: error("no anchor slice")

        var lastChar = -1
        var flips = 0
        var prevEnd = -1
        repeat(10) {
            val next = controller.findAdjacentPage(0, slice, +1) ?: error("forward flip $flips failed")
            slice = controller.unitAt(0)!!.inProgress!!.currentSlice ?: next.second
            assertNotNull("current slice after flip $flips", controller.unitAt(0)!!.inProgress!!.currentSlice)
            assertTrue(
                "forward must never skip or bounce (char ${slice.charStart} after $lastChar)",
                slice.charStart > lastChar,
            )
            if (prevEnd >= 0) {
                assertEquals(
                    "forward temp pages must tile exactly: page.charStart(${slice.charStart}) == prev.charEnd($prevEnd)",
                    prevEnd, slice.charStart,
                )
            }
            lastChar = slice.charStart
            prevEnd = slice.charEnd
            flips++
            assertWindowOk(snap())
        }

        lastChar = Int.MAX_VALUE
        var backFlips = 0
        // Tracks the page actually displayed by each backward flip. The head-arrival flip (现象2 fix)
        // may hand the reader to the CANONICAL first page — `inProgress` is then null by design (S5:
        // 章头/离开 → 废除临时表) — so the live temp slice may be absent; the displayed page is the
        // flip's landing instead.
        var lastShown: PageSlice? = null
        while (backFlips < 10) {
            val cur = controller.unitAt(0)?.inProgress?.currentSlice ?: break
            if (cur.blockStart == 0) break // reached the chapter head — further back leaves the chapter
            val next = controller.findAdjacentPage(0, cur, -1) ?: break
            val after = controller.unitAt(0)?.inProgress?.currentSlice ?: next.second
            lastShown = after
            assertTrue(
                "backward must never skip or bounce (char ${after.charStart} after $lastChar)",
                after.charStart < lastChar,
            )
            // Seam fix: the earlier page (after) must END exactly where the current page (cur) STARTS —
            // no dropped chars at the backward page seam (the pre-fix bug left prevEnd < cur.charStart).
            assertEquals(
                "backward temp pages must tile exactly: after.charEnd(${after.charEnd}) == cur.charStart(${cur.charStart})",
                cur.charStart, after.charEnd,
            )
            lastChar = after.charStart
            backFlips++
            assertWindowOk(snap())
        }
        assertTrue("exercised the headward slide", backFlips > 0)
        assertEquals(
            "sweeping back to the head must land on the chapter-head page when inside the chapter",
            0, (lastShown?.blockStart ?: -1),
        )
    }

    /**
     * 现象2 regression probe: sweeping backward to the chapter head must land the FULL head page on the
     * FIRST arrival flip — the whole-block packing's [0,k) sparse head page is NEVER shown. After
     * arrival the old temp table is either discarded (canonical bound → `inProgress == null`) or
     * re-anchored at char 0 (fresh head-anchored session → backward list empty). The subsequent forward
     * flip must advance past the head (no duplicated/re-stated head region).
     */
    @Test
    fun `回翻到章头一次落位满页不闪稀疏页不回退`() = runBlocking {
        val midChar = paraCharStart(240) + 4 // > HEAD_START_BLOCK_LIMIT → no head lift
        openChapter(midChar)

        var arrival: PageSlice? = null
        var prev = Int.MAX_VALUE
        var guard = 0
        // C1-0: skia 真机字体度量替代 Robolectric 窄字体后每页块数变少（~2.5 块/页），240 块需 ~100 翻；
        // 旧 60 步是 StaticLayout 窄行几何下的紧界。此处只守到达语义，不守步数。
        while (guard++ < 200) {
            val cur = controller.unitAt(0)?.inProgress?.currentSlice ?: break
            if (cur.blockStart == 0) { arrival = cur; continue }
            val next = controller.findAdjacentPage(0, cur, -1) ?: break
            val after = controller.unitAt(0)?.inProgress?.currentSlice ?: next.second
            assertTrue("backward must never bounce (char ${after.charStart} ≥ $prev)", after.charStart < prev)
            prev = after.charStart
            if (after.blockStart == 0) { arrival = after; break }
        }
        val arr = arrival ?: error("sweep never reached the head")
        assertEquals("head arrival must start at char 0", 0, arr.charStart)
        assertEquals("head arrival must be block 0", 0, arr.blockStart)

        // 修复关键可观察: 到达章头时后向窗口不得驻留稀疏头页（旧代码签名: backwardBlocks 非空且含 blockStart 0）。
        val snap = controller.tempWindowSnapshot(0)
        assertTrue(
            "at arrival the old sparse head page must not be resident in the window (snap=$snap)",
            snap == null || snap.backwardBlocks.isEmpty(),
        )

        // Forward from the head must advance — no duplicated/re-stated head region.
        val fwd = controller.findAdjacentPage(0, arr, +1) ?: error("forward from head")
        assertTrue(
            "forward from the head page must advance (fwd.charStart=${fwd.second.charStart} ≥ arr.charEnd=${arr.charEnd})",
            fwd.second.charStart >= arr.charEnd,
        )
    }

    @Test
    fun `mid-chapter anchor torn pair is never split while the window slides`() = runBlocking {
        // Anchor mid-paragraph in BR_PAR (block 150, beyond HEAD_START_BLOCK_LIMIT): P8's head lift only
        // re-anchors near the head, so the `<br/>`-paragraph block 150 still yields a torn head edge.
        val midChar = paraCharStart(BR_PAR) + 600
        val unit = openChapter(midChar)
        assertTrue("mid-paragraph anchor must yield a torn head edge", snap()?.headEdgeTorn == true)

        var slice = unit.inProgress!!.currentSlice ?: error("no anchor slice")
        repeat(6) { flips ->
            val next = controller.findAdjacentPage(0, slice, +1) ?: error("forward flip $flips failed")
            slice = controller.unitAt(0)!!.inProgress!!.currentSlice ?: next.second
            assertWindowOk(snap())
        }

        // Slide back toward the seam: as long as the torn head edge is resident its partner must be too.
        var back = 0
        while (back < 6) {
            val cur = controller.unitAt(0)?.inProgress?.currentSlice ?: break
            val next = controller.findAdjacentPage(0, cur, -1) ?: break
            val after = controller.unitAt(0)?.inProgress?.currentSlice ?: next.second
            assertNotNull("every flip must land a page", controller.unitAt(0)?.inProgress?.currentSlice ?: next.second)
            assertWindowOk(snap())
            back++
            if (after.blockStart == 0) break
        }
        assertTrue("torn session must support bidirectional sliding", back > 0)
    }

    @Test
    fun `jump far outside the window discards the session and restarts at the target`() = runBlocking {
        val unit = openChapter(0)

        // Slide the window forward until the original head edge is gone.
        var slice = unit.inProgress!!.currentSlice ?: error("no anchor slice")
        repeat(4) { flips ->
            val next = controller.findAdjacentPage(0, slice, +1) ?: error("forward flip $flips failed")
            slice = controller.unitAt(0)!!.inProgress!!.currentSlice ?: next.second
        }
        val oldHead = snap()?.forwardBlocks?.firstOrNull()?.first ?: -1
        assertTrue("window must have slid past the head so freshness is verifiable (head=$oldHead)", oldHead > 0)

        // Model the reader's TOC/seek: finalize the live session, then re-ensure at a DISTANT anchor.
        val farChar = paraCharStart(340) + 4
        controller.finalizeOnLeave(0)
        assertNull("finalize must clear the temp session (canonical-or-invalidate, never a live window)", unit.inProgress)
        controller.ensureChapterLayout(0, farChar)

        val s = controller.tempWindowSnapshot(0)
        if (s == null) {
            // Canonical served: the shaped pages must cover the far target.
            val slices = unit.pageSlices
            assertTrue("canonical pages must cover the far anchor", slices.any { it.charStart <= farChar && farChar < it.charEnd })
        } else {
            val freshHead = s.forwardBlocks.first().first
            assertTrue(
                "restarted session must anchor at the FAR block, not the old head edge (head=$freshHead old=$oldHead far=$FAR_BLOCK)",
                freshHead >= FAR_BLOCK - 1 && freshHead >= oldHead + 50,
            )
            assertTrue("restarted session must be compact", s.forwardBlocks.size + s.backwardBlocks.size <= 3)
            assertWindowOk(s)
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Fixtures (mirrors JumpLeaveFinalizeProbeTest's large-chapter shape)
    // ───────────────────────────────────────────────────────────────

    private fun pBody(n: Int): String =
        "Paragraph $n of the chapter. " +
            "some filler text for measuring line wraps and page breaks. ".repeat(20)

    private fun pMarkup(n: Int): String {
    // The `<br/>`s (4 text chars) land on the paragraph's leading line, so a mid-paragraph anchor there
    // yields a line-cut (torn) head edge. Block 150 sits beyond HEAD_START_BLOCK_LIMIT (100) so P8's
    // head lift never re-anchors it — yet beyond 150 the br chars shift every char offset by 4.
    val br = if (n == BR_PAR) "<br/>".repeat(4) else ""
    return "<p>$br${pBody(n)}</p>"
}

    private fun paraCharStart(n: Int): Int {
        // [boxChapterLayouter]'s char offsets index the concatenated STRIPPED text (no tags): the h1
        // text is "body ~Block 0", every paragraph k is block k starting at its own text offset. The
        // 4 `<br/>` chars of [BR_PAR] land inside block 150, shifting every later block by 4.
        return "Window".length + (if (n > BR_PAR) 4 else 0) + (1 until n).sumOf { pBody(it).length }
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P10</dc:title><dc:identifier id="bookid">urn:test:p10</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        // ~141 leaves (> SMALL_CHAPTER_BLOCKS=120): the first layout dispatches an anchor temp session.
        val body = (1..PARAS).joinToString("\n") { pMarkup(it) }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Window</h1>" + body + "</body></html>").toByteArray(),
        )
    }

    private companion object {
        const val PARAS = 360
        const val FAR_BLOCK = 340
        const val BR_PAR = 150
    }
}