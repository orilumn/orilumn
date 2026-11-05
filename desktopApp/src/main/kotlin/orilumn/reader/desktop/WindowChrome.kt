package orilumn.reader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * 自绘标题栏（**平台层·桌面壳**）。
 *
 * ## 为什么要有它
 *
 * 桌面原生窗口装饰是一条常亮系统标题栏，三件事都做不到：阅读模式下隐藏、透明不画底色、
 * 跟阅读顶栏一起滑入滑出。故标题栏自绘；窗口底子按平台分流（见 [DesktopWindowChrome]）：
 *
 * - **Windows/Linux**：无边框窗口（`WindowDecoration.Undecorated`），底子两种——真机走
 *   逐像素透明 + 圆角/投影（[WindowCornerRadius] + `Modifier.shadow`，官方 Transparent windows
 *   示例的写法）；虚拟机与纯软件渲染环境走不透明直角 + 内容边缘一条极细描边
 *   （判据见 [roundedWindowEnabled]）。
 *   **不用** `Window.setShape` 裁圆角：XWayland 下会残留旧画面并频闪，见 `Main.kt` 窗口底子处的注释；
 * - **macOS**：系统窗口底子（[applyMacSystemWindow]：透明标题栏 + 全窗口内容），系统红绿灯
 *   浮在左上（本类让出 [MacTrafficLightReserve] 空白），圆角/阴影/resize 热区/全屏全由系统给。
 *
 * - **书架模式**：常亮 + 实底（`opaque = true`），占正常布局高度；
 * - **阅读模式**：默认隐藏；鼠标压到窗口顶部 → 滑入且**不画底色**（`opaque = false`，
 *   内容从下面透出来）；鼠标移开 → 滑出；阅读顶栏切出/收起时同拍滑入/滑出
 *   （由壳里的 `AnimatedVisibility` 用 [ChromeAnimMs] 与阅读栏同长驱动）。
 *
 * ## 与阅读栏的关系
 *
 * 阅读模式下滑出来的这条**叠在阅读顶栏上方**，靠把 [TitleBarHeight] 当
 * `ReaderScreen.statusBarInset` 传给阅读面（同样动画到同一时长），让窗口控制区与阅读顶栏
 * 拼成一条整栏；不这么做就是两块互相盖住。
 *
 * ## 窗口控制（按平台惯例，不发明交互）
 *
 * - **macOS**：系统红绿灯（左上角），窗口控制全走系统：红=关、黄=最小化、绿=全屏（系统默认
 *   给「可全屏」窗口的行为）、Option+绿 = 缩放（最大化）；双击标题栏 = 缩放（本类处理，
 *   与 macOS 默认偏好一致）。
 * - **Windows/Linux**：右上角最小化 / 最大化-还原 / 全屏 / 关闭；F11 全屏（两平台通用惯例）。
 *
 * 全屏按平台分流（见 [DesktopWindowChrome] 的 KDoc）：Win/Linux 走 AWT
 * `GraphicsDevice.setFullScreenWindow`；macOS 走 Compose `WindowPlacement.Fullscreen`
 * （底层 = 系统原生 fullscreen Space）。都不自绘假全屏。
 *
 * ## 拖动
 *
 * **macOS**：窗口是系统窗口，顶部约 28pt 本来就由 AppKit 当标题栏拖动，壳只负责吃掉这里的
 * 按下事件（点标题栏不该落到阅读面的三区点按上翻页），**不再自己搬窗口**——再写一次就是
 * 两个写入者抢同一扇窗，表现为窗口整体高频来回跳（见 [draggableWindow] 的 KDoc）。
 *
 * **Win/Linux**：无边框窗口丢掉了系统标题栏热区。Compose 1.11 只给「缩放热区」
 * （`UndecoratedWindowResizer`）；官方的 `WindowDraggableArea` 还在（在
 * `androidx.compose.foundation.window`，不在 `ui-desktop`），但它的标准实现同样是用
 * `window.location` 自己搬窗口，只有跑在 JetBrains Runtime 上才把拖动交给窗口管理器。
 * 它也不管双击缩放、不管「点顶栏不翻页」，故拖动仍由自己这套手势驱动，
 * 只是**搬运窗口这一步优先交给 WM**（[SystemWindowMove]，JBR 的 `WindowMove`）：
 * 自己改坐标会被 Mutter 钳在屏内（拖不出屏幕、贴边也不吸附），交给 WM 才有原生手感；
 * 运行时/平台不支持时原样回落到自绘（见 [draggableWindow]）。
 */
internal val TitleBarHeight: Dp = 32.dp

/** 标题栏与阅读栏同一拍（对齐 `ReaderBars.BarsAnim = 240`，跨模块不能直接引用 internal）。 */
internal const val ChromeAnimMs = 240

/** 自绘窗口圆角半径（无边框窗口丢掉了系统圆角，靠内容自绘补回来）。 */
internal val WindowCornerRadius: Dp = 10.dp

/**
 * 圆角窗口的投影留边（平台层·桌面壳）。
 *
 * 官方做法（Kotlin 文档《Top-level windows management》的 Transparent windows 示例）：
 * `Window(transparent = true, undecorated = true)`，内容 `padding(5.dp).shadow(3.dp, RoundedCornerShape)`。
 * 投影只能画在内容之外的透明像素上，故窗口四边各留 **5dp** 给投影——**不要加大**：
 * 留边越大，窗口的实际矩形离看得见的边缘越远，贴到屏幕边也总差一圈，缩放热区还落在看不见的地方。
 */
internal val WindowShadowPadding: Dp = 5.dp

/** 投影高度（官方示例的 3dp）：交给 `Modifier.shadow`，不自己画 Skia 模糊。 */
internal val WindowShadowElevation: Dp = 3.dp

/** 真 macOS：系统窗口 + 系统红绿灯（见 [applyMacSystemWindow]）；其余平台无边框 + 自绘按钮。 */
internal val IS_MAC: Boolean =
    System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

/**
 * 悬浮标题栏此刻是否出现（平台层·桌面壳）。
 *
 * - 书架：常亮；
 * - 阅读：默认隐藏，鼠标压到窗口顶部或阅读栏切出时滑入；
 * - **设置面板打开期间不因悬停弹条**：面板从右侧贴顶滑出，Win/Linux 的自绘窗口按钮
 *   （右上角最小化/最大化/全屏/关闭）正压在面板标题行上。macOS 的按钮是系统红绿灯
 *   （左上角），与面板不重叠，仍按原口径显隐。
 */
internal fun titleBarStripVisible(
    reading: Boolean,
    readingBarsVisible: Boolean,
    topHover: Boolean,
    settingsPanelOpen: Boolean,
    isMac: Boolean = IS_MAC,
): Boolean {
    val windowButtonsYield = settingsPanelOpen && !isMac
    return !reading || readingBarsVisible || (topHover && !windowButtonsYield)
}

/**
 * macOS 系统红绿灯预留区（平台层·桌面壳）：三键从窗口左缘约 20dp 起、各 12dp、键距 8dp，
 * 总宽约 72dp。全屏 Space 里系统自动隐藏红绿灯，这时不留空（标题栏照常铺开）。
 */
internal val MacTrafficLightReserve: Dp = 72.dp

/** 书架模式实底色（与书架顶栏同为浅色系，避免深色条压在白书架上）。 */
private val ChromeSurface = Color(0xFFF7F7F7)

/** 书架模式前景。 */
internal val ChromeSurfaceFg = Color(0xFF303030)

/** 关闭按钮悬停底色（Windows 惯例）。 */
private val CloseHoverBg = Color(0xFFE81123)

/** 双击判定窗口（与各平台系统口径同量级）。 */
private const val DoubleClickMs = 400L

/**
 * 一条自绘标题栏。
 *
 * @param window 窗口本体（`FrameWindowScope.window`）：拖动要用它搬位置。
 * @param opaque `true` = 实底（书架模式）；`false` = 不画底色（阅读模式，内容透出来）。
 * @param glyph 字符按钮的前景色：阅读模式下由壳按「叠在阅读顶栏上 / 叠在页面上」与昼夜主题选。
 * @param fullscreen 当前是否全屏（切换全屏/还原图标）。
 * @param onToggleFullscreen 全屏 ↔ 还原（壳里接 [DesktopWindowChrome.toggleFullscreen]）。
 * @param onRequestClose 关闭窗口（壳里接 `exitApplication`）。
 */
@Composable
internal fun DesktopTitleBar(
    window: Window,
    windowState: WindowState,
    opaque: Boolean,
    glyph: Color,
    onRequestClose: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Orilumn",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(if (opaque) ChromeSurface else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (IS_MAC) {
            // 系统红绿灯预留区：红绿灯由系统画在内容之上（fullWindowContent），这里只让位、
            // 不参与点按（系统按钮自会响应）；红绿灯之间的空隙不能落到阅读面的点按上，
            // 故也接拖动（拖标题栏空白处搬窗口，macOS 惯例）。
            if (!fullscreen) {
                Box(
                    modifier = Modifier
                        .width(MacTrafficLightReserve)
                        .fillMaxHeight()
                        .draggableWindow(window) {
                            if (fullscreen) onToggleFullscreen() else zoomWindow(windowState)
                        },
                )
            }
        }
        // 拖拽区：占满按钮之外的所有横向空间，并吃掉这里的点按（见 KDoc）。
        // 双击 = 缩放（最大化），macOS 默认偏好，Win/Linux 常规习惯。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .draggableWindow(window) {
                    if (fullscreen) onToggleFullscreen() else zoomWindow(windowState)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (opaque) {
                Text(
                    text = title,
                    color = glyph,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (!IS_MAC) {
            WindowControls(
                windowState, onRequestClose, glyph, fullscreen, onToggleFullscreen,
                Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun WindowControls(
    windowState: WindowState,
    onRequestClose: () -> Unit,
    glyph: Color,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Minimize, glyph) { windowState.isMinimized = true }
        GlyphButton(
            if (windowState.placement == WindowPlacement.Maximized) Glyph.Restore else Glyph.Maximize,
            glyph,
        ) { if (fullscreen) onToggleFullscreen() else zoomWindow(windowState) }
        GlyphButton(
            if (fullscreen) Glyph.Restore else Glyph.Fullscreen,
            glyph,
        ) { onToggleFullscreen() }
        GlyphButton(Glyph.Close, glyph, danger = true, onClick = onRequestClose)
    }
}

/** JDK 客户属性名：macOS 标题栏透明（挂在 rootPane 上，见 [applyMacSystemWindow]）。 */
private const val MAC_TRANSPARENT_TITLE_BAR = "apple.awt.transparentTitleBar"

/** 拖窗停止多久后纠正一次红绿灯显隐（ms；见 `DesktopWindowChrome.scheduleMacTitleBarCorrection`）。 */
private const val MAC_TITLE_BAR_FIX_MS = 150

/** JDK 客户属性名：macOS 内容铺满整窗（含标题栏区）。 */
private const val MAC_FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"

/** JDK 客户属性名：macOS 隐藏系统标题文字（红绿灯仍在）。 */
private const val MAC_WINDOW_TITLE_VISIBLE = "apple.awt.windowTitleVisible"

/**
 * macOS：系统窗口的无边框观感（平台层·桌面壳）。
 *
 * ## 为什么走系统窗口
 *
 * 无边框 + 透明的自绘窗口在 macOS 上进不了原生全屏 Space：AppKit 把窗口搬进独立 Space 却
 * 拒改尺寸，四周一片黑（CMP issue #4573 同款复现；且 CMP 的 `isTransparent = true` 会连
 * 系统投影一起关掉——`hasMacOsShadow = false`，只能自绘留边阴影，resize 热区与视觉边缘错位）。
 * 故 macOS 保留系统装饰（decorated 窗口），用 JDK 客户属性把它伪装成无边框：
 *
 * - [MAC_TRANSPARENT_TITLE_BAR]（`true`）⇒ 系统 `setTitlebarAppearsTransparent:`，标题栏透明；
 * - [MAC_FULL_WINDOW_CONTENT]（`true`）⇒ 系统 `fullSizeContentView`，内容铺到整窗（含标题栏区）；
 * - [MAC_WINDOW_TITLE_VISIBLE]（`false`）⇒ 隐藏系统标题文字（红绿灯保留，见 [MacTrafficLightReserve]）。
 *
 * 换来的全是系统语义：原生圆角/阴影、resize 热区贴窗口边缘、原生全屏 Space
 * （⌃⌘F / 绿点 / Compose `WindowPlacement.Fullscreen` 共用同一条 `toggleFullScreen:`）。
 *
 * ## 为什么在窗口显示之后再设
 *
 * compose-jb#235：`apple.awt.fullWindowContent` 在窗口显示**之前**设置会导致 CMP 内容整窗
 * 不渲染。故只在 `componentShown`（窗口已显示）与 [DesktopWindowChrome.attach]（显示后每帧
 * 补一次，幂等）里设；同值不重写，不产生多余属性事件。
 */
private fun applyMacSystemWindow(window: Window) {
    if (!IS_MAC) return
    val rootPane = (window as? javax.swing.RootPaneContainer)?.rootPane ?: return
    if (rootPane.getClientProperty(MAC_TRANSPARENT_TITLE_BAR) != true) {
        rootPane.putClientProperty(MAC_TRANSPARENT_TITLE_BAR, true)
    }
    if (rootPane.getClientProperty(MAC_FULL_WINDOW_CONTENT) != true) {
        rootPane.putClientProperty(MAC_FULL_WINDOW_CONTENT, true)
    }
    if (rootPane.getClientProperty(MAC_WINDOW_TITLE_VISIBLE) != false) {
        rootPane.putClientProperty(MAC_WINDOW_TITLE_VISIBLE, false)
    }
}

/**
 * 缩放（最大化 ↔ 浮动）。全屏态下没有「最大化」可言，一律退回浮动
 * （对齐 macOS：全屏里双击/缩放入口即退全屏）。
 */
internal fun zoomWindow(windowState: WindowState) {
    windowState.placement = when (windowState.placement) {
        WindowPlacement.Maximized, WindowPlacement.Fullscreen -> WindowPlacement.Floating
        else -> WindowPlacement.Maximized
    }
}

/**
 * 窗口级状态 + 全屏命令（平台层·桌面壳）。
 *
 * ## 为什么要有这个类
 *
 * 全屏快捷键挂在 `Window(onPreviewKeyEvent = …)` 参数上——它在 `Window {}` 内容**之外**，
 * 拿不到 AWT 窗口对象；而窗口对象只在内容里（`FrameWindowScope.window`）才有。故用这个 holder
 * 把「AWT 窗口 + Compose 窗口状态」从内容里挂上来，快捷键与标题栏按钮共用同一条命令。
 *
 * ## 全屏按平台分流
 *
 * **Win/Linux**（无边框 + 自绘外壳）：[GraphicsDevice.setFullScreenWindow]（skiko 在
 * Win/Linux 上用的也是这条，三平台口径一致）。JDK 自己把窗口拉到整屏并收起任务栏，退出后
 * 原样还原。
 *
 * **macOS**（系统窗口，见 [applyMacSystemWindow]）：切 Compose `WindowPlacement.Fullscreen`
 * ——底层走 skiko 的原生 `toggleFullScreen:`，进的是**独立全屏 Space**（菜单栏/Dock 收起，
 * 与 ⌃⌘F、绿点的系统行为同一条路）。为什么无边框 + 透明窗口不能走这条，见
 * [applyMacSystemWindow] 的 KDoc。⌃⌘F 通常由系统（JDK 的 View 菜单 full screen 项）直接
 * 切原生全屏，事件到不了这里；F11 仍由本类处理（[isFullscreenShortcut]）。
 *
 * 两条路殊途同归后，CMP 在 `componentResized` 里回读系统全屏态（CMP 源码注释：全屏变化不触发
 * windowStateChanged、只触发 componentResized），[fullscreen] 与 `windowState.placement`
 * 双向镜像，自绘标题栏的图标与外壳描边不会卡在旧态。
 */
internal class DesktopWindowChrome {
    /** 是否处于全屏（自绘标题栏按钮靠它切「全屏 ↔ 还原」图标）。 */
    var fullscreen by mutableStateOf(false)
        private set

    private var window: Window? = null
    private var windowState: WindowState? = null
    private var placementBeforeFullscreen: WindowPlacement = WindowPlacement.Floating

    /**
     * 进全屏前的窗口底色（半透明），退出全屏后 [restoreTranslucency] 用它还原。
     * 见 [restoreTranslucency] 的 KDoc：JDK 只把底色改实、不负责改回来。
     */
    private var backgroundBeforeFullscreen: java.awt.Color? = null

    private var watched: Window? = null

    /** 阅读界面里系统红绿灯当前是否该隐藏（Apple Books 式：平时隐藏、悬停顶部才浮出）。 */
    private var macTitleBarHidden = false

    /** 上一次**投递出去**的红绿灯显隐态（null = 还没投过）；用于跳过重复投递。 */
    private var macTitleBarApplied: Boolean? = null

    /** 由 `Window {}` 内容每帧交上来（只存引用，不产生可观察写；重复调用无副作用）。 */
    fun attach(window: Window, windowState: WindowState) {
        this.window = window
        this.windowState = windowState
        // 只挂一次监听：全屏状态可能被**外部**改掉（macOS 系统 ⌃⌘F / 切换 Space 时 AWT 会自己
        // 进出全屏，窗口几何随之变化），必须回读真值，否则自绘按钮图标与外壳描边会卡在旧态。
        if (watched !== window) {
            watched?.let { it.removeComponentListener(geometryWatcher) }
            window.addComponentListener(geometryWatcher)
            watched = window
        }
        applyMacSystemWindow(window)
        // 红绿灯显隐是窗口级原生状态，系统在进出全屏 / 窗口重显时会自己改回去。这里走非 force 版：
        // 期望值没变就跳过（不重复投递），变了（含窗口刚 attach、此前投不出去）才补投。
        applyMacTitleBarVisibility()
        syncFullscreen()
    }

    /**
     * macOS：隐藏/显示系统红绿灯三键（阅读界面 Apple Books 观感，见 [MacTrafficLights]）。
     * 非 macOS 恒为 no-op；窗口未 [attach] 时只记状态，attach 后立即落位。
     */
    fun setMacTitleBarHidden(hidden: Boolean) {
        macTitleBarHidden = hidden
        applyMacTitleBarVisibility(force = true)
    }

    /**
     * 把 [macTitleBarHidden] 投给原生层（异步到 AppKit 主线程，见 [MacTrafficLights]）。
     *
     * @param force 忽略「与上次同值」短路、强制重投。系统在**进出全屏 / 窗口重显**时会把红绿灯
     *   改回可见，这时期望值没变、必须靠 force 纠回来；[attach] 每帧走的是非 force 版（跳重复），
     *   force 只出现在三处：进出全屏（[geometryWatcher] 里态变即投）、窗口重显、以及
     *   **停手后**的 resize 防抖（[scheduleMacTitleBarCorrection]）——拖动过程中一次都不投，
     *   否则几百个 AppKit 工作项会挤在主线程上与窗口合成抢时间。
     */
    private fun applyMacTitleBarVisibility(force: Boolean = false) {
        if (!IS_MAC) return
        val w = window ?: return
        if (!force && macTitleBarApplied == macTitleBarHidden) return
        macTitleBarApplied = macTitleBarHidden
        MacTrafficLights.setTitleBarButtonsHidden(w, macTitleBarHidden)
    }

    /**
     * 「停手后纠正一次」的红绿灯重投（resize 防抖；见 [geometryWatcher] 的 KDoc）。
     *
     * 用 `javax.swing.Timer` 而不是协程：重投本来就该在 EDT 上做，而 resize 事件本身
     * 也在 EDT（`ComponentListener`），`restart()` 是 EDT 内的合法调用，不必再引一个 scope。
     * 非 macOS 直接返回，连 Timer 都不建。
     */
    private var macTitleBarFixTimer: javax.swing.Timer? = null

    private fun scheduleMacTitleBarCorrection() {
        if (!IS_MAC) return
        val timer = macTitleBarFixTimer ?: javax.swing.Timer(MAC_TITLE_BAR_FIX_MS) {
            applyMacTitleBarVisibility(force = true)
        }.also {
            it.isRepeats = false
            macTitleBarFixTimer = it
        }
        // 重启式防抖：每次 resize 都把计时推后 ⇒ 连续拖动期间一次都不会投。
        if (timer.isRunning) timer.restart() else timer.start()
    }

    private val geometryWatcher = object : ComponentAdapter() {
        // resize 的两种来源要分开处理（**拖动期间绝不能逐帧碰 AppKit**）：
        //  - 全屏进出：系统必定把红绿灯改回可见 ⇒ 立刻 force 纠正（同 componentShown 口径）；
        //  - 普通拖窗 / 切 Space：旧口径每次 resize 都 force 一次 = 每个鼠标移动事件一个
        //    `dispatch_async_f` 工作项，拖一次窗口几百个，全排在 **AppKit 主线程**上与窗口
        //    合成抢同一个线程（新区域来不及合成 ⇒ 肉眼就是「先黑一块再刷新」）。故改成
        //    **停手后纠正一次**：150ms 防抖，拖动期间一次都不投。
        override fun componentResized(e: ComponentEvent?) {
            val wasFullscreen = fullscreen
            syncFullscreen()
            if (fullscreen != wasFullscreen) {
                applyMacTitleBarVisibility(force = true)
                return
            }
            scheduleMacTitleBarCorrection()
        }

        // 窗口首次显示后落位系统窗口的无边框视觉（见 [applyMacSystemWindow] 的 KDoc：
        // 显示前设置会导致 CMP 内容不渲染）。
        override fun componentShown(e: ComponentEvent?) {
            if (IS_MAC) watched?.let {
                applyMacSystemWindow(it)
                applyMacTitleBarVisibility(force = true)
            }
        }
    }

    /**
     * 以真实窗口态为准同步 [fullscreen]（同值写入不触发重组）。
     *
     * - **macOS**：`windowState.placement` 是原生全屏态的镜像（CMP 在 `componentResized`
     *   里回读系统值，见类 KDoc）。
     * - **Win/Linux**：AWT `fullScreenWindow === window`。
     */
    private fun syncFullscreen() {
        if (IS_MAC) {
            fullscreen = windowState?.placement == WindowPlacement.Fullscreen
            return
        }
        val w = window ?: return
        val dev = runCatching { w.graphicsConfiguration.device }.getOrNull() ?: return
        val nowFullscreen = dev.fullScreenWindow === w
        // 全屏 → 窗口态的转换在这里补还原半透明底子：外部（WM / 系统快捷键）退全屏时
        // [toggleFullscreen] 根本不会被调到，只有 componentResized 回读能发现。
        if (fullscreen && !nowFullscreen) restoreTranslucency(w)
        fullscreen = nowFullscreen
    }

    /**
     * 全屏 ↔ 还原。Win/Linux 以 AWT 的真实状态（`fullScreenWindow === window`）为准，不看自己的
     * 缓存位；macOS 切 Compose `WindowPlacement.Fullscreen`（原生全屏 Space）。
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
            // 乐观落位（原生切换是异步动画）：按钮图标/红绿灯预留区立即切换，回读同值不重复写。
            fullscreen = target
            return
        }
        val dev = runCatching { w.graphicsConfiguration.device }.getOrNull() ?: return
        if (!dev.isFullScreenSupported) return
        if (dev.fullScreenWindow === w) {
            dev.fullScreenWindow = null
            // JDK 进全屏时会把窗口底色改成不透明、且退出后不还原（见 [restoreTranslucency]），
            // 不补这一步，投影留边露出的是窗口底色 —— 读者看到的就是「阴影变成一圈黑边」。
            restoreTranslucency(w)
            // AWT 退出全屏会把窗口降成不活跃（本机实测 active: true → false），补一次置前，
            // 免得读者从全屏出来还得先点一下窗口，快捷键/滚轮才有反应。
            runCatching { w.toFront() }
            // 退出后还原进全屏前的放置态（进之前若在最大化，这里重新最大化）。
            windowState?.placement = placementBeforeFullscreen
            fullscreen = false
        } else {
            placementBeforeFullscreen = windowState?.placement ?: WindowPlacement.Floating
            backgroundBeforeFullscreen = w.background
            // 全屏里没有「最大化」可言：先退回浮动，免得 Compose 与 AWT 争同一份窗口几何。
            if (windowState?.placement == WindowPlacement.Maximized) {
                windowState?.placement = WindowPlacement.Floating
            }
            dev.fullScreenWindow = w
            fullscreen = true
        }
    }

    /** 全屏中先退全屏（双击标题栏 / 缩放按钮的统一入口）。 */
    fun exitFullscreen() {
        if (fullscreen) toggleFullscreen()
    }

    /**
     * 退出全屏后把窗口底色还原成半透明（投影/圆角的前提）。
     *
     * [GraphicsDevice.setFullScreenWindow] 的文档写得很明确：
     * 「When entering full-screen mode, all the translucency effects are reset for the window.
     * Its shape is set to null, the opacity value is set to 1.0f, and the background color
     * alpha is set to 255 (completely opaque). **These values are not restored when returning
     * to windowed mode.**」
     *
     * 所以进全屏那一刻 JDK 就把底色从 alpha=0 改成 alpha=255；AWT 随之换回不透明
     * graphics configuration（X11 上窗口被重建成 depth 24），而 Compose 内容在投影留边里
     * 什么都不画 —— 露出来的就是窗口底色（黑），读者看到「阴影变成一圈黑边」。
     *
     * 还原只能走 [Window.setBackground]：alpha < 255 时它会把窗口换回
     * translucency-capable 的 graphics configuration（官方口径，见该方法 javadoc
     * 「Enabling the per-pixel translucency mode may change the graphics configuration of this
     * window due to the native platform requirements」）。窗口不在全屏态时才允许调，
     * 否则 JDK 抛 IllegalComponentStateException。
     */
    private fun restoreTranslucency(w: Window) {
        val bg = backgroundBeforeFullscreen ?: return
        if (w.background == bg) return
        runCatching { w.background = bg }
            .onSuccess {
                // 换回透明底 = AWT 换 graphics configuration = **换一扇本地窗口**（X11 上 depth
                // 24 → 32）。新窗口的尺寸是回来了，可 JFrame 里的内容面板还停在全屏尺寸
                // （实测：窗口 873×716，JRootPane 仍是 1280×800），Compose 于是照整屏版面出图、
                // 被窗口裁在右下，投影留边也只重画了左上那一块 —— 读者看到的就是「除了左上角，
                // 其他三个角都成了直角」，拖一下窗口（带来一次真正的尺寸事件）才恢复。
                // 故这里显式让窗口重新布局 + 整窗重画一次，把尺寸事件补齐。
                w.invalidate()
                w.validate()
                w.repaint()
                orilumn.reader.io.Logger.i(
                    "Orilumn.Window",
                    "fullscreen exit: restored translucent background alpha=${bg.alpha}",
                )
            }
            .onFailure {
                orilumn.reader.io.Logger.w(
                    "Orilumn.Window",
                    "fullscreen exit: restore background FAILED ${it.message}",
                )
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

private enum class Glyph { Minimize, Maximize, Restore, Fullscreen, Close }

@Composable
private fun GlyphButton(
    glyph: Glyph,
    color: Color,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        hovered && danger -> CloseHoverBg
        hovered -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val fg = if (hovered && danger) Color.White else color
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = TitleBarHeight)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            val sw = size.minDimension / 10f
            when (glyph) {
                Glyph.Minimize -> drawLine(
                    color = fg,
                    start = Offset(c.x - r, c.y),
                    end = Offset(c.x + r, c.y),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                Glyph.Maximize -> drawRect(
                    color = fg,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = sw),
                )
                Glyph.Restore -> {
                    val s = r * 2f
                    drawRect(
                        color = fg,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(s, s),
                        style = Stroke(width = sw),
                    )
                    drawRect(
                        color = fg,
                        topLeft = Offset(c.x - r + sw * 2f, c.y - r - sw * 2f),
                        size = Size(s - sw * 2f, s - sw * 2f),
                        style = Stroke(width = sw),
                    )
                }
                // 全屏：四角括号（⛶）。
                Glyph.Fullscreen -> {
                    val arm = r * 0.8f
                    listOf(
                        Offset(c.x - r, c.y - r) to Offset(c.x - r + arm, c.y - r),
                        Offset(c.x - r, c.y - r) to Offset(c.x - r, c.y - r + arm),
                        Offset(c.x + r, c.y - r) to Offset(c.x + r - arm, c.y - r),
                        Offset(c.x + r, c.y - r) to Offset(c.x + r, c.y - r + arm),
                        Offset(c.x - r, c.y + r) to Offset(c.x - r + arm, c.y + r),
                        Offset(c.x - r, c.y + r) to Offset(c.x - r, c.y + r - arm),
                        Offset(c.x + r, c.y + r) to Offset(c.x + r - arm, c.y + r),
                        Offset(c.x + r, c.y + r) to Offset(c.x + r, c.y + r - arm),
                    ).forEach { (a, b) ->
                        drawLine(color = fg, start = a, end = b, strokeWidth = sw, cap = StrokeCap.Round)
                    }
                }
                Glyph.Close -> {
                    drawLine(
                        color = fg,
                        start = Offset(c.x - r, c.y - r),
                        end = Offset(c.x + r, c.y + r),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = fg,
                        start = Offset(c.x + r, c.y - r),
                        end = Offset(c.x - r, c.y + r),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * 标题栏的拖动 + 「吃掉点按」+ 双击缩放。
 *
 * ## macOS：不自己搬窗口
 *
 * macOS 是**系统窗口**（见 [applyMacSystemWindow]），窗口顶部约 28pt 由 AppKit 自己就是拖动标题栏
 * 的热区。再叠一层自绘拖动就是**两个写入者抢同一扇窗**：两边各有各的抓点基准，差几像素，
 * 于是每个指针事件窗口在两套坐标间跳一次，肉眼就是「整体相对桌面高频来回跳」
 * （实测：光标匀速 +8px/步时，窗口在「光标−150」与「光标−158」之间以 5~13ms 周期摆动；
 * 停掉自绘写入后同一手势全程干净跟手）。故 macOS 只吃这里的按下与拖动事件
 * （否则点标题栏会落到阅读面的三区点按上翻页），搬窗口与双击缩放都交给系统。
 *
 * ## Win/Linux：拖动交给 WM，回落到自绘
 *
 * 无边框窗口没有系统热区，`UndecoratedWindowResizer` 只给缩放，故拖动的手势判定自己做；
 * **搬窗口这一步优先交给 WM**（[SystemWindowMove]）：自己发 configure request 会被 Mutter
 * 钳在屏内（实测请求 -200 落 0、5000 落 2160），只有 WM 自己搬才允许移出屏幕、贴边吸附。
 *
 * 交接时机：指针移动超过起手抖动门限（约 4px）的那一帧——此时按键已按下、指针仍在窗口内，
 * 正是 `_NET_WM_MOVERESIZE` 的前置条件。**交接成功后一次都不再写 `window.location`**：
 * 两个写入者各有各的抓点基准，差几像素就会每帧互相纠正一次，表现与 macOS 那条老坑一模一样。
 * 交接失败（非 JBR 运行时、非 X11）就沿用旧的自绘拖动——
 * 位移口径取**屏幕指针差**而不是 Compose 的 `dragAmount`（窗口一被搬动，本地坐标系跟着动，
 * 用 `dragAmount` 累积会自我抵消，拖不动或抖动）。
 *
 * 两种路径下事件都照常 `consume()` 到松手：本区域的按下不能漏到阅读面的三区点按上（点顶栏不翻页）。
 *
 * 双击（两次按下间隔 ≤ [DoubleClickMs] 且都没拖动）走 [onDoubleClick]。
 *
 * 起手即 `consume()`：本区域的按下不再落到阅读面的三区点按（点顶栏不翻页）。
 */
private fun Modifier.draggableWindow(
    window: Window,
    onDoubleClick: () -> Unit = {},
): Modifier = pointerInput(window, onDoubleClick) {
    var lastDownAt = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        if (IS_MAC) {
            // 见上方 KDoc「macOS：不自己搬窗口」：只吃到松手，不写窗口位置。
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
            return@awaitEachGesture
        }
        val now = down.uptimeMillis
        val secondClick = now - lastDownAt <= DoubleClickMs
        lastDownAt = now
        val startPointer = runCatching { MouseInfo.getPointerInfo().location }.getOrNull()
        val startWindow = window.location
        var moved = false
        // 本次拖动是否已交给 WM（交出去之后禁止再写 window.location，见上方 KDoc）。
        var delegated = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            if (startPointer == null) continue
            val pointer = runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: continue
            val dx = pointer.x - startPointer.x
            val dy = pointer.y - startPointer.y
            // 起手抖动门限（约 4px）：没动够就不算拖动，纯点一下不搬窗口。
            if (!moved && dx * dx + dy * dy > 16) {
                moved = true
                // 越过门限的这一帧交接给 WM：按键已按下、指针还在窗口内，正好满足
                // _NET_WM_MOVERESIZE 的前置条件。失败就继续自绘。
                delegated = SystemWindowMove.start(window)
            }
            if (moved && !delegated) window.location = Point(startWindow.x + dx, startWindow.y + dy)
        }
        if (moved) {
            // 拖过就不算双击（下一次按下重新计数），否则「拖一下再快点一下」会误触发缩放。
            lastDownAt = 0L
        } else if (secondClick) {
            lastDownAt = 0L
            onDoubleClick()
        }
    }
}
