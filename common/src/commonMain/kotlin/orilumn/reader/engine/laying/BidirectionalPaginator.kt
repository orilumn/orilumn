package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement

/**
 * A single block laid out enough to measure its height and contribute chars/lines, independent of its
 * vertical position. S5's streaming paginator works at this granularity: a block is never split
 * across pages, so a [BlockMeasure] is the atomic unit of bidirectional paging.
 *
 * Construction needs exactly one [ParagraphBreaker] call for the block's text (a JVM fake in tests,
 * the engine-skia `SkiaParagraphBreaker` in production), which is what keeps "layout one page first, then back-fill
 * and stream forward" from ever needing the whole chapter shaped at once.
 */
data class BlockMeasure(
    /** Its position in chapter order (used to group pages by block range). */
    val index: Int,
    /** Character length this block adds to the chapter stream. */
    val textLength: Int,
    /** Per-line char ranges into the block's own text. */
    val ranges: List<IntRange>,
    /** Uniform line height (px). */
    val lineHeight: Int,
    /** Optional backing element for tracing/debug. */
    val el: MarkupElement? = null,
/** The computed style (carried for future drawing). */
    val style: ComputedStyle? = null,
    /** Absolute character offset of this block's first text character in the full chapter
     *  stream. Populated when blocks are built in chapter order; 0 means "not yet assigned". */
    val globalCharStart: Int = 0,
) {
    val lineCount: Int get() = ranges.size

    val heightPx: Int get() = ranges.size * lineHeight
}

/** Groups one or more consecutive blocks into a page (block-range based). */
data class BlockPage(
    val blockStart: Int,
    val blockEndExclusive: Int,
    val lineCount: Int,
    val step: Int,
)

/** The bidirectional page plan produced by [BidirectionalPaginator]. */
class AroundAnchorPages(
    /** Pages that run toward the chapter head, nearest-to-anchor first. */
    val backwardPages: List<BlockPage>,
    /** The anchor's opening page (contains the anchor block whole, never split). */
    val anchorPage: BlockPage,
    /** Pages that run toward the chapter tail, after the anchor page. */
    val forwardPages: List<BlockPage>,
)

/**
 * Splits a block list into pages around a **block-level anchor** without ever splitting a block.
 *
 * Strategy (matches the S5 plan):
 *  1. **Open page first** — the anchor page starts at the anchor block and extends forward blocks
 *     until the page is as full as possible; it is emitted before any other layout so it can be
 *     rendered first (lowest first-frame latency). This page may begin mid-chapter; it deliberately
 *     includes the same-block text before the CFI anchor (content is never cut mid-paragraph).
 *  2. **Forward stream** — continuing toward the chapter tail from the anchor page.
 *  3. **Back-fill** — blocks before the anchor, packed into pages nearest-to-anchor first, so the
 *     reader can flip backward all the way to a chapter-head page.
 *
 * Only [heightPx]-cumulative packing is done here; char/ y offsets are derived later by the renderer
 * from the block ranges. Oversized blocks (taller than one page) become a page of their own and are
 * allowed to tear, matching the box-layout page-break rule.
 */
object BidirectionalPaginator {

    /**
     * @param blocks complete chapter block list, in order.
     * @param anchorIndex index of the block containing the CFI anchor.
     * @param contentH content-area page height (px), same unit as [BlockMeasure.heightPx].
     */
    fun paginate(blocks: List<BlockMeasure>, anchorIndex: Int, contentH: Int): AroundAnchorPages {
        val anchorPage = forwardPageFrom(blocks, anchorIndex, contentH)
        val forwardStart = anchorPage.blockEndExclusive
        val forwardPages = forwardPagesFrom(blocks, forwardStart, contentH)
        val backwardPages = backwardPagesFrom(blocks, anchorIndex, contentH)
        return AroundAnchorPages(backwardPages, anchorPage, forwardPages)
    }

    /** One page starting at [start]; produced by consuming blocks forward until the page is full. */
    private fun forwardPageFrom(blocks: List<BlockMeasure>, start: Int, contentH: Int): BlockPage =
        forwardPagesFrom(blocks, start, contentH).let { if (it.isEmpty()) emptyPage(start) else it.first() }

    private fun emptyPage(start: Int) = BlockPage(start, start, 0, 1)

    /** Pages covering [start until end] by forward-consumption; the first is the anchor page. */
    private fun forwardPagesFrom(blocks: List<BlockMeasure>, start: Int, contentH: Int): List<BlockPage> {
        val pages = ArrayList<BlockPage>()
        var i = start
        while (i < blocks.size) {
            // Oversized block → its own page (allowed to tear); otherwise pack to the page limit.
            if (blocks[i].heightPx > contentH) {
                pages.add(BlockPage(i, i + 1, blocks[i].lineCount, 1))
                i++
                continue
            }
            var acc = 0
            var j = i
            while (j < blocks.size && acc + blocks[j].heightPx <= contentH) {
                acc += blocks[j].heightPx
                j++
            }
            if (j > i) {
                pages.add(BlockPage(i, j, acc, j - i))
            } else {
                pages.add(BlockPage(j, j + 1, blocks[j].lineCount, 1)); j++
            }
            i = j
        }
        return pages
    }

    /**
     * Pages toward the chapter head from the blocks before [endExclusive] (i.e. `[0, endExclusive)`).
     * Consumed from the nearest-to-anchor block backward; each page is closed (emitted) as it becomes
     * full, so the emitted order is naturally nearest-anchor-first: [0] is the page immediately before
     * the anchor's page, and the last entry is the chapter-head page (possibly under-full).
     */
    private fun backwardPagesFrom(blocks: List<BlockMeasure>, endExclusive: Int, contentH: Int): List<BlockPage> {
        val pages = ArrayList<BlockPage>()
        var acc = 0
        var packLow = endExclusive   // index of the first (lowest) block in the current pack
        var packHigh = endExclusive  // one past the last block in the current pack
        var j = endExclusive - 1
        while (j >= 0) {
            val b = blocks[j]
            if (b.heightPx > contentH) {
                // Close any pending small pack first; the oversized block gets its own page (tear allowed).
                if (acc > 0) { pages.add(BlockPage(j + 1, packHigh, acc, packHigh - (j + 1))); acc = 0; packHigh = j + 1 }
                pages.add(BlockPage(j, j + 1, b.lineCount, 1))
                packHigh = j
                j--
                continue
            }
            if (acc + b.heightPx <= contentH) {
                acc += b.heightPx; packLow = j; j--
            } else {
                pages.add(BlockPage(j + 1, packHigh, acc, packHigh - (j + 1)))
                acc = 0; packHigh = j + 1
            }
        }
        if (acc > 0) pages.add(BlockPage(packLow, packHigh, acc, packHigh - packLow))
        return pages   // already nearest-anchor-first
    }
}