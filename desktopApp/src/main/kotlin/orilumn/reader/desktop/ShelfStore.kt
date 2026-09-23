package orilumn.reader.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.book.Book
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.db.BookSort
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.db.OrilumnDb
import orilumn.reader.ui.reader.ThemePreset
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfRepository
import orilumn.reader.ui.shelf.ShelfSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import java.io.File

/** S33 前的书目索引行（JSON）：仅导入期读取，导入后删除文件（见 [DesktopShelfStore.load]）。 */
@Serializable
data class DesktopBookEntry(
    val id: Long,
    val title: String,
    val author: String? = null,
    val filePath: String,
    val coverPath: String? = null,
    val addedAt: Long = 0L,
    val readTime: Long? = null,
)

@Serializable
private data class ShelfIndex(val nextId: Long = 1L, val books: List<DesktopBookEntry> = emptyList())

/** 阅读定位：章节下标 + 章内字符偏移（内存形态；落库形态见 locator 串）。 */
@Serializable
data class ReadingLocator(val chapter: Int = 0, val char: Int = 0)

/** 自定义阅读主题预设的桌面持久形态（与 shared-ui `ThemePreset` 同构）。 */
@Serializable
data class ThemePresetEntry(val label: String, val bg: String, val fg: String) {
    fun toPreset(): ThemePreset = ThemePreset(label, bg, fg)
}

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

/**
 * S33 桌面书架仓储：SQLDelight 实现 shared-ui [ShelfRepository]。
 *
 * 书目索引由 S32 的 `shelf.json` 迁入 `orilumn.db`（[load] 时一次性导入并删文件，
 * id 延续以保进度关联）；轻量偏好与主题预设仍走文件（非关系数据）。
 */
class DesktopShelfStore(root: File) : ShelfRepository {
    private val dbFile = File(root, "orilumn.db")
    private val legacyIndex = File(root, "shelf.json")
    private val progressDir = File(root, "progress")
    private val prefsFile = File(root, "prefs.properties")

    private val mutex = Mutex()
    private var db: LibraryDb? = null
    private var fontLibrary: FontLibrary? = null

    private fun requireDb(): LibraryDb = checkNotNull(db) { "DesktopShelfStore.load() 尚未调用" }

    /** F4b 共享字体库（`~/.orilumn/fonts`，首版只读：系统枚举 + 隐藏可用，导入后续加法）。 */
    fun fontLibrary(): FontLibrary = checkNotNull(fontLibrary) { "DesktopShelfStore.load() 尚未调用" }

    suspend fun load() {
        val library = mutex.withLock {
            db ?: openDb().also { db = it }
        }
        if (fontLibrary == null) {
            fontLibrary = FontLibrary(
                db = library,
                fs = okio.FileSystem.SYSTEM,
                fontsDir = DesktopPaths.fontsDir.absolutePath.toPath(),
            )
        }
        importLegacyIndexIfNeeded(library)
        importLegacyProgressIfNeeded(library)
    }

    private fun openDb(): LibraryDb {
        dbFile.parentFile.mkdirs()
        val fresh = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        val current = OrilumnDb.Schema.version
        if (fresh) {
            OrilumnDb.Schema.create(driver)
            // 建库即盖章：JdbcSqliteDriver 不像 Android helper 自动维护 user_version，
            // 不盖则库永为 0，后续迁移的 `old in 1 until current` 永远跳过（F 系列真实故障）。
            runCatching { driver.execute(null, "PRAGMA user_version = $current;", 0) }
        } else {
            val old = runCatching {
                driver.executeQuery(
                    null,
                    "PRAGMA user_version;",
                    { cursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                    },
                    0,
                ) { }.value
            }.getOrDefault(0L)
            if (old == 0L) {
                // 遗留无版本库（旧版 create 未盖章）：字体表查漏补缺后盖章。
                repairUnversionedFontTable(driver)
                runCatching { driver.execute(null, "PRAGMA user_version = $current;", 0) }
            } else if (old in 1 until current) {
                OrilumnDb.Schema.migrate(driver, old, current)
            }
        }
        return LibraryDb(OrilumnDb(driver))
    }

    /**
     * 无版本库的字体表修复（F 系列）：v3 时代无 font_faces 即建（含唯一索引，对齐 3.sqm），
     * v4 时代缺 hidden 列即加（对齐 4.sqm）。其他表不动（书目/进度口径未变）。
     */
    private fun repairUnversionedFontTable(driver: JdbcSqliteDriver) {
        fun columnsOf(table: String): List<String> = runCatching {
            driver.executeQuery(
                null,
                "PRAGMA table_info($table);",
                { cursor ->
                    QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(1) ?: "") })
                },
                0,
            ) { }.value
        }.getOrDefault(emptyList())
        if (columnsOf("font_faces").isEmpty()) {
            runCatching {
                driver.execute(
                    null,
                    "CREATE TABLE font_faces(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "familyName TEXT NOT NULL, displayName TEXT NOT NULL, subfamily TEXT NOT NULL DEFAULT '', " +
                        "source TEXT NOT NULL, path TEXT, lang TEXT NOT NULL, " +
                        "hidden INTEGER NOT NULL DEFAULT 0);",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE UNIQUE INDEX index_font_faces_familyName_subfamily_source " +
                        "ON font_faces(familyName, subfamily, source);",
                    0,
                )
            }
        } else if ("hidden" !in columnsOf("font_faces")) {
            runCatching {
                driver.execute(null, "ALTER TABLE font_faces ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;", 0)
            }
        }
    }

    /** S32 JSON 索引一次性导入（id 延续），随后删除旧文件。 */
    private suspend fun importLegacyIndexIfNeeded(library: LibraryDb) {
        if (!legacyIndex.isFile) return
        val entries = runCatching {
            json.decodeFromString(ShelfIndex.serializer(), legacyIndex.readText()).books
        }.getOrDefault(emptyList())
        for (e in entries) {
            library.importBook(
                Book(
                    id = e.id,
                    title = e.title,
                    author = e.author,
                    filePath = e.filePath,
                    addedAt = e.addedAt,
                    coverPath = e.coverPath,
                    readTime = e.readTime,
                ),
            )
        }
        runCatching { legacyIndex.delete() }
    }

    override fun books(sort: ShelfSort): Flow<List<ShelfBook>> =
        requireDb().observeBooks(
            when (sort) {
                ShelfSort.Added -> BookSort.Added
                ShelfSort.Read -> BookSort.Read
                ShelfSort.Name -> BookSort.Name
            },
        ).map { list -> list.map { it.toShelf() } }

    override suspend fun allBooks(): List<ShelfBook> = requireDb().allBooks().map { it.toShelf() }

    suspend fun getEntry(id: Long): Book? = requireDb().getBook(id)

    /** 新增书目并返回条目（调用方先把文件拷入书库目录）。 */
    suspend fun addBook(title: String, author: String?, filePath: String, coverPath: String?): Book =
        requireDb().let { library ->
            val id = library.addBook(title, author, filePath, coverPath, System.currentTimeMillis())
            checkNotNull(library.getBook(id))
        }

    suspend fun updateCover(id: Long, coverPath: String) {
        val library = requireDb()
        library.getBook(id)?.let { library.updateBook(it.copy(coverPath = coverPath)) }
    }

    override suspend fun delete(id: Long) {
        val entry = requireDb().getBook(id) ?: return
        requireDb().removeBook(id)
        withContext(Dispatchers.IO) {
            runCatching { File(entry.filePath).delete() }
            entry.coverPath?.let { runCatching { File(it).delete() } }
            runCatching { File(progressDir, "$id.json").delete() }
        }
    }

    override suspend fun touchRead(id: Long) {
        requireDb().touchRead(id, System.currentTimeMillis())
    }

    suspend fun saveProgress(id: Long, locator: ReadingLocator) {
        // S34a：定位落 `reading_states` 表；locator 串为桌面恢复格式 `chapter:char`
        //（与 Android foliate JSON 同列不同生产者，各端只读写己方行，互不解析）。
        requireDb().saveReadingState(
            BookReadingState(
                bookId = id,
                chapter = locator.chapter,
                progress = 0.0,
                locator = "${locator.chapter}:${locator.char}",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun loadProgress(id: Long): ReadingLocator? {
        val state = requireDb().readingState(id) ?: return null
        val parts = state.locator?.split(':')
        val chapter = parts?.getOrNull(0)?.toIntOrNull() ?: state.chapter
        val char = parts?.getOrNull(1)?.toIntOrNull() ?: 0
        return ReadingLocator(chapter, char)
    }

    /** S32 `progress/{id}.json` 一次性导入，随后逐个删除旧文件。 */
    private suspend fun importLegacyProgressIfNeeded(library: LibraryDb) {
        if (!progressDir.isDirectory) return
        val files = progressDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        for (f in files) {
            val id = f.nameWithoutExtension.toLongOrNull() ?: continue
            val locator = runCatching {
                json.decodeFromString(ReadingLocator.serializer(), f.readText())
            }.getOrNull() ?: continue
            // 只有库中仍有该书才导入（删书残留的进度不复活）。
            if (library.getBook(id) == null) continue
            library.saveReadingState(
                BookReadingState(
                    bookId = id,
                    chapter = locator.chapter,
                    progress = 0.0,
                    locator = "${locator.chapter}:${locator.char}",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            runCatching { f.delete() }
        }
    }

    // ---- 轻量偏好（书架排序/视图；对齐 Android SharedPreferences 语义） ----

    fun loadPref(key: String, default: String): String = runCatching {
        if (!prefsFile.isFile) return default
        val props = java.util.Properties().also { p ->
            prefsFile.inputStream().use { p.load(it) }
        }
        props.getProperty(key)?.takeIf { it.isNotEmpty() } ?: default
    }.getOrDefault(default)

    fun savePref(key: String, value: String) {
        runCatching {
            val props = java.util.Properties()
            if (prefsFile.isFile) prefsFile.inputStream().use { props.load(it) }
            props.setProperty(key, value)
            prefsFile.parentFile.mkdirs()
            prefsFile.outputStream().use { props.store(it, null) }
        }
    }

    // ---- 自定义主题预设（设置面板 S29 的保存/删除，桌面持久化） ----

    suspend fun loadThemes(): List<ThemePreset> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(progressDir.parentFile, "settings/custom_themes.json")
            if (!f.isFile) return@runCatching emptyList()
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ThemePresetEntry.serializer()),
                f.readText(),
            ).map { it.toPreset() }
        }.getOrDefault(emptyList())
    }

    suspend fun saveTheme(preset: ThemePreset) {
        withContext(Dispatchers.IO) {
            runCatching {
                val cur = loadThemes().filterNot { it.bg == preset.bg && it.fg == preset.fg } + preset
                writeThemes(cur)
            }
        }
    }

    suspend fun deleteTheme(preset: ThemePreset) {
        withContext(Dispatchers.IO) {
            runCatching { writeThemes(loadThemes().filterNot { it.bg == preset.bg && it.fg == preset.fg }) }
        }
    }

    private fun writeThemes(list: List<ThemePreset>) {
        val f = File(progressDir.parentFile, "settings/custom_themes.json").also { it.parentFile.mkdirs() }
        atomicWrite(
            f,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ThemePresetEntry.serializer()),
                list.map { ThemePresetEntry(it.label, it.bg, it.fg) },
            ),
        )
    }

    // ---- 内部 ----

    private fun atomicWrite(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun Book.toShelf(): ShelfBook = ShelfBook(
        id = id,
        title = title,
        author = author,
        filePath = filePath,
        addedAt = addedAt,
        coverRef = coverPath,
        readTime = readTime,
    )
}
