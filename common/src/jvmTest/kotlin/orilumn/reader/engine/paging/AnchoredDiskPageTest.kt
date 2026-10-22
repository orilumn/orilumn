package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 回归（真机 2026-10-22「旋转/调参锚点乱跳」）：改参落位的「锚点字符 → 磁盘表页」查询。
 *
 * 两处真机根因（日志 12:02 拖动、11:59:57 转回竖屏）：
 *  1. 旧扫描只认 `start <= c < end` 的行；**空行 `start == end`** 与块间空隙都不属于任何行，
 *     于是「找不到锚点行」一路落到 `n - 1` 兜底 → 落位取本章**最后一页**（真机 3583 → 11057）。
 *  2. 旧落位取「页首行离锚点行最近」的一页，锚点靠近页尾时会落到**下一页**（真机 5024 → 5151，
 *     锚点被甩出屏幕）。总则 §2 的口径是「当前锚点所在页面」，即**含锚点行的那一页**。
 *
 * 用户口径：页首是行内元素（字符）⇒ 锚点是该字符；页首是块元素（插图/空块）⇒ 锚点是该元素
 * 本身，它的字符足迹可能为零（`start == end`），这条也必须命中，不得落到兜底页。
 *
 * 真机数据取自设备日志同一章（Kotlin 文档 ch5 竖屏）：
 * `(3577,3582) (3583,3583) (3584,3612)` —— 零长行，且它后面还空了一格（块间未绘制字符）。
 */
class AnchoredDiskPageTest {

    /** 显式行区间（前缀和表达不了「块间空隙」，而空隙正是老 bug 的触发条件）。 */
    private class RangedLayout(private val ranges: List<Pair<Int, Int>>) : BookLayout {
        override val lineCount: Int get() = ranges.size
        override val length: Int get() = ranges.last().second
        override fun getLineTop(i: Int): Int = i * 20
        override fun getLineBottom(i: Int): Int = (i + 1) * 20
        override fun getLineStart(i: Int): Int = ranges[i].first
        override fun getLineEnd(i: Int): Int = ranges[i].second
        override fun isParagraphBoundaryLine(i: Int): Boolean = i == 0
    }

    /** 三行：文字 (0,3)、**零长空块 (3,3)**、空隙一格，再文字 (4,7)。 */
    private val layout = RangedLayout(listOf(0 to 3, 3 to 3, 4 to 7))

    /** 两页：行 [0,2)（含零长空块）与行 [2,3)（下一段文字）。 */
    private val pages = listOf(
        PageSlice(charStart = 0, charEnd = 3, firstLine = 0, lastLineExclusive = 2),
        PageSlice(charStart = 4, charEnd = 7, firstLine = 2, lastLineExclusive = 3),
    )

    @Test
    fun `zero-length line holds its own anchor`() {
        assertEquals("空块的字符足迹为 0，锚点仍须命中该行（不是找不到 → 末行）", 1, anchorLineOf(layout, 3))
    }

    @Test
    fun `anchor inside a text line resolves to that line`() {
        assertEquals(0, anchorLineOf(layout, 0))
        assertEquals(0, anchorLineOf(layout, 2))
        assertEquals(2, anchorLineOf(layout, 4))
        assertEquals("越过章尾落在末行，不越界", 2, anchorLineOf(layout, 99))
    }

    @Test
    fun `landing page contains the empty-block anchor`() {
        val page = anchoredDiskPage(layout, pages, 3) ?: error("no page")
        assertEquals("空块锚点应落含它的那一页（不是兜底末页）", 0, page.charStart)
    }

    @Test
    fun `anchor on a page head keeps that page`() {
        // 锚点正是一页首字符：留在这一页（并列不得前进一页，否则等于替用户翻页）。
        val page = anchoredDiskPage(layout, pages, 5) ?: error("no page")
        assertEquals(4, page.charStart)
    }

    @Test
    fun `non-canonical slices never take the landing`() {
        val withTemp = pages + PageSlice(charStart = 0, charEnd = 7, firstLine = -1, lastLineExclusive = -1)
        val page = anchoredDiskPage(layout, withTemp, 1) ?: error("no page")
        assertSame(pages[0], page)
    }
}
