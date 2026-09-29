package orilumn.reader.engine

import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Import-built chapter-structure persistence: codec round-trip, single-authority invalidation
 * (magic/schema/cascade-version/css/chapter/truncation → null), store overwrite-in-place.
 */
class ChapterStructurePersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample() = PersistedChapterStructure(
        chapterIndex = 58,
        cssHash = 0xabcdefL,
        leafPaths = listOf(intArrayOf(0, 1), intArrayOf(0, 3, 2), intArrayOf(2)),
        charStarts = longArrayOf(0L, 406L, 1167L),
        bgOwners = mapOf(1 to intArrayOf(0)),
        avoidOwners = mapOf(2 to intArrayOf(2, 0)),
        genStrings = mapOf(0 to ("«" to null), 2 to (null to "»")),
    )

    @Test
    fun `codec round-trips paths starts owners and gen strings`() {
        val read = ChapterStructureCodec.decode(ChapterStructureCodec.encode(sample()))
        assertNotNull(read)
        val p = read!!
        assertEquals(58, p.chapterIndex)
        assertEquals(0xabcdefL, p.cssHash)
        assertEquals(3, p.leafPaths.size)
        assertArrayEquals(intArrayOf(0, 3, 2), p.leafPaths[1])
        assertArrayEquals(longArrayOf(0L, 406L, 1167L), p.charStarts)
        assertArrayEquals(intArrayOf(0), p.bgOwners[1])
        assertArrayEquals(intArrayOf(2, 0), p.avoidOwners[2])
        assertEquals("«" to null, p.genStrings[0])
        assertEquals(null to "»", p.genStrings[2])
    }

    @Test
    fun `decode rejects magic version cascade css chapter truncation trailing`() {
        val good = ChapterStructureCodec.encode(sample())
        assertNull(ChapterStructureCodec.decode(ByteArray(0)))
        assertNull(ChapterStructureCodec.decode(ByteArray(8) { it.toByte() }))
        fun patched(offsetInts: Int, value: Int): ByteArray {
            val b = good.copyOf()
            Buffer().apply { writeInt(value) }.readByteArray().copyInto(b, offsetInts * 4)
            return b
        }
        assertNull(ChapterStructureCodec.decode(patched(0, 0))) // magic
        assertNull(ChapterStructureCodec.decode(patched(1, 999))) // schema VERSION
        assertNull(ChapterStructureCodec.decode(patched(2, 999))) // STRUCTURE_VERSION
        assertNull(ChapterStructureCodec.decode(good.copyOfRange(0, good.size - 3))) // truncation
        assertNull(ChapterStructureCodec.decode(good + byteArrayOf(1.toByte()))) // trailing bytes
    }

    @Test
    fun `cssHash separates text lists and filename is chapter only`() {
        val a = ChapterStructureCodec.cssHashOf(listOf("p{color:red}", "h1{}"))
        val b = ChapterStructureCodec.cssHashOf(listOf("p{color:red}", "h1{} "))
        val c = ChapterStructureCodec.cssHashOf(listOf("p{color:red}", "h1{}"))
        assertEquals(a, c)
        assert(a != b)
        assertEquals("58.bin", ChapterStructureCodec.filename(58))
    }

    @Test
    fun `store write read overwrite and chapter guard`() {
        val s = ChapterStructureStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath())
        assertNull(s.read("book_33", 58))
        s.write("book_33", sample())
        assertNotNull(s.read("book_33", 58))
        // Same-name overwrite (stale content can never accumulate).
        val v2 = sample().copy(cssHash = 1L)
        s.write("book_33", v2)
        assertEquals(1L, s.read("book_33", 58)!!.cssHash)
        // Chapter-index guard: content smuggled under the wrong filename is rejected.
        val smuggled = s.file("book_33", 59)
        FileSystem.SYSTEM.write(smuggled) { write(ChapterStructureCodec.encode(sample())) }
        assertNull(s.read("book_33", 59))
    }
}
