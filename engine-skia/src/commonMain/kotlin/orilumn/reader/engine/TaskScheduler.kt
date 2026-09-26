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
        // ── 优先级阶梯（原则 §3）。号刻意留间隔（10/20/30/…），日后插档不必重编。
        //    号小 = 更急。翻页（PRIO_FLIP）永远不在此表里——它不进队列。 ──

        /** **第 2 档** = 邻页序列的 d=1 **顺方向**那页（原则称之为「下一页」）。
         *  「下一页」是相对**翻页方向**定义的：向前翻即页码+1，向后翻即页码−1。
         *  同档还有：d=1 章外兜底的顺向侧（[PRIO_EDGE_FORWARD]）、本章全量的加急档
         *  （[PRIO_B1_URGENT]）——它们都是"读者下一步就要用"，且都**包含**本档的邻页。 */
        const val PRIO_PAGE_NEXT = 10

        /** **第 3 档** = 邻页序列的 d=1 **反方向**那页（原则称之为「上一页」）。
         *  同档还有：d=1 章外兜底的反向侧（[PRIO_EDGE_BACKWARD]）。 */
        const val PRIO_PAGE_PREV = 20

        /** **第 4 档** = 本章其余页（d≥2，按 \|距离\| 交错）。 */
        const val PRIO_PAGE_REST = 30

        /** **第 6 档** = 本章全量（B1）。动态档：目标页距章首 d=1 时升到 [PRIO_B1_URGENT]（§3.4）。 */
        const val PRIO_B1_CHAPTER = 40

        /** **第 7 档** = 其他章全量（章距投机，懒）。 */
        const val PRIO_B2_CHAPTER = 50

        /** 解析预热（markup + 轻结构，无塑形）。排在一切排版活之后：用户不需要它出字，
         *  只需要它别挡路（原则 §7）。 */
        const val PRIO_PREWARM = 60

        /** §3.3 的 d=1 章外兜底，顺向侧（缺下一页 → 全量预排下一章）。与第 2 档同级。 */
        const val PRIO_EDGE_FORWARD = PRIO_PAGE_NEXT

        /** §3.3 的 d=1 章外兜底，反向侧（缺上一页 → 全量预排上一章）。与第 3 档同级。 */
        const val PRIO_EDGE_BACKWARD = PRIO_PAGE_PREV

        /** §3.4 本章全量的加急档（目标页距章首 d=1）。取第 2 档而非第 3 档——全量表**包含**
         *  第 2/3 档那些邻页，把一页的增量排在包含它的一趟之前说不通。 */
        const val PRIO_B1_URGENT = PRIO_PAGE_NEXT

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
                } catch (e: Throwable) {
                    // 非取消异常：后台排版 bug 在此唯一留痕。记 e 后重抛（fail-fast 不变），
                    // 否则 release 只剩"预排没来"而无栈。
                    Logger.e("Orilumn.SPIKE", "spike-task FAIL key=${task.key} ${e.message}")
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
