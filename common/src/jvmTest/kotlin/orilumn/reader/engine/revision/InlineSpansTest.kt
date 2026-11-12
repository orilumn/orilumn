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

    // ---------------------------------------------------------------- 只包着图的元素

    /**
     * 只用来包一张图的 `<span>` / `<a>` 不该摊成标记：卡片里除了那个占位字没有可改的内容，
     * 写一串标签只会让读者以为要删点什么（删了反而把图的外层样式弄丢）。
     */
    @Test
    fun imageOnlyWrapperIsNotShownAsMarkup() {
        val p = el(
            "p",
            txt("前"),
            el("span", el("img", attrs = mapOf("src" to "a.png")), attrs = mapOf("class" to "pic")),
            txt("后"),
        )
        assertTrue("只包一张图的 span 不该摊成标记：${spansOf(p)}", spansOf(p).isEmpty())
        // 链接包着图同理（整段只有一张图、作者用 <a> 包了一层）。
        val linked = el("p", el("a", el("img", attrs = mapOf("src" to "b.png")), attrs = mapOf("href" to "x")))
        assertTrue(spansOf(linked).isEmpty())
    }

    @Test
    fun nestedImageWrappersAreAllHiddenEvenWithSurroundingBlanks() {
        // 真实书里的写法：外层给间距、内层是媒体对象，图和两侧各一个空格都在这两层里面。
        // 包几层都只是「给这张图挂样式」，一层都不该露到卡片里。
        val p = el(
            "p",
            txt("会出现箭头图标"),
            el(
                "span",
                txt(" "),
                el("span", el("img", attrs = mapOf("src" to "a.png")), attrs = mapOf("class" to "inlinemediaobject")),
                txt(" "),
                attrs = mapOf("class" to "guiicon"),
            ),
            txt("、"),
            el(
                "span",
                txt(" "),
                el("span", el("img", attrs = mapOf("src" to "b.png")), attrs = mapOf("class" to "inlinemediaobject")),
                txt(" "),
                attrs = mapOf("class" to "guiicon"),
            ),
        )
        assertTrue("里外两层都得藏：${spansOf(p)}", spansOf(p).isEmpty())
    }

    @Test
    fun wrapperThatAlsoHasTextIsStillShown() {
        // 同一层里既有字又有图：这层标签是读者看得见的结构，照旧摊成标记。
        val p = el(
            "p",
            el("span", txt("说明"), el("img", attrs = mapOf("src" to "a.png")), attrs = mapOf("class" to "pic")),
        )
        assertEquals(listOf("span"), spansOf(p).map { it.sig.tag })
    }

    @Test
    fun imageOnlyWrapperRoundTripsAsPlainPlaceholder() {
        val p = el(
            "p",
            txt("前"),
            el("span", el("img", attrs = mapOf("src" to "a.png")), attrs = mapOf("class" to "pic")),
            txt("后"),
        )
        val leaf = withParents(p)
        val segments = segmentsOf(leaf)
        val spans = InlineSpans.ofLeaf(leaf, segments, { WhiteSpace.NORMAL })
        // 卡片里就是「前 + 一个占位字 + 后」，没有标签。
        assertEquals("前\uFFFC后", segments.text)
        assertEquals(segments.text, InlineMarkup.show(segments.text, spans).let { InlineMarkup.parse(it).text })
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
        // 标记**不跨行**（规格 §2.3 / §4.5）：跨着 `<br>` 的元素语法糖写不回去，改用标签形式。
        // 两种写法解析结果相同，所以这里只认「往返等价」，不再固定成 `*a\nb*`。
        // 段内换行写成硬断行（行尾 `\`），所以块里仍然是一行（块边界与 `<br>` 分得开）。
        assertEquals("<em>a\\\nb</em>", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals("a\nb", parsed.text)
        assertEquals(0 to 3, parsed.spans.single().start to parsed.spans.single().endExclusive)
    }

    @Test
    fun spansAreMeasuredFromTheSelectionStartNotFromTheLeafStart() {
        // 跨段选区的第 2 段：选区从上一段开始，这一叶的 localFrom 被夹到 0，
        // 区间以**叶首**为零点，要整体平移到选区坐标系（读者复报的「第二段的标记挪到第一段上」）。
        val later = InlineSpans.toSelectionSpace(
            spans = listOf(InlineMarkup.StyleSpan(1, 3, InlineMarkup.StyleSig("code"))),
            leafStart = 200,
            localFrom = 0,
            selectionStart = 105,
        )
        assertEquals(96 to 98, later.single().start to later.single().endExclusive)
    }

    @Test
    fun firstLeafOfASelectionIsNotShifted() {
        // 第一叶：选区从叶中起（localFrom = 5），ofLeaf 的区间本来就相对 localFrom，位移必须是 0。
        // 早先按「叶首 − 选区起点」一刀切，会把这一叶的区间往前挪（负位移）。
        val first = InlineSpans.toSelectionSpace(
            spans = listOf(InlineMarkup.StyleSpan(2, 4, InlineMarkup.StyleSig("em"))),
            leafStart = 100,
            localFrom = 5,
            selectionStart = 105,
        )
        assertEquals(2 to 4, first.single().start to first.single().endExclusive)
    }
}
