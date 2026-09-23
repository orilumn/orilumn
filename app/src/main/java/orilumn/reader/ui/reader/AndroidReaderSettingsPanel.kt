package orilumn.reader.ui.reader

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import android.provider.Settings
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontFace
import orilumn.reader.data.font.FontRepository
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reader settings panel: right-docked drawer, faithfully replicating the old `reader.html` settings drawer in controls and hierarchy.
 * Colors/structure/interaction align with the old version (gold accent, --ui-* palette). Intended for the self-built-engine reader page.
 */
@Composable
fun AndroidReaderSettingsPanel(
    visible: Boolean,
    settings: ReaderSettings,
    fontRepository: FontRepository,
    onDismiss: () -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommitTypography: (ReaderSettings) -> Unit,
    onCommitBookPrivate: (ReaderSettings) -> Unit,
    onCommitLight: (ReaderSettings) -> Unit,
    /** 字库增删（本地/WIFI 导入、左滑删除）后回调：宿主刷新 skia 共用池并重排，新字体立即可选即用。 */
    onFontsChanged: () -> Unit = {},
) {
    val p = paletteFor(settings.scheme)
    val drawerWidth = with(LocalConfiguration.current) {
        if (screenWidthDp < 600) (screenWidthDp * 0.85f).dp else 360.dp
    }
    val stack = remember { mutableStateListOf<Sub>(Sub.Home) }
    val current = stack.last()
    // 字体统一列表由 TextFont 子页自持（开页同步系统族 + 取全量表）；面板级只留刷新代际。
    var fontGen by remember { mutableStateOf(0) }
    fun refreshFonts() { fontGen++ }
    // Which typography slot (body/heading/code) the current font sub-panel selects for.
    var pickSlot by remember { mutableStateOf<String?>(null) }

    // Reset to home when closed, so the top-level menu shows next time it opens.
    LaunchedEffect(visible) { if (!visible) { stack.clear(); stack.add(Sub.Home); pickSlot = null } }
    BackHandler(enabled = visible) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
    }

    // Panel slide + mask dim/lighten, **synchronized**: the mask ramps at the SAME time and with the
    // SAME duration as the panel slide, so they move together instead of the mask trailing the panel.
    val density = LocalDensity.current
    val drawerPx = remember(drawerWidth, density) { with(density) { drawerWidth.toPx() } }
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
                    // Consume taps inside the panel so stray touches never fall through to the mask close layer.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = {},
                    ),
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
                    when (current) {
                        Sub.Home -> HomePage(settings, { stack.add(it) }, onCommitTypography, p)
                        Sub.Text -> TextPage(settings, p, onPreview, onCommitTypography,
                            { slot -> pickSlot = slot; stack.add(Sub.TextFont) })
                        Sub.TextFont -> TextFontSubPage(settings, pickSlot, fontGen, fontRepository, onCommitTypography, ::refreshFonts, onFontsChanged, p)
                        Sub.Spacing -> SpacingPage(settings, onPreview, onCommitTypography, p)
                        Sub.Theme -> ThemePage(settings, onCommitTypography, onCommitBookPrivate, p)
                        Sub.ReadingTheme -> ReadingThemePage(settings, onPreview, onCommitTypography, onCommitLight, { stack.add(Sub.ThemePresetMgr) }, p)
                        Sub.ThemePresetMgr -> ThemePresetManagerPage(settings, onPreview, onCommitTypography, p)
                        Sub.Brightness -> BrightnessPage(settings, onCommitLight, p)
                        Sub.AnimMode -> AnimModePage(settings, onCommitLight)
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

// ================= Home =================

@Composable
private fun HomePage(s: ReaderSettings, open: (Sub) -> Unit, commit: (ReaderSettings) -> Unit, p: AndroidPalette) {
    val ctx = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SetRow("排版主题", themeLabel(s.layoutTheme), { open(Sub.Theme) }, p) }
        item { SetRow("间距", onTap = { open(Sub.Spacing) }, p = p) }
        item { SetRow("文字", onTap = { open(Sub.Text) }, p = p) }
        item { SetRow("阅读主题", themeName(s, ctx), { open(Sub.ReadingTheme) }, p) }
        item { SetRow("亮度", if (s.brightnessFollowSystem) "跟随系统" else "自定义", { open(Sub.Brightness) }, p) }
        item { SetSwitch("翻页动画", s.pageAnim, { commit(s.copy(pageAnim = it)) }, p) }
        item { SetRow("翻页动画模式", modeLabel(s.pageAnimationMode), { open(Sub.AnimMode) }, p) }
        item { SetSwitch("封面等比例缩放", s.coverProportional, { commit(s.copy(coverProportional = it)) }, p) }
        item { SetSwitch("启动时继续阅读", s.autoContinue, { commit(s.copy(autoContinue = it)) }, p) }
        item { SetSwitch("显示页码", s.pageNum, { commit(s.copy(pageNum = it)) }, p) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** Reading-theme row label: current preset name if bg/fg match a built-in or saved custom theme, else "Custom". */
private fun themeName(s: ReaderSettings, ctx: Context): String =
    (BuiltinThemes + loadThemes(ctx)).firstOrNull { it.bg == s.bgOverride && it.fg == s.fgOverride }?.label ?: "自定义"

private fun themeLabel(v: String) = when (v) { "modern" -> "现代模式"; "traditional" -> "传统模式"; else -> "原书设置" }
private fun modeLabel(v: String) = if (v == "curl") "卷曲" else "平滑"

/** Body font-size px → internal fontScale step (keeps 0.1px precision). */
private fun pxToScale(px: Double): Double =
    ReaderSettings.ratioToFontScale(px / ReaderSettings.BASE_BODY_PX)

// ================= Text page (fonts + text size + letter spacing + character scale) =================

@Composable
private fun TextPage(
    s: ReaderSettings,
    p: AndroidPalette,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
    openFontPicker: (String) -> Unit,
) {
    val labelW = sliderLabelWidth(listOf("字号", "字间距"), p)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Three font-replacement tiers (body/heading/code); each row drills down into the font panel.
        FONT_FIELDS.forEach { (label, key) ->
            item { SetRow(label, fieldOf(s, key).ifEmpty { "跟随原书" }, { openFontPicker(key) }, p) }
        }
        // "Font size" slider: placed below the font rows.
        item { UiSliderRow("字号", 9.0, 36.0, 0.1, s.fontSize * ReaderSettings.fontScaleToRatio(s.fontScale),
            { String.format("%.1f", it) },
            { px -> preview(s.copy(fontScale = pxToScale(px))) },
            { px -> commit(s.copy(fontScale = pxToScale(px))) }, p, labelWidth = labelW) }
        // Letter spacing: UI value range -100..100 (step 1), internally mapped to -0.2em..0.2em.
        item { UiSliderRow("字间距", -100.0, 100.0, 1.0, s.letterSpacing, { it.roundToInt().toString() },
            { preview(s.copy(letterSpacing = it)) }, { commit(s.copy(letterSpacing = it)) }, p, labelWidth = labelW) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * F4c 字体下钻子页：共享 [FontLibraryPanel]（系统 + 导入同列 + 隐藏/删除 + 真字形预览），
 * 导入源（SAF/WiFi）由本页装配（`canImport` 常真，桌面传 false）。
 * 删除/隐藏正被槽位引用时回退"跟随原书"（沿旧 `FontManagerPanel` 口径）。
 */
@Composable
private fun TextFontSubPage(
    s: ReaderSettings,
    slotKey: String?,
    gen: Int,
    fontRepository: FontRepository,
    commit: (ReaderSettings) -> Unit,
    refresh: () -> Unit,
    onFontsChanged: () -> Unit,
    p: AndroidPalette,
) {
    val key = slotKey ?: "fontBody"
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<FontEntry>>(emptyList()) }
    // 开页 + 外部刷新代际：同步系统族落行后取统一列表。
    LaunchedEffect(gen) {
        runCatching { fontRepository.syncSystemFaces(orilumn.reader.engine.skia.systemFontFaces()) }
        entries = runCatching { fontRepository.entries() }.getOrDefault(emptyList())
    }
    fun reload(after: () -> Unit = {}) = scope.launch {
        entries = runCatching { fontRepository.entries() }.getOrDefault(emptyList())
        refresh()
        after()
    }
    val selected = fieldOf(s, key)
    fun fallBackIfSelected(family: String) {
        if (family == selected) commit(setField(s, key, ""))
    }

    var showWifi by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri -> runCatching { fontRepository.import(uri) } }
            reload { onFontsChanged() }
        }
    }

    val rows = remember(entries, selected) {
        orilumn.reader.ui.reader.buildFontRows(entries, selected, canImport = true, canWifiImport = true)
    }
    val sp = remember(p) {
        orilumn.reader.ui.reader.Palette(p.bg, p.text, p.border, p.borderSoft, p.rowActive, p.cardBg,
            p.cardBorder, p.muted, p.muted2, p.chevron, p.switchTrack, p.sliderTrack, p.sliderBtn,
            p.selBg, p.selText, p.selBorder)
    }
    val nav = remember { orilumn.reader.ui.reader.PanelNav() }
    val listState = remember { androidx.compose.foundation.lazy.LazyListState() }
    orilumn.reader.ui.reader.FontLibraryPanel(
        rows = rows,
        nav = nav,
        p = sp,
        onSelect = { family -> commit(setField(s, key, family)) },
        onHide = { ids ->
            scope.launch {
                val fam = entries.firstOrNull { e ->
                    val id = (e as? FontEntry.System)?.id ?: (e as? FontEntry.Imported)?.face?.id
                    id != null && id in ids
                }?.family
                ids.forEach { fontRepository.setHidden(it, true) }
                reload {
                    if (fam != null) fallBackIfSelected(fam)
                    onFontsChanged()
                }
            }
        },
        onUnhide = { ids ->
            scope.launch {
                ids.forEach { fontRepository.setHidden(it, false) }
                reload { onFontsChanged() }
            }
        },
        onDelete = { family ->
            scope.launch {
                fontRepository.deleteFamily(family)
                reload {
                    fallBackIfSelected(family)
                    onFontsChanged()
                }
            }
        },
        onImport = { importLauncher.launch(FONT_MIMES) },
        onImportWifi = { showWifi = true },
        onMouseMove = {},
        listState = listState,
        modifier = Modifier.fillMaxSize(),
    )
    if (showWifi) {
        WifiImportDialog(
            fontRepository = fontRepository,
            p = p,
            onDismiss = { showWifi = false },
            onImported = { reload { onFontsChanged() } },
        )
    }
}

@Composable
private fun SpacingPage(s: ReaderSettings, preview: (ReaderSettings) -> Unit, commit: (ReaderSettings) -> Unit, p: AndroidPalette) {
    val labelW = sliderLabelWidth(listOf("首行缩进", "行距", "段间距", "疏密", "上边距", "下边距", "左边距", "右边距"), p)
    // 排版量滑块永远可调 (用户最高优先级); 原书设置 只是把值重置为中性默认.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 首行缩进 (em): UI 层最高优先级 text-indent; 0 = 无首行缩进.
        item { UiSliderRow("首行缩进", 0.0, 10.0, 1.0, s.firstLineIndent,
            { "${it.roundToInt()}" },
            { preview(s.copy(firstLineIndent = it)) }, { commit(s.copy(firstLineIndent = it)) }, p, labelWidth = labelW) }
        item { UiSliderRow("行距", 0.5, 2.5, 0.1, s.lineSpacing,
            { String.format("%.1f", it) },
            { preview(s.copy(lineSpacing = it)) }, { commit(s.copy(lineSpacing = it)) }, p, labelWidth = labelW) }
        item { UiSliderRow("段间距", 0.0, 2.0, 0.1, s.paragraphSpacing,
            // A whole value (0) reads as "0", not "0.0".
            { v -> if (v == 0.0) "0" else String.format("%.1f", v) },
            { preview(s.copy(paragraphSpacing = it)) }, { commit(s.copy(paragraphSpacing = it)) }, p, labelWidth = labelW) }
        item { UiSliderRow("疏密", 0.0, 400.0, 1.0, s.paragraphGap,
            { "${it.roundToInt()}" },
            { preview(s.copy(paragraphGap = it)) }, { commit(s.copy(paragraphGap = it)) }, p, labelWidth = labelW) }
        item { UiSliderRow("上边距", 0.0, 200.0, 1.0, s.marginTop.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginTop = it.roundToInt())) }, { commit(s.copy(marginTop = it.roundToInt())) }, p, labelWidth = labelW) }
        item { UiSliderRow("下边距", 0.0, 200.0, 1.0, s.marginBottom.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginBottom = it.roundToInt())) }, { commit(s.copy(marginBottom = it.roundToInt())) }, p, labelWidth = labelW) }
        item { UiSliderRow("左边距", 0.0, 200.0, 1.0, s.marginLeft.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginLeft = it.roundToInt())) }, { commit(s.copy(marginLeft = it.roundToInt())) }, p, labelWidth = labelW) }
        item { UiSliderRow("右边距", 0.0, 200.0, 1.0, s.marginRight.toDouble(), { "${it.roundToInt()}" },
            { preview(s.copy(marginRight = it.roundToInt())) }, { commit(s.copy(marginRight = it.roundToInt())) }, p, labelWidth = labelW) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Typography theme page =================

@Composable
private fun ThemePage(s: ReaderSettings, commit: (ReaderSettings) -> Unit, commitBook: (ReaderSettings) -> Unit, p: AndroidPalette) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 三个按钮都是一键重置样式的预设: 把自身的排版值写入 UI 设置 (滑块立刻跟随、值即实际值),
        // 用户随后拖滑块是最高优先级. 现代/传统 写 缩进/段间距/字体族 (diff 传染全局);
        // 原书设置 写全套中性默认 (字体不覆盖/字号基准/缩进与段距默认/疏密 字距 行距归位),
        // 只写本书私有 overlay 不传染全局.
        item { ThemeOpt("原书设置", s.layoutTheme == "original") { commitBook(TypographicProfile.withLayoutTheme(s, "original")) } }
        item { ThemeOpt("现代模式", s.layoutTheme == "modern") { commit(TypographicProfile.withLayoutTheme(s, "modern")) } }
        item { ThemeOpt("传统模式", s.layoutTheme == "traditional") { commit(TypographicProfile.withLayoutTheme(s, "traditional")) } }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private val FONT_FIELDS = listOf("正文" to "fontBody", "标题" to "fontTitle", "代码" to "fontCode")
private fun fieldOf(s: ReaderSettings, key: String) = when (key) { "fontTitle" -> s.fontTitle; "fontCode" -> s.fontCode; else -> s.fontBody }
private fun setField(s: ReaderSettings, key: String, v: String) = when (key) {
    "fontTitle" -> s.copy(fontTitle = v); "fontCode" -> s.copy(fontCode = v); else -> s.copy(fontBody = v)
}

// ================= Reading-theme page (preset grid + night switch + blue-light + preset-manager entry) =================

@Composable
private fun ReadingThemePage(
    s: ReaderSettings,
    preview: (ReaderSettings) -> Unit,
    commit: (ReaderSettings) -> Unit,
    lightCommit: (ReaderSettings) -> Unit,
    open: () -> Unit,
    p: AndroidPalette,
) {
    val ctx = LocalContext.current
    var customs by remember { mutableStateOf(loadThemes(ctx)) }
    // A custom preset whose background equals a built-in theme is redundant: the built-in one
    // (kept first) already covers it. Dropping it also removes legacy duplicates that only differed
    // by the old foreground colour, which is why two "缃色" used to appear.
    val allThemes = remember(customs) {
        val builtinBgs = BuiltinThemes.map { it.bg }.toSet()
        val kept = customs.filterNot { it.bg in builtinBgs }
        (BuiltinThemes + kept).distinctBy { it.bg to it.fg }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(allThemes, key = { "${it.bg}|${it.fg}" }) { t ->
                    val night = s.scheme == "night"
                    // In night mode show each card as its auto-generated night counterpart so the
                    // swatches preview how the background/text will actually look while reading.
                    val dayBg = parseHex(t.bg.ifBlank { DEFAULT_BG })
                    val cellBg = if (night) TypographicProfile.nightBackgroundOf(dayBg) else dayBg
                    val dayFg = parseHex(t.fg.ifBlank { DEFAULT_FG })
                    val cellFg = if (night) TypographicProfile.nightForegroundOf(dayFg) else dayFg
                    PresetCell(
                        label = t.label, bg = Color(0xFF000000.toInt() or cellBg),
                        fg = Color(0xFF000000.toInt() or cellFg),
                        // Built-in themes share the same deep-ink foreground, and any custom preset whose
                        // background equals a built-in one is already filtered out of the grid, so a match on
                        // background alone is sufficient and also covers legacy presets that kept the old fg.
                        selected = (s.bgOverride == t.bg),
                        p = p,
                        onClick = { commit(s.copy(bgOverride = t.bg, fgOverride = t.fg)) },
                    )
                }
            }
        }
        // Night-mode switch (replaces the old day/night selector) below the preset grid. Global-only field,
        // but it changes text color so it must go through the typography (relayout) commit path.
        item { SetSwitch("夜间模式", s.scheme == "night",
            { commit(s.copy(scheme = if (it) "night" else "day")) }, p) }
        // Blue-light filter slider, moved here from the brightness panel, below the night-mode switch.
        item { UiSliderRow("护眼", 0.0, 100.0, 1.0, s.eyeProtectionLevel.toDouble(), { "${it.roundToInt()}%" },
            { lightCommit(s.copy(eyeProtectionLevel = it.roundToInt())) }, { lightCommit(s.copy(eyeProtectionLevel = it.roundToInt())) }, p) }
        // Entry to the preset-manager sub-panel (color sliders + save/delete).
        item { SetRow("预设管理", onTap = open, p = p) }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Preset-manager page (RGB/gray sliders + save/delete) =================

@Composable
private fun ThemePresetManagerPage(s: ReaderSettings, preview: (ReaderSettings) -> Unit, commit: (ReaderSettings) -> Unit, p: AndroidPalette) {
    val ctx = LocalContext.current
    var customs by remember { mutableStateOf(loadThemes(ctx)) }
    val bgHex = if (s.bgOverride.isNullOrBlank()) DEFAULT_BG else s.bgOverride
    val fgHex = if (s.fgOverride.isNullOrBlank()) DEFAULT_FG else s.fgOverride
    val bg = parseHex(bgHex)
    val fg = parseHex(fgHex)
    val labelW = sliderLabelWidth(listOf("红", "绿", "蓝", "灰度"), p)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { UiSliderRow("红", 0.0, 255.0, 1.0, RtoDouble(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(applyBg(s, bg, fg, 0, v)) }, { v -> commit(applyBg(s, bg, fg, 0, v)) }, p, labelWidth = labelW) }
        item { UiSliderRow("绿", 0.0, 255.0, 1.0, GtoDouble(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(applyBg(s, bg, fg, 1, v)) }, { v -> commit(applyBg(s, bg, fg, 1, v)) }, p, labelWidth = labelW) }
        item { UiSliderRow("蓝", 0.0, 255.0, 1.0, BtoDouble(bg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(applyBg(s, bg, fg, 2, v)) }, { v -> commit(applyBg(s, bg, fg, 2, v)) }, p, labelWidth = labelW) }
        item { UiSliderRow("灰度", 0.0, 255.0, 1.0, GrayOf(fg).toDouble(), { it.roundToInt().toString() },
            { v -> preview(applyFg(s, fg, v)) }, { v -> commit(applyFg(s, fg, v)) }, p, labelWidth = labelW) }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PingButton("保存", p, modifier = Modifier.weight(1f), onClick = { customs = doSave(ctx, customs, bgHex, fgHex) })
                PingButton("删除", p, modifier = Modifier.weight(1f), red = true, onClick = { customs = doDelete(ctx, customs, bgHex, fgHex, p) })
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private val BuiltinThemes = listOf(
    ThemePreset("原书设置", "", ""), ThemePreset("精白", "#ffffff", "#222222"), ThemePreset("象牙白", "#fffbf0", "#222222"),
    ThemePreset("霜色", "#eaecee", "#222222"), ThemePreset("缃色", "#f2ecde", "#222222"),
    ThemePreset("鸭卵青", "#e0eee8", "#222222"), ThemePreset("月白", "#d6ecf0", "#222222"),
    ThemePreset("粉白", "#fbeff2", "#222222"), ThemePreset("丁香", "#e8e0f0", "#222222"),
)
private const val DEFAULT_BG = "#f4f2ec"
private const val DEFAULT_FG = "#262626"

private data class ThemePreset(val label: String, val bg: String, val fg: String)

@Composable
private fun PresetCell(label: String, bg: Color, fg: Color, selected: Boolean, p: AndroidPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, p.selBorder, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PingButton(label: String, p: AndroidPalette, modifier: Modifier = Modifier, red: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (red) CAncelRed else p.text, fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(p.cardBg)
            .border(1.dp, p.cardBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
    )
}

private fun applyBg(s: ReaderSettings, bg: Int, fg: Int, ch: Int, v: Double): ReaderSettings {
    val c = setChannel(bg, ch, v.roundToInt())
    return s.copy(bgOverride = hexOf(c), fgOverride = if (s.fgOverride.isNullOrBlank()) hexOf(fg) else s.fgOverride)
}
private fun applyFg(s: ReaderSettings, fg: Int, v: Double): ReaderSettings {
    val g = v.roundToInt().coerceIn(0, 255)
    return s.copy(fgOverride = hexOf(0xFF000000.toInt() or (g shl 16) or (g shl 8) or g))
}
private fun setChannel(c: Int, ch: Int, v: Int): Int {
    val r = if (ch == 0) v.coerceIn(0, 255) else RtoInt(c)
    val g = if (ch == 1) v.coerceIn(0, 255) else GtoInt(c)
    val b = if (ch == 2) v.coerceIn(0, 255) else BtoInt(c)
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}
private fun hexOf(c: Int): String = "#" + ((c and 0x00FFFFFF)).toString(16).padStart(6, '0')
private fun parseHex(hex: String): Int = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0xFFF4F2EC.toInt())
private fun RtoInt(c: Int) = (c shr 16) and 0xFF
private fun GtoInt(c: Int) = (c shr 8) and 0xFF
private fun BtoInt(c: Int) = c and 0xFF
private fun RtoDouble(c: Int) = RtoInt(c).toDouble()
private fun GtoDouble(c: Int) = GtoInt(c).toDouble()
private fun BtoDouble(c: Int) = BtoInt(c).toDouble()
private fun GrayOf(c: Int) = (RtoInt(c) + GtoInt(c) + BtoInt(c)) / 3.0

private fun loadThemes(ctx: Context): List<ThemePreset> = runCatching {
    val prefs = ctx.getSharedPreferences("reader_settings_ui", Context.MODE_PRIVATE)
    val arr = JSONArray(prefs.getString(KEY_THEMES, "[]"))
    (0 until arr.length()).map { val o = arr.getJSONObject(it); ThemePreset(o.optString("label"), o.optString("bg"), o.optString("fg")) }
}.getOrDefault(emptyList())
private fun doSave(ctx: Context, customs: List<ThemePreset>, bg: String, fg: String): List<ThemePreset> {
    if (bg.isBlank()) return customs
    // Skip an exact duplicate (same bg+fg) to avoid label collisions in the preset grid.
    if (customs.any { it.bg == bg && it.fg == fg }) return customs
    val next = customs + ThemePreset(labelFor(bg, fg), bg, fg)
    persistThemes(ctx, next)
    return next
}
private fun doDelete(ctx: Context, customs: List<ThemePreset>, bg: String, fg: String, p: AndroidPalette): List<ThemePreset> {
    val next = customs.filterNot { it.bg == bg && it.fg == fg }
    persistThemes(ctx, next)
    return next
}
private fun labelFor(bg: String, fg: String): String {
    val name = buildString {
        when (bg.uppercase()) {
            "#FFFFFF" -> append("精白"); "#FFFBF0" -> append("象牙白"); "#F2ECDE" -> append("缃色")
            "#E0EEE8" -> append("鸭卵青"); "#EAECEE" -> append("霜色"); "#D6ECF0" -> append("月白")
            "#E8E0F0" -> append("丁香"); "#FBEFF2" -> append("粉白")
            else -> append("自定义")
        }
    }
    return name + (customsSuffix(bg, fg))
}
private fun customsSuffix(bg: String, fg: String) = ""
private fun persistThemes(ctx: Context, list: List<ThemePreset>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("label", it.label).put("bg", it.bg).put("fg", it.fg)) }
    ctx.getSharedPreferences("reader_settings_ui", Context.MODE_PRIVATE).edit().putString(KEY_THEMES, arr.toString()).apply()
}
private const val KEY_THEMES = "theme_presets"
private val CAncelRed = Color(0xFFD9534F)

// ================= Brightness page (follow/offset/brightness + eye-protection + blue-light + gesture) =================

@Composable
private fun BrightnessPage(s: ReaderSettings, commit: (ReaderSettings) -> Unit, p: AndroidPalette) {
    val follow = s.brightnessFollowSystem
    val ctx = LocalContext.current
    val labelW = sliderLabelWidth(listOf("亮度偏移", "亮度"), p)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SetSwitch("跟随系统", s.brightnessFollowSystem,
                { newFollow ->
                    if (newFollow) {
                        commit(s.copy(brightnessFollowSystem = true))
                    } else {
                        // When turning off follow-system, avoid a brightness jump: reset the main brightness slider with "current system brightness + brightness offset".
                        val sysPct = readSystemBrightnessPercentFor(ctx)
                        commit(s.copy(brightnessFollowSystem = false,
                            brightness = (sysPct + s.brightnessOffset).coerceIn(-50, 100)))
                    }
                }, p)
        }
        // The "brightness offset" and "brightness" sliders are always shown: offset on top, brightness below.
        item { UiSliderRow("亮度偏移", -20.0, 20.0, 1.0, s.brightnessOffset.toDouble(), { fmtSign(it) },
            { commit(s.copy(brightnessOffset = it.roundToInt())) }, { commit(s.copy(brightnessOffset = it.roundToInt())) }, p, enabled = follow, labelWidth = labelW) }
        // Main "brightness" slider: 0..100 writes system brightness as a percentage; -50..0 uses a black overlay to dim below the system minimum.
        // When follow-system is on, the main slider is greyed out; the "brightness offset" then adds/subtracts a percentage around system brightness.
        item { UiSliderRow("亮度", -50.0, 100.0, 1.0, s.brightness.toDouble(), { "${it.roundToInt()}%" },
            { commit(s.copy(brightness = it.roundToInt())) }, { commit(s.copy(brightness = it.roundToInt())) }, p, enabled = !follow, labelWidth = labelW) }
        item {
            // "Brightness gesture" heading aligns left with the other control labels (16dp start).
            Text("亮度调节手势", color = p.text, fontSize = 15.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
            // The three switches are indented right by roughly two Chinese characters (about 32dp).
            Column(Modifier.padding(start = 32.dp)) {
                SetSwitch("屏幕左侧单指上下滑", s.brightnessGestureLeft, { commit(s.copy(brightnessGestureLeft = it)) }, p, enabled = !follow)
                SetSwitch("屏幕右侧单指上下滑", s.brightnessGestureRight, { commit(s.copy(brightnessGestureRight = it)) }, p, enabled = !follow)
                SetSwitch("任意位置双指上下滑", s.brightnessGestureTwo, { commit(s.copy(brightnessGestureTwo = it)) }, p, enabled = !follow)
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}
private fun fmtSign(v: Double) = if (v > 0) "+${v.roundToInt()}" else "${v.roundToInt()}"

/** Read the current system brightness (0..100). Reads SCREEN_BRIGHTNESS (0..255), falls back to 50% on failure. */
private fun readSystemBrightnessPercentFor(ctx: Context): Int {
    val v = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    }.getOrDefault(128)
    return v.coerceIn(0, 255) * 100 / 255
}

// ================= Page-flip animation mode page =================

@Composable
private fun AnimModePage(s: ReaderSettings, commit: (ReaderSettings) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ThemeOpt("平滑", s.pageAnimationMode != "curl") { commit(s.copy(pageAnimationMode = "slide")) } }
        item { ThemeOpt("卷曲", s.pageAnimationMode == "curl") { commit(s.copy(pageAnimationMode = "curl")) } }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= Base widgets =================

@Composable
private fun HLine(p: AndroidPalette, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(1.dp).background(p.borderSoft))
}
@Composable
private fun SetRow(label: String, value: String? = null, onTap: () -> Unit, p: AndroidPalette, enabled: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clickable(enabled = enabled, onClick = onTap)
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
private fun SetSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, p: AndroidPalette, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        UiSwitch(checked = checked && enabled, p = p)
    }
}
@Composable
private fun UiSwitch(checked: Boolean, p: AndroidPalette) {
    val thumbX by animateDpAsState(if (checked) 18.dp else 0.dp, tween(180), label = "thumb")
    Row(
        modifier = Modifier
            .width(42.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Gold else p.switchTrack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flat switch thumb: a single solid circle, no nesting/shadow.
        Box(Modifier.padding(start = 2.dp).offset(x = thumbX).size(20.dp).background(Color.White, CircleShape))
    }
}
@Composable
private fun UiSliderRow(
    label: String, min: Double, max: Double, step: Double, value: Double, fmt: (Double) -> String,
    onPreview: (Double) -> Unit, onCommit: (Double) -> Unit, p: AndroidPalette, enabled: Boolean = true,
    labelWidth: Dp = 0.dp,
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
            .padding(start = 16.dp, end = 16.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.text, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = if (labelWidth.value > 0f) Modifier.width(labelWidth) else Modifier.widthIn(min = 48.dp))
        StepBtn("−", p, enabled) {
            val v = snap(clamped - step); onPreview(v); onCommit(v)
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            UiSlider(
                fraction = fr, enabled = enabled, p = p,
                onFraction = { f -> onPreview(snap(min + f * (max - min))) },
                onFinish = { f -> onCommit(snap(min + f * (max - min))) },
            )
        }
        StepBtn("+", p, enabled) {
            val v = snap(clamped + step); onPreview(v); onCommit(v)
        }
        Text(fmt(clamped), color = p.muted2, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.width(54.dp), textAlign = TextAlign.End)
    }
    HLine(p, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
}

@Composable
private fun StepBtn(char: String, p: AndroidPalette, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(6.dp)).background(p.sliderBtn)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(char, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Measure the widest slider label among [labels] so all rows in the same panel share an equal label column width. */
@Composable
private fun sliderLabelWidth(labels: List<String>, p: AndroidPalette): Dp {
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
    p: AndroidPalette,
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
                .background(if (enabled) Gold else Gold.copy(alpha = 0.4f)),
        )
        // Solid round thumb
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * frac - SliderThumbR)
                .size(SliderThumbSize)
                .background(if (enabled) Gold else Gold.copy(alpha = 0.4f), CircleShape),
        )
    }
}
@Composable
private fun ThemeOpt(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = if (selected) Gold else Mut,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ================= AndroidPalette (replicating --ui-*) =================

data class AndroidPalette(
    val bg: Color, val text: Color, val border: Color, val borderSoft: Color,
    val rowActive: Color, val cardBg: Color, val cardBorder: Color,
    val muted: Color, val muted2: Color, val chevron: Color,
    val switchTrack: Color, val sliderTrack: Color, val sliderBtn: Color,
    val selBg: Color, val selText: Color, val selBorder: Color,
)
private val Gold = Color(0xFFC8A15A)
private val Mut = Color(0xFF999999)
private val PaletteLight = paletteFor("day")

/** Self-drawn slider thumb diameter and radius (solid circle). */
private val SliderThumbSize = 18.dp
private val SliderThumbR = 9.dp

/** Dark mask opacity over the reading page behind the settings panel. */
private const val PanelMaskAlpha = 0.4f

/** Panel slide and mask dim/lighten share this duration so they stay synchronized. */
private const val PanelAnimMs = 280

/** The shared reader-panel palette (replicating the old `--ui-*` day/night schemes). Internal so the
 *  TOC drawer renders with the exact same colors as the settings drawer. */
internal fun paletteFor(scheme: String): AndroidPalette = when (scheme) {
    "night" -> AndroidPalette(Color(0xFF1C1C1E), Color(0xFFE8E8E8), Color(0xFF2C2C2E), Color(0xFF2A2A2C),
        Color(0xFF262628), Color(0xFF232326), Color(0xFF343438), Color(0xFF8A8A8A), Color(0xFF909090), Color(0xFF666666),
        Color(0xFF4A4A4E), Color(0xFF3A3A3E), Color(0xFF2C2C2E), Color(0xFF2A2018), Color(0xFFD9A94F), Color(0xFF8A5F1F))
    else -> AndroidPalette(Color(0xFFFAF8F4), Color(0xFF2B2B2B), Color(0xFFECE9E2), Color(0xFFF0EDE6),
        Color(0xFFEFECE4), Color(0xFFFFFFFF), Color(0xFFE3DFD5), Color(0xFF999999), Color(0xFF888888), Color(0xFFBBBBBB),
        Color(0xFFC9C4BA), Color(0xFFE7E3D9), Color(0xFFF0EDE6), Color(0xFFFAF3E6), Color(0xFF8A5F1F), Color(0xFFC8A15A))
}