package orilumn.reader.data.book

import orilumn.reader.data.db.BookSort
import orilumn.reader.data.db.LibraryDb
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
) {

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
        db.removeBook(book.id)
        db.clearReadingState(book.id)
    }

    // ---- Reading progress (SQLDelight；S34a) ----

    suspend fun saveReadingState(state: BookReadingState) = db.saveReadingState(state)

    suspend fun readingState(bookId: Long): BookReadingState? = db.readingState(bookId)
}
