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
}
