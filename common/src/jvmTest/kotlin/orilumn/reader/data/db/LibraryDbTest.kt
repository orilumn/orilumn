package orilumn.reader.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.bookmark.Bookmark
import orilumn.reader.data.revision.TextRevision
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
                quote = "摘录一",
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
                quote = "摘录二",
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
        // 原文摘录随记录落库（清单显示用，见 migration 8→9）。
        assertEquals("摘录一", db.annotationAt(book, 2, 5)?.quote)
        assertEquals(listOf(first.id, second.id), db.annotationsForBook(book).map { it.id })

        // 命中包含该偏移的最后一条；charEnd 不含。
        assertEquals(second.id, db.annotationAt(book, 2, 7)?.id)
        assertEquals(first.id, db.annotationAt(book, 2, 8)?.id)
        assertNull(db.annotationAt(book, 2, 9))

        // 编辑已有标注：范围（手柄拖过）、样式、批注文本、摘录一起写回。
        db.updateAnnotation(
            first.copy(
                charStart = 4,
                charEnd = 10,
                kind = AnnotationKind.Underline,
                color = 0xFF112233.toInt(),
                lineStyle = AnnotationLineStyle.Dashed,
                note = "批注",
                quote = "摘录一改",
                updatedAt = 20L,
            ),
        )
        val updated = db.annotationsForChapter(book, 2).single { it.id == first.id }
        assertEquals(4, updated.charStart)
        assertEquals(10, updated.charEnd)
        assertEquals(AnnotationKind.Underline, updated.kind)
        assertEquals(0xFF112233.toInt(), updated.color)
        assertEquals(AnnotationLineStyle.Dashed, updated.lineStyle)
        assertEquals("批注", updated.note)
        assertEquals("摘录一改", updated.quote)
        assertEquals(20L, updated.updatedAt)

        db.deleteAnnotation(first.id)
        assertEquals(listOf(second.id), db.annotationsForChapter(book, 2).map { it.id })
        db.deleteAnnotationsForBook(book)
        assertTrue(db.annotationsForChapter(book, 2).isEmpty())
    }

    @Test
    fun annotationsForBookOrdersByChapterThenStart() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        suspend fun add(chapter: Int, start: Int) = db.addAnnotation(
            Annotation(
                bookId = book,
                chapter = chapter,
                charStart = start,
                charEnd = start + 2,
                kind = AnnotationKind.Highlight,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )        // 故意乱序插入：书级查询必须按章序 + 章内起点排（与 annotationsForChapter 同序）。
        val c2b = add(2, 30)
        val c0 = add(0, 10)
        val c2a = add(2, 5)
        assertEquals(listOf(c0.id, c2a.id, c2b.id), db.annotationsForBook(book).map { it.id })
        assertTrue(db.annotationsForBook(book + 1).isEmpty())
    }

    @Test
    fun bookmarksCrudRoundTripAndDedup() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        assertTrue(db.bookmarksForBook(book).isEmpty())

        val c2 = db.addBookmark(
            Bookmark(bookId = book, chapter = 2, charStart = 30, charEnd = 60, quote = "第二页", createdAt = 5L),
        )
        val c0 = db.addBookmark(
            Bookmark(bookId = book, chapter = 0, charStart = 10, charEnd = 10, quote = null, createdAt = 6L),
        )
        val c2a = db.addBookmark(
            Bookmark(bookId = book, chapter = 2, charStart = 5, charEnd = 20, quote = "第一页", createdAt = 7L),
        )
        assertTrue(c2.id > 0)
        // 清单按章序 + 章内页首排（故意乱序插入）。
        assertEquals(listOf(c0.id, c2a.id, c2.id), db.bookmarksForBook(book).map { it.id })
        assertTrue(db.bookmarksForBook(book + 1).isEmpty())

        // 同一页再点一次「添加当前页」：返回已有那条，不长出重复行。
        val again = db.addBookmark(
            Bookmark(bookId = book, chapter = 2, charStart = 5, charEnd = 99, quote = "又一次", createdAt = 8L),
        )
        assertEquals(c2a, again)
        assertEquals(3, db.bookmarksForBook(book).size)
        // 换过版面后页首挪位：旧书签落在新页覆盖的区间里也算同一页，不再长出第二条。
        val shifted = db.addBookmark(
            Bookmark(bookId = book, chapter = 2, charStart = 4, charEnd = 25, quote = "换版面后", createdAt = 9L),
        )
        assertEquals(c2a, shifted)
        assertEquals(3, db.bookmarksForBook(book).size)
        // 页首落在图片上（空区间）也能存。
        assertEquals(10, db.bookmarkAtPage(book, 0, 10)?.charStart)

        db.deleteBookmark(c0.id)
        assertEquals(listOf(c2a.id, c2.id), db.bookmarksForBook(book).map { it.id })
        db.deleteBookmarksForBook(book)
        assertTrue(db.bookmarksForBook(book).isEmpty())
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
            db.addBookmark(
                Bookmark(bookId = book, chapter = 1, charStart = 0, charEnd = 3, quote = "首页", createdAt = 3L),
            )
        }

        db.removeBookAndData(removed)

        assertNull(db.getBook(removed))
        assertNull(db.readingState(removed))
        assertTrue(db.annotationsForChapter(removed, 1).isEmpty())
        assertTrue(db.annotationsForBook(removed).isEmpty())
        assertTrue(db.bookmarksForBook(removed).isEmpty())

        // 另一本书的数据不受影响。
        assertTrue(db.getBook(kept) != null)
        assertTrue(db.readingState(kept) != null)
        assertEquals(1, db.annotationsForChapter(kept, 1).size)
        assertEquals(1, db.bookmarksForBook(kept).size)
    }

    @Test
    fun migrate6to7AddsAnnotationTable() {
        // 用最新 schema 建库后删掉标注表、把版本退回 6，模拟真实的 6→7 升级
        // （一路迁到最新版本，顺带覆盖 7→8 的线型列）。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "DROP TABLE annotations;", 0)
        driver.execute(null, "DROP TABLE text_revisions;", 0)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
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
        // 用最新 schema 建库后把线型列与摘录列删掉、版本退回 7，模拟真实的 7→8 升级
        // （一路迁到最新版本，顺带覆盖 8→9 的摘录列）。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "ALTER TABLE annotations DROP COLUMN lineStyle;", 0)
        driver.execute(null, "ALTER TABLE annotations DROP COLUMN quote;", 0)
        driver.execute(null, "DROP TABLE text_revisions;", 0)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
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
    fun migrate8to9AddsQuoteColumn() {
        // 用最新 schema 建库后把摘录列删掉、版本退回 8，模拟真实的 8→9 升级。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "ALTER TABLE annotations DROP COLUMN quote;", 0)
        driver.execute(null, "DROP TABLE text_revisions;", 0)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
        driver.execute(null, "PRAGMA user_version = 8;", 0)

        OrilumnDb.Schema.migrate(driver, 8, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            // 老记录没有摘录列：读回是空值（面板显示「（无摘录）」），不阻塞跳转与删除。
            driver.execute(
                null,
                "INSERT INTO annotations(bookId, chapter, charStart, charEnd, kind, color, lineStyle, note, createdAt, updatedAt) " +
                    "VALUES ($book, 1, 0, 3, 'highlight', NULL, 'straight', NULL, 1, 1);",
                0,
            )
            assertNull(db.annotationsForChapter(book, 1).single().quote)
            // 新记录能写摘录，书级查询也能读到。
            db.addAnnotation(
                Annotation(
                    bookId = book,
                    chapter = 1,
                    charStart = 5,
                    charEnd = 9,
                    kind = AnnotationKind.Highlight,
                    quote = "摘录",
                    createdAt = 2L,
                    updatedAt = 2L,
                ),
            )
            assertEquals("摘录", db.annotationsForBook(book).first { it.charStart == 5 }.quote)
        }
    }

    // ---- 正文修订（schema v11） ----

    @Test
    fun textRevisionEditedStylesRoundTrip() = runBlocking {
        // 两份区间一起落库：`styles` 相对最小修订文字、`editedStyles` 相对输入区全文。
        // 老修订（没存过输入区结构）读回来是 null，不是空表 —— 两者在卡片上行为不同。
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        val code = orilumn.reader.data.revision.RevisionStyle(
            "code",
            mapOf("class" to "code", "id" to "x1"),
        )
        val saved = db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(
                    bookId = book,
                    chapter = 1,
                    charStart = 2,
                    charEnd = 5,
                    text = "val",
                    editedText = "用 val 声明",
                    editRange = 0 until 9,
                    styles = listOf(orilumn.reader.data.revision.RevisionStyleSpan(2, 5, code)),
                    editedStyles = listOf(orilumn.reader.data.revision.RevisionStyleSpan(2, 5, code)),
                    delta = 1,
                    groupId = 5L,
                ),
                TextRevision(
                    bookId = book,
                    chapter = 1,
                    charStart = 20,
                    charEnd = 21,
                    text = "Z",
                    editedText = "Z",
                    delta = 0,
                    groupId = 5L,
                ),
            ),
        )
        assertEquals(2, saved.size)
        val rows = db.textRevisionsForChapter(book, 1)
        val withStructure = rows.first { it.charStart == 2 }
        assertEquals(listOf(orilumn.reader.data.revision.RevisionStyleSpan(2, 5, code)), withStructure.styles)
        assertEquals(listOf(orilumn.reader.data.revision.RevisionStyleSpan(2, 5, code)), withStructure.editedStyles)
        // 读者拖出的整段区间照原样回来（半开区间 [0,9) → IntRange 0..8）。
        assertEquals(0 until 9, withStructure.editRange)
        // 没有样式信息的老路径：两份都是 null。
        val plain = rows.first { it.charStart == 20 }
        assertNull(plain.styles)
        assertNull(plain.editedStyles)
        assertNull(plain.editRange)
    }

    @Test
    fun textRevisionsRoundTripAndOrdering() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        val other = db.addBook("U", null, "/u.epub", addedAt = 2L)

        // 同一次提交（跨段）：两章内两条，共用一个组号；写入顺序故意倒过来。
        val saved = db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(
                    bookId = book,
                    chapter = 3,
                    charStart = 20,
                    charEnd = 24,
                    text = "改后",
                    originalText = "原句四个",
                    editedText = "改后的整段",
                    delta = -2,
                    groupId = 777L,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
                TextRevision(
                    bookId = book,
                    chapter = 3,
                    charStart = 2,
                    charEnd = 2,
                    text = "插入",
                    delta = 2,
                    groupId = 777L,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            ),
        )
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.id > 0 })
        assertTrue(saved.all { it.groupId == 777L })

        // 章内按起点排序；纯插入（charStart == charEnd）原样读回。
        val chapter = db.textRevisionsForChapter(book, 3)
        assertEquals(listOf(2, 20), chapter.map { it.charStart })
        assertEquals(2, chapter.first().charEnd)
        assertTrue(chapter.first().isInsertion)
        assertEquals(-2, chapter.last().delta)
        // 审阅清单两行原样读回（整段原文 / 整段新文字）。
        assertEquals("原句四个", chapter.last().originalText)
        assertEquals("改后的整段", chapter.last().editedText)
        assertEquals("", chapter.first().originalText)
        assertEquals("", chapter.first().editedText)

        // 别的书 / 别的章读不到。
        assertTrue(db.textRevisionsForChapter(other, 3).isEmpty())
        assertTrue(db.textRevisionsForChapter(book, 4).isEmpty())

        // 替换：删一条、插一条，仍在同一事务里。
        val replaced = db.replaceTextRevisions(
            deletedIds = listOf(chapter.first().id),
            inserted = listOf(
                TextRevision(
                    bookId = book,
                    chapter = 3,
                    charStart = 10,
                    charEnd = 11,
                    text = "",
                    delta = -1,
                    groupId = 888L,
                    createdAt = 20L,
                    updatedAt = 20L,
                ),
            ),
        )
        assertEquals(1, replaced.size)
        val after = db.textRevisionsForChapter(book, 3)
        assertEquals(listOf(10, 20), after.map { it.charStart })
        assertEquals("", after.first().text)

        // 全书查询按章序、章内起点排序。
        db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(bookId = book, chapter = 1, charStart = 5, charEnd = 6, text = "A", groupId = 1L),
            ),
        )
        assertEquals(listOf(1, 3, 3), db.textRevisionsForBook(book).map { it.chapter })
    }

    @Test
    fun legacyRevisionSnapshotBackfillOnlyFillsEmptyRows() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        val saved = db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(bookId = book, chapter = 1, charStart = 3, charEnd = 4, text = "新", groupId = 1L),
                TextRevision(
                    bookId = book,
                    chapter = 1,
                    charStart = 8,
                    charEnd = 9,
                    text = "改",
                    originalText = "旧",
                    editedText = "改",
                    groupId = 2L,
                ),
            ),
        )

        // 老行（两列都空）补上原文与退回的改文。
        db.fillLegacyRevisionSnapshot(saved[0].id, originalText = "原", editedText = "新")
        val first = db.textRevisionsForChapter(book, 1).first { it.id == saved[0].id }
        assertEquals("原", first.originalText)
        assertEquals("新", first.editedText)

        // 再补一次不能覆盖已经写好的快照（读者新提交的数据优先）。
        db.fillLegacyRevisionSnapshot(saved[0].id, originalText = "别的", editedText = "别的")
        val again = db.textRevisionsForChapter(book, 1).first { it.id == saved[0].id }
        assertEquals("原", again.originalText)
        assertEquals("新", again.editedText)

        // 本来就有快照的新行一个字节都不动。
        db.fillLegacyRevisionSnapshot(saved[1].id, originalText = "别的", editedText = "别的")
        val fresh = db.textRevisionsForChapter(book, 1).first { it.id == saved[1].id }
        assertEquals("旧", fresh.originalText)
        assertEquals("改", fresh.editedText)
    }

    @Test
    fun deleteTextRevisionRemovesOnlyThatRow() = runBlocking {
        val db = freshDb()
        val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
        val saved = db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(bookId = book, chapter = 1, charStart = 3, charEnd = 4, text = "甲", groupId = 1L),
                TextRevision(bookId = book, chapter = 1, charStart = 8, charEnd = 9, text = "乙", groupId = 1L),
                TextRevision(bookId = book, chapter = 2, charStart = 1, charEnd = 2, text = "丙", groupId = 2L),
            ),
        )

        // 只删被点的那一行：同组另一段、别的章都不受影响。
        db.deleteTextRevision(saved[0].id)
        assertEquals(listOf(8), db.textRevisionsForChapter(book, 1).map { it.charStart })
        assertEquals(listOf(1), db.textRevisionsForChapter(book, 2).map { it.charStart })

        // 再删一次是空操作，不会顺手删掉别人。
        db.deleteTextRevision(saved[0].id)
        assertEquals(2, db.textRevisionsForBook(book).size)
    }

    @Test
    fun removeBookAndDataClearsTextRevisions() = runBlocking {
        val db = freshDb()
        val removed = db.addBook("T", null, "/b.epub", addedAt = 1L)
        val kept = db.addBook("U", null, "/u.epub", addedAt = 2L)
        db.replaceTextRevisions(
            deletedIds = emptyList(),
            inserted = listOf(
                TextRevision(bookId = removed, chapter = 1, charStart = 0, charEnd = 3, text = "x", groupId = 1L),
                TextRevision(bookId = kept, chapter = 1, charStart = 0, charEnd = 3, text = "y", groupId = 2L),
            ),
        )

        db.removeBookAndData(removed)

        assertTrue(db.textRevisionsForBook(removed).isEmpty())
        assertEquals("y", db.textRevisionsForChapter(kept, 1).single().text)
    }

    @Test
    fun migrate10to11AddsTextRevisionsTable() {
        // 用最新 schema 建库后删掉修订表、把版本退回 10，模拟真实的 10→11 升级。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "DROP TABLE text_revisions;", 0)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
        driver.execute(null, "PRAGMA user_version = 10;", 0)

        OrilumnDb.Schema.migrate(driver, 10, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            val row = db.replaceTextRevisions(
                deletedIds = emptyList(),
                inserted = listOf(
                    TextRevision(
                        bookId = book, chapter = 1, charStart = 1, charEnd = 4,
                        text = "新", originalText = "旧三字", editedText = "新", delta = -2,
                        groupId = 5L,
                    ),
                ),
            ).single()
            assertEquals("旧三字", row.originalText)
            assertEquals("新", row.editedText)
            assertEquals(listOf(row), db.textRevisionsForChapter(book, 1))
        }
    }

    @Test
    fun migrate12to13AddsEditedTextColumn() {
        // 用最新 schema 建库后删掉新列（重建旧表）、把版本退回 12，模拟真实的 12→13 升级。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(
            null,
            """
            CREATE TABLE text_revisions_old(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              bookId INTEGER NOT NULL,
              chapter INTEGER NOT NULL,
              charStart INTEGER NOT NULL,
              charEnd INTEGER NOT NULL,
              text TEXT NOT NULL,
              originalText TEXT NOT NULL DEFAULT '',
              delta INTEGER NOT NULL,
              groupId INTEGER NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              CHECK (charStart >= 0),
              CHECK (charEnd >= charStart)
            );
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO text_revisions_old SELECT id, bookId, chapter, charStart, charEnd, text, originalText, delta, groupId, createdAt, updatedAt FROM text_revisions;",
            0,
        )
        driver.execute(null, "DROP TABLE text_revisions;", 0)
        driver.execute(null, "ALTER TABLE text_revisions_old RENAME TO text_revisions;", 0)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
        driver.execute(null, "PRAGMA user_version = 12;", 0)

        OrilumnDb.Schema.migrate(driver, 12, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            val row = db.replaceTextRevisions(
                deletedIds = emptyList(),
                inserted = listOf(
                    TextRevision(
                        bookId = book, chapter = 1, charStart = 1, charEnd = 4,
                        text = "新", originalText = "旧三字", editedText = "旧三字改成新",
                        delta = -2, groupId = 5L,
                    ),
                ),
            ).single()
            assertEquals("旧三字", row.originalText)
            assertEquals("旧三字改成新", row.editedText)
            assertEquals(listOf(row), db.textRevisionsForChapter(book, 1))
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

    @Test
    fun migrate14to15AddsBookmarksTable() {
        // 用最新 schema 建库后删掉书签表、把版本退回 14，模拟真实的 14→15 升级。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        driver.execute(null, "DROP TABLE bookmarks;", 0)
        driver.execute(null, "PRAGMA user_version = 14;", 0)

        OrilumnDb.Schema.migrate(driver, 14, OrilumnDb.Schema.version)

        val db = LibraryDb(OrilumnDb(driver))
        runBlocking {
            val book = db.addBook("T", null, "/b.epub", addedAt = 1L)
            val saved = db.addBookmark(
                Bookmark(bookId = book, chapter = 3, charStart = 12, charEnd = 40, quote = "迁移后", createdAt = 9L),
            )
            assertTrue(saved.id > 0)
            assertEquals(listOf(saved), db.bookmarksForBook(book))
        }
    }
}
