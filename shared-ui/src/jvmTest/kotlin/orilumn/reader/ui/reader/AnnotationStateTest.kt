package orilumn.reader.ui.reader

import kotlinx.coroutines.runBlocking
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.engine.skia.DrawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-19 内存态：换章清空、先改内存后落库、失败回滚、命中不查库。 */
class AnnotationStateTest {

    private class FakeHost : ReaderHost {
        val stored = ArrayList<Annotation>()
        var failAdd = false
        var failUpdate = false
        var failDelete = false
        private var nextId = 100L

        override fun title(): String = "book"
        override fun unitTitle(chapter: Int): String = "ch$chapter"
        override fun pageCount(chapter: Int): Int = 0
        override fun tocIndex(chapter: Int): Int = 0
        override suspend fun open(): ReaderPos? = null
        override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? = null
        override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? = null
        override suspend fun pageAtFraction(fraction: Double): ReaderPos? = null
        override suspend fun chapterStart(index: Int): ReaderPos? = null
        override fun pageProgress(pos: ReaderPos): Double = 0.0
        override fun pageLines(pos: ReaderPos): List<DrawLine>? = null
        override suspend fun firstContentChapter(): Int? = null
        override fun onSaveProgress(pos: ReaderPos) = Unit

        override suspend fun annotationsForChapter(chapter: Int): List<Annotation> =
            stored.filter { it.chapter == chapter }

        override suspend fun addAnnotation(annotation: Annotation): Annotation? {
            if (failAdd) return null
            val saved = annotation.copy(id = nextId++)
            stored += saved
            return saved
        }

        override suspend fun updateAnnotation(annotation: Annotation): Boolean {
            if (failUpdate) return false
            val i = stored.indexOfFirst { it.id == annotation.id }
            if (i < 0) return false
            stored[i] = annotation
            return true
        }

        override suspend fun deleteAnnotation(id: Long): Boolean {
            if (failDelete) return false
            return stored.removeAll { it.id == id }
        }
    }

    private fun annotation(
        id: Long = 0,
        chapter: Int = 2,
        start: Int = 3,
        end: Int = 7,
    ) = Annotation(
        id = id,
        bookId = 1,
        chapter = chapter,
        charStart = start,
        charEnd = end,
        kind = AnnotationKind.Highlight,
    )

    @Test
    fun `load reads one chapter and hit does not query the host`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 1, chapter = 2, start = 3, end = 7)
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 2)
        val state = AnnotationState(host)

        state.load(2)
        assertEquals(1, state.annotations.size)
        assertEquals(1L, state.annotations.single().id)
        assertEquals(1L, state.annotationAt(3)?.id)
        assertNull(state.annotationAt(7))
        // 同章再 load 不重读（host 里新增一条也不会被看到）。
        host.stored += annotation(id = 9, chapter = 2)
        state.load(2)
        assertEquals(1, state.annotations.size)
    }

    @Test
    fun `switch chapter clears old marks before loading new ones`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 1, chapter = 2)
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
        val state = AnnotationState(host)
        state.load(2)
        assertEquals(1, state.annotations.size)

        state.load(3)
        assertEquals(3, state.chapter)
        assertEquals(2L, state.annotations.single().id)
    }

    @Test
    fun `add is optimistic then replaced by the database id`() = runBlocking {
        val host = FakeHost()
        val state = AnnotationState(host)
        state.load(2)

        val saved = state.add(annotation())
        assertNotNull(saved)
        assertEquals(100L, saved!!.id)
        assertEquals(1, state.annotations.size)
        assertEquals(100L, state.annotations.single().id)
    }

    @Test
    fun `failed add rolls the optimistic mark back`() = runBlocking {
        val host = FakeHost()
        val state = AnnotationState(host)
        state.load(2)
        host.failAdd = true

        assertNull(state.add(annotation()))
        assertTrue(state.annotations.isEmpty())
    }

    @Test
    fun `failed update and delete restore the old mark`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 1, chapter = 2)
        val state = AnnotationState(host)
        state.load(2)

        host.failUpdate = true
        val changed = state.annotations.single().copy(kind = AnnotationKind.Underline)
        assertTrue(!state.update(changed))
        assertEquals(AnnotationKind.Highlight, state.annotations.single().kind)

        host.failDelete = true
        assertTrue(!state.delete(1))
        assertEquals(1L, state.annotations.single().id)
    }

    @Test
    fun `add ignores marks from another chapter`() = runBlocking {
        val host = FakeHost()
        val state = AnnotationState(host)
        state.load(2)
        assertNull(state.add(annotation(chapter = 3)))
        assertTrue(state.annotations.isEmpty())
    }
}
