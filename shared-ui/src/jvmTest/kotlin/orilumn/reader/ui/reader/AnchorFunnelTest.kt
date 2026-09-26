package orilumn.reader.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import orilumn.reader.engine.paging.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 锚页串行漏斗单测：try-lock（有锁就放弃、不排队）+ 按最新源链式推进 + 空源/空结果语义。 */
class AnchorFunnelTest {

    private fun pos(char: Int) = ReaderPos(0, PageSlice(charStart = char, charEnd = char + 10))

    private val funnel = AnchorFunnel(logTag = "Test")

    @Test
    fun `sequential navigates chain from the freshest source`() = runBlocking {
        var cur = pos(0)
        val sources = mutableListOf<Int>()
        repeat(10) {
            funnel.navigate("t", { cur }, { cur = it }) { src ->
                sources.add(src.slice.charStart)
                pos(src.slice.charStart + 1)
            }
        }
        // 一次做完一件再做下一件：源恰好是 0..9，终点 10。
        assertEquals((0..9).toList(), sources)
        assertEquals(10, cur.slice.charStart)
    }

    @Test
    fun `concurrent navigates only one wins the rest drop`() = runBlocking {
        var cur = pos(0)
        val results = (1..10).map {
            async {
                funnel.navigate("t", { cur }, { cur = it }) { src ->
                    // 挂起一次逼出交错：首个拿到锁后挂起，其余 9 个 try-lock 必失败。
                    kotlinx.coroutines.yield()
                    pos(src.slice.charStart + 1)
                }
            }
        }.awaitAll()
        // 10 个并发恰好一个成功、9 个 BUSY-DROP；终点只前进一步，不存在各算各的。
        assertEquals(1, results.count { it != null })
        assertEquals(9, results.count { it == null })
        assertEquals(1, cur.slice.charStart)
    }

    @Test
    fun `navigate in flight blocks the next until unlock then fresh source`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var cur = pos(0)
        val first = async {
            funnel.navigate("first", { cur }, { cur = it }) { src ->
                gate.await()
                pos(src.slice.charStart + 1)
            }
        }
        repeat(20) { kotlinx.coroutines.delay(5) }
        // 锁被占：后到直接放弃，不排队、不执行、不提交。
        var ran = false
        val dropped = async {
            funnel.navigate("second", { cur }, { cur = it }) {
                ran = true
                pos(99)
            }
        }
        assertNull(dropped.await())
        assertEquals(false, ran)
        assertEquals(0, cur.slice.charStart)
        // 解锁后的新动作按最新位置（第一跳的结果）重取源。
        gate.complete(Unit)
        assertEquals(1, first.await()?.slice?.charStart)
        val third = funnel.navigate("third", { cur }, { cur = it }) { src ->
            pos(src.slice.charStart + 1)
        }
        assertEquals(2, third?.slice?.charStart)
        assertEquals(2, cur.slice.charStart)
    }

    @Test
    fun `push in flight drops the next push`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var cur = pos(0)
        val first = async {
            funnel.push("ext1", { cur = it }) {
                gate.await()
                pos(9)
            }
        }
        repeat(20) { kotlinx.coroutines.delay(5) }
        assertNull(funnel.push("ext2", { cur = it }) { pos(7) })
        assertEquals(0, cur.slice.charStart)
        gate.complete(Unit)
        first.await()
        assertEquals(9, cur.slice.charStart)
    }

    @Test
    fun `null source drops without running`() = runBlocking {
        var ran = false
        val r = funnel.navigate("t", { null }, { error("must not commit") }) {
            ran = true
            pos(1)
        }
        assertNull(r)
        assertEquals(false, ran)
    }

    @Test
    fun `null result does not commit`() = runBlocking {
        var cur = pos(3)
        var committed = false
        val r = funnel.navigate("t", { cur }, { committed = true }) { null }
        assertNull(r)
        assertEquals(false, committed)
        assertEquals(3, cur.slice.charStart)
    }
}
