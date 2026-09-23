package orilumn.reader.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import orilumn.reader.data.book.Book
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.font.FontFace
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.db.OrilumnDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 书架排序（对齐旧 Room `BookRepository.Sort` 的三种书目流语义）。 */
enum class BookSort { Added, Read, Name }

/**
 * S33 跨平台数据层门面：`OrilumnDb`（SQLDelight 生成）的类型安全封装。
 * S34a 接管阅读进度，S34b 接管字体池；Room 已整体移除。
 *
 * - 模型统一用 common 纯模型（[Book]…），屏蔽生成类（`Books`…）；
 * - 查询走 IO 线程；书目流经 `asFlow().mapToList()` 随库变化推送（替代 Room `observe*`）；
 * - 驱动由各壳提供（Android `AndroidSqliteDriver` / 桌面 `JdbcSqliteDriver`），本类无平台绑定。
 */
class LibraryDb(db: OrilumnDb) {
    private val books = db.booksQueries
    private val progress = db.readingStatesQueries
    private val fonts = db.fontFacesQueries

    fun observeBooks(sort: BookSort = BookSort.Added): Flow<List<Book>> {
        val flow = when (sort) {
            BookSort.Added -> books.observeBooks().asFlow()
            BookSort.Read -> books.observeBooksByReadTime().asFlow()
            BookSort.Name -> books.observeBooksByName().asFlow()
        }
        return flow.mapToList(Dispatchers.IO).map { list -> list.map { it.toBook() } }
    }

    suspend fun allBooks(): List<Book> = withContext(Dispatchers.IO) {
        books.allBooks().executeAsList().map { it.toBook() }
    }

    suspend fun getBook(id: Long): Book? = withContext(Dispatchers.IO) {
        books.bookById(id).executeAsOneOrNull()?.toBook()
    }

    /** 新增书目，返回新行 id。 */
    suspend fun addBook(title: String, author: String?, filePath: String, coverPath: String? = null, addedAt: Long): Long =
        withContext(Dispatchers.IO) {
            books.insertBook(title, author, filePath, addedAt)
            books.lastInsertId().executeAsOneOrNull()?.max ?: error("insertBook 未返回新行 id")
        }

    /** Room/SQLDelight 导入用：显式 id（进度按 bookId 关联，id 必须延续）。 */
    suspend fun importBook(book: Book) = withContext(Dispatchers.IO) {
        books.importBook(
            id = book.id,
            title = book.title,
            author = book.author,
            filePath = book.filePath,
            addedAt = book.addedAt,
            coverPath = book.coverPath,
            readTime = book.readTime,
        )
    }

    /** 整行 upsert（封面回填等场景；语义对齐 Room `@Upsert`）。 */
    suspend fun updateBook(book: Book) = withContext(Dispatchers.IO) {
        books.upsertBook(
            id = book.id,
            title = book.title,
            author = book.author,
            filePath = book.filePath,
            addedAt = book.addedAt,
            coverPath = book.coverPath,
            readTime = book.readTime,
        )
    }

    suspend fun touchRead(id: Long, time: Long) = withContext(Dispatchers.IO) {
        books.updateReadTime(time, id)
    }

    suspend fun removeBook(id: Long) = withContext(Dispatchers.IO) {
        books.deleteBook(id)
    }

    // ---- 阅读进度（S34a；schema v3） ----

    suspend fun saveReadingState(state: BookReadingState) = withContext(Dispatchers.IO) {
        progress.saveReadingState(
            bookId = state.bookId,
            chapter = state.chapter.toLong(),
            target = state.target,
            progress = state.progress,
            locator = state.locator,
            updatedAt = state.updatedAt,
        )
    }

    suspend fun readingState(bookId: Long): BookReadingState? = withContext(Dispatchers.IO) {
        progress.readingState(bookId).executeAsOneOrNull()?.toState()
    }

    suspend fun clearReadingState(bookId: Long) = withContext(Dispatchers.IO) {
        progress.clearReadingState(bookId)
    }

    // ---- 字体池（S34b；schema v5，F 系列加 hidden） ----

    suspend fun allFonts(): List<FontFace> = withContext(Dispatchers.IO) {
        fonts.allFonts().executeAsList().map { it.toFace() }
    }

    suspend fun fontById(id: Long): FontFace? = withContext(Dispatchers.IO) {
        fontByIdBlocking(id)
    }

    /** 同步版：供 WebView 同步字体加载线程直调（对齐旧 Room `byIdBlocking`）。 */
    fun fontByIdBlocking(id: Long): FontFace? =
        fonts.fontById(id).executeAsOneOrNull()?.toFace()

    /**
     * 批量 upsert（导入去重靠 (familyName, subfamily, source) 唯一索引：
     * 同风格重导即整行替换，对齐旧 Room `@Insert(REPLACE)`）。
     */
    suspend fun upsertFonts(faces: List<FontFace>) = withContext(Dispatchers.IO) {
        faces.forEach {
            fonts.upsertFont(
                familyName = it.familyName,
                displayName = it.displayName,
                subfamily = it.subfamily,
                source = it.source,
                path = it.path,
                lang = it.lang,
                hidden = if (it.hidden) 1L else 0L,
            )
        }
    }

    /** Room/SQLDelight 导入用：显式 id（`/fonts/{id}` 虚域引用，id 尽量延续）。 */
    suspend fun importFont(face: FontFace) = withContext(Dispatchers.IO) {
        fonts.importFont(
            id = face.id,
            familyName = face.familyName,
            displayName = face.displayName,
            subfamily = face.subfamily,
            source = face.source,
            path = face.path,
            lang = face.lang,
            hidden = if (face.hidden) 1L else 0L,
        )
    }

    suspend fun deleteFont(id: Long) = withContext(Dispatchers.IO) {
        deleteFontBlocking(id)
    }

    /** 同步版：字体文件丢失时清理残行（对齐旧 Room `delete` blocking）。 */
    fun deleteFontBlocking(id: Long) {
        fonts.deleteFont(id)
    }

    suspend fun deleteFontsByFamily(familyName: String) = withContext(Dispatchers.IO) {
        fonts.deleteByFamily(familyName)
    }

    /** F 系列：用户隐藏/取消隐藏（系统/导入通用；隐藏后不进选择器候选）。 */
    suspend fun setFontHidden(id: Long, hidden: Boolean) = withContext(Dispatchers.IO) {
        fonts.setHidden(if (hidden) 1L else 0L, id)
    }

    /**
     * F 系列：系统字形落行（INSERT OR IGNORE 保 hidden + UPDATE 刷新 displayName，
     * 既有行本地化名也升级；
     * 调用方喂平台枚举（engine-skia `systemFontFaces`，族 + 字重名）；
     * [localizedNames] 为 macOS CoreText 本地化族名（F5 方案A，仅含 CJK 结果，
     * 键 = 原英文族名），非 mac 平台留空即写回族名，展示层回退族名本身）。
     */
    suspend fun syncSystemFonts(
        faces: List<SystemFontFace>,
        localizedNames: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val rows = faces.map { it.family.trim() to it.subfamily.trim() }
            .filter { (f, _) -> f.isNotEmpty() }
            .distinct()
        rows.forEach { (f, s) ->
            // 族级隐藏继承：族已有隐藏系统行时新字重行同 hidden（隐藏族不复活）。
            val familyHidden = fonts.anyHiddenSystemRow(f).executeAsOne() != 0L
            val display = localizedNames[f] ?: f
            fonts.syncSystemFont(
                familyName = f,
                displayName = display,
                subfamily = s,
                hidden = if (familyHidden) 1L else 0L,
            )
            // 既有行（INSERT OR IGNORE 未命中）也要刷新本地化名，不动 hidden/id。
            fonts.updateSystemDisplayName(
                displayName = display,
                familyName = f,
                subfamily = s,
            )
        }
        // 旧世代 subfamily='' 残留行：该族已枚举出字重行即过期，清掉——否则面板
        // 合并行首成员仍是残留行（displayName=族名），本地化名显示不出来。
        val familiesWithWeights = rows.filter { (_, s) -> s.isNotEmpty() }.map { (f, _) -> f }.toSet()
        if (familiesWithWeights.isNotEmpty()) {
            fonts.deleteStaleSystemRows(familiesWithWeights.toList())
        }
    }

    private fun orilumn.reader.db.Books.toBook(): Book = Book(
        id = id,
        title = title,
        author = author,
        filePath = filePath,
        addedAt = addedAt,
        coverPath = coverPath,
        readTime = readTime,
    )

    private fun orilumn.reader.db.Reading_states.toState(): BookReadingState = BookReadingState(
        bookId = bookId,
        chapter = chapter.toInt(),
        target = target,
        progress = progress,
        locator = locator,
        updatedAt = updatedAt,
    )

    private fun orilumn.reader.db.Font_faces.toFace(): FontFace = FontFace(
        id = id,
        familyName = familyName,
        displayName = displayName,
        subfamily = subfamily,
        source = source,
        path = path,
        lang = lang,
        hidden = hidden != 0L,
    )
}
