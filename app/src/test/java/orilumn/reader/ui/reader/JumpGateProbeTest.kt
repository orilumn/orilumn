package orilumn.reader.ui.reader

import orilumn.reader.engine.paging.PageSlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probe T2 (P6): in-flight jump cancellation + dedup. The reader's jump paths (`seekTo` /
 * `tocJump` / `jumpChapter`) used to launch a fresh coroutine every time with no cancellation, so a
 * seek-drag (or rapid TOC/burst seeks) stacked multiple full `pageAtFraction`/`openChapterStart`
 * anchor paths, all landing and fighting over the position. [JumpGate] converges the burst on
 * exactly ONE landing (the last request) and bounds the number of concurrently-executing
 * resolution tasks at ≤ 2 (the in-flight one being cancelled + the new one), even under true
 * thread overlap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JumpGateProbeTest {

    private val page = PageSlice(charStart = 1, charEnd = 2, firstLine = 1, lastLineExclusive = 2)

    /** A target resolution that suspends for [delayMs] and records the concurrency peak. */
    private fun slowResolve(
        target: Int,
        delayMs: Long,
        inFlight: AtomicInteger,
        peak: AtomicInteger,
    ): suspend () -> Pair<Int, PageSlice>? = {
        val now = inFlight.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        try {
            delay(delayMs)
            target to page
        } finally {
            inFlight.decrementAndGet()
        }
    }

    @Test
    fun `rapid burst lands only the last target and the indicator resets`() = runTest {
        val td = StandardTestDispatcher(testScheduler)
        val landings = Collections.synchronizedList(mutableListOf<Int>())
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val gate = JumpGate(scope = this, background = td, ui = td, land = { ch, _ -> landings += ch })

        (1..5).forEach { i -> gate.submit(slowResolve(i, 100, inFlight, peak)) }
        assertTrue("loading indicator shown while a jump is in flight", gate.active)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()

        assertEquals("exactly one landing — the LAST request of the burst", listOf(5), landings.toList())
        assertTrue("concurrent resolutions stay bounded (peak=${peak.get()})", peak.get() <= 2)
        assertTrue("no stuck loading indicator after the jump settles", !gate.active)
    }

    @Test
    fun `a stale request started before a newer one can never land`() = runTest {
        val td = StandardTestDispatcher(testScheduler)
        val landings = Collections.synchronizedList(mutableListOf<Int>())
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val gate = JumpGate(scope = this, background = td, ui = td, land = { ch, _ -> landings += ch })

        // A: long-running resolve already in progress (epoch captured, may survive past its cancel).
        gate.submit(slowResolve(1, 1000, inFlight, peak))
        runCurrent()
        advanceTimeBy(100)
        // B: a newer request supersedes A while A is still resolving.
        gate.submit(slowResolve(2, 10, inFlight, peak))
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()

        assertEquals("the superseded request must not land", listOf(2), landings.toList())
        assertTrue("concurrent resolutions stay bounded (peak=${peak.get()})", peak.get() <= 2)
    }

    @Test
    fun `overlapping seeks stay bound at two concurrent tasks and only the last lands`() = runBlocking {
        val landings = Collections.synchronizedList(mutableListOf<Int>())
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val gate = JumpGate(
            scope = this,
            background = Dispatchers.Default,
            ui = Dispatchers.Unconfined,
            land = { ch, _ -> landings += ch },
        )

        // Let the first seek really start shaping on a worker thread, then fire a quick burst —
        // the old code ran every burst to completion (peak = N); the gate must cancel and dedup.
        gate.submit(slowResolve(1, 200, inFlight, peak))
        delay(40)
        (2..6).forEach { i ->
            gate.submit(slowResolve(i, 200, inFlight, peak))
            delay(5)
        }
        delay(600) // let every cancel + the last resolve unwind

        assertEquals("only the LAST seek of a burst lands", listOf(6), landings.toList())
        assertTrue("stacked seeks never run all at once (old code did), peak=${peak.get()}", peak.get() <= 2)
        assertTrue("no stuck loading indicator", !gate.active)
    }
}