package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.revision.ChapterRevisionReplay
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableReview
import orilumn.reader.engine.revision.TableReviewPlan
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

    private fun reviewRow(text: String, vararg lengths: Int): TableReviewPlan.RowInfo =
        TableReviewPlan.RowInfo(text, cells(*lengths).cells)

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
        rowSources: List<Int>? = null,
    ): ChapterRevisionReplay.TableEdit = ChapterRevisionReplay.TableEdit(
        firstLeaf = 0,
        lastLeaf = lastLeaf,
        start = 0,
        endExclusive = 8,
        text = text,
        rows = rows,
        revisionId = 1,
        rowSources = rowSources,
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
    fun `review replay keeps the deleted row in place`() {
        val f = prepare(html)
        val plan = TableReview.plan(
            oldRows = listOf(reviewRow("abcd", 2, 2), reviewRow("efgh", 2, 2)),
            newRows = listOf(reviewRow("abcd", 2, 2)),
            rowSources = listOf(0),
        )!!
        val res = replay(
            f,
            edit(listOf(cells(2, 2)), "abcd", rowSources = listOf(0)).copy(review = plan),
        )

        assertFalse(res.needsRecompute)
        assertEquals("旧行留着 + 新表 + 尾段", 3, res.leaves.size)
        assertEquals(listOf(0L, 4L, 8L), res.charStarts.toList())
        val t = table(res.root)!!
        assertEquals(listOf(listOf("ab", "cd"), listOf("ef", "gh")), rowTexts(t))
        assertEquals("abcdefgh", plan.overlay.text)
        assertEquals("abcdefgh", trs(t).joinToString("") { texts(it) })
    }

    @Test
    fun `review replay inserts new cell text after the old text`() {
        val f = prepare(html)
        val plan = TableReview.plan(
            oldRows = listOf(reviewRow("abcd", 2, 2), reviewRow("efgh", 2, 2)),
            newRows = listOf(reviewRow("aXcd", 2, 2), reviewRow("efgh", 2, 2)),
            rowSources = listOf(0, 1),
        )!!
        val res = replay(
            f,
            edit(
                listOf(cells(2, 2), cells(2, 2)),
                "aXcdefgh",
                rowSources = listOf(0, 1),
            ).copy(review = plan),
        )

        assertFalse(res.needsRecompute)
        assertEquals(listOf(0L, 5L, 9L), res.charStarts.toList())
        assertEquals(
            listOf(listOf("abX", "cd"), listOf("ef", "gh")),
            rowTexts(table(res.root)!!),
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

    private val styledHtml =
        "<html><body><table class=\"book\">" +
            "<tr class=\"odd\"><td class=\"c1\"><span class=\"em\">ab</span></td><td class=\"c2\">cd</td></tr>" +
            "<tr class=\"even\"><td class=\"c3\"><b>ef</b></td><td class=\"c4\">gh</td></tr>" +
            "<tr class=\"odd\"><td class=\"c5\"><i>ij</i></td><td class=\"c6\">kl</td></tr>" +
            "</table><p>尾段。</p></body></html>"

    private fun findByTag(el: MarkupElement, tag: String): MarkupElement? = run {
        fun find(n: MarkupElement): MarkupElement? {
            if (n.tag == tag) return n
            for (c in n.children) find(c)?.let { return it }
            return null
        }
        find(el)
    }

    @Test
    fun `deleting a row keeps the other rows' attributes and inline markup`() {
        // 真机复报「删掉一行，表格很多样式都丢了」：没选中的行必须原样留在树上
        // （行 / 格属性、格内行内元素都不动），只有被删的那一行消失。
        val f = prepare(styledHtml)
        val res = replay(
            f,
            edit(
                rows = listOf(cells(2, 2), cells(2, 2)),
                text = "abcdijkl",
                lastLeaf = 2,
                rowSources = listOf(0, 2),
            ),
        )

        assertFalse(res.needsRecompute)
        val t = table(res.root)!!
        assertEquals("书自己的 table 属性不能丢", "book", t.attrs["class"])
        assertEquals(listOf(listOf("ab", "cd"), listOf("ij", "kl")), rowTexts(t))
        val rows = trs(t)
        assertEquals("两行都还是原来的行节点", listOf("odd", "odd"), rows.map { it.attrs["class"] })
        assertEquals("第一个格的 class 不能丢", "c1", rows[0].children[0].attrs["class"])
        assertEquals(
            "格里的行内元素不能丢",
            "em",
            findByTag(rows[0].children[0], "span")?.attrs?.get("class"),
        )
        assertEquals("最后一行用的是它自己的格，不是前一行的", "c5", rows[1].children[0].attrs["class"])
        assertEquals("i", findByTag(rows[1].children[0], "i")?.tag)
    }

    @Test
    fun `deleting the first row does not shift the next row's attributes`() {
        // 行属性按**它自己**的来源行取：删掉第一行后，原来的第二行不能套上第一行的样式。
        val f = prepare(styledHtml)
        val res = replay(
            f,
            edit(
                rows = listOf(cells(2, 2), cells(2, 2)),
                text = "efghijkl",
                lastLeaf = 2,
                rowSources = listOf(1, 2),
            ),
        )

        val rows = trs(table(res.root)!!)
        assertEquals(listOf("even", "odd"), rows.map { it.attrs["class"] })
        assertEquals(listOf("c3", "c5"), rows.map { it.children[0].attrs["class"] })
    }

    @Test
    fun `editing a cell keeps its attributes and inline elements`() {
        val oneRow =
            "<html><body><table><tr class=\"r\"><td class=\"c1\" style=\"background:#eee\">" +
                "<span class=\"em\">ab</span></td><td class=\"c2\">cd</td></tr></table><p>尾段。</p></body></html>"
        val f = prepare(oneRow)
        val res = replay(
            f,
            edit(
                rows = listOf(cells(3, 2)),
                text = "aXbcd",
                lastLeaf = 0,
                rowSources = listOf(0),
            ),
        )

        val row = trs(table(res.root)!!).single()
        assertEquals("行属性不能丢", "r", row.attrs["class"])
        val cell = row.children.first { it.tag == "td" }
        assertEquals("格属性不能丢", "c1", cell.attrs["class"])
        assertEquals("格内行内样式不能丢", "background:#eee", cell.attrs["style"])
        val span = findByTag(cell, "span")
        assertEquals("em", span?.attrs?.get("class"))
        assertEquals("新字仍落在原来的元素里", "aXb", texts(cell))
        assertEquals("没改的格原样留着", "c2", row.children[1].attrs["class"])
    }

    @Test
    fun `deleting a body row keeps thead and tbody`() {
        val grouped =
            "<html><body><table><thead><tr><th class=\"h\">ab</th><th class=\"h\">cd</th></tr></thead>" +
                "<tbody><tr><td>ef</td><td>gh</td></tr><tr><td class=\"last\">ij</td><td>kl</td></tr>" +
                "</tbody></table><p>尾段。</p></body></html>"
        val f = prepare(grouped)
        val res = replay(
            f,
            edit(
                rows = listOf(cells(2, 2), cells(2, 2)),
                text = "abcdefgh",
                lastLeaf = 2,
                rowSources = listOf(0, 1),
            ),
        )

        val t = table(res.root)!!
        val thead = t.children.firstOrNull { it.tag == "thead" }
        val tbody = t.children.firstOrNull { it.tag == "tbody" }
        assertTrue("thead 必须还在", thead != null)
        assertTrue("tbody 必须还在", tbody != null)
        assertEquals("表头行仍留在 thead 里", 1, trs(thead!!).size)
        assertEquals("表体行仍留在 tbody 里", 1, trs(tbody!!).size)
        assertEquals("h", trs(thead).single().children[0].attrs["class"])
    }
}
