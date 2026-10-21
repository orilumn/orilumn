package orilumn.reader.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
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
    fun annotationsCrudRoundTrip() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        assertTrue(db.annotationsForChapter(book, 2).isEmpty())

        val first = db.addAnnotation(
            Annotation(
                bookId = book,
                chapter = 2,
                charStart = 5,
                charEnd = 9,
                kind = AnnotationKind.Highlight,
                color = 0x80FFCC00.toInt(),
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )
        val second = db.addAnnotation(
            Annotation(
                bookId = book,
                chapter = 2,
                charStart = 7,
                charEnd = 8,
                kind = AnnotationKind.Underline,
                color = 0xFF3366CC.toInt(),
                lineStyle = AnnotationLineStyle.Wavy,
                createdAt = 11L,
                updatedAt = 11L,
            ),
        )
        assertTrue(first.id > 0)
        assertTrue(second.id > first.id)
        // 线型随记录落库（老数据缺列 = 实线，见 migration 7→8）。
        assertEquals(AnnotationLineStyle.Wavy, db.annotationAt(book, 2, 7)?.lineStyle)
        assertEquals(AnnotationLineStyle.Straight, db.annotationAt(book, 2, 8)?.lineStyle)
        assertEquals(listOf(first.id, second.id), db.annotationsForChapter(book, 2).map { it.id })

        // 命中包含该偏移的最后一条；charEnd 不含。
        assertEquals(second.id, db.annotationAt(book, 2, 7)?.id)
        assertEquals(first.id, db.annotationAt(book, 2, 8)?.id)
        assertNull(db.annotationAt(book, 2, 9))

        // v1 只改颜色；批注文本先按可写验证，v2 界面再接。
        db.updateAnnotation(
            first.copy(
                kind = AnnotationKind.Underline,
                color = 0xFF112233.toInt(),
                lineStyle = AnnotationLineStyle.Strike,
                note = "批注",
                updatedAt = 20L,
            ),
        )
        val updated = db.annotationsForChapter(book, 2).single { it.id == first.id }
        assertEquals(AnnotationKind.Underline, updated.kind)
        assertEquals(0xFF112233.toInt(), updated.color)
        assertEquals(AnnotationLineStyle.Strike, updated.lineStyle)
        assertEquals("批注", updated.note)
        assertEquals(20L, updated.updatedAt)

        db.deleteAnnotation(first.id)
        assertEquals(listOf(second.id), db.annotationsForChapter(book, 2).map { it.id })
        db.deleteAnnotationsForBook(book)
        assertTrue(db.annotationsForChapter(book, 2).isEmpty())
    }

    @Test
    fun removeBookAndDataClearsProgressAndAnnotations() = runBlocking {
        val db = freshDb()
        val kept = db.addBook("Keep", null, "/keep.epub", addedAt = 1L)
        val removed = db.addBook("Remove", null, "/remove.epub", addedAt = 2L)
        listOf(kept, removed).forEach { book ->
            db.saveReadingState(
                orilumn.reader.data.book.BookReadingState(
                    bookId = book,
                    chapter = 1,
                    target = "c1",
                    progress = 0.25,
                    locator = null,
                    updatedAt = 3L,
                ),
            )
            db.addAnnotation(
                Annotation(
                    bookId = book,
                    chapter = 1,
                    charStart = 0,
                    charEnd = 3,
                    kind = AnnotationKind.Highlight,
                    color = 0x80FFCC00.toInt(),
                    createdAt = 3L,
                    updatedAt = 3L,
                ),
            )
        }

        db.removeBookAndData(removed)

        assertNull(db.getBook(removed))
        assertNull(db.readingState(removed))
        assertTrue(db.annotationsForChapter(removed, 1).isEmpty())

        // 另一本书的数据不受影响。
        assertTrue(db.getBook(kept) != null)
        assertTrue(db.readingState(kept) != null)
        assertEquals(1, db.annotationsForChapter(kept, 1).size)
    }

    @Test
    fun migrate6to7AddsAnnotationTable() {
        // 用最新 schema 建库后删掉标注表、把版本退回 6，模拟真实的 6→7 升级
        // （一路迁到最新版本，顺带覆盖 7→8 的线型列）。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "DROP TABLE annotations;", 0)
        driver.execute(null, "PRAGMA user_version = 6;", 0)

        OrilumnDb.Schema.migrate(driver, 6, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            val row = db.addAnnotation(
                Annotation(
                    bookId = book,
                    chapter = 1,
                    charStart = 2,
                    charEnd = 6,
                    kind = AnnotationKind.Underline,
                    color = 0xFF000000.toInt(),
                    lineStyle = AnnotationLineStyle.Wavy,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            assertEquals(listOf(row), db.annotationsForChapter(book, 1))
        }
    }

    @Test
    fun migrate7to8AddsLineStyleColumn() {
        // 用最新 schema 建库后把线型列删掉、版本退回 7，模拟真实的 7→8 升级。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "ALTER TABLE annotations DROP COLUMN lineStyle;", 0)
        driver.execute(null, "PRAGMA user_version = 7;", 0)

        OrilumnDb.Schema.migrate(driver, 7, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            // 老记录没有线型列：插一条不带线型的，读回必须是实线。
            driver.execute(
                null,
                "INSERT INTO annotations(bookId, chapter, charStart, charEnd, kind, color, note, createdAt, updatedAt) " +
                    "VALUES ($book, 1, 0, 3, 'underline', NULL, NULL, 1, 1);",
                0,
            )
            assertEquals(AnnotationLineStyle.Straight, db.annotationsForChapter(book, 1).single().lineStyle)
            // 新记录能写线型。
            db.addAnnotation(
                Annotation(
                    bookId = book,
                    chapter = 1,
                    charStart = 5,
                    charEnd = 9,
                    kind = AnnotationKind.Underline,
                    color = 0xFF000000.toInt(),
                    lineStyle = AnnotationLineStyle.Dashed,
                    createdAt = 2L,
                    updatedAt = 2L,
                ),
            )
            assertEquals(
                AnnotationLineStyle.Dashed,
                db.annotationsForChapter(book, 1).first { it.charStart == 5 }.lineStyle,
            )
        }
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

    @Test
    fun syncSystemFontsReconcilesRenamedWeights() = runBlocking {
        // 字重改名（英文 Regular → 中文 常规体）：旧英文行不再被命中即删，
        // 不留一行双语；族隐藏态由新行继承。
        val db = freshDb()
        val sys = { sub: String ->
            orilumn.reader.data.font.SystemFontFace("F", sub)
        }
        db.syncSystemFonts(listOf(sys("Regular"), sys("Bold")))
        assertEquals(
            listOf("Bold", "Regular"),
            db.allFonts().map { it.subfamily }.sorted(),
        )
        db.setFontHidden(db.allFonts().single { it.subfamily == "Bold" }.id, true)
        db.syncSystemFonts(listOf(sys("常规体"), sys("粗体")))
        val subs = db.allFonts().map { it.subfamily }.sorted()
        assertEquals(listOf("常规体", "粗体"), subs)
        // 旧英文行已清；隐藏按族继承到新行。
        assertTrue(db.allFonts().all { it.hidden })
    }

    @Test
    fun syncSystemFontsEmptyKeepsLibrary() = runBlocking {
        // 空枚举（平台失败）不清库。
        val db = freshDb()
        db.syncSystemFonts(listOf(orilumn.reader.data.font.SystemFontFace("F", "Regular")))
        db.syncSystemFonts(emptyList())
        assertEquals(listOf("Regular"), db.allFonts().map { it.subfamily })
    }

    @Test
    fun syncSystemFontsPrunesFamiliesGoneFromPlatform() = runBlocking {
        // 字体集变化（卸载 / fontconfig 过滤）后，本次枚举里不存在的族必须清掉——
        // 面板列全表，不清即把「早就不存在的字体」继续列给用户。
        // Linux 实锤：系统实际 178 族，库里却积着 1322 族 / 2697 行。
        val db = freshDb()
        db.syncSystemFonts(
            listOf(
                orilumn.reader.data.font.SystemFontFace("Noto Sans Arabic", "Regular"),
                orilumn.reader.data.font.SystemFontFace("Noto Serif CJK SC", "Regular"),
            ),
        )
        assertEquals(2, db.allFonts().size)

        // 新枚举只剩中文那一族（阿拉伯语族被过滤掉了）。
        db.syncSystemFonts(listOf(orilumn.reader.data.font.SystemFontFace("Noto Serif CJK SC", "Regular")))

        val families = db.allFonts().map { it.familyName }.distinct()
        assertEquals("消失的族应被清掉", listOf("Noto Serif CJK SC"), families)
    }

    @Test
    fun syncSystemFontsKeepsImportedRowsWhenPruning() = runBlocking {
        // 护栏一：导入字体没有平台枚举来源，删陈旧系统行时**绝不能**牵连它们。
        val db = freshDb()
        db.importFont(
            orilumn.reader.data.font.FontFace(
                id = 42,
                familyName = "MyImported",
                displayName = "MyImported",
                subfamily = "Regular",
                path = "/fonts/my.ttf",
                lang = "cjk",
            ),
        )
        db.syncSystemFonts(listOf(orilumn.reader.data.font.SystemFontFace("SysA", "Regular")))
        // 系统枚举变了（SysA 不再出现），导入行必须原样还在。
        db.syncSystemFonts(listOf(orilumn.reader.data.font.SystemFontFace("SysB", "Regular")))

        val imported = db.allFonts().filter { it.source != orilumn.reader.data.font.FontFace.SOURCE_SYSTEM }
        assertEquals("导入字体不得被陈旧清理牵连", 1, imported.size)
        assertEquals("MyImported", imported.single().familyName)
        // 被过滤掉的系统族照常清掉（这条是本测试的另一面）。
        assertEquals(
            listOf("SysB"),
            db.allFonts().filter { it.source == orilumn.reader.data.font.FontFace.SOURCE_SYSTEM }.map { it.familyName },
        )
    }
}
