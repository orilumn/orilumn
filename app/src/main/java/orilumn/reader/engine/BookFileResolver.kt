package orilumn.reader.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Book source resolution: normalizes the source path (`content://` or an absolute file path)
 * passed when opening the reader into a local [File] openable by
 * [orilumn.reader.data.epub.ZipEpubResourceReader].
 *
 * A `content://` book source cannot be opened as a File directly; it must first be copied into the
 * private cache directory (a temporary file, cleaned up when cleared). A local file path is
 * referenced directly (not copied, to avoid wasting double disk space).
 */
class BookFileResolver(private val context: Context) {

    /** Resolves the book source; returns null when src is empty/reading fails. */
    fun resolve(src: String): File? {
        if (src.isBlank()) return null
        return if (src.startsWith("content://")) {
            copyToCache(src)
        } else {
            File(src).takeIf { it.isFile }
        }
    }

    /** Copies content:// into cache and returns the cache file. */
    private fun copyToCache(contentUri: String): File? = runCatching {
        val uri = Uri.parse(contentUri)
        val name = (contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        }) ?: "book.epub"
        val dest = File(context.cacheDir, "book_src_${System.currentTimeMillis()}_${sanitize(name)}")
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.isFile && dest.length() > 0) dest else null
    }.getOrNull()

    private val contentResolver get() = context.contentResolver

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}