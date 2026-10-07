package orilumn.reader.engine

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用户层·壳：Java 未捕获异常落盘（与 common `Logger` 同目录 `files/logs/日志_YYYYMMDD.txt`）。
 *
 * 背景：此前崩溃只进 logcat（按隐私规定不拉），落盘日志里永远看不到堆栈 ——
 * “设标题字体后退回书架”这类疑似进程死亡只能靠猜。 native 崩溃 / OOM-kill
 * 不经过此处（无 Java 堆栈可记），仍需 logcat 或 tombstone。
 *
 * 实现：进程内装一次（双 Activity 各调，幂等），记完调用原默认处理器（系统照常杀进程、
 * 出崩溃框），行为除多一行落盘外零变化。写盘同步直写（Logger 是后台线程，来不及刷）。
 */
object CrashLog {
    private const val TAG = "Orilumn.CRASH"
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024

    private val installed = AtomicBoolean(false)
    private val lock = Any()

    /** 在 `AppRoot.init` 之后调（目录语义与其一致）；重复调用无操作。 */
    fun install(filesDir: File) {
        if (!installed.compareAndSet(false, true)) return
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(filesDir, thread, throwable) }
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrash(filesDir: File, thread: Thread, throwable: Throwable) {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val dir = File(filesDir, "logs")
        synchronized(lock) {
            runCatching { dir.mkdirs() }
            val file = File(dir, "日志_$date.txt")
            // 1MB 上限：超了也不丢这次崩溃（崩溃可能是最后一次写），直接追加。
            if (file.exists() && file.length() > MAX_FILE_BYTES * 2) return
            val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val trace = Log.getStackTraceString(throwable)
            runCatching {
                file.appendText("E $now [$TAG] FATAL thread=${thread.name} ${throwable}\n$trace\n")
            }
        }
    }
}
