package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.font.FontEntry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * F4a 统一字体管理面板（shared-ui）：系统 + 导入同列展示（桌面仅系统区）。
 *
 * 行模型（[buildFontRows] 纯函数，单测覆盖）：跟随原书 → 导入区（一行双按钮：
 * 本地导入 + WIFI 导入，沿旧平板样式，平板独占）→ 已导入区（点选 + 左滑删除，
 * 平板独占，[showImported]）→ 系统字体区（点选 + 左滑隐藏）→ 已隐藏区（点选取消隐藏）。
 * 同家族多字重合并为一行（系统/导入同形，按族名分组；槽位存的本来就是族名），
 * 隐藏/删除按家族原子操作。
 * 族名展示优先中文（name 表直读方案B + CoreText 方案A 中文名链结果；逻辑键仍用原族名）。
 * 副标题仅导入组（语种 · 字重表，无字重名即"无字重名"）；纯系统组无字重数据，不硬写副标题。
 * 排序在面板内按 [familyNameComparator]（两端同一拼音序）。
 * 预览各行用自己的字形（[FontPreviewText]，平台 last-mile 不同、字形同真）。
 * 左滑物理（弹簧 + 橡皮筋 + 单行展开）沿旧 `FontSwipeRow` 原样搬运，触摸/鼠标拖拽通用。
 * 删除/隐藏正被槽位引用时由调用方回退"跟随原书"（沿旧平板口径）。
 */

/** 扁平行（渲染与键盘键 1:1；Header/Hint 不可操作）。 */
sealed interface FontPanelRow {
    data class FollowOriginal(val selected: Boolean) : FontPanelRow
    /** 导入区（一行双按钮：本地导入 + 无线导入，沿旧 `FontManagerPanel` 样式；平板独占）。 */
    data object Import : FontPanelRow
    data class Header(val title: String) : FontPanelRow
    /** 同家族合并行（导入多字重 / 系统单行 / 隐藏组统一形状）。 */
    data class Entry(val family: String, val members: List<FontEntry>, val selected: Boolean) : FontPanelRow
    data class EmptyHint(val text: String) : FontPanelRow
}

/** 纯行模型：分区 + 按家族合并 + 排序 + 隐藏下沉（调用方只管喂全量表 + 当前槽位值）。 */
fun buildFontRows(
    entries: List<FontEntry>,
    selectedFamily: String,
    canImport: Boolean,
    /** 桌面=false：不展示导入入口 + 已导入区（仅系统字体，导入字体不同列）。 */
    showImported: Boolean = true,
    /** 无线导入入口（平板=true；与本地导入独立的能力位）。 */
    canWifiImport: Boolean = true,
): List<FontPanelRow> {
    val cmp = familyNameComparator()
    // 同展示名合族：同一字体的不同包装（Source Han Sans/Serif VF + 区域静态版 →
    // 「思源黑体/宋体」）在列表只占一行，避免同名两行让人以为字体重复。
    // **真不同字体**靠展示名自然拆开（Hei→Hei vs Heiti SC→黑体-简、Kai→Kai vs
    // Kaiti SC→楷体-简，展示名不同即不走合族）。规范族 = 面数最多者（并列取
    // 族名字典序小者：SC 区域版自然先于 VF），行首排规范族，槽位写入也用该族。
    fun mergeByName(groups: Map<String, List<FontEntry>>): List<Pair<String, List<FontEntry>>> =
        groups.values.flatMap { it }.groupBy { it.display }.map { (_, members) ->
            val families = members.groupBy { it.family }
            // 规范族 = 面数最多者；并列取族名字典序小者（SC 早于 VF；Heiti SC 早于 Hei）。
            val maxSize = families.values.maxOf { it.size }
            val canonical = families.entries.filter { it.value.size == maxSize }
                .minBy { it.key }.key
            val ordered = members.sortedWith(
                compareBy<FontEntry>(
                    { m -> m.family != canonical },
                    { m -> m.family },
                    { m ->
                        when (m) {
                            is FontEntry.System -> m.subfamily
                            is FontEntry.Imported -> m.face.subfamily
                        }
                    },
                ),
            )
            canonical to ordered
        }.sortedWith { a, b -> cmp.compare(a.first, b.first) }
    val imported = mergeByName(entries.filterIsInstance<FontEntry.Imported>()
        .filter { !it.hidden }.groupBy { it.family })
    val system = mergeByName(entries.filterIsInstance<FontEntry.System>()
        .filter { !it.hidden }.groupBy { it.family })
    val hiddenSource = if (showImported) entries else entries.filterIsInstance<FontEntry.System>()
    val hidden = mergeByName(hiddenSource.filter { it.hidden }.groupBy { it.family })
    return buildList {
        add(FontPanelRow.FollowOriginal(selectedFamily.isEmpty()))
        if (showImported && (canImport || canWifiImport)) add(FontPanelRow.Import)
        if (showImported) {
            add(FontPanelRow.Header("已导入"))
            if (imported.isEmpty()) {
                add(FontPanelRow.EmptyHint(if (canImport) "点上方导入添加字体" else "暂无导入字体"))
            } else {
                imported.forEach { (family, members) ->
                    add(FontPanelRow.Entry(family, members, members.any { it.family == selectedFamily }))
                }
            }
        }
        add(FontPanelRow.Header("系统字体"))
        if (system.isEmpty()) {
            add(FontPanelRow.EmptyHint("无可用系统字体"))
        } else {
            system.forEach { (family, members) ->
                add(FontPanelRow.Entry(family, members, members.any { it.family == selectedFamily }))
            }
        }
        if (hidden.isNotEmpty()) {
            add(FontPanelRow.Header("已隐藏"))
            hidden.forEach { (family, members) ->
                add(FontPanelRow.Entry(family, members, false))
            }
        }
    }
}

/**
 * F4a 真字形预览（各行用自己的字形渲染族名）。
 * - android：Compose Text + Typeface.create/createFromFile。
 * - jvm：Skia 段落直画 nativeCanvas（桌面即 skia Canvas）。
 */
@Composable
expect fun FontPreviewText(
    entry: FontEntry,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
)

@Composable
fun FontLibraryPanel(
    rows: List<FontPanelRow>,
    nav: PanelNav,
    p: Palette,
    onSelect: (String) -> Unit,
    onHide: (List<Long>) -> Unit,
    onUnhide: (List<Long>) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    /** 本地导入按钮是否显示（与无线导入独立的能力位；单开时只显示对应按钮）。 */
    showLocalButton: Boolean = true,
    /** 无线导入按钮是否显示（与本地导入独立的能力位；单开时只显示对应按钮）。 */
    showWifiButton: Boolean = true,
    /** 无线导入（平板 WiFi 上传；默认空即与本地同一入口语义，调用方按需覆盖）。 */
    onImportWifi: () -> Unit = onImport,
) {
    // 同时只展开一行的滑动操作：新展开即收起旧行。
    var openKey by remember { mutableStateOf<String?>(null) }
    // 滚动即跟随（F 余项 5）：滚轮/翻页/拖条把高亮（activeIdx）行滚出视口后，
    // 高亮重算到首个可见可焦点行，箭头导航不再从屏幕外的旧行起步、与所见错位。
    // 只在滚动静止后评估——键盘 ensureListVisible 自滚的落点行恒可见，不会误改；
    // 安卓无键盘，activeIdx 恒 null，本效应不动作。
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(listState, nav) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (inProgress) return@collect
            val vis = listState.layoutInfo.visibleItemsInfo
            val a = nav.activeIdx ?: return@collect
            if (vis.isEmpty() || vis.any { it.index == a }) return@collect
            val target = vis.firstOrNull {
                when (currentRows.getOrNull(it.index)) {
                    is FontPanelRow.FollowOriginal,
                    is FontPanelRow.Import,
                    is FontPanelRow.Entry -> true
                    else -> false
                }
            }?.index ?: return@collect
            nav.hoverAt(target)
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
        items(rows.size) { i ->
            when (val row = rows[i]) {
                is FontPanelRow.FollowOriginal ->
                    FontManageRow("跟随原书", null, row.selected, null, null, nav, i, p,
                        onTap = { openKey = null; onSelect("") }, openKey = openKey,
                        onOpenChange = { openKey = it })
                is FontPanelRow.Import ->
                    FontImportPair(
                        showLocal = showLocalButton, onLocal = onImport,
                        showWifi = showWifiButton, onWifi = onImportWifi,
                        nav = nav, index = i, p = p,
                    )
                is FontPanelRow.Header ->
                    FontSectionHeader(row.title, p)
                is FontPanelRow.EmptyHint ->
                    FontEmptyHint(row.text, p)
                is FontPanelRow.Entry -> {
                    val hidden = row.members.all { it.hidden }
                    val ids = row.members.mapNotNull {
                        when (it) {
                            is FontEntry.System -> it.id
                            is FontEntry.Imported -> it.face.id
                        }
                    }
                    val isSystem = row.members.all { it is FontEntry.System }
                    val subtitle = rowSubtitle(row.members)
                    when {
                        hidden -> FontManageRow(row.family, row.members.first(), false,
                            "取消隐藏", SwipeActionTone.Restore, nav, i, p, subtitle,
                            onTap = { openKey = null; onUnhide(ids) },
                            onAction = { onUnhide(ids) },
                            openKey = openKey, onOpenChange = { openKey = it })
                        isSystem -> FontManageRow(row.family, row.members.first(), row.selected,
                            "隐藏", SwipeActionTone.Hide, nav, i, p, subtitle,
                            onTap = { openKey = null; onSelect(row.family) },
                            onAction = { onHide(ids) },
                            openKey = openKey, onOpenChange = { openKey = it })
                        else -> FontManageRow(row.family, row.members.first(), row.selected,
                            "删除", SwipeActionTone.Delete, nav, i, p, subtitle,
                            onTap = { openKey = null; onSelect(row.family) },
                            onAction = { onDelete(row.family) },
                            openKey = openKey, onOpenChange = { openKey = it })
                    }
                }
            }
        }
    }
}

/** 副标题统一口径：语种 · 字重表（系统/导入同形；无字重名即"无字重名"）。 */
private fun rowSubtitle(members: List<FontEntry>): String? {
    val imported = members.filterIsInstance<FontEntry.Imported>()
    val lang = imported.firstOrNull()?.face?.lang?.trim()?.takeIf { it.isNotEmpty() }
    val subs = members.mapNotNull {
        when (it) {
            is FontEntry.Imported -> it.face.subfamily.trim().ifBlank { null }
            is FontEntry.System -> it.subfamily.trim().ifBlank { null }
        }
    }.distinct().joinToString(" / ")
    return listOfNotNull(lang, subs.ifBlank { null }).joinToString(" · ").ifEmpty { "无字重名" }
}

/**
 * 名字行与字重副标题的间隙：行盒已是真字形墨迹高度（栅格真值），字形越高只微量加气口
 * （0.15 比率），并钳到 [minPx, maxPx]（最小呼吸 + 不喧宾夺主——高字形行不再图 12dp
 * 大间距，名/重聚拢成一体）。
 * 纯函数（行模型同一文件可测）。
 */
fun subtitleGapPx(nameHeightPx: Int, minPx: Float, maxPx: Float): Float =
    (nameHeightPx * 0.15f).coerceIn(minPx, maxPx)

/** 左滑操作配色（删除红沿旧口径；隐藏/恢复另取 hues）。 */
private enum class SwipeActionTone(val color: Color) {
    Delete(Color(0xFFD9534F)),
    Hide(Color(0xFFE67E22)),
    Restore(Color(0xFF1A7F37)),
}

/**
 * 管理行：真字形族名 + 副标题 + 选中金点 + iOS 式左滑（沿旧 `FontSwipeRow` 物理）：
 * 文本与按钮一起滑，左滑至多露出按钮宽（橡皮筋超限），松手按方向惯性吸附开/合；
 * 同时只展开一行（[openKey] 仲裁，切换即弹簧收回）。
 * 点行 = 选择（隐藏组点行 = 取消隐藏）；鼠标拖拽同式。
 */
@Composable
private fun FontManageRow(
    family: String,
    entry: FontEntry?,
    selected: Boolean,
    actionLabel: String?,
    tone: SwipeActionTone?,
    nav: PanelNav,
    index: Int,
    p: Palette,
    subtitle: String? = null,
    onTap: () -> Unit,
    onAction: (() -> Unit)? = null,
    openKey: String?,
    onOpenChange: (String?) -> Unit,
) {
    val rowKey = "font:$family:${actionLabel ?: "pick"}"
    // 按钮宽 = 文本左滑行程（旧口径 88dp）；右滑橡皮筋 72dp，左超限橡皮筋 56dp。
    val yDp = 88.dp
    val yPx = with(LocalDensity.current) { yDp.toPx() }
    val rightPx = with(LocalDensity.current) { 72.dp.toPx() }
    val leftPx = with(LocalDensity.current) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val overBest = remember { Animatable(0f) }
    val dragDir = remember { mutableFloatStateOf(0f) }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    //  latch 到共享 openKey：本行不再是展开行即弹簧收回；展开只由手指驱动，
    // 切换行不在同一 offset 上叠动画（旧版几秒才收完的根因）。
    LaunchedEffect(openKey) {
        if (openKey != rowKey) {
            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.7f))
            overBest.animateTo(0f, spring())
        }
    }
    val clickSrc = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clipToBounds()
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .panelHover(nav, index),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(yPx, rightPx, leftPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragDir.value = 0f
                            dragAccum.value = offsetX.value
                            // 触碰即认领：其他展开行立刻开始收（不 lingering 第二个按钮）。
                            onOpenChange(rowKey)
                        },
                        onDragEnd = {
                            scope.launch {
                                val target = if (offsetX.value < 0f) {
                                    if (dragDir.value < 0f) -yPx else 0f
                                } else 0f
                                offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.5f))
                                overBest.animateTo(0f, spring())
                                // 落位展开即认领，其他行收起。
                                if (target < 0f) onOpenChange(rowKey)
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.snapTo(0f); overBest.snapTo(0f) } },
                    ) { change, dragAmount ->
                        change.consume()
                        if (dragAmount != 0f) dragDir.value = if (dragAmount > 0f) 1f else -1f
                        dragAccum.value += dragAmount
                        val d = dragAccum.value
                        if (d < -yPx) {
                            val over = -d - yPx
                            val best = leftPx * (1f - exp(-over / leftPx))
                            scope.launch { overBest.snapTo(best); offsetX.snapTo(-yPx - overBest.value) }
                        } else {
                            val shown = if (d <= 0f) d else rightPx * (1f - exp(-d / rightPx))
                            scope.launch { offsetX.snapTo(shown); overBest.snapTo(0f) }
                        }
                    }
                }
                // 点行体选择（无水平拖拽的点按才落到这里）。
                .clickable(onClick = onTap, interactionSource = clickSrc, indication = null)
                .kbRing()
                .padding(horizontal = 16.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (entry != null) {
                    // 名字行 = 真墨迹预览（Canvas 高度 = max(度量行高, 栅格真墨底)，墨不溢出）。
                    // 名/重间隙 = 行高*0.15 钳 [6,8]dp：常规行恒 6dp 兜底，字形越高的行
                    // 只微量加气口（高字形整行已大，间距不再按比例放大，聚拢成一体）。
                    var nameH by remember { mutableIntStateOf(0) }
                    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { nameH = it.height }) {
                        FontPreviewText(entry, if (selected) PanelGold else p.text, 15.sp, Modifier.fillMaxWidth())
                    }
                    if (subtitle != null) {
                        val density = LocalDensity.current
                        val gapPx = subtitleGapPx(nameH, with(density) { 6.dp.toPx() }, with(density) { 8.dp.toPx() })
                        Spacer(Modifier.height(with(density) { gapPx.toDp() }))
                        // 副标题（语种 · 字重表）可折行（长族名/多字重不再单行裁切），行高与字号匹配。
                        Text(subtitle, color = p.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                } else {
                    Text(family, color = if (selected) PanelGold else p.text, fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            if (selected) Text("●", color = PanelGold, fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (actionLabel != null && tone != null) {
            val overDp = with(LocalDensity.current) { overBest.value.toDp() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset { IntOffset((offsetX.value + yPx + overBest.value).roundToInt(), 0) },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(yDp + overDp)
                        .fillMaxHeight()
                        .background(tone.color)
                        .clickable { onAction?.invoke(); onOpenChange(null) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(actionLabel, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 28.dp, end = 0.dp))
                }
            }
        }
    }
}

/**
 * 导入区：沿旧 `FontManagerPanel` 原样——图标上、文字下双按钮（本地导入 + WIFI 导入），
 * 按下衬底高亮；下方 16dp 内缩分隔线。单开能力位时只显示对应按钮；
 * 键盘 active 高亮走行级 rowActive（与其他行同源），悬停认领同 [panelHover]。
 */
@Composable
private fun FontImportPair(
    showLocal: Boolean,
    onLocal: () -> Unit,
    showWifi: Boolean,
    onWifi: () -> Unit,
    nav: PanelNav,
    index: Int,
    p: Palette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (nav.activeIdx == index) p.rowActive else Color.Transparent)
            .panelHover(nav, index),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showLocal) FontImportAction(Icons.Default.Add, "本地导入", p, onClick = onLocal)
            if (showWifi) FontImportAction(WifiIcon, "WIFI 导入", p, onClick = onWifi)
        }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(p.borderSoft))
    }
}

/**
 * 导入按钮：沿旧 `FontManagerPanel.FontImportAction` 原样——图标上文字下圆角块，按下高亮。
 */
/** 无线导入图标：旧 `ic_wifi` 矢量同型（icons-core 无 Wifi，按原 path 手描，不引 extended）。 */
private val WifiIcon: ImageVector = ImageVector.Builder(
    name = "Wifi", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9z" +
            "M9.05,16.05l2.95,2.95l2.95,-2.95c-1.63,-1.63 -4.27,-1.63 -5.9,0z" +
            "M5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

@Composable
private fun FontImportAction(icon: ImageVector, label: String, p: Palette, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Color(0x12000000) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .kbRing()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = p.text, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, fontSize = 12.sp, color = p.text, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FontSectionHeader(title: String, p: Palette) {
    Text(title, color = p.muted, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun FontEmptyHint(text: String, p: Palette) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = p.muted, fontSize = 14.sp)
    }
}
