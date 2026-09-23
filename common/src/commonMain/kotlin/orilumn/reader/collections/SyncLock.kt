package orilumn.reader.collections

/**
 * C2-P2a: 平台互斥锁（非挂起路径用；挂起路径继续用协程 `Mutex`）。
 *
 * stdlib 的 `kotlin.concurrent.SynchronizedObject` 在本工程 common 源集不可见，
 * 自建缝更可控：JVM/Android/Desktop 用 `synchronized`，iOS actual 留给 L 阶段。
 * 非内联（调用点写成表达式风格，不要在块里 `return`）。
 */
expect class SyncLock()

expect fun <T> SyncLock.withLock(block: () -> T): T
