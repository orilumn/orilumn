package orilumn.reader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
 * Reading table-of-contents drawer: a **left-docked** panel mirroring the settings drawer's slide +
 * mask interaction (and its palette), listing the book's hierarchy with indentation, current-chapter
 * highlight, and per-node expand/collapse. Selecting an item calls [onSelect].
 */
@Composable
fun AndroidReaderTocPanel(
    visible: Boolean,
    toc: List<TocItem>,
    currentChapter: Int,
    scheme: String,
    currentFragments: Set<String> = emptySet(),
    onSelect: (TocItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = paletteFor(scheme)
    val drawerWidth = with(LocalConfiguration.current) {
        if (screenWidthDp < 600) (screenWidthDp * 0.85f).dp else 360.dp
    }
    var collapsed by remember { mutableStateOf(setOf<Int>()) }

    val rows = remember(toc) { flattenToc(toc) }
    fun isVisible(rowIndex: Int): Boolean {
        var a = rows[rowIndex].parentIndex
        while (a >= 0) { if (a in collapsed) return false; a = rows[a].parentIndex }
        return true
    }
    val visibleRows = remember(rows, collapsed) { rows.filterIndexed { i, _ -> isVisible(i) } }
    val currentIndex = remember(rows, currentChapter) { rows.indexOfFirst { it.item.index == currentChapter } }

    val listState = rememberLazyListState()

    // Panel slide + mask dim/lighten synchronized: the mask ramps at the same time and duration as the
    // panel slide (left docks here).
    val density = LocalDensity.current
    val drawerPx = remember(drawerWidth, density) { with(density) { drawerWidth.toPx() } }
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
        } else {
            maskOn = false
            slide.animateTo(1f, tween(TocAnimMs, easing = FastOutSlowInEasing))
            delay((TocAnimMs + 20).toLong())
            mounted = false
        }
    }
    BackHandler(enabled = visible) { onDismiss() }

    if (mounted) {
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = {},
                    ),
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
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
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
                                },
                                onSelect = { onSelect(row.item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One flattened TOC row in document order. */
private class TocRow(
    val item: TocItem,
    val depth: Int,
    val idx: Int,
) {
    /** Flat index of the parent row; -1 for a top-level row. */
    var parentIndex: Int = -1
}

/** Flattens the TOC tree into document-order rows, recording each row's flattened parent index. */
private fun flattenToc(toc: List<TocItem>): List<TocRow> {
    val rows = ArrayList<TocRow>()
    fun walk(items: List<TocItem>, depth: Int, parentIdx: Int) {
        for (it in items) {
            val idx = rows.size
            rows.add(TocRow(it, depth, idx).apply { parentIndex = parentIdx })
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
    row: TocRow,
    current: Boolean,
    palette: AndroidPalette,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val gold = Color(0xFFC8A15A)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(if (current) palette.rowActive else Color.Transparent)
                .clickable(onClick = onSelect)
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