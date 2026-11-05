@file:OptIn(ExperimentalComposeUiApi::class)

package orilumn.reader.desktop

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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import java.awt.Dimension
import java.io.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PREF_SORT = "shelf_sort_name"
private const val PREF_VIEW = "shelf_view"
private const val PREF_WIN_W = "window_width_dp"
private const val PREF_WIN_H = "window_height_dp"
private const val PREF_WIN_X = "window_x_dp"
private const val PREF_WIN_Y = "window_y_dp"

/**
 * 设置提交序号（平台层·桌面壳）：`Mutex` 只保证互斥，不保证「先提交的先拿锁」——
 * 两个协程被不同 IO 线程取走后可能反序到达锁，后落盘的旧快照会把新改动顶掉。
 * 序号在提交时（UI 线程）分配，锁内比较；晚到的旧快照直接丢弃。
 */
private class SettingsPersistOrder {
    private var lastSubmitted = 0L
    private var lastApplied = 0L

    /** 提交时分配（仅 UI 线程调用）。 */
    fun next(): Long = ++lastSubmitted

    /** 锁内判定：该序号是否仍然是最新提交。 */
    fun shouldApply(seq: Long): Boolean = seq > lastApplied

    /** 锁内登记已落盘。 */
    fun markApplied(seq: Long) {
        lastApplied = seq
    }
}

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
 * 「启动时继续阅读」要打开的那一本（平台层·桌面壳）：读过、且文件还在的书里，
 * 取最近一次打开的那本；一本都没有（全没读过 / 文件都被删了）返回 null，调用方留在书架。
 *
 * 判据只看**书架记录**，不另存「上次那本」：删书即从书架消失，不会留下指不到任何书的存档。
 *
 * @param fileExists 书文件是否还在（默认查真实文件系统；测试注入以便构造场景）
 */
internal fun lastReadBook(
    books: List<ShelfBook>,
    fileExists: (String) -> Boolean = { File(it).isFile },
): ShelfBook? = books
    .filter { it.readTime != null && fileExists(it.filePath) }
    .maxByOrNull { it.readTime ?: 0L }

/**
 * S32 桌面最小壳：JVM `main` 起窗口挂 shared-ui。
 *
 * 薄壳契约（与 S31 Android 壳同口径）：
 * - 只做真系统级：数据根初始化、窗口管理、导航状态；
 * - 书架（列表/导入/mpfilepicker/Snackbar）走 [App]；
 * - 阅读（画布/手势/进度/遮罩）走 [ReaderView] + [DesktopReaderHost]（首个真实 Skia 管线）；
 * - 启动参数给路径即开书（与书架比对后打开/覆盖导入，见 [DesktopShelfHost.openFromPath]）；
 * - 设置（全局 `reader.json` + 自定义主题预设持久化）走 [ReaderView] 内的设置面板。
 */
fun main(args: Array<String>) {
    DesktopPaths.ensure()
    // 启动参数开书（平台层·桌面壳）：取第一个真实存在的非选项文件参数当作要打开的书
    //（Gradle `run --args="<epub路径>"` 与文件管理器双击给参同口径）；没给就进书架。
    val startupPath = args.firstOrNull {
        !it.startsWith("-") && runCatching { File(it).isFile }.getOrDefault(false)
    }
    application {
        // 三端同一 JVM 壳：窗口尺寸+位置经 prefs 持久化，下次启动原地恢复
        // （Win/macOS/Linux 同口径；显示器变更致越界时回居中，不开到屏外）。
        val store = remember { DesktopShelfStore(DesktopPaths.root) }
        // 窗口底子（平台层·桌面壳，Win/Linux）：**不透明 + 直角**。
        //
        // 早先这里是「逐像素半透明 + 内容自绘投影」。半透明窗口在**没有硬件加速**的环境里
        // （虚拟机 / XWayland / 4K 高 DPI）贴屏只能走 CPU 逐像素合成：同一扇 1600×1800 窗口、
        // 同一份内容实测 193–2216 ms/帧（一次拖动只出 7 帧），而内容重排本身只要 10–60 ms。
        // 于是读者看到的是「窗口先拉出一条黑边、正文要等一两秒才跟上」，拉左边时旧画面整体
        // 右移（X11 把已有内容钉在左上角）更像「拉左边右边跟着动」。不透明窗口同一场景
        // 54–130 ms/帧（17 帧）。故底子改回**不透明**：尺寸跟手、新露出的区域立刻被内容填上。
        //
        // **不要再改用 `Window.setShape` 裁圆角**（试过，已撤）：XWayland 下窗口形状与缓冲区
        // 尺寸不同步，而形状原先跟 `windowState.size` 每变一帧就重设一次 —— 缩小窗口后旧窗口
        // 矩形那块残留上一帧的画面、合成器在两份缓冲之间来回贴，肉眼就是「原位残留 + 快速频闪」。
        // 圆角纯属观感，不值得拿这个换；Win/Linux 取直角，用内容边缘一条极细描边代偿边界感，
        // 没有系统投影也就没有别的代价。macOS 走系统窗口，圆角/投影/全屏全由系统给，不走这里。
        // 窗口 = 内容（不再有投影留边）：prefs 里存的就是内容尺寸与内容左上角，直接当窗口用。
        // 旧存档同理——它存的本来就是内容口径，升级后可见区域大小与位置原地不变。
        fun contentDp(key: String, def: Int, lo: Int, hi: Int): Float =
            (store.loadPref(key, def.toString()).toIntOrNull() ?: def).coerceIn(lo, hi).toFloat()
        // 默认/钳制下限同「最小窗口」口径（下方 `minimumSize` 一处定，两处引用同一常量）：
        // 550×400 是桌面阅读的舒服尺寸（小屏/高分屏上不至于占满），240×300 是能读的版心下限
        // （版心 = 内容尺寸 − 上100/下60/左右50dp 的页边距；再小就一行字都放不下）。
        val windowState = rememberWindowState(
            width = contentDp(PREF_WIN_W, 550, MinContentW, 2560).dp,
            height = contentDp(PREF_WIN_H, 400, MinContentH, 1600).dp,
            position = remember {
                val x = store.loadPref(PREF_WIN_X, "").toIntOrNull()
                val y = store.loadPref(PREF_WIN_Y, "").toIntOrNull()
                if (x != null && y != null && onAnyScreen(x, y)) {
                    androidx.compose.ui.window.WindowPosition.Absolute(x.dp, y.dp)
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
        LaunchedEffect(windowState, chrome) {
            snapshotFlow {
                Triple(chrome.fullscreen, windowState.size, windowState.position)
            }
                .debounce(500)
                .collect { (fullscreen, size, pos) ->
                    // 只记**浮动**几何：全屏时 size 已是整屏、最大化时是工作区，都不是用户调出来的
                    // 窗口大小/位置，落盘会把用户原来的浮动几何冲掉。
                    if (fullscreen || windowState.placement != WindowPlacement.Floating) return@collect
                    // 窗口 = 内容，直接存，不做任何换算。
                    withContext(Dispatchers.IO) {
                        store.savePref(PREF_WIN_W, size.width.value.toInt().toString())
                        store.savePref(PREF_WIN_H, size.height.value.toInt().toString())
                        (pos as? androidx.compose.ui.window.WindowPosition.Absolute)?.let {
                            store.savePref(PREF_WIN_X, it.x.value.toInt().toString())
                            store.savePref(PREF_WIN_Y, it.y.value.toInt().toString())
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
            // 窗口装饰（平台层·桌面壳）：Win/Linux 无边框 + 自绘标题栏（原生装饰三件事
            // 都做不到——阅读模式下隐藏、跟阅读顶栏一起滑入滑出、自绘窗口控制区）；
            // macOS 保留系统装饰换原生圆角/阴影/resize 热区/全屏 Space——透明标题栏 + 全窗内容
            // 的无边框观感（见 WindowChrome.applyMacSystemWindow），自绘那套在 mac 上进不了
            // 真全屏（AppKit 拒绝给透明窗口开全屏 Space）。
            //
            // 无边框窗口没有系统缩放热区，CMP 只能自绘，且恒定贴在 **AWT 窗口边缘**往内
            // `resizerThickness`。窗口现在 = 内容（不再有投影留边），热区就贴真实窗口边缘，
            // 与视觉边界重合；默认 8dp 是 CMP 自己的手感值，Windows 系统的边框热区也是这个量级。
            decoration = if (IS_MAC) {
                WindowDecoration.SystemDefault
            } else {
                WindowDecoration.Undecorated(8.dp)
            },
            // 不透明（见上面窗口底子的注释）：半透明窗口在这类无加速环境下贴屏要 CPU 逐像素
            // 合成，拉窗 0.2–2 秒一帧。圆角也不再走窗口形状——那会在缩小窗口后留下原位残影与频闪。
            transparent = false,
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
            // 最小窗口尺寸兜底：防止拖到比版心下限还小导致 contentH ≤ 0。
            // 取值见 [MinContentW]/[MinContentH]（与上方读存档的钳制下限同源）。
            val densityMin = LocalDensity.current
            val awtWin = window as? java.awt.Window
            SideEffect {
                val w = awtWin ?: return@SideEffect
                with(densityMin) {
                    val minW = MinContentW.dp.roundToPx()
                    val minH = MinContentH.dp.roundToPx()
                    if (w.minimumSize.width != minW || w.minimumSize.height != minH) {
                        w.minimumSize = Dimension(minW, minH)
                    }
                }
            }
            MaterialTheme {
                // 窗口底子（Windows/Linux 自绘壳）：不透明 + 直角；macOS 是系统窗口，圆角由系统给。
                // 浮动时留一条极细描边代偿没有系统投影的边界感（最大化/全屏贴边，不需要）。
                val bordered = !IS_MAC && !chrome.fullscreen &&
                    windowState.placement == WindowPlacement.Floating
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .then(
                            if (bordered) {
                                Modifier.border(1.dp, Color(0x14000000), RectangleShape)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 新露出来的那条区域交给 X11 立刻填成页面底色（而不是默认的黑）：窗口被拉大
                    // 的那一瞬间，这一条还没被任何一帧画到，读者看到的就是「先拉出一条黑线」。
                    // 给窗口和它的绘制面都设上同一个不透明底色，X11 在 expose 时按它填充 —— 于是
                    // 就算这一帧还没画完，露出来的也是纸色，不是黑色。
                    val pageBg = MaterialTheme.colorScheme.background
                    SideEffect {
                        val w = awtWin ?: return@SideEffect
                        val fill = java.awt.Color(pageBg.toArgb(), true)
                        if (w.background != fill) w.background = fill
                        for (c in w.components) {
                            if (c.background != fill) c.background = fill
                        }
                    }
                    DesktopRoot(
                        store = store,
                        windowState = windowState,
                        awtWindow = window,
                        chrome = chrome,
                        onRequestClose = ::exitApplication,
                        startupPath = startupPath,
                    )
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
    startupPath: String?,
) {
    val settingsStore = remember { ReaderSettingsStore(DesktopPaths.settingsDir.absolutePath) }
    // 双层设置（与平板同规则，共享实现）：全局 + 每书 overlay。
    val settingsPersist = remember {
        PerBookSettings(settingsStore, BookSettingsStore(DesktopPaths.settingsDir.absolutePath))
    }
    // 设置写入串行锁（与平板同口径）：并发写会互相覆盖，后落盘的旧快照把新改动顶掉。
    val settingsPersistMutex = remember { Mutex() }
    // 提交序号：Mutex 只保证互斥，不保证「先提交的先拿锁」；晚到的旧快照必须在锁内丢弃。
    val settingsPersistOrder = remember { SettingsPersistOrder() }
    val scope = rememberCoroutineScope()
    // 书架宿主单实例：启动开书（下方 LaunchedEffect）与书架界面走同一个（导入进度一致）。
    val shelfHost = remember(store) { DesktopShelfHost(store) }

    var settings by remember { mutableStateOf(settingsStore.load()) }
    var customs by remember { mutableStateOf(emptyList<ThemePreset>()) }
    var openBook by remember { mutableStateOf<ShelfBook?>(null) }
    // DB 打开是挂起调用：首帧组合先于 LaunchedEffect 执行，直接挂 App 会让
    // ShelfScreen 首帧即调 books() 撞上 requireDb()（DesktopShelfStore 未 load）。
    // 以 ready 门控首帧，未就绪只画加载占位。
    var ready by remember { mutableStateOf(false) }
    // 自绘标题栏的三个瞬时状态（平台层·桌面壳）：阅读栏显隐回抛 / 指针是否压在窗口顶部 /
    // 设置面板是否打开（面板贴顶从右侧滑出，Win/Linux 的窗口按钮会让位，见 stripVisible）。
    var readingBarsVisible by remember { mutableStateOf(false) }
    var topHover by remember { mutableStateOf(false) }
    var settingsPanelOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.load()
        // 启动一次性系统字体检测（方案 §2.2）：只比族名指纹，变才进共享池 45 档扫；
        // 面板打开时同键提档到 5（见 ReaderView 的可见性驱动）。开书路径零字体任务。
        runCatching { DesktopFontScan.scanner(store.fontLibrary()).detectOnStart() }
        customs = store.loadThemes()
        // 启动参数开书（平台层·桌面壳）：放在就绪前，导入期间维持加载占位，就绪即直达
        // 阅读面（不闪书架）；失败留书架，日志留痕。
        val fromArgs = startupPath?.let { path ->
            val entry = runCatching { shelfHost.openFromPath(path) }.getOrNull()
            if (entry == null) orilumn.reader.io.Logger.w("Orilumn.Shelf", "startup open FAIL $path")
            entry
        }
        // 「启动时继续阅读」（平台层·桌面壳）：与平板同口径——开关打开且书库里有读过记录，
        // 启动即直达上次读的那本（定位/续读位由阅读面自己按存档恢复），不先闪一下书架。
        // 启动参数给了书就以参数为准（双击文件打开不该被上次那本顶掉）；
        // 没有读过记录、或开关关着，照旧停在书架。文件被删掉的那本不算（读不了，别开进去）。
        val lastRead = if (fromArgs == null && startupPath == null && settings.autoContinue) {
            runCatching { lastReadBook(store.allBooks()) }.getOrNull()
        } else {
            null
        }
        openBook = fromArgs ?: lastRead
        if (lastRead != null) {
            orilumn.reader.io.Logger.i(
                "Orilumn.Shelf",
                "auto-continue last book id=${lastRead.id} title=${lastRead.title}",
            )
        }
        ready = true
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载书库…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    fun persistSettings(next: ReaderSettings) {
        settings = next
        val seq = settingsPersistOrder.next()
        val bookId = openBook?.id
        scope.launch(Dispatchers.IO) {
            settingsPersistMutex.withLock {
                if (!settingsPersistOrder.shouldApply(seq)) return@withLock
                settingsPersist.persist(bookId, next)
                settingsPersistOrder.markApplied(seq)
            }
        }
    }

    /**
     * 当前 `openBook` 的生效设置是否已落定（值为书 id）。
     *
     * **必须先等它再建阅读宿主**：宿主按 `settings` 算 `paramHash`（分页缓存键）。若先用全局设置
     * 建一遍、再把本书 overlay 换进来，就会按全局键查表（查不到）→ 整章重排一次 → overlay 到齐
     * 后再整章重排一次，且磁盘表是按 overlay 的键写的，下次开书照样查不到（永久双排）。
     * 这里只多等一次本地设置读取（毫秒级），换开书直接命中磁盘表。
     */
    var readerSettingsBook by remember { mutableStateOf<Long?>(null) }

    // 开书即套本书 overlay（无私设即纯全局）；回书架回到全局。
    LaunchedEffect(openBook) {
        // 换书/回书架即清标题栏的瞬时状态：阅读栏回抛、悬停与设置面板都不会跨书残留
        //（否则回书架后 readingBarsVisible 还是旧书的 true，阅读模式下条不隐藏）。
        readingBarsVisible = false
        topHover = false
        settingsPanelOpen = false
        val b = openBook
        settings = withContext(Dispatchers.IO) { settingsPersist.effectiveFor(b?.id) }
        readerSettingsBook = b?.id
    }

    // ---- 自绘标题栏编排（平台层·桌面壳） ----
    // 书架模式：常亮实底条（占正常布局高度）；
    // 阅读模式：默认隐藏；鼠标压到窗口顶部 → 滑入且**不画底色**（内容透出来，Apple Reader 观感），
    //           移开 → 滑出；阅读顶栏切出/收起时同拍滑入/滑出（与 ReaderBars 同长 ChromeAnimMs）。
    val reading = openBook != null
    // 设置面板从**右侧贴顶**滑出（见 ReaderSettingsPanel），与 Win/Linux 自绘窗口按钮（右上角）
    // 抢同一块地方：面板打开期间不再因悬停顶部滑出标题栏，否则面板一开、鼠标还在顶部，
    // 最小化/最大化/全屏/关闭就压在面板标题行上（判据见 [titleBarStripVisible]）。
    val stripVisible = titleBarStripVisible(
        reading = reading,
        readingBarsVisible = readingBarsVisible,
        topHover = topHover,
        settingsPanelOpen = settingsPanelOpen,
    )
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
                    shelfHost = shelfHost,
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
        } else if (readerSettingsBook != openBook!!.id) {
            // 生效设置还没到齐（见 readerSettingsBook 注释）：先占位，别用全局设置建宿主。
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载…", style = MaterialTheme.typography.bodyLarge)
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
                    customs = customs,
                    onBack = { openBook = null },
                    onSettingsChange = { persistSettings(it) },
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
                    onSettingsPanelOpenChanged = { settingsPanelOpen = it },
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
