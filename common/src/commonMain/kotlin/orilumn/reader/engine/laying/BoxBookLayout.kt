package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BreakAwareBookLayout

/**
 * [BookLayout] backed by the box-flow line stream produced by [NormalFlowLayout].
 *
 * The paging algorithm consumes only [BookLayout], so [BoxLayouter] can feed the Paginator entirely
 * through this type without touching rendering. As a [BreakAwareBookLayout] it also surfaces the
 * line ranges of `break-inside: avoid` boxes so the Paginator refuses to tear them across pages.
 *
 * Coordinates follow the BookLayout convention (y relative to the content-area top, char offsets
 * into the whole stream).
 */
class BoxBookLayout(
    result: BoxLayoutResult,
) : BreakAwareBookLayout {

    private val lines: List<FlowedLine> = result.lines

    override val lineCount: Int get() = lines.size

    override val length: Int get() = lines.lastOrNull()?.charEnd ?: 0

    override fun getLineTop(i: Int): Int = lines[i].yTop

    override fun getLineBottom(i: Int): Int = lines[i].yBottom

    override fun getLineStart(i: Int): Int = lines[i].charStart

    override fun getLineEnd(i: Int): Int = lines[i].charEnd

    override fun isParagraphBoundaryLine(i: Int): Boolean = lines[i].paragraphStart

    /**
     * Line ranges that must stay whole across a page break. A box (leaf or container) contributes a
     * range when it opts into `break-inside: avoid` and actually owns lines. Whether a range
     * physically fits a page is left to the Paginator (the only party that knows the page height),
     * which retreats to the range start only when the whole range fits.
     */
    override val breakInsideAvoidRanges: List<IntRange> by lazy {
        val out = ArrayList<IntRange>()
        for (box in result.boxes) collectBreakRanges(box, out)
        out
    }

    private fun collectBreakRanges(box: LayoutBox, out: MutableList<IntRange>) {
        if (box.breakInsideAvoid && box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            out.add(box.firstLineIndex until box.lastLineExclusive)
        }
        for (child in box.childBoxes) collectBreakRanges(child, out)
    }
}