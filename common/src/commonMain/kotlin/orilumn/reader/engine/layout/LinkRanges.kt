package orilumn.reader.engine.layout

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.EmptyGen
import orilumn.reader.engine.laying.GenOf
import orilumn.reader.engine.laying.styledSegments

/** Block-level tag names (shared by [LinkRanges] and [orilumn.reader.engine.layout.CssLayouter]; pure
 *  constant kept here in common so block skipping never touches Android types). */
val BLOCK_TAGS = setOf(
    "p", "div", "blockquote", "pre", "ul", "ol", "li",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "header", "footer", "figure", "figcaption",
    "main", "hgroup", "details", "summary",
)

/** A hyperlink's char range within a paragraph block's absorbed text, with its target `href`. */
data class LinkRange(val start: Int, val endExclusive: Int, val href: String)

/**
 * Extracts `<a href>` link ranges from a block leaf, aligned to the same char offsets
 * [ParagraphShapes.emitText] uses for shaping (text leaves add their length, `<br>` adds 1, nested
 * blocks are skipped). This is the engine-side foundation for tap-to-navigate: given a page's
 * char offset you can look up whether it lies inside a link and which chapter anchor it targets.
 *
 * Pure JVM (unit-testable); the actual tap hit-testing + navigation wiring lives in the reader UI.
 */
object LinkRanges {

    /** [block]'s `<a href>` ranges in ascending char order. [href] is the raw attribute value. */
    fun ofLeaf(block: MarkupElement): List<LinkRange> {
        val out = ArrayList<LinkRange>()
        val cursor = IntArray(1) // char-position cursor threaded through collect()
        collect(block, cursor, out)
        return out
    }

    private fun collect(el: MarkupElement, cursor: IntArray, out: MutableList<LinkRange>) {
        for (c in el.children) {
            when {
                c.isText -> cursor[0] += c.text.length
                c.tag == "br" -> cursor[0] += 1
                c.tag in BLOCK_TAGS -> Unit // nested block shouldn't appear in a leaf
                c.tag == "a" -> {
                    val href = c.attrs["href"]
                    val start = cursor[0]
                    collect(c, cursor, out)
                    val end = cursor[0]
                    if (href != null && end > start) out.add(LinkRange(start, end, href))
                }
                else -> collect(c, cursor, out)
            }
        }
    }

    /**
     * P4-c1 styled-aligned variant of [ofLeaf]: ranges over the exact string the shaper saw
     * ([styledSegments] — white-space normalized, `display:none` excluded, generated content
     * included), so tap hit-testing on shaped text cannot drift from these offsets.
     *
     * Each styled segment is attributed to its nearest ancestor (or self) `<a href>`; contiguous
     * same-href segments merge into one range. Parameters mirror [styledSegments] exactly —
     * callers must pass the same functions the layout was built with.
     */
    fun ofLeafStyled(
        block: MarkupElement,
        wsOf: (MarkupElement) -> WhiteSpace,
        isExcluded: (MarkupElement) -> Boolean,
        isBlock: (MarkupElement) -> Boolean,
        leafWs: WhiteSpace,
        genOf: GenOf = EmptyGen,
        styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
    ): List<LinkRange> {
        val segs = styledSegments(block, wsOf, isExcluded, isBlock, leafWs, genOf, styleOf).segments
        val out = ArrayList<LinkRange>()
        var cursor = 0
        var openHref: String? = null
        var openStart = 0
        fun close() {
            val h = openHref
            if (h != null && cursor > openStart) out.add(LinkRange(openStart, cursor, h))
            openHref = null
        }
        for (s in segs) {
            val href = s.node?.let(::nearestHref)
            val len = s.text.length
            if (href != null) {
                if (href != openHref) {
                    close()
                    openHref = href
                    openStart = cursor
                }
            } else {
                close()
            }
            cursor += len
        }
        close()
        return out
    }

    /** Nearest self-or-ancestor `<a href>` (invalid nested `<a>` resolves to the innermost). */
    private fun nearestHref(node: MarkupElement): String? {
        var cur: MarkupElement? = node
        while (cur != null) {
            if (cur.tag == "a") cur.attrs["href"]?.let { return it }
            cur = cur.parent
        }
        return null
    }
}