package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.styledSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「叶 → 样式区间」单测：逐字归属、选区裁剪、未知标签跳过、与 [InlineMarkup.show] 的往返。
 * 口径见 `docs/正文修改-行内标记语法.md` §3.1。
 */
class InlineSpansTest {

    private fun txt(s: String) = MarkupElement("#text", text = s)

    private fun el(tag: String, vararg children: MarkupElement, attrs: Map<String, String> = emptyMap()) =
        MarkupElement(tag, attrs, children.toList())

    private fun withParents(root: MarkupElement): MarkupElement {
        fun walk(n: MarkupElement, p: MarkupElement?) {
            n.parent = p
            n.children.forEach { walk(it, n) }
        }
        walk(root, null)
        return root
    }

    private fun segmentsOf(root: MarkupElement) = styledSegments(
        root = root,
        wsOf = { WhiteSpace.NORMAL },
        isExcluded = { false },
        isBlock = { false },
        leafWs = WhiteSpace.NORMAL,
    )

    private fun spansOf(root: MarkupElement, from: Int = 0, to: Int = Int.MAX_VALUE): List<InlineMarkup.StyleSpan> {
        val leaf = withParents(root)
        val segments = segmentsOf(leaf)
        return InlineSpans.ofLeaf(
            leaf = leaf,
            segments = segments,
            wsOf = { WhiteSpace.NORMAL },
            from = from,
            to = minOf(to, segments.text.length),
        )
    }

    @Test
    fun spansFollowEachInlineElement() {
        val p = el("p", txt("他"), el("em", txt("慢慢"), attrs = mapOf("class" to "a")), txt("走"))
        val spans = spansOf(p)
        assertEquals(1, spans.size)
        assertEquals(1 to 3, spans.single().start to spans.single().endExclusive)
        assertEquals(InlineMarkup.StyleSig("em", mapOf("class" to "a")), spans.single().sig)
    }

    @Test
    fun spansAreClippedToSelection() {
        val p = el(
            "p",
            txt("他"),
            el("em", txt("慢慢")),
            el("span", txt("走"), attrs = mapOf("style" to "color:red")),
        )
        val spans = spansOf(p, from = 1, to = 4)
        assertEquals(
            listOf(0 to 2 to "em", 2 to 3 to "span"),
            spans.map { it.start to it.endExclusive to it.sig.tag },
        )
    }

    @Test
    fun nestedElementsKeepNesting() {
        val p = el("p", txt("前"), el("em", el("strong", txt("重"))), txt("后"))
        val spans = spansOf(p)
        assertEquals(
            listOf(1 to 2 to "em", 1 to 2 to "strong"),
            spans.map { it.start to it.endExclusive to it.sig.tag },
        )
    }

    @Test
    fun unknownTagsAreSkipped() {
        val p = el("p", el("foo", txt("x")), el("em", txt("y")))
        val spans = spansOf(p)
        assertEquals(listOf("em"), spans.map { it.sig.tag })
    }

    @Test
    fun markupRoundTripsThroughParse() {
        val p = el(
            "p",
            txt("他"),
            el("em", txt("慢慢"), attrs = mapOf("class" to "a")),
            txt("地"),
            el("a", txt("走"), attrs = mapOf("href" to "https://a.b")),
        )
        val leaf = withParents(p)
        val segments = segmentsOf(leaf)
        val spans = InlineSpans.ofLeaf(leaf, segments, { WhiteSpace.NORMAL })
        val markup = InlineMarkup.show(segments.text, spans)
        assertEquals("他*慢慢*地[走](https://a.b)", markup)

        val parsed = InlineMarkup.parse(markup)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(segments.text, parsed.text)
        val restored = InlineMarkup.restore(spans, parsed.spans)
        assertEquals(
            spans.map { it.start to it.endExclusive to it.sig },
            restored.map { it.start to it.endExclusive to it.sig },
        )
    }

    @Test
    fun brInsideElementKeepsSpan() {
        val p = el("p", el("em", txt("a"), el("br"), txt("b")))
        val leaf = withParents(p)
        val segments = segmentsOf(leaf)
        assertEquals("a\nb", segments.text)
        val spans = InlineSpans.ofLeaf(leaf, segments, { WhiteSpace.NORMAL })
        assertEquals(1, spans.size)
        assertEquals(0 to 3, spans.single().start to spans.single().endExclusive)

        val markup = InlineMarkup.show(segments.text, spans)
        assertEquals("*a\nb*", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals("a\nb", parsed.text)
        assertEquals(0 to 3, parsed.spans.single().start to parsed.spans.single().endExclusive)
    }
}
