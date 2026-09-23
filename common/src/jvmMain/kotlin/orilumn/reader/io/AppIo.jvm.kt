package orilumn.reader.io

import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * S30 actual —— 一套 jvmMain actual 服务 android + desktop 两个 jvmLike 平台（镜像
 * FontParser.jvm.kt 的共享 actual 接缝；androidMain.dependsOn(jvmMain) 使 android 复用同一文件）。
 */
private val rootRef = AtomicReference<RootHolder?>(null)

private class RootHolder(val root: Path, val fs: FileSystem)

public actual object AppRoot {
    public actual val root: Path?
        get() = rootRef.get()?.root

    public actual fun init(root: Path, fileSystem: FileSystem) {
        rootRef.compareAndSet(null, RootHolder(root, fileSystem))
    }

    public actual fun cleanForTest() {
        rootRef.set(null)
    }
}

private data class LogEntry(val level: Char, val tag: String, val msg: String, val tr: Throwable?)

private val logQueue = ConcurrentLinkedQueue<LogEntry>()
private val workerRef = AtomicReference<Thread?>(null)
private const val MAX_LINE_BYTES = 4000
private const val MAX_FILE_BYTES = 1L * 1024 * 1024
private const val ROTATE_SUFFIX = ".1"
private const val DATE_FORMAT = "yyyyMMdd"
private val LEVEL_LABEL = mapOf('D' to "D", 'I' to "I", 'W' to "W", 'E' to "E")

public actual object Logger {

    public actual fun d(tag: String, msg: String) = enqueue('D', tag, msg, null)
    public actual fun i(tag: String, msg: String) = enqueue('I', tag, msg, null)
    public actual fun w(tag: String, msg: String) = enqueue('W', tag, msg, null)
    public actual fun e(tag: String, msg: String) = enqueue('E', tag, msg, null)
    public actual fun e(tag: String, msg: String, tr: Throwable) = enqueue('E', tag, msg, tr)

    public actual fun batched(tag: String, lines: List<String>) {
        if (lines.isEmpty()) return
        ensureWorker()
        logQueue.add(LogEntry('I', tag, lines.joinToString("\n    ", prefix = "batched(", postfix = ")"), null))
    }

    private fun enqueue(level: Char, tag: String, msg: String, tr: Throwable?) {
        val holder = rootRef.get() ?: return
        // 先入队再 ensureWorker：worker 首次 poll 必定看到本条（镜像仓库约定——有状态 seam 的
        // 「先入队、后唤醒」落盘顺序，避免 worker 先行退出的丢唤醒竞态；FontParser 无此语义，状态无关）。
        logQueue.add(LogEntry(level, tag, msg, tr))
        ensureWorker()
    }

    private fun ensureWorker() {
        if (workerRef.get() != null) return
        // kotlin.concurrent.thread 默认【立即 start】；CAS 胜出者直接复用该线程（CAS 失败者让出，无二次 start）
        val t = thread(name = "orilumn-log", isDaemon = true, block = ::drain)
        workerRef.compareAndSet(null, t)
    }

    private fun drain() {
        try {
            while (true) {
                val entry = logQueue.poll() ?: break
                write(entry)
            }
        } finally {
            workerRef.set(null)
        }
    }

    private fun write(entry: LogEntry) {
        val holder = rootRef.get() ?: return
        val fs = holder.fs
        val root = holder.root
        val logsDir = root / "logs"
        safelyCreate(fs, logsDir)
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT))
        val file = logsDir / "日志_$date.txt"
        val line = buildLine(entry)
        if (line.isEmpty()) return
        try {
            // okio 3.9 精确调用面（容器内的实际签名）：appendingSink(Path, mustExist=) 追加、.buffer().use{writeUtf8}、
            // metadata(file).size 是 Long?（需 ?:0L）、轮转用 atomicMove（无 move()）。逐一与 :common 现有
            // OkioStore 落盘豆句（sink(buffer use writeUtf8)）同源，只是日志必须「追加」→ 用 appendingSink。
            fs.appendingSink(file, mustExist = false).buffer().use { sink ->
                sink.writeUtf8(line)
            }
            val size = fs.metadata(file).size ?: 0L
            if (size > MAX_FILE_BYTES) {
                val rotated = logsDir / "日志_$date$ROTATE_SUFFIX.txt"
                fs.delete(rotated, mustExist = false)
                fs.atomicMove(file, rotated)
            }
        } catch (_: Exception) {
            // 日志绝不影响业务：写盘/轮转失败静默忽略
        }
    }

    private fun buildLine(entry: LogEntry): String = buildString {
        append(entry.level)
        append(' ')
        append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")))
        append(" [").append(entry.tag).append("] ").append(entry.msg)
        entry.tr?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            append('\n').append(sw.toString())
        }
        append('\n')
    }.let { if (it.length > MAX_LINE_BYTES) it.substring(0, MAX_LINE_BYTES) + "…\n" else it }
}

private fun safelyCreate(fs: FileSystem, dir: Path) {
    if (!fs.exists(dir)) {
        try { fs.createDirectories(dir) } catch (_: Exception) {}
    }
}
