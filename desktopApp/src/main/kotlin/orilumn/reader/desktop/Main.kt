package orilumn.reader.desktop

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.ReaderSettingsStore
import orilumn.reader.ui.App
import orilumn.reader.ui.reader.ThemePreset
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfSort
import orilumn.reader.ui.shelf.ShelfView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_SORT = "shelf_sort_name"
private const val PREF_VIEW = "shelf_view"
private const val PREF_WIN_W = "window_width_dp"
private const val PREF_WIN_H = "window_height_dp"
private const val PREF_WIN_X = "window_x_dp"
private const val PREF_WIN_Y = "window_y_dp"

/**
 * 存档坐标是否落在任一当前屏幕内（dp 按该屏缩放换算 px，200px 容差允许半出屏；
 * 取不到屏幕信息即放行）。显示器增减后越界坐标回默认位，不把窗口开到屏外。
 */
private fun onAnyScreen(xDp: Int, yDp: Int): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { dev ->
        val b = dev.defaultConfiguration.bounds
        val s = dev.defaultConfiguration.defaultTransform.scaleX.coerceAtLeast(1.0)
        val x = (xDp * s).toInt()
        val y = (yDp * s).toInt()
        x in b.x - 200..b.x + b.width && y in b.y - 200..b.y + b.height
    }
}.getOrDefault(true)

/**
 * S32 桌面最小壳：JVM `main` 起窗口挂 shared-ui。
 *
 * 薄壳契约（与 S31 Android 壳同口径）：
 * - 只做真系统级：数据根初始化、窗口管理、导航状态；
 * - 书架（列表/导入/mpfilepicker/Snackbar）走 [App]；
 * - 阅读（画布/手势/进度/遮罩）走 [ReaderView] + [DesktopReaderHost]（首个真实 Skia 管线）；
 * - 设置（全局 `reader.json` + 自定义主题预设持久化）走 [ReaderView] 内的设置面板。
 */
fun main() {
    DesktopPaths.ensure()
    application {
        // 三端同一 JVM 壳：窗口尺寸+位置经 prefs 持久化，下次启动原地恢复
        // （Win/macOS/Linux 同口径；显示器变更致越界时回居中，不开到屏外）。
        val store = remember { DesktopShelfStore(DesktopPaths.root) }
        val windowState = rememberWindowState(
            width = store.loadPref(PREF_WIN_W, "1100").toIntOrNull()?.coerceIn(480, 2560)?.dp ?: 1100.dp,
            height = store.loadPref(PREF_WIN_H, "800").toIntOrNull()?.coerceIn(320, 1600)?.dp ?: 800.dp,
            position = remember {
                val x = store.loadPref(PREF_WIN_X, "").toIntOrNull()
                val y = store.loadPref(PREF_WIN_Y, "").toIntOrNull()
                if (x != null && y != null && onAnyScreen(x, y)) {
                    androidx.compose.ui.window.WindowPosition.Absolute(x.dp, y.dp)
                } else {
                    androidx.compose.ui.window.WindowPosition.PlatformDefault
                }
            },
        )
        // 移动/调整完 500ms 落盘（IO 线程，不挡 UI；下次启动即原地恢复）。
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size to windowState.position }
                .debounce(500)
                .collect { (size, pos) ->
                    withContext(Dispatchers.IO) {
                        store.savePref(PREF_WIN_W, size.width.value.toInt().toString())
                        store.savePref(PREF_WIN_H, size.height.value.toInt().toString())
                        (pos as? androidx.compose.ui.window.WindowPosition.Absolute)?.let {
                            store.savePref(PREF_WIN_X, it.x.value.toInt().toString())
                            store.savePref(PREF_WIN_Y, it.y.value.toInt().toString())
                        }
                    }
                }
        }
        // icon.png 走 JVM classpath（src/main/resources）：旧 painterResource(String)
        // 在 CMP 1.7 虽 deprecated 但仍是 classpath 加载的可用口径，故显式 Suppress。
        @Suppress("DEPRECATION")
        val appIcon = painterResource("icon.png")
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Orilumn",
            icon = appIcon,
        ) {
            MaterialTheme {
                DesktopRoot(store = store)
            }
        }
    }
}

@Composable
private fun DesktopRoot(store: DesktopShelfStore) {
    val settingsStore = remember { ReaderSettingsStore(DesktopPaths.settingsDir.absolutePath) }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(settingsStore.load()) }
    var customs by remember { mutableStateOf(emptyList<ThemePreset>()) }
    var openBook by remember { mutableStateOf<ShelfBook?>(null) }
    // DB 打开是挂起调用：首帧组合先于 LaunchedEffect 执行，直接挂 App 会让
    // ShelfScreen 首帧即调 books() 撞上 requireDb()（DesktopShelfStore 未 load）。
    // 以 ready 门控首帧，未就绪只画加载占位。
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.load()
        customs = store.loadThemes()
        ready = true
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载书库…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    fun persistSettings(next: ReaderSettings) {
        settings = next
        scope.launch(Dispatchers.IO) { settingsStore.save(next) }
    }

    if (openBook == null) {
        App(
            shelfRepository = store,
            shelfHost = remember(store) { DesktopShelfHost(store) },
            initialSort = runCatching {
                ShelfSort.valueOf(store.loadPref(PREF_SORT, ShelfSort.Added.name))
            }.getOrDefault(ShelfSort.Added),
            initialView = runCatching {
                ShelfView.valueOf(store.loadPref(PREF_VIEW, ShelfView.Grid.name))
            }.getOrDefault(ShelfView.Grid),
            onPersistSort = { store.savePref(PREF_SORT, it.name) },
            onPersistView = { store.savePref(PREF_VIEW, it.name) },
            onOpenBook = { openBook = it },
        )
    } else {
        val book = openBook!!
        ReaderView(
            book = book,
            store = store,
            settings = settings,
            customs = customs,
            onBack = { openBook = null },
            onSettingsChange = ::persistSettings,
            onSaveTheme = { preset ->
                scope.launch {
                    store.saveTheme(preset)
                    customs = store.loadThemes()
                }
            },
            onDeleteTheme = { preset ->
                scope.launch {
                    store.deleteTheme(preset)
                    customs = store.loadThemes()
                }
            },
        )
    }
}
