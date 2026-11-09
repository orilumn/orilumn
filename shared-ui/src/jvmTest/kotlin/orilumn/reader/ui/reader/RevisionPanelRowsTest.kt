package orilumn.reader.ui.reader

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑清单的纯逻辑（摘录折叠、单行审阅摘要取法）：不依赖 Compose 与数据库。 */
class RevisionPanelRowsTest {

    private fun rev(
        start: Int,
        end: Int,
        text: String,
        originalText: String = "",
        editedText: String = "",
    ): TextRevision = TextRevision(
        bookId = 1,
        chapter = 1,
        charStart = start,
        charEnd = end,
        text = text,
        originalText = originalText,
        editedText = editedText,
    )

    private fun concat(spans: List<ReviewSpan>): String = spans.joinToString("") { it.text }

    private fun kinds(spans: List<ReviewSpan>): List<ReviewSpanKind> = spans.map { it.kind }

    @Test
    fun excerptFoldsNewlinesAndWhitespace() {
        assertEquals("改后的 一段 文字", revisionExcerpt("改后的\n一段\t文字 "))
        // 纯删除 / 纯插入的那一侧是空串。
        assertEquals("", revisionExcerpt(""))
        assertEquals("", revisionExcerpt(" \n\t "))
    }

    @Test
    fun excerptFoldsParagraphMarkers() {
        // 老修订的替换文字里带段界痕迹，清单不显示这个不占字的控制符。
        assertEquals("上一段 下一段", revisionExcerpt("上一段\uE000下一段"))
    }

    @Test
    fun newRevisionShowsOnlyTheChangedCharsInReviewStyle() {
        // 选中「稳健」改成「稳健地」：没动的字照常，只有新加的「地」是新增。
        val spans = revisionReviewSpans(
            rev(start = 5, end = 5, text = "地", originalText = "稳健", editedText = "稳健地"),
        )
        assertEquals("稳健地", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Kept, ReviewSpanKind.Inserted), kinds(spans))
    }

    @Test
    fun inPlaceReplacementMarksBothSides() {
        // 中间换一个字：删掉的字标删除，新写的字紧接着标新增，前后的字照常。
        val spans = revisionReviewSpans(
            rev(start = 4, end = 4, text = "X", originalText = "ABC", editedText = "AXC"),
        )
        assertEquals("ABXC", concat(spans))
        assertEquals(
            listOf(
                ReviewSpanKind.Kept,
                ReviewSpanKind.Deleted,
                ReviewSpanKind.Inserted,
                ReviewSpanKind.Kept,
            ),
            kinds(spans),
        )
    }

    @Test
    fun newDeletionIsAllDeleted() {
        val spans = revisionReviewSpans(
            rev(start = 2, end = 5, text = "", originalText = "删掉的三个字", editedText = ""),
        )
        assertEquals("删掉的三个字", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Deleted), kinds(spans))
    }

    @Test
    fun pureInsertionIsOnlyNewText() {
        // 新修订没有选区：只显示新写进去的字，没有原文行。
        val spans = revisionReviewSpans(
            rev(start = 3, end = 3, text = "地", originalText = "", editedText = "地"),
        )
        assertEquals("地", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Inserted), kinds(spans))
    }

    @Test
    fun legacyReplacementFallsBackToItsReplacementText() {
        // 加快照之前写的老修订：没有原文快照，用占位句 + 当时的替换文字。
        val spans = revisionReviewSpans(rev(start = 1, end = 2, text = "X"))
        assertEquals("（原文未记录）X", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Deleted, ReviewSpanKind.Inserted), kinds(spans))
    }

    @Test
    fun legacyInsertionHasNoPlaceholder() {
        // 老修订的纯插入本来就没有原文，不必补「（原文未记录）」。
        val spans = revisionReviewSpans(rev(start = 3, end = 3, text = "X"))
        assertEquals("X", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Inserted), kinds(spans))
    }

    @Test
    fun newDeletionInsideOldRevisionDoesNotFallBackToLeftoverText() {
        // 删掉旧改文的一部分：改文为空就是纯删除，不能把最小修订里保留下来的旧字当新文字。
        val spans = revisionReviewSpans(
            rev(start = 2, end = 3, text = "Y", originalText = "X", editedText = ""),
        )
        assertEquals("X", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Deleted), kinds(spans))
    }

    @Test
    fun whitespaceOnlySelectionIsNotReportedAsMissing() {
        // 新修订选中的只有空白：有快照，不能说成「原文未记录」。
        val spans = revisionReviewSpans(
            rev(start = 1, end = 3, text = "X", originalText = " \n ", editedText = "X"),
        )
        assertEquals("（空白）X", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Kept, ReviewSpanKind.Inserted), kinds(spans))
    }

    @Test
    fun legacyDeletionKeepsPlaceholder() {
        val spans = revisionReviewSpans(rev(start = 3, end = 4, text = ""))
        assertEquals("（原文未记录）", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Deleted), kinds(spans))
    }

    @Test
    fun longRevisionIsTruncated() {
        // 两行排不下的长文不再往控件里塞：摘要长度有上限。
        val spans = revisionReviewSpans(
            rev(start = 0, end = 0, text = "", originalText = "", editedText = "长".repeat(500)),
        )
        assertTrue(concat(spans).length <= 200)
    }
}
