package orilumn.reader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * SPIKE instrumentation (remove or keep per P0 verdict): [startSpikeAutoPhase] cycles
 * [maxSlots] with `spike-phase` logs; task start/done/cancel log `spike-task`.
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

    /** Worker slot budget (P0: driven by auto-phase; P1: fixed from measured knee). */
    @Volatile
    var maxSlots: Int = maxSlots

    /** P0 spike: cycle slots automatically for the contention curve (ONE install, phases in log). */
    var spikePhases: List<Int> = listOf(1, 2, 4)
    var spikePhaseMs: Long = 60_000L
    private var spikeJob: Job? = null

    fun submit(task: Task) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.key == task.key }
                running.remove(task.key)?.cancel()
                runningPrio.remove(task.key)
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
                running.remove(key)?.cancel()
                runningPrio.remove(key)
            }
        }
    }

    /** Preempt everything less urgent than [threshold] (greater number = less urgent). */
    fun cancelLowerThan(threshold: Int) {
        scope.launch {
            mutex.withLock {
                queue.removeAll { it.priority > threshold }
                val it = runningPrio.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.value > threshold) {
                        running.remove(e.key)?.cancel()
                        it.remove()
                    }
                }
            }
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { queue.size }
    suspend fun runningCount(): Int = mutex.withLock { running.size }

    fun startSpikeAutoPhase() {
        spikeJob?.cancel()
        spikeJob = scope.launch {
            for (n in spikePhases) {
                maxSlots = n
                Logger.w("Orilumn.SPIKE", "spike-phase slots=$n")
                delay(spikePhaseMs)
                ensureActive()
            }
            Logger.w("Orilumn.SPIKE", "spike-phase done")
        }
    }

    fun stopSpikeAutoPhase() {
        spikeJob?.cancel()
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
