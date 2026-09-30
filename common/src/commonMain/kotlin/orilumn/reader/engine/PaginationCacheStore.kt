package orilumn.reader.engine

import okio.FileSystem
import okio.Path
import orilumn.reader.io.Logger

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
 * @param appVersion the platform's monotonic build number stamped into every table by [PaginationCacheCodec];
 *   tables from other builds are treated as misses (upgrade invalidates uniformly).
 */
class PaginationCacheStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    root: Path,
    private val appVersion: Int = 0,
) {
    private val rootDir: Path = root

    /** Resolves the directory path for a book's pagination tables (creates if missing). */
    fun dirFor(bookId: String): Path =
        // root 不可写（内部配置错）此前藏到首次 write 才炸：记 w 让现场前移（返回保留）。
        rootDir.resolve("pagination/$bookId").also {
            runCatching { fs.createDirectories(it) }
                .onFailure { Logger.w("Orilumn.DISK", "pagination dirFor FAIL $it ${it.message}") }
        }

    /** Full cache file path. */
    fun file(bookId: String, chapterIndex: Int, paramHash: Long): Path =
        dirFor(bookId).resolve(PaginationCacheCodec.filename(chapterIndex, paramHash))

    /** Reads a pagination table from disk. Returns null on miss, schema/geometry-version mismatch,
     *  build-number mismatch, or corruption — the single authority for whether a cached table is
     *  still usable. A successful read re-writes the identical bytes so the file's last-modified
     *  time tracks last USED, not last written (okio's common `FileSystem` exposes no mtime-touch;
     *  the rewrite is a few KB, idempotent, and keeps the LRU eviction semantics byte-for-byte with
     *  the old shell). */
    fun read(f: Path): ChapterPaginationTable? {
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        // IO 异常（权限/磁盘满/并发删）此前静默——读失败即重建是对的，但原因要留痕。
        val bytes = runCatching { fs.read(f) { readByteArray() } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination read IO FAIL $f ${it.message}") }
            .getOrNull() ?: return null
        // decode==null 的原因由 codec 逐条落盘，此处透传。
        val table = PaginationCacheCodec.decode(bytes, appVersion) ?: return null
        // LRU-touch 重写的失败此前静默——evict 语义漂移查不出，记 w。
        runCatching { fs.write(f) { write(bytes) } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination LRU-touch FAIL $f ${it.message}") }
        return table
    }

    /** Writes a pagination table to disk. Overwrites any existing file at that location, then
     *  LRU-trims the book's table directory so orphaned parameter-hash files stay bounded. */
    fun write(table: ChapterPaginationTable, f: Path) {
        runCatching { fs.createDirectories(f.parent ?: return) }
        fs.write(f) { write(PaginationCacheCodec.encode(table, appVersion)) }
        f.parent?.let { trim(it) }
    }

    /** Keeps at most [PaginationCacheCodec.MAX_TABLES_PER_BOOK] files per book directory. Deletes the
     *  least-recently-used (lowest last-modified) tables beyond the cap — idempotent, and safe to run
     *  on any thread since it only touches filename/lastModified metadata and never table contents. */
    private fun trim(dir: Path) {
        // list 失败此前静默跳过修剪→孤儿 .bin 无限堆积（cap 失效）：记 w。
        val files = runCatching { fs.list(dir) }
            .onFailure { Logger.w("Orilumn.DISK", "pagination trim list FAIL $dir ${it.message}") }
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

    /** Deletes every table file in the book's directory that no longer decodes under the current
     *  build (older schema/geometry/build, or corruption). Same-hash files from the running build
     *  are untouched; different-parameter-hash files from the running build are live history (a
     *  settings revert hits them) and stay for the LRU cap. Runs on book open (background): at most
     *  [PaginationCacheCodec.MAX_TABLES_PER_BOOK] tiny header reads. Returns the deleted count. */
    fun sweepStale(bookId: String): Int {
        val dir = rootDir.resolve("pagination/$bookId")
        val files = runCatching { fs.list(dir) }
            .onFailure { Logger.w("Orilumn.DISK", "pagination sweep list FAIL $dir ${it.message}") }
            .getOrNull()
            ?.filter { it.name.endsWith(".bin") && fs.metadataOrNull(it)?.isRegularFile == true }
            ?: return 0
        var deleted = 0
        for (f in files) {
            val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: continue
            if (PaginationCacheCodec.decode(bytes, appVersion) == null) {
                if (runCatching { fs.delete(f) }.isSuccess) deleted++
            }
        }
        if (deleted > 0) Logger.w("Orilumn.DISK", "pagination sweep book=$bookId deleted=$deleted kept=${files.size - deleted}")
        return deleted
    }
}
