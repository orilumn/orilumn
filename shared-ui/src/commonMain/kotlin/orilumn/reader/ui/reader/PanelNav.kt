package orilumn.reader.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 面板键盘导航共享状态机：目录与设置共用**同一套**（不是各搞一套）。
 *
 * 模型（与原生菜单同语义）：
 * - [activeIdx]：悬停与键盘共用的唯一高亮行；
 * - [kbHold]：键盘导航期间忽略悬停认领（滚动带过静止光标不抢高亮），鼠标坐标真变即交还；
 * - 移动 = 按方向找下一个有效行（目录的有效 = 未折叠可见，设置的有效 = 非 disabled），
 *   与焦点几何/Tab 序无关（`moveFocus` 在异构 LazyColumn 行间不可靠，已退役）。
 *
 * 条目模型差异由调用方以 lambda 传入（[move]/[resolve] 的 `isValid`、`count`、`stride`，
 * 跟随滚动的 `listPosOf` 映射），本文件只收敛状态 + 扫描 + 滚动 + 悬停四件套。
 */
class PanelNav {
    var activeIdx: Int? by mutableStateOf(null)
    var kbHold: Boolean by mutableStateOf(false)

    /** 键盘落位：认领高亮并持有（屏蔽悬停）。 */
    fun land(i: Int) {
        activeIdx = i
        kbHold = true
    }

    /** 悬停认领：只挪高亮，不持有。 */
    fun hoverAt(i: Int) {
        activeIdx = i
    }

    /** 鼠标真动：交还悬停。 */
    fun releaseHold() {
        kbHold = false
    }

    /**
     * 从当前高亮按 [dir]（±1）走 [stride] 步找下一个有效行，落位并回调（调用方做跟随滚动）。
     * 无高亮（null）时不动作，由调用方决定初始落点（目录落当前章，设置落首行）。
     */
    fun move(
        dir: Int,
        stride: Int = 1,
        count: Int,
        isValid: (Int) -> Boolean,
        lo: Int = 0,
        onLand: (Int) -> Unit = {},
    ) {
        val a = activeIdx ?: return
        resolve(a, dir, stride, count, isValid, lo)?.let { land(it); onLand(it) }
    }

    companion object {
        /**
         * 从 [start]（已含步进的目标位）按 [dir] 扫描到 [lo, count) 内第一个有效行。
         * 越界/无有效行回 null（调用方保持原高亮）。
         */
        fun resolveFrom(
            start: Int,
            dir: Int,
            count: Int,
            isValid: (Int) -> Boolean,
            lo: Int = 0,
        ): Int? {
            if (count <= lo) return null
            var i = start.coerceIn(lo, count - 1)
            while (i in lo until count) {
                if (isValid(i)) return i
                i += dir
            }
            return null
        }

        /** 从 [from] 出发先走 [stride] 步，再按 [dir] 扫描（[move] 的步进版）。 */
        fun resolve(
            from: Int,
            dir: Int,
            stride: Int,
            count: Int,
            isValid: (Int) -> Boolean,
            lo: Int = 0,
        ): Int? = resolveFrom(from + dir * stride, dir, count, isValid, lo)
    }
}

/**
 * 行悬停认领（目录/设置共用）：悬停即把 [PanelNav.activeIdx] 挪过来；
 * 键盘持有期间忽略（滚动带过静止光标不算鼠标移动）。
 */
@Composable
fun Modifier.panelHover(nav: PanelNav, index: Int): Modifier {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    LaunchedEffect(hovered, nav.kbHold) { if (hovered && !nav.kbHold) nav.hoverAt(index) }
    return this.hoverable(src)
}

/**
 * 原生菜单滚动策略（目录/设置共用）：目标在窗内只走高亮不动列表，越界才滚——
 * 向下越界落视口底部，向上越界落顶部。只用 `scrollToItem(i)`，不用 `scrollOffset`
 *（实测它不是"距视口顶偏移"，见目录面板记录）。
 */
fun CoroutineScope.ensureListVisible(listState: LazyListState, pos: Int, dir: Int) {
    val vis = listState.layoutInfo.visibleItemsInfo
    if (vis.any { it.index == pos }) return
    val first = if (dir > 0) (pos - vis.size + 1).coerceAtLeast(0) else pos
    launch { runCatching { listState.scrollToItem(first) } }
}

/**
 * 鼠标坐标真变即交还悬停（目录/设置共用；过滤 relayout 合成事件——官方明示 relayout
 * 会发同坐标 synthetic Move，滚动带过静止光标不抢高亮）。
 */
internal fun Modifier.clearKbHoldOnMove(onMove: () -> Unit): Modifier = this.pointerInput(Unit) {
    var last: androidx.compose.ui.geometry.Offset? = null
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Move) {
                val pos = e.changes.firstOrNull()?.position
                if (pos != null && pos != last) onMove()
                last = pos
            }
        }
    }
}

/**
 * 键盘焦点环（F4a 从 `ReaderSettingsPanel.kt` 搬出共用）：鼠标点按行获得焦点时的描边反馈
 * （键盘 active 高亮另走目录同款 rowActive 底）。挂在 clickable 之后。
 */
@Composable
internal fun Modifier.kbRing(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 1.dp else 0.dp,
            color = if (focused) KbFocusRing else androidx.compose.ui.graphics.Color.Transparent,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        )
}
