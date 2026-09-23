package orilumn.reader.engine

import okio.FileSystem
import okio.Path

/**
 * Shared pagination-table disk store (C1-1): [ChapterPaginationTable] persistence over an
 * injected okio [FileSystem] (S18 口径), so every shell — Android today, desktop / iOS next —
 * reads and writes the same bytes through the same [PaginationCacheCodec].
 *
 * The Android shell keeps its own `java.io.File` adapter
 * (`orilumn.reader.engine.PaginationCacheStore` in `:app`) with identical behavior for its
 * transitional call sites; that adapter delegates format decisions here and will retire in C1-2.
 *
 * @param fs okio filesystem (production: `FileSystem.SYSTEM`; tests: a temp-dir-backed SYSTEM).
 * @param root cache root directory; tables live at `<root>/pagination/<bookId>/`.
 */
class PaginationCacheStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    root: Path,
) {
    private val rootDir: Path = root

    /** Resolves the directory path for a book's pagination tables (creates if missing). */
    fun dirFor(bookId: String): Path =
        rootDir.resolve("pagination/$bookId").also { runCatching { fs.createDirectories(it) } }

    /** Full cache file path. */
    fun file(bookId: String, chapterIndex: Int, paramHash: Long): Path =
        dirFor(bookId).resolve(PaginationCacheCodec.filename(chapterIndex, paramHash))

    /** Reads a pagination table from disk. Returns null on miss, schema/geometry-version mismatch,
     *  or corruption — the single authority for whether a cached table is still usable. A successful
     *  read re-writes the identical bytes so the file's last-modified time tracks last USED, not
     *  last written (okio's common `FileSystem` exposes no mtime-touch; the rewrite is a few KB,
     *  idempotent, and keeps the LRU eviction semantics byte-for-byte with the old shell). */
    fun read(f: Path): ChapterPaginationTable? {
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return null
        val table = PaginationCacheCodec.decode(bytes) ?: return null
        runCatching { fs.write(f) { write(bytes) } }
        return table
    }

    /** Writes a pagination table to disk. Overwrites any existing file at that location, then
     *  LRU-trims the book's table directory so orphaned parameter-hash files stay bounded. */
    fun write(table: ChapterPaginationTable, f: Path) {
        runCatching { fs.createDirectories(f.parent ?: return) }
        fs.write(f) { write(PaginationCacheCodec.encode(table)) }
        f.parent?.let { trim(it) }
    }

    /** Keeps at most [PaginationCacheCodec.MAX_TABLES_PER_BOOK] files per book directory. Deletes the
     *  least-recently-used (lowest last-modified) tables beyond the cap — idempotent, and safe to run
     *  on any thread since it only touches filename/lastModified metadata and never table contents. */
    private fun trim(dir: Path) {
        val files = runCatching { fs.list(dir) }
            .getOrNull()
            ?.filter { it.name.endsWith(".bin") && fs.metadataOrNull(it)?.isRegularFile == true }
            ?.sortedByDescending { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
            ?: return
        if (files.size <= PaginationCacheCodec.MAX_TABLES_PER_BOOK) return
        for (stale in files.drop(PaginationCacheCodec.MAX_TABLES_PER_BOOK)) {
            runCatching { fs.delete(stale) }
        }
    }

    /** Removes all pagination-table files for a specific book (useful on book delete / cache
     *  eviction). Does nothing if the book directory doesn't exist. */
    fun clearBook(bookId: String) {
        val dir = rootDir.resolve("pagination/$bookId")
        val files = runCatching { fs.list(dir) }.getOrNull() ?: return
        for (f in files) runCatching { fs.delete(f) }
    }
}
