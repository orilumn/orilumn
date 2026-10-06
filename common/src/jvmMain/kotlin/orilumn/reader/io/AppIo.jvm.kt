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
import java.util.concurrent.atomic.AtomicInteger
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
/** 队列深度（`size()` 是 O(n)，高频路径不用它）。 */
private val queueDepth = AtomicInteger(0)
/** 拥塞时丢掉的行数（drain 到水位线下时记一条 marker，不断证据链）。 */
private val droppedLines = AtomicInteger(0)
private val workerRef = AtomicReference<Thread?>(null)
private const val MAX_LINE_BYTES = 4000
/** 内存队列上限（条）。行均 ~200B，2000 条 ≈ 0.5MB —— 突发再大堆也不涨。 */
private const val MAX_QUEUE = 2000
private const val MAX_FILE_BYTES = 1L * 1024 * 1024
private const val ROTATE_SUFFIX = ".1"
// 同日最多保留两个备份（.1/.2，加上当前共三个 txt）：此前只留一份，第二次轮转就删掉
// 第一段，崩溃上下文正好落第一段里就永远找不回来。
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
        // 有界队列：B2 整书等突发可达上万行/秒，落盘（每行 open/append/close+metadata）跟不上时
        // 无界增长即 OOM（“日志绝不影响业务”落空）。超限丢最老，水位回到一半；丢数由 drain 记 marker。
        if (queueDepth.incrementAndGet() > MAX_QUEUE) {
            var target = queueDepth.get() - MAX_QUEUE / 2
            while (target-- > 0) {
                if (logQueue.poll() == null) break
                queueDepth.decrementAndGet()
                droppedLines.incrementAndGet()
            }
        }
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
                // 拥塞 marker：丢弃随时发生（本轮 burst 中段），每轮先报数再写行，
                // 让读者知道中间丢过行，而不是误判时间线连续。
                val dropped = droppedLines.getAndSet(0)
                if (dropped > 0) write(LogEntry('W', "Orilumn.LOG", "burst-dropped $dropped lines (queue cap $MAX_QUEUE)", null))
                val entry = logQueue.poll() ?: break
                queueDepth.decrementAndGet()
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
                val second = logsDir / "日志_$date.2.txt"
                val rotated = logsDir / "日志_$date$ROTATE_SUFFIX.txt"
                if (fs.exists(rotated)) runCatching {
                    fs.delete(second, mustExist = false)
                    fs.atomicMove(rotated, second)
                }
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
