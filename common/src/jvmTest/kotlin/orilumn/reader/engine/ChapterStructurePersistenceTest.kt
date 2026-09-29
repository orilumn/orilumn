package orilumn.reader.engine

import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import orilumn.reader.engine.html.MarkupElement
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
    fun `store chapter file round-trips and overwrites in place`() {
        val s = ChapterStructureStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath())
        val tree = MarkupElement("body", children = listOf(
            MarkupElement("p", children = listOf(MarkupElement("#text", text = "hi"))),
        ))
        val file = PersistedChapterFile(58, 0xabcdefL, listOf(11L), listOf("ch.html"), tree, sample())
        assertNull(s.readChapter("book_33", 58))
        s.writeChapter("book_33", file)
        val read = s.readChapter("book_33", 58)
        assertNotNull(read)
        assertEquals(listOf(11L), read!!.sheetHashes)
        assertEquals(listOf("ch.html"), read.baseHrefs)
        assertEquals("hi", read.tree.children[0].children[0].text)
        assertEquals(3, read.structure.leafPaths.size)
        // Same-name overwrite (stale content can never accumulate).
        s.writeChapter("book_33", file.copy(cssHash = 1L, structure = sample().copy(cssHash = 1L)))
        assertEquals(1L, s.readChapter("book_33", 58)!!.cssHash)
        // Chapter-index guard: content smuggled under the wrong filename is rejected.
        FileSystem.SYSTEM.write(s.file("book_33", 59)) {
            write(ChapterStructureCodec.encodeFile(file))
        }
        assertNull(s.readChapter("book_33", 59))
    }

    @Test
    fun `store sheets merge unions without loss`() {
        val s = ChapterStructureStore(FileSystem.SYSTEM, tmp.root.absolutePath.toPath())
        assertNull(s.readSheets("book_33"))
        s.mergeSheets("book_33", mapOf(1L to "p{}"))
        s.mergeSheets("book_33", mapOf(2L to "h1{}", 1L to "p{}"))
        assertEquals(mapOf(1L to "p{}", 2L to "h1{}"), s.readSheets("book_33"))
    }

    @Test
    fun `file codec round-trips tree with attrs and rejects v1 bytes`() {
        val tree = MarkupElement(
            "body", mapOf("class" to "calibre"), listOf(
                MarkupElement("h2", children = listOf(MarkupElement("#text", text = "第五十七章"))),
                MarkupElement("p", mapOf("class" to "calibre1", "style" to "color:red"), children = listOf(
                    MarkupElement("#text", text = "正文 mixed text"),
                    MarkupElement("br"),
                    MarkupElement("span", children = listOf(MarkupElement("#text", text = "旁白"))),
                )),
            ),
        )
        val file = PersistedChapterFile(7, 42L, listOf(1L, 2L), listOf("a", "b"), tree, sample().copy(chapterIndex = 7, cssHash = 42L))
        val read = ChapterStructureCodec.decodeFile(ChapterStructureCodec.encodeFile(file))
        assertNotNull(read)
        assertEquals(42L, read!!.cssHash)
        assertEquals(listOf(1L, 2L), read.sheetHashes)
        fun flat(n: MarkupElement, acc: MutableList<String>) {
            acc.add("${n.tag}|${n.attrs}|${n.text}")
            n.children.forEach { flat(it, acc) }
        }
        val want = ArrayList<String>()
        val got = ArrayList<String>()
        flat(tree, want)
        flat(read.tree, got)
        assertEquals(want, got)
        // v1 structure-only bytes are rejected by the file gate (recomputed on demand).
        assertNull(ChapterStructureCodec.decodeFile(ChapterStructureCodec.encode(sample())))
    }
}
