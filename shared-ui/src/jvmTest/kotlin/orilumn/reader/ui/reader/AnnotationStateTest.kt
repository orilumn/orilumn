package orilumn.reader.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
        /** 每次 `annotationsForChapter` 的章号（判「进预读过的章不再读库」）。 */
        val chapterReads = ArrayList<Int>()
        var failAdd = false
        var failUpdate = false
        var failDelete = false
        var addGate: CompletableDeferred<Unit>? = null
        val addStarted = CompletableDeferred<Unit>()
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

        override suspend fun annotationsForChapter(chapter: Int): List<Annotation> {
            chapterReads += chapter
            return stored.filter { it.chapter == chapter }
        }

        override suspend fun addAnnotation(annotation: Annotation): Annotation? {
            addStarted.complete(Unit)
            addGate?.await()
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

    @Test
    fun `add finishing after a chapter switch does not leak into the new chapter`() = runBlocking {
        val host = FakeHost()
        val gate = CompletableDeferred<Unit>()
        host.addGate = gate
        val state = AnnotationState(host)
        state.load(2)

        val job = launch { state.add(annotation(chapter = 2)) }
        host.addStarted.await()
        state.load(3)
        gate.complete(Unit)
        job.join()

        assertEquals(3, state.chapter)
        assertTrue(state.annotations.isEmpty())
    }

    @Test
    fun `prefetched neighbours can be read before entering and are not read again on entry`() =
        runBlocking {
            val host = FakeHost()
            host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
            val state = AnnotationState(host)
            state.load(2)
            host.chapterReads.clear()

            state.prefetchNeighbors(2)
            // 前后各一次（前章没有标注也照样算「读回来了」）。
            assertEquals(listOf(3, 1), host.chapterReads)
            assertTrue(state.isReady(3))
            assertTrue(state.isReady(1))
            assertTrue(!state.isReady(4))
            assertEquals(listOf(2L), state.annotationsFor(3)?.map { it.id })

            host.chapterReads.clear()
            state.load(3)
            // 预读命中：进章不再等一次库往返，装饰 / 旁注首帧就能布。
            assertEquals(emptyList<Int>(), host.chapterReads)
            assertEquals(3, state.chapter)
            assertEquals(listOf(2L), state.annotations.map { it.id })
            assertTrue(state.loaded)
        }

    @Test
    fun `prefetched chapters stay out of the current chapter list`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 1, chapter = 2)
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
        val state = AnnotationState(host)
        state.load(2)
        state.prefetch(3)

        assertEquals(listOf(1L), state.annotations.map { it.id })
        assertEquals(listOf(1L), state.annotationsFor(2)?.map { it.id })
        assertEquals(listOf(2L), state.annotationsFor(3)?.map { it.id })
        assertNull(state.annotationsFor(4))
    }

    @Test
    fun `entering a prefetched chapter keeps its note generation`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
        val state = AnnotationState(host)
        state.load(2)
        state.prefetch(3)
        val before = state.generationOf(3)

        state.load(3)
        // 预读摆上去是同一份内容：代数不动，按代数存下的布点结果继续有效（否则预热白做）。
        assertEquals(before, state.generationOf(3))

        state.load(2)
        state.prefetch(3)
        state.load(3)
        assertEquals(before, state.generationOf(3))
    }

    @Test
    fun `prefetch after an external delete bumps the generation`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
        val state = AnnotationState(host)
        state.load(3)
        val before = state.generationOf(3)
        state.load(2)

        // 笔记清单里删掉那条 ⇒ 外部改动走 reload（预读快照同时作废），随后重备邻居。
        host.stored.clear()
        state.reload()
        state.prefetch(3)

        // 内容真的变了：旧布点结果必须作废，翻过去按新内容重算。
        assertTrue(state.generationOf(3) > before)
        assertTrue(state.annotationsFor(3).isNullOrEmpty())
    }

    @Test
    fun `reload drops prefetched neighbours`() = runBlocking {
        val host = FakeHost()
        host.stored += annotation(id = 1, chapter = 2)
        host.stored += annotation(id = 2, chapter = 3, start = 0, end = 4)
        val state = AnnotationState(host)
        state.load(2)
        state.prefetch(3)
        assertTrue(state.isReady(3))

        // 笔记清单里删的可能是别的章的标注：预读快照整批作废，翻过去时重读。
        state.reload()
        assertTrue(!state.isReady(3))
    }
}
