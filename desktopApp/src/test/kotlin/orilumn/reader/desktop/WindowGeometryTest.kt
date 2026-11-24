package orilumn.reader.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 桌面窗口几何存档：浮动尺寸/位置逐像素往返，放置态（最大化）单独记，
 * 全屏期间的尺寸不写进存档。
 *
 * 只测读写口径本身（不碰真实窗口）：headless 下取不到屏幕信息，越界校验放行。
 */
class WindowGeometryTest {

    private fun tempStore(): DesktopShelfStore =
        DesktopShelfStore(Files.createTempDirectory("orilumn-win-geom").toFile())

    @Test
    fun `floating geometry round-trips exactly`() {
        val store = tempStore()
        persistWindowGeometry(
            store,
            fullscreen = false,
            placement = WindowPlacement.Floating,
            size = DpSize(777.dp, 555.dp),
            position = WindowPosition.Absolute(120.dp, 80.dp),
        )

        val restored = restoreWindowGeometry(store)

        // 版心是按像素进分页缓存键的，差 1 像素就是整章重排 ⇒ 必须逐像素相同。
        assertEquals(777, restored.widthPx)
        assertEquals(555, restored.heightPx)
        assertEquals(WindowPosition.Absolute(120.dp, 80.dp), restored.position)
        assertEquals(WindowPlacement.Floating, restored.placement)
    }

    @Test
    fun `maximized keeps the floating geometry and is restored as maximized`() {
        val store = tempStore()
        persistWindowGeometry(
            store,
            fullscreen = false,
            placement = WindowPlacement.Floating,
            size = DpSize(800.dp, 600.dp),
            position = WindowPosition.Absolute(10.dp, 20.dp),
        )
        // 最大化时 AWT 报的是工作区尺寸，不该顶掉浮动几何。
        persistWindowGeometry(
            store,
            fullscreen = false,
            placement = WindowPlacement.Maximized,
            size = DpSize(1920.dp, 1080.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
        )

        val restored = restoreWindowGeometry(store)

        assertEquals(800, restored.widthPx)
        assertEquals(600, restored.heightPx)
        assertEquals(WindowPosition.Absolute(10.dp, 20.dp), restored.position)
        assertEquals(WindowPlacement.Maximized, restored.placement)
    }

    @Test
    fun `fullscreen never overwrites the archive`() {
        val store = tempStore()
        persistWindowGeometry(
            store,
            fullscreen = false,
            placement = WindowPlacement.Floating,
            size = DpSize(640.dp, 480.dp),
            position = WindowPosition.Absolute(30.dp, 40.dp),
        )
        // Win/Linux 全屏时放置态仍是 Floating，只靠 fullscreen 标记拦住。
        persistWindowGeometry(
            store,
            fullscreen = true,
            placement = WindowPlacement.Floating,
            size = DpSize(2560.dp, 1440.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
        )
        // macOS 全屏时放置态是 Fullscreen，同样不记。
        persistWindowGeometry(
            store,
            fullscreen = false,
            placement = WindowPlacement.Fullscreen,
            size = DpSize(2560.dp, 1440.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
        )

        val restored = restoreWindowGeometry(store)

        assertEquals(640, restored.widthPx)
        assertEquals(480, restored.heightPx)
        assertEquals(WindowPlacement.Floating, restored.placement)
    }
}
