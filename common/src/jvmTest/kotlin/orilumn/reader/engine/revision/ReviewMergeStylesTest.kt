package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.laying.styledSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审阅视图的跨段修订：样式要跟着审阅文字走。
 *
 * 读者复报：列表中间补一项「测试」，那一行带着行内代码的字体和底色。原因是审阅视图把样式
 * 丢了，新字「没有前面的字可挂」时退化到叶里第一个文本节点 —— 恰好是段首 `<code>` 里面。
 * 这里按真实输入（`<p><code>main()</code>函数是…` → 三块，最后一行是「测试」）钉住结果：
 * 新加的行落在叶级，原来那段的 `main()` 仍是代码元素。
 */
class ReviewMergeStylesTest {

    private fun txt(s: String) = MarkupElement("#text", text = s)
    private fun el(tag: String, vararg c: MarkupElement) = MarkupElement(tag, emptyMap(), c.toList())

    private fun seg(root: MarkupElement) = styledSegments(
        root = root,
        wsOf = { WhiteSpace.NORMAL },
        isExcluded = { false },
        isBlock = { false },
        leafWs = WhiteSpace.NORMAL,
    )

    private fun textOf(node: MarkupElement): String =
        if (node.isText) node.text else node.children.joinToString("") { textOf(it) }

    private fun item(code: String, rest: String) = MarkupElement(
        "li",
        mapOf("class" to "list__item"),
        listOf(
            MarkupElement(
                "p",
                emptyMap(),
                listOf(MarkupElement("code", mapOf("class" to "code"), listOf(txt(code))), txt(rest)),
            ),
        ),
    )

    @Test
    fun insertedListItemStaysOutOfCodeElement() {
        val root = el(
            "body",
            MarkupElement(
                "ul",
                emptyMap(),
                listOf(item("fun", " 用于声明函数"), item("main()", "函数是你的程序开始的地方")),
            ),
        )
        MarkupPaths.assignParents(root)
        val ul = root.children[0]
        val leaves = listOf(ul.children[0].children[0], ul.children[1].children[0])

        val old = "fun 用于声明函数main()函数是你的程序开始的地方"
        val revisionText = "fun 用于声明函数${RevisionBlocks.SEPARATOR}" +
            "main()函数是你的程序开始的地方${RevisionBlocks.SEPARATOR}测试"
        val plan = ReviewOverlay.plan(old, revisionText, intArrayOf(10))!!
        // 审阅文字：原文照排；第一个块界原来就在那里（没动，不画 ¶），第二个 ¶ 是读者新加的。
        assertEquals(
            "fun 用于声明函数${RevisionBlocks.SEPARATOR}" +
                "main()函数是你的程序开始的地方${ChapterRevisionMap.BREAK_MARK_CHAR}" +
                "${RevisionBlocks.SEPARATOR}测试",
            plan.text,
        )
        val spans = listOf(
            InlineMarkup.StyleSpan(0, 3, InlineMarkup.StyleSig("code", mapOf("class" to "code"))),
            InlineMarkup.StyleSpan(11, 17, InlineMarkup.StyleSig("code", mapOf("class" to "code"))),
        )
        val reviewSpans = plan.stylesInText(revisionText, spans)
        assertEquals(listOf(0 until 3, 11 until 17), reviewSpans.map { it.start until it.endExclusive })

        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = leaves,
            leafPaths = listOf(intArrayOf(0, 0, 0), intArrayOf(0, 1, 0)),
            charStarts = longArrayOf(0, 10),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(
                    firstLeaf = 0,
                    lastLeaf = 1,
                    start = 0,
                    endExclusive = 28,
                    text = plan.text,
                    revisionId = 1L,
                    styles = reviewSpans,
                    kinds = listOf(BlockKind.Bullet, BlockKind.Bullet, BlockKind.Bullet),
                ),
            ),
        )

        val list = r.root.children[0]
        assertEquals(3, list.children.size)
        // 原来那两段里的 main() 仍然带着代码元素。
        val second = list.children[1].children[0]
        assertTrue(
            "main() 应留在 <code> 里：${second.children.map { it.tag }}",
            second.children.any { it.tag == "code" && textOf(it) == "main()" },
        )
        // 新加的一行落在叶级（<p> 的直接子节点），不继承段首 <code> 的样式。
        val third = list.children[2].children[0]
        assertEquals("测试", seg(third).text)
        assertEquals("#text", third.children.first().tag)
        assertEquals("测试", third.children.first().text)
        assertTrue(
            "「测试」不能长在 code 里：${third.children.map { it.tag }}",
            third.children.none { it.tag == "code" && textOf(it).isNotEmpty() },
        )
    }
}
