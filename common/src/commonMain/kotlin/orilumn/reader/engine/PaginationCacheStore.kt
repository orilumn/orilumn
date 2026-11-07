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
     *  the rewrite is tens to a few hundred bytes, idempotent). */
    fun read(f: Path): ChapterPaginationTable? {
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        // IO 异常（权限/磁盘满/并发删）此前静默——读失败即重建是对的，但原因要留痕。
        val bytes = runCatching { fs.read(f) { readByteArray() } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination read IO FAIL $f ${it.message}") }
            .getOrNull() ?: return null
        // decode==null 的原因由 codec 逐条落盘，此处透传。
        val table = PaginationCacheCodec.decode(bytes, appVersion) ?: return null
        // 刷新「最后使用」时间的重写失败此前静默——调不到只能记 w。
        runCatching { fs.write(f) { write(bytes) } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination touch FAIL $f ${it.message}") }
        return table
    }

    /**
     * 只读表头拿本章页数（全书累计页码求和用，见 [PaginationCacheCodec.decodeHeaderPageCount]）。
     *
     * 与 [read] 的两处刻意差别：
     *  - **只读 40 字节**，不整份解码：一次全书扫描要碰几十上百个文件，页记录（每页 16 字节 ×
     *    几百页）对「数页数」是纯浪费；
     *  - **不重写文件**：`read` 的重写是为了刷新「最后使用」时间，但页数扫描是后台搭车，
     *    没必要把全书每章的表都重写一遍（纯写放大）。
     *
     * 命中判据与 [read] 完全同源（当前构建 + 当前参数 + 该章号确有表），只是不落日志。
     */
    fun readPageCount(bookId: String, chapterIndex: Int, paramHash: Long): Int? {
        val f = file(bookId, chapterIndex, paramHash)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching {
            fs.read(f) { readByteArray(PaginationCacheCodec.HEADER_BYTES.toLong()) }
        }.getOrNull() ?: return null
        return PaginationCacheCodec.decodeHeaderPageCount(bytes, appVersion, chapterIndex, paramHash)
    }

    /** 全书各章页数快照的落点（`.idx`，与表文件同目录，但只随参数指纹整份作废）。 */
    fun countsFile(bookId: String, paramHash: Long): Path =
        dirFor(bookId).resolve(PaginationCacheCodec.countsFilename(paramHash))

    /**
     * 读全书页数快照（章号 → 页数）。参数指纹 / 引擎几何指纹 / 构建号任一不符即 null。
     *
     * 它只在**同一套版式参数 + 同一构建**下有意义：同参数下分页是确定的，页数不会变。
     * 单独存它的理由是**一次读盘**（762 章 6 KB vs 逐章开 762 个文件读表头）；
     * 表文件丢了它还能读只是附带性质——参数/几何/构建任一不符仍整份作废，跨不过「排版改了」。
     */
    fun readPageCounts(bookId: String, paramHash: Long): Map<Int, Int>? {
        val f = countsFile(bookId, paramHash)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination counts read IO FAIL $f ${it.message}") }
            .getOrNull() ?: return null
        return PaginationCacheCodec.decodePageCounts(bytes, appVersion, paramHash)
    }

    /** 写全书页数快照（整份覆盖；几百 KB 以内的小文件，几十字节/章）。 */
    fun writePageCounts(bookId: String, paramHash: Long, counts: Map<Int, Int>) {
        val f = countsFile(bookId, paramHash)
        runCatching { fs.write(f) { write(PaginationCacheCodec.encodePageCounts(paramHash, counts, appVersion)) } }
            .onFailure { Logger.w("Orilumn.DISK", "pagination counts write FAIL $f ${it.message}") }
    }

    /** Writes a pagination table to disk. Overwrites any existing file at that location.
     *
     *  表文件不做数量裁剪：实测一张表只有几十到二百字节（762 章整本不到 120 KB），
     *  留着每一章才能开书即磁盘命中。旧参数指纹的孤儿表由 [cleanStaleParams] 在
     *  参数稳定点按指纹清掉，旧构建/坏表由 [sweepStale] 开书清掉，都不靠数量上限兜。 */
    fun write(table: ChapterPaginationTable, f: Path) {
        runCatching { fs.createDirectories(f.parent ?: return) }
        fs.write(f) { write(PaginationCacheCodec.encode(table, appVersion)) }
    }

    /** Removes all pagination-table files for a specific book (useful on book delete / cache
     *  eviction). Does nothing if the book directory doesn't exist. */
    fun clearBook(bookId: String) {
        val dir = rootDir.resolve("pagination/$bookId")
        val files = runCatching { fs.list(dir) }.getOrNull() ?: return
        for (f in files) runCatching { fs.delete(f) }
    }

    /** Deletes the book's pagination-table files orphaned by a layout-parameter
     *  change: every `<chapterIndex>_<paramHash>.bin` whose hash differs from
     *  [keepHash] (the running params' [LayoutParamKey] fingerprint). Pure
     *  filename filter — no table reads, so it is cheap and idempotent.
     *  Files matching [keepHash] **stay**: they are valid for the running params
     *  (a previous session with identical params is a disk hit, and a settings
     *  revert re-hits them) — unlike [cleanBookAll], which wipes those too. */
    fun cleanStaleParams(bookId: String, keepHash: Long): Int {
        val dir = rootDir.resolve("pagination/$bookId")
        val files = runCatching { fs.list(dir) }
            .onFailure { Logger.w("Orilumn.DISK", "pagination clean-stale list FAIL $dir ${it.message}") }
            .getOrNull()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".idx") }
            ?: return 0
        val suffix = "_$keepHash.bin"
        val countsSuffix = "_$keepHash.idx"
        var deleted = 0
        for (f in files) {
            if (f.name.endsWith(suffix)) continue
            // 页数快照同样是「换参数即作废」：留着不删只会在目录里攒出一堆同构清单。
            if (f.name.startsWith("counts_") && f.name.endsWith(countsSuffix)) continue
            if (runCatching { fs.delete(f) }.isSuccess) deleted++
        }
        if (deleted > 0) Logger.w("Orilumn.DISK", "pagination clean-stale book=$bookId keepHash=$keepHash deleted=$deleted")
        return deleted
    }

    /** Deletes every table file in the book's directory that no longer decodes under the current
     *  build (older schema/geometry/build, or corruption). Same-hash files from the running build
     *  are untouched; different-parameter-hash files from the running build are live history (a
     *  settings revert hits them) and are left to [cleanStaleParams]. Runs on book open (background):
     *  one tiny header read per table file. Returns the deleted count. */
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
        // 页数快照同受「换构建/换引擎几何」影响：解不开的（含旧构建写的）一并清掉，
        // 免得留一堆永远读不中的 `.idx`。参数指纹从文件名里读回，指纹变了不算「坏」。
        val countsFiles = runCatching { fs.list(dir) }
            .getOrNull()
            ?.filter { it.name.startsWith("counts_") && it.name.endsWith(".idx") && fs.metadataOrNull(it)?.isRegularFile == true }
            ?: emptyList()
        for (f in countsFiles) {
            val hash = f.name.removePrefix("counts_").removeSuffix(".idx").toLongOrNull() ?: continue
            val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: continue
            if (PaginationCacheCodec.decodePageCounts(bytes, appVersion, hash) == null) {
                if (runCatching { fs.delete(f) }.isSuccess) deleted++
            }
        }
        if (deleted > 0) Logger.w("Orilumn.DISK", "pagination sweep book=$bookId deleted=$deleted kept=${files.size - deleted}")
        return deleted
    }

    /** Deletes all pagination-table files for the entire book regardless of chapter/paramHash. */
    fun cleanBookAll(bookId: String) {
        val dir = rootDir.resolve("pagination/$bookId")
        val files = runCatching { fs.list(dir) }
            .onFailure { Logger.w("Orilumn.DISK", "pagination clean list FAIL $dir ${it.message}") }
            .getOrNull()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".idx") }
            ?: return
        for (f in files) {
            runCatching { fs.delete(f) }
                .onFailure { Logger.w("Orilumn.DISK", "pagination clean delete FAIL $f ${it.message}") }
        }
    }
}
