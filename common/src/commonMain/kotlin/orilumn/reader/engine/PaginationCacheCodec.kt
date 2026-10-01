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
 * mismatch, when the table's [LAYOUT_VERSION] differs from the current engine geometry version, or
 * when the table's [appVersion] differs from the running build. **[LAYOUT_VERSION] is derived
 * automatically from engine source content** (see its KDoc) — it is *not* a hand-maintained counter,
 * so any change to how line geometry is computed (break width, margin folding, line height, char
 * offsets, …) invalidates old tables with no human bookkeeping; [appVersion] (the platform's
 * monotonic build number — Android `versionCode`, desktop `DISK_CACHE_VERSION`) remains the backstop
 * for everything the source fingerprint cannot see (a different Skia / system-font set installed
 * under an unchanged source tree, etc.): an upgrade with a new build number uniformly discards old
 * disk tables, with no per-site clearing. Old files are never migrated — the first read after an
 * upgrade misses and the chapter is reshaped under the new build.
 */
object PaginationCacheCodec {

    private const val MAGIC = 0x43505442

    /** File/schema version. Bump only when the on-disk byte layout changes. */
    private const val VERSION = 3 // 3: header gains appVersion (build-number backstop; v2 files miss on read)

    /**
     * **行几何版本 = 引擎源码指纹**（[LayoutGeometryStamp]），**不靠人手动 bump**。
     *
     * ## 为什么不手动 bump
     *
     * 本值曾经是一个手写的 `const val`（历史 18…31），靠「改了断行/度量算法就记得 +1」维持。
     * 这条纪律**必然漏**，而漏一次的后果是**静默**的：分页磁盘表的 key 是
     * [orilumn.reader.engine.text.LayoutParamKey.hash()]，只含**版面参数**、不含算法版本 ⇒
     * 漏 bump 时旧表继续命中，读者看到的是**上一版算法的分页结果**，日志里也只有
     * 一条正常的 `loaded from persist`，**没有任何异常信号**。
     * 2026-10-01 本轮就实况踩了一次：贪心加「往回退」改了行尾下标（页切点随之变），
     * 真机却仍命中旧表，修复看起来完全没生效。
     *
     * 现成的 [appVersion] 兜底（Android `versionCode` / 桌面 `DISK_CACHE_VERSION`）**救不了**：
     * 两者都是写死常量（`versionCode = 20` / `= 1`），同一 versionCode 下所有构建同值 ⇒
     * 只在**发版升级**时兜底，开发期与 CI 完全不触发。
     *
     * ## 现在怎么来的
     *
     * 根 `build.gradle.kts` 的 `generateLayoutGeometryStamp` 任务对
     * **引擎模块（`common` + `engine-skia`）的任一 `*Main` 文件内容**求 SHA-256，取前 4 字节
     * 生成本常量（源码在 [LayoutGeometryStamp]）。于是：
     *  - 引擎源码变了（断行、度量、样式层叠、盒模型…）⇒ 本值变 ⇒ 旧表在 [decode] 被拒 ⇒ 自动重排；
     *  - 引擎源码没变 ⇒ 本值不变 ⇒ 缓存跨构建存活（不是「每次构建都作废」）。
     *
     * 指纹取**粗粒度**（整模块 `*Main`）是刻意的：方向是宁可多作废、不可少作废；
     * 多作废在发版时本就要被 [appVersion] 全量作废一次，**生产额外成本为 0**。
     *
     * 仍与 [VERSION] 分开：schema（字节布局）变而几何不变时不必作废，反之亦然。
     */
    const val LAYOUT_VERSION = LayoutGeometryStamp.VALUE

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
            // 带上指纹：miss 时能直接对上「当前源码状态」，不用再回头猜是漏 bump 还是参数变了。
            if (buf.readInt() != LAYOUT_VERSION) {
                return decodeNull("layout-version have=${LAYOUT_VERSION.toUInt().toString(16)}/${LayoutGeometryStamp.DIGEST_PREFIX}")
            }
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
