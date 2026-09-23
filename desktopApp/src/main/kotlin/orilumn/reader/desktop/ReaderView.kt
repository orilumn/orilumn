package orilumn.reader.desktop

import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.ui.reader.ReaderHost
import orilumn.reader.ui.reader.ReaderPos
import orilumn.reader.ui.reader.ReaderScreen
import orilumn.reader.ui.reader.ReaderSettingsPanel
import orilumn.reader.ui.reader.ReaderTocPanel
import orilumn.reader.ui.reader.ThemePreset
import orilumn.reader.ui.shelf.ShelfBook
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S32 桌面阅读视图：窗口级组装（`ReaderScreen` + 设置抽屉 + 目录抽屉）。
 *
 * 状态机（最小壳口径）：
 * - 视口/版式变化 → 重建 [DesktopReaderHost]（`remember` 键），`ReaderScreen` 经
 *   `LaunchedEffect(host)` 重新 `open()` 并落到锚点（存档定位 / 目录跳转目标）；
 * - 设置面板拖拽是高频 `onPreview`：版式键走 150ms 防抖（`layoutSettings`），避免逐帧
 *   重排（对齐 Android `scheduleRelayout` 节流语义）；
 * - 目录跳转：先经现 host `chapterStart` 塑形校验，再以锚点重建 host 落位；
 * - 当前定位经 [SnapshotReaderHost] 回抛，供目录高亮与重建锚点。
 */
@Composable
fun ReaderView(
    book: ShelfBook,
    store: DesktopShelfStore,
    settings: ReaderSettings,
    customs: List<ThemePreset>,
    onBack: () -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onSaveTheme: (ThemePreset) -> Unit,
    onDeleteTheme: (ThemePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var anchorOverride by remember(book) { mutableStateOf<Pair<Int, Int>?>(null) }
    var session by remember(book) { mutableStateOf(0) }
    // 锚点一次性消费：落位重建提交后即清除，后续重建回退到存档定位
    // （open() 每次落位都持久化，故存档定位恒新）。
    LaunchedEffect(session) { anchorOverride = null }
    var currentPos by remember(book) { mutableStateOf<ReaderPos?>(null) }
    var settingsOpen by remember(book) { mutableStateOf(false) }
    var tocOpen by remember(book) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // F4b 桌面仅系统字体：系统枚举经 fontconfig 含用户字体（~/.fonts 等）只读展示，
    // 可隐藏；不展示导入入口/已导入区（平板独占本地+WiFi 导入与删除）。
    val fontLibrary = remember(store) { store.fontLibrary() }
    var fontEntries by remember(book) { mutableStateOf<List<FontEntry>>(emptyList()) }
    LaunchedEffect(book) {
        val sys = runCatching { orilumn.reader.engine.skia.systemFontFaces() }.getOrDefault(emptyList())
        val faces = runCatching {
            // F 系列中文名链（方案B → 方案A）：
            // 1) name 表直读优先——不依赖 CoreText/系统语言的 OpenType 变体语言记录（TC→zh-TW、
            //    HK→zh-HK、MO→zh-MO、其余→zh-CN，是名不筛简繁），扫系统/用户字体目录建
            //    「拉丁族名 → 中文族名」映射，进程内缓存；
            // 2) CoreText 只补 name 表未覆盖的族（含 CJK 结果）；
            // 3) 落库 displayName；无本地化名的族展示层回退族名本身。都是后台线程
            //    （几十次原生调用 + 一次性扫描）。
            val localized = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val nameTable = NameTableChineseNames.namesFor(sys.map { it.family })
                val coreText = MacFamilyNames.localizedFamilyNames(sys.map { it.family })
                coreText + nameTable // map 合并：同键以右侧（name 表）为准
            }
            fontLibrary.syncSystemFaces(sys, localizedNames = localized)
        }.getOrElse { runCatching { fontLibrary.list() }.getOrDefault(emptyList()) }
        fontEntries = fontLibrary.allEntries(faces).filterIsInstance<FontEntry.System>()
    }
    fun refreshFonts() = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        fontEntries = fontLibrary.allEntries(fontLibrary.list()).filterIsInstance<FontEntry.System>()
    }

    // 版式键防抖：面板拖拽时画布 Profile 即时跟手，宿主重排最多 ~7 次/秒。
    var layoutSettings by remember(book) { mutableStateOf(settings) }
    LaunchedEffect(settings) {
        delay(150)
        layoutSettings = settings
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current.density
        // 整窗视口（边距由控制器内部按 profile 扣除，与平板 `setViewport` 同语义；
        // 此处勿预减，否则排版比绘制区窄一圈右边距、矮一圈下边距）。
        val densityScope = LocalDensity.current
        val viewportW = with(densityScope) { maxWidth.toPx() }.toInt().coerceAtLeast(16)
        val viewportH = with(densityScope) { maxHeight.toPx() }.toInt().coerceAtLeast(16)

        val snapshot = remember(book, layoutSettings, viewportW, viewportH, session) {
            val delegate = DesktopReaderHost(
                bookFile = book.filePath,
                bookId = book.id,
                store = store,
                settings = layoutSettings,
                density = density,
                viewportW = viewportW,
                viewportH = viewportH,
                initialAnchor = anchorOverride,
                // C1-3：桌面分页表写穿 `cache/`（与平板同一共享 Store/参数键/失效语义）。
                cacheRoot = DesktopPaths.cacheDir,
                fontLibrary = fontLibrary,
            )
            SnapshotReaderHost(delegate) { currentPos = it }
        }
        DisposableEffect(snapshot) {
            onDispose { (snapshot.delegate as? DesktopReaderHost)?.close() }
        }
        val desktopHost = snapshot.delegate as? DesktopReaderHost

        fun commit(next: ReaderSettings) = onSettingsChange(next)

        ReaderScreen(
            host = snapshot,
            settings = settings,
            onBack = onBack,
            onNight = {
                commit(settings.copy(scheme = if (settings.scheme == "night") "day" else "night"))
            },
            onSettings = { settingsOpen = true },
            onToc = { tocOpen = true },
            onLightChange = { onSettingsChange(it) },
            onLightCommit = { commit(it) },
            // 面板打开时按键留给面板，阅读面不翻页；面板关闭回阅读面即重夺焦点。
            keysEnabled = !settingsOpen && !tocOpen,
        )

        // 面板常驻组合、经 visible 驱动：关闭走面板内部退场动画（滑出+遮罩淡出后卸载）；
        // 若用 if 条件组合会整棵拔掉，退场动画永远跑不到。
        ReaderSettingsPanel(
            visible = settingsOpen,
            settings = settings,
            // 桌面仅系统字体（无导入入口/已导入区；系统行左滑隐藏）。
            fontEntries = fontEntries,
            showImportedSection = false,
            onFontHide = { ids ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val family = fontEntries.firstOrNull { e ->
                        val id = (e as? FontEntry.System)?.id
                        id != null && id in ids
                    }?.family
                    ids.forEach { fontLibrary.setHidden(it, true) }
                    refreshFonts().join()
                    // 隐藏正被槽位引用 → 回退跟随原书（沿删除口径），宿主重建经 onDemandFonts 追装。
                    if (family != null && family in setOf(settings.fontBody, settings.fontTitle, settings.fontCode)) {
                        commit(settings.copy(
                            fontBody = settings.fontBody.takeUnless { it == family } ?: "",
                            fontTitle = settings.fontTitle.takeUnless { it == family } ?: "",
                            fontCode = settings.fontCode.takeUnless { it == family } ?: "",
                        ))
                    }
                }
            },
            onFontUnhide = { ids ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ids.forEach { fontLibrary.setHidden(it, false) }
                    refreshFonts().join()
                }
            },
            onDismiss = { settingsOpen = false },
            onPreview = { onSettingsChange(it) },
            onCommitTypography = { commit(it) },
            onCommitLight = { commit(it) },
            customThemes = customs,
            onSaveTheme = onSaveTheme,
            onDeleteTheme = onDeleteTheme,
        )

        ReaderTocPanel(
            visible = tocOpen,
            toc = desktopHost?.toc ?: emptyList(),
            currentChapter = currentPos?.chapter ?: 0,
            scheme = settings.scheme,
            onSelect = { item ->
                val idx = item.index ?: return@ReaderTocPanel
                tocOpen = false
                scope.launch {
                    val target = snapshot.chapterStart(idx) ?: return@launch
                    anchorOverride = target.chapter to target.slice.charStart
                    session++
                }
            },
            onDismiss = { tocOpen = false },
        )
    }
}

/**
 * 定位回抛装饰器：把宿主返回的每个 [ReaderPos] 同步给视图状态（目录高亮/重建锚点），
 * 其余全部透传。`onSaveProgress` 同样携带定位，一并回抛。
 */
class SnapshotReaderHost(
    val delegate: ReaderHost,
    private val onPos: (ReaderPos) -> Unit,
) : ReaderHost by delegate {
    override suspend fun open(): ReaderPos? = delegate.open()?.also(onPos)
    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? =
        delegate.adjacent(pos, direction)?.also(onPos)
    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        delegate.neighborChapterStart(chapter, direction)?.also(onPos)
    override suspend fun pageAtFraction(fraction: Double): ReaderPos? =
        delegate.pageAtFraction(fraction)?.also(onPos)
    override suspend fun chapterStart(index: Int): ReaderPos? =
        delegate.chapterStart(index)?.also(onPos)
    override fun onSaveProgress(pos: ReaderPos) {
        onPos(pos)
        delegate.onSaveProgress(pos)
    }
}
