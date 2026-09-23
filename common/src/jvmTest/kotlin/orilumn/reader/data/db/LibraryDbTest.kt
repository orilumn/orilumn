package orilumn.reader.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.db.OrilumnDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S33 LibraryDb 单测：真实 SQLite（内存）上的 CRUD + 书目流 + 1→2 迁移。
 * S34a 追加进度 CRUD；S34b 追加字体池 CRUD + 去重 + 3→4 迁移。
 */
class LibraryDbTest {

    private fun freshDb(): LibraryDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        return LibraryDb(OrilumnDb(driver))
    }

    @Test
    fun crudRoundTrip() = runBlocking {
        val db = freshDb()
        val id = db.addBook("T", "A", "/b.epub", addedAt = 100L)
        assertTrue(id > 0)

        val got = db.getBook(id)!!
        assertEquals("T", got.title)
        assertEquals("A", got.author)
        assertEquals("/b.epub", got.filePath)
        assertEquals(100L, got.addedAt)
        assertNull(got.coverPath)
        assertNull(got.readTime)

        db.updateBook(got.copy(coverPath = "/c.png"))
        assertEquals("/c.png", db.getBook(id)!!.coverPath)

        db.touchRead(id, 200L)
        assertEquals(200L, db.getBook(id)!!.readTime)

        db.removeBook(id)
        assertNull(db.getBook(id))
    }

    @Test
    fun observeSortsMirrorRoomSemantics() = runBlocking {
        val db = freshDb()
        // Added: addedAt 倒序；Read：readTime 倒序（null 沉底）；Name：标题大小写不敏感升序。
        val idB = db.addBook("banana", null, "/b", addedAt = 2L)
        val idA = db.addBook("Apple", null, "/a", addedAt = 1L)
        db.touchRead(idB, 50L)

        assertEquals(listOf(idB, idA), db.observeBooks(BookSort.Added).first().map { it.id })
        assertEquals(listOf(idB, idA), db.observeBooks(BookSort.Read).first().map { it.id })
        assertEquals(listOf(idA, idB), db.observeBooks(BookSort.Name).first().map { it.id })

        // 流随写更新。
        db.addBook("Cherry", null, "/c", addedAt = 3L)
        assertEquals(3, db.observeBooks(BookSort.Added).first().size)
    }

    @Test
    fun importPreservesIds() = runBlocking {
        val db = freshDb()
        db.importBook(orilumn.reader.data.book.Book(id = 42, title = "T", filePath = "/x", addedAt = 7L))
        assertEquals("T", db.getBook(42)?.title)
    }

    @Test
    fun migrate1to2AddsShelfColumns() {
        // 手工搭 v1 库（无 coverPath/readTime，用户版本 1），跑真实 Schema.migrate(1, 2)。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, author TEXT, filePath TEXT NOT NULL, addedAt INTEGER NOT NULL);",
            0,
        )
        driver.execute(null, "INSERT INTO books(title, filePath, addedAt) VALUES ('T', '/b', 9);", 0)
        driver.execute(null, "PRAGMA user_version = 1;", 0)

        OrilumnDb.Schema.migrate(driver, 1, 2)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val books = db.allBooks()
            assertEquals(1, books.size)
            assertEquals("T", books.single().title)
            assertNull(books.single().coverPath)
            assertNull(books.single().readTime)
            // 迁移后新列可写。
            db.updateBook(books.single().copy(coverPath = "/c.png", readTime = 5L))
            assertEquals("/c.png", db.getBook(books.single().id)?.coverPath)
        }
    }

    @Test
    fun coroutinesFlowExtensionLoads() = runBlocking {
        // asFlow/mapToList 链路本身可用（observeBooks 已间接覆盖，这里显式验证生成查询可转流）。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        val db = OrilumnDb(driver)
        val rows = db.booksQueries.allBooks().asFlow().mapToList(Dispatchers.IO).first()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun fontsCrudRoundTrip() = runBlocking {
        val db = freshDb()
        assertTrue(db.allFonts().isEmpty())

        db.upsertFonts(
            listOf(
                orilumn.reader.data.font.FontFace(
                    familyName = "F",
                    displayName = "F-Regular",
                    subfamily = "Regular",
                    path = "/fonts/f.ttf",
                    lang = "cjk",
                ),
            ),
        )
        val one = db.allFonts()
        assertEquals(1, one.size)
        assertEquals("F", one.single().familyName)
        assertEquals(orilumn.reader.data.font.FontFace.SOURCE_IMPORTED, one.single().source)

        val id = one.single().id
        assertTrue(id > 0)
        assertEquals("/fonts/f.ttf", db.fontById(id)?.path)

        db.deleteFont(id)
        assertNull(db.fontById(id))
        assertTrue(db.allFonts().isEmpty())
    }

    @Test
    fun fontsUpsertDedupsByFamilySubfamily() = runBlocking {
        val db = freshDb()
        val face = orilumn.reader.data.font.FontFace(
            familyName = "F",
            displayName = "F-Regular",
            subfamily = "Regular",
            path = "/fonts/a.ttf",
            lang = "cjk",
        )
        db.upsertFonts(listOf(face))
        // 同 (family, subfamily, source) 重导覆盖：仍一行，且 path 更新。
        db.upsertFonts(listOf(face.copy(displayName = "F-Regular", path = "/fonts/b.ttf")))
        val rows = db.allFonts()
        assertEquals(1, rows.size)
        assertEquals("/fonts/b.ttf", rows.single().path)
        // 不同 subfamily 共存。
        db.upsertFonts(listOf(face.copy(displayName = "F-Bold", subfamily = "Bold", path = "/fonts/bold.ttf")))
        assertEquals(2, db.allFonts().size)
        // 按家族删除。
        db.deleteFontsByFamily("F")
        assertTrue(db.allFonts().isEmpty())
    }

    @Test
    fun fontsImportPreservesIds() = runBlocking {
        val db = freshDb()
        db.importFont(
            orilumn.reader.data.font.FontFace(
                id = 42,
                familyName = "F",
                displayName = "F-Regular",
                subfamily = "Regular",
                path = "/fonts/f.ttf",
                lang = "cjk",
            ),
        )
        assertEquals("/fonts/f.ttf", db.fontById(42)?.path)
    }

    @Test
    fun progressCrudRoundTrip() = runBlocking {
        val db = freshDb()
        val id = db.addBook("T", null, "/b.epub", addedAt = 1L)
        assertNull(db.readingState(id))
        db.saveReadingState(
            orilumn.reader.data.book.BookReadingState(
                bookId = id,
                chapter = 3,
                target = "c3",
                progress = 0.5,
                locator = "{}",
                updatedAt = 9L,
            ),
        )
        assertEquals(3, db.readingState(id)?.chapter)
        db.clearReadingState(id)
        assertNull(db.readingState(id))
    }

    @Test
    fun fontsHiddenRoundTrip() = runBlocking {
        val db = freshDb()
        db.upsertFonts(
            listOf(
                orilumn.reader.data.font.FontFace(
                    familyName = "Sys",
                    displayName = "Sys",
                    source = orilumn.reader.data.font.FontFace.SOURCE_SYSTEM,
                    path = null,
                    lang = "",
                ),
            ),
        )
        val one = db.allFonts().single()
        assertEquals(orilumn.reader.data.font.FontFace.SOURCE_SYSTEM, one.source)
        assertEquals(false, one.hidden)

        db.setFontHidden(one.id, true)
        assertEquals(true, db.fontById(one.id)?.hidden)
        db.setFontHidden(one.id, false)
        assertEquals(false, db.fontById(one.id)?.hidden)
    }

    @Test
    fun migrate4to5AddsHiddenDefaultVisible() {
        // 手工搭 v4 库（font_faces 无 hidden，用户版本 4），跑真实 Schema.migrate(4, 5)。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, author TEXT, filePath TEXT NOT NULL, addedAt INTEGER NOT NULL, coverPath TEXT, readTime INTEGER);",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE reading_states(bookId INTEGER PRIMARY KEY, chapter INTEGER NOT NULL, target TEXT, progress REAL NOT NULL, locator TEXT, updatedAt INTEGER NOT NULL);",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE font_faces(id INTEGER PRIMARY KEY AUTOINCREMENT, familyName TEXT NOT NULL, displayName TEXT NOT NULL, subfamily TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, path TEXT, lang TEXT NOT NULL);",
            0,
        )
        driver.execute(null, "INSERT INTO font_faces(familyName, displayName, source, lang) VALUES ('F', 'F', 'imported', 'cjk');", 0)
        driver.execute(null, "PRAGMA user_version = 4;", 0)

        OrilumnDb.Schema.migrate(driver, 4, 5)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            // 旧行默认全可见，且隐藏口径可用。
            val one = db.allFonts().single()
            assertEquals(false, one.hidden)
            db.setFontHidden(one.id, true)
            assertEquals(true, db.fontById(one.id)?.hidden)
        }
    }

    @Test
    fun migrate3to4AddsFontTable() {
        // 手工搭 v3 库（books + reading_states，无 font_faces，用户版本 3），跑真实 Schema.migrate(3, 4)。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, author TEXT, filePath TEXT NOT NULL, addedAt INTEGER NOT NULL, coverPath TEXT, readTime INTEGER);",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE reading_states(bookId INTEGER PRIMARY KEY, chapter INTEGER NOT NULL, target TEXT, progress REAL NOT NULL, locator TEXT, updatedAt INTEGER NOT NULL);",
            0,
        )
        driver.execute(null, "INSERT INTO books(title, filePath, addedAt) VALUES ('T', '/b', 9);", 0)
        driver.execute(null, "PRAGMA user_version = 3;", 0)

        OrilumnDb.Schema.migrate(driver, 3, 4)

        // v4 的 font_faces 无 hidden 列（新 query 带 hidden，只能验表结构；
        // 写能力由 migrate4to5 测试覆盖）。
        val tables = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'font_faces';",
            { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0) ?: "") })
            },
            0,
        ) { }.value
        assertEquals(listOf("font_faces"), tables)
        val cols = driver.executeQuery(
            null,
            "PRAGMA table_info(font_faces);",
            { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(1) ?: "") })
            },
            0,
        ) { }.value
        assertTrue(cols.containsAll(listOf("familyName", "source", "lang")))
        assertTrue(!cols.contains("hidden"))
    }
}
