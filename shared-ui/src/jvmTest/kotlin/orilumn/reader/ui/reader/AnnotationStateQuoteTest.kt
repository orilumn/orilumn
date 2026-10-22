package orilumn.reader.ui.reader

import kotlinx.coroutines.runBlocking
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 老标注的原文摘录回填（D-24）：开章读到空摘录时按章节文本补一次，失败不阻塞。 */
class AnnotationStateQuoteTest {

    @Test
    fun loadFillsMissingQuoteFromChapterText() = runBlocking {
        val old = Annotation(
            id = 7,
            bookId = 1,
            chapter = 3,
            charStart = 2,
            charEnd = 5,
            kind = AnnotationKind.Highlight,
        )
        val host = FakeHost(listOf(old), chapterText = "0123456789")
        val state = AnnotationState(host)

        state.load(3)

        assertEquals("234", state.annotations.single().quote)
        assertEquals("234", host.lastUpdate?.quote)
    }

    @Test
    fun loadLeavesQuoteEmptyWhenChapterTextUnavailable() = runBlocking {
        val old = Annotation(
            id = 7,
            bookId = 1,
            chapter = 3,
            charStart = 2,
            charEnd = 5,
            kind = AnnotationKind.Highlight,
        )
        val host = FakeHost(listOf(old), chapterText = null)
        val state = AnnotationState(host)

        state.load(3)

        assertNull(state.annotations.single().quote)
        assertNull(host.lastUpdate)
    }

    private class FakeHost(
        private val chapterAnnotations: List<Annotation>,
        private val chapterText: String?,
    ) : ReaderHost {
        var lastUpdate: Annotation? = null

        override fun title(): String = "T"
        override fun unitTitle(chapter: Int): String = ""
        override fun pageCount(chapter: Int): Int = 0
        override fun tocIndex(chapter: Int): Int = 0
        override suspend fun open(): ReaderPos? = null
        override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? = null
        override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? = null
        override suspend fun pageAtFraction(fraction: Double): ReaderPos? = null
        override suspend fun chapterStart(index: Int): ReaderPos? = null
        override suspend fun firstContentChapter(): Int? = null
        override fun pageProgress(pos: ReaderPos): Double = 0.0
        override fun pageLines(pos: ReaderPos): List<orilumn.reader.engine.skia.DrawLine>? = null
        override fun onSaveProgress(pos: ReaderPos) {}
        override fun textOf(chapter: Int, start: Int, endExclusive: Int): String? {
            val text = chapterText ?: return null
            val s = start.coerceIn(0, text.length)
            val e = endExclusive.coerceIn(s, text.length)
            return text.substring(s, e)
        }

        override suspend fun annotationsForChapter(chapter: Int): List<Annotation> = chapterAnnotations
        override suspend fun updateAnnotation(annotation: Annotation): Boolean {
            lastUpdate = annotation
            return true
        }
    }
}
