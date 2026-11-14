package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.revision.TextRevisionStore
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审阅痕迹在**别章提交修改**之后必须还在。
 *
 * 回归背景：提交一次正文修改后，宿主整本重读修订表（`reloadTextRevisions`）。早先那一步把
 * 全书的修订表整体换成「只有区间 / 替换文字」的裸表，除了**刚改的那一章**（随后重排会重新
 * 补上原文快照）之外，其余各章的原文快照全丢了 ⇒ `reviewMarks` 一条都给不出来，翻回前一页
 * 看到的就是「删除与新增的字全是黑的」。真机表现正是如此。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewMarksSurviveReloadTest {

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
    fun `review marks in an unchanged chapter survive a reload caused by another chapter`() = runBlocking {
        assertTrue(controller.open(11L, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0) ?: error("no normal layout")

        val normalText = controller.chapterText(0) ?: error("no normal text")
        val phrase = "普通文字"
        val at = normalText.indexOf(phrase)
        assertTrue("fixture paragraph missing", at >= 0)

        store.revisions = listOf(revision(chapter = 0, start = at, end = at + phrase.length, text = "寻常字句"))
        controller.reloadTextRevisions()
        controller.setReviewMode(true)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout")
        val before = controller.reviewMarks(0)
        assertFalse("review marks must exist before the reload", before.isEmpty())

        // 别的章提交一次修改：宿主整本重读修订表（本章内容没变、不会被重排）。
        store.revisions = store.revisions + revision(chapter = 1, start = 0, end = 1, text = "另")
        controller.reloadTextRevisions()

        val after = controller.reviewMarks(0)
        assertFalse("review marks must survive a book-wide revision reload", after.isEmpty())
        assertTrue("marks must be unchanged: before=$before after=$after", before == after)

        // 清单两端摘要 / id 变了但排版内容没变（清单回填、删掉后再写一条同样的修订）：
        // 内容版本不变 ⇒ 痕迹仍然要给得出来，不能因为这一笔就把红绿抹成黑。
        store.revisions = store.revisions.map {
            if (it.chapter == 0) it.copy(id = 999L, originalText = "原文摘要", editedText = "新文字摘要") else it
        }
        controller.reloadTextRevisions()
        assertTrue(
            "summary-only changes must not drop the review marks",
            controller.reviewMarks(0) == before,
        )
    }

    @Test
    fun `review marks come back after a new edit in the same chapter`() = runBlocking {
        assertTrue(controller.open(11L, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0) ?: error("no normal layout")

        val normalText = controller.chapterText(0) ?: error("no normal text")
        val phrase = "普通文字"
        val at = normalText.indexOf(phrase)
        assertTrue("fixture paragraph missing", at >= 0)
        store.revisions = listOf(revision(chapter = 0, start = at, end = at + phrase.length, text = "寻常字句"))
        controller.reloadTextRevisions()
        controller.setReviewMode(true)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout")
        assertFalse("review marks must exist before the edit", controller.reviewMarks(0).isEmpty())

        // 再改一处（本章内容变了）：落库 → 重读 → 作废本章 → 重排。
        val second = normalText.indexOf("用来确保")
        assertTrue("second fixture phrase missing", second >= 0)
        store.revisions = store.revisions +
            revision(chapter = 0, start = second, end = second + 4, text = "以便确认")
        controller.reloadTextRevisions()
        controller.invalidateChapterContent(0)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout after edit")
        assertFalse(
            "both edits must be marked after the relayout",
            controller.reviewMarks(0).isEmpty(),
        )
    }

    private fun revision(chapter: Int, start: Int, end: Int, text: String) = TextRevision(
        id = (chapter * 100L + start),
        bookId = 11L,
        chapter = chapter,
        charStart = start,
        charEnd = end,
        text = text,
    )

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>REVIEW-RELOAD</dc:title><dc:identifier id="bookid">urn:test:review-reload</dc:identifier>
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
            <p>第二段足够长的普通文字，用来确保后面还有内容可以落位。</p>
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
