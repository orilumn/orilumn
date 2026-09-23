package orilumn.reader.desktop

import orilumn.reader.io.AppRoot
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * S32 桌面数据根：`~/.orilumn/`（XDG 前可先用家目录；Linux/macOS 通用，Windows 由
 * user.home 同理落盘）。
 *
 * 布局（与 Android `filesDir` 语义对齐，见 S31 `MainActivity`）：
 * - `books/`：私有的 EPUB 副本（导入即拷贝，不依赖外部文件存活）；
 * - `covers/`：封面缓存（原字节，不做缩放，解码时由 Skia 按需处理）；
 * - `progress/`：每书阅读定位 `{id}.json`；
 * - `settings/`：`ReaderSettingsStore` 全局阅读设置；
 * - `cache/`：EPUB 解析临时文件。
 * - `fonts/`：F4b 用户字体私有副本（首版只读：隐藏可用，导入后续加法）。
 *
 * 改名迁移：包名 orilum→orilumn 时数据根由 `~/.orilum/` 换到 `~/.orilumn/`；
 * [ensure] 首次发现旧目录且新目录不存在时整体搬过去，老书库/进度/设置不断档。
 */
object DesktopPaths {
    val root: File = File(System.getProperty("user.home"), ".orilumn")
    val booksDir: File = File(root, "books")
    val coversDir: File = File(root, "covers")
    val progressDir: File = File(root, "progress")
    val settingsDir: File = File(root, "settings")
    val cacheDir: File = File(root, "cache")
    val fontsDir: File = File(root, "fonts")

    val okioRoot: Path get() = root.absolutePath.toPath()

    fun ensure() {
        migrateLegacyRoot()
        for (d in listOf(root, booksDir, coversDir, progressDir, settingsDir, cacheDir, fontsDir)) d.mkdirs()
        // S30 横切注入：common Logger/AppRoot 的数据根（只首次生效）。
        AppRoot.init(okioRoot, FileSystem.SYSTEM)
    }

    /** 改名一次性迁移：`~/.orilum/` → `~/.orilumn/`（新目录已存在则不动）。 */
    private fun migrateLegacyRoot() {
        if (root.exists()) return
        val legacy = File(System.getProperty("user.home"), ".orilum")
        if (legacy.exists()) runCatching { legacy.renameTo(root) }
    }
}
