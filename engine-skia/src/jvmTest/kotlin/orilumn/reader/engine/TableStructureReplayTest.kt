package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.revision.ChapterRevisionReplay
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableRowInfo
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整表结构修订的重放：删行 / 加行 / 删整表都按落库的格结构整表重建，
 * 旧的整行叶撤掉，后面的叶起点跟着平移。
 */
class TableStructureReplayTest {

    private val layouter = BoxChapterLayouter()
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private class Fixture(
        val root: MarkupElement,
        val structure: ChapterStructureCache,
        val css: CssBundle,
    )

    private fun prepare(html: String): Fixture {
        val root = HtmlTreeConverter().convert(html)!!
        val structure = ChapterStructureCache()
        val bundle = CssBundle(emptyList())
        layouter.prepareLight(root, bundle, profile, 720, structure, 1280)
        return Fixture(root, structure, bundle)
    }

    private fun replay(f: Fixture, edit: ChapterRevisionReplay.TableEdit?): ChapterRevisionReplay.Result =
        layouter.replayRevisions(
            markup = f.root,
            cssBundle = f.css,
            profile = profile,
            contentW = 720,
            contentH = 1280,
            structure = f.structure,
            leafPaths = f.structure.leaves.map { MarkupPaths.nodePath(f.root, it) },
            editsForLeaf = { _, _ -> emptyList() },
            tableEdits = listOfNotNull(edit),
        )

    private fun cells(vararg lengths: Int): TableRowInfo {
        var at = 0
        return TableRowInfo(
            lengths.map { len ->
                TableCellInfo(at, at + len, header = false).also { at += len }
            },
        )
    }

    private fun texts(el: MarkupElement): String = buildString {
        fun walk(n: MarkupElement) {
            if (n.isText) append(n.text)
            for (c in n.children) walk(c)
        }
        walk(el)
    }

    private fun table(root: MarkupElement): MarkupElement? = run {
        fun find(n: MarkupElement): MarkupElement? {
            if (n.tag == "table") return n
            for (c in n.children) find(c)?.let { return it }
            return null
        }
        find(root)
    }

    private fun trs(el: MarkupElement): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        fun walk(n: MarkupElement) {
            if (n.tag == "tr") {
                out += n
                return
            }
            for (c in n.children) walk(c)
        }
        walk(el)
        return out
    }

    private fun rowTexts(el: MarkupElement): List<List<String>> =
        trs(el).map { row ->
            row.children.filter { it.tag == "td" || it.tag == "th" }.map(::texts)
        }

    private val html =
        "<html><body><table>" +
            "<tr><td>ab</td><td>cd</td></tr>" +
            "<tr><td>ef</td><td>gh</td></tr>" +
            "</table><p>尾段。</p></body></html>"

    private fun edit(
        rows: List<TableRowInfo>,
        text: String,
        lastLeaf: Int = 1,
    ): ChapterRevisionReplay.TableEdit = ChapterRevisionReplay.TableEdit(
        firstLeaf = 0,
        lastLeaf = lastLeaf,
        start = 0,
        endExclusive = 8,
        text = text,
        rows = rows,
        revisionId = 1,
    )

    @Test
    fun `deleting a row removes the tr and shifts the next leaf`() {
        val f = prepare(html)
        assertEquals(listOf(0L, 4L, 8L), f.structure.globalCharStarts.toList())
        val res = replay(f, edit(listOf(cells(2, 2)), "abcd"))

        assertFalse("整表重建不该退回重算", res.needsRecompute)
        assertEquals("剩一行 + 尾段", 2, res.leaves.size)
        assertEquals(listOf(0L, 4L), res.charStarts.toList())
        val t = table(res.root)
        assertTrue(t != null)
        assertEquals(listOf(listOf("ab", "cd")), rowTexts(t!!))
        assertEquals("尾段。", texts(res.leaves.last()))
    }

    @Test
    fun `adding a row rebuilds the table in place`() {
        val f = prepare(html)
        val res = replay(
            f,
            edit(listOf(cells(2, 2), cells(2, 2), cells(2, 2)), "abcdefghijkl"),
        )

        assertFalse(res.needsRecompute)
        assertEquals("三行 + 尾段", 4, res.leaves.size)
        assertEquals(listOf(0L, 4L, 8L, 12L), res.charStarts.toList())
        val t = table(res.root)!!
        assertEquals(
            listOf(listOf("ab", "cd"), listOf("ef", "gh"), listOf("ij", "kl")),
            rowTexts(t),
        )
    }

    @Test
    fun `deleting the whole table removes the table element`() {
        val f = prepare(html)
        val res = replay(f, edit(emptyList(), ""))

        assertFalse(res.needsRecompute)
        assertEquals("只剩尾段", 1, res.leaves.size)
        assertEquals(listOf(0L), res.charStarts.toList())
        assertNull(table(res.root))
    }

    @Test
    fun `merged cells keep the span attributes`() {
        val f = prepare(html)
        val row = TableRowInfo(
            listOf(
                TableCellInfo(0, 4, header = false, rowspan = 1, colspan = 2),
            ),
        )
        val res = replay(f, edit(listOf(row), "abcd"))

        val t = table(res.root)!!
        val kept = trs(t).single()
        val cell = kept.children.first { it.tag == "td" }
        assertEquals("2", cell.attrs["colspan"])
        assertEquals("abcd", texts(cell))
    }
}
