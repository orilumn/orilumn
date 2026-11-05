package orilumn.reader.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮标题栏显隐判据（平台层·桌面壳）。
 *
 * 重点守一条：设置面板打开时，鼠标压在窗口顶部也不再弹出窗口按钮
 * （面板从右侧贴顶滑出，Win/Linux 的按钮会压在面板标题行上）；macOS 红绿灯在左上角不受影响。
 */
class TitleBarStripTest {

    private fun visible(
        reading: Boolean = true,
        bars: Boolean = false,
        hover: Boolean = false,
        settings: Boolean = false,
        mac: Boolean = false,
    ) = titleBarStripVisible(
        reading = reading,
        readingBarsVisible = bars,
        topHover = hover,
        settingsPanelOpen = settings,
        isMac = mac,
    )

    @Test
    fun `shelf keeps the strip visible`() {
        assertTrue(visible(reading = false))
        assertTrue(visible(reading = false, settings = true))
    }

    @Test
    fun `reading hides the strip until hovered or the reader bars show`() {
        assertFalse(visible())
        assertTrue(visible(hover = true))
        assertTrue(visible(bars = true))
    }

    @Test
    fun `open settings panel suppresses hover on win and linux`() {
        assertFalse(visible(hover = true, settings = true))
        // 面板打开时阅读栏本就会先收起，这里守住「不弹窗」这一条
        assertFalse(visible(hover = true, bars = false, settings = true))
    }

    @Test
    fun `mac traffic lights are unaffected by the settings panel`() {
        assertTrue(visible(hover = true, settings = true, mac = true))
    }
}
