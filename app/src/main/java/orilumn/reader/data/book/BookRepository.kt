package orilumn.reader.data.book

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.bookmark.Bookmark
import orilumn.reader.data.db.BookSort
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.revision.TextRevisionStore
import kotlinx.coroutines.flow.Flow

/**
 * Library repository: wraps shelf CRUD and reading-progress access.
 *
 * S34a：进度随 `reading_states` 表迁入 SQLDelight；S34b 起字体池亦迁入，
 * 本类只持 [LibraryDb]（Room 已整体移除）；调用方（阅读面/书架）API 不变。
 *
 * M0 only does data persistence; "open and parse the EPUB by sourceUri" is invoked by the rendering layer at read time (see the M0 closed loop).
 */
class BookRepository(
    private val db: LibraryDb,
) : TextRevisionStore {

    /** Shelf sort enum: added time / read time / title. */
    enum class Sort {
        Added,
        Read,
        Name,
    }

    /** The shelf (per the given sort). */
    fun books(sort: Sort = Sort.Added): Flow<List<Book>> =
        db.observeBooks(
            when (sort) {
                Sort.Added -> BookSort.Added
                Sort.Read -> BookSort.Read
                Sort.Name -> BookSort.Name
            },
        )

    suspend fun getBook(id: Long): Book? = db.getBook(id)

    /** Return the whole library in one shot (used in non-streaming scenarios like import duplicate detection). */
    suspend fun allBooks(): List<Book> = db.allBooks()

    /** Add to the shelf; returns the new record's primary key. */
    suspend fun addBook(title: String, author: String?, filePath: String, coverPath: String? = null): Long =
        db.addBook(title, author, filePath, coverPath, System.currentTimeMillis())

    /** Update a book's cover local path. */
    suspend fun updateBook(book: Book) = db.updateBook(book)

    suspend fun touchRead(id: Long) = db.touchRead(id, System.currentTimeMillis())

    suspend fun removeBook(book: Book) {
        db.removeBookAndData(book.id)
    }

    // ---- Reading progress (SQLDelight；S34a) ----

    suspend fun saveReadingState(state: BookReadingState) = db.saveReadingState(state)

    suspend fun readingState(bookId: Long): BookReadingState? = db.readingState(bookId)

    // ---- 文本标注（schema v9） ----

    suspend fun annotationsForChapter(bookId: Long, chapter: Int): List<Annotation> =
        db.annotationsForChapter(bookId, chapter)

    suspend fun annotationsForBook(bookId: Long): List<Annotation> =
        db.annotationsForBook(bookId)

    suspend fun addAnnotation(annotation: Annotation): Annotation = db.addAnnotation(annotation)

    suspend fun updateAnnotation(annotation: Annotation) = db.updateAnnotation(annotation)

    suspend fun deleteAnnotation(id: Long) = db.deleteAnnotation(id)

    // ---- 正文修订（schema v11；锚点为原书章内字符区间） ----

    override suspend fun textRevisionsForChapter(bookId: Long, chapter: Int): List<TextRevision> =
        db.textRevisionsForChapter(bookId, chapter)

    override suspend fun textRevisionsForBook(bookId: Long): List<TextRevision> = db.textRevisionsForBook(bookId)

    /** 同一次提交：删被吸收的旧修订 + 插新修订（同一事务）。 */
    override suspend fun replaceTextRevisions(
        deletedIds: List<Long>,
        inserted: List<TextRevision>,
    ): List<TextRevision> = db.replaceTextRevisions(deletedIds, inserted)

    override suspend fun fillLegacyRevisionSnapshot(id: Long, originalText: String, editedText: String) =
        db.fillLegacyRevisionSnapshot(id, originalText, editedText)

    override suspend fun deleteTextRevision(id: Long) = db.deleteTextRevision(id)

    override suspend fun deleteTextRevisionsForBook(bookId: Long) = db.deleteTextRevisionsForBook(bookId)

    // ---- 书签（schema v15；锚点为原书章内页首字符） ----

    suspend fun bookmarksForBook(bookId: Long): List<Bookmark> = db.bookmarksForBook(bookId)

    suspend fun addBookmark(bookmark: Bookmark): Bookmark = db.addBookmark(bookmark)

    suspend fun deleteBookmark(id: Long) = db.deleteBookmark(id)
}
