package orilumn.reader.data.book

import android.content.Context
import android.net.Uri
import orilumn.reader.data.epub.EpubFormatException
import orilumn.reader.data.epub.parseEpubBytes
import orilumn.reader.data.epub.readEpubEntry
import orilumn.reader.engine.skia.ImageCodec
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports e-books the user selects via SAF into the shelf:
 * copy into the private library directory → parse metadata with the self-built [EpubParser] → write into [BookRepository].
 *
 * **Book files kept private, metadata kept private**:
 *  - After SAF selection, **copy** the book file into the private `filesDir/books/`; `Book.filePath` stores the private absolute path.
 *  - The private directory needs no permission, is stably readable, and is cleared on uninstall; copying avoids relying
 *    on SAF temporary grants (a one-session uri dies on restart).
 *  - Metadata such as book info / reading progress / settings lives in the private SQLDelight database, managed by [BookRepository].
 *  - Parsing goes "write a temp copy first → read the zip" ([ZipEpubResourceReader] is file-path based), then deletes the temp file.
 */
class BookImporter(
    private val context: Context,
    private val repository: BookRepository,
) {

    /**
     * Import pre-check: only parses the selected book's metadata (title/author), **copies no files, stores nothing**.
     * Used to detect duplicates against books already in the library. Returns null on parse failure.
     */
    suspend fun scan(uri: Uri): ScannedBook? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        scanBytes(bytes)
    }

    /**
     * Import the selected e-book, returning the new record's primary key; throws on failure.
     *
     * @param uri the content:// uri returned by SAF selection (valid only for this session, used for one-shot read + copy).
     */
    suspend fun import(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("无法打开所选文件"))
        importBytes(bytes)
    }

    /**
     * Import pre-check (byte version for shared-ui [ShelfHost]): only parses metadata,
     * copies no files, stores nothing. Used for duplicate detection. Returns null on failure.
     *
     * S31: mpfilepicker (FileKit) gives `PlatformFile.readBytes()` instead of a SAF uri;
     * the byte path shares the same parse gate as [scan].
     */
    suspend fun scanBytes(bytes: ByteArray): ScannedBook? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = parseEpubBytes(bytes, context.cacheDir.absolutePath)
                ?: throw EpubFormatException("书中没有可读正文章节")
            ScannedBook(parsed.title, parsed.author)
        }.getOrNull()
    }

    /**
     * Import from raw bytes (byte version for shared-ui [ShelfHost]).
     * Semantics identical to [import]: temp copy → parse gate → private copy → Room row → cover.
     */
    suspend fun importBytes(bytes: ByteArray): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = parseEpubBytes(bytes, context.cacheDir.absolutePath)
                ?: throw EpubFormatException("书中没有可读正文章节")
            val name = "book_${System.currentTimeMillis()}.epub"
            val target = copyToPrivate(name, bytes)
                ?: throw IllegalStateException("无法写入书库目录")
            val id = repository.addBook(parsed.title, parsed.author, target)
            val coverPath = parsed.cover?.let { href ->
                val coverBytes = readEpubEntry(bytes, context.cacheDir.absolutePath, href)
                    ?: return@let null
                saveCover(coverBytes, id)
            }
            if (coverPath != null) {
                repository.getBook(id)?.let { repository.updateBook(it.copy(coverPath = coverPath)) }
            }
            id
        }
    }
    /** Write the book bytes into the private `filesDir/books/`, returning the absolute path; null when the write fails. */
    private fun copyToPrivate(name: String, bytes: ByteArray): String? {
        return runCatching {
            val dir = File(context.filesDir, "books").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrNull()
    }

    /** Decode the cover bytes → downscale to max side ~720px → store `filesDir/covers/cover_{bookId}.png`, returning the absolute path; null on failure. */
    private fun saveCover(bytes: ByteArray, bookId: Long): String? {
        return runCatching {
            // First read only the dimensions (skia header decode) to compute the downsampling ratio,
            // avoiding blowing up memory by fully decoding a large image.
            val bounds = ImageCodec.probeBounds(bytes) ?: return@runCatching null
            val max = maxOf(bounds.first, bounds.second)
            val decoded = if (max <= 720) {
                ImageCodec.decode(bytes) ?: return@runCatching null
            } else {
                val ratio = 720f / max
                val tw = (bounds.first * ratio).roundToInt().coerceAtLeast(1)
                ImageCodec.decodeScaled(bytes, tw) ?: return@runCatching null
            }
            val outBytes = ImageCodec.encodePng(decoded.image) ?: return@runCatching null
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            File(dir, "cover_$bookId.png").apply { writeBytes(outBytes) }.absolutePath
        }.getOrNull()
    }
}

/** Book metadata obtained by the pre-check scan (used for duplicate detection before import). */
data class ScannedBook(
    val title: String,
    val author: String?,
)