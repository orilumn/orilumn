package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.revision.TextRevisionStore
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审阅视图的叶尾插入：整段引用句（`‘…’` → `“…”`）的收尾引号落在叶/段边界上时，
 * **必须有一个叶收下它**。
 *
 * 回归背景：叶内插入的归属判据曾是 `leafStart <= at < leafEnd`，下一叶按
 * `revisionsTouching` 又看不到 `charEnd == leafStart` 的修订 ⇒ 边界上的那一格两叶都不落，
 * 审阅正文比坐标换算表少字。真机表现：ch245 页首「李长寿…」在审阅空间是 3191，
 * 换算回正常空间却得到 3165（上一段末尾的「。」），切一次视图页首就退一行。
 *
 * 这里用「整段引用句 + 后面还有几段」复现：修好后审阅正文多出两格（开引号 + 收引号），
 * 且每个块尾换算回原书空间都与正常视图的块尾一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewLeafBoundaryInsertionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val store = FakeRevisionStore()
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
        controller.textRevisionStore = store
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `closing quote at a leaf end is not dropped and block ends map back`() = runBlocking {
        assertTrue(controller.open(7L, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0) ?: error("no normal layout")

        val normalText = controller.chapterText(0) ?: error("no normal text")
        val normalEnds = controller.chapterTextBlockEnds(0) ?: error("no normal block ends")
        val quoted = "‘重点句子。’"
        val at = normalText.indexOf(quoted)
        assertTrue("fixture paragraph missing", at >= 0)

        // 整段引用句换成弯引号：两端各插入一格，收引号正好落在这一段（叶）的末尾。
        store.revisions = listOf(
            TextRevision(
                id = 1L,
                bookId = 7L,
                chapter = 0,
                charStart = at,
                charEnd = at + quoted.length,
                text = "“重点句子。”",
                originalText = quoted,
                editedText = "“重点句子。”",
            ),
        )
        controller.reloadTextRevisions()
        controller.setReviewMode(true)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout")

        val reviewText = controller.chapterText(0) ?: error("no review text")
        val reviewEnds = controller.chapterTextBlockEnds(0) ?: error("no review block ends")

        assertEquals(
            "review text must keep both inserted quotes",
            normalText.length + 2,
            reviewText.length,
        )
        assertEquals("block count must not change", normalEnds.size, reviewEnds.size)
        for (i in normalEnds.indices) {
            assertEquals(
                "block $i end must map back to the same original offset " +
                    "(review=${reviewEnds[i]}, normal=${normalEnds[i]})",
                normalEnds[i],
                controller.originalOffset(0, reviewEnds[i]),
            )
        }
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>LEAF-BOUNDARY</dc:title><dc:identifier id="bookid">urn:test:leaf-boundary</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val c1 = """
            <html><body>
            <h1>Chapter 0</h1>
            <p>前言段落，这里是一些普通文字。</p>
            <p>‘重点句子。’</p>
            <p>第二段足够长的普通文字，用来确保后面还有内容可以落位。</p>
            <p>第三段。</p>
            <p>第四段。</p>
            </body></html>
        """.trimIndent()
        val c2 = "<html><body><h1>Chapter 1</h1><p>另一章。</p></body></html>"
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to c1.toByteArray(),
            "OEBPS/Text/c2.xhtml" to c2.toByteArray(),
        )
    }

    private class FakeRevisionStore : TextRevisionStore {
        var revisions: List<TextRevision> = emptyList()

        override suspend fun textRevisionsForChapter(bookId: Long, chapter: Int): List<TextRevision> =
            revisions.filter { it.bookId == bookId && it.chapter == chapter }

        override suspend fun textRevisionsForBook(bookId: Long): List<TextRevision> =
            revisions.filter { it.bookId == bookId }

        override suspend fun replaceTextRevisions(
            deletedIds: List<Long>,
            inserted: List<TextRevision>,
        ): List<TextRevision> = inserted

        override suspend fun fillLegacyRevisionSnapshot(
            id: Long,
            originalText: String,
            editedText: String,
        ) = Unit

        override suspend fun deleteTextRevision(id: Long) = Unit

        override suspend fun deleteTextRevisionsForBook(bookId: Long) = Unit
    }
}
