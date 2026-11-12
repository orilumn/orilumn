package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.revision.RevisionStyle
import orilumn.reader.data.revision.RevisionStyleSpan
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.revision.TextRevisionStore
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.revision.InlineMarkup
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
 * 审阅视图里的**最终行内样式**：读者给一段加斜体 / 粗体之后，审阅视图里留下来的字也得是斜体 /
 * 粗体，不能只剩一行普通正文（读者 2026-11-12 复报）。
 *
 * 回归背景：只改结构（一个字都没改）的修订在审阅空间里没有任何「就地插入」，早先的叶内落字
 * 只把样式套在新写的字上 ⇒ 保留下来的字仍是原样。修法是「整条修订落在本叶、且不动段界」时
 * 整段按修订后的行内结构重建一次；这里用 `textEditSpans`（卡片取结构的那把尺）验证审阅空间
 * 里的结构确实盖住了保留字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewRestyleTest {

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
    fun `style only edit shows the italic on kept text in review`() = runBlocking {
        assertTrue(controller.open(7L, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0) ?: error("no normal layout")

        val normalText = controller.chapterText(0) ?: error("no normal text")
        val target = "重点句子"
        val at = normalText.indexOf(target)
        assertTrue("fixture paragraph missing", at >= 0)

        // 一个字都没改，只给这五个字加斜体：最小修订 = 原文，样式 = 整段 em。
        store.revisions = listOf(
            TextRevision(
                id = 1L,
                bookId = 7L,
                chapter = 0,
                charStart = at,
                charEnd = at + target.length,
                text = target,
                originalText = target,
                editedText = target,
                styles = listOf(RevisionStyleSpan(0, target.length, RevisionStyle("em"))),
                editedStyles = listOf(RevisionStyleSpan(0, target.length, RevisionStyle("em"))),
            ),
        )
        controller.reloadTextRevisions()
        controller.setReviewMode(true)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout")

        // 审阅空间多出两个绿 `*` 痕迹（标记本身要看得见），正文一个字没改；
        // 卡片取到的结构里必须有这一整段斜体（保留字也套上最终样式）。
        val reviewText = controller.chapterText(0) ?: error("no review text")
        assertEquals(normalText.length + 2, reviewText.length)
        assertEquals("*$target*", reviewText.substring(at, at + target.length + 2))
        val spans = controller.textEditSpans(0, at + 1, at + 1 + target.length).orEmpty()
        assertEquals(
            listOf(InlineMarkup.StyleSpan(0, target.length, InlineMarkup.StyleSig("em"))),
            spans,
        )
    }

    @Test
    fun `kept text keeps the final style when the edit also adds characters`() = runBlocking {
        assertTrue(controller.open(7L, saved = null))
        controller.setViewport(viewW, viewH)
        controller.ensureChapterLayout(0, 0) ?: error("no normal layout")

        val normalText = controller.chapterText(0) ?: error("no normal text")
        val target = "重点句子"
        val at = normalText.indexOf(target)
        assertTrue("fixture paragraph missing", at >= 0)

        // 在段中加一个字，同时整段加粗：保留下来的字也要带上最终结构。
        val edited = "重点新句子"
        store.revisions = listOf(
            TextRevision(
                id = 2L,
                bookId = 7L,
                chapter = 0,
                charStart = at,
                charEnd = at + target.length,
                text = edited,
                originalText = target,
                editedText = edited,
                styles = listOf(RevisionStyleSpan(0, edited.length, RevisionStyle("strong"))),
                editedStyles = listOf(RevisionStyleSpan(0, edited.length, RevisionStyle("strong"))),
            ),
        )
        controller.reloadTextRevisions()
        controller.setReviewMode(true)
        controller.ensureChapterLayout(0, 0) ?: error("no review layout")

        val reviewText = controller.chapterText(0) ?: error("no review text")
        // 审阅空间 = 原文 + 新字（「新」插在「点」后面）。
        assertEquals(normalText.length + 1, reviewText.length)
        val spans = controller.textEditSpans(0, at, at + edited.length).orEmpty()
        assertEquals(
            listOf(InlineMarkup.StyleSpan(0, edited.length, InlineMarkup.StyleSig("strong"))),
            spans,
        )
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>REVIEW-RESTYLE</dc:title><dc:identifier id="bookid">urn:test:review-restyle</dc:identifier>
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
            <p>重点句子。</p>
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
