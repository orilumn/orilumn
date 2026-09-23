package orilumn.reader.data.settings

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import okio.use

/**
 * Per-book private settings override read/write: `settingsDir/books/{bookId}.json`.
 *
 * Responsibilities:
 *  - [load]: read a book's overlay; no file → [BookSettings.EMPTY] (all fall back to global memory).
 *  - [save]: save the overlay (atomic write).
 *  - [reset]: delete a book's overlay; afterwards the book fully falls back to global memory.
 *
 * Thread safety: methods are called from IO threads; recommended to run within coroutine Dispatchers.IO.
 * Platform-agnostic via okio [FileSystem.SYSTEM] (S18: java.io.File → okio, joins commonMain).
 *
 * @see BookSettings overlay data class
 * @see ReaderSettingsStore global memory
 */
class BookSettingsStore(settingsDir: String) {

    private val fs = FileSystem.SYSTEM

    private val booksDir: Path = settingsDir.toPath() / BOOKS_DIR

    /** Read a book's overlay; no file → [BookSettings.EMPTY]. */
    fun load(bookId: Long): BookSettings {
        val file = bookFile(bookId)
        val text = if (fs.exists(file)) fs.source(file).buffer().use { it.readUtf8() } else null
        return if (text != null) BookSettings.fromJson(text) else BookSettings.EMPTY
    }

    /** Save a book's overlay (atomic write). */
    fun save(bookId: Long, overlay: BookSettings) {
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

    private fun bookFile(bookId: Long): Path = booksDir / "${bookId}.json"

    private companion object {
        const val BOOKS_DIR = "books"
    }
}