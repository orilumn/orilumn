package orilumn.reader.data.settings

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import okio.use
import orilumn.reader.io.Logger

/**
 * Per-book private settings override read/write: `settingsDir/books/{bookId}.json`.
 *
 * Responsibilities:
 *  - [load]: read a book's sparse overlay; no file → [BookSettings.EMPTY]
 *    (每项回**预设**，不是回「全局」——全局区里已经没有私有项了).
 *  - [save]: save the overlay (atomic write; 空层即删文件).
 *  - [reset]: delete a book's overlay; afterwards the book fully falls back to the book's own styles.
 *
 * 旧模型（整份快照）→ 新模型（稀疏覆盖）的**一次性**迁移也在本类完成：迁移前每本书的行距必有
 * 1.5、缩进必有 0（旧默认值），按「没配」读一次后重写文件并落版本标记（`settings/books.version`）。
 * 迁移之后 1.5 / 0 就是读者真设的值，**不再**被改写——值判据会让「读者就要 1.5」每次重开书
 * 都被打回原书的值，这正是本类不在常规读档里做值映射的原因。
 *
 * Thread safety: methods are called from IO threads; recommended to run within coroutine Dispatchers.IO.
 * Platform-agnostic via okio [FileSystem.SYSTEM] (S18: java.io.File → okio, joins commonMain).
 *
 * @see BookSettings overlay data class
 * @see ReaderSettingsStore global memory
 */
class BookSettingsStore(settingsDir: String) {

    private val fs = FileSystem.SYSTEM

    private val settingsDir: Path = settingsDir.toPath()
    private val booksDir: Path = this.settingsDir / BOOKS_DIR
    private val versionFile: Path = this.settingsDir / VERSION_FILE

    /** Read a book's overlay; no file → [BookSettings.EMPTY]. */
    fun load(bookId: Long): BookSettings {
        migrateLegacyOverlaysIfNeeded()
        val file = bookFile(bookId)
        val text = if (fs.exists(file)) fs.source(file).buffer().use { it.readUtf8() } else null
        return if (text != null) BookSettings.fromJson(text) else BookSettings.EMPTY
    }

    /** Save a book's overlay (atomic write). */
    fun save(bookId: Long, overlay: BookSettings) {
        migrateLegacyOverlaysIfNeeded()
        if (overlay.isEmpty) {
            // Empty overlay needs no persistence; just delete any existing file
            reset(bookId)
            return
        }
        fs.createDirectories(booksDir)
        val file = bookFile(bookId)
        fs.writeTextAtomic(file, overlay.toJson(2))
    }

    /** Delete a book's overlay. */
    fun reset(bookId: Long) {
        val file = bookFile(bookId)
        fs.delete(file, mustExist = false)
        fs.delete(file.withSuffix(".tmp"), mustExist = false)
    }

    /**
     * 旧档（整份快照）一次性迁移：把每本书的行距 1.5、缩进 0 读成「没配」并重写文件，然后落
     * [VERSION_FILE] 标记。标记存在即跳过，所以读者后来真的设成 1.5 / 0 也不会被再次改写。
     * 失败不落标记（下次重试）；迁移是幂等的（已迁移的文件里没有这两个旧默认值可删）。
     */
    private fun migrateLegacyOverlaysIfNeeded() {
        if (fs.exists(versionFile)) return
        runCatching {
            val files = if (fs.exists(booksDir)) {
                fs.list(booksDir).filter { it.name.endsWith(".json") }
            } else {
                emptyList()
            }
            for (file in files) {
                val raw = fs.source(file).buffer().use { it.readUtf8() }
                val migrated = BookSettings.fromJson(raw, legacy = true).normalized()
                if (migrated.isEmpty) {
                    fs.delete(file, mustExist = false)
                } else {
                    fs.writeTextAtomic(file, migrated.toJson(2))
                }
            }
            fs.createDirectories(settingsDir)
            fs.writeTextAtomic(versionFile, VERSION)
        }.onFailure {
            Logger.w("Orilumn.SET", "book overlays legacy migration FAIL ${it.message}（下次重试）")
        }
    }

    private fun bookFile(bookId: Long): Path = booksDir / "$bookId.json"

    private companion object {
        const val BOOKS_DIR = "books"

        /** 迁移标记：内容即当前书档结构版本（见 [migrateLegacyOverlaysIfNeeded]）。 */
        const val VERSION_FILE = "books.version"
        const val VERSION = "2"
    }
}
