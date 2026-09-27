package orilumn.reader.desktop

import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.ui.reader.ReaderHost
import orilumn.reader.ui.reader.ReaderPos
import orilumn.reader.ui.reader.ReaderScreen
import orilumn.reader.ui.reader.ReaderSettingsPanel
import orilumn.reader.ui.reader.SnapshotReaderHost
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
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import okio.Path.Companion.toPath

/**
 * S32 桌面阅读视图：窗口级组装（`ReaderScreen` + 设置抽屉 + 目录抽屉）。
 *
 * 状态机（最小壳口径）：
 * - 视口/换书/session 变化 → 重建 [DesktopReaderHost]（`remember` 键），`ReaderScreen` 经
 *   `LaunchedEffect(host)` 重新 `open()` 并落到锚点（存档定位 / 目录跳转目标）；
 * - 排版设置变化 → 宿主两段式原位重排（R15：150ms 防抖轻刷新 + 800ms 静默全套），经 `externalPos` +
 *   `contentRevision` 推送阅读面（对齐平板 tick 轻刷新 + 关面板全套口径）；
 * - 设置面板拖拽是高频 `onPreview`：150ms 防抖后才进原位重排，避免逐帧重排
 *   （对齐 Android `scheduleRelayout` 节流语义）；纯亮度变化不进版式管线；
 * - 目录跳转：先经现 host `chapterStart` 塑形校验，再以锚点重建 host 落位；
 * - 当前定位经 [SnapshotReaderHost] 回抛，供目录高亮与重排锚点。
 */
@Composable
fun ReaderView(
    book: ShelfBook,
    store: DesktopShelfStore,
    settings: ReaderSettings,
    customs: List<ThemePreset>,
    onBack: () -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    /** 原书设置专用提交（只写本书 overlay，共享持久化语义；与平板同口径）。 */
    onCommitBookPrivate: (ReaderSettings) -> Unit,
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
    fun commit(next: ReaderSettings) = onSettingsChange(next)

    // 桌面字库（用户层·壳自持，内容与平板同口径）：
    // - 发现链在宿主 `syncPanelFonts`：macOS 目录扫描 + 中文名链（方案B name 表 → 方案A CoreText），
    //   落库 displayName；与平板 systemFontFaces 同源（skia 系统枚举），只是本地化名来源不同；
    // - 列表不再只留系统行：导入/隐藏/删除与平板同一 `buildFontRows`（导入区 + 已导入区全开，
    //   本地导入经 FileKit 原生对话框、无线导入经桌面独写对话框，同走共享服务；SAF/Android Dialog
    //   是平板 UI，不进共享）。
    val fontLibrary = remember(store) { store.fontLibrary() }
    var fontEntries by remember(book) { mutableStateOf<List<FontEntry>>(emptyList()) }
    fun refreshFonts() = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        fontEntries = fontLibrary.allEntries(fontLibrary.list())
    }
    // 本地导入（桌面 UI）：FileKit 原生多选对话框 → 字节入库（与平板 SAF 同一 `importBytes`）。
    val fontPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("ttf", "otf", "ttc", "woff", "woff2")),
        mode = FileKitMode.Multiple(),
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { files: List<PlatformFile>? ->
        val picked = files.orEmpty()
        if (picked.isEmpty()) return@rememberFilePickerLauncher
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            picked.forEach { f ->
                runCatching { fontLibrary.importBytes(f.readBytes(), f.name) }
            }
            refreshFonts().join()
        }
    }
    /** 隐藏/删除的族若正被槽位引用 → 回退跟随原书（系统/导入同查，与平板同口径）。 */
    fun fallBackFontSlots(family: String, s: ReaderSettings) {
        if (family != s.fontBody && family != s.fontTitle && family != s.fontCode) return
        commit(s.copy(
            fontBody = s.fontBody.takeUnless { it == family } ?: "",
            fontTitle = s.fontTitle.takeUnless { it == family } ?: "",
            fontCode = s.fontCode.takeUnless { it == family } ?: "",
        ))
    }
    fun familyOfFontIds(ids: List<Long>): String? =
        fontEntries.firstOrNull { e ->
            val id = (e as? FontEntry.System)?.id ?: (e as? FontEntry.Imported)?.face?.id
            id != null && id in ids
        }?.family

    // 版式防抖 + 原位重排（R5/R15，与平板 tick 轻刷新 + 关面板全套同序）：
    // 亮度分叉（与平板同规则，共享 `withoutLight`）：纯亮度变化不进版式管线；
    // 排版变化走宿主两段式（previewToSettings 行锚合位 + commitRelayout 全套），经 externalPos 推送阅读面，
    // 不再重建宿主（旧 `remember(layoutSettings)` 整宿主重建丢内存位是跳章首页根因）。
    // 视口/换书/session 仍走下方的宿主重建（存档定位）。
    var appliedLayout by remember(book) { mutableStateOf(settings) }
    var externalPos by remember(book) { mutableStateOf<ReaderPos?>(null) }
    var contentRevision by remember(book) { mutableStateOf(0) }
    // 真背光（macOS DDC；scan 一次常驻）：支持时亮度滑块 -50..100（>0 下发硬件），
    // 不支持时钳到 -50..0（纯遮罩，物理调亮不可达也不给滑）。
    val displayBrightness = remember { orilumn.reader.desktop.brightness.MacDisplayBrightness() }
    var ddcCapable by remember(book) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(book) {
        ddcCapable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { displayBrightness.ddcCapable() }.getOrDefault(false)
        }
    }
    // 亮度 >0 下发真背光（150ms 防抖合流；≤0 只画遮罩，不碰硬件；跟随系统 onmacOS 不动作）。
    LaunchedEffect(settings.brightness, settings.brightnessFollowSystem, ddcCapable) {
        if (settings.brightnessFollowSystem) return@LaunchedEffect
        if (ddcCapable != true) return@LaunchedEffect
        val v = settings.brightness.coerceIn(1, 100)
        if (settings.brightness <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { displayBrightness.set(v) }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current.density
        // 整窗视口（边距由控制器内部按 profile 扣除，与平板 `setViewport` 同语义；
        // 此处勿预减，否则排版比绘制区窄一圈右边距、矮一圈下边距）。
        val densityScope = LocalDensity.current
        val viewportW = with(densityScope) { maxWidth.toPx() }.toInt().coerceAtLeast(16)
        val viewportH = with(densityScope) { maxHeight.toPx() }.toInt().coerceAtLeast(16)
        // 窗口拉伸防抖：拖动中每像素都重组，宿主重建（重解析+重排）配漏斗互斥即自杀式
        // BUSY-DROP 风暴——旧宿主落位、新宿主画布=白屏。静置 400ms 后再用稳定值重建。
        var settledViewport by remember(book) { mutableStateOf(viewportW to viewportH) }
        LaunchedEffect(book, viewportW, viewportH) {
            kotlinx.coroutines.delay(400)
            settledViewport = viewportW to viewportH
        }

        val snapshot = remember(book, session) {
            val delegate = DesktopReaderHost(
                bookFile = book.filePath,
                bookId = book.id,
                store = store,
                settings = appliedLayout,
                density = density,
                viewportW = settledViewport.first,
                viewportH = settledViewport.second,
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
        // 引擎重排落位推送（设置两段式与视口重排共用）：刷版本号 + 定位到含锚字符的新页，
        // NonCancellable 保证已完成的重排不被 effect 重启吞掉。
        suspend fun pushLanding(landing: ReaderPos) {
            withContext(NonCancellable) {
                currentPos = landing
                externalPos = landing
                contentRevision++
                orilumn.reader.io.Logger.w("Orilumn.Desktop",
                    "push ch=${landing.chapter} slice=${landing.slice} rev=$contentRevision")
            }
        }
        // 视口变化不断连重排：宿主只在换书/会话时重建，拉伸窗口只换视口，
        // 与调参完全同序（轻刷新即时落位 + 全套沉淀），锚点取当页首字符
        //（slice.charStart 即页内首字符；封面/纯图页同样是字符定位，无字符页不存在）。
        // 旧页保持可画到新页落定（重建宿主的青黄不接即闪屏根因）。首帧跳过（构造已用该视口）。
        var appliedViewport by remember(book, session) { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(book, session, settledViewport) {
            val prev = appliedViewport
            appliedViewport = settledViewport
            if (prev == null || prev == settledViewport) return@LaunchedEffect
            val host = desktopHost ?: return@LaunchedEffect
            if (!host.applyViewportSize(settledViewport.first, settledViewport.second)) return@LaunchedEffect
            // 第一段：轻刷新即时落位（与 previewToSettings 同）。
            val anchor = currentPos
            host.previewToSettings(
                settings, anchor?.chapter ?: 0, anchor?.slice?.charStart ?: 0,
            )?.let { pushLanding(it) }
            // 第二段：全套沉淀（与 commitRelayout 同）。
            val anchor2 = currentPos
            host.commitRelayout(anchor2?.chapter ?: 0, anchor2?.slice?.charStart ?: 0)?.let { pushLanding(it) }
        }
        // 面板字库经宿主装载（R4：枚举+中文名链已下沉 `syncPanelFonts`，视图只收表；
        // 键只跟书，视口 resize 重建宿主不重枚举）。
        LaunchedEffect(book) {
            fontEntries = desktopHost?.syncPanelFonts() ?: fontLibrary.allEntries(fontLibrary.list())
        }
        // 设置驱动的原位重排（R15 两段式，与平板“tick 轻刷新 + 关面板全套”同序）：
        // 第一段 150ms 防抖落位即调（本章轻刷新）；第二段静默 800ms 后全套一次（bump 代际 + B2）。
        // 新设置到达即重启本 effect，未执行的第二段自动取消。落位经 externalPos+contentRevision
        // 推送（与平板 applyReflowResult 同口径：刷版本号 + 定位到含锚字符的新页）。
        // 修复：推送必须在 NonCancellable 中执行，避免 LaunchedEffect 重启取消导致已完成的引擎重排被丢弃。
        LaunchedEffect(book, settings) {
            delay(150)
            if (settings.withoutLight() == appliedLayout.withoutLight()) return@LaunchedEffect
            val host = desktopHost ?: return@LaunchedEffect
            val anchor = currentPos
            val landing = host.previewToSettings(
                settings, anchor?.chapter ?: 0, anchor?.slice?.charStart ?: 0)
            appliedLayout = settings
            if (landing != null) pushLanding(landing)
            // 第二段：设置静默 800ms 后全套（与平板关面板同序；新设置到达则本 effect 重启，此段取消）。
            delay(800)
            val anchor2 = currentPos
            val landed2 = host.commitRelayout(anchor2?.chapter ?: 0, anchor2?.slice?.charStart ?: 0)
            if (landed2 != null) pushLanding(landed2)
        }

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
            externalPos = externalPos,
            contentRevision = contentRevision,
            // 面板打开时按键留给面板，阅读面不翻页；面板关闭回阅读面即重夺焦点。
            keysEnabled = !settingsOpen && !tocOpen,
        )

        // 面板常驻组合、经 visible 驱动：关闭走面板内部退场动画（滑出+遮罩淡出后卸载）；
        // 若用 if 条件组合会整棵拔掉，退场动画永远跑不到。
        ReaderSettingsPanel(
            visible = settingsOpen,
            settings = settings,
            // 内容与平板同口径：导入区 + 已导入区全开；
            // 导入经 FileKit（桌面 UI），WiFi 经桌面独写对话框（服务共享），发现链见上（macOS 独有，保留）。
            fontEntries = fontEntries,
            showImportedSection = true,
            canFontImport = true,
            onFontImport = { fontPicker.launch() },
            canFontWifiImport = true,
            // 无线走面板内下钻（与平板同页）：壳只给暂存目录 + 落盘入库。
            wifiUploadDir = java.io.File(DesktopPaths.cacheDir, "wifi_fonts").absolutePath,
            onWifiUpload = { path ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fontLibrary.importFile(path.toPath()) }
                    refreshFonts().join()
                }
            },
            onFontHide = { ids ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val family = familyOfFontIds(ids)
                    ids.forEach { fontLibrary.setHidden(it, true) }
                    refreshFonts().join()
                    // 隐藏正被槽位引用 → 回退跟随原书（沿删除口径），回退经设置提交走原位重排追装。
                    if (family != null) {
                        val s = settings
                        if (family in setOf(s.fontBody, s.fontTitle, s.fontCode)) {
                            fallBackFontSlots(family, s)
                        }
                    }
                }
            },
            onFontUnhide = { ids ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ids.forEach { fontLibrary.setHidden(it, false) }
                    refreshFonts().join()
                }
            },
            onFontDelete = { family ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    fontLibrary.deleteFamily(family)
                    refreshFonts().join()
                    fallBackFontSlots(family, settings)
                }
            },
            onCommitBookPrivate = onCommitBookPrivate,
            onDismiss = { settingsOpen = false },
            brightnessMax = if (ddcCapable == true) 100 else 0,
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
            // 目录项1（用户层）：桌面此前没传当页标题 id，一直只能定位到章首；现与平板同口径。
            currentFragments = currentPos?.let { pos ->
                desktopHost?.currentPageFragmentIds(pos.chapter, pos.slice.charStart, pos.slice.charEnd)
            } ?: emptySet(),
            onSelect = { item ->
                val idx = item.index ?: return@ReaderTocPanel
                tocOpen = false
                scope.launch {
                    val target = desktopHost?.tocItem(idx, item.fragment)
                        ?: snapshot.chapterStart(idx) ?: return@launch
                    anchorOverride = target.chapter to target.slice.charStart
                    session++
                }
            },
            onDismiss = { tocOpen = false },
        )
    }
}
