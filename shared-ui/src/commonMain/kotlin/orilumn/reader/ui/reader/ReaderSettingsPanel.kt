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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
 * 语义条约：排版类改动走 [onCommitTypography]（触发重排），亮度/护眼走 [onCommitLight]（只刷新遮罩）。
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
    onDismiss: () -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommitTypography: (ReaderSettings) -> Unit,
    onCommitLight: (ReaderSettings) -> Unit,
    customThemes: List<ThemePreset> = emptyList(),
    onSaveTheme: (ThemePreset) -> Unit = {},
    onDeleteTheme: (ThemePreset) -> Unit = {},
    readSystemBrightness: () -> Int = { 50 },
    /** 原书设置 专用提交: 只写本书私有设置, 不传染全局 (Android 有 per-book overlay 时传;
     *  桌面单全局设置, 缺省回落到 [onCommitTypography]). */
    onCommitBookPrivate: ((ReaderSettings) -> Unit)? = null,
    /** F4a 字体管理回调（隐藏/取消隐藏/删除/导入，宿主落库 + 刷新字库重排）。 */
    onFontHide: (List<Long>) -> Unit = {},
    onFontUnhide: (List<Long>) -> Unit = {},
    onFontDelete: (String) -> Unit = {},
    /** 本地导入按钮（平板独占；桌面隐藏；与无线导入独立，单开只显对应按钮）。 */
    canFontImport: Boolean = false,
    onFontImport: () -> Unit = {},
    /** 无线导入按钮（平板独占；桌面隐藏；与本地导入独立，默认开、随导入区）。 */
    canFontWifiImport: Boolean = true,
    onFontImportWifi: () -> Unit = {},
    /** 已导入区是否显示（平板=true；桌面=false，仅系统字体）。 */
    showImportedSection: Boolean = true,
) {
    val p = paletteFor(settings.scheme)
    val stack = remember { mutableStateListOf<Sub>(Sub.Home) }
    val current = stack.last()
    // Which typography slot (body/heading/code) the current font sub-panel selects for.
    var pickSlot by remember { mutableStateOf<String?>(null) }
    // 自定义主题预设（内存态）：Seed 自宿主列表；保存/删除经 onSaveTheme/onDeleteTheme 上报宿主。
    var customs by remember(customThemes) { mutableStateOf(customThemes) }

    // Reset to home when closed, so the top-level menu shows next time it opens.
    LaunchedEffect(visible) {
        if (!visible) {
            stack.clear()
            stack.add(Sub.Home)
            pickSlot = null
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
        LaunchedEffect(mounted, current) { if (mounted) drawerFr.requestFocus() }
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
        val commitBook = onCommitBookPrivate ?: onCommitTypography
        // F4a 字体行模型（键与渲染共用同一份，1:1 对齐 activeIdx）。
        val slotKey: String = pickSlot ?: "fontBody"
        val fontRows: List<FontPanelRow> = remember(current, fontEntries, s, pickSlot, canFontImport, canFontWifiImport, showImportedSection) {
            if (current == Sub.TextFont) buildFontRows(fontEntries, fieldOf(s, slotKey), canFontImport, showImportedSection, canFontWifiImport)
            else emptyList()
        }
        // 入口行（正文/标题/代码）展示名：族 → 本地化展示名（系统/导入同表）；
        // 缺席（如已删族仍被槽位引用）回退族名本身 + CSS 通用族标签（不裸奔英文原族名）。
        val fontDisplayByFamily = remember(fontEntries) { fontEntries.associate { it.family to it.display } }
        // flat idx → LazyColumn 位置（阅读主题网格每 3 格并一行；亮度页手势开关同行）。
        val listPosOf: (Int) -> Int = when (current) {
            Sub.ReadingTheme -> { idx ->
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
                ItemKey(onEnter = { stack.add(Sub.ReadingTheme) }),
                ItemKey(onEnter = { stack.add(Sub.Brightness) }),
                ItemKey(onEnter = { onCommitTypography(s.copy(pageAnim = !s.pageAnim)) }),
                ItemKey(onEnter = { stack.add(Sub.AnimMode) }),
                ItemKey(onEnter = { onCommitTypography(s.copy(coverProportional = !s.coverProportional)) }),
                ItemKey(onEnter = { onCommitTypography(s.copy(autoContinue = !s.autoContinue)) }),
                ItemKey(onEnter = { onCommitTypography(s.copy(pageNum = !s.pageNum)) }),
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
                    sliderKey(s.letterSpacing, -100.0, 100.0, 1.0,
                        apply = { v -> onPreview(s.copy(letterSpacing = v)); onCommitTypography(s.copy(letterSpacing = v)) },
                        fmt = { it.roundToInt().toString() }),
                )
            }
            Sub.TextFont -> fontRows.map { row ->
                when (row) {
                    is FontPanelRow.FollowOriginal ->
                        ItemKey(onEnter = { onCommitTypography(setField(s, slotKey, "")) })
                    is FontPanelRow.Import ->
                        // 双按钮同行：回车走主动作（本地导入优先；单开无线时走无线；桌面不渲染此行）。
                        ItemKey(onEnter = { if (canFontImport) onFontImport() else onFontImportWifi() })
                    is FontPanelRow.Header, is FontPanelRow.EmptyHint ->
                        ItemKey(enabled = false)
                    is FontPanelRow.Entry ->
                        ItemKey(onEnter = { onCommitTypography(setField(s, slotKey, row.family)) })
                }
            }
            Sub.Spacing -> listOf(
                sliderKey(s.firstLineIndent, 0.0, 10.0, 1.0,
                    apply = { v -> onPreview(s.copy(firstLineIndent = v)); onCommitTypography(s.copy(firstLineIndent = v)) },
                    fmt = { "${it.roundToInt()}" }),
                sliderKey(s.lineSpacing, 0.5, 2.5, 0.1,
                    apply = { v -> onPreview(s.copy(lineSpacing = v)); onCommitTypography(s.copy(lineSpacing = v)) },
                    fmt = { String.format("%.1f", it) }),
                sliderKey(s.paragraphSpacing, 0.0, 2.0, 0.1,
                    apply = { v -> onPreview(s.copy(paragraphSpacing = v)); onCommitTypography(s.copy(paragraphSpacing = v)) },
                    fmt = { v -> if (v == 0.0) "0" else String.format("%.1f", v) }),
                sliderKey(s.paragraphGap, 0.0, 400.0, 1.0,
                    apply = { v -> onPreview(s.copy(paragraphGap = v)); onCommitTypography(s.copy(paragraphGap = v)) },
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
            Sub.Theme -> listOf(
                ItemKey(onEnter = { commitBook(TypographicProfile.withLayoutTheme(s, "original")) }),
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
                                brightness = (sysPct + s.brightnessOffset).coerceIn(-50, 100)))
                        }
                    }),
                    sliderKey(s.brightnessOffset.toDouble(), -20.0, 20.0, 1.0,
                        apply = { v -> onCommitLight(s.copy(brightnessOffset = v.roundToInt())) },
                        fmt = { v -> if (v > 0) "+${v.roundToInt()}" else "${v.roundToInt()}" },
                        enabled = follow),
                    sliderKey(s.brightness.toDouble(), -50.0, 100.0, 1.0,
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
        fun isEnabled(i: Int) = keys.getOrNull(i)?.enabled == true
        fun visible(i: Int, dir: Int) = scope.ensureListVisible(listState, listPosOf(i), dir)
        fun moveActive(dir: Int) = nav.move(dir, 1, keys.size, ::isEnabled) { visible(it, dir) }
        fun onEnterActive() {
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onEnter?.invoke() }
        }
        fun onLeftActive() {
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onLeft?.invoke() }
        }
        fun onRightActive() {
            nav.activeIdx?.let { keys.getOrNull(it)?.takeIf { k -> k.enabled }?.onRight?.invoke() }
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(maskAlpha)
                        .background(Color.Black.copy(alpha = PanelMaskAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = onDismiss,
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
                            onUp = ::onUpActive,
                            onDown = ::onDownActive,
                            onEnter = ::onEnterActive,
                            onEscape = ::onEscape,
                            onLeft = ::onLeftActive,
                            onRight = ::onRightActive,
                        )
                        // 点按消费（不抢焦点）：杂散点按不穿透到遮罩关闭层。
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
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
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { stack.removeAt(stack.lastIndex) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("‹", color = p.text, fontSize = 20.sp)
                            }
                        }
                        Text(current.title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center))
                        Text("✕", color = p.text, fontSize = 18.sp, modifier = Modifier
                            .align(Alignment.CenterEnd).padding(10.dp)
                            .clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss))
                    }
                    HLine(p, modifier = Modifier.fillMaxWidth())
                    Box(modifier = Modifier.weight(1f)) {
                        val onMove: () -> Unit = { nav.releaseHold() }
                        when (current) {
                            Sub.Home -> HomePage(s, keys, nav, onMove, listState, onCommitTypography, customs, p)
                            Sub.Text -> TextPage(s, keys, nav, onMove, listState, p, onPreview, onCommitTypography, fontDisplayByFamily)
                            Sub.TextFont -> FontLibraryPanel(
                                rows = fontRows,
                                nav = nav,
                                p = p,
                                onSelect = { family -> onCommitTypography(setField(s, slotKey, family)) },
                                onHide = onFontHide,
                                onUnhide = onFontUnhide,
                                onDelete = onFontDelete,
                                onImport = onFontImport,
                                onImportWifi = onFontImportWifi,
                                showLocalButton = canFontImport,
                                showWifiButton = canFontWifiImport,
                                onMouseMove = onMove,
                                listState = listState,
                            )
                            Sub.Spacing -> SpacingPage(s, keys, nav, onMove, listState, p, onPreview, onCommitTypography)
                            Sub.Theme -> ThemePage(s, keys, nav, onMove, listState, onCommitTypography, commitBook, p)
                            Sub.ReadingTheme -> ReadingThemePage(s, allThemes, keys, nav, onMove, listState, onPreview, onCommitTypography, onCommitLight, p)
                            Sub.ThemePresetMgr -> ThemePresetManagerPage(s, keys, nav, onMove, listState, onPreview, onCommitTypography, customs,
                                onCustomsChanged = { customs = it },
                                onSaveTheme = onSaveTheme,
                                onDeleteTheme = onDeleteTheme,
                                p)
                            Sub.Brightness -> BrightnessPage(s, keys, nav, onMove, listState, onCommitLight, readSystemBrightness, p)
                            Sub.AnimMode -> AnimModePage(s, keys, nav, onMove, listState, onCommitLight, p)
                        }
                    }
                }
            }
        }
    }
}

private enum class Sub(val title: String) {
    Home("设置"), Text("文字"), TextFont("字体管理"), Spacing("间距"),
    Theme("排版主题"), ReadingTheme("阅读主题"), ThemePresetMgr("预设管理"),
    Brightness("亮度"), AnimMode("翻页动画模式"),
}

/** 键盘项：click 与键盘共用同一回调；网格格的左右由调用方配成步进，滑块的左右配成调值。 */
private data class ItemKey(
    val onEnter: () -> Unit = {},
    val onLeft: () -> Unit = {},
    val onRight: () -> Unit = {},
    val enabled: Boolean = true,
)

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
    customs: List<ThemePreset>,
    p: Palette,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        item { SetRow("排版主题", ReaderThemeMath.layoutThemeLabel(s.layoutTheme), keys[0].onEnter, p, nav = nav, index = 0) }
        item { SetRow("间距", onTap = keys[1].onEnter, p = p, nav = nav, index = 1) }
        item { SetRow("文字", onTap = keys[2].onEnter, p = p, nav = nav, index = 2) }
        item { SetRow("阅读主题", ReaderThemeMath.themeName(s, customs), keys[3].onEnter, p, nav = nav, index = 3) }
        item { SetRow("亮度", if (s.brightnessFollowSystem) "跟随系统" else "自定义", keys[4].onEnter, p, nav = nav, index = 4) }
        item { SetSwitch("翻页动画", s.pageAnim, { commit(s.copy(pageAnim = it)) }, p, nav = nav, index = 5) }
        item { SetRow("翻页动画模式", ReaderThemeMath.pageAnimationModeLabel(s.pageAnimationMode), keys[6].onEnter, p, nav = nav, index = 6) }
        item { SetSwitch("封面等比例缩放", s.coverProportional, { commit(s.copy(coverProportional = it)) }, p, nav = nav, index = 7) }
        item { SetSwitch("启动时继续阅读", s.autoContinue, { commit(s.copy(autoContinue = it)) }, p, nav = nav, index = 8) }
        item { SetSwitch("显示页码", s.pageNum, { commit(s.copy(pageNum = it)) }, p, nav = nav, index = 9) }
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
    val labelW = sliderLabelWidth(listOf("字号", "字间距"), p)
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
        item { UiSliderRow("字间距", -100.0, 100.0, 1.0, s.letterSpacing, { it.roundToInt().toString() },
            { preview(s.copy(letterSpacing = it)) }, { commit(s.copy(letterSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 4) }
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
        // 首行缩进 (em): UI 层最高优先级 text-indent; 0 = 无首行缩进.
        item { UiSliderRow("首行缩进", 0.0, 10.0, 1.0, s.firstLineIndent,
            { "${it.roundToInt()}" },
            { preview(s.copy(firstLineIndent = it)) }, { commit(s.copy(firstLineIndent = it)) }, p, labelWidth = labelW,
            nav = nav, index = 0) }
        item { UiSliderRow("行距", 0.5, 2.5, 0.1, s.lineSpacing,
            { String.format("%.1f", it) },
            { preview(s.copy(lineSpacing = it)) }, { commit(s.copy(lineSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 1) }
        item { UiSliderRow("段间距", 0.0, 2.0, 0.1, s.paragraphSpacing,
            // A whole value (0) reads as "0", not "0.0".
            { v -> if (v == 0.0) "0" else String.format("%.1f", v) },
            { preview(s.copy(paragraphSpacing = it)) }, { commit(s.copy(paragraphSpacing = it)) }, p, labelWidth = labelW,
            nav = nav, index = 2) }
        item { UiSliderRow("疏密", 0.0, 400.0, 1.0, s.paragraphGap,
            { "${it.roundToInt()}" },
            { preview(s.copy(paragraphGap = it)) }, { commit(s.copy(paragraphGap = it)) }, p, labelWidth = labelW,
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
    commitBook: (ReaderSettings) -> Unit,
    p: Palette,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        // 三个按钮都是一键重置样式的预设: 把自身的排版值写入 UI 设置 (滑块立刻跟随、值即实际值),
        // 用户随后拖滑块是最高优先级. 现代/传统 写 缩进/段间距/字体族 (diff 传染全局);
        // 原书设置 写全套中性默认 (字体不覆盖/字号基准/缩进与段距默认/疏密 字距 行距归位),
        // 只写本书私有 overlay 不传染全局.
        item { ThemeOpt("原书设置", s.layoutTheme == "original", keys[0].onEnter, p, nav = nav, index = 0) }
        item { ThemeOpt("现代模式", s.layoutTheme == "modern", keys[1].onEnter, p, nav = nav, index = 1) }
        item { ThemeOpt("传统模式", s.layoutTheme == "traditional", keys[2].onEnter, p, nav = nav, index = 2) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private val FONT_FIELDS = listOf("正文" to "fontBody", "标题" to "fontTitle", "代码" to "fontCode")
private fun fieldOf(s: ReaderSettings, key: String) = when (key) {
    "fontTitle" -> s.fontTitle
    "fontCode" -> s.fontCode
    else -> s.fontBody
}
private fun setField(s: ReaderSettings, key: String, v: String) = when (key) {
    "fontTitle" -> s.copy(fontTitle = v)
    "fontCode" -> s.copy(fontCode = v)
    else -> s.copy(fontBody = v)
}

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
    onClick: () -> Unit,
    nav: PanelNav,
    index: Int,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Text(
        label, color = if (red) CancelRed else p.text, fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (nav.activeIdx == index) p.rowActive else p.cardBg)
            .border(1.dp, p.cardBorder, RoundedCornerShape(6.dp))
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
                            brightness = (sysPct + s.brightnessOffset).coerceIn(-50, 100)))
                    }
                }, p, nav = nav, index = 0)
        }
        // The "brightness offset" and "brightness" sliders are always shown: offset on top, brightness below.
        item { UiSliderRow("亮度偏移", -20.0, 20.0, 1.0, s.brightnessOffset.toDouble(), { fmtSign(it) },
            { commit(s.copy(brightnessOffset = it.roundToInt())) }, { commit(s.copy(brightnessOffset = it.roundToInt())) }, p, enabled = follow, labelWidth = labelW,
            nav = nav, index = 1) }
        // Main "brightness" slider: 0..100 writes system brightness as a percentage; -50..0 uses a black overlay to dim below the system minimum.
        // When follow-system is on, the main slider is greyed out; the "brightness offset" then adds/subtracts a percentage around system brightness.
        item { UiSliderRow("亮度", -50.0, 100.0, 1.0, s.brightness.toDouble(), { "${it.roundToInt()}%" },
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
        item { ThemeOpt("平滑", s.pageAnimationMode != "curl", keys[0].onEnter, p, nav = nav, index = 0) }
        item { ThemeOpt("卷曲", s.pageAnimationMode == "curl", keys[1].onEnter, p, nav = nav, index = 1) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
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
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        currentOnFraction(last)
                    },
                    onDragEnd = { currentOnFinish(last) },
                    onDragCancel = { currentOnFinish(last) },
                )
            },
    ) {
        val frac = fraction.coerceIn(0f, 1f)
        val railColor = if (enabled) p.sliderTrack else p.sliderTrack.copy(alpha = 0.4f)
        // Thin rail
        Box(
            modifier = Modifier
                .fillMaxWidth().height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(railColor),
        )
        // Gold fill
        Box(
            modifier = Modifier
                .fillMaxWidth(frac).height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(if (enabled) PanelGold else PanelGold.copy(alpha = 0.4f)),
        )
        // Solid round thumb
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * frac - SliderThumbR)
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

private val CancelRed = Color(0xFFD9534F)
