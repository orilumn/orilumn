package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.revision.TextRevisionStore
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.ChapterStructureStore
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 有修订、但本章**还没有持久化结构**（首次导入 / 改过版心后的媒体章）时的载入。
 *
 * 重放修订要结构里的叶表，老路径只能「先按原文显示，等下次开书再说」——读者的观感就是
 * 「改的字没变」；更糟的是修订表已载入，标注 / 选区按修订表换算，正文却还是原文，
 * 一串批注整体错位（真机复报）。
 *
 * 现在载入时缺结构就**当场**用原文算一遍结构（排版本来也要付这一笔）并落盘，随后同一棵
 * 树的内存副本上重放；结构落盘的是原文那份，下次冷开书载入结构再重放，不会套两遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevisionWithoutPersistedStructureProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheRoot: okio.Path
    private val bookId = 41L
    private val viewW = 720
    private val viewH = 1280

    private val original = "你好世界"
    private val edited = "Hello, world"
    private val revision = TextRevision(
        id = 1,
        bookId = bookId,
        chapter = 1,
        charStart = 0,
        charEnd = original.length,
        text = edited,
        originalText = original,
        editedText = edited,
        delta = edited.length - original.length,
        groupId = 1,
    )

    @Before
    fun setUp() {
        cacheRoot = temp.newFolder("cache").absolutePath.toPath()
        controller = newController()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `revision is replayed when the chapter had no persisted structure`() = runBlocking {
        controller.textRevisionStore = FakeRevisionStore(listOf(revision))
        controller.open(bookId, saved = null)
        controller.setViewport(viewW, viewH)
        controller.reloadTextRevisions()

        controller.openChapterStart(1) ?: error("no ch1 head")

        val text = controller.chapterText(1) ?: error("no ch1 text")
        assertTrue("改后的字必须出现在正文里：$text", text.contains(edited))
        assertFalse("原文里那一处必须已被替换掉：$text", text.contains(original))
        assertEquals("改完之后的偏移整体后移", 0, controller.renderedOffset(1, 0))
        assertEquals(edited.length, controller.renderedOffset(1, original.length))

        // 结构已按原文落盘：下次冷开书直接「载入结构 + 重放」，不必再算一遍。
        val file = ChapterStructureStore(FileSystem.SYSTEM, cacheRoot).readChapter("book_$bookId", 1)
        assertNotNull("缺结构时补算的结构必须落盘（否则每次开书都要重算）", file)
    }

    @Test
    fun `revision is not applied twice on the next open`() = runBlocking {
        controller.textRevisionStore = FakeRevisionStore(listOf(revision))
        controller.open(bookId, saved = null)
        controller.setViewport(viewW, viewH)
        controller.reloadTextRevisions()
        controller.openChapterStart(1) ?: error("no ch1 head")
        controller.close()

        // 同一缓存目录再开一次：结构已在盘上，走的是「载入结构 + 重放」那条正路。
        controller = newController()
        controller.textRevisionStore = FakeRevisionStore(listOf(revision))
        controller.open(bookId, saved = null)
        controller.setViewport(viewW, viewH)
        controller.reloadTextRevisions()
        controller.openChapterStart(1) ?: error("no ch1 head")

        val unit = controller.unitAt(1) ?: error("no ch1")
        assertTrue("重开必须从持久化结构重放修订", unit.hasTextRevisions)
        val firstLeaf = textOf(unit.structureCache.leaves.firstOrNull() ?: error("no leaf"))
        assertEquals("修订只能套一次（重放的是原文，不是上一轮的产物）", edited, firstLeaf)

        // 结构文件里落的必须是**原文**：烧进修订的话，下次开书会再套一遍。
        val file = ChapterStructureStore(FileSystem.SYSTEM, cacheRoot).readChapter("book_$bookId", 1)
        assertNotNull("结构必须已落盘", file)
        val persisted = textOf(file!!.tree)
        assertTrue("结构里应是原书文字", persisted.contains(original))
        assertFalse("修订不许烧进结构", persisted.contains(edited))
    }

    private fun textOf(el: MarkupElement): String {
        if (el.isText) return el.text
        return el.children.joinToString("") { textOf(it) }
    }

    private fun newController() = BookDocumentController(
        reader = FakeEpubResourceReader(epubFiles()),
        layouter = BoxChapterLayouter(),
        profile = TypographicProfile.build(ReaderSettings.DEFAULT),
    ).apply { cacheRoot = this@RevisionWithoutPersistedStructureProbeTest.cacheRoot }

    private class FakeRevisionStore(private val rows: List<TextRevision>) : TextRevisionStore {
        override suspend fun textRevisionsForChapter(bookId: Long, chapter: Int): List<TextRevision> =
            rows.filter { it.bookId == bookId && it.chapter == chapter }

        override suspend fun textRevisionsForBook(bookId: Long): List<TextRevision> =
            rows.filter { it.bookId == bookId }

        override suspend fun replaceTextRevisions(
            deletedIds: List<Long>,
            inserted: List<TextRevision>,
        ): List<TextRevision> = error("not used")

        override suspend fun fillLegacyRevisionSnapshot(id: Long, originalText: String, editedText: String) =
            Unit

        override suspend fun deleteTextRevision(id: Long) = Unit

        override suspend fun deleteTextRevisionsForBook(bookId: Long) = Unit
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>REV</dc:title><dc:identifier id="bookid">urn:test:revision-fresh-structure</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c0" href="Text/c0.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c0"/><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val filler = (1..40).joinToString("\n") { p ->
            "<p>第 $p 段，用来测量断行与分页的填充文字，多写一点让它跨行。</p>"
        }
        val chapters = mapOf(
            "OEBPS/Text/c0.xhtml" to "<html><body><h1>前言</h1><p>无关内容。</p></body></html>",
            // 第一叶就是被改的那一句 ⇒ 修订区间 = [0, 4)，不依赖任何偏移推算。
            "OEBPS/Text/c1.xhtml" to "<html><body><p>$original</p>$filler</body></html>",
            "OEBPS/Text/c2.xhtml" to "<html><body><h1>后记</h1><p>无关内容。</p></body></html>",
        ).mapValues { it.value.toByteArray() }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }
}
