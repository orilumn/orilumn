package orilumn.reader.ui.reader

/**
 * 用户层共享：阅读面位图跨页 LRU 缓存（平板/桌面同用，`ReaderScreen` 持有）。
 *
 * 背景：位图 map 曾以页为键（`remember(pos)`），翻页即清空重解 —— 含灰占位在内每页必闪一次，
 * 来回翻页也闪。本缓存以**稳定身份**为键（与几何无关：同图跨页 `yTop/widthPx` 不同也命中），
 * 翻页/改参不再清空；回访页首帧即有图，灰闪只剩首次解码。
 *
 * - 条目按字节计费（`sizeOf` 由调用方给：`ImageBitmap` 按 `w*h*4`，`DecodedImage` 按内图同式），
 *   超 `maxBytes` 淘汰最久未用；失败/空解码不入库（下次重解）。
 * - 键必须含书定位（`chapterHref`）：同 `src` 在不同书是不同字节；宿主切换（换书）时调用方
 *   重建本缓存（`remember(currentHost)`），双保险。
 * - **刚放入的这一批（同一页的工作集）永不淘汰**，见 [putAll] 的 KDoc —— 容量上界因此是
 *   `max(maxBytes, 一页工作集)`，不是 `maxBytes`。这是刻意的：单条大于 `maxBytes` 的条目
 *   若被自己淘汰，调用方会「解码 → 放入 → 取不到」死循环，那张图**永远**显示不出来。
 */
class PageImageCache<T : Any>(
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val sizeOf: (T) -> Long,
) {
    private val map = LinkedHashMap<String, T>(16, 0.75f, true)
    private var bytes: Long = 0L

    fun get(key: String): T? = map[key]

    /** 单条放入（等价于 [putAll] 只有一条）。 */
    fun put(key: String, value: T) = putAll(listOf(key to value))

    /**
     * **一页的全部位图整批放入**（调用方一次解码多图后必须走这里，不要逐条 [put]）。
     *
     * 本批的键在本次淘汰中受保护 ⇒ 同一页的多张图只要一次交出，就一定全部留得住；
     * 逐条 put 会让后一条把前一条（同页仍在用）淘汰掉 ⇒ 同页只画得出一张图。
     *
     * 保护只作用于**本批**：下一次 put 时它们已不再受保护，照常参与 LRU 淘汰。
     */
    fun putAll(entries: List<Pair<String, T>>) {
        if (entries.isEmpty()) return
        for ((key, value) in entries) {
            val prev = map.put(key, value)
            bytes += sizeOf(value) - (prev?.let(sizeOf) ?: 0L)
        }
        evict(protect = entries.mapTo(HashSet(entries.size)) { it.first })
    }

    /**
     * 淘汰最久未用，直到 `bytes <= maxBytes`，但**绝不淘汰 [protect]**（本批刚放入的）。
     *
     * 迭代序是 LRU → MRU（access-order `LinkedHashMap`），所以先牺牲最久未用的；
     * 若剩下的全是本批（单条/整页工作集本身就超预算），到此为止：宁可超预算，
     * 也不能把调用方刚要的东西淘汰掉。`bytes <= maxBytes` 不是硬保证而是目标值。
     */
    private fun evict(protect: Set<String>) {
        if (bytes <= maxBytes) return
        val it = map.entries.iterator()
        while (it.hasNext() && bytes > maxBytes) {
            val entry = it.next()
            if (entry.key in protect) continue
            bytes -= sizeOf(entry.value)
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        bytes = 0L
    }
}
