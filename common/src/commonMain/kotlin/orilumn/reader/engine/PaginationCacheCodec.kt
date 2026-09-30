package orilumn.reader.engine

import okio.Buffer
import orilumn.reader.io.Logger

/**
 * Binary codec for [ChapterPaginationTable] — the single source of the on-disk cache format
 * (C1-1, S18 okio 口径). Both the Android `File` adapter and the shared [PaginationCacheStore]
 * persist through this object, so the byte layout can never drift between shells.
 *
 * File format (big-endian, okio `Buffer.readInt/writeInt` — byte-identical to the retired
 * `DataInput/OutputStream` layout, so caches written before C1-1 stay readable):
 *   Header:   [magic: Int = 0x43505442 "CPTB"] [version: Int = 3]
 *             [layoutVersion: Int] [appVersion: Int] [chapterIndex: Int] [paramHash: Long]
 *             [totalBlocks: Int] [totalChars: Int] [pageCount: Int]
 *   Pages:    [charStart, charEnd, blockStart, blockEndExclusive] × pageCount  (each Int)
 *
 * **Cache-invalidation contract.** There is exactly one authority for whether a persisted table is
 * still valid: [decode]. A table is rejected (returns null → the caller rebuilds) on magic/schema
 * mismatch, when the table's [LAYOUT_VERSION] differs from the current engine geometry version,
 * or when the table's [appVersion] differs from the running build. Bumping [LAYOUT_VERSION] is the
 * invalidation point after any change to how line geometry is computed (break width, margin
 * folding, line height, char offsets, …); [appVersion] (the platform's monotonic build number —
 * Android `versionCode`, desktop `DISK_CACHE_VERSION`) is the backstop for everything the geometry
 * version doesn't cover (shaping engine / Skia / system-font changes shipped in a release without
 * a geometry bump): an upgrade with a new build number uniformly discards old disk tables, with no
 * per-site clearing. Old files are never migrated — the first read after an upgrade misses and the
 * chapter is reshaped under the new build.
 */
object PaginationCacheCodec {

    private const val MAGIC = 0x43505442

    /** File/schema version. Bump only when the on-disk byte layout changes. */
    private const val VERSION = 3 // 3: header gains appVersion (build-number backstop; v2 files miss on read)

    /** Engine-geometry version. Bump on ANY change to line geometry computation so stale tables are
     *  invalidated at the single [decode] choke-point. Kept separate from [VERSION]: schema changes may
     *  leave geometry untouched and vice-versa. */
    const val LAYOUT_VERSION = 28 // 28: prepare 盒布局传入 imageLoader/chapterHref，叶高用真实内在比例（此前回退 w/2），旧表 Y 全错位作废

    /** Per-book cap on persisted table files. Old-parameter-hash tables are orphaned when the layout
     *  key changes; version-stale orphans (older build) are swept by [PaginationCacheStore.sweepStale]
     *  on book open, the rest are LRU-evicted here. Keep the most recently used and evict the rest
     *  (each file ≤ ~32 KB ⇒ ≤ ~8 MB per book at the cap). R7 raised this from 32: a whole-book B2
     *  pass must be able to record every chapter (the in-memory skip-fresh registry keys off these
     *  files' presence), and 256 chapters covers virtually all books. */
    const val MAX_TABLES_PER_BOOK = 256

    /** Filename convention: `<chapterIndex>_<paramHash>.bin`. */
    fun filename(chapterIndex: Int, paramHash: Long): String =
        "${chapterIndex}_${paramHash}.bin"

    /** Serializes [table] to its on-disk bytes, stamped with [appVersion] (the platform's
     *  monotonic build number). Tables stamped with a different build are rejected on read. */
    fun encode(table: ChapterPaginationTable, appVersion: Int): ByteArray {
        val buf = Buffer()
        buf.writeInt(MAGIC)
        buf.writeInt(VERSION)
        buf.writeInt(LAYOUT_VERSION)
        buf.writeInt(appVersion)
        buf.writeInt(table.chapterIndex)
        buf.writeLong(table.paramHash)
        buf.writeInt(table.totalBlocks)
        buf.writeInt(table.totalChars)
        buf.writeInt(table.pages.size)
        for (p in table.pages) {
            buf.writeInt(p.charStart)
            buf.writeInt(p.charEnd)
            buf.writeInt(p.blockStart)
            buf.writeInt(p.blockEndExclusive)
        }
        return buf.readByteArray()
    }

    /** Deserializes on-disk bytes. Returns null on miss, schema/geometry-version mismatch,
     *  build-number mismatch, or corruption — the single authority for whether a cached table is
     *  still usable.
     *  Null 原因逐条落盘：版本不匹配（预期失效）/损坏/解析异常三者不可再压成同一个哑 null，
     *  否则 DISK-HIT 跟踪无法区分 miss、损坏与 codec 自身 bug。 */
    fun decode(bytes: ByteArray, expectedAppVersion: Int): ChapterPaginationTable? {
        if (bytes.isEmpty()) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != MAGIC) return decodeNull("magic")
            if (buf.readInt() != VERSION) return decodeNull("version")
            if (buf.readInt() != LAYOUT_VERSION) return decodeNull("layout-version")
            if (buf.readInt() != expectedAppVersion) return decodeNull("app-version")
            val chapterIndex = buf.readInt()
            val paramHash = buf.readLong()
            val totalBlocks = buf.readInt()
            val totalChars = buf.readInt()
            val pageCount = buf.readInt()
            if (pageCount < 0) return decodeNull("pageCount=$pageCount")
            val pages = ArrayList<ChapterPaginationTable.PageRecord>(pageCount)
            repeat(pageCount) {
                pages.add(
                    ChapterPaginationTable.PageRecord(
                        charStart = buf.readInt(),
                        charEnd = buf.readInt(),
                        blockStart = buf.readInt(),
                        blockEndExclusive = buf.readInt(),
                    ),
                )
            }
            ChapterPaginationTable(
                chapterIndex = chapterIndex,
                paramHash = paramHash,
                totalBlocks = totalBlocks,
                totalChars = totalChars,
                pages = pages,
            )
        } catch (e: Exception) {
            decodeNull("exception ${e.message}")
        }
    }

    private fun decodeNull(reason: String): ChapterPaginationTable? {
        Logger.w("Orilumn.DISK", "pagination decode null ($reason)")
        return null
    }
}
