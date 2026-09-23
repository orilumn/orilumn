package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染层：[BoxDrawer.drawOnPage] 页带裁剪按箱子起止分别处理 —— 在本页内结束/开始的箱子
 * 保留完整 border-box（padding 在内、margin 永不进底），只有撕裂延续才按行带裁剪。
 * 章末块底 padding 明明有页空间却被裁掉即此回归。
 */
class BoxDrawerPageBandTest {

    private fun box(
        top: Int, bottom: Int, first: Int, lastExclusive: Int, bg: String = "#4debffff",
    ): LayoutBox {
        val b = LayoutBox(
            el = null,
            style = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f, backgroundColorHex = bg),
            contentLeft = 0, contentWidth = 600,
        )
        b.contentTop = top
        b.contentBottom = bottom
        b.firstLineIndex = first
        b.lastLineExclusive = lastExclusive
        return b
    }

    @Test
    fun `ended box keeps bottom padding past the line band`() {
        // 章末块：行止于 187，盒底（含 padding）196；页行带只到 187。
        val b = box(top = 134, bottom = 196, first = 3, lastExclusive = 5)
        val rs = BoxDrawer.drawOnPage(listOf(b), 0, 5, 0, 187)
            .filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(134, rs[0].top)
        assertEquals(196, rs[0].bottom)
    }

    @Test
    fun `torn continuation is still cut at the band`() {
        // 撕裂块（止于页后）：底仍裁到行带，不向邻页漏底。
        val b = box(top = 100, bottom = 400, first = 2, lastExclusive = 9)
        val rs = BoxDrawer.drawOnPage(listOf(b), 0, 5, 0, 187)
            .filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(187, rs[0].bottom)
    }

    @Test
    fun `started box keeps top padding above the line band`() {
        // 页首起块：顶 padding 不裁（行带从首行顶算起，盒顶在其上）。
        val b = box(top = 9, bottom = 152, first = 0, lastExclusive = 3)
        val rs = BoxDrawer.drawOnPage(listOf(b), 0, 5, 54, 300)
            .filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(9, rs[0].top)
    }

    @Test
    fun `torn start from previous page is still cut at the band`() {
        // 上页延续下来的块（首行在页前）：顶仍裁到行带。
        val b = box(top = 9, bottom = 152, first = 2, lastExclusive = 7)
        val rs = BoxDrawer.drawOnPage(listOf(b), 5, 9, 54, 300)
            .filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(54, rs[0].top)
    }

    @Test
    fun `margins are never painted`() {
        // 扩展上限就是 border-box（contentTop/Bottom），外边距不可能进底。
        val b = box(top = 134, bottom = 196, first = 3, lastExclusive = 5)
        val rs = BoxDrawer.drawOnPage(listOf(b), 0, 5, 0, 187)
            .filter { it.kind == DrawKind.BACKGROUND }
        assertTrue(rs.all { it.top >= 134 && it.bottom <= 196 })
    }
}
