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
}
