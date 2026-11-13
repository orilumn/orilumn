package orilumn.reader.ui.reader

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重开旧修订时要不要扩到整块：只认「纯插入、插入的全是换行」。
 *
 * 背景：读者在零散文字里敲了个 `<br>`，落库的是**最小修订**（原区间为空、替换文本就是那个
 * 换行）。按它开卡，输入区里只剩行尾一个 `\`，看不出换行落在哪两个词之间——这种要扩到
 * 所在整块再开卡。删字 / 换字的修订卡片里本来就有字，不扩（审阅视图里扩块会把被删的原文带回来）。
 */
class RevisionBlockContextTest {

    private fun revision(charStart: Int, charEnd: Int, text: String, delta: Int) = TextRevision(
        bookId = 1,
        chapter = 0,
        charStart = charStart,
        charEnd = charEnd,
        text = text,
        delta = delta,
    )

    @Test
    fun insertingABreakNeedsBlockContext() {
        // 实测形态（桌面全管线）：在「云涌」之间敲 `<br>`，落库 6..6 纯插入，delta = 1。
        // 阅读面拿到的两份区间：普通视图 = 渲染区间 6..7，审阅视图 = 整段 5..8。
        assertTrue(revision(6, 7, "\n", delta = 1).let(::revisionNeedsBlockContext))
        assertTrue(revision(5, 8, "\n", delta = 1).let(::revisionNeedsBlockContext))
        assertTrue(revision(6, 8, "\n\n", delta = 2).let(::revisionNeedsBlockContext))
    }

    @Test
    fun replacingACharacterWithABreakDoesNotNeedBlockContext() {
        // 换掉了一个字：审阅视图里那块文字还排着被删的原文，扩块开卡会把它带回来。
        assertFalse(revisionNeedsBlockContext(revision(6, 7, "\n", delta = 0)))
    }

    @Test
    fun deletingTextDoesNotNeedBlockContext() {
        assertFalse(revisionNeedsBlockContext(revision(5, 7, "", delta = -2)))
    }

    @Test
    fun insertingAParagraphBreakAloneIsLeftToTheOldCard() {
        // 段界不占字符（renderedLength = 0）：这条修订的渲染区间是空的，没有「所在整块」可取。
        assertFalse(revisionNeedsBlockContext(revision(6, 6, "\uE000", delta = 0)))
    }

    @Test
    fun ordinaryRevisionsKeepTheirOwnText() {
        assertFalse(revisionNeedsBlockContext(revision(6, 7, "海", delta = 0)))
        assertFalse(revisionNeedsBlockContext(revision(6, 6, "新", delta = 1)))
    }
}
