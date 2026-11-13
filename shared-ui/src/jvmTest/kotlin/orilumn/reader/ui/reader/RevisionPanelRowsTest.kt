package orilumn.reader.ui.reader

import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.revision.ReviewOverlay
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
    fun formatOnlyRevisionUsesTheReviewSpaceExcerpt() {
        // 用户的原例：只给「Price」加粗，字一个没改 —— 快照两端相同，按字符差异一片空白，
        // 行里只剩普通文字；宿主给得出审阅空间那一段时按它排，绿 `**` 与审阅视图一致。
        val spans = revisionReviewSpans(
            rev(start = 7, end = 12, text = "Price", originalText = "Price", editedText = "Price"),
            excerpt("**Price**", inserted = listOf(0 until 2, 7 until 9)),
        )
        assertEquals("**Price**", concat(spans))
        assertEquals(
            listOf(ReviewSpanKind.Inserted, ReviewSpanKind.Kept, ReviewSpanKind.Inserted),
            kinds(spans),
        )
    }

    @Test
    fun reviewSpaceExcerptKeepsRedAndGreenTheSameWay() {
        // 改了字的修订走同一份数据：红字（删掉 / 换掉的）与绿字（新写的）位置直接照抄。
        val spans = revisionReviewSpans(
            rev(start = 1, end = 3, text = "快快", originalText = "他慢慢地走", editedText = "他快快地走"),
            excerpt("他慢慢快快地走", deleted = listOf(1 until 3), inserted = listOf(3 until 5)),
        )
        assertEquals("他慢慢快快地走", concat(spans))
        assertEquals(
            listOf(ReviewSpanKind.Kept, ReviewSpanKind.Deleted, ReviewSpanKind.Inserted, ReviewSpanKind.Kept),
            kinds(spans),
        )
    }

    @Test
    fun reviewSpaceExcerptFoldsParagraphMarksAndWhitespace() {
        // 段界不占字符、换行折成空格（清单是一条流式文字），颜色跟着字走，¶ 照排。
        val spans = revisionReviewSpans(
            rev(start = 0, end = 2, text = "甲\uE000乙", originalText = "甲乙", editedText = "甲\n乙"),
            excerpt("甲\uE000乙\n¶丙"),
        )
        assertEquals("甲 乙 ¶丙", concat(spans))
        assertEquals(listOf(ReviewSpanKind.Kept), kinds(spans))
    }

    @Test
    fun emptyReviewSpaceExcerptFallsBackToTheSnapshot() {
        // 宿主给了个空段（对不上 / 全是空白）：仍按快照对齐，不把行留空。
        val spans = revisionReviewSpans(
            rev(start = 2, end = 5, text = "", originalText = "删掉的三个字", editedText = ""),
            excerpt(""),
        )
        assertEquals("删掉的三个字", concat(spans))
    }

    private fun excerpt(
        text: String,
        deleted: List<IntRange> = emptyList(),
        inserted: List<IntRange> = emptyList(),
    ): ReviewOverlay.Excerpt = ReviewOverlay.Excerpt(
        text = text,
        deleted = deleted.flatMap { listOf(it.first, it.last + 1) }.toIntArray(),
        inserted = inserted.flatMap { listOf(it.first, it.last + 1) }.toIntArray(),
    )

    @Test
    fun longRevisionIsTruncated() {
        // 两行排不下的长文不再往控件里塞：摘要长度有上限。
        val spans = revisionReviewSpans(
            rev(start = 0, end = 0, text = "", originalText = "", editedText = "长".repeat(500)),
        )
        assertTrue(concat(spans).length <= 200)
    }
}
