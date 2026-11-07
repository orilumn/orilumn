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

    /** Filename convention: `<chapterIndex>_<paramHash>.bin`. */
    fun filename(chapterIndex: Int, paramHash: Long): String =
        "${chapterIndex}_${paramHash}.bin"

    /**
     * 全书各章页数快照的文件名：`counts_<paramHash>.idx`（与分页表同目录）。
     *
     * 为什么单独存一份「页数」而不是靠扫表：全书累计页码要的是**前面每一章**的页数，
     * 扫表得逐章开文件读表头（七百多章 = 七百多次 IO，每次几十字节，实测补齐一次 1.2 s 起）；
     * 记一份几十字节/章的清单，一次读几 KB（762 章 6.1 KB）就拿到全书页数。
     *
     * **失效口径与表完全相同**（见 [decodePageCounts]）：参数指纹 / 引擎几何指纹 / 构建号
     * 任一不符即整份作废。所以「排版改了还拿旧页码」不会发生——清单不会比表活得更久。
     * 单张表文件损坏/丢失不影响它，只是附带性质（同参数下页数是确定的事实），不是存它的理由。
     */
    fun countsFilename(paramHash: Long): String = "counts_$paramHash.idx"

    /** 页数快照的 magic（与表文件的 MAGIC 故意不同：两者永不被彼此的解析器接受）。 */
    private const val COUNTS_MAGIC = 0x43504E43 // "CPNC"

    /** 页数快照表头字节数：magic/version/layoutVersion/appVersion 四个 Int + paramHash 一个 Long + count 一个 Int。 */
    const val COUNTS_HEADER_BYTES = 28

    /**
     * 序列化整本「章号 → 页数」快照（含排完是 0 页的空章）。
     *
     * 布局：[magic][VERSION][LAYOUT_VERSION][appVersion][paramHash: Long][count][(chapter, pages) × count]。
     * 校验口径与表文件**同序同判**：源码几何指纹 + 构建号 + 参数指纹任一不符即整份作废
     * （宁可重扫一遍表头，也不拿旧结论凑页码）。0 页合法（`pages >= 0`）。
     */
    fun encodePageCounts(paramHash: Long, counts: Map<Int, Int>, appVersion: Int): ByteArray {
        val buf = Buffer()
        buf.writeInt(COUNTS_MAGIC)
        buf.writeInt(VERSION)
        buf.writeInt(LAYOUT_VERSION)
        buf.writeInt(appVersion)
        buf.writeLong(paramHash)
        buf.writeInt(counts.size)
        for ((chapter, pages) in counts) {
            buf.writeInt(chapter)
            buf.writeInt(pages)
        }
        return buf.readByteArray()
    }

    /** 解页数快照；任何校验不过（含截断）返回 null。刻意不落日志：扫不到的常态不该刷屏。 */
    fun decodePageCounts(
        bytes: ByteArray,
        expectedAppVersion: Int,
        expectedParamHash: Long,
    ): Map<Int, Int>? {
        if (bytes.size < COUNTS_HEADER_BYTES) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != COUNTS_MAGIC) return null
            if (buf.readInt() != VERSION) return null
            if (buf.readInt() != LAYOUT_VERSION) return null
            if (buf.readInt() != expectedAppVersion) return null
            if (buf.readLong() != expectedParamHash) return null
            val count = buf.readInt()
            if (count < 0 || bytes.size.toLong() != COUNTS_HEADER_BYTES + count * 8L) return null
            val out = LinkedHashMap<Int, Int>(count)
            repeat(count) {
                val chapter = buf.readInt()
                val pages = buf.readInt()
                if (chapter < 0 || pages < 0) return null
                out[chapter] = pages
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 表头固定字节数（见上面的文件格式：magic/version/layoutVersion/appVersion/chapterIndex 五个
     *  Int + paramHash 一个 Long + totalBlocks/totalChars/pageCount 三个 Int = 40）。 */
    const val HEADER_BYTES = 40

    /**
     * 只解表头取页数（全书累计页码求和用）：命中条件与 [decode] **同序同判**（magic、schema、
     * 引擎几何指纹、构建号、章号、参数指纹），只额外少读页记录本身。
     *
     * 刻意**不落日志**：整书扫描一次要读几十上百个文件，「当前参数下这一章还没表」是常态而非
     * 异常，逐条打日志只会把真正的 miss（[decode]）淹掉。
     *
     * @param bytes 文件前 [HEADER_BYTES] 字节（多传无妨，只读表头）。
     * @return 页数（≥ 0）；任何一项校验不过或字节不足返回 null。
     */
    fun decodeHeaderPageCount(
        bytes: ByteArray,
        expectedAppVersion: Int,
        expectedChapterIndex: Int,
        expectedParamHash: Long,
    ): Int? {
        if (bytes.size < HEADER_BYTES) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != MAGIC) return null
            if (buf.readInt() != VERSION) return null
            if (buf.readInt() != LAYOUT_VERSION) return null
            if (buf.readInt() != expectedAppVersion) return null
            if (buf.readInt() != expectedChapterIndex) return null
            if (buf.readLong() != expectedParamHash) return null
            buf.readInt() // totalBlocks
            buf.readInt() // totalChars
            buf.readInt().takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

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
