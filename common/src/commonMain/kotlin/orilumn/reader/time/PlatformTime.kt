package orilumn.reader.time

/**
 * C2-P2b-1: 墙钟毫秒（profiling 计时用，无精度语义）。
 *
 * stdlib `kotlin.time.Clock` 在本工程 common 源集不可见，`System` 是 JVM-only，
 * 自建缝：JVM actual 一行，iOS actual 留给 L 阶段。
 */
expect fun platformNowMs(): Long
