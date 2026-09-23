package orilumn.reader.collections

/**
 * C2-P2a: 同一性键 Map（键比 `===` 而非 `==`）。
 *
 * 排版 display 缓存以 `MarkupElement` 为键：不同元素结构可能相等，必须按同一性区分，
 * `HashMap` 会错误合并。JVM/Android 用 `IdentityHashMap`；iOS actual 留给 L 阶段。
 */
expect fun <K, V> identityMap(): MutableMap<K, V>
