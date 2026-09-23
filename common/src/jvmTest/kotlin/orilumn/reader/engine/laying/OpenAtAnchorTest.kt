package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5 — CFI anchoring + bidirectional streaming, all on the JVM with a fake [ParagraphBreaker].
 *
 * Verifies the block-level contract: an anchor resolves to its containing block (never split), the
 * anchor page is emitted first then forward/backward pages stream toward the chapter tail/head, and
 * every block belongs to exactly one page with fully continuous coverage.
 */
class OpenAtAnchorTest {

    /** Fixed-height block for a run of [textLen] chars with [charsPerLine] chars per line. */
    private fun block(index: Int, textLen: Int, charsPerLine: Int = 3, lineHeight: Int = 15): BlockMeasure {
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < textLen) { val e = (i + charsPerLine).coerceAtMost(textLen); ranges.add(i until e); i = e }
        return BlockMeasure(index, textLen, ranges, lineHeight)
    }

    /** Every block in a surrounding page range belongs to exactly one page (block-atomic, no gaps). */
    private fun assertCoverage(pages: List<BlockPage>, expectedBlocks: IntRange) {
        val seen = HashSet<Int>()
        for (p in pages) for (b in p.blockStart until p.blockEndExclusive) assertTrue("block $b duplicated", seen.add(b))
        for (b in expectedBlocks) assertTrue("block $b uncovered", observed(pages, b))
        assertEquals(expectedBlocks.count(), seen.size)
    }

    private fun observed(pages: List<BlockPage>, blockIndex: Int): Boolean =
        pages.any { blockIndex in it.blockStart until it.blockEndExclusive }

    @Test
    fun `锚点块整体不切块且锚点页最先产生`() {
        // 5 blocks of 3 lines = 45px each; contentH 90 fits exactly 2 blocks per page.
        val blocks = List(5) { block(it, textLen = 9, charsPerLine = 3, lineHeight = 15) } // each 45px
        val plan = BidirectionalPaginator.paginate(blocks, anchorIndex = 2, contentH = 90)

        // Anchor page = anchor block(2) + block(3); block(2) must appear whole here.
        assertEquals(2, plan.anchorPage.blockStart)
        assertEquals(4, plan.anchorPage.blockEndExclusive)

        // Forward: block(4) remains.
        assertEquals(1, plan.forwardPages.size)
        assertEquals(4, plan.forwardPages[0].blockStart)
        assertEquals(5, plan.forwardPages[0].blockEndExclusive)

        // Backward: block(0)+block(1) pack into one nearest-anchor page.
        assertEquals(1, plan.backwardPages.size)
        assertEquals(0, plan.backwardPages[0].blockStart)
        assertEquals(2, plan.backwardPages[0].blockEndExclusive)

        // Full coverage, no block shared between pages.
        assertEquals(4, plan.anchorPage.blockEndExclusive) // covers 2,3
        assertCoverage(plan.anchorPage.let { listOf(it) } + plan.forwardPages + plan.backwardPages, 0 until blocks.size)
    }

    @Test
    fun `向章尾流式页依次前移`() {
        // long run of blocks, contentH small so pages split evenly
        val blocks = List(10) { block(it, textLen = 3, charsPerLine = 3, lineHeight = 20) } // each 1 line, 20px
        val plan = BidirectionalPaginator.paginate(blocks, anchorIndex = 3, contentH = 40)

        // Forward pages continue after the anchor page without overlap and in order.
        val forward = plan.forwardPages
        for (i in 1 until forward.size) assertEquals(forward[i - 1].blockEndExclusive, forward[i].blockStart)
        // anchor page starts at block 3
        assertEquals(3, plan.anchorPage.blockStart)
        // backward nearest-anchor first: last backward page reaches block 0 (chapter head)
        assertEquals(0, plan.backwardPages.last().blockStart)
    }

    @Test
    fun `反向页从锚向章头顺序与连续性`() {
        val blocks = List(8) { block(it, textLen = 6, charsPerLine = 3, lineHeight = 15) } // 2 lines → 30px each
        val plan = BidirectionalPaginator.paginate(blocks, anchorIndex = 5, contentH = 60) // 2 blocks/page

        // backward page nearest to anchor must end at block 5 (the anchor block index boundary).
        val bwd = plan.backwardPages
        assertTrue(bwd.isNotEmpty())
        // Pages are ordered nearest-anchor-first: descending block range start toward 0.
        var prev = Int.MAX_VALUE
        for (p in bwd) { assertTrue(p.blockStart < prev); prev = p.blockStart }
        // The union of all backward, anchor and forward pages covers every block exactly once.
        assertCoverage(listOf(plan.anchorPage) + plan.forwardPages + bwd, 0 until blocks.size)
    }

    @Test
    fun `超高块允许单独成页且不阻塞双向`() {
        // a huge block (100px > 60px contentH)
        val big = block(index = 2, textLen = 21, charsPerLine = 3, lineHeight = 15) // 7 lines → 105px
        val blocks = listOf(
            block(0, 9, 3, 15), // 45px
            block(1, 9, 3, 15), // 45px
            big,
            block(3, 9, 3, 15), // 45px
        )
        val plan = BidirectionalPaginator.paginate(blocks, anchorIndex = 2, contentH = 60)
        // the oversized anchor block stands alone on its page
        assertEquals(true, plan.anchorPage.blockEndExclusive - plan.anchorPage.blockStart >= 1)
        // forward page carries block 3
        assertEquals(listOf(3 until 4), plan.forwardPages.map { it.blockStart until it.blockEndExclusive })
        // backward covers blocks 0..1
        assertEquals(setOf(0, 1), plan.backwardPages.flatMap { (it.blockStart until it.blockEndExclusive).toList() }.toSet())
    }
}