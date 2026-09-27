package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.focus.focusProperties
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

/** 扁平行（渲染与键盘键 1:1，全行可操作）。 */
sealed interface FontPanelRow {
    /** 首行开关（无导入区时独立成行；有导入区时并进导入行第三钮，不重复）。 */
    data object Toggle : FontPanelRow
    /** 导入区（一行三按钮：本地导入 + 无线导入 + 显示/隐藏，沿旧 `FontManagerPanel` 样式；平板独占）。 */
    data object Import : FontPanelRow
    data class FollowOriginal(val selected: Boolean) : FontPanelRow
    /** 同展示名合并行（成员同来源：导入行左滑删除，系统行左滑隐藏/取消隐藏）。 */
    data class Entry(val family: String, val members: List<FontEntry>, val selected: Boolean) : FontPanelRow
}

/** 纯行模型：开关 + 导入区 + 跟随原书 + 单列字体（调用方只管喂全量表 + 当前槽位值）。 */
fun buildFontRows(
    entries: List<FontEntry>,
    selectedFamily: String,
    canImport: Boolean,
    /** 桌面=false：不展示导入入口 + 已导入区（仅系统字体，导入字体不同列）。 */
    showImported: Boolean = true,
    /** 无线导入入口（平板=true；与本地导入独立的能力位）。 */
    canWifiImport: Boolean = true,
    /** true 即把隐藏字体与其他行同列（首行开关态）；false 即不列出（无已隐藏区）。 */
    includeHidden: Boolean = false,
): List<FontPanelRow> {
    val cmp = chineseFirstComparator(familyNameComparator())
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
        }.sortedWith { a, b -> cmp.compare(a.second.first().display, b.second.first().display) }
    val imported = mergeByName(entries.filterIsInstance<FontEntry.Imported>()
        .filter { showImported && (includeHidden || !it.hidden) }.groupBy { it.family })
    val system = mergeByName(entries.filterIsInstance<FontEntry.System>()
        .filter { includeHidden || !it.hidden }.groupBy { it.family })
    return buildList {
        // 导入区（平板三按钮，含显示/隐藏开关；桌面无此行，开关独立成行兜底）。
        val hasImport = showImported && (canImport || canWifiImport)
        if (!hasImport) add(FontPanelRow.Toggle)
        if (hasImport) add(FontPanelRow.Import)
        add(FontPanelRow.FollowOriginal(selectedFamily.isEmpty()))
        // 单列：导入行与系统行按展示名归并排序（两组各自有序；跨来源同名各占一行，
        // 来源标记天然单一，删除/隐藏不串味）。
        val ia = imported.iterator()
        val sa = system.iterator()
        var i = if (ia.hasNext()) ia.next() else null
        var s = if (sa.hasNext()) sa.next() else null
        while (i != null || s != null) {
            // mergeByName 内部已按规范族排序，这里按展示名（组内首成员 display）归并。
            val takeI = i != null && (s == null ||
                cmp.compare(i.second.first().display, s.second.first().display) <= 0)
            val (family, members) = if (takeI) i!!.also { i = if (ia.hasNext()) ia.next() else null }
            else s!!.also { s = if (sa.hasNext()) sa.next() else null }
            add(FontPanelRow.Entry(family, members, members.any { it.family == selectedFamily }))
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
    /** 多字重行点按：下钻字重页（调用方压栈）；单字重行走 onSelect。 */
    onPickWeight: (String) -> Unit = {},
    /** 首行开关态：true 即隐藏字体已列出（按钮显"隐藏"），false 即未列出（按钮显"显示"）。 */
    showHidden: Boolean = false,
    /** 首行开关点按（键鼠共用同一回调）。 */
    onToggleHidden: () -> Unit = {},
    /** 导入行内聚焦按钮键（"local"/"wifi"/"toggle"，Tab 环驱动；null 即无聚焦）。 */
    importFocusedKey: String? = null,
    /** 独立开关行聚焦态（无导入区时，Tab 环驱动）。 */
    toggleFocused: Boolean = false,
) {
    // 同时只展开一行的滑动操作：新展开即收起旧行。
    var openKey by remember { mutableStateOf<String?>(null) }
    // 滚动即跟随（F 余项 5）：滚轮/翻页/拖条把高亮（activeIdx）行滚出视口后，
    // 高亮重算到首个可见可焦点行，箭头导航不再从屏幕外的旧行起步、与所见错位。
    // 只在滚动静止后评估——键盘 ensureListVisible 自滚的落点行恒可见，不会误改；
    // 安卓无键盘，activeIdx 恒 null，本效应不动作。
    val currentRows by rememberUpdatedState(rows)
    // 懒索引 = 行号 - fixedCount（按钮区在列表外固定）：跟随/可见换算加回。
    val fixedCount = rows.takeWhile { it is FontPanelRow.Toggle || it is FontPanelRow.Import }.size
    LaunchedEffect(listState, nav) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (inProgress) return@collect
            val vis = listState.layoutInfo.visibleItemsInfo
            val a = nav.activeIdx ?: return@collect
            if (vis.isEmpty() || vis.any { it.index + fixedCount == a }) return@collect
            val target = vis.firstOrNull {
                when (currentRows.getOrNull(it.index + fixedCount)) {
                    is FontPanelRow.FollowOriginal,
                    is FontPanelRow.Entry -> true
                    else -> false
                }
            }?.index?.plus(fixedCount) ?: return@collect
            nav.hoverAt(target)
        }
    }
    // 按钮区（导入行/独立开关）固定在列表上方，不进滚动区：翻页/滚动只走字体行。
    // LazyColumn 只装字体行；懒索引 = 行号 - fixedCount，两处换算（本文件跟随效应 +
    // 调用方 listPosOf）各自加减，键盘 activeIdx 仍用行号。
    Column(modifier = modifier.fillMaxSize()) {
        rows.take(fixedCount).forEachIndexed { i, row ->
            when (row) {
                // 首行开关（书架同口径：标当前态——列出中标"显示"，未列出标"隐藏"）。
                is FontPanelRow.Toggle ->
                    FontToggleRow(
                        label = if (showHidden) "显示" else "隐藏",
                        nav = nav, index = i, p = p, onTap = { openKey = null; onToggleHidden() },
                        focused = toggleFocused,
                    )
                is FontPanelRow.Import ->
                    FontImportPair(
                        showLocal = showLocalButton, onLocal = onImport,
                        showWifi = showWifiButton, onWifi = onImportWifi,
                        hiddenShown = showHidden, onToggleHidden = onToggleHidden,
                        focusedKey = importFocusedKey,
                        nav = nav, index = i, p = p,
                    )
                else -> Unit
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
            items(rows.size - fixedCount) { i ->
                val rowIndex = i + fixedCount
                when (val row = rows[rowIndex]) {
                        is FontPanelRow.Toggle -> Unit
                        is FontPanelRow.Import -> Unit
                        is FontPanelRow.FollowOriginal ->
                            // 跟随原书（用户层）：当作一个字体行——与 Entry 同规格（名字行 + 副标题 +
                            // 选中金点），稳坐导入区之下；不是字体，无左滑。
                            FontManageRow("跟随原书", null, row.selected, null, null, nav, rowIndex, p,
                                subtitle = "使用原书字体", swipeEnabled = false, sourceLabel = null,
                                onTap = { openKey = null; onSelect("") }, openKey = openKey,
                                onOpenChange = { openKey = it })
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
                    // 预览成员：有 Regular 字重用它（同族多字重时名行字形稳定），没有用第一个。
                    val preview = row.members.previewMember()
                    // 来源标记：隐藏行标"隐藏"，其余按来源（同展示名跨来源各占一行，行内来源天然单一）。
                    val sourceLabel = when {
                        hidden -> "隐藏"
                        isSystem -> "系统"
                        else -> "导入"
                    }
                    when {
                        hidden -> FontManageRow(row.family, preview, false,
                            "显示", SwipeActionTone.Restore, nav, rowIndex, p, subtitle,
                            sourceLabel = sourceLabel,
                            onTap = { openKey = null; onUnhide(ids) },
                            onAction = { onUnhide(ids) },
                            openKey = openKey, onOpenChange = { openKey = it })
                        isSystem -> FontManageRow(row.family, preview, row.selected,
                            "隐藏", SwipeActionTone.Hide, nav, rowIndex, p, subtitle,
                            sourceLabel = sourceLabel,
                            onTap = {
                                openKey = null
                                if (row.members.weightChoices().size > 1) onPickWeight(row.family)
                                else onSelect(row.family)
                            },
                            onAction = { onHide(ids) },
                            openKey = openKey, onOpenChange = { openKey = it })
                        else -> FontManageRow(row.family, preview, row.selected,
                            "删除", SwipeActionTone.Delete, nav, rowIndex, p, subtitle,
                            sourceLabel = sourceLabel,
                            onTap = {
                                openKey = null
                                // 多字重下钻字重页，单一下钻直接选中。
                                if (row.members.weightChoices().size > 1) onPickWeight(row.family)
                                else onSelect(row.family)
                            },
                            onAction = { onDelete(row.family) },
                            openKey = openKey, onOpenChange = { openKey = it })
                    }
                    }
                }
            }
        }
    }
}

/**
 * 中文名在前、英文名在后（组内仍走拼音序）：读音归一做不到（穷举不尽），只按"是否含中日韩
 * 表意/假名"分两档——中文展示名天然聚前，英文专有名词（Helvetica 这类本就没有中文名的）
 * 沉后。纯函数（行模型同一文件可测）。
 */
internal fun chineseFirstComparator(cmp: Comparator<String>): Comparator<String> = Comparator { a, b ->
    val ac = a.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xFF65..0xFF9D }
    val bc = b.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xFF65..0xFF9D }
    if (ac != bc) return@Comparator if (ac) -1 else 1
    cmp.compare(a, b)
}

/**
 * 字重下钻页（一族的字重档列表，用户层）：同数值合档只留最常用一名（见 [weightChoices]），
 * 斜体面不进列表（渲染按数值就近选面、斜体自然匹配，另见
 * `SkParagraphFactory.anchoredWeight`；斜体只在字体列表副标题出现）；点即提交
 * （族写入槽位 + 锚点落库）并停留本页，‹/Esc 返回字体列表。行复用无左滑 [FontManageRow]
 * （名字行 + 选中金点）；键盘键由调用方按同一顺序配（首档即键 0）。
 * 选中金色仅由调用方按"该族是否为本槽位当前字体"决定（非当前族传 null，即只留高亮）。
 */
@Composable
internal fun WeightPickerPage(
    options: List<Pair<Int, String>>,
    selectedWeight: Int?,
    nav: PanelNav,
    p: Palette,
    onPickWeight: (Int) -> Unit,
    onMouseMove: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clearKbHoldOnMove(onMouseMove)) {
            items(options.size) { i ->
                val (w, name) = options[i]
                FontManageRow(
                    family = name, entry = null, selected = selectedWeight == w,
                    actionLabel = null, tone = null, nav = nav, index = i, p = p,
                    subtitle = w.toString(),
                    swipeEnabled = false, sourceLabel = null,
                    onTap = { onPickWeight(w) }, openKey = null, onOpenChange = {},
                )
            }
        }
    }
}

/** 副标题统一口径（用户层）：语种 · 字重表（系统/导入同形；无字重名即"无字重名"）。 */
internal fun rowSubtitle(members: List<FontEntry>): String? {
    val imported = members.filterIsInstance<FontEntry.Imported>()
    // generic 只是"中西文兼备"的兜底分类，无信息量，不展示（cjk/latin 保留）。
    val lang = imported.firstOrNull()?.face?.lang?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("generic", ignoreCase = true) }
    // 字重名展示原值：入库即按简体>繁体>日文>英文归一（导入 `parse` + 系统 `systemFontFaces`
    // 同一 `FontParser.localizedName` 口径），展示层不翻译；旧英文残留由 `syncSystemFonts` 对账清。
    // 同一（数值，是否斜体）只留最常用一名（见 [canonicalSubfamilyName]）；斜体组保留，
    // upright 组在前、同数值内斜体排后，仍按数值升序。
    val rawSubs = members.mapNotNull {
        when (it) {
            is FontEntry.Imported -> it.face.subfamily.trim().ifBlank { null }
            is FontEntry.System -> it.subfamily.trim().ifBlank { null }
        }
    }.distinct()
    val subs = rawSubs.groupBy {
        orilumn.reader.data.font.SubfamilyMetric.weight(it) to
            orilumn.reader.data.font.SubfamilyMetric.italic(it)
    }.entries.sortedWith(compareBy({ it.key.first }, { if (it.key.second) 1 else 0 }))
        .map { (_, names) -> canonicalSubfamilyName(names) }
        .joinToString(" / ")
    return listOfNotNull(lang, subs.ifBlank { null }).joinToString(" · ").ifEmpty { "无字重名" }
}

/**
 * 预览成员：同行多字重时优先 Regular（名行字形稳定，不随排序飘到 Bold/Italic），
 * 无 Regular 用第一个。纯函数（行模型同一文件可测）。
 */
internal fun List<FontEntry>.previewMember(): FontEntry =
    firstOrNull {
        val sub = when (it) {
            is FontEntry.Imported -> it.face.subfamily
            is FontEntry.System -> it.subfamily
        }.trim()
        sub.equals("Regular", ignoreCase = true) || sub == "常规"
    } ?: first()

/**
 * 同数值内最常用的字重名（用户层纯函数）：候选名按 [CanonicalSubfamilyTokens] 首命中
 * 词排名（越前越常用），再按短名优先、字典序兜底。同档多名（如 Regular / Soft /
 * Compact Regular / Condensed Regular）只留一名，宽体/厂商后缀变体自然沉底。
 */
internal fun canonicalSubfamilyName(names: List<String>): String {
    require(names.isNotEmpty())
    return names.distinct().minWithOrNull(
        compareBy(
            { n -> CanonicalSubfamilyTokens.indexOfFirst { t -> n.contains(t, ignoreCase = true) }
                .takeIf { it >= 0 } ?: Int.MAX_VALUE },
            { n -> n.length },
            { n -> n },
        ),
    ) ?: names.first()
}

/** 常用度排序的匹配词（先长词后短词：semibold/extrabold/ultralight 含短词子串，先命中才分得开）。 */
private val CanonicalSubfamilyTokens = listOf(
    "regular", "常规", "normal", "book", "roman",
    "medium", "中等", "中黑",
    "semibold", "demibold", "半粗", "中粗",
    "extrabold", "ultrabold", "特粗", "超粗",
    "bold", "粗",
    "black", "黑",
    "extralight", "ultralight", "特细", "超细", "极细",
    "light", "细",
    "thin", "纤细",
    "heavy", "默认",
)

/**
 * 一族的字重档（用户层纯函数，数值 → 该档最常用一名，见 [canonicalSubfamilyName]）：
 * 成员字重经 SubfamilyMetric 归一化后按数值合档排序；空白字重名展示为"默认"。
 * 斜体面不进列表——锚点本就是数值粒度（见 SkParagraphFactory.anchoredWeight），
 * 分开展示会误导（选了斜体面也不会让正文变斜）；斜体只在字体列表副标题出现。
 * 点按分流（多档下钻字重页）与字重页共用同一口径。纯函数（行模型同一文件可测）。
 */
internal fun List<FontEntry>.weightChoices(): List<Pair<Int, String>> =
    filter { e ->
        val raw = when (e) {
            is FontEntry.Imported -> e.face.subfamily
            is FontEntry.System -> e.subfamily
        }.trim()
        !orilumn.reader.data.font.SubfamilyMetric.italic(raw)
    }.map { e ->
        val raw = when (e) {
            is FontEntry.Imported -> e.face.subfamily
            is FontEntry.System -> e.subfamily
        }.trim()
        orilumn.reader.data.font.SubfamilyMetric.weight(raw) to raw.ifBlank { "默认" }
    }.groupBy({ it.first }, { it.second })
        .map { (w, names) -> w to canonicalSubfamilyName(names) }
        .sortedBy { it.first }

/**
 * 名字行与字重副标题的间隙：行盒已是真字形墨迹高度（栅格真值），字形越高只微量加气口
 * （0.08 比率），并钳到 [minPx, maxPx]（最小呼吸 + 不喧宾夺主——高字形行不再图大间距，
 * 名/重聚拢成一体）。
 * 纯函数（行模型同一文件可测）。
 */
fun subtitleGapPx(nameHeightPx: Int, minPx: Float, maxPx: Float): Float =
    (nameHeightPx * 0.08f).coerceIn(minPx, maxPx)

/** 左滑操作配色（删除红沿旧口径；隐藏/恢复另取 hues）。 */
private enum class SwipeActionTone(val color: Color) {
    Delete(Color(0xFFD9534F)),
    Hide(Color(0xFFE67E22)),
    Restore(Color(0xFF9C27B0)),
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
    /** 跟随原书不是字体：false 即无左滑（点选保留）。 */
    swipeEnabled: Boolean = true,
    /** 行首来源竖标（"系统"/"导入"，逐字竖排，占满名+副标题高度）；null 即无标。 */
    sourceLabel: String? = null,
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
                .then(if (swipeEnabled) Modifier
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
                    } else Modifier)
                // 点行体选择（无水平拖拽的点按才落到这里）。
                // 行不抢焦点（面板按键走 activeIdx 状态机，与焦点遍历无关）：
                // 点过的行若留着焦点，回车会被行当"再点一次"吃掉，抽屉导航就再也收不到回车。
                .focusProperties { canFocus = false }
                .clickable(onClick = onTap, interactionSource = clickSrc, indication = null)
                .kbRing()
                .padding(horizontal = 16.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sourceLabel != null) {
                // 来源竖标：逐字竖排，撑满名 + 副标题整列高度。
                Column(
                    modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    sourceLabel.forEach { ch ->
                        Text(ch.toString(), color = p.muted, fontSize = 9.sp, lineHeight = 11.sp)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (entry != null) {
                    // 名字行 = 真墨迹预览（Canvas 高度 = max(度量行高, 栅格真墨底)，墨不溢出）。
                    // 名/重间隙 = 行高*0.08 钳 [2,5]dp：Canvas 已含真墨下缘，间隙纯粹是气口，
                    // 常规行 2dp 贴紧，高字形行最多 5dp，名/重聚拢成一体。
                    var nameH by remember { mutableIntStateOf(0) }
                    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { nameH = it.height }) {
                        FontPreviewText(entry, if (selected) PanelGold else p.text, 15.sp, Modifier.fillMaxWidth())
                    }
                    if (subtitle != null) {
                        val density = LocalDensity.current
                        val gapPx = subtitleGapPx(nameH, with(density) { 2.dp.toPx() }, with(density) { 5.dp.toPx() })
                        Spacer(Modifier.height(with(density) { gapPx.toDp() }))
                        // 副标题（语种 · 字重表）可折行（长族名/多字重不再单行裁切），行高与字号匹配。
                        Text(subtitle, color = p.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                } else {
                    Text(family, color = if (selected) PanelGold else p.text, fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    // 无字形条目（如跟随原书）同样走副标题行，与 Entry 行同规格；
                    // 间隙取 2dp（与字形行 subtitleGapPx 下限一致）。
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, color = p.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            if (selected) Text("●", color = PanelGold, fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (swipeEnabled && actionLabel != null && tone != null) {
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
 * 导入区：沿旧 `FontManagerPanel` 原样——图标上、文字下三按钮（本地导入 + WIFI 导入 +
 * 显示/隐藏开关），按下衬底高亮；下方 16dp 内缩分隔线。单开能力位时只显示对应按钮；
 * 键盘 active 高亮走行级 rowActive（与其他行同源），悬停认领同 [panelHover]。
 */
@Composable
private fun FontImportPair(
    showLocal: Boolean,
    onLocal: () -> Unit,
    showWifi: Boolean,
    onWifi: () -> Unit,
    /** 显示/隐藏开关态（书架同口径：标当前态）：true=隐藏字体已列出（按钮显"显示"），false=未列出（按钮显"隐藏"）。 */
    hiddenShown: Boolean,
    onToggleHidden: () -> Unit,
    /** Tab 环聚焦按钮键（"local"/"wifi"/"toggle"；null 即无聚焦）。 */
    focusedKey: String? = null,
    nav: PanelNav,
    index: Int,
    p: Palette,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showLocal) FontImportAction(Icons.Default.Add, "本地导入", p, onClick = onLocal, focused = focusedKey == "local")
            if (showWifi) FontImportAction(WifiIcon, "WIFI 导入", p, onClick = onWifi, focused = focusedKey == "wifi")
            FontImportAction(EyeIcon, if (hiddenShown) "显示" else "隐藏", p, onClick = onToggleHidden, focused = focusedKey == "toggle")
        }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(p.borderSoft))
    }
}

/**
 * 导入按钮：沿旧 `FontManagerPanel.FontImportAction` 原样——图标上文字下圆角块，按下高亮。
 */
/** 眼睛图标：icons-core 无 Visibility（58 个无眼），按 Material 眼睛 path 手描（与 WifiIcon 同例）。 */
private val EyeIcon: ImageVector = ImageVector.Builder(
    name = "Eye", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
            "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
            "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

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
private fun FontImportAction(icon: ImageVector, label: String, p: Palette, onClick: () -> Unit, focused: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            // 高亮只到按钮：悬停/Tab 聚焦亮本钮，不整行背底。
            .background(if (focused || hovered) p.rowActive else if (pressed) Color(0x12000000) else Color.Transparent)
            .hoverable(interaction)
            // 按钮不抢焦点（同管理行：回车留给抽屉导航，不被按钮当"再点一次"吃掉）。
            .focusProperties { canFocus = false }
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

/**
 * 首行开关：整行点按，键鼠共用同一回调；键盘 active 高亮走行级 rowActive（与其他行同源）。
 */
@Composable
private fun FontToggleRow(
    label: String,
    nav: PanelNav,
    index: Int,
    p: Palette,
    onTap: () -> Unit,
    /** Tab 环聚焦态（与行级高亮同底）。 */
    focused: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused || nav.activeIdx == index) p.rowActive else Color.Transparent)
            .panelHover(nav, index)
            // 开关不抢焦点（同管理行：焦点永远留在抽屉容器，回车才到得了导航）。
            .focusProperties { canFocus = false }
            .clickable(onClick = onTap, interactionSource = remember { MutableInteractionSource() }, indication = null)
            .kbRing()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = p.text, fontSize = 14.sp)
    }
}
