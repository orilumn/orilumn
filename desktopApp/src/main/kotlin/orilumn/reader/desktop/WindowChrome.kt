package orilumn.reader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/** 真 macOS（窗口装饰与全屏走系统那一套，见 [DesktopWindowChrome]）。 */
internal val IS_MAC: Boolean =
    System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

/**
 * 窗口级状态 + 全屏命令（**平台层·桌面壳**）。
 *
 * ## 窗口装饰一律系统默认
 *
 * 标题栏、圆角、阴影、缩放热区（含缩放光标）、贴边吸附全部交给窗口管理器 / AppKit。
 * 曾经为了「阅读模式下隐藏标题栏、跟阅读顶栏一起滑出」自绘过一整套无边框窗口壳
 * （自绘标题栏 + 自绘按钮 + 透明圆角投影 + 拖动交接），代价是缩放热区与缩放光标也得自己做，
 * 而 Linux 上 AWT 的预设光标取的是 X 的 cursor font 位图、并不跟随桌面主题——观感与原生对不上。
 * 那条路已整体退役，这个类只剩「全屏」一件事。
 *
 * ## 为什么要有这个类
 *
 * 全屏快捷键挂在 `Window(onPreviewKeyEvent = …)` 参数上——它在 `Window {}` 内容**之外**，
 * 拿不到 AWT 窗口对象；而窗口对象只在内容里（`FrameWindowScope.window`）才有。故用这个 holder
 * 把「AWT 窗口 + Compose 窗口状态」从内容里挂上来。
 *
 * ## 全屏按平台分流
 *
 * - **Windows/Linux**：[GraphicsDevice.setFullScreenWindow]（skiko 在 Win/Linux 上用的也是这条）。
 *   JDK 自己把窗口拉到整屏并收起任务栏，退出后原样还原窗口与装饰。
 * - **macOS**：切 Compose `WindowPlacement.Fullscreen`——底层走 skiko 的原生 `toggleFullScreen:`
 *   ，进的是**独立全屏 Space**（菜单栏/Dock 收起，与 ⌃⌘F、绿点的系统行为同一条路）。
 *
 * 两条路殊途同归后，CMP 在 `componentResized` 里回读系统全屏态（CMP 源码注释：全屏变化不触发
 * windowStateChanged、只触发 componentResized），[fullscreen] 与真实状态保持一致——窗口几何的
 * 落盘判据要用它（全屏期间的尺寸不是读者调出来的）。
 */
internal class DesktopWindowChrome {

    /** 是否处于全屏。 */
    var fullscreen by mutableStateOf(false)
        private set

    private var window: Window? = null
    private var windowState: WindowState? = null
    private var placementBeforeFullscreen: WindowPlacement = WindowPlacement.Floating
    private var watched: Window? = null

    /** 由 `Window {}` 内容每帧交上来（只存引用，不产生可观察写；重复调用无副作用）。 */
    fun attach(window: Window, windowState: WindowState) {
        this.window = window
        this.windowState = windowState
        // 只挂一次监听：全屏状态可能被**外部**改掉（系统快捷键 / 切换 Space 时 AWT 会自己
        // 进出全屏），必须回读真值，否则落盘判据会拿全屏尺寸当读者调的窗口大小。
        if (watched !== window) {
            watched?.let { it.removeComponentListener(geometryWatcher) }
            window.addComponentListener(geometryWatcher)
            watched = window
        }
        syncFullscreen()
    }

    private val geometryWatcher = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) = syncFullscreen()
    }

    /** 以真实窗口态为准同步 [fullscreen]（同值写入不触发重组）。 */
    private fun syncFullscreen() {
        if (IS_MAC) {
            fullscreen = windowState?.placement == WindowPlacement.Fullscreen
            return
        }
        val w = window ?: return
        val dev = runCatching { w.graphicsConfiguration.device }.getOrNull() ?: return
        fullscreen = dev.fullScreenWindow === w
    }

    /**
     * 全屏 ↔ 还原。Win/Linux 以 AWT 的真实状态（`fullScreenWindow === window`）为准；
     * macOS 切 Compose `WindowPlacement.Fullscreen`（原生全屏 Space）。
     * 平台不支持全屏时静默不动（与 skiko 同口径：只在支持时才切）。
     */
    fun toggleFullscreen() {
        val w = window ?: return
        if (IS_MAC) {
            val st = windowState ?: return
            val target = st.placement != WindowPlacement.Fullscreen
            // 全屏里没有「最大化」可言：先退回浮动，免得原生全屏与 AWT 缩放态争同一份几何。
            if (target && st.placement == WindowPlacement.Maximized) {
                st.placement = WindowPlacement.Floating
            }
            st.placement = if (target) WindowPlacement.Fullscreen else WindowPlacement.Floating
            // 乐观落位（原生切换是异步动画）：回读同值时不重复写。
            fullscreen = target
            return
        }
        val dev = runCatching { w.graphicsConfiguration.device }.getOrNull() ?: return
        if (!dev.isFullScreenSupported) return
        if (dev.fullScreenWindow === w) {
            dev.fullScreenWindow = null
            // AWT 退出全屏会把窗口降成不活跃（本机实测 active: true → false），补一次置前，
            // 免得读者从全屏出来还得先点一下窗口，快捷键/滚轮才有反应。
            runCatching { w.toFront() }
            // 退出后还原进全屏前的放置态（进之前若在最大化，这里重新最大化）。
            windowState?.placement = placementBeforeFullscreen
            fullscreen = false
        } else {
            placementBeforeFullscreen = windowState?.placement ?: WindowPlacement.Floating
            // 全屏里没有「最大化」可言：先退回浮动，免得 Compose 与 AWT 争同一份窗口几何。
            if (windowState?.placement == WindowPlacement.Maximized) {
                windowState?.placement = WindowPlacement.Floating
            }
            dev.fullScreenWindow = w
            fullscreen = true
        }
    }
}

/**
 * 全屏快捷键判定（平台层惯例）：`F11` 三平台通吃（Win/Linux 标准，macOS 上也常见）；
 * macOS 另认系统惯例 `⌃⌘F`——它通常被 JDK 的 View 菜单 full screen 项先抢走（直接切原生
 * 全屏，事件到不了这里），这里只作兜底，两条路殊途同归（都走原生全屏）。只认按下，避免按住连翻。
 */
internal fun isFullscreenShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.F11) return true
    return IS_MAC && event.isMetaPressed && event.isCtrlPressed && event.key == Key.F
}
