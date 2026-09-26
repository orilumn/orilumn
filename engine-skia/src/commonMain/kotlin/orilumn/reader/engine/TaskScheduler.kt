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
import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.io.Logger

/**
 * Priority task pool for background layout work (排版层（上）; the single parallelism source —
 * 原则文档 §4/§5: worker slots are the only concurrency knob, flips never enqueue).
 *
 * Flips are the synchronous highest priority and stay OFF the queue (caller thread shapes
 * directly); the pool serves page prefill / prev-chapter bump / B1 / B2 / parse-prewarm bodies
 * and yields to flips by cancellation.
 *
 * Public API is non-suspending (maintenance hops through an internal mutex); task bodies run
 * as child coroutines on [workerDispatcher] bounded by [maxSlots]. Same-key submit replaces the
 * pending/running twin (dedup); [cancelLowerThan] preempts everything less urgent than a
 * threshold. Lower priority number = more urgent.
 *
 * Two invariants the semantics unit tests lock (`app/src/test/.../TaskSchedulerTest.kt`):
 *  - slot accounting = the liveness map, so it can never diverge from reality: cancellation kills
 *    the job IN PLACE and only the job's own `finally` removes its map entry (P0 fix);
 *  - ordering is decided by [Task.seq] (assigned at CALL time), never by arrival order — the
 *    maintenance hops below are async, so a same-key resubmit can be enqueued out of order and
 *    the newer twin must still win (P2.6 fix).
 *
 * Instrumentation: task start/done/cancel log under `Orilumn.SPIKE` / `spike-task`. The tag and
 * prefix are kept verbatim from P0 so P3 device measurements stay comparable with the P0 verdict
 * run; they are permanent queue diagnostics, not spike leftovers.
 */
class TaskScheduler(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxSlots: Int = 2,
) {
    companion object {
        const val PRIO_PREFILL_PAGE = 10
        /** P2.1: prev-chapter full bump (second priority is unconditional — between neighbor
         *  pages and current-chapter canonical). Shares B1 keying for mutual exclusion. */
        const val PRIO_PREV_CHAPTER = 15
        const val PRIO_B1_CHAPTER = 20
        const val PRIO_B2_CHAPTER = 30
        const val PRIO_PREWARM = 40
        const val PRIO_FLIP = 0 // reference only — flips never enqueue
    }

    data class Task(
        val key: String,
        val priority: Int,
        /** Submission sequence, assigned by [submit] at call time (call order is deterministic
         *  even when the async maintenance hops land out of order). Default 0 = unset. */
        val seq: Long = 0L,
        val block: suspend () -> Unit,
    )

    private val mutex = Mutex()
    private val queue = ArrayList<Task>()
    private val running = HashMap<String, Job>()
    private val runningPrio = HashMap<String, Int>()
    private val runningSeq = HashMap<String, Long>()
    private val latestSeq = HashMap<String, Long>()
    private val seqLock = SyncLock()
    private var submitSeq = 0L

    /** P1f: lock-free snapshot of running keys (refreshed under mutex on every mutation).
     *  Status views read this without suspending; benignly stale by microseconds. */
    @Volatile
    var runningKeys: Set<String> = emptySet()
        private set

    /** Worker slot budget (P1: fixed at 2 from the P0 knee — light pages flat, heavy
     *  content-bound; tunable at runtime for future measurement). */
    @Volatile
    var maxSlots: Int = maxSlots

    fun submit(task: Task) {
        // Sequence at CALL time (synchronous): later calls always outrank earlier ones no matter
        // how the async maintenance hops below interleave.
        val mySeq = seqLock.withLock {
            ++submitSeq
            latestSeq[task.key] = submitSeq
            submitSeq
        }
        val stamped = task.copy(seq = mySeq)
        scope.launch {
            mutex.withLock {
                // Drop only OLDER twins; cancel only an older running twin. A newer twin already
                // queued/running (maintenance reordered) is left alone — seq decides at pump time.
                queue.removeAll { it.key == stamped.key && it.seq < mySeq }
                if ((runningSeq[stamped.key] ?: -1L) < mySeq) running[stamped.key]?.cancel()
                var at = queue.size
                for (i in queue.indices) {
                    if (queue[i].priority > stamped.priority) {
                        at = i
                        break
                    }
                }
                queue.add(at, stamped)
                pumpLocked()
            }
        }
    }

    fun cancelKey(key: String) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.key == key }
                running[key]?.cancel()
                syncKeysLocked()
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
                syncKeysLocked()
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
            // Stale twin? A newer submit for the same key outranks, however maintenance landed.
            if (queue[0].seq != latestSeq[queue[0].key]) {
                queue.removeAt(0)
                continue
            }
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
                            runningSeq.remove(task.key)
                        }
                        pumpLocked()
                    }
                }
            }
            holder[0] = job
            running[task.key] = job
            runningPrio[task.key] = task.priority
            runningSeq[task.key] = task.seq
            syncKeysLocked()
        }
    }

    /** Refresh [runningKeys]. Call only with [mutex] held. */
    private fun syncKeysLocked() {
        runningKeys = running.keys.toSet()
    }
}
