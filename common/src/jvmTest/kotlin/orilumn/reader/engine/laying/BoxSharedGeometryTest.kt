package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-source geometry lock: the shared width/advance functions every dual-implementation path
 * must funnel through ([NormalFlowLayout]'s `descendContentWidth` / `consecutiveLeafAdvance`) must
 * reproduce, byte-for-byte, the geometry the canonical `emit` flow pass actually produces. If either
 * function drifts from the canonical box model, these tests fail before any page-level overflow does.
 */
class BoxSharedGeometryTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    /** Deterministic breaker: exactly `charsPerLine` chars per line (width-independent). */
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

    /** Wires every node's [MarkupElement.parent] so ancestor-chain walks (lazy + shared) work. */
    private fun linkParents(n: MarkupElement, p: MarkupElement?) {
        n.parent = p
        for (c in n.children) linkParents(c, n)
    }

    private fun compute(map: Map<MarkupElement, ComputedStyle>, el: MarkupElement): ComputedStyle =
        map[el] ?: ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)

    /** Runs the canonical box layout and returns the flattened leaf boxes in document order. */
    private fun layoutLeaves(root: MarkupElement): List<LayoutBox> {
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styleMap)
        return flattenLeaves(result.boxes)
    }

    @Test
    fun sharedWidthMatchesCanonicalContentWidth() {
        // body > div(margin/padding/border) > section(padding) > p → the leaf's border-box width.
        val p = node("p", children = listOf(text("abcdef")))
        val section = node("section", mapOf("style" to "padding: 3px 8px"), children = listOf(p))
        val div = node("div", mapOf("style" to "border: 2px; padding: 4px 10px; margin: 5px"), children = listOf(section))
        val root = node("body", children = listOf(div))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leafBox = layoutLeaves(root).single()
        val sharedW = NormalFlowLayout.descendContentWidth(p, 30) { e ->
            val s = compute(styleMap, e)
            (s.padding.horizontal + s.border.horizontal).roundToInt()
        }
        assertEquals(leafBox.contentWidth, sharedW)
    }

    @Test
    fun siblingLeavesCollapseToTheLargerMargin() {
        val p1 = node("p", mapOf("style" to "margin-bottom: 20px"), listOf(text("abcdef")))
        val p2 = node("p", mapOf("style" to "margin-top: 30px"), listOf(text("ghijklm")))
        val root = node("body", children = listOf(p1, p2))
        linkParents(root, null)

        assertConsecutiveAdvancesMatchHeavy(root)
    }

    @Test
    fun containerBottomMarginParticipatesInTheCollapse() {
        // section(margin-bottom:50) wrapping p(A), followed by p(B) sibling — emit keeps section's 50.
        val section = node("section", mapOf("style" to "margin-bottom: 50px"), children = listOf(node("p", children = listOf(text("aa")))))
        val next = node("p", children = listOf(text("bb")))
        val root = node("body", children = listOf(section, next))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leaves = layoutLeaves(root)
        assertEquals(2, leaves.size)
        val gap = NormalFlowLayout.consecutiveLeafAdvance(leaves[0].el!!, leaves[1].el!!) { compute(styleMap, it) }
        assertEquals(50, gap)
        assertConsecutiveAdvancesMatchHeavy(root)
    }

    @Test
    fun `nextInsideContainerCollapsesPrecedingMargin`() {
        // p(margin-bottom:40) followed by div(p B): CSS collapses p's 40 with the (margin-less) div's 0
        // → 40. The old "entered top-aligned" behavior dropped the 40 entirely; skeletal containers must
        // now honor the box model like a leaf.
        val a = node("p", mapOf("style" to "margin-bottom: 40px"), children = listOf(text("aa")))
        val div = node("div", children = listOf(node("p", children = listOf(text("bb")))))
        val root = node("body", children = listOf(a, div))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leaves = layoutLeaves(root)
        assertEquals(2, leaves.size)
        val gap = NormalFlowLayout.consecutiveLeafAdvance(leaves[0].el!!, leaves[1].el!!) { compute(styleMap, it) }
        assertEquals(40, gap)
        assertConsecutiveAdvancesMatchHeavy(root)
    }

    /**
     * Regression: consecutiveLeafAdvance must round exactly where `emit` does — a collapsed margin in
     * its own `.roundToInt()`, each container's border/padding pair in another, and a first-child
     * margin in its own. Summing the gap in floats and rounding once at the end drifted ±1px off the
     * canonical box flow whenever fractional margins/padding straddled a .5 rounding boundary, so the
     * incremental/temp geometry (rebuilt via consecutiveLeafAdvance) disagreed with the canonical
     * pagination that wrote the disk tables — cumulative page under-fill / slight overflow when a book
     * was opened from its persisted tables.
     */
    @Test
    fun `fractional margins and container padding round per step like emit`() {
        val a = node("div", mapOf("style" to "margin-bottom: 0.5px"), children = listOf(node("p", children = listOf(text("aa")))))
        val b = node("div", mapOf("style" to "padding-top: 0.5px"), children = listOf(node("p", children = listOf(text("bb")))))
        val root = node("body", children = listOf(a, b))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leaves = layoutLeaves(root)
        // Canonical emit: b's collapse consumes .roundToInt(0.5)=1, then b's top padding .roundToInt(0.5)=1.
        // (A float-summed advance would round 1.0 → 1 and drift −1 from the canonical 2.)
        val shared = NormalFlowLayout.consecutiveLeafAdvance(leaves[0].el!!, leaves[1].el!!) { compute(styleMap, it) }
        assertEquals(leaves[1].contentTop - leaves[0].contentBottom, shared)
        assertConsecutiveAdvancesMatchHeavy(root)
    }

    /**
     * Regression for the rust-book chapter-1 symptom: a `pre` whose only child is `code{display:block}`
     * becomes a **container** box, and a container's own top border+padding must space its first child
     * down (not overlap the preceding paragraph) while its bottom border+padding must push a following
     * sibling down (not pull it into the pre's padded bottom).
     */
    @Test
    fun preContainerPaddingDoesNotOverlapPrecedingText() {
        val para = node("p", mapOf("style" to "margin-bottom: 20px"), children = listOf(text("正文段落正文段落正文段落正文段落")))
        val code = node("code", mapOf("style" to "display:block; padding: 10px"), children = listOf(text("let x = 1; let y = 2; let z = 3;")))
        val pre = node("pre", mapOf("style" to "padding: 12px 16px; border: 1px solid #000000; margin: 20px 0"), children = listOf(code))
        val root = node("body", children = listOf(para, pre))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(6)).layoutBoxes(root, widthPx = 30, styleMap = styleMap)
        val leaves = flattenLeaves(result.boxes)
        val paraLeaf = leaves[0]
        val codeLeaf = leaves[1]
        val preBox = findBox(result.boxes, pre)!!

        // The pre background/border box must start at or below the preceding paragraph's content bottom
        // — it must never overlap the text above (the reported "negative margin-top / overlap" symptom).
        assertTrue(
            "pre bg box overlaps preceding text: pre.contentTop=${preBox.contentTop}, para.contentBottom=${paraLeaf.contentBottom}",
            preBox.contentTop >= paraLeaf.contentBottom,
        )
        // Top padding/border create the inner inset: pre bg top = code border-box top − (pre top edges).
        assertEquals(codeLeaf.contentTop - (1 + 12), preBox.contentTop)
        // Bottom padding/border mirror the top: pre bg bottom = code border-box bottom + (pre bottom edges).
        assertEquals(codeLeaf.contentBottom + (12 + 1), preBox.contentBottom)
        // The shared advance (light path) equals the canonical heavy gap for this leaf pair.
        val sharedGap = NormalFlowLayout.consecutiveLeafAdvance(paraLeaf.el!!, codeLeaf.el!!) { compute(styleMap, it) }
        assertEquals(codeLeaf.contentTop - paraLeaf.contentBottom, sharedGap)
    }

    @Test
    fun followingSiblingDoesNotInvadeContainerBottomPadding() {
        val code = node("code", mapOf("style" to "display:block"), children = listOf(text("aaa")))
        val pre = node("pre", mapOf("style" to "padding: 12px 16px; border: 1px solid #000000"), children = listOf(code))
        val after = node("p", children = listOf(text("bbbb")))
        val root = node("body", children = listOf(pre, after))
        linkParents(root, null)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(6)).layoutBoxes(root, widthPx = 30, styleMap = styleMap)
        val leaves = flattenLeaves(result.boxes)
        val preBox = findBox(result.boxes, pre)!!
        val codeLeaf = leaves[0]
        val afterLeaf = leaves[1]

        // The sibling after the pre must begin at/after the pre's content bottom, not inside its padding.
        assertTrue("following sibling pulled up into pre bottom padding", afterLeaf.contentTop >= preBox.contentBottom)
        val shared = NormalFlowLayout.consecutiveLeafAdvance(codeLeaf.el!!, afterLeaf.el!!) { compute(styleMap, it) }
        assertEquals(afterLeaf.contentTop - codeLeaf.contentBottom, shared)
    }

    /** For every consecutive leaf pair, the shared function must equal the canonical emit gap. */
    private fun findBox(boxes: List<LayoutBox>, el: MarkupElement): LayoutBox? {
        for (b in boxes) {
            if (b.el === el) return b
            val r = findBox(b.childBoxes, el)
            if (r != null) return r
        }
        return null
    }

    /** For every consecutive leaf pair, the shared function must equal the canonical emit gap. */
    private fun assertConsecutiveAdvancesMatchHeavy(root: MarkupElement, css: String = "") {
        val authorSheet = if (css.isEmpty()) LightCssParser().parse("") else LightCssParser().parse(css)
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(authorSheet)).compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, css.isNotBlank())
        val leaves = run {
            val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styleMap, classify = classify)
            flattenLeaves(result.boxes)
        }
        for (i in 1 until leaves.size) {
            val heavyGap = leaves[i].contentTop - leaves[i - 1].contentBottom
            val sharedGap = NormalFlowLayout.consecutiveLeafAdvance(leaves[i - 1].el!!, leaves[i].el!!) { styleMap[it] ?: ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f) }
            assertEquals("pair $i heavy gap does not match shared advance", heavyGap, sharedGap)
        }
    }

    /**
     * CSS 2.1 §8.3.1 regression: the top margin of a parent block collapses transitively with its
     * first-child chain through every edge-free container down to a leaf or a box with its own top
     * border/padding (edge terminator). Both paths (emit + consecutiveLeafAdvance) must produce
     * identical geometry for every leaf pair.
     */
    @Test
    fun `parent first child top margins collapse along the flush chain`() {
        val textEl = MarkupElement("#text", text = "第 4 章")
        val secNumSpan = MarkupElement("span", mapOf("class" to "sec-num"), listOf(textEl))
        val anonText = MarkupElement("#text", text = "认识所有权")
        val h1 = MarkupElement("h1", children = listOf(secNumSpan, anonText))
        val nextP = MarkupElement("p", children = listOf(MarkupElement("#text", text = "正文")))
        val root = MarkupElement("body", children = listOf(h1, nextP))
        linkParents(root, null)

        // CSS: h1 has no top edges (margin-only), sec-num is display:block (a leaf with its own margin),
        // so h1.mt and sec-num.mt collapse via §8.3.1 into one rounded max — NOT a stacked sum.
        // An anonymous text sibling inside h1 has mt 0 and inherits h1's styles; sec-num mb 30px
        // collapses with it as plain siblings.
        val css = """
            h1 { margin-top: 10px; margin-bottom: 30px }
            .sec-num { display: block; margin-top: 10px; margin-bottom: 30px }
        """.trimIndent()
        assertConsecutiveAdvancesMatchHeavy(root, css)

        // Verify the first line really starts at the collapsed 10px (not the stacked 20px).
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css))).compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, true)
        val result = BoxLayouter(10f, FixedWidthBreaker(100)).layoutBoxes(root, widthPx = 300, styleMap = styleMap, classify = classify)
        val leaves = flattenLeaves(result.boxes)
        assertEquals(10, leaves[0].contentTop) // max(h1.mt=10, sec.mt=10) = 10, not 20
    }

    private fun flattenLeaves(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(flattenLeaves(b.childBoxes)) else out.add(b)
        return out
    }

    /**
     * CSS 2.1 §8.3.1 (bottom) regression mirror of `parent first child top margins collapse along the
     * flush chain`: a bottom-edge-free, auto-height container collapses its own bottom margin with the
     * entire last-child chain (every edge-free container's margin down to the deepest leaf). Both paths
     * (emit + consecutiveLeafAdvance) must produce identical geometry for every leaf pair, and the
     * collapse must win over the old stacked 14+8=22.
     */
    @Test
    fun `parent last child bottom margins collapse along the flush chain`() {
        val section = node("section", mapOf("style" to "margin-bottom: 14px"), children = listOf(
            node("p", children = listOf(text("aa"))),
            node("div", mapOf("style" to "margin-bottom: 8px"), children = listOf(node("p", children = listOf(text("bb"))))),
        ))
        val next = node("p", children = listOf(text("cc")))
        val root = node("body", children = listOf(section, next))
        linkParents(root, null)

        assertConsecutiveAdvancesMatchHeavy(root)

        // section(14) > div(8) > bb(0) — the whole last-leaf chain collapses to max(14,8,0)=14.
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leaves = layoutLeaves(root)
        assertEquals(3, leaves.size)
        val gap = NormalFlowLayout.consecutiveLeafAdvance(leaves[1].el!!, leaves[2].el!!) { compute(styleMap, it) }
        assertEquals(14, gap)
    }

    /**
     * CSS 2.1 §8.3.1 (bottom): bottom border/padding detaches the parent's margin from the last child's
     * — the child chain is trapped above the padding, and the parent contributes only its own bottom
     * margin. Both paths must agree, and the leaf gap includes the section's 8px padding: 8+14=22, not
     * the folded 14.
     */
    @Test
    fun `padded container bottom margin never folds the last child chain`() {
        val section = node("section", mapOf("style" to "margin-bottom: 14px; padding-bottom: 8px"), children = listOf(
            node("div", mapOf("style" to "margin-bottom: 8px"), children = listOf(node("p", children = listOf(text("bb"))))),
        ))
        val next = node("p", children = listOf(text("cc")))
        val root = node("body", children = listOf(section, next))
        linkParents(root, null)

        assertConsecutiveAdvancesMatchHeavy(root)

        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val leaves = layoutLeaves(root)
        assertEquals(2, leaves.size)
        val gap = NormalFlowLayout.consecutiveLeafAdvance(leaves[0].el!!, leaves[1].el!!) { compute(styleMap, it) }
        assertEquals(8 + 14, gap) // padding-bottom 8 + section's own margin-bottom 14 — children's 8 NOT folded
    }
}