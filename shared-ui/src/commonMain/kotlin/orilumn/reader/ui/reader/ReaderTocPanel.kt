package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.epub.TocItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * S29 阅读目录抽屉：**左停靠**面板，镜像设置抽屉的 slide + mask 交互（共用同一调色板），
 * 列出书本层级（缩进/当前项金字/逐节点折叠展开），选中经 [onSelect] 跳转。
 * R3 收敛后双端共用此实现（原 Android `AndroidReaderTocPanel` 已删）；返回键不在
 * commonMain 承载（各壳 S31 负责：桌面无返回键，平板由 `ReaderActivity` 接）。
 */
@Composable
fun ReaderTocPanel(
    visible: Boolean,
    toc: List<TocItem>,
    currentChapter: Int,
    scheme: String,
    currentFragments: Set<String> = emptySet(),
    onSelect: (TocItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = paletteFor(scheme)
    var collapsed by remember { mutableStateOf(setOf<Int>()) }

    val rows = remember(toc) { flattenToc(toc) }
    fun isVisible(rowIndex: Int): Boolean {
        var a = rows[rowIndex].parentIndex
        while (a >= 0) {
            if (a in collapsed) return false
            a = rows[a].parentIndex
        }
        return true
    }
    // 目录项1（用户层）：打开定位到当前阅读位置对应的目录项（章内优先最末命中的子标题）。
    val targetRow = remember(rows, currentChapter, currentFragments) {
        resolveTocCurrentRow(rows, currentChapter, currentFragments)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 与设置共用同一套面板导航状态机（PanelNav）：activeIdx 共享高亮，kbHold 仲裁悬停。
    // 有效行 = 未被折叠隐藏（isVisible）；移动/跟随滚动/悬停认领全部走共享实现。
    val nav = remember { PanelNav() }
    fun moveActive(dir: Int) = nav.move(dir, 1, rows.size, ::isVisible) {
        scope.ensureListVisible(listState, it, dir)
    }
    fun clampActive() {
        val a = nav.activeIdx ?: return
        if (a !in rows.indices || !isVisible(a)) {
            nav.activeIdx = PanelNav.resolve(a, 1, 1, rows.size, ::isVisible)
                ?: PanelNav.resolve(a, -1, 1, rows.size, ::isVisible)
                ?: rows.indices.firstOrNull(::isVisible)
        }
    }

    // Panel slide + mask dim/lighten synchronized: the mask ramps at the same time and duration as the
    // panel slide (left docks here).
    val density = LocalDensity.current
    val slide = remember { Animatable(1f) }
    var mounted by remember { mutableStateOf(false) }
    var maskOn by remember { mutableStateOf(false) }
    val maskAlpha by animateFloatAsState(if (maskOn) 1f else 0f,
        tween(TocAnimMs, easing = FastOutSlowInEasing), label = "tocMask")

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            slide.snapTo(1f)
            // Flip the mask and the slide target at the same instant so both animate together.
            maskOn = true
            slide.animateTo(0f, tween(TocAnimMs, easing = FastOutSlowInEasing))
            // 目录项2（用户层）：每次打开重置为"仅当前章展开"，不沿用上次手风琴状态。
            collapsed = initialTocCollapsed(rows, targetRow)
            if (targetRow >= 0) {
                try { listState.scrollToItem((targetRow - 2).coerceAtLeast(0)) } catch (_: Exception) {}
            }
            // 共享高亮起点：静置鼠标所在行优先认领（行 hover effect 在动画期间认领），
            // 无鼠标才落当前项/首行——键盘从鼠标行或当前项接着走，不是从顶部重来。
            if (nav.activeIdx == null) {
                nav.activeIdx = (if (targetRow >= 0 && isVisible(targetRow)) targetRow else null)
                    ?: rows.indices.firstOrNull(::isVisible)
            }
            nav.releaseHold()
        } else {
            maskOn = false
            nav.activeIdx = null
            slide.animateTo(1f, tween(TocAnimMs, easing = FastOutSlowInEasing))
            delay((TocAnimMs + 20).toLong())
            mounted = false
        }
    }

    if (mounted) {
        // 焦点落抽屉只做按键捕获：移动是纯状态驱动（activeIdx），不需要焦点几何。
        val drawerFr = remember { FocusRequester() }
        LaunchedEffect(mounted, collapsed) {
            if (mounted) drawerFr.requestFocus()
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val drawerWidth = if (maxWidth < 600.dp) maxWidth * 0.85f else 360.dp
            val drawerPx = remember(drawerWidth, density) { with(density) { drawerWidth.toPx() } }
            Box(modifier = Modifier.fillMaxSize()) {
                // Dark mask over the rest of the screen.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(maskAlpha)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = onDismiss,
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((-slide.value * drawerPx).roundToInt(), 0) }
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .background(p.bg)
                        .focusRequester(drawerFr)
                        .focusTarget()
                        .panelKeyEvents(
                            onUp = { moveActive(-1) },
                            onDown = { moveActive(1) },
                            onEnter = {
                                nav.activeIdx?.takeIf { it in rows.indices && isVisible(it) }
                                    ?.let { onSelect(rows[it].item) }
                            },
                            onEscape = onDismiss,
                        )
                        // 点按消费（不抢焦点）：杂散点按不穿透到遮罩关闭层。
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("目录", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text("✕", color = p.text, fontSize = 18.sp, modifier = Modifier
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss))
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(p.borderSoft))
                    if (rows.isEmpty()) {
                        Text("暂无目录", color = p.muted2, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                                .clearKbHoldOnMove { nav.releaseHold() },
                        ) {
                            items(rows.size, key = { i -> i }) { rowIndex ->
                                if (!isVisible(rowIndex)) return@items
                                val row = rows[rowIndex]
                                TocRow(
                                    // 当前项 = 打开时定位到的那一行（章内优先当页子标题，
                                    // 无命中回退章首——比逐行 fragment 比对覆盖更全）。
                                    row = row,
                                    current = rowIndex == targetRow,
                                    palette = p,
                                    hasChildren = row.item.children.isNotEmpty(),
                                    expanded = row.idx !in collapsed,
                                    onToggle = {
                                        // 目录项2（用户层）：顶层展开手风琴式折叠其余章。
                                        collapsed = toggleTocCollapsed(collapsed, rows, row.idx)
                                        clampActive()
                                    },
                                    onSelect = { onSelect(row.item) },
                                    nav = nav,
                                    index = rowIndex,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One flattened TOC row in document order.
 *
 * Q1 收敛后公开：平板 `AndroidReaderTocPanel` 的同构私有实现已删，统一用此。
 */
data class TocRowData(
    val item: TocItem,
    val depth: Int,
    val idx: Int,
    val parentIndex: Int,
)

/** Flattens the TOC tree into document-order rows, recording each row's flattened parent index. */
fun flattenToc(toc: List<TocItem>): List<TocRowData> {
    val rows = ArrayList<TocRowData>()
    fun walk(items: List<TocItem>, depth: Int, parentIdx: Int) {
        for (it in items) {
            val idx = rows.size
            rows.add(TocRowData(it, depth, idx, parentIdx))
            if (it.children.isNotEmpty()) walk(it.children, depth + 1, idx)
        }
    }
    walk(toc, 0, -1)
    return rows
}

/**
 * 目录项1（用户层·共享）：当前阅读位置对应的扁平行。
 *
 * 同一章内可能有多行（章标题 + 章内子标题）：当页标题 id 命中时取文档序最末命中
 * （最深/最贴近阅读位置的子标题），无命中回退章首行，无章匹配返回 -1。
 */
fun resolveTocCurrentRow(
    rows: List<TocRowData>,
    currentChapter: Int,
    currentFragments: Set<String>,
): Int {
    if (rows.isEmpty()) return -1
    val inChapter = rows.indices.filter { rows[it].item.index == currentChapter }
    if (inChapter.isEmpty()) return -1
    if (currentFragments.isNotEmpty()) {
        inChapter.lastOrNull { rows[it].item.fragment != null && rows[it].item.fragment in currentFragments }
            ?.let { return it }
    }
    return inChapter.first()
}

/**
 * 目录项2a（用户层·共享）：打开时的初始折叠集合——默认全折叠，仅保留当前行所在
 * 顶层子树展开（"章" = depth 0 顶层节点；其嵌套子节点默认全展开）。
 */
fun initialTocCollapsed(rows: List<TocRowData>, currentRow: Int): Set<Int> {
    var root = -1
    if (currentRow in rows.indices) {
        var a = currentRow
        while (rows[a].parentIndex >= 0) a = rows[a].parentIndex
        root = a
    }
    return rows.indices.filter { i ->
        rows[i].depth == 0 && rows[i].item.children.isNotEmpty() && i != root
    }.toSet()
}

/**
 * 目录项2b（用户层·共享）：折叠切换——展开顶层节点时手风琴式折叠其余顶层节点；
 * 嵌套节点与折叠方向只管自己，不波及其他。
 */
fun toggleTocCollapsed(collapsed: Set<Int>, rows: List<TocRowData>, toggledIdx: Int): Set<Int> {
    if (toggledIdx !in rows.indices) return collapsed
    if (toggledIdx in collapsed) {
        if (rows[toggledIdx].depth != 0) return collapsed - toggledIdx
        val others = rows.indices.filter { i ->
            i != toggledIdx && rows[i].depth == 0 && rows[i].item.children.isNotEmpty()
        }.toSet()
        return (collapsed - toggledIdx) + others
    }
    return collapsed + toggledIdx
}

/** Panel slide and mask dim/lighten share this duration so they stay synchronized. */
private const val TocAnimMs = 280

/** Draws one TOC row: indent by depth, label, expand/toggle caret, current-chapter highlight. */
@Composable
private fun TocRow(
    row: TocRowData,
    current: Boolean,
    palette: Palette,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    nav: PanelNav,
    index: Int,
) {
    val gold = Color(0xFFC8A15A)
    // 当前项只标前景（金字）：背景是焦点（悬停/键盘 activeIdx）的，两者各行其是、
    // 互不冒充——焦点行才有底色，当前行只有金字。
    val active = nav.activeIdx == index
    // 行点按禁默认 ripple：框架自带悬停灰会和 active 高亮各行其是（鼠标一套灰、
    // 键盘一套米黄），唯一高亮只走 activeIdx（悬停认领/键盘共用）。
    val clickSrc = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(if (active) palette.rowActive else Color.Transparent)
                .clickable(
                    interactionSource = clickSrc, indication = null,
                    onClick = onSelect,
                )
                .panelHover(nav, index)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.width(8.dp + (row.depth * 14).dp))
            if (hasChildren) {
                // 目录三角（用户层）：正文色保证对比度（chevron 日间 #BBBBBB 太浅，且调色板
                // 与设置面板共用、不动它）；16sp/36dp 点击区，字形居中。
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onToggle),
                ) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        color = palette.text, fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Spacer(Modifier.size(36.dp))
            }
            Text(
                text = row.item.label,
                color = if (current) gold else palette.text,
                fontSize = 14.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
        }
    }
}