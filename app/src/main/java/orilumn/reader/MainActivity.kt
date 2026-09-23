package orilumn.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import orilumn.reader.data.book.BookImporter
import orilumn.reader.data.font.FontRepository
import orilumn.reader.data.settings.ReaderSettingsStore
import orilumn.reader.host.AndroidDb
import orilumn.reader.host.AndroidShelfHost
import orilumn.reader.host.AndroidShelfRepository
import orilumn.reader.io.AppRoot
import orilumn.reader.io.Logger
import orilumn.reader.ui.App
import orilumn.reader.ui.reader.EXTRA_BOOK_ID
import orilumn.reader.ui.reader.EXTRA_BOOK_PATH
import orilumn.reader.ui.reader.ReaderActivity
import orilumn.reader.ui.shelf.ShelfSort
import orilumn.reader.ui.shelf.ShelfView
import orilumn.reader.ui.theme.OrilumnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * S31 薄壳：书架 Activity 只做三件事——
 * 1. 真系统级：edge-to-edge + 系统栏图标 + [AppRoot] 路径注入（S30 横切 seam，桌面由 jvmTest 覆盖）；
 * 2. `setContent { App(...) }` 挂载 shared-ui 书架（列表/封面/删除/排序/导入/mpfilepicker/Snackbar 全在通用层）；
 * 3. 壳导航：`onOpenBook` 起 [ReaderActivity]（阅读面 engine/卷曲仍是 Android 专属实现，留壳）。
 *
 * 已移除的内在实现（随 S27–S29 平移进 shared-ui，S31 删除，不再双份维护）：
 * - 书架 Compose（ShelfScreen/ToolItem/CoverThumb/BookCoverGridItem/CoverListRow 全量 ~600 行）；
 * - SAF `OpenMultipleDocuments` launcher + 逐本导入/覆盖聚合（`ShelfImporter` + FileKit 替代）；
 * - `Toast` 反馈（CMP `Snackbar` 替代）；
 * - 排序/视图 SharedPreferences 读写保留在壳内（轻量 prefs，非 UI 逻辑）。
 *
 * 保留项说明（S31-cleanup 已收敛）：
 * - 日志统一走 common `Logger`：`util/FileLogger` 已删除，阅读面/engine/字体池均经 common
 *   `Logger` 落盘 `<filesDir>/logs/`（直启阅读页时 `ReaderActivity` 补 `AppRoot.init` 兜底）；
 * - `WRITE_SETTINGS`/亮度物理背光、`curl` 卷曲、`WifiImportDialog` 的 SAF 字体导入均在
 *   `ReaderActivity` 壳内（目标结构「留 androidApp」项），本书架壳不碰。
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy { AndroidDb.repository(this) }
    private val importer by lazy { BookImporter(this, repository) }

    /** Reading config; autoContinue decides whether to auto-enter the last read book on shelf launch. */
    private val settingsStore by lazy { ReaderSettingsStore(File(filesDir, "settings").absolutePath) }

    /** Records the primary key of the last opened book for cold-start "open and continue reading" navigation. */
    private val prefs by lazy { getSharedPreferences("reader_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // S30 横切注入：common Logger/AppRoot 的数据根（只首次生效；桌面壳/测试注入各自临时目录）。
        AppRoot.init(filesDir.absolutePath.toPath(), FileSystem.SYSTEM)
        Logger.i(TAG, "shelf onCreate")
        // S33/S34a/S34b：首启把 Room 书目/进度/字体导入 SQLDelight（后台；书架流来自新库，导入完成即推送）。
        lifecycleScope.launch(Dispatchers.IO) {
            AndroidDb.importBooksFromRoomIfNeeded(this@MainActivity)
            AndroidDb.importProgressFromRoomIfNeeded(this@MainActivity)
            AndroidDb.importFontsFromRoomIfNeeded(this@MainActivity)
            // 孤儿字体文件自愈（有字节无记录）：书架字体管理页直接受益。
            runCatching {
                FontRepository(this@MainActivity, AndroidDb.library(this@MainActivity)).reconcileOrphanFiles()
            }
        }

        if (savedInstanceState == null) maybeContinueLastBook()

        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val shelfRepository = AndroidShelfRepository(repository)
        val shelfHost = AndroidShelfHost(this, repository, importer)
        val initialSort = runCatching {
            ShelfSort.valueOf(loadPref(prefs, KEY_SORT_NAME, ShelfSort.Added.name))
        }.getOrDefault(ShelfSort.Added)
        val initialView = runCatching {
            ShelfView.valueOf(loadPref(prefs, KEY_SHELF_VIEW, ShelfView.Grid.name))
        }.getOrDefault(ShelfView.Grid)

        setContent {
            OrilumnTheme {
                App(
                    shelfRepository = shelfRepository,
                    shelfHost = shelfHost,
                    initialSort = initialSort,
                    initialView = initialView,
                    onPersistSort = { prefs.edit().putString(KEY_SORT_NAME, it.name).apply() },
                    onPersistView = { prefs.edit().putString(KEY_SHELF_VIEW, it.name).apply() },
                    onOpenBook = { openReader(it.id, it.filePath) },
                )
            }
        }
    }

    private fun openReader(bookId: Long, filePath: String) {
        lifecycleScope.launch { repository.touchRead(bookId) }
        prefs.edit().putLong(KEY_LAST_BOOK_ID, bookId).apply()
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_PATH, filePath)
                .putExtra(EXTRA_BOOK_ID, bookId),
        )
    }

    /** "Open and continue reading": when enabled and a last-read book is recorded → auto-enter its reader on app start. */
    private fun maybeContinueLastBook() {
        if (!settingsStore.load().autoContinue) return
        val lastId = prefs.getLong(KEY_LAST_BOOK_ID, -1L)
        if (lastId <= 0) return
        lifecycleScope.launch {
            val book = repository.getBook(lastId)
            if (book != null) {
                Logger.i(TAG, "continue last book id=$lastId title=${book.title}")
                openReader(book.id, book.filePath)
            } else {
                prefs.edit().remove(KEY_LAST_BOOK_ID).apply()
            }
        }
    }

    companion object {
        private const val TAG = "Orilumn.Main"
        private const val KEY_LAST_BOOK_ID = "last_book_id"
        private const val KEY_SHELF_VIEW = "shelf_view"
        private const val KEY_SORT_NAME = "shelf_sort_name"

        /** Persisted shelf view/sort: invalid values fall back to defaults. */
        private fun loadPref(prefs: android.content.SharedPreferences, key: String, default: String) =
            prefs.getString(key, null)?.takeIf { it.isNotEmpty() } ?: default
    }
}
