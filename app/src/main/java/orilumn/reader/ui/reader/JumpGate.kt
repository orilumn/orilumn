package orilumn.reader.ui.reader

import orilumn.reader.engine.paging.PageSlice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P6 — single-slot jump gate for the reader's three jump paths (`jumpChapter` / `seekTo` /
 * `tocJump`, all of which used to launch a fresh coroutine every time with no cancellation).
 *
 * Each [submit] cancels the previous in-flight jump and carries a fresh epoch; a resolved target
 * only lands through [land] when the request is STILL the latest (epoch-guard), so a stale jump
 * that was already past its own cancellation point can never clobber the current position.
 * Rapid bursts therefore converge on exactly one landing (the last request) instead of stacking
 * concurrent anchor paths.
 *
 * [onActive] drives the reader's light "loading" indicator: true on submit, false once the latest
 * jump has landed or resolved to nothing (a cancelled older request never clears a newer one).
 */
class JumpGate(
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val ui: CoroutineDispatcher = Dispatchers.Main,
    private val onActive: (Boolean) -> Unit = {},
    private val land: (Int, PageSlice) -> Unit,
) {
    private var job: Job? = null
    private var epoch = 0L

    /** Whether the gate currently holds an in-flight jump (drives the loading indicator).
     *  Written on submit (calling thread) and on completion (background thread); only the latest
     *  request's completion clears it. */
    @Volatile
    var active: Boolean = false
        private set

    /** Cancel the in-flight jump and dispatch a fresh one resolving [target]. The [land] callback
     *  runs on [ui] only when this request still is the newest one. */
    fun submit(target: suspend () -> Pair<Int, PageSlice>?) {
        val myEpoch = ++epoch
        job?.cancel()
        active = true
        onActive(true)
        job = scope.launch(background) {
            try {
                val resolved = target()
                withContext(ui) {
                    if (myEpoch == epoch && resolved != null) land(resolved.first, resolved.second)
                }
            } finally {
                if (myEpoch == epoch) {
                    active = false
                    onActive(false)
                }
            }
        }
    }

    /** Whether any jump is currently in flight or queued. */
    fun hasPending(): Boolean = active
}