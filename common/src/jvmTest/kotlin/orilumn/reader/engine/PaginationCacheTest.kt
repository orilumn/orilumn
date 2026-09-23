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
 * (magic/schema/geometry-version mismatch → null), and the per-book LRU cap tracking
 * last-USED (read refreshes recency, exactly like the Android `File` adapter).
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
        val read = PaginationCacheCodec.decode(PaginationCacheCodec.encode(sampleTable()))
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
        assertNull(PaginationCacheCodec.decode(ByteArray(0)))
        assertNull(PaginationCacheCodec.decode(ByteArray(8) { it.toByte() })) // invalid magic
        val wrongVersion = Buffer().apply {
            writeInt(0x43505442)
            writeInt(999) // schema VERSION mismatch
            writeInt(PaginationCacheCodec.LAYOUT_VERSION)
        }.readByteArray()
        assertNull(PaginationCacheCodec.decode(wrongVersion))
    }

    @Test
    fun `decode rejects an older engine-geometry version`() {
        // Guards the single cache-invalidation choke-point: a table whose engine-geometry version
        // differs from the current one must be rejected (treated as a miss → rebuilt uniformly).
        val stale = Buffer().apply {
            writeInt(0x43505442) // MAGIC
            writeInt(2) // current schema VERSION
            writeInt(0) // layoutVersion 0 ≠ current → must be rejected
        }.readByteArray()
        assertNull(PaginationCacheCodec.decode(stale))
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
    fun `store read refreshes last-used so LRU evicts the unread file`() {
        val s = store()
        val ns = "book_lru"
        val onePage = listOf(PageSlice(0, 50, 0, 7, kind = PageSlice.Kind.TEXT, blockStart = 0, blockEndExclusive = 2))
        val fOld = s.file(ns, 1, 1L)
        val fUnread = s.file(ns, 2, 2L)
        s.write(ChapterPaginationTable.fromSlices(1, 1L, onePage, 2, 50), fOld)
        s.write(ChapterPaginationTable.fromSlices(2, 2L, onePage, 2, 50), fUnread)
        // Artificially age so recency is unambiguous.
        java.io.File(fOld.toString()).setLastModified(1_000L)
        java.io.File(fUnread.toString()).setLastModified(2_000L)

        // Reading the OLD file bumps its recency above the unread-newer one — LRU is last USED.
        assertNotNull(s.read(fOld))
        val oldMtime = FileSystem.SYSTEM.metadataOrNull(fOld)?.lastModifiedAtMillis ?: 0L
        val unreadMtime = FileSystem.SYSTEM.metadataOrNull(fUnread)?.lastModifiedAtMillis ?: 0L
        assertTrue("read must refresh last-used so old-but-used tables outrank unread ones", oldMtime > unreadMtime)

        // Push past the cap: peak 2 + 31 = 33 files → one eviction down to the 32 cap.
        repeat(31) { i ->
            val ch = 10 + i
            s.write(
                ChapterPaginationTable.fromSlices(ch, ch.toLong(), onePage, 2, 50),
                s.file(ns, ch, ch.toLong()),
            )
        }

        val bins = FileSystem.SYSTEM.list(s.dirFor(ns)).filter { it.name.endsWith(".bin") }
        assertEquals("orphaned-param-hash files stay bounded by the per-book cap", 32, bins.size)
        assertFalse("the least-recently-USED file is evicted", FileSystem.SYSTEM.exists(fUnread))
        assertTrue("a recently-read file survives eviction", FileSystem.SYSTEM.exists(fOld))
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
}
