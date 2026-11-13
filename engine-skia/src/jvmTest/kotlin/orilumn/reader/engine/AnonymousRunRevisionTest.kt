package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.laying.normalizeAnonymousRun
import orilumn.reader.engine.revision.ChapterRevisionReplay
import orilumn.reader.engine.revision.LeafTextEditor
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文修订：标题里的 stray 文字（`flowChildren` 合成的匿名 run 叶）也要能改。
 *
 * 真书 `<h1><span class="num" style="display:block">第三章</span>风起云涌</h1>` 会拆成
 * 一个真块叶（span）+ 一个合成 `#text` 匿名叶（风起云涌）。修订重放此前遇到匿名叶直接
 * 放弃（`needsRecompute`，没人接），于是非审阅模式删字毫无反应（审阅视图只是画出红删除线，
 * 看起来像生效）。本组用例锁住「匿名 run 改字真的落回章树、字符起点跟着平移」。
 */
class AnonymousRunRevisionTest {

    private val layouter = BoxChapterLayouter()
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private class Fixture(
        val root: MarkupElement,
        val structure: ChapterStructureCache,
        val css: CssBundle,
    )

    private fun prepare(html: String, css: String): Fixture {
        val root = HtmlTreeConverter().convert(html)!!
        val structure = ChapterStructureCache()
        val bundle = CssBundle(listOf(css))
        layouter.prepareLight(root, bundle, profile, 720, structure, 1280)
        return Fixture(root, structure, bundle)
    }

    private fun replay(
        f: Fixture,
        editsForLeaf: (leafIndex: Int, leafStart: Int) -> List<LeafTextEditor.Edit>,
    ): ChapterRevisionReplay.Result = layouter.replayRevisions(
        markup = f.root,
        cssBundle = f.css,
        profile = profile,
        contentW = 720,
        contentH = 1280,
        structure = f.structure,
        leafPaths = f.structure.leaves.map { MarkupPaths.nodePath(f.root, it) },
        editsForLeaf = editsForLeaf,
    )

    private fun treeTexts(el: MarkupElement, out: MutableList<String> = ArrayList()): List<String> {
        if (el.isText) out += el.text
        for (c in el.children) treeTexts(c, out)
        return out
    }

    private fun anonymousLeaf(f: Fixture): Int {
        val i = f.structure.leaves.indexOfFirst { it.tag == "#text" }
        assertTrue("前置：标题后半段应是合成匿名叶", i >= 0)
        return i
    }

    /** 叶的排版文本（匿名 run 走整段归一，与塑形/字符投影同一串）。 */
    private fun runText(f: Fixture, leaf: Int): String =
        normalizeAnonymousRun(f.structure.leaves[leaf].text, WhiteSpace.NORMAL)

    @Test
    fun `deletes a character in the heading stray run`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风起云涌</h2>" +
                "<p>正文第一段。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(0, 1, "")) else emptyList()
        }

        assertFalse("匿名 run 改字不该再退回放弃结构", res.needsRecompute)
        assertEquals("字符起点按实测长度平移", listOf(0L, 3L, 6L), res.charStarts.toList())
        assertEquals(
            "重放后的叶表里是改后的 run",
            listOf("起云涌"),
            res.leaves.filter { it.tag == "#text" && it.parent?.tag == "h2" }.map { it.text },
        )
        assertTrue(
            "章节树里真的少了这一个字",
            treeTexts(res.root).contains("起云涌") && !treeTexts(res.root).contains("风起云涌"),
        )
        assertTrue("重建后的合成叶要重新挂回属主", res.leaves.first { it.tag == "#text" }.parent === res.root.children[0])
    }

    @Test
    fun `deleting the whole run drops the leaf`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风起云涌</h2>" +
                "<p>正文第一段。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(0, 4, "")) else emptyList()
        }

        assertFalse(res.needsRecompute)
        assertEquals("删空的 run 不再占叶", 2, res.leaves.size)
        assertEquals(listOf(0L, 3L), res.charStarts.toList())
        assertEquals(listOf("第三章", "正文第一段。"), treeTexts(res.root))
    }

    @Test
    fun `run spanning inline children keeps their inline structure`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span><em>风</em><strong>起云涌</strong></h2>" +
                "<p>正文。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(0, 1, "")) else emptyList()
        }

        assertFalse(res.needsRecompute)
        val texts = treeTexts(res.root)
        assertTrue(texts.contains("起云涌"))
        assertFalse(texts.contains("风"))
        val h2 = res.root.children[0]
        // 字被删光的 `em` 整个走掉（本就空的元素才保留）；剩下 `<strong>` 的结构原样不动。
        assertEquals("行内元素保留（删空的 em 整个走掉）", listOf("span", "strong"), h2.children.map { it.tag })
    }

    @Test
    fun `anon run and body paragraph edit in one replay`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风起云涌</h2>" +
                "<p>正文第一段。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val para = f.structure.leaves.indexOfFirst { it.tag == "p" }
        val res = replay(f) { i, _ ->
            when (i) {
                anon -> listOf(LeafTextEditor.Edit(0, 1, ""))
                para -> listOf(LeafTextEditor.Edit(0, 1, ""))
                else -> emptyList()
            }
        }

        assertFalse(res.needsRecompute)
        assertEquals(listOf(0L, 3L, 6L), res.charStarts.toList())
        val texts = treeTexts(res.root)
        assertTrue(texts.contains("起云涌"))
        assertTrue(texts.contains("文第一段。"))
    }

    @Test
    fun `run with a br is editable`() {
        // 匿名 run 里的 `<br>` 与源码换行在合成原文里曾经分不开（都写成 `\n`），整段归一
        // 把 `<br>` 也折成空格 ⇒ 逐节点段文本与排版文本对不上 ⇒ 重建放弃（needsRecompute），
        // 提交显示成功、正文一个字都没变。现在 `<br>` 走自己的占位符，两种口径逐字相同。
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风<br/>起云涌</h2>" +
                "<p>正文。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        assertEquals("风\n起云涌", runText(f, anon))
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(2, 3, "")) else emptyList()
        }

        assertFalse("含 <br> 的匿名 run 也要能改", res.needsRecompute)
        val rebuilt = res.leaves.firstOrNull { it.tag == "#text" } ?: error("run 还在")
        assertEquals("风\n云涌", normalizeAnonymousRun(rebuilt.text, WhiteSpace.NORMAL))
        assertEquals("换行还在（br 没被吃掉）", 1, res.root.children[0].children.count { it.tag == "br" })
        assertFalse(treeTexts(res.root).contains("风起云涌"))
    }

    @Test
    fun `deleting the newline in a run removes its br`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风<br/>起云涌</h2>" +
                "<p>正文。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(1, 2, "")) else emptyList()
        }

        assertFalse(res.needsRecompute)
        val rebuilt = res.leaves.firstOrNull { it.tag == "#text" } ?: error("run 还在")
        assertEquals("风起云涌", normalizeAnonymousRun(rebuilt.text, WhiteSpace.NORMAL))
        assertEquals("换行没了，br 跟着走", 0, res.root.children[0].children.count { it.tag == "br" })
    }

    @Test
    fun `typing a newline into a run inserts a br`() {
        val f = prepare(
            "<html><body><h2><span class=\"num\">第三章</span>风起云涌</h2>" +
                "<p>正文。</p></body></html>",
            ".num{display:block}",
        )
        val anon = anonymousLeaf(f)
        val res = replay(f) { i, _ ->
            if (i == anon) listOf(LeafTextEditor.Edit(1, 1, "\n")) else emptyList()
        }

        assertFalse(res.needsRecompute)
        val rebuilt = res.leaves.firstOrNull { it.tag == "#text" } ?: error("run 还在")
        assertEquals("风\n起云涌", normalizeAnonymousRun(rebuilt.text, WhiteSpace.NORMAL))
        assertEquals("新敲的换行落成真 <br>", 1, res.root.children[0].children.count { it.tag == "br" })
    }
}
