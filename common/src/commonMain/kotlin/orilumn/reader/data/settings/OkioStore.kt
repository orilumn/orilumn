package orilumn.reader.data.settings

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Small okio helpers for settings persistence (S18: java.io.File → okio FileSystem).
 */
internal fun FileSystem.writeTextAtomic(path: Path, text: String) {
    // 每次落盘用独立 tmp：并发 save 互不踩踏（同名 tmp 会出现 A 写/B 写/A 移/B 移空的竞态）。
    // 替换带重试：macOS 上同步盘/索引/杀软短暂锁文件会让 delete 直接抛 IOException；
    // 重试 3 次仍失败则降级直写。函数整体不抛异常——设置落盘是 best-effort，
    // load() 本来就有 DEFAULT 兜底，绝不能为一次落盘炸掉调用方协程。
    val tmp = "$path.${Random.nextLong().toString(16)}.tmp".toPath()
    runCatching { sink(tmp).buffer().use { it.writeUtf8(text) } }.getOrElse { return }
    repeat(3) {
        runCatching {
            try {
                atomicMove(tmp, path)
            } catch (e: IOException) {
                // ATOMIC_MOVE 不覆盖已存在目标：删后再移（删失败即抛，走下次重试）。
                delete(path)
                atomicMove(tmp, path)
            }
            return
        }
        // 重试前不删 tmp（名字唯一，下轮还用它；进程崩了也只是残留一个小文件）。
    }
    // 最终降级：直写目标（非原子，但不断连）。
    runCatching { sink(path).buffer().use { it.writeUtf8(text) } }
    runCatching { delete(tmp) }
}

/** Returns a new [Path] with [suffix] appended to the last path segment (e.g. "reader.json" → "reader.json.tmp"). */
internal fun Path.withSuffix(suffix: String): Path = (toString() + suffix).toPath()