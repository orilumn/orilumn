package orilumn.reader.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfHost
import orilumn.reader.ui.shelf.ShelfRepository
import orilumn.reader.ui.shelf.ShelfScreen
import orilumn.reader.ui.shelf.ShelfSort
import orilumn.reader.ui.shelf.ShelfView

/**
 * S31 androidApp 瘦身入口：shared-ui 根 composable。
 *
 * 薄壳契约（对齐目标结构 `androidApp 极薄宿主`）：
 * - Android `MainActivity` 只做 `setContent { App(...) }` + 真系统级（edge-to-edge、
 *   `AppRoot` 路径注入、系统亮度/导航），不再持有书架列表/导入/封面/排序等内在实现；
 * - 书架 UI（列表/封面/删除/排序/导入编排/mpfilepicker 文件选择/Snackbar）全部由
 *   [ShelfScreen]（commonMain，S27–S29）承载；
 * - 打开书籍仍走壳内导航（`onOpenBook` 回调起 `ReaderActivity`）：阅读面 engine 仍是
 *   StaticLayout + 卷曲（Android 专属，目标结构明确留壳），待 Skia 绘制全量接管后再把
 *   `ReaderScreen` 挂进本导航（S32 桌面壳/后续阅读面切换项）。
 */
@Composable
fun App(
    shelfRepository: ShelfRepository,
    shelfHost: ShelfHost,
    modifier: Modifier = Modifier,
    initialSort: ShelfSort = ShelfSort.Added,
    initialView: ShelfView = ShelfView.Grid,
    onPersistSort: (ShelfSort) -> Unit = {},
    onPersistView: (ShelfView) -> Unit = {},
    onOpenBook: (ShelfBook) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ShelfScreen(
        repository = shelfRepository,
        host = remember(shelfHost) {
            object : ShelfHost by shelfHost {
                override fun openBook(book: ShelfBook) = onOpenBook(book)
            }
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        initialSort = initialSort,
        initialView = initialView,
        onPersistSort = onPersistSort,
        onPersistView = onPersistView,
    )
}
