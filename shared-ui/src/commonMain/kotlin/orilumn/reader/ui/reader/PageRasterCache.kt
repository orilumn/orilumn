package orilumn.reader.ui.reader

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground

/**
 * P0b 页位图的**稳定身份**（用户层·跨端共用）：一页像素的缓存键。
 *
 * 为什么需要它：P0a 的页缓存是「渲染器实例上的 1 个槽位 + 内容相等比对」，
 * 只能挡住同页重组，翻页必然重栅格。翻页动画要的是**邻页位图已就绪**，
 * 所以缓存必须能同时持有若干页 ⇒ 得有一个跨帧、跨重组稳定的身份。
 *
 * 键里必须有的东西（少任何一条都会静默复用旧像素）：
 *  - `chapter` + `charStart`/`charEnd`：页身份；
 *  - `widthPx`/`heightPx`：视口变了像素就得重画（旋转/窗口缩放/双页对开都走这条）；
 *  - `contentRevision`：**字重这类「行数据完全相等」的纯字形变更只能靠它失效**，
 *    比对相等看不见（旧 `ReaderPageRenderer` KDoc 记的就是这个教训）；
 *  - `bgColor`/`inkColor`：换主题色即时生效（墨色是绘制时盖章，不触发布局失效）。
 *
 * 不在键里的东西：行/背景/插图本身。它们进 [PageRasterFingerprint] 做**二次校验**——
 * 插图补解码这类变化改内容却不改键（首帧灰块 → 解码后真图），那种情况必须重画，
 * 否则灰块会一直留着。
 */
class PageRasterKey(
    val chapter: Int,
    val charStart: Int,
    val charEnd: Int,
    val widthPx: Int,
    val heightPx: Int,
    val contentRevision: Int,
    val bgColor: Int,
    val inkColor: Int,
) {
    /** 缓存 map 的键（字段全列，不用 hashCode：跨版本/跨进程不稳）。 */
    val id: String = "$chapter:$charStart-$charEnd@${widthPx}x$heightPx#$contentRevision:$bgColor:$inkColor"
}

/**
 * 页内容指纹：同 [PageRasterKey] 下「内容真的没变」的判据。
 *
 * `lines`/`backgrounds` 是 data class list，`==` **按值**比较（微秒级；
 * `===` 永不命中，因为 `shiftToPageFrame` 每次 map 出新 List——旧代码踩过）。
 * 插图/背景图是 skia `Image`，无值相等，按**实例**比：同页同实例即命中，
 * 补解码换了实例就 miss → 重画（这正是我们要的）。
 */
data class PageRasterFingerprint(
    val lines: List<DrawLine>,
    val backgrounds: List<PageBackground>,
    val bgImageKeys: Set<String>,
    val images: List<PageImageSlot>,
    val pageBg: Int,
    val contentRevision: Int,
    /**
     * 该页位图按哪个缩放栅格出来（[PageRasterSpec.rasterScale]）。进指纹是**防串档**：
     * 将来「静止全分辨率 + 动画代理」两级并存时，同一页会有 1f 与 0.5f 两张图，
     * 键相同，命中判据必须能把它们分开。
     */
    val rasterScale: Float = 1f,
)

/**
 * P0b 多页页位图缓存（用户层·跨端共用，按字节 LRU + 显式回收回调）。
 *
 * - 键 = [PageRasterKey.id]；值 = 平台位图 + 其 [PageRasterFingerprint]，命中要求键与指纹都对；
 * - 容量按字节（`sizeOf` 由平台给），超预算淘汰最久未用；
 * - **淘汰必须回调** [onEvict]：Android 的 `android.graphics.Bitmap` 像素在 native 侧，
 *   只靠 GC 回收会一路涨到 OOM（这正是 P0a 旧代码每次替换都 `recycle()` 的原因）。
 *   桌面侧 skia `Image` 有 finalizer，回调里 `close()` 只是更干净。
 *
 * 为什么不复用 `PageImageCache`：那个是给插图/背景图用的纯值 LRU，没有淘汰回调语义；
 * 页位图的回收是平台义务，两者混用会把「必须显式回收」这条约束藏掉。
 *
 * 线程（P0c）：预栅格移到后台线程后，**同一池**会被后台预栅格与 UI 绘制并发读写，
 * 故本类所有入口都过 [SyncLock]（只护 `map`/`bytes` 这一小段，命中路径也是这笔开销）。
 * [onEvict] 在锁内调用，实现方**不得**回调本类（会自锁），且应把真正的释放推迟到
 * 平台主线程——淘汰可能发生在后台线程，`recycle()` 正在绘制的那张会崩（Android actual 已推迟）。
 */
class PageRasterCache<T : Any>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
    private val onEvict: (T) -> Unit = {},
) {
    private class Entry<T : Any>(val value: T, val fingerprint: PageRasterFingerprint, val bytes: Long)

    private val lock = SyncLock()
    private val map = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private var bytes = 0L

    /** 命中返回位图；键或指纹不符返回 null（调用方重画并 [put]）。 */
    fun get(key: PageRasterKey, fingerprint: PageRasterFingerprint): T? = lock.withLock {
        map[key.id]?.takeIf { it.fingerprint == fingerprint }?.value
    }

    fun put(key: PageRasterKey, fingerprint: PageRasterFingerprint, value: T) {
        lock.withLock {
            val size = sizeOf(value).coerceAtLeast(1L)
            val prev = map.put(key.id, Entry(value, fingerprint, size))
            if (prev != null) {
                bytes -= prev.bytes
                if (prev.value !== value) onEvict(prev.value)
            }
            bytes += size
            evict()
        }
    }

    private fun evict() {
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next().value
            bytes -= e.bytes
            it.remove()
            onEvict(e.value)
        }
    }

    /** 清空（宿主换代/换书时调用，逐页回收）。 */
    fun clear() {
        lock.withLock {
            for (e in map.values) onEvict(e.value)
            map.clear()
            bytes = 0L
        }
    }

    /** 当前持有页数 + 字节数（诊断/测试用）。 */
    fun stats(): Pair<Int, Long> = lock.withLock { map.size to bytes }
}