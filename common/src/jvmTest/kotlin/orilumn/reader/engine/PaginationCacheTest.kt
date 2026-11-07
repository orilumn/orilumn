package orilumn.reader.engine

import orilumn.reader.engine.paging.PageSlice
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C1-1: shared pagination-table codec + okio store.
 *
 * Guards the single-source on-disk format (byte-identical to the retired app `DataInput/Output`
 * layout so pre-C1-1 caches stay readable), the single cache-invalidation choke-point
 * (magic/schema/geometry-version mismatch → null), and the read-time recency touch
 * (no per-book count cap: tables are tens of bytes and every chapter should stay a disk hit).
 */
class PaginationCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleTable() = ChapterPaginationTable.fromSlices(
        chapterIndex = 7,
        paramHash = 0x1234567890abL,
        slices = listOf(
            PageSlice(0, 100, 0, 12, kind = PageSlice.Kind.TEXT, blockStart = 0, blockEndExclusive = 3),
            PageSlice(100, 220, 12, 26, kind = PageSlice.Kind.TEXT, blockStart = 3, blockEndExclusive = 6),
            PageSlice(220, 340, 26, 38, kind = PageSlice.Kind.TEXT, blockStart = 6, blockEndExclusive = 9),
        ),
        totalBlocks = 9,
        totalChars = 340,
    )

    private fun store(): PaginationCacheStore =
        PaginationCacheStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath())

    @Test
    fun `codec round-trips all fields`() {
        val read = PaginationCacheCodec.decode(PaginationCacheCodec.encode(sampleTable(), 18), 18)
        assertNotNull(read)
        val t = read!!
        assertEquals(7, t.chapterIndex)
        assertEquals(0x1234567890abL, t.paramHash)
        assertEquals(9, t.totalBlocks)
        assertEquals(340, t.totalChars)
        assertEquals(3, t.totalPages)
        assertEquals(0, t.pages[0].charStart)
        assertEquals(100, t.pages[0].charEnd)
        assertEquals(0, t.pages[0].blockStart)
        assertEquals(3, t.pages[0].blockEndExclusive)
        assertEquals(3, t.pages[1].blockStart)
        assertEquals(6, t.pages[1].blockEndExclusive)
    }

    @Test
    fun `codec filename convention is chapter_paramHash_bin`() {
        assertEquals("7_${0x1234567890abL}.bin", PaginationCacheCodec.filename(7, 0x1234567890abL))
    }

    @Test
    fun `decode rejects empty-corrupted-and-wrong-version bytes`() {
        assertNull(PaginationCacheCodec.decode(ByteArray(0), 18))
        assertNull(PaginationCacheCodec.decode(ByteArray(8) { it.toByte() }, 18)) // invalid magic
        val wrongVersion = Buffer().apply {
            writeInt(0x43505442)
            writeInt(999) // schema VERSION mismatch
            writeInt(PaginationCacheCodec.LAYOUT_VERSION)
        }.readByteArray()
        assertNull(PaginationCacheCodec.decode(wrongVersion, 18))
    }

    @Test
    fun `decode rejects an older engine-geometry version`() {
        // Guards the single cache-invalidation choke-point: a table whose engine-geometry version
        // differs from the current one must be rejected (treated as a miss → rebuilt uniformly).
        val stale = Buffer().apply {
            writeInt(0x43505442) // MAGIC
            writeInt(3) // current schema VERSION
            // LAYOUT_VERSION 现在是源码指纹（见 PaginationCacheCodec），用 +1 保证「一定不等」，
            // 别写死 0 —— 指纹理论上可能正好是 0，那样这条锁会因为错误的原因通过/失败。
            writeInt(PaginationCacheCodec.LAYOUT_VERSION + 1) // ≠ current → must be rejected
            writeInt(18) // appVersion matches — rejection must come from the geometry gate
        }.readByteArray()
        assertNull(PaginationCacheCodec.decode(stale, 18))
    }

    @Test
    fun `decode rejects a table stamped by another build`() {
        // Build-number backstop: an upgrade with a new versionCode uniformly discards old tables,
        // even when schema + geometry versions match.
        val bytes = PaginationCacheCodec.encode(sampleTable(), 17)
        assertNull(PaginationCacheCodec.decode(bytes, 18))
        assertNotNull(PaginationCacheCodec.decode(bytes, 17))
    }

    @Test
    fun `store isolates builds and sweepStale deletes only stale files`() {
        val oldStore = PaginationCacheStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath(), 17)
        val newStore = PaginationCacheStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath(), 18)
        val ns = "book_sweep"
        val fOld = oldStore.file(ns, 1, 111L)
        oldStore.write(sampleTable(), fOld)
        val fNew = newStore.file(ns, 2, 222L)
        newStore.write(sampleTable(), fNew)
        // The new build cannot read the old build's table (upgrade misses → reshapes).
        assertNull(newStore.read(fOld))
        assertNotNull(newStore.read(fNew))
        assertEquals(1, newStore.sweepStale(ns))
        assertFalse(FileSystem.SYSTEM.exists(fOld))
        assertTrue(FileSystem.SYSTEM.exists(fNew))
        // Sweep is idempotent: a second pass finds nothing stale.
        assertEquals(0, newStore.sweepStale(ns))
    }

    @Test
    fun `store write then read round-trips and missing returns null`() {
        val s = store()
        val f = s.file("book_5", 7, 0x1234567890abL)
        s.write(sampleTable(), f)
        assertTrue(FileSystem.SYSTEM.exists(s.dirFor("book_5")))
        val read = s.read(f)
        assertNotNull(read)
        assertEquals(340, read!!.totalChars)
        assertNull(s.read(s.file("book_5", 0, 1L)))
    }

    @Test
    fun `store read refreshes last-used timestamp`() {
        val s = store()
        val ns = "book_touch"
        val onePage = listOf(PageSlice(0, 50, 0, 7, kind = PageSlice.Kind.TEXT, blockStart = 0, blockEndExclusive = 2))
        val fOld = s.file(ns, 1, 1L)
        val fOther = s.file(ns, 2, 2L)
        s.write(ChapterPaginationTable.fromSlices(1, 1L, onePage, 2, 50), fOld)
        s.write(ChapterPaginationTable.fromSlices(2, 2L, onePage, 2, 50), fOther)
        // Artificially age so recency is unambiguous.
        java.io.File(fOld.toString()).setLastModified(1_000L)
        java.io.File(fOther.toString()).setLastModified(2_000L)

        // Reading bumps recency (last USED, not last written).
        assertNotNull(s.read(fOld))
        val oldMtime = FileSystem.SYSTEM.metadataOrNull(fOld)?.lastModifiedAtMillis ?: 0L
        val otherMtime = FileSystem.SYSTEM.metadataOrNull(fOther)?.lastModifiedAtMillis ?: 0L
        assertTrue("read must refresh last-used", oldMtime > otherMtime)
    }

    @Test
    fun `store keeps every chapter table (no per-book count cap)`() {
        // 表只有几十到二百字节（实测 256 张共 36 KB），长书要靠它们开书即磁盘命中。
        // 之前按每本书留 256 张裁剪，762 章的书页码前缀就断在裁剪点上。
        val s = store()
        val ns = "book_big"
        val onePage = listOf(PageSlice(0, 50, 0, 7, kind = PageSlice.Kind.TEXT, blockStart = 0, blockEndExclusive = 2))
        repeat(600) { i ->
            s.write(
                ChapterPaginationTable.fromSlices(i, 1L, onePage, 2, 50),
                s.file(ns, i, 1L),
            )
        }
        assertEquals(
            "every chapter keeps its table",
            600,
            FileSystem.SYSTEM.list(s.dirFor(ns)).count { it.name.endsWith(".bin") },
        )
    }

    @Test
    fun `readPageCount reads only the header and honours the same gates as read`() {
        // 全书累计页码要按「章 → 页数」扫全书的表头。判据必须与 read 同源（构建号/引擎指纹/
        // 章号/参数指纹），否则会把别人的表当成自己的页数，加出一个错的总页数。
        val s = store()
        val ns = "book_hdr"
        val f = s.file(ns, 7, 0x1234567890abL)
        s.write(sampleTable(), f)
        assertEquals(3, s.readPageCount(ns, 7, 0x1234567890abL))
        assertNull("章号对不上 = 别人的表", s.readPageCount(ns, 8, 0x1234567890abL))
        assertNull("参数指纹对不上 = 旧版式的表", s.readPageCount(ns, 7, 0x1L))
        assertNull("没有文件", s.readPageCount(ns, 0, 1L))

        // 另一构建写的表：页数读不到；该构建自己读得到。
        val oldStore = PaginationCacheStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath(), 17)
        val ns2 = "book_hdr_old"
        oldStore.write(
            ChapterPaginationTable.fromSlices(1, 5L, sampleTable().pages.map {
                PageSlice(it.charStart, it.charEnd, 0, 0, kind = PageSlice.Kind.TEXT, blockStart = it.blockStart, blockEndExclusive = it.blockEndExclusive)
            }, 9, 340),
            oldStore.file(ns2, 1, 5L),
        )
        assertNull(s.readPageCount(ns2, 1, 5L))
        assertEquals(3, oldStore.readPageCount(ns2, 1, 5L))
    }

    @Test
    fun `readPageCount does not rewrite the file so recency is untouched`() {
        // read() 会重写整份文件来刷新「最后使用」时间；页数扫描不能顺手把全书每章都重写一遍。
        val s = store()
        val ns = "book_hdr_lru"
        val f = s.file(ns, 3, 42L)
        s.write(ChapterPaginationTable.fromSlices(3, 42L, sampleTable().pages.map {
            PageSlice(it.charStart, it.charEnd, 0, 0, kind = PageSlice.Kind.TEXT, blockStart = it.blockStart, blockEndExclusive = it.blockEndExclusive)
        }, 9, 340), f)
        java.io.File(f.toString()).setLastModified(1_234_000L)
        assertEquals(3, s.readPageCount(ns, 3, 42L))
        assertEquals(1_234_000L, FileSystem.SYSTEM.metadataOrNull(f)?.lastModifiedAtMillis)
    }

    @Test
    fun `page counts snapshot round-trips and is gated like tables`() {
        // 全书页码要的是「前面每一章几页」。表文件会被清掉，页数不会变——于是单独存一份清单，
        // 校验口径必须与表一致：引擎几何指纹 + 构建号 + 参数指纹，任一不符整份作废。
        val counts = linkedMapOf(0 to 1, 1 to 0, 2 to 6, 3 to 12) // 第 1 章排完是 0 页：合法
        val bytes = PaginationCacheCodec.encodePageCounts(77L, counts, 18)
        assertEquals(counts, PaginationCacheCodec.decodePageCounts(bytes, 18, 77L))
        assertNull("参数指纹对不上 = 旧版式的清单", PaginationCacheCodec.decodePageCounts(bytes, 18, 78L))
        assertNull("别的构建写的清单", PaginationCacheCodec.decodePageCounts(bytes, 17, 77L))
        assertNull("截断的清单（半截写盘）不当真", PaginationCacheCodec.decodePageCounts(bytes.copyOf(bytes.size - 3), 18, 77L))
        assertNull("空字节", PaginationCacheCodec.decodePageCounts(ByteArray(0), 18, 77L))
        // 乱改一个字节（页数）不该被当成合法清单：完整性靠长度 + 表头校验，且不解析越界。
        val tampered = bytes.copyOf()
        tampered[tampered.size - 1] = 0xFF.toByte()
        val read = PaginationCacheCodec.decodePageCounts(tampered, 18, 77L)
        assertTrue("越界页数被拒或按原值解析，不得抛异常", read == null || read[3] != null)
    }

    @Test
    fun `page counts snapshot survives table file loss`() {
        // 页数清单不靠表文件存活：表会被清缓存/坏文件删掉，页数是同参数下不变的小事实。
        val s = store()
        val ns = "book_counts"
        val onePage = listOf(PageSlice(0, 50, 0, 7, kind = PageSlice.Kind.TEXT, blockStart = 0, blockEndExclusive = 2))
        s.writePageCounts(ns, 9L, mapOf(0 to 1, 1 to 5, 2 to 0))
        repeat(8) { i ->
            s.write(
                ChapterPaginationTable.fromSlices(i, 9L, onePage, 2, 50),
                s.file(ns, i, 9L),
            )
        }
        for (f in FileSystem.SYSTEM.list(s.dirFor(ns)).filter { it.name.endsWith(".bin") }) {
            FileSystem.SYSTEM.delete(f)
        }
        assertEquals(mapOf(0 to 1, 1 to 5, 2 to 0), s.readPageCounts(ns, 9L))
    }

    @Test
    fun `cleanStaleParams drops other-hash page counts but keeps the running hash`() {
        val s = store()
        val ns = "book_counts_stale"
        s.writePageCounts(ns, 222L, mapOf(0 to 1))
        s.writePageCounts(ns, 111L, mapOf(0 to 2))
        assertEquals(1, s.cleanStaleParams(ns, 222L))
        assertEquals(mapOf(0 to 1), s.readPageCounts(ns, 222L))
        assertNull(s.readPageCounts(ns, 111L))
    }

    @Test
    fun `store clearBook removes all entries`() {
        val s = store()
        val ns = "book_11"
        s.write(sampleTable(), s.file(ns, 1, 111L))
        s.write(sampleTable(), s.file(ns, 2, 222L))
        s.clearBook(ns)
        assertEquals(0, FileSystem.SYSTEM.list(s.dirFor(ns)).size)
    }

    @Test
    fun `cleanStaleParams deletes only other-hash tables and keeps the running hash`() {
        // 调参/字体变更后旧指纹表是孤儿（读路径按当前指纹寻址永远命中不到），
        // 但同指纹表是有效缓存（参数回退即命中）——清理必须按指纹区分，不能全清。
        val s = store()
        val ns = "book_stale"
        val fOld = s.file(ns, 1, 111L)
        s.write(sampleTable(), fOld)
        val fKeep = s.file(ns, 2, 222L)
        s.write(sampleTable(), fKeep)
        // 负 hash：文件名后缀匹配必须按 "_<hash>.bin" 整段比对，
        // 防 "3_142.bin" 误配 "_42.bin" 一类的前缀/子串误伤。
        val fNeg = s.file(ns, 3, -42L)
        s.write(sampleTable(), fNeg)
        val fNear = s.file(ns, 4, 142L)
        s.write(sampleTable(), fNear)

        // 保留 222：其余三个指纹（111 / 负 hash -42 / 近似的 142）全成孤儿删除。
        assertEquals(3, s.cleanStaleParams(ns, 222L))
        assertFalse(FileSystem.SYSTEM.exists(fOld))
        assertTrue(FileSystem.SYSTEM.exists(fKeep))
        assertFalse(FileSystem.SYSTEM.exists(fNeg))
        assertFalse(FileSystem.SYSTEM.exists(fNear))
        // 幂等：二轮无旧指纹文件可删。
        assertEquals(0, s.cleanStaleParams(ns, 222L))

        // 换保留指纹为负 hash："3_-42.bin" 精确命中保留；
        // "4_142.bin" 不得因「以 42 结尾」误配 "_-42.bin"。
        s.write(sampleTable(), fOld)
        s.write(sampleTable(), fNear)
        s.write(sampleTable(), fNeg)
        assertEquals(3, s.cleanStaleParams(ns, -42L))
        assertFalse(FileSystem.SYSTEM.exists(fOld))
        assertFalse(FileSystem.SYSTEM.exists(fKeep))
        assertTrue(FileSystem.SYSTEM.exists(fNeg))
        assertFalse(FileSystem.SYSTEM.exists(fNear))
        // 书目录不存在时安全空转。
        assertEquals(0, s.cleanStaleParams("book_absent", 222L))
    }
}
