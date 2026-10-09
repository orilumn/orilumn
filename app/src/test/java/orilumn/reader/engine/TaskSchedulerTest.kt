package orilumn.reader.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * P0 spike: [TaskScheduler] pure semantics — priority order, same-key dedup, preemption,
 * slot bound. Deterministic via gate latches (no timing asserts except generous timeouts).
 */
class TaskSchedulerTest {

    private fun newScheduler(slots: Int): Pair<TaskScheduler, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TaskScheduler(scope, Dispatchers.Default, slots) to scope
    }

    @Test
    fun `queued tasks run in priority order`() = runBlocking {
        val (sched, scope) = newScheduler(1)
        try {
            val gate = CompletableDeferred<Unit>()
            val order = Collections.synchronizedList(ArrayList<Int>())
            // Occupy the single slot first.
            sched.submit(TaskScheduler.Task("gate", 0) { gate.await() })
            withTimeout(5_000) { while (sched.runningCount() == 0) delay(10) }
            sched.submit(TaskScheduler.Task("p30", 30) { order.add(30) })
            sched.submit(TaskScheduler.Task("p10", 10) { order.add(10) })
            sched.submit(TaskScheduler.Task("p20", 20) { order.add(20) })
            delay(200) // let all three queue behind the gate
            gate.complete(Unit)
            withTimeout(10_000) { while (order.size < 3) delay(10) }
            assertEquals(listOf(10, 20, 30), order)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `same key replaces pending twin`() = runBlocking {
        val (sched, scope) = newScheduler(1)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = Collections.synchronizedList(ArrayList<String>())
            sched.submit(TaskScheduler.Task("gate", 0) { gate.await() })
            withTimeout(5_000) { while (sched.runningCount() == 0) delay(10) }
            sched.submit(TaskScheduler.Task("k", 30) { ran.add("old") })
            sched.submit(TaskScheduler.Task("k", 10) { ran.add("new") })
            gate.complete(Unit)
            withTimeout(10_000) { while (ran.isEmpty()) delay(10) }
            delay(300) // give a leaked twin time to (not) run
            assertEquals(listOf("new"), ran)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelLowerThan preempts running work`() = runBlocking {
        val (sched, scope) = newScheduler(1)
        try {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            sched.submit(TaskScheduler.Task("long", 30) {
                try {
                    started.complete(Unit)
                    delay(30_000)
                } catch (e: Exception) {
                    cancelled.complete(Unit)
                    throw e
                }
            })
            withTimeout(5_000) { started.await() }
            sched.cancelLowerThan(20)
            withTimeout(5_000) { cancelled.await() }
            assertTrue(true)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 栅格抢占（`onForegroundRaster` → [TaskScheduler.cancelForRaster]）必须**放行当前章的 ±1 页预排**：
     * 这两条 `pg:` d=1 任务是三页截图窗口（当前页 ±1）的装配源，也是下一次落位命中 `pageCache`
     * 快路径的前提。真实 bug：栅格未命中时裸 `cancelLowerThan` 把它们一并砍掉，`pageCache` 永热不起来，
     * 每次翻页落位都退化成 `ensurePageRangeShaped` 同步重排（真机 137–308ms）且栅格永 miss。
     * 其余档（第 4 档 `PRIO_PAGE_REST`、章外 edge 整章、B2）照常被抢占。
     */
    @Test
    fun `cancelForRaster spares the plus-minus-one page prefill only`() = runBlocking {
        val (sched, scope) = newScheduler(1)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = Collections.synchronizedList(ArrayList<String>())
            // Occupy the single slot so everything below queues up.
            sched.submit(TaskScheduler.Task("gate", 0) { gate.await() })
            withTimeout(5_000) { while (sched.runningCount() == 0) delay(10) }

            // ±1 page prefill (the two d=1 tiers) — must survive.
            sched.submit(TaskScheduler.Task("pg:8:16", TaskScheduler.PRIO_PAGE_NEXT) { ran.add("dir-side") })
            sched.submit(TaskScheduler.Task("pg:8:14", TaskScheduler.PRIO_PAGE_PREV) { ran.add("other-side") })
            // Everything else — must be preempted.
            sched.submit(TaskScheduler.Task("pg:8:17", TaskScheduler.PRIO_PAGE_REST) { ran.add("rest") })
            sched.submit(TaskScheduler.Task("edge:9", TaskScheduler.PRIO_EDGE_FORWARD) { ran.add("edge") })
            sched.submit(TaskScheduler.Task("b2:11", TaskScheduler.PRIO_B2_CHAPTER) { ran.add("b2") })
            delay(200) // let all five queue behind the gate

            sched.cancelForRaster(TaskScheduler.PRIO_FLIP)
            delay(300) // let the async preemption install

            gate.complete(Unit)
            withTimeout(10_000) { while (ran.size < 2) delay(10) }
            delay(300) // give any leaked task time to (not) run
            assertEquals(
                "only the two d=1 page-prefill tiers may survive the raster preemption",
                listOf("dir-side", "other-side"),
                ran,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrency never exceeds slots`() = runBlocking {
        val (sched, scope) = newScheduler(2)
        try {
            val live = java.util.concurrent.atomic.AtomicInteger(0)
            var peak = 0
            val gate = CompletableDeferred<Unit>()
            repeat(5) { i ->
                sched.submit(TaskScheduler.Task("t$i", 10) {
                    val n = live.incrementAndGet()
                    synchronized(this@TaskSchedulerTest) {
                        if (n > peak) peak = n
                    }
                    try {
                        gate.await()
                    } finally {
                        live.decrementAndGet()
                    }
                })
            }
            delay(500) // let workers pick up tasks
            gate.complete(Unit)
            withTimeout(10_000) { while (sched.runningCount() + sched.pendingCount() > 0) delay(10) }
            assertTrue("peak=$peak must stay within 2 slots", peak <= 2)
            assertTrue("peak=$peak should have used both slots", peak == 2)
        } finally {
            scope.cancel()
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Submit-order dedup (cc01867): `submit` stamps a sequence at CALL time, then hops through an
    // async maintenance hop to install the task. Two same-key submits can therefore land OUT OF
    // ORDER — the newer twin's hop may run before the older one's. Ordering must be decided by
    // Task.seq, never by arrival, or the stale twin wins and the fresh work is silently lost.
    // The two tests below force exactly that reordering.
    // ───────────────────────────────────────────────────────────────

    /** Lets the test choose WHEN and IN WHICH ORDER the maintenance hops land. Worker bodies still
     *  run on the real dispatcher, so only the *installation* order is under the test's control. */
    private class ManualHopDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queued = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queued.add(block)
        }

        /** Runs the i-th queued hop (i = arrival order unless deliberately scrambled) and drops it. */
        fun runAt(i: Int) {
            queued.removeAt(i).run()
        }
    }

    @Test
    fun `newer same-key submit wins when its maintenance hop lands first`() = runBlocking {
        val hops = ManualHopDispatcher()
        val scope = CoroutineScope(SupervisorJob() + hops)
        val sched = TaskScheduler(scope, Dispatchers.Default, 1)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = Collections.synchronizedList(ArrayList<String>())
            // Occupy the only slot so both twins queue up behind it.
            sched.submit(TaskScheduler.Task("gate", 0) { gate.await() })
            hops.runAt(0)
            withTimeout(5_000) { while (sched.runningCount() == 0) delay(10) }

            sched.submit(TaskScheduler.Task("k", 30) { ran.add("old") })
            sched.submit(TaskScheduler.Task("k", 10) { ran.add("new") })
            // Scramble: the NEWER twin (called last) installs first, the older one lands after.
            hops.runAt(1)
            hops.runAt(0)
            gate.complete(Unit)

            withTimeout(10_000) { while (ran.isEmpty()) delay(10) }
            delay(300) // give a leaked twin time to (not) run
            assertEquals("call order decides, not arrival order", listOf("new"), ran)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `older same-key hop landing late does not cancel the running newer twin`() = runBlocking {
        val hops = ManualHopDispatcher()
        val scope = CoroutineScope(SupervisorJob() + hops)
        val sched = TaskScheduler(scope, Dispatchers.Default, 1)
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val done = Collections.synchronizedList(ArrayList<String>())
            sched.submit(TaskScheduler.Task("k", 30) { done.add("old-ran") })
            sched.submit(TaskScheduler.Task("k", 10) {
                started.complete(Unit)
                release.await()
                done.add("new-finished")
            })
            hops.runAt(1) // NEWER hop lands first and a worker picks it up
            withTimeout(5_000) { started.await() }

            hops.runAt(0) // OLDER hop lands late — it must NOT preempt the running newer twin
            delay(300)
            assertEquals("an older resubmit must not cancel the newer running twin", 1, sched.runningCount())
            assertTrue("nothing may have completed yet", done.isEmpty())

            release.complete(Unit)
            withTimeout(10_000) { while (done.isEmpty()) delay(10) }
            delay(300)
            assertEquals("only the newer twin's work runs", listOf("new-finished"), done)
        } finally {
            scope.cancel()
        }
    }
}
