package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.styledSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叶级重建单测：逐段保留行内元素样式、截一半、跨节点、`<br>`、纯插入、整段换新字。
 * 只跑纯逻辑（不需要引擎 / Android）。
 */
class LeafTextEditorTest {

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

    private fun textOf(root: MarkupElement) = segmentsOf(withParents(root)).text

    private fun child(root: MarkupElement, index: Int) = root.children[index]

    @Test
    fun keepsEachInlineElementOwnClassAndText() {
        // <p>他<em class="a">慢慢</em><strong class="b">地</strong>走</p>
        // 选中第二个「慢」+「地」→「快快地」：公共字只有「地」，逐字归属。
        val em = el("em", txt("慢慢"), attrs = mapOf("class" to "a"))
        val strong = el("strong", txt("地"), attrs = mapOf("class" to "b"))
        val p = el("p", txt("他"), em, strong, txt("走"))

        val r = rebuild(p, LeafTextEditor.Edit(2, 4, "快快地"))

        assertFalse("不应退化：${r.expected} vs ${r.text}", r.degraded)
        assertEquals("他慢快快地走", r.text)
        assertEquals("慢快快", textOf(child(r.leaf, 1)))
        assertEquals("地", textOf(child(r.leaf, 2)))
        assertEquals(mapOf("class" to "a"), child(r.leaf, 1).attrs)
        assertEquals(mapOf("class" to "b"), child(r.leaf, 2).attrs)
    }

    @Test
    fun inlineElementCutInHalfKeepsElement() {
        // <p><em>abc</em>def</p> 改中间一个字符：<em> 仍是 <em>，只是文字变了。
        val p = el("p", el("em", txt("abc")), txt("def"))
        val r = rebuild(p, LeafTextEditor.Edit(1, 2, "X"))

        assertFalse(r.degraded)
        assertEquals("aXcdef", r.text)
        assertEquals("em", child(r.leaf, 0).tag)
        assertEquals("aXc", textOf(child(r.leaf, 0)))
        assertEquals("def", textOf(child(r.leaf, 1)))
    }

    @Test
    fun pureInsertAtBoundaryStaysInPreviousElement() {
        // <p><em>ab</em><strong>cd</strong></p> 在 ab 与 cd 之间插入 X → 续写手感：留在 <em>。
        val p = el("p", el("em", txt("ab")), el("strong", txt("cd")))
        val r = rebuild(p, LeafTextEditor.Edit(2, 2, "X"))

        assertFalse(r.degraded)
        assertEquals("abXcd", r.text)
        assertEquals("abX", textOf(child(r.leaf, 0)))
        assertEquals("cd", textOf(child(r.leaf, 1)))
    }

    @Test
    fun deleteAcrossElementsKeepsBothElements() {
        // <p><em>ab</em><strong>cd</strong></p> 删「bc」：<em> 留 a、<strong> 留 d，元素都不剪枝。
        val p = el("p", el("em", txt("ab")), el("strong", txt("cd")))
        val r = rebuild(p, LeafTextEditor.Edit(1, 3, ""))

        assertFalse(r.degraded)
        assertEquals("ad", r.text)
        assertEquals("em", child(r.leaf, 0).tag)
        assertEquals("a", textOf(child(r.leaf, 0)))
        assertEquals("strong", child(r.leaf, 1).tag)
        assertEquals("d", textOf(child(r.leaf, 1)))
    }

    @Test
    fun insertedNewlineBecomesBr() {
        val p = el("p", txt("abc"))
        val r = rebuild(p, LeafTextEditor.Edit(1, 1, "\n"))

        assertFalse(r.degraded)
        assertEquals("a\nbc", r.text)
        assertEquals(3, r.leaf.children.size)
        assertEquals("br", r.leaf.children[1].tag)
        assertEquals("bc", textOf(r.leaf.children[2]))
    }

    @Test
    fun deletingNewlineRemovesBr() {
        val p = el("p", txt("a"), MarkupElement("br"), txt("b"))
        val r = rebuild(p, LeafTextEditor.Edit(1, 2, ""))

        assertFalse(r.degraded)
        assertEquals("ab", r.text)
        assertEquals(2, r.leaf.children.size)
        assertTrue(r.leaf.children.none { it.tag == "br" })
    }

    @Test
    fun wholeRewriteFallsBackToStartElement() {
        // 没有任何公共字：新字全部落在中段起点那个元素（<em>），后面的元素留空但保留。
        val p = el("p", el("em", txt("甲")), el("strong", txt("乙")))
        val r = rebuild(p, LeafTextEditor.Edit(0, 2, "XY"))

        assertFalse(r.degraded)
        assertEquals("XY", r.text)
        assertEquals("XY", textOf(child(r.leaf, 0)))
        assertEquals("", textOf(child(r.leaf, 1)))
        assertEquals("strong", child(r.leaf, 1).tag)
    }

    @Test
    fun repeatedCharactersAreStableAcrossRuns() {
        val build = {
            val p = el("p", el("em", txt("哈哈哈")), el("strong", txt("哈哈")))
            rebuild(p, LeafTextEditor.Edit(1, 4, "呵呵"))
        }
        val first = build()
        repeat(10) {
            val again = build()
            assertEquals(first.text, again.text)
            assertEquals(first.degraded, again.degraded)
            assertEquals(textOf(first.leaf.children[0]), textOf(again.leaf.children[0]))
        }
    }

    @Test
    fun untouchedNodeKeepsRawWhitespace() {
        // 只改第一个字：<em> 里的原始空白不动，边界空格合并结果仍是 "x b"。
        val p = el("p", txt("a "), el("em", txt(" b")))
        val r = rebuild(p, LeafTextEditor.Edit(0, 1, "x"))

        assertFalse(r.degraded)
        assertEquals("x b", r.text)
        assertEquals(" b", r.leaf.children[1].children[0].text)
    }

    @Test
    fun deletingRenderedBoundarySpaceIsReportedAsDegraded() {
        // 已知边界：渲染上的空格由前一个节点提供、后一个节点自带被折叠掉的前导空格；
        // 删掉渲染空格后，后一个节点的原始空格会露出来。方案接受「以实测为准 + 记日志」。
        val p = el("p", txt("a "), el("em", txt(" b")))
        val r = rebuild(p, LeafTextEditor.Edit(1, 2, ""))

        assertEquals("ab", r.expected)
        assertTrue("这类空白边界必须显式报退化（调用方记日志）", r.degraded)
    }

    @Test
    fun parentsAreRelinked() {
        val p = el("p", el("em", txt("abc")), txt("def"))
        val r = rebuild(p, LeafTextEditor.Edit(0, 1, "Z"))

        val em = child(r.leaf, 0)
        assertNotNull(em.parent)
        assertEquals(r.leaf, em.parent)
        assertEquals(em, em.children[0].parent)
        assertNull(r.leaf.parent)
    }

    @Test
    fun preNormalizeCollapsesAndTrimsBoundaries() {
        assertEquals("a b", LeafTextEditor.preNormalize("a  b", WhiteSpace.NORMAL, 'x', 'y'))
        assertEquals("a", LeafTextEditor.preNormalize(" a", WhiteSpace.NORMAL, null, 'y'))
        assertEquals("a", LeafTextEditor.preNormalize("a ", WhiteSpace.NORMAL, 'x', null))
        assertEquals(" a", LeafTextEditor.preNormalize(" a", WhiteSpace.NORMAL, 'x', 'y'))
        assertEquals("a", LeafTextEditor.preNormalize("a ", WhiteSpace.NORMAL, 'x', '\n'))
        // PRE 原样（只展开制表符）。
        assertEquals("a  b", LeafTextEditor.preNormalize("a  b", WhiteSpace.PRE, null, null))
    }

    @Test
    fun multipleNonOverlappingEditsInOneLeaf() {
        val p = el("p", el("em", txt("abcd")))
        val r = rebuild(
            p,
            LeafTextEditor.Edit(0, 1, "X"),
            LeafTextEditor.Edit(3, 4, "Y"),
        )
        assertFalse(r.degraded)
        assertEquals("XbcY", r.text)
        assertEquals("XbcY", textOf(child(r.leaf, 0)))
    }
}
