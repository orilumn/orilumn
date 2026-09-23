package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the Rust-book `pre`/`.filename` rendering defects.
 *
 * Root cause (shared by the heavy and light render paths): both line-flow rebuilds
 * ([orilumn.reader.engine.BoxChapterLayouter.rebuildLinesFromShapes] and
 * [orilumn.reader.engine.BoxChapterLayouter.rebuildLocalLines]) placed a leaf's FIRST line flush at its
 * border-box top instead of subtracting/nesting its own top border+padding (which
 * [orilumn.reader.engine.laying.NormalFlowLayout.emit] does via `contentY = contentTop + topEdges`). A
 * self-padded block (`pre`, `p.filename`) therefore lost its top padding AND drifted upward — the
 * overlapping gray band / `.filename` title — and the light background builder double-counted the
 * owner's edges on top of that.
 *
 * The two line-rebuild functions themselves need Android `StaticLayout` shapes, so they are exercised
 * on-device, not in the JVM. This test locks the two JVM-testable seams that encode the shared fix:
 *  1. [NormalFlowLayout.backgroundBandExtent] — the self-owned vs container-owner band rule that
 *     eliminates the double-count.
 *  2. The box-model invariant (from [NormalFlowLayout.emit], which [layoutBoxes] exercises) that a
 *     self-padded leaf's first line sits at `contentTop + topEdges` and that its background band does
 *     not overlap the preceding block — the exact geometry both rebuilds now reproduce.
 */
class PreContainerMarginReproTest {

    /** Deterministic breaker: exactly `charsPerLine` chars per line at 15px each. */
    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + charsPerLine).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private val converter = HtmlTreeConverter()

    private fun engineFor(css: String): StyleComputer =
        StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))

    // ---------------------------------------------------------------- backgroundBandExtent

    @Test
    fun `self-owned pre band is exactly the leaf extent, edges counted once`() {
        // A `pre` carrying its own background is SELF-owned: its aggregate extent already spans its own
        // 9px top+bottom edges (counted once). The band must NOT be extended again — that double-count
        // is what pushed the Rust `pre` band 18px above its border-box top.
        val (top, bottom) = NormalFlowLayout.backgroundBandExtent(40, 100, 9, 9, selfOwned = true)
        assertEquals(40, top)
        assertEquals(100, bottom)
    }

    @Test
    fun `container owner extends band by its own edges to fill padding`() {
        // A container (blockquote/.rust-example-rendered) is NOT self-owned: its text lines span only the
        // content area, so the band must extend up by its top edges and down by its bottom edges so its
        // vertical padding visually carries the fill.
        val (top, bottom) = NormalFlowLayout.backgroundBandExtent(40, 100, 9, 9, selfOwned = false)
        assertEquals(31, top)
        assertEquals(109, bottom)
    }

    // ------------------------------------------------- emit invariant both rebuilds now reproduce

    @Test
    fun `padded pre first line sits at border-box top + its own top edges`() {
        val root = converter.convert("<p>上</p><pre>代码</pre>")!!
        val engine = engineFor("pre { padding: 9px; background-color: #eeeeee }")
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, 40, styleMap, classify)

        val pre = result.boxes.flatMap { box -> leavesOf(box) }.first { it.el?.tag == "pre" }
        assertEquals(9, pre.style.padding.top.roundToInt())

        // emit(): first line = contentTop + own top edges (the invariant rebuildLinesFromShapes and
        // rebuildLocalLines now both reproduce). Previously both rebuilds put it flush at contentTop.
        val firstLine = result.lines[if (pre.firstLineIndex >= 0) pre.firstLineIndex else fail()]
        assertEquals("pre first line must be inseted by its own top border+padding",
            pre.contentTop + (pre.style.border.top + pre.style.padding.top).roundToInt(), firstLine.yTop)

        // The pre's border-box bottom = its last line bottom + its own bottom edges (padding filled).
        val lastLine = result.lines[pre.lastLineExclusive - 1]
        assertEquals(pre.contentBottom, lastLine.yBottom + (pre.style.border.bottom + pre.style.padding.bottom).roundToInt())

        // The pre background band (contentTop..contentBottom, what BoxDrawer paints) starts exactly where
        // the preceding `p` ends: NO gray overlap above the code block.
        val prevLine = result.lines.take(pre.firstLineIndex).last()
        assertEquals("pre background must not overlap the block above", prevLine.yBottom, pre.contentTop)
    }

    @Test
    fun `filename plus pre zeroes the pre top margin inside a blockquote too`() {
        // Rust book: `.filename + pre { margin-top: 0 }` must hold for a `.filename`/`pre` pair nested
        // inside a blockquote. jsoup keeps the whitespace between the tags as a `#text` sibling, and the
        // `+` combinator used to choke on it (only body-level pairs matched) — leaving the pre with its
        // 0.5rem top margin and the visible gap between the two gray bands. CSS-wise whitespace text
        // never breaks `+`, so the pre's top margin must be 0 and the layout gap 0 in both contexts.
        val rustCss = """
            * { margin: 0; padding: 0; border: 0; }
            blockquote { margin: 0.5rem 0; padding: 0.5rem; padding-bottom: 0.2rem; background: rgba(235,255,255,0.3); }
            pre { background-color: rgba(128,128,128,0.08); margin: 0.5rem 0; padding: 0.5rem; }
            .filename { display: block; margin-top: 0.5rem; margin-bottom: 0; padding-top: 0.5rem; background-color: rgba(128,128,128,0.08); }
            .filename + pre { margin-top: 0; }
        """.trimIndent()
        val root = converter.convert(
            "<p>引用如下</p>" +
                "<blockquote>\n<div class=\"filename\">文件名：src/main.rs</div>\n<pre><code>struct User {}</code></pre>\n" +
                "<div class=\"filename\">文件名：src/main.rs</div>\n<pre><code>fn main()</code></pre>\n</blockquote>",
        )!!
        val engine = engineFor(rustCss)
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(10f, FixedWidthBreaker(4)).layoutBoxes(root, 40, styleMap, classify)

        val blockquote = root.children.first { it.tag == "blockquote" }
        val internalPre = blockquote.children.first { it.tag == "pre" }
        val fname = blockquote.children.first { it.tag == "div" }
        assertEquals("blockquote-internal .filename + pre must zero the pre top margin",
            0f, styleMap[internalPre]!!.margin.top)

        // Layout: the pre's border-box top sits exactly at the .filename's content-bottom (gap 0).
        val leaves = result.boxes.flatMap { box -> leavesOf(box) }
        val preLeaves = leaves.filter { it.el?.tag == "pre" }.sortedBy { it.contentTop }
        val fnameLeaf = leaves.first { it.el === fname }
        assertEquals("no vertical gap between .filename and the pre it introduces",
            fnameLeaf.contentBottom, preLeaves.first().contentTop)
    }

    private fun leavesOf(box: LayoutBox): List<LayoutBox> =
        if (box.isContainer) box.childBoxes.flatMap { leavesOf(it) } else listOf(box)

    private fun fail(): Int {
        throw AssertionError("missing pre leaf")
    }
}