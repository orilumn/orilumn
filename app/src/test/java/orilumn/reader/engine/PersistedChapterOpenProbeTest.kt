package orilumn.reader.engine

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import okio.Path.Companion.toPath
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1 acceptance at controller level: a chapter computed once is persisted (tree + sheets +
 * structure); a fresh controller over the same cache root opens it with zero chapter-text IO
 * (the epub is never re-read for that chapter) and relinks identical structure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistedChapterOpenProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    private class CountingReader(files: Map<String, ByteArray>) : EpubResourceReader {
        private val inner = FakeEpubResourceReader(files)
        var chapterReads = 0
        override fun entries(): Sequence<String> = inner.entries()
        override fun readText(path: String): String? {
            if (path.endsWith(".xhtml") || path.endsWith(".html")) chapterReads++
            return inner.readText(path)
        }
        override fun readBytes(path: String): ByteArray? = inner.readBytes(path)
    }

    private lateinit var controller: BookDocumentController

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Before
    fun setUp() {
        cacheDir = temp.newFolder("cache")
    }

    private fun openWith(reader: EpubResourceReader): BookDocumentController {
        val c = BookDocumentController(
            reader = reader,
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        c.cacheRoot = cacheDir.absolutePath.toPath()
        c.diskCacheVersion = 18
        c.setViewport(viewW, viewH)
        return c
    }

    @Test
    fun `second open loads chapter with zero chapter-text IO`() = runBlocking {
        val files = OpenAnchorProbeEpilog.epubFiles()
        // First controller: full epub path, must converge the structure file.
        val first = CountingReader(files)
        controller = openWith(first)
        assertTrue(controller.open(23L, saved = null))
        assertNotNull(controller.ensureChapterLayout(0))
        val chFile = java.io.File(cacheDir, "structure/book_23/0.bin")
        assertTrue("first touch must persist the chapter file", chFile.isFile)
        assertTrue(first.chapterReads > 0)
        controller.close()

        // Second controller, same cache: chapter text must never be re-read.
        val second = CountingReader(files)
        val reopened = openWith(second)
        controller = reopened
        assertTrue(reopened.open(23L, saved = null))
        val unit = reopened.ensureChapterLayout(0)
        assertNotNull(unit)
        assertEquals("chapter text IO must be zero on relink open", 0, second.chapterReads)
        assertTrue(
            "relinked structure must carry leaves",
            (unit!!.let { reopenedChapterLeaves(reopened, 0) }),
        )
    }

    private fun reopenedChapterLeaves(c: BookDocumentController, chapter: Int): Boolean {
        // Structure is observable through behavior: a relinked chapter flips without computing.
        // Direct signal: the unit's cache was bound media-free (no cascade on this open).
        val field = c.javaClass.getDeclaredMethod("unitAt", Int::class.java).apply { isAccessible = true }
        val unit = field.invoke(c, chapter) as ChapterUnit
        return unit.structureCache.loadedMediaFree && unit.structureCache.leaves.isNotEmpty()
    }

    /** Shared epub fixture (single css-less chapter). */
    object OpenAnchorProbeEpilog {
        fun epubFiles(): Map<String, ByteArray> {
            val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
            val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Persist</dc:title><dc:identifier id="bookid">urn:test:persist</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
            val body = (1..40).joinToString("\n") { p -> "<p>Paragraph $p of the chapter.</p>" }
            return mapOf(
                "META-INF/container.xml" to container.toByteArray(),
                "OEBPS/package.opf" to opf.toByteArray(),
                "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray(),
            )
        }
    }
}
