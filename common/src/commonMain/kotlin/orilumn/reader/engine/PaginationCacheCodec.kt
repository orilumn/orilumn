package orilumn.reader.engine

import okio.Buffer

/**
 * Binary codec for [ChapterPaginationTable] — the single source of the on-disk cache format
 * (C1-1, S18 okio 口径). Both the Android `File` adapter and the shared [PaginationCacheStore]
 * persist through this object, so the byte layout can never drift between shells.
 *
 * File format (big-endian, okio `Buffer.readInt/writeInt` — byte-identical to the retired
 * `DataInput/OutputStream` layout, so caches written before C1-1 stay readable):
 *   Header:   [magic: Int = 0x43505442 "CPTB"] [version: Int = 2]
 *             [layoutVersion: Int] [chapterIndex: Int] [paramHash: Long]
 *             [totalBlocks: Int] [totalChars: Int] [pageCount: Int]
 *   Pages:    [charStart, charEnd, blockStart, blockEndExclusive] × pageCount  (each Int)
 *
 * **Cache-invalidation contract.** There is exactly one authority for whether a persisted table is
 * still valid: [decode]. A table is rejected (returns null → the caller rebuilds) on magic/schema
 * mismatch, or when the table's [LAYOUT_VERSION] differs from the current engine geometry version.
 * Bumping [LAYOUT_VERSION] is the *single* invalidation point after any change to how line geometry
 * is computed (break width, margin folding, line height, char offsets, …) — old disk tables are then
 * discarded uniformly on every reader, with no per-site clearing.
 */
object PaginationCacheCodec {

    private const val MAGIC = 0x43505442

    /** File/schema version. Bump only when the on-disk byte layout changes. */
    private const val VERSION = 2

    /** Engine-geometry version. Bump on ANY change to line geometry computation so stale tables are
     *  invalidated at the single [decode] choke-point. Kept separate from [VERSION]: schema changes may
     *  leave geometry untouched and vice-versa. */
    const val LAYOUT_VERSION = 26 // 26: 段间距仅 p/li 相邻对生效（UI 层相邻兄弟规则），p 纵边距几何变，旧表作废

    /** Per-book cap on persisted table files. Old-parameter-hash tables are orphaned when the layout
     *  key changes and are never deleted today; keep the most recently used and evict the rest
     *  (each file ≤ ~32 KB ⇒ ≤ ~1 MB per book at the cap). */
    const val MAX_TABLES_PER_BOOK = 32

    /** Filename convention: `<chapterIndex>_<paramHash>.bin`. */
    fun filename(chapterIndex: Int, paramHash: Long): String =
        "${chapterIndex}_${paramHash}.bin"

    /** Serializes [table] to its on-disk bytes. */
    fun encode(table: ChapterPaginationTable): ByteArray {
        val buf = Buffer()
        buf.writeInt(MAGIC)
        buf.writeInt(VERSION)
        buf.writeInt(LAYOUT_VERSION)
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

    /** Deserializes on-disk bytes. Returns null on miss, schema/geometry-version mismatch, or
     *  corruption — the single authority for whether a cached table is still usable. */
    fun decode(bytes: ByteArray): ChapterPaginationTable? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != MAGIC) return null
            if (buf.readInt() != VERSION) return null
            if (buf.readInt() != LAYOUT_VERSION) return null
            val chapterIndex = buf.readInt()
            val paramHash = buf.readLong()
            val totalBlocks = buf.readInt()
            val totalChars = buf.readInt()
            val pageCount = buf.readInt()
            if (pageCount < 0) return null
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
        }.getOrNull()
    }
}
