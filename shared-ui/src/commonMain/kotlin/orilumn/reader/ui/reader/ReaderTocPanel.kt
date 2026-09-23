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
 * 列出书本层级（缩进/当前章高亮/逐节点折叠展开），选中经 [onSelect] 跳转。
 * 平移自 Android `ReaderTocPanel`；Android 返回键同样不在 commonMain 承载（壳 S31 负责）。
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
    val currentIndex = remember(rows, currentChapter) { rows.indexOfFirst { it.item.index == currentChapter } }

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

    LaunchedEffect(visible, currentIndex) {
        if (visible) {
            mounted = true
            slide.snapTo(1f)
            // Flip the mask and the slide target at the same instant so both animate together.
            maskOn = true
            slide.animateTo(0f, tween(TocAnimMs, easing = FastOutSlowInEasing))
            val vi = rows.indexOfFirst { it.item.index == currentChapter }
            if (vi >= 0) {
                try { listState.scrollToItem((vi - 2).coerceAtLeast(0)) } catch (_: Exception) {}
            }
            // 共享高亮起点：静置鼠标所在行优先认领（行 hover effect 在动画期间认领），
            // 无鼠标才落当前章/首行——键盘从鼠标行接着走，不是从顶部重来。
            if (nav.activeIdx == null) {
                nav.activeIdx = (if (vi >= 0 && isVisible(vi)) vi else null)
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
                                val onCurrentPage = row.item.index == currentChapter &&
                                    row.item.fragment != null && row.item.fragment in currentFragments
                                TocRow(
                                    row = row,
                                    current = onCurrentPage,
                                    palette = p,
                                    hasChildren = row.item.children.isNotEmpty(),
                                    expanded = row.idx !in collapsed,
                                    onToggle = {
                                        collapsed = if (row.idx in collapsed) collapsed - row.idx
                                            else collapsed + row.idx
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

/** One flattened TOC row in document order. */
internal data class TocRowData(
    val item: TocItem,
    val depth: Int,
    val idx: Int,
    val parentIndex: Int,
)

/** Flattens the TOC tree into document-order rows, recording each row's flattened parent index. */
internal fun flattenToc(toc: List<TocItem>): List<TocRowData> {
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
    // 共享高亮即当前章标记语言：active（悬停/键盘）与 current 共用 rowActive 底，
    // 当前章另有金字；键盘焦点不另起视觉，鼠标不动。
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
                .background(if (active || current) palette.rowActive else Color.Transparent)
                .clickable(
                    interactionSource = clickSrc, indication = null,
                    onClick = onSelect,
                )
                .panelHover(nav, index)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.width(8.dp + (row.depth * 14).dp))
            if (hasChildren) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = palette.chevron, fontSize = 11.sp,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onToggle),
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.size(22.dp))
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