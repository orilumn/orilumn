package orilumn.reader.ui.reader

import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Surface as SkiaSurface
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground

/**
 * P0b 栅格请求（**页坐标**，未归一）：一页像素的全部输入。
 *
 * 把「请求」与「画」拆开是这层存在的理由：
 *  - 命中判据、池容量、淘汰、离屏 surface 生命周期全在 [PageRasterStore]，可脱离 GUI 直测；
 *  - 平台 actual 只剩最后一跳「把 skia 图变成平台位图并画出去」。
 */
class PageRasterSpec(
    val lines: List<DrawLine>,
    val backgrounds: List<PageBackground>,
    val bgImages: Map<String, DecodedImage>,
    val images: List<PageImageSlot>,
    val pageBg: Int,
    val contentLeft: Float,
    val contentRectLeft: Float,
    val contentRectTop: Float,
    val contentRight: Float,
    val contentBottom: Float,
    val contentRevision: Int,
) {
    /** 内容指纹：同键下「内容没变」的判据（插图补解码会改它）。 */
    fun fingerprint(): PageRasterFingerprint =
        PageRasterFingerprint(
            lines = lines,
            backgrounds = backgrounds,
            bgImageKeys = backgrounds.mapNotNullTo(HashSet()) { it.bgKey()?.takeIf { k -> bgImages.containsKey(k) } },
            images = images,
            pageBg = pageBg,
            contentRevision = contentRevision,
        )

    val widthPx: Int get() = (contentRight - contentRectLeft).toInt().coerceAtLeast(1)
    val heightPx: Int get() = (contentBottom - contentRectTop).toInt().coerceAtLeast(1)
}

/** [PageRasterStore.obtain] 的结果，顺带把「这次是命中还是重画」带出来给日志/测试看。 */
class PageRasterResult<T : Any>(
    val value: T,
    val cacheHit: Boolean,
    val rasterMs: Long,
)

/**
 * P0b 页栅格仓库（用户层·跨端共用）：离屏 surface + 多页 LRU + 命中判据。
 *
 * 职责边界（刻意收窄）：
 *  - [obtain]：命中就返回缓存页；否则用 [drawPageContent] 栅格整页、经 [encode] 变成平台位图入池；
 *  - 视口尺寸变化 ⇒ 池全清（尺寸都不对了，留着白占内存）；
 *  - **不做**任何坐标/几何决策（那是 `drawPageContent` 的事）、**不碰**排版与分页。
 *
 * [encode] 是唯一的平台缝：Desktop 传恒等（零拷贝，直接持 skia `Image`），
 * Android 传「skia Image → `android.graphics.Bitmap`」的零编码像素桥。
 * 它**接管传入 snapshot 的所有权**（恒等时返回的就是 snapshot 本身），不得提前 close。
 *
 * 线程：**限 UI/渲染线程**。后台预栅格（翻页动画要的那条）在验证线程安全后另开一条路，
 * 不在本类里开——后台与 UI 共用一个 raster surface 必然出事。
 */
class PageRasterStore<T : Any>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
    private val onEvict: (T) -> Unit = {},
    private val encode: (SkiaImage) -> T?,
    drawer: LineWindowDrawer = LineWindowDrawer(),
) {
    private val drawer = drawer
    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1
    private val cache = PageRasterCache<T>(maxBytes, sizeOf, onEvict)

    fun obtain(key: PageRasterKey?, spec: PageRasterSpec): PageRasterResult<T>? {
        val w = spec.widthPx
        val h = spec.heightPx
        if (surface == null || w != width || h != height) {
            width = w
            height = h
            surface?.close()
            surface = SkiaSurface.makeRasterN32Premul(w, h)
            cache.clear() // 视口变了：旧尺寸页整池回收
        }
        val fingerprint = spec.fingerprint()
        if (key != null) {
            cache.get(key, fingerprint)?.let { return PageRasterResult(it, true, 0L) }
        }
        val s = surface ?: return null
        val t0 = System.nanoTime()
        drawPageContent(
            canvas = s.canvas,
            contentLeft = spec.contentLeft,
            contentRectLeft = spec.contentRectLeft,
            contentRectTop = spec.contentRectTop,
            lines = spec.lines,
            backgrounds = spec.backgrounds,
            bgImages = spec.bgImages,
            images = spec.images,
            pageBg = spec.pageBg,
            w = w,
            h = h,
            drawer = drawer,
        )
        // encode 拿走 snapshot 的所有权：桌面恒等（零拷贝，直接持 skia Image），Android 转 Bitmap。
        // 所以只有 encode 失败时才在这里回收——成功路径回收等于把返回给调用方的页图提前释放。
        val snapshot = s.makeImageSnapshot()
        val value = encode(snapshot)
        if (value == null) {
            snapshot.close()
            return null
        }
        if (key != null) cache.put(key, fingerprint, value)
        return PageRasterResult(value, false, (System.nanoTime() - t0) / 1_000_000)
    }

    /** 池状态（诊断/测试用）：页数 + 字节数。 */
    fun stats(): Pair<Int, Long> = cache.stats()

    /** 显式清空（宿主换代/换书）。 */
    fun clear() = cache.clear()
}