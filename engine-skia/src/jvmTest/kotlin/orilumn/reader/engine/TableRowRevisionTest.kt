package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.revision.ChapterRevisionReplay
import orilumn.reader.engine.revision.LeafTextEditor
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableRowInfo
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文修订：表格行叶里的改字必须落回**原来那一格**，不能写到 `td` 外面、也不能
 * 因为格界只是普通文本边界就把文字塞进前一格。
 *
 * 表格行的字符流是「各格文字按序拼接」；卡片提交时落库的目标格结构（[TableRowInfo]）
 * 是明确各格新长度的唯一依据 —— 边界插入靠它落到后一格，跨格改动靠它把新文字分回各格。
 */
class TableRowRevisionTest {

    private val layouter = BoxChapterLayouter()
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private class Fixture(
        val root: MarkupElement,
        val structure: ChapterStructureCache,
        val css: CssBundle,
    )

    private fun prepare(html: String, css: String = ""): Fixture {
        val root = HtmlTreeConverter().convert(html)!!
        val structure = ChapterStructureCache()
        val bundle = CssBundle(listOf(css))
        layouter.prepareLight(root, bundle, profile, 720, structure, 1280)
        return Fixture(root, structure, bundle)
    }

    private fun replay(
        f: Fixture,
        targets: Map<MarkupElement, TableRowInfo> = emptyMap(),
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
        tableOfRow = { targets[it] },
    )

    private fun rowLeaf(f: Fixture): MarkupElement {
        val row = f.structure.leaves.firstOrNull { it.tag == "tr" }
        assertTrue("前置：表格行应是排版叶", row != null)
        return row!!
    }

    private fun rowIndex(f: Fixture): Int {
        val i = f.structure.leaves.indexOfFirst { it.tag == "tr" }
        assertTrue("前置：表格行应是排版叶", i >= 0)
        return i
    }

    private fun texts(el: MarkupElement): String = buildString {
        fun walk(n: MarkupElement) {
            if (n.isText) append(n.text)
            for (c in n.children) walk(c)
        }
        walk(el)
    }

    private fun cellTexts(row: MarkupElement): List<String> =
        row.children.filter { it.tag == "td" || it.tag == "th" }.map(::texts)

    private fun cells(vararg lengths: Int): TableRowInfo {
        var at = 0
        return TableRowInfo(
            lengths.map { len ->
                TableCellInfo(at, at + len, header = false).also { at += len }
            },
        )
    }

    private val html =
        "<html><body><table><tr><td>ab</td><td>cd</td></tr></table><p>尾段。</p></body></html>"

    @Test
    fun `editing inside a cell keeps the cell boundaries`() {
        val f = prepare(html)
        val row = rowLeaf(f)
        val idx = rowIndex(f)
        val res = replay(f, mapOf(row to cells(2, 2))) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(1, 2, "X")) else emptyList()
        }

        assertFalse("表格行改字不该退回放弃结构", res.needsRecompute)
        assertFalse("单格改字不该退化", res.degradedLeaves > 0)
        val newRow = res.leaves.first { it.tag == "tr" }
        assertEquals(listOf("aX", "cd"), cellTexts(newRow))
        assertEquals("行文字长度不变，后续叶起点不动", listOf(0L, 4L), res.charStarts.toList())
    }

    @Test
    fun `insertion at the cell boundary goes to the next cell per the target`() {
        val f = prepare(html)
        val row = rowLeaf(f)
        val idx = rowIndex(f)
        // 旧行文字 "abcd"，在格界（偏移 2）插入一个 X，卡片显示成 `| ab | Xcd |`。
        val res = replay(f, mapOf(row to cells(2, 3))) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(2, 2, "X")) else emptyList()
        }

        val newRow = res.leaves.first { it.tag == "tr" }
        assertEquals("边界插入按目标格结构落到后一格", listOf("ab", "Xcd"), cellTexts(newRow))
        assertEquals(listOf(0L, 5L), res.charStarts.toList())
    }

    @Test
    fun `without a target a boundary insertion falls back to the previous cell`() {
        val f = prepare(html)
        val idx = rowIndex(f)
        val res = replay(f) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(2, 2, "X")) else emptyList()
        }

        val newRow = res.leaves.first { it.tag == "tr" }
        assertEquals("没有目标结构时按旧格界推断", listOf("abX", "cd"), cellTexts(newRow))
    }

    @Test
    fun `cross cell deletion distributes the new text by the target`() {
        val f = prepare(html)
        val row = rowLeaf(f)
        val idx = rowIndex(f)
        // 旧 "abcd"，删掉 b、c（跨两格），卡片显示 `| a | d |`。
        val res = replay(f, mapOf(row to cells(1, 1))) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(1, 3, "")) else emptyList()
        }

        assertFalse(res.needsRecompute)
        val newRow = res.leaves.first { it.tag == "tr" }
        assertEquals(listOf("a", "d"), cellTexts(newRow))
        assertEquals(listOf(0L, 2L), res.charStarts.toList())
    }

    @Test
    fun `untouched cell keeps its nodes`() {
        val f = prepare(html)
        val row = rowLeaf(f)
        val idx = rowIndex(f)
        val second = row.children.filter { it.tag == "td" }[1]
        val res = replay(f, mapOf(row to cells(2, 2))) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(0, 1, "Z")) else emptyList()
        }

        val newRow = res.leaves.first { it.tag == "tr" }
        assertSame("没改到的格子节点原样复用", second, newRow.children.filter { it.tag == "td" }[1])
    }

    @Test
    fun `inline structure inside a cell survives the edit`() {
        val f = prepare(
            "<html><body><table><tr><td><b>ab</b></td><td>cd</td></tr></table><p>尾段。</p></body></html>",
        )
        val row = rowLeaf(f)
        val idx = rowIndex(f)
        val res = replay(f, mapOf(row to cells(2, 2))) { i, _ ->
            if (i == idx) listOf(LeafTextEditor.Edit(1, 2, "X")) else emptyList()
        }

        val newRow = res.leaves.first { it.tag == "tr" }
        val first = newRow.children.filter { it.tag == "td" }[0]
        assertEquals("格内行内元素保留", "b", first.children.first { !it.isText }.tag)
        assertEquals(listOf("aX", "cd"), cellTexts(newRow))
    }
}
