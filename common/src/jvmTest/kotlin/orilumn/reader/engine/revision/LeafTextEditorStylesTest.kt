package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.styledSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 带显式样式区间的叶级重建（`LeafTextEditor.Edit.styles`）：
 * 区间盖住的字落在对应元素里，没盖住的字落在叶级（不再被左边那个行内元素吞掉）。
 * 口径见 `docs/正文修改-行内标记语法.md` §4.3 / §4.4。
 */
class LeafTextEditorStylesTest {

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

    private fun rebuild(root: MarkupElement, vararg edits: LeafTextEditor.Edit): LeafTextEditor.Result {
        val leaf = withParents(root)
        return LeafTextEditor.rebuild(
            leaf = leaf,
            segments = segmentsOf(leaf),
            edits = edits.toList(),
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            rebuildSegments = { segmentsOf(it) },
        )
    }

    private fun textOf(node: MarkupElement) = segmentsOf(withParents(node)).text

    private fun sig(tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSig(tag, attrs.toMap())

    private fun span(s: Int, e: Int, tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSpan(s, e, sig(tag, *attrs))

    @Test
    fun styleOnlyChangeWrapsKeptText() {
        // 一个字都没改，只加了粗：整段按区间重建（老规则下这种提交什么都不会发生）。
        val p = el("p", txt("慢慢"))
        val r = rebuild(p, LeafTextEditor.Edit(0, 2, "慢慢", listOf(span(0, 2, "strong"))))

        assertFalse(r.degraded)
        assertEquals("慢慢", r.text)
        assertEquals(1, r.leaf.children.size)
        val strong = r.leaf.children[0]
        assertEquals("strong", strong.tag)
        assertEquals("慢慢", textOf(strong))
    }

    @Test
    fun replacingWholeElementKeepsElement() {
        // 选区整段在 <em> 里、读者保留了斜体标记：新字仍在该元素里（属性不动）。
        val p = el("p", txt("他"), el("em", txt("慢慢"), attrs = mapOf("class" to "a")), txt("走"))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(1, 3, "慢慢快快", listOf(span(0, 4, "em", "class" to "a"))),
        )

        assertFalse(r.degraded)
        assertEquals("他慢慢快快走", r.text)
        val em = r.leaf.children[1]
        assertEquals("em", em.tag)
        assertEquals("a", em.attrs["class"])
        assertEquals("慢慢快快", textOf(em))
    }

    @Test
    fun textOutsideMarkersIsNotSwallowed() {
        // 同一个选区、读者把标记只圈住前两个字：后面两个字必须落在叶级，不能进 <em>。
        val p = el("p", txt("他"), el("em", txt("慢慢")), txt("走"))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(1, 3, "慢慢快快", listOf(span(0, 2, "em"))),
        )

        assertFalse(r.degraded)
        assertEquals("他慢慢快快走", r.text)
        val em = r.leaf.children.firstOrNull { it.tag == "em" }
        assertTrue("新字被吞进行内元素了", em == null || textOf(em) == "慢慢")
    }

    @Test
    fun removingMarkersTakesTextOutOfElement() {
        val p = el("p", el("em", txt("慢慢")), txt("走"))
        val r = rebuild(p, LeafTextEditor.Edit(0, 2, "慢慢", emptyList()))

        assertFalse(r.degraded)
        assertEquals("慢慢走", r.text)
        val em = r.leaf.children.firstOrNull { it.tag == "em" }
        assertTrue("删掉标记后文字仍留在元素里", em == null || textOf(em).isEmpty())
    }

    @Test
    fun markedTextWithNewlineBecomesBrInsideElement() {
        val p = el("p", el("em", txt("a")))
        val r = rebuild(p, LeafTextEditor.Edit(0, 1, "a\nb", listOf(span(0, 3, "em"))))

        assertFalse(r.degraded)
        assertEquals("a\nb", r.text)
        val em = r.leaf.children.first { it.tag == "em" }
        assertEquals("br", em.children[1].tag)
    }

    @Test
    fun nestedMarkersNestElements() {        val p = el("p", txt("前后"))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(1, 1, "中", listOf(span(0, 1, "em"), span(0, 1, "strong"))),
        )

        assertFalse(r.degraded)
        assertEquals("前中后", r.text)
        val em = r.leaf.children.first { it.tag == "em" }
        val strong = em.children.first { it.tag == "strong" }
        assertEquals("中", textOf(strong))
    }

    @Test
    fun untouchedElementsStayAndNewTextLandsBesideThem() {
        // 改「走」这一处，前后各有一个元素：两个元素原样留着，新字落在叶级、不钻进旁边的元素。
        val p = el("p", txt("他"), el("em", txt("慢慢")), txt("走"), el("strong", txt("开")))
        val r = rebuild(p, LeafTextEditor.Edit(3, 4, "走啊", emptyList()))

        assertFalse(r.degraded)
        assertEquals("他慢慢走啊开", r.text)
        assertEquals("慢慢", textOf(r.leaf.children.first { it.tag == "em" }))
        assertEquals("开", textOf(r.leaf.children.first { it.tag == "strong" }))
        val leafText = r.leaf.children.filter { it.isText }.joinToString("") { it.text }
        assertTrue("新字钻进了旁边的元素", leafText.contains("啊"))
    }

    @Test
    fun emptiedInlineElementsArePruned() {
        // 真书场景：链接里套着代码，整段换字后旧元素只剩空壳。空 `code` 带内边距会渲染成一小块，
        // 所以剪掉（规格 §4.3「元素整个消失，不留空元素」）。
        val p = el(
            "p",
            txt("见 "),
            el(
                "a",
                el("code", txt("println()"), attrs = mapOf("class" to "code")),
                attrs = mapOf("href" to "https://a.b"),
            ),
            txt(" 的用法。"),
        )
        val text = "见 println() 的用法。"
        val r = rebuild(
            p,
            LeafTextEditor.Edit(0, text.length, "见 print() 的用法。", listOf(span(2, 7, "code"))),
        )

        assertFalse(r.degraded)
        assertEquals("见 print() 的用法。", r.text)
        assertTrue("空壳 <a> 还在", r.leaf.children.none { it.tag == "a" })
        // 留下的 code 是读者新写的那个（区间重建出来的），不是旧元素的空壳。
        assertEquals(1, r.leaf.children.count { it.tag == "code" })
        assertEquals("print", textOf(r.leaf.children.first { it.tag == "code" }))
        assertEquals("见 print() 的用法。", textOf(r.leaf))
    }

    @Test
    fun newCharsInsideAnElementStayInPlaceInsteadOfJumpingAfterIt() {
        // 审阅视图的格式痕迹（绿 `**`）：这一段在 `<code>` 里，痕迹自己不带 code 样式。
        // 早先这类「新字不在锚点字那个元素里」一律挂到整个元素之后，审阅正文就成了
        // 「getPriceInEuros()****」——字序被改掉，读者看不懂（用户复报）。
        val p = el("p", txt("调用"), el("code", txt("getPriceInEuros()")), txt("取得价格。"))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(
                5, 10, "**Price**",
                listOf(span(2, 7, "code"), span(2, 7, "strong")),
            ),
        )

        assertFalse(r.degraded)
        assertEquals("调用get**Price**InEuros()取得价格。", r.text)
    }

    @Test
    fun droppingTheElementInTheMiddleOfItKeepsTextOrder() {
        // 读者把中间那段字从 `<code>` 里拿出来、只留粗体：元素在这一点切开，
        // 字仍在原来的位置（早先整段挪到 `</code>` 之后，变成「getInEuros()Price」）。
        val p = el("p", txt("调用"), el("code", txt("getPriceInEuros()")), txt("取得价格。"))
        val r = rebuild(p, LeafTextEditor.Edit(5, 10, "Price", listOf(span(0, 5, "strong"))))

        assertFalse(r.degraded)
        assertEquals("调用getPriceInEuros()取得价格。", r.text)
        assertEquals("get", textOf(r.leaf.children[1]))
        assertEquals("Price", textOf(r.leaf.children[2]))
        assertEquals("InEuros()", textOf(r.leaf.children[3]))
    }

    @Test
    fun newCharsAtTheStartOfAnElementLandBeforeItsText() {
        // 元素开头那一段被换掉：新字排在元素剩下的字**前面**，不能挂到元素后面。
        val p = el("p", txt("调用"), el("code", txt("getPrice")), txt("好"))
        val r = rebuild(p, LeafTextEditor.Edit(2, 5, "get", listOf(span(0, 3, "strong"))))

        assertFalse(r.degraded)
        assertEquals("调用getPrice好", r.text)
        assertEquals("get", textOf(r.leaf.children[1]))
        assertEquals("Price", textOf(r.leaf.children[2]))
    }

    @Test
    fun newCharsAtTheStartOfAnElementLandBeforeItsKeptChars() {
        // 叶子一上来就是元素、被换掉的又是它开头那一截：新字仍落在元素剩下的字**前面**。
        // 这一条钉住「新字就地落进元素」的锚点，别让它跑到后面保留字之后（字序会变成
        // `InEuros()Price`）。
        val p = el("p", el("code", txt("PriceInEuros()")))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(0, 5, "Price", listOf(span(0, 5, "code"), span(0, 5, "strong"))),
        )

        assertFalse(r.degraded)
        assertEquals("PriceInEuros()", r.text)
    }

    @Test
    fun elementAtTheStartIsSplitAroundNewCharsThatLeaveIt() {
        // 同上，但读者把开头那截从元素里拿出来了：元素在开头切开，剩下的字仍排在后面。
        val p = el("p", el("code", txt("getPrice")))
        val r = rebuild(p, LeafTextEditor.Edit(0, 3, "get", listOf(span(0, 3, "strong"))))

        assertFalse(r.degraded)
        assertEquals("getPrice", r.text)
        assertEquals("get", textOf(r.leaf.children[0]))
        assertEquals("Price", textOf(r.leaf.children[1]))
    }

    @Test
    fun elementsThatWereEmptyInTheBookStay() {
        // 书里本来就写成空的元素（没有子节点）是原样，不剪。
        val p = el("p", txt("a"), el("span", attrs = mapOf("class" to "gap")), txt("b"))
        val r = rebuild(p, LeafTextEditor.Edit(0, 1, "A"))

        assertFalse(r.degraded)
        assertEquals("Ab", r.text)
        val span = r.leaf.children.first { it.tag == "span" }
        assertEquals("gap", span.attrs["class"])
    }
}
