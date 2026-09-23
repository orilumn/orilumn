package orilumn.reader.engine.layout

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/** P4-c1: styled link ranges align to the shaped string (normalized, exclusions honored). */
class P4cLinkRangesTest {

    private fun text(s: String) = MarkupElement("#text", text = s)

    private fun link(href: String, vararg kids: MarkupElement) =
        MarkupElement("a", attrs = mapOf("href" to href), children = kids.toList())

    private fun p(vararg kids: MarkupElement) = MarkupElement("p", children = kids.toList()).also { fill(it, null) }

    private fun fill(n: MarkupElement, parent: MarkupElement?) {
        n.parent = parent
        for (c in n.children) fill(c, n)
    }

    private fun styled(block: MarkupElement, excluded: Set<MarkupElement> = emptySet()): List<LinkRange> =
        LinkRanges.ofLeafStyled(
            block,
            wsOf = { WhiteSpace.NORMAL },
            isExcluded = { it in excluded },
            isBlock = { false },
            leafWs = WhiteSpace.NORMAL,
        )

    @Test
    fun `ranges follow whitespace-collapsed offsets not raw`() {
        // Raw absorbed text would be "甲  乙丙  丁" (link at [3,5)); styled is "甲 乙丙 丁" (link at [2,4)).
        val block = p(text("甲  "), link("x", text("乙丙")), text("  丁"))
        assertEquals(listOf(LinkRange(2, 4, "x")), styled(block))
    }

    @Test
    fun `adjacent same-href anchors merge`() {
        val block = p(link("x", text("甲")), link("x", text("乙")), text("丙"))
        assertEquals(listOf(LinkRange(0, 2, "x")), styled(block))
    }

    @Test
    fun `excluded subtree leaves no chars and splits ranges`() {
        val hidden = MarkupElement("span", children = listOf(text("丙")))
        val block = p(text("甲"), link("x", text("乙")), hidden, text("丁")).also { fill(it, null) }
        // Styled text "甲乙丁"; the hidden span contributes nothing.
        assertEquals(listOf(LinkRange(1, 2, "x")), styled(block, setOf(hidden)))
    }

    @Test
    fun `styled agrees with raw on clean text`() {
        val block = p(text("看"), link("ch2.html#s", text("这里")), text("和"), link("#t", text("那儿")), text("结"))
        assertEquals(LinkRanges.ofLeaf(block), styled(block))
    }
}
