package orilumn.reader.ui.reader

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 编辑清单的纯逻辑（摘录折叠、两行取法）：不依赖 Compose 与数据库。 */
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

    @Test
    fun excerptFoldsNewlinesAndWhitespace() {
        assertEquals("改后的 一段 文字", revisionExcerpt("改后的\n一段\t文字 "))
        // 纯删除 / 纯插入的那一侧是空串：清单据此不画那一行。
        assertEquals("", revisionExcerpt(""))
        assertEquals("", revisionExcerpt(" \n\t "))
    }

    @Test
    fun newRevisionShowsWholeSelectionBeforeAndAfter() {
        // 选中「稳健」改成「稳健地」：最小修订只是插入「地」，清单仍显示整段前后。
        val lines = revisionRowLines(
            rev(start = 5, end = 5, text = "地", originalText = "稳健", editedText = "稳健地"),
        )
        assertEquals("稳健", lines.original)
        assertEquals("稳健地", lines.updated)
    }

    @Test
    fun newDeletionHasNoUpdatedLine() {
        val lines = revisionRowLines(
            rev(start = 2, end = 5, text = "", originalText = "删掉的三个字", editedText = ""),
        )
        assertEquals("删掉的三个字", lines.original)
        assertEquals("", lines.updated)
    }

    @Test
    fun legacyReplacementFallsBackToItsReplacementText() {
        // 加快照之前写的老修订：没有原文快照，下行退回当时的替换文字。
        val lines = revisionRowLines(rev(start = 1, end = 2, text = "X"))
        assertEquals("（原文未记录）", lines.original)
        assertEquals("X", lines.updated)
    }

    @Test
    fun legacyInsertionHasNoOriginalLine() {
        // 老修订的纯插入本来就没有原文，不必画一行「（原文未记录）」。
        val lines = revisionRowLines(rev(start = 3, end = 3, text = "X"))
        assertNull(lines.original)
        assertEquals("X", lines.updated)
    }

    @Test
    fun newDeletionInsideOldRevisionDoesNotFallBackToLeftoverText() {
        // 删掉旧改文的一部分：新修订的改文为空（纯删除），不能把保留下来的旧改文当新文字。
        val lines = revisionRowLines(
            rev(start = 2, end = 3, text = "Y", originalText = "X", editedText = ""),
        )
        assertEquals("X", lines.original)
        assertEquals("", lines.updated)
    }

    @Test
    fun whitespaceOnlySelectionIsNotReportedAsMissing() {
        // 新修订选中的只有空白：有快照，不能说成「原文未记录」。
        val lines = revisionRowLines(
            rev(start = 1, end = 3, text = "X", originalText = " \n ", editedText = "X"),
        )
        assertEquals("（空白）", lines.original)
        assertEquals("X", lines.updated)
    }

    @Test
    fun legacyDeletionKeepsPlaceholderLine() {
        val lines = revisionRowLines(rev(start = 3, end = 4, text = ""))
        assertEquals("（原文未记录）", lines.original)
        assertEquals("", lines.updated)
    }
}
