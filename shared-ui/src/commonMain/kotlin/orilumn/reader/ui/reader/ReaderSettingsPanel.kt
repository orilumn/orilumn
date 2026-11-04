package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssDefaults
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import orilumn.reader.net.FontUploadServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * S29 阅读设置抽屉：右停靠面板，复刻旧 `reader.html` 设置抽屉在控件与层级上的排布
 * （金色强调、--ui-* 调色板），平移自 Android `ReaderSettingsPanel`。
 *
 * 平台接缝（相对 Android 版改动）：
 *  - 系统亮度读值（Android `Settings.System`）收敛为 [readSystemBrightness] 回调（0..100，默认 50）；
 *  - 字体管理走共享 [FontLibraryPanel]（[fontEntries] 统一列表：系统 + 导入同列，
 *    点选写入对应槽位；隐藏/删除/导入经回调交宿主，F4a）；
 *  - 自定义阅读主题预设在面板内为纯内存列表，经 [onSaveTheme]/[onDeleteTheme] 交宿主持久化；
 *  - Android 返回键（BackHandler）不在此承载——Android 壳（S31）负责路由到 [onDismiss]。
 *
 * 语义条约：排版类改动走 [onCommitTypography]（触发重排）；不影响排版的设置
 * （亮度/护眼/纯 UI 开关——翻页动画、封面拉伸、启动续读、页码、隐藏字体，
 * 以及标注色槽等只改像素的项）走 [onCommitLight]（不重排，只持久化 + 重组）。
 * 新增开关先判「是否影响版式指纹」：
 * 不影响就必须走 [onCommitLight]——排版提交会触发重排绑定并推落位，与开书封面解析
 * 竞态会误记封面 dismissed（封面不弹），纯开关本也无需重排。
 *
 * **两端同规则，不各写一遍**：这条「是否影响版式」的判定收敛在
 * [orilumn.reader.data.settings.ReaderSettings.withoutNonLayout]（亮度族 + 纯 UI 开关 + 标注色槽整族，
 * 字段族由 `SettingsLayoutScopeTest` 锁完备性）。平板按本面板的两类回调分流
 * （`ReaderActivity.commitSettings(typographyChanged=…)`），桌面两条回调合流、改为在
 * `ReaderView` 里比对该共享函数——两端问同一个问题。**新增字段必须同时改两处**：
 * 本面板的路由 + `withoutNonLayout` 的登记（漏一处 = 切开关白跑一次整书重排）。
 *
 * 按键模型（与目录同款）：焦点落在抽屉容器本身只做按键捕获（[panelKeyEvents] 共享路由），
 * 移动是纯状态驱动——各子页共享一个 `activeIdx` + 跟随滚动（`ensureVisible`），悬停认领与
 * 键盘共用同一个 active（`kbHold` 仲裁，滚动带过静止光标不抢）。行不再走焦点遍历：
 * `moveFocus` 在 LazyColumn 异构行之间不可靠（↑↓ 无响应根因），已退役。
 */
@Composable
fun ReaderSettingsPanel(
    visible: Boolean,
    settings: ReaderSettings,
    /** F4a 统一字体列表（系统 + 导入，含隐藏；排序/分区由 [buildFontRows]）。 */
    fontEntries: List<FontEntry>,
    /** 系统字体扫描实时进度（方案 §2.4）：面板打开且扫描在途时非 null，字体页显示「已 N / 共 M 族」。 */
    fontScanProgress: FontScanProgress? = null,
    onDismiss: () -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommitTypography: (ReaderSettings) -> Unit,
    onCommitLight: (ReaderSettings) -> Unit,
    /**
     * 只改像素的**拖动中**预览（不落盘、不重排）：笔记页四条颜色滑条每挪一格就发一次，
     * 让色槽与正文墨迹随手实时变，松手再由 [onCommitLight] 落盘。
     * 缺省等于 [onCommitLight]（没有预览通道的宿主仍按旧行为）。
     */
    onPreviewLight: (ReaderSettings) -> Unit = onCommitLight,
    customThemes: List<ThemePreset> = emptyList(),
    onSaveTheme: (ThemePreset) -> Unit = {},
    onDeleteTheme: (ThemePreset) -> Unit = {},
    readSystemBrightness: () -> Int = { 50 },
    /** F4a 字体管理回调（隐藏/取消隐藏/删除/导入，宿主落库 + 刷新字库重排）。 */
    onFontHide: (List<Long>) -> Unit = {},
    onFontUnhide: (List<Long>) -> Unit = {},
    onFontDelete: (String) -> Unit = {},
    /** 本地导入按钮（桌面 FileKit / 平板 SAF，经 [onFontImport] 交壳；与无线导入独立）。 */
    canFontImport: Boolean = false,
    onFontImport: () -> Unit = {},
    /**
     * 无线导入下钻页（双端同页）：服务走共享 [FontUploadServer]，页面进出即启停；
     * 壳只给暂存目录 + 落盘入库回调（`onWifiUpload(path)` 内导入并刷新，线程自理）。
     * 为空即不展示无线入口。
     */
    canFontWifiImport: Boolean = true,
    wifiUploadDir: String = "",
    onWifiUpload: (String) -> Unit = {},
    /** 已导入区是否显示（平板=true；桌面=false，仅系统字体）。 */
    showImportedSection: Boolean = true,
    /**
     * 亮度滑块上限（真背光可用时 100；仅遮罩时壳传 0，上限钳到遮罩段 -50..0，
     * 调亮物理不可达也不给滑。缺省 100，所有现调用方零改动）。
     */
    brightnessMax: Int = 100,
    /**
     * 设备是否有物理键盘：无键盘时焦点行背景（`rowActive`）置透明——键盘/悬停共用的
     * active 高亮只在有键盘时有意义。选中值指示（金色边框/开关）不受影响。
     * 缺省 true（桌面恒有键盘，零改动）。
     */
    hasKeyboard: Boolean = true,
) {
    val p = paletteFor(settings.scheme)
        .let { if (hasKeyboard) it else it.copy(rowActive = Color.Transparent) }
    val stack = remember { mutableStateListOf<Sub>(Sub.Home) }
    val current = stack.last()
    // Which typography slot (body/heading/code) the current font sub-panel selects for.
    var pickSlot by remember { mutableStateOf<String?>(null) }
    // 笔记页：当前编辑的类别 / 色槽。只在本面板内记忆，关面板复位。
    var annotationColorKind by remember { mutableStateOf(AnnotationKind.Highlight) }
    var annotationColorSlot by remember { mutableStateOf(0) }
    /**
     * 笔记页「恢复默认颜色池」的按下反馈（[ColorPoolResetFlashMs] 内按钮换成「已恢复」并高亮）。
     *
     * 色槽本来就可能就是默认值，只靠色块变化读者分不清「按上了但没变」和「根本没按到」；
     * 计数而非布尔：连按两次也要重新计时。
     */
    var colorPoolResetCount by remember { mutableStateOf(0) }
    var colorPoolResetFlash by remember { mutableStateOf(false) }
    LaunchedEffect(colorPoolResetCount) {
        if (colorPoolResetCount == 0) return@LaunchedEffect
        colorPoolResetFlash = true
        delay(ColorPoolResetFlashMs.toLong())
        colorPoolResetFlash = false
    }
    // 自定义主题预设（内存态）：Seed 自宿主列表；保存/删除经 onSaveTheme/onDeleteTheme 上报宿主。
    var customs by remember(customThemes) { mutableStateOf(customThemes) }

    // Reset to home when closed, so the top-level menu shows next time it opens.
    LaunchedEffect(visible) {
        if (!visible) {
            stack.clear()
            stack.add(Sub.Home)
            pickSlot = null
            annotationColorKind = AnnotationKind.Highlight
            annotationColorSlot = 0
        }
    }

    // Panel slide + mask dim/lighten, **synchronized**: the mask ramps at the SAME time and with the
    // SAME duration as the panel slide, so they move together instead of the mask trailing the panel.
    val density = LocalDensity.current
    val slide = remember { Animatable(1f) }            // 1 = off-screen (right), 0 = docked
    var mounted by remember { mutableStateOf(false) } // content composed only while shown
    var maskOn by remember { mutableStateOf(false) }
    val maskAlpha by animateFloatAsState(if (maskOn) 1f else 0f,
        tween(PanelAnimMs, easing = FastOutSlowInEasing), label = "panelMask")
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            slide.snapTo(1f)
            // Flip the mask and the slide target at the same instant so both animate together.
            maskOn = true
            slide.animateTo(0f, tween(PanelAnimMs, easing = FastOutSlowInEasing))
        } else {
            maskOn = false
            slide.animateTo(1f, tween(PanelAnimMs, easing = FastOutSlowInEasing))
            delay((PanelAnimMs + 20).toLong())                     // wait for both to finish before unmounting
            mounted = false
        }
    }

    if (mounted) {
        // 与目录同款：焦点落抽屉容器只做按键捕获，移动走 activeIdx 状态。
        val drawerFr = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        // 子页切换即重置：列表状态重建（滚动归零），active 回首行。
        // 与目录同一套 PanelNav：activeIdx 共享高亮，kbHold 仲裁悬停，有效行 = 非 disabled。
        val listState = remember(current) { LazyListState() }
        val nav = remember(current) { PanelNav().apply { activeIdx = 0 } }
        // 字体页按钮环焦点（null=字体区，activeIdx 生效；否则为环下标，上下箭头够不着按钮）：
        // 环 = ‹返回/X/导入钮…，Tab 循环，按钮区左右切换，进字体页即复位。
        var fontButtonFocus by remember { mutableStateOf<Int?>(null) }
        /** 字重下钻目标族（null=不在字重页；切子页即清）。 */
        var weightPickFamily by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(current) {
            fontButtonFocus = null
            // 字重页进入时目标族保留（先设值后压栈，同一次重组；离页才清）。
            if (current != Sub.WeightPicker) weightPickFamily = null
        }
        LaunchedEffect(mounted, current, visible) {
            // 取焦点只在面板可见时：退出（visible=false）即便因子页栈重置导致 current 变化，
            // 也不得抢回焦点——否则正好压住阅读面的夺回（二级面板遮罩退出后方向键失灵即此）。
            if (visible) drawerFr.requestFocus()
        }
        fun onEscape() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
        }

        // 阅读主题网格（父级计算，供键列表与渲染共用同一 allThemes）。
        val allThemes = remember(customs) {
            val builtinBgs = ReaderThemeMath.BUILTIN_THEMES.map { it.bg }.toSet()
            val kept = customs.filterNot { it.bg in builtinBgs }
            (ReaderThemeMath.BUILTIN_THEMES + kept).distinctBy { it.bg to it.fg }
        }

        // ---- 当前页键列表（click 与键盘共用同一回调） ----
        val s = settings
        // F4a 字体行模型（键与渲染共用同一份，1:1 对齐 activeIdx）。
        val slotKey: String = pickSlot ?: "fontBody"
        val fontRows: List<FontPanelRow> = remember(current, fontEntries, s, pickSlot, canFontImport, canFontWifiImport, showImportedSection) {
            if (current == Sub.TextFont) buildFontRows(fontEntries, fieldOf(s, slotKey), canFontImport, showImportedSection, canFontWifiImport, s.showHiddenFonts)
            else emptyList()
        }
        /**
         * 字体槽位提交：正文 / 标题 / 代码换字体要重新断行（排版提交）；
         * 批注字体只改旁注覆盖层的字形（无重排提交，与色槽同口径）。
         */
        fun commitSlot(next: ReaderSettings) {
            if (slotKey == "noteFont") onCommitLight(next) else onCommitTypography(next)
        }
        /**
         * 字体行进入：多字重档下钻字重页，单一下钻直接写入槽位（渲染点按与键盘共用）。
         *
         * 批注字体与正文三槽**同一套下钻口径**：该族有多档字重就进字重页，选完族 + 字重一起落盘。
         */
        fun enterFontRow(family: String) {
            val multi = fontEntries.filter { it.family == family }.weightChoices().size > 1
            if (multi) {
                weightPickFamily = family
                stack.add(Sub.WeightPicker)
            } else {
                commitSlot(setField(s, slotKey, family))
            }
        }
        // 无线下钻页可用性：能力位开且壳给了暂存目录。
        val wifiReady = canFontWifiImport && wifiUploadDir.isNotEmpty()
        // 字体行号换算：按钮区固定在列表外，懒索引 = 行号 - fixedCount；
        // 上下箭头 lo 钳到首字体行（按钮够不着）。
        val fontFixedCount = fontRows.takeWhile { it is FontPanelRow.Toggle || it is FontPanelRow.Import }.size
        val fontStartIdx = fontRows.indexOfFirst { it is FontPanelRow.FollowOriginal }
            .takeIf { it >= 0 } ?: 0
        // 入口行（正文/标题/代码）展示名：族 → 本地化展示名（系统/导入同表）；
        // 缺席（如已删族仍被槽位引用）回退族名本身 + CSS 通用族标签（不裸奔英文原族名）。
        val fontDisplayByFamily = remember(fontEntries) { fontEntries.associate { it.family to it.display } }
        // flat idx → LazyColumn 位置（阅读主题网格每 3 格并一行；亮度页手势开关同行；
        // 字体页按钮区在列表外，懒索引 = 行号 - fixedCount）。
        val listPosOf: (Int) -> Int = when (current) {
            Sub.TextFont -> { idx -> idx - fontFixedCount }            Sub.ReadingTheme -> { idx ->
                val n = allThemes.size
                val chunks = (n + GridCols - 1) / GridCols
                if (idx < n) idx / GridCols else chunks + (idx - n)
            }
            Sub.Brightness -> { idx -> if (idx < 3) idx else 4 }
            else -> { idx -> idx }
        }
        val keys: List<ItemKey> = when (current) {
            Sub.Home -> listOf(
                ItemKey(onEnter = { stack.add(Sub.Theme) }),
                ItemKey(onEnter = { stack.add(Sub.Spacing) }),
                ItemKey(onEnter = { stack.add(Sub.Text) }),
                ItemKey(onEnter = { stack.add(Sub.AnnotationColors) }),
                ItemKey(onEnter = { stack.add(Sub.ReadingTheme) }),
                ItemKey(onEnter = { stack.add(Sub.Brightness) }),
                // 以下四个开关不影响排版（翻页动画/封面拉伸/启动续读/页码）：
                // 走无重排提交（纯 UI 设置，与 showHiddenFonts 同口径）——
                // 走排版提交会触发重排绑定并推落位，与开书封面解析竞态
                // （误记 dismissed 致封面不弹），且纯开关本无需重排。
                ItemKey(onEnter = { onCommitLight(s.copy(pageAnim = !s.pageAnim)) }),
                ItemKey(onEnter = { stack.add(Sub.AnimMode) }),
                ItemKey(onEnter = { onCommitLight(s.copy(coverStretch = !s.coverStretch)) }),
                ItemKey(onEnter = { onCommitLight(s.copy(autoContinue = !s.autoContinue)) }),
                ItemKey(onEnter = { onCommitLight(s.copy(pageNum = !s.pageNum)) }),
            )
            Sub.Text -> {
                val px = s.fontSize * ReaderSettings.fontScaleToRatio(s.fontScale)
                listOf(
                    ItemKey(onEnter = { pickSlot = "fontBody"; stack.add(Sub.TextFont) }),
                    ItemKey(onEnter = { pickSlot = "fontTitle"; stack.add(Sub.TextFont) }),
                    ItemKey(onEnter = { pickSlot = "fontCode"; stack.add(Sub.TextFont) }),
                    sliderKey(px, 9.0, 36.0, 0.1,
                        apply = { v -> val ns = ReaderThemeMath.pxToScale(v); onPreview(s.copy(fontScale = ns)); onCommitTypography(s.copy(fontScale = ns)) },
                        fmt = { String.format("%.1f", it) }),
                    sliderKey(s.letterSpacingSlot(), -100.0, 100.0, 1.0,
                        apply = { v -> onPreview(s.copy(letterSpacing = v)); onCommitTypography(s.copy(letterSpacing = v)) },
                        fmt = { it.roundToInt().toString() }),
                    // 第 6 个滑块 = 混排字距（正文页渲染在 index 5，见同函数下方的行构造）。
                    // ⚠ 漏了这一项**不崩**（下游一律 `getOrNull`），但键盘/手柄**够不到那一行** ——
                    //   症状是「鼠标能拖、键盘翻不到」，极难归因。故键表必须与行序逐项对齐。
                    sliderKey(s.cjkLatinSpacing, 0.0, 100.0, 1.0,
                        apply = { v -> onPreview(s.copy(cjkLatinSpacing = v)); onCommitTypography(s.copy(cjkLatinSpacing = v)) },
                        fmt = { it.roundToInt().toString() }),
                )
            }
            Sub.TextFont -> fontRows.map { row ->
                when (row) {
                    is FontPanelRow.Toggle ->
                        // 开关走无重排提交（纯全局设置，直写全局 + 落盘；见 showHiddenFonts）。
                        ItemKey(onEnter = { onCommitLight(s.copy(showHiddenFonts = !s.showHiddenFonts)) })
                    is FontPanelRow.FollowOriginal ->
                        ItemKey(onEnter = {
                            if (slotKey == "noteFont") {
                                commitSlot(setField(s, slotKey, "").copy(noteFontWeight = 0))
                            } else {
                                val clearedSlotAnchors = s.fontWeightAnchorsBySlot.filterKeys { !it.startsWith("$slotKey|") }
                                commitSlot(setField(s, slotKey, "").copy(fontWeightAnchorsBySlot = clearedSlotAnchors))
                            }
                        })
                    is FontPanelRow.Import ->
                        // 双按钮同行：回车走主动作（本地导入优先；单开无线时进无线下钻页）。
                        ItemKey(onEnter = { if (canFontImport) onFontImport() else stack.add(Sub.WifiImport) })
                    is FontPanelRow.Entry ->
                        ItemKey(onEnter = { enterFontRow(row.family) })
                }
            }
            Sub.WeightPicker -> {
                // 字重档（无自动行：点即写入槽位 + 锚点并停留本页；键与渲染同序，首档即键 0）。
                val fam = weightPickFamily
                if (fam == null) {
                    listOf(ItemKey())
                } else {
                    val opts = fontEntries.filter { it.family == fam }.weightChoices()
                    opts.map { (w, _) ->
                        ItemKey(onEnter = {
                            commitSlot(withSlotWeight(s, slotKey, fam, w))
                        })
                    }
                }
            }
            // 无线下钻页是纯展示页（地址 + 说明）：键盘占一位空键，只保焦点计数不崩。
            Sub.WifiImport -> listOf(ItemKey())
            Sub.Spacing -> listOf(
                sliderKey(s.firstLineIndent ?: s.probeIndent ?: 0.0, 0.0, 10.0, 1.0,
                    apply = { v -> onPreview(s.copy(firstLineIndent = v)); onCommitTypography(s.copy(firstLineIndent = v)) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.lineSpacing ?: s.probeLineSpacing ?: CssDefaults.DEFAULT_LINE_HEIGHT.toDouble(), 0.5, 2.5, 0.1,
                    apply = { v -> onPreview(s.copy(lineSpacing = v)); onCommitTypography(s.copy(lineSpacing = v)) },
                    fmt = { String.format("%.1f", it) }),
                // 段间距是**绝对值 em**（0..2）：读者没配（null）时显示原书实测段距，拖动即写本书
                // 显式值（书自己的 margin 从此不再参与）。疏密仍是百分比乘算（另一档语义）。
                sliderKey(s.paragraphSpacing ?: s.probeParagraphSpacing ?: 0.0, 0.0, 2.0, 0.1,
                    apply = { v -> onPreview(s.copy(paragraphSpacing = v)); onCommitTypography(s.copy(paragraphSpacing = v)) },
                    fmt = { String.format("%.1f", it) }),
                sliderKey(s.paragraphGap / 10.0, 0.0, 100.0, 1.0,
                    apply = { v -> onPreview(s.copy(paragraphGap = v * 10.0)); onCommitTypography(s.copy(paragraphGap = v * 10.0)) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.marginTop.toDouble(), 0.0, 200.0, 1.0,
                    apply = { v -> onPreview(s.copy(marginTop = v.roundToInt())); onCommitTypography(s.copy(marginTop = v.roundToInt())) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.marginBottom.toDouble(), 0.0, 200.0, 1.0,
                    apply = { v -> onPreview(s.copy(marginBottom = v.roundToInt())); onCommitTypography(s.copy(marginBottom = v.roundToInt())) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.marginLeft.toDouble(), 0.0, 200.0, 1.0,
                    apply = { v -> onPreview(s.copy(marginLeft = v.roundToInt())); onCommitTypography(s.copy(marginLeft = v.roundToInt())) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.marginRight.toDouble(), 0.0, 200.0, 1.0,
                    apply = { v -> onPreview(s.copy(marginRight = v.roundToInt())); onCommitTypography(s.copy(marginRight = v.roundToInt())) },
                    fmt = { "${it.roundToInt()}" }),
            )
            Sub.AnnotationColors -> {
                val slots = annotationColorSlots(s, annotationColorKind)
                val slotCount = slots.size.coerceAtLeast(1)
                val selected = annotationColorSlot.coerceIn(0, slotCount - 1)
                val slotColor = annotationSlotColor(s, annotationColorKind, selected)
                // 键盘：两行标签各自用 ←→ 挪色槽（挪动同时切到那一类），之后是红 / 绿 / 蓝 /
                // 不透明度四条滑条，最后是恢复默认颜色池（只恢复两类色槽，不动下面的批注样式）。
                // 行序号必须与页面里的 index 逐项对齐。
                fun tabKey(tab: AnnotationKind): ItemKey {
                    val tabSlots = annotationColorSlots(s, tab)
                    val n = tabSlots.size.coerceAtLeast(1)
                    val sel = if (annotationColorKind == tab) annotationColorSlot.coerceIn(0, n - 1) else 0
                    fun pick(next: Int) {
                        annotationColorKind = tab
                        annotationColorSlot = next
                    }
                    return ItemKey(
                        onEnter = { pick(sel) },
                        onLeft = { pick((sel - 1 + n) % n) },
                        onRight = { pick((sel + 1) % n) },
                    )
                }
                listOf(
                    tabKey(AnnotationKind.Highlight),
                    tabKey(AnnotationKind.Underline),
                    sliderKey(
                        annotationRed(slotColor).toDouble(),
                        0.0,
                        255.0,
                        1.0,
                        apply = { v ->
                            val next = annotationWithChannel(slotColor, 16, v.roundToInt())
                            onCommitLight(withAnnotationColorSlot(s, annotationColorKind, selected, next))
                        },
                        fmt = { "${it.roundToInt()}" },
                    ),
                    sliderKey(
                        annotationGreen(slotColor).toDouble(),
                        0.0,
                        255.0,
                        1.0,
                        apply = { v ->
                            val next = annotationWithChannel(slotColor, 8, v.roundToInt())
                            onCommitLight(withAnnotationColorSlot(s, annotationColorKind, selected, next))
                        },
                        fmt = { "${it.roundToInt()}" },
                    ),
                    sliderKey(
                        annotationBlue(slotColor).toDouble(),
                        0.0,
                        255.0,
                        1.0,
                        apply = { v ->
                            val next = annotationWithChannel(slotColor, 0, v.roundToInt())
                            onCommitLight(withAnnotationColorSlot(s, annotationColorKind, selected, next))
                        },
                        fmt = { "${it.roundToInt()}" },
                    ),
                    sliderKey(
                        annotationAlphaPercent(slotColor).toDouble(),
                        0.0,
                        100.0,
                        1.0,
                        apply = { v ->
                            val next = annotationWithAlpha(slotColor, annotationAlphaFromPercent(v.toFloat()))
                            onCommitLight(withAnnotationColorSlot(s, annotationColorKind, selected, next))
                        },
                        fmt = { "${it.roundToInt()}%" },
                    ),
                    ItemKey(onEnter = {
                        onCommitLight(withDefaultAnnotationColorSlots(s))
                        colorPoolResetCount++
                    }),
                    // 折叠所有批注：整页只留贴着划线的小图标。纯像素开关，走无重排提交。
                    // 放在这一组的最上面：它是「要不要看见批注」的总闸，打开后其余批注控件一律置灰。
                    ItemKey(onEnter = { onCommitLight(s.copy(noteCollapseAll = !s.noteCollapseAll)) }),
                    // 批注字体：复用正文那套字体下钻页（同一套系统 / 导入字体列表），
                    // 写入的是旁注覆盖层自己的族，不影响正文。
                    ItemKey(
                        enabled = !s.noteCollapseAll,
                        onEnter = { pickSlot = "noteFont"; stack.add(Sub.TextFont) },
                    ),
                    // 批注字号：滑条按**字号**走（0.1 一档），存的是相对正文旁注默认字号的倍率，
                    // 这样正文字号一变旁注等比变，读者看到的读数也能真正细调。
                    sliderKey(
                        noteFontSizeValue(s),
                        noteFontSizeMin(s),
                        noteFontSizeMax(s),
                        NOTE_FONT_SIZE_STEP,
                        apply = { v ->
                            onCommitLight(s.copy(noteFontScale = noteFontScaleFor(s, v)))
                        },
                        fmt = { noteFontSizeLabel(it) },
                        enabled = !s.noteCollapseAll,
                    ),
                    // 旁注框怎么画：描边 / 底色 / 文字用标注色。纯像素开关，走无重排提交。
                    // 折叠态下小图标有自己的固定画法，这三个开关不生效（下面渲染时置灰）。
                    ItemKey(
                        enabled = !s.noteCollapseAll,
                        onEnter = { onCommitLight(s.copy(noteBorder = !s.noteBorder)) },
                    ),
                    ItemKey(
                        enabled = !s.noteCollapseAll,
                        onEnter = { onCommitLight(s.copy(noteBackground = !s.noteBackground)) },
                    ),
                    ItemKey(
                        enabled = !s.noteCollapseAll,
                        onEnter = { onCommitLight(s.copy(noteTextSameColor = !s.noteTextSameColor)) },
                    ),
                )
            }
            Sub.Theme -> listOf(
                ItemKey(onEnter = { onCommitTypography(TypographicProfile.withLayoutTheme(s, "original")) }),
                ItemKey(onEnter = { onCommitTypography(TypographicProfile.withLayoutTheme(s, "modern")) }),
                ItemKey(onEnter = { onCommitTypography(TypographicProfile.withLayoutTheme(s, "traditional")) }),
            )
            Sub.ReadingTheme -> buildList {
                val n = allThemes.size
                allThemes.forEach { t ->
                    add(ItemKey(
                        onEnter = { onCommitTypography(s.copy(bgOverride = t.bg, fgOverride = t.fg)) },
                        // 网格内 ←→ 按格走（不出网格，上下才跨段）。
                        onLeft = { nav.move(-1, 1, n, { true }) { scope.ensureListVisible(listState, listPosOf(it), -1) } },
                        onRight = { nav.move(1, 1, n, { true }) { scope.ensureListVisible(listState, listPosOf(it), 1) } },
                    ))
                }
                add(ItemKey(onEnter = { onCommitTypography(s.copy(scheme = if (s.scheme == "night") "day" else "night")) }))
                add(sliderKey(s.eyeProtectionLevel.toDouble(), 0.0, 100.0, 1.0,
                    apply = { v -> onCommitLight(s.copy(eyeProtectionLevel = v.roundToInt())) },
                    fmt = { "${it.roundToInt()}%" }))
                add(ItemKey(onEnter = { stack.add(Sub.ThemePresetMgr) }))
            }
            Sub.ThemePresetMgr -> {
                val bgHex = if (s.bgOverride.isNullOrBlank()) ReaderThemeMath.DEFAULT_BG else s.bgOverride
                val fgHex = if (s.fgOverride.isNullOrBlank()) ReaderThemeMath.DEFAULT_FG else s.fgOverride
                val bg = ReaderThemeMath.parseHex(bgHex)
                val fg = ReaderThemeMath.parseHex(fgHex)
                listOf(
                    sliderKey(ReaderThemeMath.r(bg).toDouble(), 0.0, 255.0, 1.0,
                        apply = { v -> val n = ReaderThemeMath.applyBg(s, bg, fg, 0, v); onPreview(n); onCommitTypography(n) },
                        fmt = { it.roundToInt().toString() }),
                    sliderKey(ReaderThemeMath.g(bg).toDouble(), 0.0, 255.0, 1.0,
                        apply = { v -> val n = ReaderThemeMath.applyBg(s, bg, fg, 1, v); onPreview(n); onCommitTypography(n) },
                        fmt = { it.roundToInt().toString() }),
                    sliderKey(ReaderThemeMath.b(bg).toDouble(), 0.0, 255.0, 1.0,
                        apply = { v -> val n = ReaderThemeMath.applyBg(s, bg, fg, 2, v); onPreview(n); onCommitTypography(n) },
                        fmt = { it.roundToInt().toString() }),
                    sliderKey(ReaderThemeMath.grayOf(fg), 0.0, 255.0, 1.0,
                        apply = { v -> val n = ReaderThemeMath.applyFg(s, fg, v); onPreview(n); onCommitTypography(n) },
                        fmt = { it.roundToInt().toString() }),
                    ItemKey(onEnter = {
                        val next = ReaderThemeMath.saveTheme(customs, bgHex, fgHex)
                        if (next != customs) {
                            onSaveTheme(ThemePreset(ReaderThemeMath.labelFor(bgHex, fgHex), bgHex, fgHex))
                            customs = next
                        }
                    }),
                    ItemKey(onEnter = {
                        val next = ReaderThemeMath.deleteTheme(customs, bgHex, fgHex)
                        if (next != customs) {
                            customs.firstOrNull { it.bg == bgHex && it.fg == fgHex }?.let { onDeleteTheme(it) }
                            customs = next
                        }
                    }),
                )
            }
            Sub.Brightness -> {
                val follow = s.brightnessFollowSystem
                listOf(
                    ItemKey(onEnter = {
                        if (!follow) onCommitLight(s.copy(brightnessFollowSystem = true))
                        else {
                            val sysPct = readSystemBrightness()
                            onCommitLight(s.copy(brightnessFollowSystem = false,
                                brightness = (sysPct + s.brightnessOffset).coerceIn(-50, brightnessMax)))
                        }
                    }),
                    sliderKey(s.brightnessOffset.toDouble(), -20.0, 20.0, 1.0,
                        apply = { v -> onCommitLight(s.copy(brightnessOffset = v.roundToInt())) },
                        fmt = { v -> if (v > 0) "+${v.roundToInt()}" else "${v.roundToInt()}" },
                        enabled = follow),
                    sliderKey(s.brightness.toDouble(), -50.0, brightnessMax.toDouble(), 1.0,
                        apply = { v -> onCommitLight(s.copy(brightness = v.roundToInt())) },
                        fmt = { "${it.roundToInt()}%" },
                        enabled = !follow),
                    ItemKey(
                        onEnter = { if (!follow) onCommitLight(s.copy(brightnessGestureLeft = !s.brightnessGestureLeft)) },
                        enabled = !follow),
                    ItemKey(
                        onEnter = { if (!follow) onCommitLight(s.copy(brightnessGestureRight = !s.brightnessGestureRight)) },
                        enabled = !follow),
                    ItemKey(
                        onEnter = { if (!follow) onCommitLight(s.copy(brightnessGestureTwo = !s.brightnessGestureTwo)) },
                        enabled = !follow),
                )
            }
            Sub.AnimMode -> listOf(
                ItemKey(onEnter = { onCommitLight(s.copy(pageAnimationMode = "slide")) }),
                ItemKey(onEnter = { onCommitLight(s.copy(pageAnimationMode = "curl")) }),
            )
        }
        // itemCount 变化（字体/预设增删）时钳制 active。
        LaunchedEffect(keys.size) { if (keys.isNotEmpty()) nav.activeIdx = (nav.activeIdx ?: 0).coerceIn(keys.indices) }
        // 进字体页首落首字体行（按钮行不进上下序列；切回本页同样归位）。
        LaunchedEffect(current, fontStartIdx) {
            if (current == Sub.TextFont && keys.isNotEmpty()) nav.activeIdx = fontStartIdx.coerceIn(keys.indices)
        }
        fun isEnabled(i: Int) = keys.getOrNull(i)?.enabled == true
        fun visible(i: Int, dir: Int) = scope.ensureListVisible(listState, listPosOf(i), dir)
        // ---- 字体页焦点区（仅 TextFont 页）：按钮环(null=字体区) ----
        // 按钮不是字体：上下箭头够不着（首行下标见上），Tab 在环内循环，环内左右切换。
        // 环顺序：‹返回/X/[本地/无线/开关…]；下标即 Tab 顺序，null=字体区。
        val fontButtonRing: List<Pair<String, () -> Unit>> =
            if (current == Sub.TextFont) buildList {
                add("back" to ::onEscape)
                add("close" to onDismiss)
                if (fontRows.any { it is FontPanelRow.Import }) {
                    if (canFontImport) add("local" to onFontImport)
                    if (wifiReady) add("wifi" to { stack.add(Sub.WifiImport) })
                    add("toggle" to { onCommitLight(s.copy(showHiddenFonts = !s.showHiddenFonts)) })
                } else if (fontRows.any { it is FontPanelRow.Toggle }) {
                    add("toggle" to { onCommitLight(s.copy(showHiddenFonts = !s.showHiddenFonts)) })
                }
            } else emptyList()
        /** 在按钮区：true（上下/回车/左右走环，不碰字体行）。 */
        fun inFontButtons() = current == Sub.TextFont && fontButtonFocus != null
        /** 落字体行：清按钮焦点 + 高亮持有 + 跟随滚动（单步移动用，只越界才滚）。 */
        fun landFont(idx: Int, dir: Int) {
            fontButtonFocus = null
            nav.land(idx)
            visible(idx, dir)
        }
        /**
         * 跳转滚动（PgUp/PgDn/Home/End 用，用户层）：直达目标，焦点行恒可见。
         * 焦点与滚到顶的不是同一行时（如 PgUp 取新底端）分开传。
         * 注意：按钮区已搬出列表，LazyColumn 里全是字体行，不需要任何像素垫付——
         * 传 scrollOffset 会把目标行顶出视口之外（此前按"垫掉吸顶按钮高度"传了一行
         * 高度，PgDn 恒多翻约两行，即此 bug）。
         * PgUp 额外保证（用户层）：焦点行恒为可见区最后一行且底部完整可见（与下箭头
         * 的完整可见保证同口径）——只 scrollToItem(top) 会把焦点行落在半裁剪的底边，
         * 故按溢出像素再 scrollBy 一小段，把焦点行底对齐视口底（焦点仍是最后一行）。
         */
        fun jumpFontTo(focus: Int, top: Int) {
            if (current != Sub.TextFont || keys.isEmpty()) return
            val last = keys.lastIndex
            if (fontStartIdx > last) return
            val f = focus.coerceIn(fontStartIdx, last)
            val t = top.coerceIn(fontStartIdx, last)
            fontButtonFocus = null
            nav.land(f)
            scope.launch {
                runCatching {
                    listState.scrollToItem(listPosOf(t))
                    if (f != t) {
                        val fLazy = listPosOf(f)
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == fLazy }
                        if (item != null) {
                            val overflow = item.offset + item.size - info.viewportEndOffset
                            if (overflow > 0) listState.scrollBy(overflow.toFloat())
                        } else {
                            listState.scrollToItem(fLazy)
                        }
                    }
                }
            }
        }
        /** 整页翻：落到新顶端行（下翻）/新底端行（上翻），焦点跟去；可视行剔掉吸顶按钮。 */
        // 连发节流（用户层）：桌面按住 PgDn/PgUp 会连发 KeyDown（Compose 原样透传、
        // 无 repeat 标记可区分），一次稍长的按压翻掉多页；300ms 内只认第一次，
        // 有意连打仍以约 3 页/秒推进。
        var lastPageAt by remember { mutableStateOf<TimeMark?>(null) }
        fun pageFonts(dir: Int) {
            if (current != Sub.TextFont || keys.isEmpty()) return
            if ((lastPageAt?.elapsedNow() ?: PageThrottleMs.milliseconds) < PageThrottleMs.milliseconds) return
            lastPageAt = TimeSource.Monotonic.markNow()
            // 懒索引换算回行号（按钮区在列表外）。
            val vis = listState.layoutInfo.visibleItemsInfo.map { it.index + fontFixedCount }.filter { it >= fontStartIdx }
            if (dir > 0) {
                val target = (vis.maxOrNull()?.plus(1)) ?: fontStartIdx
                jumpFontTo(target, target)
            } else {
                // 整页上翻：新底端 = 旧顶端上一行，新顶端再往上翻一页。
                val page = vis.size.coerceAtLeast(1)
                val bottom = (vis.minOrNull() ?: (fontStartIdx + 1)) - 1
                jumpFontTo(bottom, bottom - page + 1)
            }
        }
        fun moveActive(dir: Int) = if (current == Sub.TextFont)
            nav.move(dir, 1, keys.size, ::isEnabled, lo = fontStartIdx) { visible(it, dir) }
        else nav.move(dir, 1, keys.size, ::isEnabled) { visible(it, dir) }
        fun onEnterActive() {
            if (inFontButtons()) {
                fontButtonFocus?.let { fontButtonRing.getOrNull(it)?.second?.invoke() }
                return
            }
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onEnter?.invoke() }
        }
        fun onLeftActive() {
            if (inFontButtons()) {
                val n = fontButtonRing.size
                if (n > 0) fontButtonFocus = (((fontButtonFocus ?: 0) - 1 + n) % n)
                return
            }
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onLeft?.invoke() }
        }
        fun onRightActive() {
            if (inFontButtons()) {
                val n = fontButtonRing.size
                if (n > 0) fontButtonFocus = (((fontButtonFocus ?: 0) + 1) % n)
                return
            }
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onRight?.invoke() }
        }
        /** Tab：按钮环循环（一轮末回字体区）；非字体页不管。 */
        fun onTabActive() {
            if (current != Sub.TextFont || fontButtonRing.isEmpty()) return
            val cur = fontButtonFocus
            fontButtonFocus = if (cur == null) 0 else if (cur + 1 < fontButtonRing.size) cur + 1 else null
        }
        fun onUpActive() {
            val a = nav.activeIdx ?: return
            // 网格区内 ↑↓ 按列跳（3 列），出网格即越界到下一段。
            if (current == Sub.ReadingTheme && a < allThemes.size) {
                if (a - GridCols < 0) return
                nav.move(-1, GridCols, allThemes.size, { true }) { visible(it, -1) }
                return
            }
            moveActive(-1)
        }
        fun onDownActive() {
            val a = nav.activeIdx ?: return
            if (current == Sub.ReadingTheme && a < allThemes.size) {
                if (a + GridCols >= allThemes.size) {
                    // 底行 ↓ 越界到夜间开关。
                    PanelNav.resolveFrom(allThemes.size, 1, keys.size, ::isEnabled)
                        ?.let { nav.land(it); visible(it, 1) }
                } else {
                    nav.move(1, GridCols, allThemes.size, { true }) { visible(it, 1) }
                }
                return
            }
            moveActive(1)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val drawerWidth = if (maxWidth < 600.dp) maxWidth * 0.85f else 360.dp
            val drawerPx = remember(drawerWidth, density) { with(density) { drawerWidth.toPx() } }
            Box(modifier = Modifier.fillMaxSize()) {
                // Dark mask over the area left of the panel; shown only after docking, faded before sliding out.
                // 退场动画中（mounted 但 !visible）遮罩立即停止拦截：否则关闭后 300ms 内的点按
                // 被退场中的遮罩吃掉（"关面板后点左侧没反应"），还顺带触发第二次全书重排。
                // 诊断：遮罩点按落盘，与 Orilumn.TAP 对账。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(maskAlpha)
                        .background(Color.Black.copy(alpha = PanelMaskAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = visible,
                            onClick = {
                                Logger.d("Orilumn.TAP", "panel-mask tap → dismiss")
                                onDismiss()
                            },
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset((slide.value * drawerPx).roundToInt(), 0) }
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .background(p.bg)
                        .focusRequester(drawerFr)
                        .focusTarget()
                        .panelKeyEvents(
                            onUp = {
                                if (inFontButtons()) jumpFontTo(keys.lastIndex, keys.lastIndex)
                                else onUpActive()
                            },
                            onDown = {
                                if (inFontButtons()) jumpFontTo(fontStartIdx, fontStartIdx)
                                else onDownActive()
                            },
                            onEnter = ::onEnterActive,
                            onEscape = ::onEscape,
                            onLeft = ::onLeftActive,
                            onRight = ::onRightActive,
                            onTab = ::onTabActive,
                            onPgUp = { pageFonts(-1) },
                            onPgDn = { pageFonts(1) },
                            onHome = {
                                if (current == Sub.TextFont && keys.isNotEmpty()) {
                                    jumpFontTo(fontStartIdx, fontStartIdx)
                                }
                            },
                            onEnd = {
                                if (current == Sub.TextFont && keys.isNotEmpty()) {
                                    jumpFontTo(keys.lastIndex, keys.lastIndex)
                                }
                            },
                        )
                        // 点按消费（不抢焦点）：杂散点按不穿透到遮罩关闭层。
                        // 仅面板可见时消费——退场动画中已滑出屏幕，不再占右侧触摸。
                        .pointerInput(visible) { if (visible) detectTapGestures(onTap = {}) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 8.dp)
                            .background(p.bg),
                    ) {
                        if (stack.size > 1) {
                            // Larger tap target so the back tap is never missed and doesn't fall through to the close layer.
                            // 字体页 Tab 环焦点态同行级高亮；按钮不抢焦点（回车留给导航）。
                            val backFocused = current == Sub.TextFont &&
                                fontButtonFocus?.let { fontButtonRing.getOrNull(it)?.first } == "back"
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (backFocused) p.rowActive else Color.Transparent)
                                    .focusProperties { canFocus = false }
                                    .clickable { stack.removeAt(stack.lastIndex) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("‹", color = p.text, fontSize = 20.sp)
                            }
                        }
                        Text(
                            // 字体页是四个槽位共用的：批注字体单独标题，别让读者以为在改正文字体。
                            if (current == Sub.TextFont && slotKey == "noteFont") "批注字体" else current.title,
                            color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        val closeFocused = current == Sub.TextFont &&
                            fontButtonFocus?.let { fontButtonRing.getOrNull(it)?.first } == "close"
                        Text("✕", color = p.text, fontSize = 18.sp, modifier = Modifier
                            .align(Alignment.CenterEnd).padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (closeFocused) p.rowActive else Color.Transparent)
                            .focusProperties { canFocus = false }
                            .clickable(onClick = onDismiss))
                    }
                    HLine(p, modifier = Modifier.fillMaxWidth())
                    Box(modifier = Modifier.weight(1f)) {
                        val onMove: () -> Unit = { nav.releaseHold() }
                        when (current) {
                            Sub.Home -> HomePage(s, keys, nav, onMove, listState, onCommitTypography, onCommitLight, customs, p)
                            Sub.Text -> TextPage(s, keys, nav, onMove, listState, p, onPreview, onCommitTypography, fontDisplayByFamily)
                            Sub.TextFont -> FontLibraryPanel(
                                rows = fontRows,
                                nav = nav,
                                p = p,
                                onSelect = { family -> commitSlot(setField(s, slotKey, family)) },
                                onHide = onFontHide,
                                onUnhide = onFontUnhide,
                                onDelete = onFontDelete,
                                onImport = onFontImport,
                                // 无线走面板内下钻（返回‹/Esc 即回字体列表），不经壳弹框。
                                onImportWifi = { stack.add(Sub.WifiImport) },
                                // 多字重行点按下钻字重页（与键盘共用 enterFontRow 口径）；
                                // 批注字体同样进字重页，选完族 + 字重一起落盘。
                                onPickWeight = { family ->
                                    weightPickFamily = family
                                    stack.add(Sub.WeightPicker)
                                },
                                showLocalButton = canFontImport,
                                showWifiButton = wifiReady,
                                showHidden = s.showHiddenFonts,
                                onToggleHidden = { onCommitLight(s.copy(showHiddenFonts = !s.showHiddenFonts)) },
                                importFocusedKey = fontButtonFocus?.let { fontButtonRing.getOrNull(it)?.first }
                                    ?.takeIf { it == "local" || it == "wifi" || it == "toggle" },
                                toggleFocused = fontButtonFocus?.let { fontButtonRing.getOrNull(it)?.first } == "toggle",
                                onMouseMove = onMove,
                                listState = listState,
                                scanProgress = fontScanProgress,
                                // 批注字体槽位的「跟随」是跟界面默认字体（旁注不套书里的正文族）。
                                followLabel = if (slotKey == "noteFont") "跟随界面" else "跟随原书",
                                followSubtitle = if (slotKey == "noteFont") "使用界面默认字体" else "使用原书字体",
                            )
                            Sub.WeightPicker -> {
                                val fam = weightPickFamily
                                if (fam != null) {
                                    val opts = fontEntries.filter { it.family == fam }.weightChoices()
                                    // 非本槽位当前字体只留键盘/悬停高亮，不标金色选中。
                                    val isCurrent = fieldOf(s, slotKey) == fam
                                    WeightPickerPage(
                                        options = opts,
                                        selectedWeight = if (isCurrent) slotWeightOf(s, slotKey, fam) else null,
                                        nav = nav,
                                        p = p,
                                        onPickWeight = { w ->
                                            commitSlot(withSlotWeight(s, slotKey, fam, w))
                                        },
                                        onMouseMove = onMove,
                                        listState = listState,
                                    )
                                }
                            }
                            Sub.WifiImport -> WifiImportPage(
                                uploadDirPath = wifiUploadDir,
                                onUploadFile = onWifiUpload,
                                p = p,
                            )
                            Sub.Spacing -> SpacingPage(s, keys, nav, onMove, listState, p, onPreview, onCommitTypography)
                            Sub.Theme -> ThemePage(s, keys, nav, onMove, listState, onCommitTypography, p)
                            Sub.ReadingTheme -> ReadingThemePage(s, allThemes, keys, nav, onMove, listState, onPreview, onCommitTypography, onCommitLight, p)
                            Sub.ThemePresetMgr -> ThemePresetManagerPage(s, keys, nav, onMove, listState, onPreview, onCommitTypography, customs,
                                onCustomsChanged = { customs = it },
                                onSaveTheme = onSaveTheme,
                                onDeleteTheme = onDeleteTheme,
                                p)
                            Sub.Brightness -> BrightnessPage(s, keys, nav, onMove, listState, onCommitLight, readSystemBrightness, p, brightnessMax)
                            Sub.AnimMode -> AnimModePage(s, keys, nav, onMove, listState, onCommitLight, p)
                            Sub.AnnotationColors -> AnnotationColorPage(
                                s = s,
                                kind = annotationColorKind,
                                selectedSlot = annotationColorSlot,
                                keys = keys,
                                nav = nav,
                                onMouseMove = onMove,
                                listState = listState,
                                commitLight = onCommitLight,
                                previewLight = onPreviewLight,
                                onSelectKind = { annotationColorKind = it },
                                onSelectSlot = { k, i ->
                                    annotationColorKind = k
                                    annotationColorSlot = i
                                },
                                /** 刚才按过「恢复默认颜色池」：按钮短暂换成「已恢复」并金色高亮。 */
                                resetFlash = colorPoolResetFlash,
                                noteFontLabel = fieldOf(s, "noteFont").let {
                                    if (it.isEmpty()) "跟随界面" else fontDisplayByFamily[it] ?: genericFamilyLabel(it)
                                },
                                p = p,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WIFI 导入下钻页（双端同页）：服务走共享 [FontUploadServer]（与旧两端对话框同一实现、
 * 同一端口约定），进页启动、离页停止（暂存清理沿服务 `stop` 语义）。
 * 壳只给暂存目录 + 落盘入库回调（线程自理）；返回‹/Esc 即回字体列表（栈导航自带）。
 */
@Composable
private fun WifiImportPage(
    uploadDirPath: String,
    onUploadFile: (String) -> Unit,
    p: Palette,
) {
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf<String?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    val server = remember(uploadDirPath) {
        FontUploadServer(uploadDirPath) { _, path ->
            scope.launch { onUploadFile(path) }
        }
    }
    DisposableEffect(server) {
        val a = server.start()
        if (a == null) startFailed = true else addr = a
        onDispose { server.stop() }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        when {
            startFailed -> Text(
                text = "启动服务失败：请确认设备已连接到 Wi-Fi，然后重试。",
                color = p.text, fontSize = 14.sp,
            )
            addr == null -> Text("正在启动服务器…", color = p.muted, fontSize = 14.sp)
            else -> {
                Text(
                    text = "导入设备与当前设备必须在同一个局域网内。在导入设备浏览器中打开以下地址：",
                    color = p.text, fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.borderSoft)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        Text(addr ?: "", color = p.text, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("支持 .ttf / .otf / .ttc 格式字体文件，可多选。", color = p.muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("本机会自动剔除重复导入，并合并不同字重的字体。", color = p.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private enum class Sub(val title: String) {
    Home("设置"), Text("文字"), TextFont("字体管理"), WeightPicker("字重"), WifiImport("WIFI 导入"), Spacing("间距"),
    Theme("排版主题"), ReadingTheme("阅读主题"), ThemePresetMgr("预设管理"),
    Brightness("亮度"), AnimMode("翻页动画模式"), AnnotationColors("笔记"),
}

/** 键盘项：click 与键盘共用同一回调；网格格的左右由调用方配成步进，滑块的左右配成调值。 */
private data class ItemKey(
    val onEnter: () -> Unit = {},
    val onLeft: () -> Unit = {},
    val onRight: () -> Unit = {},
    val enabled: Boolean = true,
)

/** 字间距滑块的取值（档位 -100..100 = -0.2em..0.2em）：读者配了就用它的槽位，
 *  没配（`null` = 按书）就用原书正文实测字距换算成槽位（与引擎实际排版同值）。 */
private fun ReaderSettings.letterSpacingSlot(): Double =
    letterSpacing ?: ((probeLetterSpacing ?: 0.0) * 500.0).coerceIn(-100.0, 100.0)

/** 滑块项：←→ 按步长调值（与 −/+ 按钮同 snap 网格、同 preview/commit 口径）。 */
private fun sliderKey(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    apply: (Double) -> Unit,
    fmt: (Double) -> String,
    enabled: Boolean = true,
): ItemKey {
    fun snap(v: Double): Double {
        val n = ((v - min) / step).roundToInt()
        val raw = min + n * step
        return ((raw * 1e6).roundToLong() / 1e6).coerceIn(min, max)
    }
    val clamped = value.coerceIn(min, max)
    return ItemKey(
        onLeft = { if (enabled) apply(snap(clamped - step)) },
        onRight = { if (enabled) apply(snap(clamped + step)) },
        enabled = enabled,
    )
}

private const val GridCols = 3

// ================= Home =================

@Composable
private fun HomePage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    commit: (ReaderSettings) -> Unit,
    /** 不影响排版的纯 UI 开关走它（无重排提交，与 showHiddenFonts 同口径）。 */
    commitPlain: (ReaderSettings) -> Unit,
    customs: List<ThemePreset>,
    p: Palette,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item { SetRow("排版主题", ReaderThemeMath.layoutThemeLabel(s.layoutTheme), keys[0].onEnter, p, nav = nav, index = 0) }
        item { SetRow("间距", onTap = keys[1].onEnter, p = p, nav = nav, index = 1) }
        item { SetRow("文字", onTap = keys[2].onEnter, p = p, nav = nav, index = 2) }
        item { SetRow("笔记", onTap = keys[3].onEnter, p = p, nav = nav, index = 3) }
        item { SetRow("阅读主题", ReaderThemeMath.themeName(s, customs), keys[4].onEnter, p, nav = nav, index = 4) }
        item { SetRow("亮度", if (s.brightnessFollowSystem) "跟随系统" else "自定义", keys[5].onEnter, p, nav = nav, index = 5) }
        item { SetSwitch("翻页动画", s.pageAnim, { commitPlain(s.copy(pageAnim = it)) }, p, nav = nav, index = 6) }
        item { SetRow("翻页动画模式", ReaderThemeMath.pageAnimationModeLabel(s.pageAnimationMode), keys[7].onEnter, p, nav = nav, index = 7) }
        item { SetSwitch("封面拉伸全屏", s.coverStretch, { commitPlain(s.copy(coverStretch = it)) }, p, nav = nav, index = 8) }
        item { SetSwitch("启动时继续阅读", s.autoContinue, { commitPlain(s.copy(autoContinue = it)) }, p, nav = nav, index = 9) }
        item { SetSwitch("显示页码", s.pageNum, { commitPlain(s.copy(pageNum = it)) }, p, nav = nav, index = 10) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Text page (fonts + text size + letter spacing + character scale) =================

@Composable
private fun TextPage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    p: Palette,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
    /** 族 → 展示名（调用方由 fontEntries 预建；缺席回退族名本身，另给 CSS 通用族 3 个中文标签）。 */
    fontDisplayByFamily: Map<String, String>,
) {
    val labelW = sliderLabelWidth(listOf("字号", "字间距", "混排字距"), p)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        // Three font-replacement tiers (body/heading/code); each row drills down into the font panel.
        FONT_FIELDS.forEachIndexed { i, (label, key) ->
            item { SetRow(label, fieldOf(s, key).let { if (it.isEmpty()) "跟随原书" else fontDisplayByFamily[it] ?: genericFamilyLabel(it) }, keys[i].onEnter, p, nav = nav, index = i) }
        }
        // "Font size" slider: placed below the font rows.
        item { UiSliderRow("字号", 9.0, 36.0, 0.1, s.fontSize * ReaderSettings.fontScaleToRatio(s.fontScale),
            { String.format("%.1f", it) },
            { px -> preview(s.copy(fontScale = ReaderThemeMath.pxToScale(px))) },
            { px -> commit(s.copy(fontScale = ReaderThemeMath.pxToScale(px))) }, p, labelWidth = labelW,
            nav = nav, index = 3) }
        // Letter spacing: UI value range -100..100 (step 1), internally mapped to -0.2em..0.2em.
        // 字间距：**em 语义**（-100..100 档 = -0.2em..0.2em）。读者没配（null）时滑条显示
        // 原书正文实测字距（引擎就是按它排的，见 `BoxChapterLayouter.chapterLetterSpacingEm`）。
        item { UiSliderRow("字间距", -100.0, 100.0, 1.0, s.letterSpacingSlot(), { it.roundToInt().toString() },
            { preview(s.copy(letterSpacing = it)) }, { commit(s.copy(letterSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 4) }
        // 混排字距（用户可见名 2026-10-03 由「中西字距」改名；内部标识符与 JSON 键仍是
        // `cjkLatinSpacing`，改名会改掉持久化键、作废刚写好的 schemaVersion 迁移）：
        // 0..100 映射到 0..1.0em，**默认 25**（= 0.25em，CLREQ 值）；0 是合法档位 = 关掉间隙，
        // 此时中西之间**只**靠「字间距」分隔，本功能不做任何额外动作。
        item { UiSliderRow("混排字距", 0.0, 100.0, 1.0, s.cjkLatinSpacing,
            { it.roundToInt().toString() },
            { preview(s.copy(cjkLatinSpacing = it)) }, { commit(s.copy(cjkLatinSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 5) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * F4a：旧纯别名选择页（`FontPickSubPage`）已删，字体下钻统一走 [FontLibraryPanel]
 * （系统 + 导入同列 + 隐藏/删除 + 真字形预览）。
 */

@Composable
private fun SpacingPage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    p: Palette,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
) {
    val labelW = sliderLabelWidth(listOf("首行缩进", "行距", "段间距", "疏密", "上边距", "下边距", "左边距", "右边距"), p)
    // 排版量滑块永远可调 (用户最高优先级); 原书设置 只是把值重置为中性默认.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        // 首行缩进 (em): 读者设过就按读者值发声明（0 = 明确不缩进，压掉书的缩进）；
        // 没设过（null）时滑条显示原书的实测读数，渲染仍由书自己的 CSS 生效。
        item { UiSliderRow("首行缩进", 0.0, 10.0, 1.0, s.firstLineIndent ?: s.probeIndent ?: 0.0,
            { "${it.roundToInt()}" },
            { preview(s.copy(firstLineIndent = it)) }, { commit(s.copy(firstLineIndent = it)) }, p, labelWidth = labelW,
            nav = nav, index = 0) }
        // 行距：读者设过就显示读者值；没设过（null）显示原书实测行高，书没写就显示引擎初值 1.5
        // （面板显示的是**实际生效值**，不设「跟随原书」伪档位；拖动即写本书显式值）。
        item { UiSliderRow("行距", 0.5, 2.5, 0.1, s.lineSpacing ?: s.probeLineSpacing ?: CssDefaults.DEFAULT_LINE_HEIGHT.toDouble(),
            { String.format("%.1f", it) },
            { preview(s.copy(lineSpacing = it)) }, { commit(s.copy(lineSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 1) }
        // 段间距：**绝对值 em**（0..2）。读者没配（null）时滑条显示原书实测段距（含 UA 基线 1em），
        // 渲染仍按书自己的 margin；拖动即写本书显式值，p/li 的上下外边距从此固定为该 em。
        // 2026-10-29 由百分比乘算改回绝对值：百分比会让同一条滑块在不同书上代表不同物理间距。
        item { UiSliderRow("段间距", 0.0, 2.0, 0.1, s.paragraphSpacing ?: s.probeParagraphSpacing ?: 0.0,
            { String.format("%.1f", it) },
            { preview(s.copy(paragraphSpacing = it)) }, { commit(s.copy(paragraphSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 2) }
        // 疏密：p/li 之外一切块级纵边距**按比例乘算**（10 = 100% = 原书节奏，0 = 结构块边距清零）；
        // 与段间距的绝对值不是同一档语义，滑块取值 0..100。
        item { UiSliderRow("疏密", 0.0, 100.0, 1.0, s.paragraphGap / 10.0,
            { "${it.roundToInt()}" },
            { preview(s.copy(paragraphGap = it * 10.0)) }, { commit(s.copy(paragraphGap = it * 10.0)) }, p, labelWidth = labelW,
            nav = nav, index = 3) }
        // 边距 (设备级几何) 不随 原书设置 禁用.
        item { UiSliderRow("上边距", 0.0, 200.0, 1.0, s.marginTop.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginTop = it.roundToInt())) }, { commit(s.copy(marginTop = it.roundToInt())) }, p, labelWidth = labelW,
            nav = nav, index = 4) }
        item { UiSliderRow("下边距", 0.0, 200.0, 1.0, s.marginBottom.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginBottom = it.roundToInt())) }, { commit(s.copy(marginBottom = it.roundToInt())) }, p, labelWidth = labelW,
            nav = nav, index = 5) }
        item { UiSliderRow("左边距", 0.0, 200.0, 1.0, s.marginLeft.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginLeft = it.roundToInt())) }, { commit(s.copy(marginLeft = it.roundToInt())) }, p, labelWidth = labelW,
            nav = nav, index = 6) }
        item { UiSliderRow("右边距", 0.0, 200.0, 1.0, s.marginRight.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginRight = it.roundToInt())) }, { commit(s.copy(marginRight = it.roundToInt())) }, p, labelWidth = labelW,
            nav = nav, index = 7) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Typography theme page =================

@Composable
private fun ThemePage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    commit: (ReaderSettings) -> Unit,
    p: Palette,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        // 三个按钮都只切 `layoutTheme` 这个开关：基线交给主题层（`modern.css` / `traditional.css`），
        // 读者层不再把主题值钉进本书（写了反而盖掉主题层口径）。
        // 「原书设置」= 清空本书的排版覆盖（字体槽/行距/字距/段距/疏密/缩进回预设），本书重新完全听书；
        // 不动字号与页边距。见 `docs/设置模型整改计划.md` §3.5。
        item { ThemeOpt("原书设置", s.layoutTheme == "original", keys[0].onEnter, p, nav = nav, index = 0) }
        item { ThemeOpt("现代模式", s.layoutTheme == "modern", keys[1].onEnter, p, nav = nav, index = 1) }
        item { ThemeOpt("传统模式", s.layoutTheme == "traditional", keys[2].onEnter, p, nav = nav, index = 2) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private val FONT_FIELDS = listOf("正文" to "fontBody", "标题" to "fontTitle", "代码" to "fontCode")

/**
 * 字体槽位读写：正文 / 标题 / 代码三槽之外，笔记页的「批注字体」也复用同一套字体下钻页
 * （[FontLibraryPanel]），槽位键 `noteFont`。它的提交走无重排路径（见 `commitSlot`）。
 *
 * 批注换族时一并清掉旧字重：字重没按族记账（独立字段），留着上一个族的粗档会让新族
 * 莫名其妙地变粗。字重页写入时是「先 setField 再填字重」，清掉不碍事。
 */
private fun fieldOf(s: ReaderSettings, key: String) = when (key) {
    "fontTitle" -> s.fontTitle
    "fontCode" -> s.fontCode
    "noteFont" -> s.noteFont
    else -> s.fontBody
}
private fun setField(s: ReaderSettings, key: String, v: String) = when (key) {
    "fontTitle" -> s.copy(fontTitle = v)
    "fontCode" -> s.copy(fontCode = v)
    "noteFont" -> s.copy(noteFont = v, noteFontWeight = 0)
    else -> s.copy(fontBody = v)
}

/**
 * 槽位当前字重：正文三槽读按槽锚点（旧全局表兜底），批注字体读自己的独立字段。
 *
 * 批注不是书本排版的一部分，不能把锚点塞进 `fontWeightAnchorsBySlot`：那张表按书私有
 * 且进 [orilumn.reader.engine.text.LayoutParamKey]，会让旁注换字重误作废分页表。
 */
private fun slotWeightOf(s: ReaderSettings, key: String, family: String): Int? =
    if (key == "noteFont") {
        s.noteFontWeight.takeIf { it in 100..900 }
    } else {
        s.fontWeightAnchorsBySlot["$key|$family"] ?: s.fontWeightAnchors[family]
    }

/** 写入某个族的字重（与 [slotWeightOf] 对称），返回可交给 `commitSlot` 的新设置。 */
private fun withSlotWeight(s: ReaderSettings, key: String, family: String, weight: Int): ReaderSettings =
    if (key == "noteFont") {
        setField(s, key, family).copy(noteFontWeight = weight)
    } else {
        setField(s, key, family).copy(
            fontWeightAnchorsBySlot = s.fontWeightAnchorsBySlot + ("$key|$family" to weight),
        )
    }

/**
 * 批注字号滑条的步长：**显示字号** 0.1 一档（不是存盘的倍率）。
 *
 * 存的是倍率（正文字号一变旁注等比变），但读者看的是右侧那个字号读数；若按「倍率 0.1 一档」
 * 去走，读数一跳就是 `18 × 0.72 × 0.1 ≈ 1.3` 号，等于根本没有细档（读者报「每一档是 1.3」）。
 * 所以滑条的值一律用**字号**表示，提交时才换算回倍率。
 */
private const val NOTE_FONT_SIZE_STEP = 0.1

/** 批注字号的基准字号：正文主字号 × 旁注比例（倍率 = 1 时旁注多大）。 */
private fun noteFontBase(s: ReaderSettings): Double =
    s.fontSize * ReaderSettings.fontScaleToRatio(s.fontScale) * NOTE_BUBBLE_FONT_RATIO

/** 滑条的当前值：旁注实际字号（与「文字」页字号同口径）。 */
private fun noteFontSizeValue(s: ReaderSettings): Double = noteFontBase(s) * s.noteFontScale

/**
 * 滑条值（字号）→ 落盘倍率。
 *
 * 上下限仍按倍率的 [ReaderSettings.MIN_NOTE_FONT_SCALE] / [ReaderSettings.MAX_NOTE_FONT_SCALE] 夹，
 * 滑条的端点也用同一对边界换算（见 [noteFontSizeMin] / [noteFontSizeMax]）。
 */
private fun noteFontScaleFor(s: ReaderSettings, size: Double): Float =
    (size / noteFontBase(s)).toFloat()
        .coerceIn(ReaderSettings.MIN_NOTE_FONT_SCALE, ReaderSettings.MAX_NOTE_FONT_SCALE)

/** 滑条下限：倍率下限换算成字号，并落到 0.1 的整格上（否则每一档都带小数尾巴）。 */
private fun noteFontSizeMin(s: ReaderSettings): Double =
    ((noteFontBase(s) * ReaderSettings.MIN_NOTE_FONT_SCALE) * 10.0).roundToLong() / 10.0

/**
 * 批注字号的**绝对上限**（与「文字」页字号同口径）：滑条右端最多停在这里。
 *
 * 存的仍是倍率（正文字号变了旁注等比变），但读者能拖到的最大值不再随正文字号一起飘到
 * 二十几、三十几 —— 再大就不像一行旁注了。
 */
private const val NOTE_FONT_SIZE_MAX = 25.0

/**
 * 滑条上限：倍率上限换算成字号，但不超过 [NOTE_FONT_SIZE_MAX]，并落到 0.1 的整格上。
 *
 * 正文字号小的时候倍率上限先到（旁注不该比正文大一倍多），正文字号大的时候 25 先到。
 * 极端情况下（正文字号 36 又拉到最大缩放）倍率下限换算出来已经超过 25，此时保住一格可拖动，
 * 不让上下限反过来。
 */
private fun noteFontSizeMax(s: ReaderSettings): Double {
    val byRatio = noteFontBase(s) * ReaderSettings.MAX_NOTE_FONT_SCALE
    val cap = maxOf(minOf(byRatio, NOTE_FONT_SIZE_MAX), noteFontSizeMin(s) + NOTE_FONT_SIZE_STEP)
    return (cap * 10.0).roundToLong() / 10.0
}

/** 滑条右侧读数：就是字号本身，0.1 一档老老实实显示成 0.1。 */
private fun noteFontSizeLabel(size: Double): String = String.format("%.1f", size)

/** CSS 通用族名（排版主题写进槽位的默认值）→ 中文标签；真正的字族展示由 [fontDisplayByFamily] 给出。 */
private fun genericFamilyLabel(family: String): String = when (family) {
    "serif" -> "衬线字体"
    "sans-serif" -> "默认字体"
    "monospace" -> "等宽字体"
    else -> family
}

// ================= Reading-theme page (preset grid + night switch + blue-light + preset-manager entry) =================

@Composable
private fun ReadingThemePage(
    s: ReaderSettings,
    allThemes: List<ThemePreset>,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
    lightCommit: (ReaderSettings) -> Unit,
    p: Palette,
) {
    val n = allThemes.size
    val chunks = remember(allThemes) { allThemes.chunked(GridCols) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        items(chunks.size) { r ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunks[r].forEachIndexed { c, t ->
                    val idx = r * GridCols + c
                    val night = s.scheme == "night"
                    // In night mode show each card as its auto-generated night counterpart so the
                    // swatches preview how the background/text will actually look while reading.
                    val dayBg = ReaderThemeMath.parseHex(t.bg.ifBlank { ReaderThemeMath.DEFAULT_BG })
                    val cellBg = if (night) TypographicProfile.nightBackgroundOf(dayBg) else dayBg
                    val dayFg = ReaderThemeMath.parseHex(t.fg.ifBlank { ReaderThemeMath.DEFAULT_FG })
                    val cellFg = if (night) TypographicProfile.nightForegroundOf(dayFg) else dayFg
                    PresetCell(
                        label = t.label, bg = Color(0xFF000000.toInt() or cellBg),
                        fg = Color(0xFF000000.toInt() or cellFg),
                        // Built-in themes share the same deep-ink foreground, and any custom preset whose
                        // background equals a built-in one is already filtered out of the grid, so a match on
                        // background alone is sufficient and also covers legacy presets that kept the old fg.
                        selected = (s.bgOverride == t.bg),
                        p = p,
                        onClick = keys[idx].onEnter,
                        nav = nav,
                        index = idx,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不满 3 格时占位补齐，保持列宽一致。
                repeat(GridCols - chunks[r].size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // Night-mode switch (replaces the old day/night selector) below the preset grid. Global-only field,
        // but it changes text color so it must go through the typography (relayout) commit path.
        item { SetSwitch("夜间模式", s.scheme == "night",
            { commit(s.copy(scheme = if (it) "night" else "day")) }, p,
            nav = nav, index = n) }
        // Blue-light filter slider, moved here from the brightness panel, below the night-mode switch.
        item { UiSliderRow("护眼", 0.0, 100.0, 1.0, s.eyeProtectionLevel.toDouble(), { "${it.roundToInt()}%" },
            { lightCommit(s.copy(eyeProtectionLevel = it.roundToInt())) }, { lightCommit(s.copy(eyeProtectionLevel = it.roundToInt())) }, p,
            nav = nav, index = n + 1) }
        // Entry to the preset-manager sub-panel (color sliders + save/delete).
        item { SetRow("预设管理", onTap = keys[n + 2].onEnter, p = p, nav = nav, index = n + 2) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Preset-manager page (RGB/gray sliders + save/delete) =================

@Composable
private fun ThemePresetManagerPage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
    customs: List<ThemePreset>,
    onCustomsChanged: (List<ThemePreset>) -> Unit,
    onSaveTheme: (ThemePreset) -> Unit,
    onDeleteTheme: (ThemePreset) -> Unit,
    p: Palette,
) {
    val bgHex = if (s.bgOverride.isNullOrBlank()) ReaderThemeMath.DEFAULT_BG else s.bgOverride
    val fgHex = if (s.fgOverride.isNullOrBlank()) ReaderThemeMath.DEFAULT_FG else s.fgOverride
    val bg = ReaderThemeMath.parseHex(bgHex)
    val fg = ReaderThemeMath.parseHex(fgHex)
    val labelW = sliderLabelWidth(listOf("红", "绿", "蓝", "灰度"), p)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item { UiSliderRow("红", 0.0, 255.0, 1.0, ReaderThemeMath.r(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(ReaderThemeMath.applyBg(s, bg, fg, 0, v)) }, { v -> commit(ReaderThemeMath.applyBg(s, bg, fg, 0, v)) }, p, labelWidth = labelW,
            nav = nav, index = 0) }
        item { UiSliderRow("绿", 0.0, 255.0, 1.0, ReaderThemeMath.g(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(ReaderThemeMath.applyBg(s, bg, fg, 1, v)) }, { v -> commit(ReaderThemeMath.applyBg(s, bg, fg, 1, v)) }, p, labelWidth = labelW,
            nav = nav, index = 1) }
        item { UiSliderRow("蓝", 0.0, 255.0, 1.0, ReaderThemeMath.b(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(ReaderThemeMath.applyBg(s, bg, fg, 2, v)) }, { v -> commit(ReaderThemeMath.applyBg(s, bg, fg, 2, v)) }, p, labelWidth = labelW,
            nav = nav, index = 2) }
        item { UiSliderRow("灰度", 0.0, 255.0, 1.0, ReaderThemeMath.grayOf(fg), { it.roundToInt().toString() },
            { v -> preview(ReaderThemeMath.applyFg(s, fg, v)) }, { v -> commit(ReaderThemeMath.applyFg(s, fg, v)) }, p, labelWidth = labelW,
            nav = nav, index = 3) }
        // 保存/删除各占一行（原先左右并排）：键盘 active 逐行走，回车触发，与其余行一致。
        item {
            PingButton("保存", p, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = keys[4].onEnter,
                nav = nav, index = 4)
        }
        item {
            PingButton("删除", p, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), red = true, onClick = keys[5].onEnter,
                nav = nav, index = 5)
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun PresetCell(
    label: String,
    bg: Color,
    fg: Color,
    selected: Boolean,
    p: Palette,
    onClick: () -> Unit,
    nav: PanelNav,
    index: Int,
    modifier: Modifier = Modifier,
) {
    // 格底色是主题预览色、不能直接盖底：active 时外垫一层 rowActive 衬底（与目录同视觉语言）。
    // 内格同样禁默认指示，高亮只走 active 单源。
    val clickSrc = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .then(if (selected) Modifier.border(2.dp, p.selBorder, RoundedCornerShape(8.dp)) else Modifier)
                .clickable(onClick = onClick, interactionSource = clickSrc, indication = null)
                .panelHover(nav, index)
                .kbRing(),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = fg, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PingButton(
    label: String,
    p: Palette,
    modifier: Modifier = Modifier,
    red: Boolean = false,
    /** 按下反馈：整块换成金色描边 + 高亮底，和「只是聚焦」区分开（见 [ColorPoolResetFlashMs]）。 */
    flash: Boolean = false,
    onClick: () -> Unit,
    nav: PanelNav,
    index: Int,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Text(
        label, color = when {
            flash -> PanelGold
            red -> CancelRed
            else -> p.text
        }, fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (flash || nav.activeIdx == index) p.rowActive else p.cardBg)
            .border(1.dp, if (flash) PanelGold else p.cardBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick, interactionSource = clickSrc, indication = null)
            .panelHover(nav, index)
            .kbRing()
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
    )
}

// ================= Brightness page (follow/offset/brightness + eye-protection + blue-light + gesture) =================

@Composable
private fun BrightnessPage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    commit: (ReaderSettings) -> Unit,
    readSystemBrightness: () -> Int,
    p: Palette,
    brightnessMax: Int = 100,
) {
    val follow = s.brightnessFollowSystem
    val labelW = sliderLabelWidth(listOf("亮度偏移", "亮度"), p)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item {
            SetSwitch("跟随系统", s.brightnessFollowSystem,
                { newFollow ->
                    if (newFollow) {
                        commit(s.copy(brightnessFollowSystem = true))
                    } else {
                        // When turning off follow-system, avoid a brightness jump: reset the main brightness slider with "current system brightness + brightness offset".
                        val sysPct = readSystemBrightness()
                        commit(s.copy(brightnessFollowSystem = false,
                            brightness = (sysPct + s.brightnessOffset).coerceIn(-50, brightnessMax)))
                    }
                }, p, nav = nav, index = 0)
        }
        // The "brightness offset" and "brightness" sliders are always shown: offset on top, brightness below.
        item { UiSliderRow("亮度偏移", -20.0, 20.0, 1.0, s.brightnessOffset.toDouble(), { fmtSign(it) },
            { commit(s.copy(brightnessOffset = it.roundToInt())) }, { commit(s.copy(brightnessOffset = it.roundToInt())) }, p, enabled = follow, labelWidth = labelW,
            nav = nav, index = 1) }
        // Main "brightness" slider: 0..100 writes system brightness as a percentage; -50..0 uses a black overlay to dim below the system minimum.
        // When follow-system is on, the main slider is greyed out; the "brightness offset" then adds/subtracts a percentage around system brightness.
        item { UiSliderRow("亮度", -50.0, brightnessMax.toDouble(), 1.0, s.brightness.toDouble(), { "${it.roundToInt()}%" },
            { commit(s.copy(brightness = it.roundToInt())) }, { commit(s.copy(brightness = it.roundToInt())) }, p, enabled = !follow, labelWidth = labelW,
            nav = nav, index = 2) }
        item {
            // "Brightness gesture" heading aligns left with the other control labels (16dp start).
            Text("亮度调节手势", color = p.text, fontSize = 15.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
        }
        item {
            // The three switches are indented right by roughly two Chinese characters (about 32dp).
            Column(Modifier.padding(start = 32.dp)) {
                SetSwitch("屏幕左侧单指上下滑", s.brightnessGestureLeft, { commit(s.copy(brightnessGestureLeft = it)) }, p, enabled = !follow,
                    nav = nav, index = 3)
                SetSwitch("屏幕右侧单指上下滑", s.brightnessGestureRight, { commit(s.copy(brightnessGestureRight = it)) }, p, enabled = !follow,
                    nav = nav, index = 4)
                SetSwitch("任意位置双指上下滑", s.brightnessGestureTwo, { commit(s.copy(brightnessGestureTwo = it)) }, p, enabled = !follow,
                    nav = nav, index = 5)
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private fun fmtSign(v: Double) = if (v > 0) "+${v.roundToInt()}" else "${v.roundToInt()}"

// ================= Page-flip animation mode page =================

@Composable
private fun AnimModePage(
    s: ReaderSettings,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    commit: (ReaderSettings) -> Unit,
    p: Palette,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item { ThemeOpt("滑动", s.pageAnimationMode != "curl", keys[0].onEnter, p, nav = nav, index = 0) }
        item { ThemeOpt("卷曲", s.pageAnimationMode == "curl", keys[1].onEnter, p, nav = nav, index = 1) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Annotation colors page =================

/**
 * 笔记页（D-13 / D-21）：高亮 / 划线各一行标签，每行右侧是本类的五格色槽。
 *
 * 批注**没有自己的色系**：它只是高亮 / 划线记录上附带的一段文字，墨色跟随所属记录（D-26），
 * 所以这里只有两类。
 *
 * 交互：点标签切类别；点任一行里的色槽既切到那一类、又选中那格；下方红/绿/蓝与不透明度
 * 只改选中那一格（不透明度 100% = 完全盖住文字，0% = 全透明看不见），**拖动中即时生效**
 * （走 [previewLight]，只应用不落盘），松手再由 [commitLight] 落盘。
 * 最下方的「恢复默认颜色池」把两类十格一起恢复出厂值——**只恢复色槽**，不动下面那几项
 * 批注自己的样式（2026-11-04 用户定口：按钮名与作用范围要一致）；按完按钮会短暂变成
 * 「已恢复」并金色高亮，因为色槽本来就是出厂值时色块不会变，没有反馈就看不出按上了。
 * 默认色槽不归本页管（新建标注用哪一格由浮动菜单决定），所以这里没有金点、也没有默认按钮。
 * 所有改动走 [commitLight]（只改像素，不触发重排）。
 *
 * 「恢复默认颜色池」之后还有两项**旁注自己的样式**（2026-10-28 用户定口）：
 * - **批注字体**：与正文 / 标题 / 代码同一套字体下钻页，可挑任意系统或导入字体；
 *   多字重的族同样进字重页（选完族与字重一起生效，换族回到该族默认字重）；
 *   空槽 = 跟随界面默认（不是跟随原书——旁注不套书里的正文族）。
 * - **批注字号**：滑条编辑倍率，右侧显示换算后的实际字号；旁注始终跟正文字号等比。
 * 两项都只作用于覆盖层，与色槽同口径走无重排提交。
 */
@Composable
private fun AnnotationColorPage(
    s: ReaderSettings,
    kind: AnnotationKind,
    selectedSlot: Int,
    keys: List<ItemKey>,
    nav: PanelNav,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    commitLight: (ReaderSettings) -> Unit,
    previewLight: (ReaderSettings) -> Unit,
    onSelectKind: (AnnotationKind) -> Unit,
    onSelectSlot: (AnnotationKind, Int) -> Unit,
    /** 「恢复默认颜色池」的按下反馈：true 时按钮文字换成「已恢复」并金色高亮。 */
    resetFlash: Boolean,
    /** 「批注字体」行右侧的展示名（空槽 = 跟随界面；导入 / 系统族取本地化展示名）。 */
    noteFontLabel: String,
    p: Palette,
) {
    val slots = annotationColorSlots(s, kind)
    val slotCount = slots.size.coerceAtLeast(1)
    val selected = selectedSlot.coerceIn(0, slotCount - 1)
    val slotColor = annotationSlotColor(s, kind, selected)
    var redDraft by remember(kind, selected, slotColor) {
        mutableFloatStateOf(annotationRed(slotColor).toFloat())
    }
    var greenDraft by remember(kind, selected, slotColor) {
        mutableFloatStateOf(annotationGreen(slotColor).toFloat())
    }
    var blueDraft by remember(kind, selected, slotColor) {
        mutableFloatStateOf(annotationBlue(slotColor).toFloat())
    }
    var alphaDraft by remember(kind, selected, slotColor) {
        mutableFloatStateOf(annotationAlphaPercent(slotColor))
    }
    LaunchedEffect(slotColor) {
        redDraft = annotationRed(slotColor).toFloat()
        greenDraft = annotationGreen(slotColor).toFloat()
        blueDraft = annotationBlue(slotColor).toFloat()
        alphaDraft = annotationAlphaPercent(slotColor)
    }
    val labelW = sliderLabelWidth(listOf("红", "绿", "蓝", "不透明度", "批注字号"), p)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item {
            AnnotationKindTabRow(
                label = "高亮",
                active = kind == AnnotationKind.Highlight,
                slots = annotationColorSlots(s, AnnotationKind.Highlight),
                selectedSlot = if (kind == AnnotationKind.Highlight) selected else -1,
                p = p,
                nav = nav,
                index = 0,
                onActivate = { onSelectKind(AnnotationKind.Highlight) },
                onSelectSlot = { onSelectSlot(AnnotationKind.Highlight, it) },
            )
            HLine(p, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
        }
        item {
            AnnotationKindTabRow(
                label = "划线",
                active = kind == AnnotationKind.Underline,
                slots = annotationColorSlots(s, AnnotationKind.Underline),
                selectedSlot = if (kind == AnnotationKind.Underline) selected else -1,
                p = p,
                nav = nav,
                index = 1,
                onActivate = { onSelectKind(AnnotationKind.Underline) },
                onSelectSlot = { onSelectSlot(AnnotationKind.Underline, it) },
            )
            HLine(p, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
        }
        item {
            UiSliderRow(
                label = "红",
                min = 0.0,
                max = 255.0,
                step = 1.0,
                value = redDraft.toDouble(),
                fmt = { "${it.roundToInt()}" },
                onPreview = { v ->
                    redDraft = v.toFloat()
                    // 拖动中就走一遍预览：色槽与正文墨迹随手实时变，松手再落盘。
                    previewLight(withAnnotationColorSlot(
                        s, kind, selected,
                        annotationWithChannel(slotColor, 16, v.roundToInt()),
                    ))
                },
                onCommit = { v ->
                    redDraft = v.toFloat()
                    commitLight(
                        withAnnotationColorSlot(
                            s, kind, selected,
                            annotationWithChannel(slotColor, 16, v.roundToInt()),
                        ),
                    )
                },
                p = p,
                labelWidth = labelW,
                nav = nav,
                index = 2,
            )
        }
        item {
            UiSliderRow(
                label = "绿",
                min = 0.0,
                max = 255.0,
                step = 1.0,
                value = greenDraft.toDouble(),
                fmt = { "${it.roundToInt()}" },
                onPreview = { v ->
                    greenDraft = v.toFloat()
                    previewLight(withAnnotationColorSlot(
                        s, kind, selected,
                        annotationWithChannel(slotColor, 8, v.roundToInt()),
                    ))
                },
                onCommit = { v ->
                    greenDraft = v.toFloat()
                    commitLight(
                        withAnnotationColorSlot(
                            s, kind, selected,
                            annotationWithChannel(slotColor, 8, v.roundToInt()),
                        ),
                    )
                },
                p = p,
                labelWidth = labelW,
                nav = nav,
                index = 3,
            )
        }
        item {
            UiSliderRow(
                label = "蓝",
                min = 0.0,
                max = 255.0,
                step = 1.0,
                value = blueDraft.toDouble(),
                fmt = { "${it.roundToInt()}" },
                onPreview = { v ->
                    blueDraft = v.toFloat()
                    previewLight(withAnnotationColorSlot(
                        s, kind, selected,
                        annotationWithChannel(slotColor, 0, v.roundToInt()),
                    ))
                },
                onCommit = { v ->
                    blueDraft = v.toFloat()
                    commitLight(
                        withAnnotationColorSlot(
                            s, kind, selected,
                            annotationWithChannel(slotColor, 0, v.roundToInt()),
                        ),
                    )
                },
                p = p,
                labelWidth = labelW,
                nav = nav,
                index = 4,
            )
        }
        item {
            UiSliderRow(
                label = "不透明度",
                min = 0.0,
                max = 100.0,
                step = 1.0,
                value = alphaDraft.toDouble(),
                fmt = { "${it.roundToInt()}%" },
                onPreview = { v ->
                    alphaDraft = v.toFloat()
                    previewLight(withAnnotationColorSlot(
                        s, kind, selected,
                        annotationWithAlpha(slotColor, annotationAlphaFromPercent(v.toFloat())),
                    ))
                },
                onCommit = { v ->
                    alphaDraft = v.toFloat()
                    commitLight(
                        withAnnotationColorSlot(
                            s, kind, selected,
                            annotationWithAlpha(slotColor, annotationAlphaFromPercent(v.toFloat())),
                        ),
                    )
                },
                p = p,
                labelWidth = labelW,
                nav = nav,
                index = 5,
            )
        }
        item {
            PingButton(
                label = if (resetFlash) "已恢复" else "恢复默认颜色池",
                p = p,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                flash = resetFlash,
                onClick = keys[6].onEnter,
                nav = nav,
                index = 6,
            )
        }
        // 折叠所有批注：整页退成贴着划线的小图标。只改覆盖层像素，走无重排提交。
        // 它是「要不要看见批注」的总闸：打开后下面的字体、字号、描边、底色、同色字一律置灰。
        item {
            SetSwitch(
                label = "折叠所有批注",
                checked = s.noteCollapseAll,
                onChange = { commitLight(s.copy(noteCollapseAll = it)) },
                p = p,
                nav = nav,
                index = 7,
            )
        }
        item {
            SetRow(
                label = "批注字体",
                value = noteFontLabel,
                onTap = keys[8].onEnter,
                p = p,
                enabled = !s.noteCollapseAll,
                nav = nav,
                index = 8,
            )
        }
        item {
            // 滑条编辑的是**字号**（读数即所见，0.1 一档）；存盘仍按倍率，正文字号变了旁注等比跟着变。
            UiSliderRow(
                label = "批注字号",
                min = noteFontSizeMin(s),
                max = noteFontSizeMax(s),
                step = NOTE_FONT_SIZE_STEP,
                value = noteFontSizeValue(s),
                fmt = { noteFontSizeLabel(it) },
                onPreview = { v -> previewLight(s.copy(noteFontScale = noteFontScaleFor(s, v))) },
                onCommit = { v -> commitLight(s.copy(noteFontScale = noteFontScaleFor(s, v))) },
                p = p,
                enabled = !s.noteCollapseAll,
                labelWidth = labelW,
                nav = nav,
                index = 9,
            )
        }
        // 旁注框的三种画法：只改覆盖层像素，走无重排提交（与上面两项同口径）。
        // 折叠态下页面上只剩小图标，小图标不是文字、有自己的固定画法，这三项一并置灰。
        item {
            SetSwitch(
                label = "显示批注边框",
                checked = s.noteBorder,
                onChange = { commitLight(s.copy(noteBorder = it)) },
                p = p,
                enabled = !s.noteCollapseAll,
                nav = nav,
                index = 10,
            )
        }
        item {
            SetSwitch(
                label = "显示批注背景色",
                checked = s.noteBackground,
                onChange = { commitLight(s.copy(noteBackground = it)) },
                p = p,
                enabled = !s.noteCollapseAll,
                nav = nav,
                index = 11,
            )
        }
        item {
            SetSwitch(
                label = "使用标注同色字体",
                checked = s.noteTextSameColor,
                onChange = { commitLight(s.copy(noteTextSameColor = it)) },
                p = p,
                enabled = !s.noteCollapseAll,
                nav = nav,
                index = 12,
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * 笔记页的一行标签：左边类别名，右边本类的五格色槽。
 *
 * 点标签只切类别；点色槽既切到这一类、又选中那格（跨行点色槽不必先点标签）。
 * 选中描边只出现在当前类别那一行。
 */
@Composable
private fun AnnotationKindTabRow(
    label: String,
    active: Boolean,
    slots: List<Int>,
    selectedSlot: Int,
    p: Palette,
    nav: PanelNav,
    index: Int,
    onActivate: () -> Unit,
    onSelectSlot: (Int) -> Unit,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .clickable(interactionSource = clickSrc, indication = null, onClick = onActivate)
            .panelHover(nav, index)
            .kbRing()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) PanelGold else p.muted,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        slots.forEachIndexed { i, argb ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            AnnotationSlotSwatch(
                argb = argb,
                selected = active && i == selectedSlot,
                p = p,
                onClick = { onSelectSlot(i) },
            )
        }
    }
}

@Composable
private fun AnnotationSlotSwatch(
    argb: Int,
    selected: Boolean,
    p: Palette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color(argb), CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) p.text else p.cardBorder,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

// ================= Base widgets =================

@Composable
private fun HLine(p: Palette, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(1.dp).background(p.borderSoft))
}

/**
 * 键盘焦点环见 `PanelNav.kt`（[kbRing]，目录/设置共用）。
 */

@Composable
private fun SetRow(
    label: String,
    value: String? = null,
    onTap: () -> Unit,
    p: Palette,
    enabled: Boolean = true,
    nav: PanelNav,
    index: Int,
) {
    // 行禁默认 ripple/hover 指示（与目录同款）：框架自带悬停灰会和 active 高亮各行其是，
    // 唯一高亮只走 activeIdx。
    val clickSrc = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
                .alpha(if (enabled) 1f else 0.45f)
                .clickable(enabled = enabled, interactionSource = clickSrc, indication = null, onClick = onTap)
                .panelHover(nav, index)
                .kbRing()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, color = p.muted2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp).padding(end = 4.dp))
            }
            Text("›", color = p.chevron, fontSize = 14.sp)
        }
        HLine(p, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
    }
}

@Composable
private fun SetSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    p: Palette,
    enabled: Boolean = true,
    nav: PanelNav,
    index: Int,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, interactionSource = clickSrc, indication = null) { onChange(!checked) }
            .panelHover(nav, index)
            .kbRing()
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        UiSwitch(checked = checked && enabled, p = p)
    }
}

@Composable
private fun UiSwitch(checked: Boolean, p: Palette) {
    val thumbX by animateDpAsState(if (checked) 18.dp else 0.dp, tween(180), label = "thumb")
    Row(
        modifier = Modifier
            .width(42.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) PanelGold else p.switchTrack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flat switch thumb: a single solid circle, no nesting/shadow.
        Box(Modifier.padding(start = 2.dp).offset(x = thumbX).size(20.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun UiSliderRow(
    label: String, min: Double, max: Double, step: Double, value: Double, fmt: (Double) -> String,
    onPreview: (Double) -> Unit, onCommit: (Double) -> Unit, p: Palette, enabled: Boolean = true,
    labelWidth: Dp = 0.dp,
    nav: PanelNav,
    index: Int,
) {
    val clamped = value.coerceIn(min, max)
    val fr = if (max > min) ((clamped - min) / (max - min)).toFloat() else 0f
    // Snap any value to the nearest [step] multiple within [min, max], cleaning float noise,
    // so dragging and the −/+ buttons always land on the same step grid.
    val snap: (Double) -> Double = { v ->
        val n = ((v - min) / step).roundToInt()
        val raw = min + n * step
        ((raw * 1e6).roundToLong() / 1e6).coerceIn(min, max)
    }
    // Single row: fixed-width label + step buttons + expanding slider + fixed-width value, aligned to the same label column.
    // Row height matches the switch rows ([SetSwitch]: min 60.dp) so slider rows are not cramped.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .padding(start = 16.dp, end = 16.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .panelHover(nav, index),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.text, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = if (labelWidth.value > 0f) Modifier.width(labelWidth) else Modifier.widthIn(min = 48.dp))
        StepBtn("−", p, enabled) {
            val v = snap(clamped - step)
            onPreview(v)
            onCommit(v)
        }
        Box(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
            UiSlider(
                fraction = fr, enabled = enabled, p = p,
                onFraction = { f -> onPreview(snap(min + f * (max - min))) },
                onFinish = { f -> onCommit(snap(min + f * (max - min))) },
            )
        }
        StepBtn("+", p, enabled) {
            val v = snap(clamped + step)
            onPreview(v)
            onCommit(v)
        }
        Text(fmt(clamped), color = p.muted2, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.width(54.dp), textAlign = TextAlign.End)
    }
    HLine(p, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
}

@Composable
private fun StepBtn(char: String, p: Palette, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(6.dp)).background(p.sliderBtn)
            .clickable(enabled = enabled, onClick = onClick)
            .kbRing(),
        contentAlignment = Alignment.Center,
    ) { Text(char, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Measure the widest slider label among [labels] so all rows in the same panel share an equal label column width. */
@Composable
private fun sliderLabelWidth(labels: List<String>, p: Palette): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = labels.maxOf { l ->
        measurer.measure(AnnotatedString(l), TextStyle(color = p.text, fontSize = 15.sp)).size.width
    }
    return with(density) { maxPx.toDp() + 8.dp }
}

/** Self-drawn thin-rail slider (flat version recreating the old .slider-track look):
 *  the thin rail (4dp) is centered, gold fills up to the current fraction, a solid round thumb moves with the drag;
 *  calls back [onFraction] on each drag frame and [onFinish] on release. No Material big knob. */
@Composable
private fun UiSlider(
    fraction: Float,
    enabled: Boolean,
    p: Palette,
    onFraction: (Float) -> Unit,
    onFinish: (Float) -> Unit,
) {
    var last by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    // Sync internal drag position with externally-driven fraction (e.g. another slider
    // changed panelSettings and recomposed this slider). Without this, `last` would be
    // stale and the next drag would emit an outdated value.
    LaunchedEffect(fraction) { last = fraction.coerceIn(0f, 1f) }
    // Always read the latest callbacks — pointerInput keys only on `enabled`, so the
    // lambdas captured at first composition would otherwise stay stale and reset the
    // OTHER slider's value back to what it was when this slider was first composed.
    val currentOnFraction by rememberUpdatedState(onFraction)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // 滑钮圆的直径（px）：拖动换算要按「滑钮心能走的那一段」算，
    // 滑钮两端各留一个半径，才不会越出行边界。
    val thumbPx = with(LocalDensity.current) { SliderThumbSize.toPx() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled) {
                // `pointerInput(enabled)` 只是「enabled 变了就重开这个手势块」，**不会**关掉手势：
                // 置灰的行以前照样能拖（真机反馈：折叠所有批注打开后，灰着的批注字号还被拖动）。
                // 禁用时连手势都不挂，拖、点、滑一律不接。
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val travel = (size.width - thumbPx).coerceAtLeast(1f)
                        last = ((change.position.x - thumbPx / 2f) / travel).coerceIn(0f, 1f)
                        currentOnFraction(last)
                    },
                    onDragEnd = { currentOnFinish(last) },
                    onDragCancel = { currentOnFinish(last) },
                )
            },
    ) {
        val frac = fraction.coerceIn(0f, 1f)
        val railColor = if (enabled) p.sliderTrack else p.sliderTrack.copy(alpha = 0.4f)
        // 轨道两端各让出一个滑钮半径：滑钮跑到 0 / 100% 时整颗都还在行内，
        // 不会被右侧的「+」按钮压住一角（那一角画在按钮底色下面 = 看起来被挡）。
        val railW = (maxWidth - SliderThumbSize).coerceAtLeast(0.dp)
        // Thin rail
        Box(
            modifier = Modifier
                .offset(x = SliderThumbR)
                .width(railW).height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(railColor),
        )
        // Gold fill
        Box(
            modifier = Modifier
                .offset(x = SliderThumbR)
                .width(railW * frac).height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(if (enabled) PanelGold else PanelGold.copy(alpha = 0.4f)),
        )
        // Solid round thumb
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - SliderThumbSize) * frac)
                .size(SliderThumbSize)
                .background(if (enabled) PanelGold else PanelGold.copy(alpha = 0.4f), CircleShape),
        )
    }
}

@Composable
private fun ThemeOpt(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    p: Palette,
    nav: PanelNav,
    index: Int,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .clickable(onClick = onClick, interactionSource = clickSrc, indication = null)
            .panelHover(nav, index).kbRing().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = if (selected) PanelGold else PanelMut,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Dark mask opacity over the reading page behind the settings panel. */
private const val PanelMaskAlpha = 0.4f

/** Panel slide and mask dim/lighten share this duration so they stay synchronized. */
private const val PanelAnimMs = 280

/**
 * 笔记页「恢复默认颜色池」按下后保持高亮反馈的时长（ms）。
 *
 * 色槽本来就可能是出厂值，恢复后色块毫无变化：没有反馈读者分不清「按到了」和「没按到」。
 */
private const val ColorPoolResetFlashMs = 900

/** 字体列表整页翻连发节流窗口（桌面 PgDn/PgUp 按住连发，一次按压只认一次）。 */
private const val PageThrottleMs = 300

private val CancelRed = Color(0xFFD9534F)
