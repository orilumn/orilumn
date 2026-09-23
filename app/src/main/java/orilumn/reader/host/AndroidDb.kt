package orilumn.reader.host

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import orilumn.reader.data.book.Book
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.book.BookRepository
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontFace
import orilumn.reader.db.OrilumnDb
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S33 Android 数据库宿主：SQLDelight 驱动单例 + Room 一次性导入。
 * S34b：书目/进度/字体三表均已移交，Room 整体移除；旧库 `orilumn.db` 仅导入期直读。
 *
 * - 新库文件 `orilumn.db`（SQLDelight schema v4），替代 Room `orilumn.db` 的 books /
 *   reading_states / font_faces 三表；两文件版本各自独立（`PRAGMA user_version` 按文件隔离），互不干扰。
 * - [importBooksFromRoomIfNeeded]：首启用 framework SQLite 直读旧库 books 表（不经过
 *   Room——Room 8→9 迁移会 DROP 该表，直读必须先行），按 id 原样导入
 *   （阅读进度按 bookId 关联，id 延续是 S34a 进度导入的前提），成功后打标不再重复；
 *   列缺失（远古版本库）时 coverPath/readTime 回退 null。
 * - [importFontsFromRoomIfNeeded]：同理直读旧库 font_faces 表，按 id 原样导入
 *   （`/fonts/{id}` 虚域引用，id 尽量延续；缺 subfamily 列回退 ""）。
 */
object AndroidDb {
    private const val DB_FILE = "orilumn.db"
    private const val PREFS = "sqldelight"
    private const val KEY_BOOKS_IMPORTED = "books_imported_v2"
    private const val KEY_PROGRESS_IMPORTED = "progress_imported_v3"
    private const val KEY_FONTS_IMPORTED = "fonts_imported_v4"

    @Volatile
    private var library: LibraryDb? = null

    fun library(context: Context): LibraryDb = library ?: synchronized(this) {
        library ?: LibraryDb(
            OrilumnDb(
                AndroidSqliteDriver(
                    schema = OrilumnDb.Schema,
                    context = context.applicationContext,
                    name = DB_FILE,
                ),
            ),
        ).also { library = it }
    }

    fun repository(context: Context): BookRepository =
        BookRepository(library(context))

    suspend fun importBooksFromRoomIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BOOKS_IMPORTED, false)) return
        val books = withContext(Dispatchers.IO) { readRoomBooks(context) }
        val db = library(context)
        books.forEach { db.importBook(it) }
        prefs.edit().putBoolean(KEY_BOOKS_IMPORTED, true).apply()
    }

    suspend fun importProgressFromRoomIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROGRESS_IMPORTED, false)) return
        val states = withContext(Dispatchers.IO) { readRoomProgress(context) }
        val db = library(context)
        states.forEach { db.saveReadingState(it) }
        prefs.edit().putBoolean(KEY_PROGRESS_IMPORTED, true).apply()
    }

    suspend fun importFontsFromRoomIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FONTS_IMPORTED, false)) return
        val faces = withContext(Dispatchers.IO) { readRoomFonts(context) }
        val db = library(context)
        faces.forEach { db.importFont(it) }
        prefs.edit().putBoolean(KEY_FONTS_IMPORTED, true).apply()
    }

    /** Framework 直读旧 Room 库的 books 表（v1–v8 皆容：缺列回退 null）。 */
    private fun readRoomBooks(context: Context): List<Book> {
        val file = context.getDatabasePath(ROOM_DB_FILE)
        if (!file.exists()) return emptyList()
        val out = ArrayList<Book>()
        runCatching {
            val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                sqlite.rawQuery("SELECT * FROM books", null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow("id")
                    val iTitle = c.getColumnIndexOrThrow("title")
                    val iAuthor = c.getColumnIndex("author")
                    val iPath = c.getColumnIndexOrThrow("filePath")
                    val iAdded = c.getColumnIndex("addedAt")
                    val iCover = c.getColumnIndex("coverPath")
                    val iRead = c.getColumnIndex("readTime")
                    while (c.moveToNext()) {
                        out.add(
                            Book(
                                id = c.getLong(iId),
                                title = c.getString(iTitle) ?: "",
                                author = if (iAuthor >= 0 && !c.isNull(iAuthor)) c.getString(iAuthor) else null,
                                filePath = c.getString(iPath) ?: "",
                                addedAt = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                                coverPath = if (iCover >= 0 && !c.isNull(iCover)) c.getString(iCover) else null,
                                readTime = if (iRead >= 0 && !c.isNull(iRead)) c.getLong(iRead) else null,
                            ),
                        )
                    }
                }
            } finally {
                sqlite.close()
            }
        }
        return out
    }

    /** Framework 直读旧 Room 库的 reading_states 表（v2– 皆容：locator 缺列回退 null）。 */
    private fun readRoomProgress(context: Context): List<BookReadingState> {
        val file = context.getDatabasePath(ROOM_DB_FILE)
        if (!file.exists()) return emptyList()
        val out = ArrayList<BookReadingState>()
        runCatching {
            val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                sqlite.rawQuery("SELECT * FROM reading_states", null)?.use { c ->
                    val iBook = c.getColumnIndexOrThrow("bookId")
                    val iCh = c.getColumnIndex("chapter")
                    val iTarget = c.getColumnIndex("target")
                    val iProg = c.getColumnIndex("progress")
                    val iLoc = c.getColumnIndex("locator")
                    val iUpd = c.getColumnIndex("updatedAt")
                    while (c.moveToNext()) {
                        out.add(
                            BookReadingState(
                                bookId = c.getLong(iBook),
                                chapter = if (iCh >= 0) c.getInt(iCh) else 0,
                                target = if (iTarget >= 0 && !c.isNull(iTarget)) c.getString(iTarget) else null,
                                progress = if (iProg >= 0) c.getDouble(iProg) else 0.0,
                                locator = if (iLoc >= 0 && !c.isNull(iLoc)) c.getString(iLoc) else null,
                                updatedAt = if (iUpd >= 0) c.getLong(iUpd) else 0L,
                            ),
                        )
                    }
                }
            } finally {
                sqlite.close()
            }
        }
        return out
    }

    /** Framework 直读旧 Room 库的 font_faces 表（v4–v8 皆容：subfamily 缺列回退 ""）。 */
    private fun readRoomFonts(context: Context): List<FontFace> {
        val file = context.getDatabasePath(ROOM_DB_FILE)
        if (!file.exists()) return emptyList()
        val out = ArrayList<FontFace>()
        runCatching {
            val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                sqlite.rawQuery("SELECT * FROM font_faces", null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow("id")
                    val iFamily = c.getColumnIndexOrThrow("familyName")
                    val iDisp = c.getColumnIndex("displayName")
                    val iSub = c.getColumnIndex("subfamily")
                    val iSource = c.getColumnIndex("source")
                    val iPath = c.getColumnIndex("path")
                    val iLang = c.getColumnIndex("lang")
                    while (c.moveToNext()) {
                        out.add(
                            FontFace(
                                id = c.getLong(iId),
                                familyName = c.getString(iFamily) ?: "",
                                displayName = if (iDisp >= 0 && !c.isNull(iDisp)) c.getString(iDisp) else "",
                                subfamily = if (iSub >= 0 && !c.isNull(iSub)) c.getString(iSub) else "",
                                source = if (iSource >= 0 && !c.isNull(iSource)) c.getString(iSource) else FontFace.SOURCE_IMPORTED,
                                path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) else null,
                                lang = if (iLang >= 0 && !c.isNull(iLang)) c.getString(iLang) else "",
                            ),
                        )
                    }
                }
            } finally {
                sqlite.close()
            }
        }
        return out
    }

    private const val ROOM_DB_FILE = "orilumn.db"
}
