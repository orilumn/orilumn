package orilumn.reader.engine

import android.util.Log
import orilumn.reader.BuildConfig
import orilumn.reader.io.Logger

/**
 * Unified engine log gate (debug-only diagnostic facility).
 *
 * - Only `BuildConfig.DEBUG` (debug builds) produces output;
 * - on debug it writes both logcat (instant viewing) and common Logger
 *   (`files/logs/日志_*.txt`, readable via run-as);
 * - release builds skip everything (works with R8/removal, zero runtime overhead).
 *
 * Usage: every key engine/reader action (parse, layout, pagination, page flip, gestures) goes
 * through this, so `adb shell run-as orilumn.reader cat files/logs/日志_*.txt` can be used to
 * troubleshoot on real devices.
 */
object EngineLog {

    /** Whether the app enables engine logging (equivalent to BuildConfig.DEBUG). */
    val enabled: Boolean = BuildConfig.DEBUG

    /** Global auto-incrementing sequence number: each log line is prefixed with a timestamp + `#[000123]`,
 * for comparing/solving issues by time and operation order. */
    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun next(): String =
        String.format("%1\$tT.%1\$tL #[%2\$06d] ", System.currentTimeMillis(), seq.incrementAndGet())

    fun d(tag: String, msg: String) = emit('D', tag, msg)
    fun i(tag: String, msg: String) = emit('I', tag, msg)
    fun w(tag: String, msg: String) = emit('W', tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = emit('E', tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)

    private fun emit(level: Char, tag: String, msg: String) {
        if (!enabled) return
        val prefixed = next() + msg
        // Android runtime output (logcat + file); JVM unit-test envs have no Android runtime and would
        // throw not-mocked, so swallow silently so pure JVM classes (e.g. HtmlTreeConverter) can be
        // unit-tested independently.
        runCatching {
            when (level) {
                'D' -> { Logger.d(tag, prefixed); Log.d(tag, prefixed) }
                'I' -> { Logger.i(tag, prefixed); Log.i(tag, prefixed) }
                'W' -> { Logger.w(tag, prefixed); Log.w(tag, prefixed) }
                else -> { Logger.e(tag, prefixed); Log.e(tag, prefixed) }
            }
        }
    }
}