package orilumn.reader.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import orilumn.reader.data.book.BookRepository
import orilumn.reader.host.AndroidDb
import orilumn.reader.data.font.FontRepository
import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.epub.ZipEpubResourceReader
import orilumn.reader.data.settings.BookSettings
import orilumn.reader.data.settings.BookSettingsStore
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.ReaderSettingsStore
import orilumn.reader.engine.BookDocumentController
import orilumn.reader.engine.BookFileResolver
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.SystemCjkSerif
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.render.DebugDraw
import orilumn.reader.engine.skia.SkiaFontPool
import orilumn.reader.ui.theme.OrilumnTheme
import orilumn.reader.io.AppRoot
import orilumn.reader.io.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * Q1-b：阅读窗口 Compose 薄壳 — 把 legacy `ReaderActivity`（BodyPageView + CurlView + FlipGestureDetector
 * 的 own 渲染/手势管线）重写为 shared-ui [ReaderScreen] 的 Android 装配层：
 *
 *  - 宿主：[TabletReaderHost] + [SnapshotReaderHost]（定位回抛）接入 [ReaderScreen]，画布/手势/亮度
 *    遮罩/上下栏全走 shared-ui（引擎三路 skia 行窗口同源，见 TabletReaderHost.doc）；
 *  - 覆盖层：保留 Android 侧 [AndroidReaderSettingsPanel]/[AndroidReaderTocPanel]（抽屉 + 目录，
 *    亮度/护眼/夜间/排版主题等回调语义与 legacy 一致）；
 *  - 环境：保留系统栏沉浸（show/hide chrome + WindowInsetsAnimationCompat 逐帧 statusInset）、
 *    Room 设置持久化（fork-on-first-customization + withBookStyle）、版式重排节流
 *    （deferCanonical / prepareRelayout 循环 / 关面板后 finalizeRelayoutAll 全书重排）；
 *  - 移除：BodyPageView/curl/FlipGestureDetector/AndroidReaderBars/BrightnessOverlayView 与
 *    亮度手势指示（shared-ui [BrightnessGestureIndicator] 已含，本文件不再重复声明）。
 */
@SuppressLint("ClickableViewAccessibility")
class ReaderActivity : ComponentActivity() {

    private var bookPath: String? = null
    private var bookId: Long = -1L

    private lateinit var repository: BookRepository
    private lateinit var settingsStore: ReaderSettingsStore
    private lateinit var bookSettingsStore: BookSettingsStore
    private lateinit var fontRepository: FontRepository

    /** 当前生效设置（响应 Compose 重排；初始 = 全局 + 本书私有 overlay）。 */
    private var effective by mutableStateOf(ReaderSettings.DEFAULT)

    /** 引擎持有的版式（重排/探测共用；liveApply 时同步，构图内不直接读）。 */
    private var profile: TypographicProfile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 引擎单实例：打开成功后设置一次；`null` = 加载中/失败。 */
    private var engine by mutableStateOf<BookDocumentController?>(null)

    /** 打开失败（展示失败文案并退出）。 */
    private var openFailed by mutableStateOf(false)

    /** Android 侧宿主（[TabletReaderHost]；TOC 深锚跳转与退出收口直连）。 */
    private var tabletHost by mutableStateOf<TabletReaderHost?>(null)

    /** 当前定位（SnapshotReaderHost 回抛；目录高亮 / 重排锚点 / 退出保存）。 */
    private var currentPos by mutableStateOf<ReaderPos?>(null)

    /** 外部推送落位：版式重排绑定 / 目录跳转落地后置位，[ReaderScreen] 键变化即认领。 */
    private var externalPos by mutableStateOf<ReaderPos?>(null)

    /**
     * 版式版本号：每次重排绑定（整章/锚点临时）+1。字体等“只换字形不断行”的变更分页不变、
     * 新旧 pos 相等，[ReaderScreen] 的 `remember(pos)` 行缓存不会重取，必须用本号强制刷新。
     */
    private var layoutRevision by mutableStateOf(0)

    private var settingsOpen by mutableStateOf(false)
    private var tocOpen by mutableStateOf(false)
    private var panelSettings by mutableStateOf(ReaderSettings.DEFAULT)

    /** 面板打开时的排版指纹；关闭时变化 → 全书 canonical 重排。 */
    private var panelBaseHash = 0L

    /** 顶/底栏显隐（[ReaderScreen] 经 onBarsVisibleChanged 回抛，驱动系统栏 chrome）。 */
    private var barsVisible by mutableStateOf(false)

    private var debugOverlayOn by mutableStateOf(DebugDraw.enabled)

    /** 版式重排任务状态（固定周期节流）。 */
    private var relayoutPending = false
    private var relayoutScheduled = false
    private var relayoutJob: Job? = null

    /** 当前系统状态栏顶 inset（物理 px，随显隐动画逐帧更新）。 */
    private val statusInsetTopPx = mutableIntStateOf(0)

    /** 当前设备 dp→物理像素缩放（density）。 */
    private val dpDensity: Float get() = resources.displayMetrics.density

    // ---- onCreate ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志根改由 AppRoot 注入（幂等、只首次生效；直启阅读页时 MainActivity 可能没跑过，此处兜底）。
        AppRoot.init(filesDir.absolutePath.toPath(), FileSystem.SYSTEM)
        bookPath = intent?.getStringExtra(EXTRA_BOOK_PATH)
        bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        repository = AndroidDb.repository(this)
        // 直启阅读页时同样确保 Room 书目/进度/字体已导入（通常书架已做，此处为兜底）。
        lifecycleScope.launch(Dispatchers.IO) {
            AndroidDb.importBooksFromRoomIfNeeded(this@ReaderActivity)
            AndroidDb.importProgressFromRoomIfNeeded(this@ReaderActivity)
            AndroidDb.importFontsFromRoomIfNeeded(this@ReaderActivity)
        }
        settingsStore = ReaderSettingsStore(File(filesDir, "settings").absolutePath)
        bookSettingsStore = BookSettingsStore(File(filesDir, "settings").absolutePath)
        fontRepository = FontRepository(this, AndroidDb.library(this))

        effective = settingsStore.load()
        if (bookId >= 0) {
            effective = effective.applyOverlay(bookSettingsStore.load(bookId))
        }
        profile = TypographicProfile.build(effective, dpDensity)
        Logger.w(TAG, "★ ReaderActivity onCreate path=${bookPath != null} bookId=$bookId")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBars()
        // 移除系统手势导航底线：禁用导航/状态栏对比度强制（否则部分 ROM 划线/铺底），配下面的 nav 隐藏，按 API 级别守卫。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

        // 系统栏显隐动画的逐帧回调：把状态栏 inset 写进状态，顶栏 translationY 与状态栏逐帧对齐。
        ViewCompat.setWindowInsetsAnimationCallback(
            window.decorView,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    statusInsetTopPx.intValue = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    return insets
                }
            },
        )

        setContent {
            OrilumnTheme {
                ReaderShell()
            }
        }
    }

    /** 打开书籍并构建引擎单实例（复用 legacy openBook 语义：resolve → reader → layouter →
     *  controller + setViewport；open()/locateStart() 由 [TabletReaderHost.open] 在 ReaderScreen 内收口）。 */
    private suspend fun openBookEngine(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val loaded: BookDocumentController? = withContext(Dispatchers.IO) {
            runCatching {
                // C2-P2b-3: 引擎诊断总闸（was EngineLog.enabled = BuildConfig.DEBUG）。
                orilumn.reader.engine.EngineDiag.enabled = orilumn.reader.BuildConfig.DEBUG
                // 用户字库先进共用 skia 集合（整形与绘制同一实例；否则首排按系统字体断行，
                // 绘制切内嵌字体即全书错位）。IO 内同步等，不与首排抢跑。
                // P2-b: 新书清掉旧书内字体条目（池刷新只含本书）。
                bookFontEntries = emptyList()
                // F4c: 系统族落行（INSERT OR IGNORE，开屏一次；面板开页再同步一次）。
                // 中文名链（桌面同式方案B）：name 表直读优先，无本地化名的族展示层回退族名本身。
                runCatching {
                    fontRepository.syncSystemFaces(
                        orilumn.reader.engine.skia.systemFontFaces(),
                        fontRepository.systemFontLocalizedNames(),
                    )
                }
                refreshSkiaFonts()
                val file = BookFileResolver(this@ReaderActivity).resolve(bookPath ?: return@runCatching null)
                    ?: return@runCatching null
                val reader = ZipEpubResourceReader(file.absolutePath)
                val imageLoader = orilumn.reader.engine.ImageLoader(reader)
                val c = BookDocumentController(
                    reader,
                    orilumn.reader.engine.BoxChapterLayouter(imageLoader = imageLoader),
                    profile,
                    TAG,
                    lifecycleScope,
                    imageLoader = imageLoader,
                )
                c.cacheRoot = this@ReaderActivity.cacheDir.absolutePath.toPath()
                c.setViewport(w, h)
                // 章字体需求回调：整形前把本章命中的导入面追装进池（首绘即对，无跳变）。
                c.onDemandFonts = { demand -> topUpSkiaFonts(demand) }
                // P2-b: 书内字体回调：整形前把本章 @font-face 字节并入池（首绘即对）。
                c.onBookFonts = { fonts -> syncBookFonts(fonts) }
                // Q1 遗留接线补齐：宿主在此装配（此前从未构造，ReaderScreen 恒取空快照 → 白屏）。
                tabletHost = TabletReaderHost(c, bookId, repository)
                c
            }.getOrNull()
        }
        if (loaded != null) {
            engine = loaded
            Logger.w(TAG, "open ok chapters=${loaded.chapterCount}")
        } else {
            openFailed = true
            Logger.w(TAG, "打开失败")
            finish()
        }
    }

    /** 引擎构建 + [ReaderScreen] 装配的 Compose 壳。 */
    @Composable
    private fun ReaderShell() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current.density
            val pxW = with(LocalDensity.current) { maxWidth.toPx().roundToInt() }
            val pxH = with(LocalDensity.current) { maxHeight.toPx().roundToInt() }
            val bgTone = remember(effective, density) { TypographicProfile.build(effective, density).bgColor }

            // 打开书籍并构建引擎（一次；视口就绪后触发）。
            LaunchedEffect(pxW, pxH) {
                if (engine == null && !openFailed) openBookEngine(pxW, pxH)
            }

            if (openFailed) {
                Text(
                    text = "无法打开书籍",
                    color = ComposeColor(0xFF999999),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@BoxWithConstraints
            }

            val snapshot = remember(tabletHost) {
                tabletHost?.let { SnapshotReaderHost(it) { pos -> currentPos = pos } }
            }
            if (snapshot != null) {
                ReaderScreen(
                    host = snapshot,
                    settings = effective,
                    statusBarInset = with(LocalDensity.current) { (statusInsetTopPx.intValue / density).dp },
                    debugActive = debugOverlayOn,
                    onBack = { finish() },
                    onNight = {
                        commitSettings(effective.copy(scheme = if (effective.scheme == "night") "day" else "night"), typographyChanged = true)
                    },
                    onSettings = { openSettingsPanel() },
                    onToc = { openTocPanel() },
                    onDebug = {
                        DebugDraw.enabled = !DebugDraw.enabled
                        debugOverlayOn = DebugDraw.enabled
                        Logger.d(TAG, if (DebugDraw.enabled) "调试线框：显示" else "调试线框：关闭")
                    },
                    onLightChange = { applyPhysicalBrightness(it) },
                    onLightCommit = { commitBrightness(it) },
                    onBarsVisibleChanged = { barsVisible = it; applySystemBars() },
                    externalPos = externalPos,
                    contentRevision = layoutRevision,
                )
            } else {
                // 加载背页（打开的背色与正文一致）。
                Box(Modifier.fillMaxSize().background(ComposeColor(bgTone)))
            }

            currentPos?.let { p ->
                AndroidReaderTocPanel(
                    visible = tocOpen,
                    toc = engine?.toc() ?: emptyList(),
                    currentChapter = p.chapter,
                    scheme = effective.scheme,
                    currentFragments = remember(p) {
                        engine?.currentPageFragmentIds(p.chapter, p.slice.charStart, p.slice.charEnd) ?: emptySet()
                    },
                    onSelect = { item -> tocJump(item) },
                    onDismiss = { closeTocPanel() },
                )
            } ?: AndroidReaderTocPanel(
                visible = tocOpen,
                toc = engine?.toc() ?: emptyList(),
                currentChapter = 0,
                scheme = effective.scheme,
                onSelect = { item -> tocJump(item) },
                onDismiss = { closeTocPanel() },
            )

            AndroidReaderSettingsPanel(
                visible = settingsOpen,
                settings = panelSettings,
                fontRepository = fontRepository,
                onDismiss = { closeSettingsPanel() },
                onPreview = { previewLive(it) },
                onCommitTypography = { commitSettings(it, typographyChanged = true) },
                onCommitBookPrivate = { commitSettings(it.withBookStyle(), typographyChanged = true, bookOnly = true) },
                onCommitLight = { commitSettings(it, typographyChanged = false) },
                onFontsChanged = { refreshFontsAndRelayout() },
            )
        }
    }

    // ---- Settings panel ----

    private fun openSettingsPanel() {
        tocOpen = false
        panelSettings = effective
        settingsOpen = true
        applySystemBars()
        // 面板开时抑制后台 canonical 全章重排（避免与前台实时 temp 塑形抢 CPU）；关闭时若排版真实
        // 变化再整书重跑（捕获指纹判定）。
        engine?.deferCanonical = true
        panelBaseHash = typographHash()
        Logger.w(TAG, "open settings panel")
    }

    private fun closeSettingsPanel() {
        settingsOpen = false
        applySystemBars()
        val c = engine
        c?.deferCanonical = false
        if (c != null && typographHash() != panelBaseHash) {
            Logger.w(TAG, "close settings panel -> typography changed, whole-book relayout")
            // 先停掉实时节流循环并等其当前迭代落位，避免两趟后台塑形并发作用于同一章。
            relayoutPending = false
            val liveLoop = relayoutJob
            val p = currentPos
            val ch = p?.chapter ?: 0
            val anchor = p?.slice?.charStart ?: 0
            lifecycleScope.launch(Dispatchers.Default) {
                liveLoop?.join()
                val r = c.finalizeRelayoutAll(ch, anchor)
                withContext(Dispatchers.Main) {
                    if (r != null) applyReflowResult(c, r)
                }
            }
        }
    }

    /** 排版敏感设置指纹（宽度/高度为固定输入，这里只看排版是否变化）。 */
    private fun typographHash(): Long =
        LayoutParamKey.fromProfile(engine?.profile ?: profile, 0, 0).hash()

    // ---- Table of contents ----

    private fun openTocPanel() {
        settingsOpen = false
        tocOpen = true
        applySystemBars()
        Logger.w(TAG, "open toc panel")
    }

    private fun closeTocPanel() {
        tocOpen = false
        applySystemBars()
    }

    /** 目录跳转：[item.fragment] 锚定子节自身标题页（回退章首），关抽屉后经 host 落位（finalizeOnLeave 语义）。 */
    private fun tocJump(item: TocItem) {
        closeTocPanel()
        val idx = item.index ?: return
        val host = tabletHost ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val target = host.jumpToToc(idx, item.fragment)
            withContext(Dispatchers.Main) {
                if (target != null) {
                    currentPos = target
                    externalPos = target
                }
            }
        }
    }

    // ---- Settings commit / preview ----

    /** 统一入口：liveApply 后，排版变化走节流重排，否则重应用物理背光；随后持久化。 */
    private fun commitSettings(next: ReaderSettings, typographyChanged: Boolean, bookOnly: Boolean = false) {
        liveApply(next)
        // 排版提交先刷新字库池再重排：换选已导入字体只改槽，不断行也可能同分页，
        // 池不跟进则新族名无处解析、画出来只能回退（切字体无效的根因）。
        if (typographyChanged) refreshFontsThenRelayout() else applyPhysicalBrightness(next)
        persistSettings(next, bookOnly)
    }

    /** 拖拽实时应用（不落盘）：更新 effective/profile 与引擎持有 profile。排版由调用方节流重排。 */
    private fun liveApply(next: ReaderSettings) {
        panelSettings = next
        effective = next
        profile = TypographicProfile.build(next, dpDensity)
        engine?.profile = profile
    }

    /** 面板实时预览（排版项）：应用 + 防抖重排，不落盘。 */
    private fun previewLive(next: ReaderSettings) {
        liveApply(next)
        scheduleRelayout()
    }

    /** 亮度手势提交：应用 + 物理背光 + 持久化（无重排）。 */
    private fun commitBrightness(next: ReaderSettings) {
        effective = next
        applyPhysicalBrightness(next)
        persistSettings(next)
    }

    /**
     * 持久化（每书隔离，fork-on-first-customization）：
     *  - 本书 overlay = 改变后全部每书字段的快照，一旦触碰即不再跟随后续全局变更；
     *  - 全局内存只收本次实际改动字段（相对本书基线的 diff），一本书的私有值不泄漏进共享默认；
     *  - [bookOnly]（原书设置）：只写本书私有 overlay，永不合并进全局；
     *  - 系统/全局字段（亮度/护眼/亮度手势/夜间）不入任何每书 overlay，直写全局内存。
     */
    private fun persistSettings(next: ReaderSettings, bookOnly: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val storedGlobal = settingsStore.load()
            val storedOverlay = if (bookId >= 0) bookSettingsStore.load(bookId) else BookSettings.EMPTY
            val baseline = storedGlobal.applyOverlay(storedOverlay)
            val changed = BookSettings.changedFrom(next, baseline)
            val global = if (bookOnly) {
                storedGlobal
            } else {
                storedGlobal.mergeFrom(changed)
                    .copy(
                        scheme = next.scheme,
                        brightness = next.brightness,
                        brightnessFollowSystem = next.brightnessFollowSystem,
                        brightnessOffset = next.brightnessOffset,
                        eyeProtectionLevel = next.eyeProtectionLevel,
                        brightnessGestureLeft = next.brightnessGestureLeft,
                        brightnessGestureRight = next.brightnessGestureRight,
                        brightnessGestureTwo = next.brightnessGestureTwo,
                    )
            }
            settingsStore.save(global)
            val overlay = if (bookId >= 0) {
                val o = if (changed.isEmpty) storedOverlay else BookSettings.fromReaderSettings(next)
                bookSettingsStore.save(bookId, o)
                o
            } else BookSettings.EMPTY
            Logger.d(TAG, "persist settings: changed empty=${changed.isEmpty} overlay empty=${overlay.isEmpty}")
        }
    }

    /**
     * 排版敏感设置变更 → 固定周期节流全书重排（保位）。拖拽期间只要仍有 pending 标记就每隔
     * [RELAYOUT_INTERVAL_MS] 用最新 profile 重排一次，保证滑块跟手；不做逐帧取消+重启。
     */
    private fun scheduleRelayout() {
        relayoutPending = true
        if (relayoutScheduled) return
        relayoutScheduled = true
        relayoutJob = lifecycleScope.launch(Dispatchers.Default) {
            while (relayoutPending) {
                relayoutPending = false
                val c = engine ?: break
                val p = currentPos
                val chapter = p?.chapter ?: 0
                val anchorChar = p?.slice?.charStart ?: 0
                // 后台算新版式（保留当前章旧版式不闪），主线程原子替换。
                val r = c.prepareRelayout(chapter, anchorChar)
                withContext(Dispatchers.Main) {
                    if (r != null) applyReflowResult(c, r)
                }
                if (relayoutPending) delay(RELAYOUT_INTERVAL_MS)
            }
            relayoutScheduled = false
            // 参数稳定点：节流循环结束后以最终稳定参数重派整书（B2）剩余章扫描（controller 对过期 epoch 空转）。
            engine?.requestWholeBookRelayout()
            relayoutJob = null
        }
    }

    /** 绑定重排结果并落位（版式变更收归控制器 [BookDocumentController.bindReflow]，此处只刷版本号与定位）。 */
    private fun applyReflowResult(c: BookDocumentController, r: BookDocumentController.ReflowResult) {
        if (!c.bindReflow(r)) return
        // 先 bump 版式号：行/图/背景缓存键随之失效（pos 相等时也强制重取）。
        layoutRevision++
        currentPos = ReaderPos(r.chapter, r.page)
        externalPos = ReaderPos(r.chapter, r.page)
    }

    // ---- Brightness (physical backlight; overlay drawn by ReaderScreen's ReaderLightMask) ----

    /**
     * 应用物理背光（跟随系统 = 系统% + 偏移；自定义 0..100 直写；<0 收敛到 0，剩余压暗交给遮罩）。
     * 纯绘制遮罩（压暗/护眼暖色）在 shared-ui [ReaderLightMask]，本层只动 window 背光。
     */
    private fun applyPhysicalBrightness(s: ReaderSettings) {
        val target: Float = if (s.brightnessFollowSystem) {
            (readSystemBrightnessPercent() + s.brightnessOffset).coerceIn(0f, 100f)
        } else {
            s.brightness.coerceIn(0, 100).toFloat()
        }
        window.attributes = window.attributes.apply {
            screenBrightness = target / 100f
        }
    }

    /** 读当前系统亮度（0..100%）。读 SCREEN_BRIGHTNESS（0..255），失败回退 50%。 */
    private fun readSystemBrightnessPercent(): Float {
        val v = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        }.getOrDefault(128)
        return (v.coerceIn(0, 255) / 255f) * 100f
    }

    // ---- Environment ----

    override fun onResume() {
        super.onResume()
        applySystemBars()
        // 字体管理页导入/删除后回来：字库集合变化即重排（新度量不断行不变）。
        refreshFontsAndRelayout()
    }

    /** 字库刷新 + 按需重排（onResume 与面板内增删共用）：集合不变直接跳过。 */
    private fun refreshFontsAndRelayout() {
        lifecycleScope.launch {
            if (refreshSkiaFonts() && engine != null) scheduleRelayout()
        }
    }

    /** 排版提交统一走这里：先等字库池跟上槽位，再重排（池未变也照排，边距字号同样要走）。 */
    private fun refreshFontsThenRelayout() {
        lifecycleScope.launch {
            refreshSkiaFonts()
            if (engine != null) scheduleRelayout()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 失焦会被系统清掉沉浸标志，回来重收敛，否则栏卡在显示态。
        if (hasFocus) applySystemBars()
    }

    /** 系统栏按当前状态收敛：面板打开 → 隐藏；否则随上下栏可见性。 */
    private fun applySystemBars() {
        if (settingsOpen || tocOpen) hideSystemChrome()
        else if (barsVisible) showSystemChrome()
        else hideSystemChrome()
    }

    /** 同步当前状态栏 inset（动画回调之外的静态时刻，如 create/resume）。 */
    private fun syncStatusInset() {
        statusInsetTopPx.intValue =
            ViewCompat.getRootWindowInsets(window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }

    /** 栏显示：状态栏 + 底部手势栏可见（controller.show，edge-to-edge 下唯一可靠路径）。 */
    private fun showSystemChrome() {
        syncStatusInset()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.statusBarColor = Color.TRANSPARENT
        val c = barsController()
        c.show(WindowInsetsCompat.Type.systemBars())
        // 白天浅底用深色图标，夜间深底用浅色图标；之前写死白色致白天“栏出来了也看不见”。
        c.isAppearanceLightStatusBars = effective.scheme != "night"
        c.isAppearanceLightNavigationBars = effective.scheme != "night"
    }

    /** 栏隐藏（阅读中）：controller.hide 为主（手势 pill 唯一可靠路径），legacy 标志位兼顾旧 ROM。 */
    private fun hideSystemChrome() {
        syncStatusInset()
        val c = barsController()
        c.hide(WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        c.isAppearanceLightStatusBars = effective.scheme != "night"
    }

    /** 系统栏控制器：transient 模式——边缘滑动临时 peek 后自动回藏，不与应用工具栏状态打架。 */
    private fun barsController(): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        // 退出收口：最后定位的 finalize + 落盘（ReaderScreen 已防抖，这里兜底）。
        val host = tabletHost
        val p = currentPos
        if (host != null && p != null) host.onSaveProgress(p)
        super.onDestroy()
    }

    // ---- Font pool ----

    /**
     * 用户导入字库进 skia 共用集合（IO 内同步调用；开书前必到一次保证首排不断错）。
     *
     * 装载集 = 槽位三件套 ∪ 本章实际需求（[orilumn.reader.engine.css.FontDemand]，controller
     * 在整形前回调补给）：每族只取款式必需面（Regular/Bold × 直/斜最近邻），全量共驻
     * 百余字体即 OOM（堆上限 256M，实测直接撑爆致开书失败白屏回书架），且大字库部分
     * 解析失败时池子集逐次不同、同一家族渲染字重飘忽。去重签名命中即跳过读文件
     * （翻页高频调用零 IO）。
     * @return 集合是否发生变化。
     */
    private var lastPoolFaceSig: String? = null

    private suspend fun refreshSkiaFonts(): Boolean = syncSkiaPool(orilumn.reader.engine.css.FontDemand.EMPTY)

    /** controller 按需回调：本章需求 deficient 时追装（调用方负责整形前调用）。 */
    private suspend fun topUpSkiaFonts(demand: orilumn.reader.engine.css.FontDemand): Boolean =
        if (demand == orilumn.reader.engine.css.FontDemand.EMPTY) false else syncSkiaPool(demand)

    /**
     * F4c: 装池经共享 [FontPoolSync]（两段式，语义沿旧实现：孤儿自愈→选择→签名比对→装配；
     * 系统衬线（[SystemCjkSerif]）经 `systemSerif` 参数并入）。
     */
    private suspend fun syncSkiaPool(extra: orilumn.reader.engine.css.FontDemand): Boolean = withContext(Dispatchers.IO) {
        // 系统宋体补装：把设备自带的中文衬线（NotoSerifCJK.ttc 的 SC 面）以引擎保留别名装进共用池，
        // 让传统模式/书内 `serif` 的中文落到宋体而非默认黑体（见 SystemCjkSerif）。进程内只读一次。
        val (changed, sig) = orilumn.reader.engine.skia.FontPoolSync.syncPool(
            profile = profile,
            demand = extra,
            bookEntries = bookFontEntries,
            lastSig = lastPoolFaceSig,
            loadFaces = {
                // 孤儿文件先自愈（有字节无记录：选它永远落空），再按需装载。
                runCatching { fontRepository.reconcileOrphanFiles() }.getOrNull()
                    ?: runCatching { fontRepository.list() }.getOrNull()
            },
            fileSize = { p -> java.io.File(p).takeIf { it.isFile }?.length() },
            fontBytes = { id -> fontRepository.fontBytes(id) },
            systemSerif = SystemCjkSerif.entry(),
            logTag = TAG,
        )
        lastPoolFaceSig = sig
        changed
    }

    /**
     * P2-b: 书内字体进池（与用户字库同集合共存）。controller 在整形前回调本书各章的
     * 实际引用字节；集合变化即返回 true（调用方作废版式重排，首绘即对）。
     */
    private var bookFontEntries: List<SkiaFontPool.EmbeddedFont> = emptyList()

    private suspend fun syncBookFonts(fonts: List<orilumn.reader.engine.css.BookFont>): Boolean = withContext(Dispatchers.IO) {
        bookFontEntries = orilumn.reader.engine.skia.FontPoolSync.mergeBookFonts(bookFontEntries, fonts)
            ?: return@withContext false
        // 走统一合并装载（签名含书内部分，零变化即池不动）。
        syncSkiaPool(orilumn.reader.engine.css.FontDemand.EMPTY)
    }


    /**
     * 切到原书设置时，把当前章节的真实排版（首行缩进/段间距/行距）快照进预设的滑块值
     * （2em 缩进的书滑块就是 2），再走正常提交 + 增量排版。探测失败原样返回。
     */
    private fun ReaderSettings.withBookStyle(): ReaderSettings {
        if (layoutTheme != "original") return this
        val c = engine ?: return this
        val p = currentPos ?: return this
        return c.snapshotBookStyle(p.chapter, profile.bodyPx, this)
    }

    companion object {
        private const val TAG = "Orilumn.Reader"

        /** 重排间隔（ms）：近实时，至多每帧一次；拖拽期间只要还有 pending 就一直重排。 */
        private const val RELAYOUT_INTERVAL_MS = 16L
    }
}

/** Absolute path of the book file passed in when launching the reader from the bookshelf. */
const val EXTRA_BOOK_PATH = "book_file_path"
/** Primary key of the book passed when launching the reader from the bookshelf (progress is stored/loaded only when >=0). */
const val EXTRA_BOOK_ID = "book_id"
