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
 */
class PageImageCache<T : Any>(
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val sizeOf: (T) -> Long,
) {
    private val map = LinkedHashMap<String, T>(16, 0.75f, true)
    private var bytes: Long = 0L

    fun get(key: String): T? = map[key]

    fun put(key: String, value: T) {
        val prev = map.put(key, value)
        bytes += sizeOf(value) - (prev?.let(sizeOf) ?: 0L)
        evict()
    }

    private fun evict() {
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= sizeOf(it.next().value)
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        bytes = 0L
    }
}
