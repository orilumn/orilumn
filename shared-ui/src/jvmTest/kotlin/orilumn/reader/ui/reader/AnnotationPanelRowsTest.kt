package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.epub.TocItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标注清单的纯逻辑（分组 / 章标题 / 键盘序）：不依赖 Compose 与数据库。 */
class AnnotationPanelRowsTest {

    private fun ann(chapter: Int, start: Int, id: Long = start.toLong()) = Annotation(
        id = id,
        bookId = 1,
        chapter = chapter,
        charStart = start,
        charEnd = start + 2,
        kind = AnnotationKind.Highlight,
    )

    @Test
    fun groupsByChapterAndFallsBackToChapterNumber() {
        val rows = buildAnnotationRows(
            listOf(ann(0, 5), ann(0, 9, id = 2), ann(2, 1, id = 3)),
            mapOf(0 to "第一章"),
        )

        assertEquals(5, rows.size)
        assertEquals("第一章", (rows[0] as AnnotationPanelRow.Header).title)
        assertEquals(0, (rows[0] as AnnotationPanelRow.Header).chapter)
        assertEquals(listOf(0, 1), listOf(rows[1], rows[2]).map { (it as AnnotationPanelRow.Item).seq })
        // 缺项目录回退「第 N 章」（章号 +1，与宿主 unitTitle 同口径）。
        assertEquals("第 3 章", (rows[3] as AnnotationPanelRow.Header).title)
        assertEquals(2, (rows[4] as AnnotationPanelRow.Item).seq)
    }

    @Test
    fun emptyInputHasNoRows() {
        assertTrue(buildAnnotationRows(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun chapterTitlesTakeFirstTocItemPerChapter() {
        val toc = listOf(
            TocItem("章一", 0, null, children = listOf(TocItem("节一", 0, "s1"))),
            TocItem("仅链接", null, "x"),
            TocItem("章二", 1, null),
        )
        val titles = chapterTitlesOf(toc)
        assertEquals("章一", titles[0])
        assertEquals("章二", titles[1])
        assertEquals(setOf(0, 1), titles.keys)
    }
}
