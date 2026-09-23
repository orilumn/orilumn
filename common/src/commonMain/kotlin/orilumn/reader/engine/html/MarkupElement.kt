package orilumn.reader.engine.html

/**
 * XHTML semantic-tree node (pure logic, zero Android dependency, JVM unit-testable).
 *
 * Built by [HtmlTreeConverter] from the chapter XHTML; it is a stable intermediate representation
 * between parsing and layout: both the legacy `CssLayouter` (span materialization) and the new box
 * engine ([orilumn.reader.engine.laying.NormalFlowLayout]) depend only on this tree rather than raw XML,
 * decoupling parsing from rendering, and malformed-book tolerance is concentrated in this layer.
 *
 * @property tag the tag name (lowercased localName; `#text` denotes a plain-text leaf, `br` a forced line-break node).
 * @property attrs the attribute map (keeps the raw `style`, parsed by [CssInlineResolver]).
 * @property children child nodes; a text leaf's text is stored directly in [text].
 * @property text the text carried by this node itself (only `#text` leaves have a value; empty for other nodes).
 */
class MarkupElement(
    val tag: String,
    val attrs: Map<String, String> = emptyMap(),
    val children: List<MarkupElement> = emptyList(),
    val text: String = "",
) {
    /** Parent node (null for the root). Filled once after build by the converter; readers walk up this
     *  chain for lazy cascade. Strong reference is safe: the tree is acyclic and immutable afterward. */
    var parent: MarkupElement? = null

    /** Cumulative length of all leaf text within the subtree (a `br` counts as 1 line-advance; tags excluded); used for progress conversion without materializing spans. */
    val textLength: Long by lazy {
        if (tag == "#text") text.length.toLong()
        else children.sumOf { it.textLength } + if (tag == "br") 1L else 0L
    }

    val isText: Boolean get() = tag == "#text"

    /**
     * Whether the subtree carries real reading content: non-empty char stream with at least one
     * non-whitespace character (pure cover/svg/blank chapters return false).
     *
     * Shared landing guard: both the Android host (`BookDocumentController.locateStart` and its
     * cross-chapter skips) and the desktop host (`DesktopReaderHost.open`) land on the first
     * significant chapter through this single source instead of duplicating the walk.
     */
    fun hasSignificantText(): Boolean {
        if (textLength <= 0) return false
        fun anyVisible(el: MarkupElement): Boolean {
            if (el.isText) return el.text.any { !it.isWhitespace() }
            // `br` carries no text itself (its +1 lives only in textLength); recurse otherwise.
            for (c in el.children) if (anyVisible(c)) return true
            return false
        }
        return anyVisible(this)
    }

    /** Walks from this node to the root (inclusive), nearest first. */
    val ancestorsOrRoot: Sequence<MarkupElement> get() = generateSequence(this) { it.parent }

    override fun toString(): String =
        if (tag == "#text") { if (text.length > 24) text.take(24) + "…" else text }
        else "<$tag>${children.size}"
}