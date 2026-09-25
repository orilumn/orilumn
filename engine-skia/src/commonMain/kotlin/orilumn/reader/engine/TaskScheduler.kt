package orilumn.reader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import orilumn.reader.io.Logger

/**
 * P0 spike scheduler: priority task pool for background layout work. Flips never enter the
 * queue (synchronous highest priority, direct call); the pool serves prefill/B1/B2/prewarm
 * bodies and yields to flips via cancellation.
 *
 * Public API is non-suspending (maintenance hops through an internal mutex); task bodies run
 * as child coroutines on [workerDispatcher] bounded by [maxSlots]. Same-key submit replaces the
 * pending/running twin (dedup); [cancelLowerThan] preempts everything less urgent than a
 * threshold. Lower priority number = more urgent.
 *
 * SPIKE instrumentation: task start/done/cancel log `spike-task` (kept as permanent
 * lightweight diagnostics for queue behavior).
 */
class TaskScheduler(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxSlots: Int = 2,
) {
    companion object {
        const val PRIO_PREFILL_PAGE = 10
        const val PRIO_B1_CHAPTER = 20
        const val PRIO_B2_CHAPTER = 30
        const val PRIO_PREWARM = 40
        const val PRIO_FLIP = 0 // reference only — flips never enqueue
    }

    data class Task(
        val key: String,
        val priority: Int,
        val block: suspend () -> Unit,
    )

    private val mutex = Mutex()
    private val queue = ArrayList<Task>()
    private val running = HashMap<String, Job>()
    private val runningPrio = HashMap<String, Int>()

    /** Worker slot budget (P1: fixed at 2 from the P0 knee — light pages flat, heavy
     *  content-bound; tunable at runtime for future measurement). */
    @Volatile
    var maxSlots: Int = maxSlots

    fun submit(task: Task) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.key == task.key }
                // P0 fix: cancel in place WITHOUT removing the map entry — the entry is the
                // liveness record and only the task's own finally-block may remove it (on actual
                // death). Removing-then-cancelling leaks the slot: pump sees an empty slot while
                // the cancelled twin is still draining, and starts a second shaper on top of it.
                running[task.key]?.cancel()
                var at = queue.size
                for (i in queue.indices) {
                    if (queue[i].priority > task.priority) {
                        at = i
                        break
                    }
                }
                queue.add(at, task)
                pumpLocked()
            }
        }
    }

    fun cancelKey(key: String) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.key == key }
                running[key]?.cancel()
            }
        }
    }

    /** Preempt everything less urgent than [threshold] (greater number = less urgent).
     *  P0 fix: jobs are cancelled in place; map entries (the liveness record) are removed only
     *  by their own finally-block on actual death, so slot accounting never diverges from reality.
     *  A preempting task therefore waits for the drain (abandon latency, P7 checkpoints) — that
     *  wait IS the measured preemption cost. */
    fun cancelLowerThan(threshold: Int) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.priority > threshold }
                for ((key, prio) in runningPrio.toMap()) {
                    if (prio > threshold) {
                        running[key]?.cancel()
                    }
                }
            }
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { queue.size }
    suspend fun runningCount(): Int = mutex.withLock { running.size }

    /** P1a: diagnostic-grade idle wait (polling). Resolves when no task is queued or running.
     *  Callers must re-validate whatever they were waiting for (new submits may land after). */
    suspend fun awaitIdle() {
        while (true) {
            val busy = mutex.withLock { queue.isNotEmpty() || running.isNotEmpty() }
            if (!busy) return
            delay(200)
        }
    }

    private fun pumpLocked() {
        while (running.size < maxSlots && queue.isNotEmpty()) {
            val task = queue.removeAt(0)
            Logger.w("Orilumn.SPIKE", "spike-task start key=${task.key} prio=${task.priority}")
            val holder = arrayOfNulls<Job>(1)
            val job = scope.launch(workerDispatcher) {
                try {
                    task.block()
                    Logger.w("Orilumn.SPIKE", "spike-task done key=${task.key}")
                } catch (e: CancellationException) {
                    Logger.w("Orilumn.SPIKE", "spike-task cancel key=${task.key}")
                    throw e
                } finally {
                    mutex.withLock {
                        if (running[task.key] === holder[0]) {
                            running.remove(task.key)
                            runningPrio.remove(task.key)
                        }
                        pumpLocked()
                    }
                }
            }
            holder[0] = job
            running[task.key] = job
            runningPrio[task.key] = task.priority
        }
    }
}
