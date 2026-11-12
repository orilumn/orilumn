package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths

/**
 * 块级重塑单测：换叶标签、包裹层增删、相邻同形态合并、没动的块不碰。
 * 口径见 `docs/正文修改-块级标记.md`。
 */
class BlockRestructureTest {

    private fun txt(s: String) = MarkupElement("#text", text = s)

    private fun el(tag: String, vararg c: MarkupElement) = MarkupElement(tag, emptyMap(), c.toList())

    private fun el(tag: String, attrs: Map<String, String>, vararg c: MarkupElement) =
        MarkupElement(tag, attrs, c.toList())

    /** 紧凑打印：`root[blockquote[p[a],p[b]]]`。 */
    private fun dump(e: MarkupElement): String = when {
        e.tag == "#text" -> e.text
        e.children.isEmpty() -> e.tag
        else -> "${e.tag}[${e.children.joinToString(",") { dump(it) }}]"
    }

    /** 建树、重链，返回（根, 叶表）。 */
    private fun tree(root: MarkupElement): Pair<MarkupElement, List<MarkupElement>> {
        MarkupPaths.assignParents(root)
        val leaves = ArrayList<MarkupElement>()
        fun walk(e: MarkupElement) {
            if (e.tag in setOf("p", "li", "pre", "h1", "h2", "h3", "h4", "h5", "h6")) leaves += e
            e.children.forEach(::walk)
        }
        walk(root)
        return root to leaves
    }

    private fun restructure(root: MarkupElement, vararg want: Pair<MarkupElement, BlockKind>) =
        BlockRestructure.apply(root, want.toMap())

    // ---------------------------------------------------------------- 换标签

    @Test
    fun paragraphBecomesHeading() {
        val (root, leaves) = tree(el("div", el("p", txt("标题"))))
        val p = leaves.first()
        val result = restructure(root, p to BlockKind.Heading2)
        assertEquals("div[h2[标题]]", dump(result.root))
        assertTrue(result.changed)
        assertEquals("h2", result.nodeMap[p]?.tag)
    }

    @Test
    fun nothingToDoReturnsTheSameTree() {
        val (root, _) = tree(el("div", el("p", txt("a"))))
        val result = BlockRestructure.apply(root, emptyMap())
        assertSame(root, result.root)
        assertFalse(result.changed)
    }

    @Test
    fun unchangedSiblingsKeepTheirIdentity() {
        val (root, leaves) = tree(el("div", el("p", txt("a")), el("p", txt("b"))))
        val (a, b) = leaves
        val result = restructure(root, a to BlockKind.Heading1)
        assertEquals("div[h1[a],p[b]]", dump(result.root))
        // 没动的那一段还是原来那个节点（生成内容、装饰都挂在它身上）。
        assertSame(b, result.root.children[1])
    }

    // ---------------------------------------------------------------- 包裹层

    @Test
    fun adjacentQuotesShareOneBlockquote() {
        val (root, leaves) = tree(el("div", el("p", txt("a")), el("p", txt("b"))))
        val result = restructure(root, leaves[0] to BlockKind.Quote, leaves[1] to BlockKind.Quote)
        assertEquals("div[blockquote[p[a],p[b]]]", dump(result.root))
    }

    @Test
    fun quoteSplitByAPlainParagraph() {
        val (root, leaves) = tree(
            el("blockquote", el("p", txt("a")), el("p", txt("b")), el("p", txt("c"))),
        )
        val result = restructure(root, leaves[1] to BlockKind.Paragraph)
        assertEquals("div[blockquote[p[a]],p[b],blockquote[p[c]]]", dump(el("div", *result.root.children.toTypedArray())))
    }

    @Test
    fun paragraphLiftsOutOfQuote() {
        val (root, leaves) = tree(el("div", el("blockquote", el("p", txt("a")))))
        val result = restructure(root, leaves.first() to BlockKind.Paragraph)
        assertEquals("div[p[a]]", dump(result.root))
    }

    @Test
    fun newBlockquoteKeepsTheOriginalAttributes() {
        val (root, leaves) = tree(el("div", el("p", txt("a"))))
        val result = restructure(root, leaves.first() to BlockKind.Quote)
        // 原书没有引用层时没有属性可沿用；有的话（class / style）必须带上。
        assertEquals("div[blockquote[p[a]]]", dump(result.root))
        assertEquals(emptyMap<String, String>(), result.root.children.first().attrs)

        val (root2, leaves2) = tree(
            el("div", el("blockquote", mapOf("class" to "q"), el("p", txt("a"))), el("p", txt("b"))),
        )
        val result2 = restructure(root2, leaves2.last() to BlockKind.Quote)
        assertEquals("div[blockquote[p[a],p[b]]]", dump(result2.root))
        assertEquals("q", result2.root.children.first().attrs["class"])
    }

    @Test
    fun orderedAndBulletListsSplit() {
        val (root, leaves) = tree(el("ul", el("li", txt("a")), el("li", txt("b"))))
        val result = restructure(root, leaves[0] to BlockKind.Ordered)
        assertEquals("div[ol[li[a]],ul[li[b]]]", dump(el("div", *result.root.children.toTypedArray())))
    }

    @Test
    fun listItemBecomesPlainParagraph() {
        val (root, leaves) = tree(el("div", el("ul", el("li", txt("a")), el("li", txt("b")))))
        val result = restructure(root, leaves[0] to BlockKind.Paragraph)
        assertEquals("div[p[a],ul[li[b]]]", dump(result.root))
    }

    @Test
    fun listItemBecomesQuote() {
        val (root, leaves) = tree(el("div", el("ul", el("li", txt("a")))))
        val result = restructure(root, leaves.first() to BlockKind.Quote)
        assertEquals("div[blockquote[p[a]]]", dump(result.root))
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun structuralContainersAreNotFlattened() {
        val (root, leaves) = tree(
            el("div", el("section", el("p", txt("a"))), el("p", txt("b"))),
        )
        val result = restructure(root, leaves[0] to BlockKind.Heading3)
        assertEquals("div[section[h3[a]],p[b]]", dump(result.root))
    }

    @Test
    fun multiParagraphListItemStaysWhole() {
        // `li > p`：拆开会让兄弟段落丢掉列表项缩进，所以列表项不摊平，只在它内部换标签。
        val (root, leaves) = tree(
            el("div", el("ul", el("li", el("p", txt("a")), el("p", txt("b"))))),
        )
        val first = leaves.first { it.tag == "p" }
        val result = restructure(root, first to BlockKind.Heading4)
        assertEquals("div[ul[li[h4[a],p[b]]]]", dump(result.root))
    }

    @Test
    fun nestedQuoteInsideListKeepsItsPlace() {
        // 引用嵌在列表项里：只改最近的一层（引用），列表项不动。
        val (root, leaves) = tree(
            el("div", el("ul", el("li", el("blockquote", el("p", txt("a")))))),
        )
        val result = restructure(root, leaves.first { it.tag == "p" } to BlockKind.Paragraph)
        assertEquals("div[ul[li[p[a]]]]", dump(result.root))
    }

    @Test
    fun idIsDroppedWhenTheTagChanges() {
        val p = MarkupElement("p", mapOf("id" to "x1", "class" to "c"), listOf(txt("a")))
        val (root, _) = tree(el("div", p))
        val result = restructure(root, p to BlockKind.Heading2)
        assertEquals(mapOf("class" to "c"), result.root.children.first().attrs)
    }

    @Test
    fun blockKindsRoundTripThroughElementShape() {
        val (root, leaves) = tree(
            el(
                "div",
                el("p", txt("a")),
                el("blockquote", el("p", txt("b"))),
                el("ul", el("li", txt("c"))),
                el("ol", el("li", txt("d"))),
                el("h2", txt("e")),
                el("pre", txt("f")),
            ),
        )
        val kinds = leaves.map { BlockKind.ofElement(it) }
        assertEquals(
            listOf(
                BlockKind.Paragraph,
                BlockKind.Quote,
                BlockKind.Bullet,
                BlockKind.Ordered,
                BlockKind.Heading2,
                BlockKind.Pre,
            ),
            kinds,
        )
    }
}
