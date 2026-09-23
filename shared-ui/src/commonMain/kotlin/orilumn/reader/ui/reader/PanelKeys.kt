package orilumn.reader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 面板按键路由（目录/设置共享的按键语言：Esc 关闭·回退，↑↓ 移动，Enter 选中，←→ 调值/网格内移动）。
 *
 * 只收敛**路由**；移动状态机是另一处共享——[PanelNav]（PanelNav.kt，目录/设置共用同一套：
 * activeIdx 共享高亮 + kbHold 悬停仲裁 + 按方向找有效行 + 跟随滚动 + 悬停认领）。
 * 两面板的差异只在条目模型（目录：折叠可见性；设置：disabled + 网格跨步 + 滑块调值），
 * 以 lambda 传入共享原语，不各写一套。
 */
fun Modifier.panelKeyEvents(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
): Modifier = this.onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (event.key) {
        Key.Escape -> { onEscape(); true }
        Key.DirectionUp -> { onUp(); true }
        Key.DirectionDown -> { onDown(); true }
        Key.DirectionLeft -> { onLeft(); true }
        Key.DirectionRight -> { onRight(); true }
        Key.Enter, Key.NumPadEnter -> { onEnter(); true }
        else -> false
    }
}
