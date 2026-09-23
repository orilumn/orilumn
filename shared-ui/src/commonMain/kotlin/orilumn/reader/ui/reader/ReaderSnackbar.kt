package orilumn.reader.ui.reader

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * S29 `Toast` → CMP `Snackbar`：给阅读面一个轻量 snackbar 宿主（material3 commonMain），
 * 替代旧 Android `ReaderActivity.toast(...)` 的提示用途。
 *
 * 用法：
 * ```
 * val snackbar = rememberReaderSnackbar()
 * SnackbarHost(hostState = snackbar.hostState, modifier = ...)
 * snackbar.show("书签（规划中）")
 * ```
 * [show] 每次调用都会叠加新消息（material3 默认队列 + 自动消失），无需手动计时。
 */
class ReaderSnackbarController internal constructor(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        scope.launch {
            hostState.showSnackbar(message = message, duration = duration)
        }
    }
}

@Composable
fun rememberReaderSnackbar(): ReaderSnackbarController {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { ReaderSnackbarController(hostState, scope) }
}