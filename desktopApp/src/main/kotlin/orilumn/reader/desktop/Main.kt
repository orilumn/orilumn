@file:OptIn(ExperimentalComposeUiApi::class)

package orilumn.reader.desktop

import orilumn.reader.data.settings.BookSettings
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.ReaderSettingsStore
import orilumn.reader.data.settings.BookSettingsStore
import orilumn.reader.data.settings.PerBookSettings
import orilumn.reader.ui.App
import orilumn.reader.ui.reader.ThemePreset
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfSort
import orilumn.reader.ui.shelf.ShelfView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import java.awt.Dimension
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_SORT = "shelf_sort_name"
private const val PREF_VIEW = "shelf_view"
private const val PREF_WIN_W = "window_width_dp"
private const val PREF_WIN_H = "window_height_dp"
private const val PREF_WIN_X = "window_x_dp"
private const val PREF_WIN_Y = "window_y_dp"

/**
 * 最小窗口**内容**尺寸（dp）。同时用作：`minimumSize` 下限 + 读存档时的钳制下限。
 *
 * 下限的由来不是拍脑袋，是版心：页边距默认上 100 / 下 60 / 左右各 50dp，正文 18dp，
 * 故内容高 −160dp、内容宽 −100dp 就是版心。240×300 ⇒ 版心 140×140dp（约每行 7 字、4 行），
 * 再小就一行字都放不下（版心高 ≤ 0 时分页直接空页）。
 */
private const val MinContentW = 240
private const val MinContentH = 300

/**
 * 存档坐标是否落在任一当前屏幕内（dp 按该屏缩放换算 px，200px 容差允许半出屏；
 * 取不到屏幕信息即放行）。显示器增减后越界坐标回默认位，不把窗口开到屏外。
 */
private fun onAnyScreen(xDp: Int, yDp: Int): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { dev ->
        val b = dev.defaultConfiguration.bounds
        val s = dev.defaultConfiguration.defaultTransform.scaleX.coerceAtLeast(1.0)
        val x = (xDp * s).toInt()
        val y = (yDp * s).toInt()
        x in b.x - 200..b.x + b.width && y in b.y - 200..b.y + b.height
    }
}.getOrDefault(true)

/**
 * S32 桌面最小壳：JVM `main` 起窗口挂 shared-ui。
 *
 * 薄壳契约（与 S31 Android 壳同口径）：
 * - 只做真系统级：数据根初始化、窗口管理、导航状态；
 * - 书架（列表/导入/mpfilepicker/Snackbar）走 [App]；
 * - 阅读（画布/手势/进度/遮罩）走 [ReaderView] + [DesktopReaderHost]（首个真实 Skia 管线）；
 * - 设置（全局 `reader.json` + 自定义主题预设持久化）走 [ReaderView] 内的设置面板。
 */
fun main() {
    DesktopPaths.ensure()
    application {
        // 三端同一 JVM 壳：窗口尺寸+位置经 prefs 持久化，下次启动原地恢复
        // （Win/macOS/Linux 同口径；显示器变更致越界时回居中，不开到屏外）。
        val store = remember { DesktopShelfStore(DesktopPaths.root) }
        // 逐像素透明是自绘圆角/投影的前提（四角与边缘透出桌面），只给无边框的 Win/Linux 用：
        // macOS 走系统窗口（透明标题栏 + 全窗口内容，见 WindowChrome.applyMacSystemWindow），
        // 圆角/阴影/全屏全由系统给，用不到透明。不支持透明的平台（部分未开合成器的 Linux）
        // 设透明背景会直接抛 UnsupportedOperationException 让窗口起不来，故先探测：不支持就
        // 退回「不透明 + 直角 + 无投影」，功能一项不少，只是和原生窗口不一致。
        val canTransparent = !IS_MAC && runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .isWindowTranslucencySupported(
                    java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
                )
        }.getOrDefault(false)
        // 浮动窗口要在窗口**内部**四边留出投影的位置（见 [WindowShadowMargin]）：故 prefs 里存的是
        // **内容**尺寸与内容左上角，窗口尺寸/位置 = 内容 ± 留边。留边之前的存档存的正是「窗口 =
        // 内容」，按内容读入即天然兼容——升级后内容尺寸、屏幕位置都原地不变，不会看着窗口变小。
        // macOS 系统窗口 = 内容的窗口（无留边，见 applyMacSystemWindow），同一把换算在
        // inset = 0 时即「窗口 = 内容」，与旧存档口径一致。
        val shadowInsetDp = if (canTransparent) WindowShadowMargin.value * 2f else 0f
        val shadowOffsetDp = if (canTransparent) WindowShadowMargin.value else 0f
        fun contentDp(key: String, def: Int, lo: Int, hi: Int): Float =
            (store.loadPref(key, def.toString()).toIntOrNull() ?: def).coerceIn(lo, hi).toFloat()
        // 默认/钳制下限同「最小窗口」口径（下方 `minimumSize` 一处定，两处引用同一常量）：
        // 550×400 是桌面阅读的舒服尺寸（小屏/高分屏上不至于占满），240×300 是能读的版心下限
        // （版心 = 内容尺寸 − 上100/下60/左右50dp 的页边距；再小就一行字都放不下）。
        val windowState = rememberWindowState(
            width = (contentDp(PREF_WIN_W, 550, MinContentW, 2560) + shadowInsetDp).dp,
            height = (contentDp(PREF_WIN_H, 400, MinContentH, 1600) + shadowInsetDp).dp,
            position = remember {
                val x = store.loadPref(PREF_WIN_X, "").toIntOrNull()
                val y = store.loadPref(PREF_WIN_Y, "").toIntOrNull()
                if (x != null && y != null && onAnyScreen(x, y)) {
                    androidx.compose.ui.window.WindowPosition.Absolute(
                        (x - shadowOffsetDp).dp,
                        (y - shadowOffsetDp).dp,
                    )
                } else {
                    androidx.compose.ui.window.WindowPosition.PlatformDefault
                }
            },
        )
        // 窗口级状态 + 全屏命令（平台层·桌面壳）：快捷键挂在 Window 参数上、拿不到窗口对象，
        // 故用这个 holder 把内容里的窗口挂上来（见 DesktopWindowChrome 的 KDoc）。
        val chrome = remember { DesktopWindowChrome() }
        // 移动/调整完 500ms 落盘（IO 线程，不挡 UI；下次启动即原地恢复）。
        // 全屏期间不落盘：那时 windowState.size 已是整屏尺寸，落盘会把用户原来的窗口大小冲掉。
        LaunchedEffect(windowState, chrome, canTransparent) {
            snapshotFlow {
                Triple(chrome.fullscreen, windowState.size, windowState.position)
            }
                .debounce(500)
                .collect { (fullscreen, size, pos) ->
                    // 只记**浮动**几何：全屏时 size 已是整屏、最大化时是工作区，都不是用户调出来的
                    // 窗口大小/位置，落盘会把用户原来的浮动几何冲掉。
                    if (fullscreen || windowState.placement != WindowPlacement.Floating) return@collect
                    // 存的是**内容**尺寸与内容左上角（见上面 rememberWindowState 的注释）：
                    // 只有「浮动 + 支持透明」时窗口里才有留边，别的态窗口 = 内容。
                    val inset = if (canTransparent) WindowShadowMargin.value else 0f
                    withContext(Dispatchers.IO) {
                        store.savePref(PREF_WIN_W, (size.width.value - inset * 2f).toInt().toString())
                        store.savePref(PREF_WIN_H, (size.height.value - inset * 2f).toInt().toString())
                        (pos as? androidx.compose.ui.window.WindowPosition.Absolute)?.let {
                            store.savePref(PREF_WIN_X, (it.x.value + inset).toInt().toString())
                            store.savePref(PREF_WIN_Y, (it.y.value + inset).toInt().toString())
                        }
                    }
                }
        }
        // icon.png 走 JVM classpath（src/main/resources）：旧 painterResource(String)
        // 在 CMP 1.7 虽 deprecated 但仍是 classpath 加载的可用口径，故显式 Suppress。
        @Suppress("DEPRECATION")
        val appIcon = painterResource("icon.png")
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Orilumn",
            icon = appIcon,
            // 窗口装饰（平台层·桌面壳）：Win/Linux 无边框 + 自绘标题栏/圆角/投影（原生装饰
            // 三件事都做不到——阅读模式下隐藏、透明不画底色、跟阅读顶栏一起滑入滑出）；
            // macOS 保留系统装饰换原生圆角/阴影/resize 热区/全屏 Space——透明标题栏 + 全窗内容
            // 的无边框观感（见 WindowChrome.applyMacSystemWindow），自绘那套在 mac 上进不了
            // 真全屏（AppKit 拒绝给透明窗口开全屏 Space）。
            // 无边框窗口没有系统缩放热区，CMP 只能自绘，且恒定贴在 **AWT 窗口边缘**往内
            // `resizerThickness`（默认 8dp，见 `WindowDecoration.Undecorated`）。
            // 自绘阴影把可视内容整体内缩 `WindowShadowMargin`，那 8dp 热区于是整条陷进不可见的
            // 透明环 —— 指针不变缩放光标、也抓不到，表现为「能拖不能缩」。
            //
            // 热区厚度直接从**同一个** `shadowInsetDp` 导出（= 单侧留边 + 默认热区），与窗口尺寸、
            // 内容 padding 共用一把尺 ⇒ 留边为 0 的平台（macOS 走系统窗口 / 不支持透明的 Linux）
            // 自动退回 8dp，无需按平台分叉。这是共享机制的一次修复，不是 Win/Linux 的特例。
            // 历史：ff7d9de 记录过 macOS 撞的是同一个病根（「resize 热区与视觉边缘错位」），
            // 但当时选择退回系统窗口绕开；此处补上机制层的修法。
            decoration = if (IS_MAC) {
                WindowDecoration.SystemDefault
            } else {
                WindowDecoration.Undecorated((shadowInsetDp / 2f + 8f).dp)
            },
            transparent = canTransparent,
            // 平台惯例全屏快捷键：F11 三平台通吃，macOS 另加 ⌃⌘F（见 WindowChrome.isFullscreenShortcut）。
            onPreviewKeyEvent = { event ->
                if (isFullscreenShortcut(event)) {
                    chrome.toggleFullscreen()
                    true
                } else {
                    false
                }
            },
        ) {
            SideEffect { chrome.attach(window, windowState) }
            // 最小窗口尺寸兜底：防止拖到比阴影留边还小导致 contentH ≤ 0。
            // 取值见 [MinContentW]/[MinContentH]（与上方读存档的钳制下限同源）。
            val densityMin = LocalDensity.current
            val awtWin = window as? java.awt.Window
            SideEffect {
                val w = awtWin ?: return@SideEffect
                with(densityMin) {
                    val minW = (MinContentW.dp + shadowInsetDp.dp * 2).roundToPx()
                    val minH = (MinContentH.dp + shadowInsetDp.dp * 2).roundToPx()
                    if (w.minimumSize.width != minW || w.minimumSize.height != minH) {
                        w.minimumSize = Dimension(minW, minH)
                    }
                }
            }
            MaterialTheme {
                // 无边框窗口（Win/Linux）丢掉了系统圆角与投影，靠内容自绘补回
                //（[WindowCornerRadius] / [windowShadow]）：四角裁掉、投影画在留边上，
                // 都靠透明像素透出桌面。macOS 是系统窗口（canTransparent 恒 false），不走这里。
                // 只在「浮动」态圆角+投影：全屏/最大化取直角（原生 Windows 最大化就是直角贴满工作区）。
                val rounded = canTransparent && !chrome.fullscreen &&
                    windowState.placement == WindowPlacement.Floating
                val shadowed = rounded
                // 打底：透明窗口下「没画到的地方 = 桌面」，这里补一层不透明底，
                // 书架 Scaffold / 阅读面各自的面板照常盖在上面（四角仍在裁切外）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (shadowed) WindowShadowMargin else 0.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowShadow(enabled = shadowed)
                            .clip(if (rounded) RoundedCornerShape(WindowCornerRadius) else RectangleShape)
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        DesktopRoot(
                            store = store,
                            windowState = windowState,
                            awtWindow = window,
                            chrome = chrome,
                            onRequestClose = ::exitApplication,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopRoot(
    store: DesktopShelfStore,
    windowState: WindowState,
    awtWindow: java.awt.Window,
    chrome: DesktopWindowChrome,
    onRequestClose: () -> Unit,
) {
    val settingsStore = remember { ReaderSettingsStore(DesktopPaths.settingsDir.absolutePath) }
    // 双层设置（与平板同规则，共享实现）：全局 + 每书 overlay。
    val settingsPersist = remember {
        PerBookSettings(settingsStore, BookSettingsStore(DesktopPaths.settingsDir.absolutePath))
    }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(settingsStore.load()) }
    // 本书原始私有层（首次开书探针判“钉没钉过”用；随书加载，见下）。
    var bookOverlay by remember { mutableStateOf(BookSettings.EMPTY) }
    var customs by remember { mutableStateOf(emptyList<ThemePreset>()) }
    var openBook by remember { mutableStateOf<ShelfBook?>(null) }
    // DB 打开是挂起调用：首帧组合先于 LaunchedEffect 执行，直接挂 App 会让
    // ShelfScreen 首帧即调 books() 撞上 requireDb()（DesktopShelfStore 未 load）。
    // 以 ready 门控首帧，未就绪只画加载占位。
    var ready by remember { mutableStateOf(false) }
    // 自绘标题栏的两个瞬时状态（平台层·桌面壳）：阅读栏显隐回抛 / 指针是否压在窗口顶部。
    var readingBarsVisible by remember { mutableStateOf(false) }
    var topHover by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.load()
        // 启动一次性系统字体检测（方案 §2.2）：只比族名指纹，变才进共享池 45 档扫；
        // 面板打开时同键提档到 5（见 ReaderView 的可见性驱动）。开书路径零字体任务。
        runCatching { DesktopFontScan.scanner(store.fontLibrary()).detectOnStart() }
        customs = store.loadThemes()
        ready = true
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载书库…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    fun persistSettings(next: ReaderSettings, bookOnly: Boolean = false) {
        settings = next
        val bookId = openBook?.id
        scope.launch(Dispatchers.IO) { settingsPersist.persist(bookId, next, bookOnly) }
    }

    // 开书即套本书 overlay（无私设即纯全局）；回书架回到全局。
    LaunchedEffect(openBook) {
        // 换书/回书架即清标题栏的两个瞬时状态：阅读栏回抛与悬停都不会跨书残留
        //（否则回书架后 readingBarsVisible 还是旧书的 true，阅读模式下条不隐藏）。
        readingBarsVisible = false
        topHover = false
        val b = openBook
        settings = withContext(Dispatchers.IO) { settingsPersist.effectiveFor(b?.id) }
        bookOverlay = withContext(Dispatchers.IO) { settingsPersist.overlayFor(b?.id) }
    }

    // ---- 自绘标题栏编排（平台层·桌面壳） ----
    // 书架模式：常亮实底条（占正常布局高度）；
    // 阅读模式：默认隐藏；鼠标压到窗口顶部 → 滑入且**不画底色**（内容透出来，Apple Reader 观感），
    //           移开 → 滑出；阅读顶栏切出/收起时同拍滑入/滑出（与 ReaderBars 同长 ChromeAnimMs）。
    val reading = openBook != null
    val stripVisible = !reading || readingBarsVisible || topHover
    val hoverZonePx = with(LocalDensity.current) { (TitleBarHeight + 8.dp).toPx() }
    // 阅读模式把标题栏高度当顶栏 inset 传给阅读面：窗口标题栏区（Win/Linux 自绘按钮区、
    // macOS 系统红绿灯区）与阅读顶栏拼成一条整栏，而不是两块互相盖住。同一时长动画，避免错位。
    // 例外：macOS 全屏 Space 里系统自己收掉了标题栏（悬停才浮出），壳不再预留这一截，
    // 否则阅读顶栏上方会多出一截空白深色横条（顶栏底色但无内容）。Win/Linux 全屏时自绘按钮仍在，
    // 照旧预留。注意只有阅读顶栏真正滑出时（stripVisible）才预留，平时阅读面顶到窗口顶。
    val reserveSystemTitleBar = reading && stripVisible && !(IS_MAC && chrome.fullscreen)
    val readerTopInset by animateDpAsState(
        targetValue = if (reserveSystemTitleBar) TitleBarHeight else 0.dp,
        animationSpec = tween(ChromeAnimMs),
        label = "desktopTitleBarInset",
    )

    // macOS 系统红绿灯（平台层·桌面壳）：Apple Books 式——阅读界面平时隐藏，鼠标移到窗口顶部
    //（条滑出）才浮出；回书架 / 阅读栏切出时照常显示。全屏 Space 里系统本就收掉标题栏，这里也
    // 一致隐藏。非 macOS 是 no-op（Win/Linux 用自绘按钮，见 DesktopTitleBar）。系统在进出全屏 /
    // 窗口重显时会自己改回去，靠 DesktopWindowChrome.attach 每帧补一次纠回来。
    LaunchedEffect(reading, stripVisible) {
        chrome.setMacTitleBarHidden(reading && !stripVisible)
    }

    Box(Modifier.fillMaxSize()) {
        if (openBook == null) {
            Column(Modifier.fillMaxSize()) {
                DesktopTitleBar(
                    window = awtWindow,
                    windowState = windowState,
                    opaque = true,
                    glyph = ChromeSurfaceFg,
                    onRequestClose = onRequestClose,
                    fullscreen = chrome.fullscreen,
                    onToggleFullscreen = chrome::toggleFullscreen,
                )
                App(
                    shelfRepository = store,
                    shelfHost = remember(store) { DesktopShelfHost(store) },
                    initialSort = runCatching {
                        ShelfSort.valueOf(store.loadPref(PREF_SORT, ShelfSort.Added.name))
                    }.getOrDefault(ShelfSort.Added),
                    initialView = runCatching {
                        ShelfView.valueOf(store.loadPref(PREF_VIEW, ShelfView.Grid.name))
                    }.getOrDefault(ShelfView.Grid),
                    onPersistSort = { store.savePref(PREF_SORT, it.name) },
                    onPersistView = { store.savePref(PREF_VIEW, it.name) },
                    onOpenBook = { openBook = it },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val book = openBook!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 悬停侦测：指针压到窗口顶部（标题栏高度 + 8dp 余量）即显示，离开窗口即收。
                    // 只做判定不消费事件，阅读面手势照常。
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val y = event.changes.firstOrNull()?.position?.y
                        topHover = y != null && y <= hoverZonePx
                    }
                    .onPointerEvent(PointerEventType.Exit) { topHover = false },
            ) {
                ReaderView(
                    book = book,
                    store = store,
                    settings = settings,
                    bookOverlay = bookOverlay,
                    customs = customs,
                    onBack = { openBook = null },
                    onSettingsChange = { persistSettings(it) },
                    onCommitBookPrivate = { persistSettings(it, bookOnly = true) },
                    onSaveTheme = { preset ->
                        scope.launch {
                            store.saveTheme(preset)
                            customs = store.loadThemes()
                        }
                    },
                    onDeleteTheme = { preset ->
                        scope.launch {
                            store.deleteTheme(preset)
                            customs = store.loadThemes()
                        }
                    },
                    statusBarInset = readerTopInset,
                    onBarsVisibleChanged = { readingBarsVisible = it },
                )
                // 悬浮标题栏：与阅读栏同拍滑入/滑出；不画底色，内容从下面透出来。
                AnimatedVisibility(
                    visible = stripVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically(tween(ChromeAnimMs)) { -it } + fadeIn(tween(ChromeAnimMs)),
                    exit = slideOutVertically(tween(ChromeAnimMs)) { -it } + fadeOut(tween(ChromeAnimMs)),
                ) {
                    DesktopTitleBar(
                        window = awtWindow,
                        windowState = windowState,
                        opaque = false,
                        // 叠在阅读顶栏上（深底）用浅色；只叠在页面上时按昼夜主题取色，
                        // 否则白天页面上浅色按钮等于隐形。
                        glyph = if (readingBarsVisible || settings.scheme == "night") {
                            Color(0xFFF5F5F5)
                        } else {
                            Color(0xFF303030)
                        },
                        onRequestClose = onRequestClose,
                        fullscreen = chrome.fullscreen,
                        onToggleFullscreen = chrome::toggleFullscreen,
                    )
                }
            }
        }
    }
}
