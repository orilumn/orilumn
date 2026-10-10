package orilumn.reader.ui.reader

import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Surface as SkiaSurface
import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import kotlin.math.roundToInt

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
    /**
     * 栅格缩放（1f = 与内容区 1:1 全分辨率）。小于 1 ⇒ 离屏 surface 按比例缩小，
     * 贴回内容区时再放大（见 [PageRasterStore.obtain]）。
     *
     * 这是「两级分辨率」的**第二级**（设计文档 §3.3）：卷曲/滑动过程中纹理被
     * 压缩/拉伸，全分辨率无视觉收益，1/2 只有 1/4 内存。当前 [PAGE_RASTER_SCALE]
     * 取值见 `PageRaster.kt`。
     */
    val rasterScale: Float = 1f,
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
            rasterScale = rasterScale,
        )

    /** 内容区宽（逻辑像素，= 贴回的目标宽）。 */
    val widthPx: Int get() = (contentRight - contentRectLeft).toInt().coerceAtLeast(1)

    /** 内容区高（逻辑像素，= 贴回的目标高）。 */
    val heightPx: Int get() = (contentBottom - contentRectTop).toInt().coerceAtLeast(1)

    /** 离屏 surface 实际像素宽（= 内容区 × [rasterScale]）。 */
    val surfaceWidthPx: Int get() = (widthPx * rasterScale).roundToInt().coerceAtLeast(1)

    /** 离屏 surface 实际像素高（= 内容区 × [rasterScale]）。 */
    val surfaceHeightPx: Int get() = (heightPx * rasterScale).roundToInt().coerceAtLeast(1)
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
 * 线程（P0c）：预栅格已移到后台线程（见 `Preraster.kt`），与 UI 绘制**共用同一个 store**。
 * 分工是「命中走缓存自己的锁、重画进本类的 [renderLock]」——命中路径完全绕开 surface，
 * 所以翻页动画里的每帧命中不会被后台预栅格挡住；只有真正重画才在 surface 上串行。
 * [PageRasterCache]、画布与 `surface/width/height` 均受锁保护，可被 UI/后台两条线程交替调用。
 */
class PageRasterStore<T : Any>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
    private val onEvict: (T) -> Unit = {},
    private val encode: (SkiaImage) -> T?,
    drawer: LineWindowDrawer = LineWindowDrawer(),
) {
    private val drawer = drawer
    private val renderLock = SyncLock()
    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1
    private val cache = PageRasterCache<T>(maxBytes, sizeOf, onEvict)

    /**
     * 取一页位图。命中直接返回；未命中真正栅格化。
     *
     * @param onMiss **仅在未命中、即将真正栅格化时**调用一次（宿主用来在「第 1 档渲染」
     *   开始前抢占后台预排 —— 见 `线程调度原则.md` §3.6/§5 与 AGENTS.md 总则第 1 条：
     *   栅格是第 1 档的一部分，必须像翻页一样让后台让路，否则第 7 档整章全量会把绘制
     *   线程压住。命中路径**不调**（每帧命中是常态，调了等于每帧都抢）。
     */
    fun obtain(key: PageRasterKey?, spec: PageRasterSpec, onMiss: (() -> Unit)? = null): PageRasterResult<T>? {
        val fingerprint = spec.fingerprint()
        // 命中快路径：不碰 surface，不抢 renderLock（翻页每帧命中是常态，不能排队）。
        if (key != null) {
            cache.get(key, fingerprint)?.let { return PageRasterResult(it, true, 0L) }
        }
        onMiss?.invoke() // 第 1 档渲染开始 —— 让后台预排让路（一次性，仅未命中）
        return renderLock.withLock {
            // 双检：进锁前另一条线程可能刚画好同一页（预栅格与绘制同时要这张）。
            if (key != null) {
                cache.get(key, fingerprint)?.let { return@withLock PageRasterResult(it, true, 0L) }
            }
            // 内容区尺寸（逻辑，贴回目标）与离屏 surface 尺寸（= 内容区 × 缩放）分开：
            // 下面把绘制坐标一律当「内容区坐标」交给 drawPageContent，缩放由画布矩阵承担。
            val w = spec.widthPx
            val h = spec.heightPx
            val sw = spec.surfaceWidthPx
            val sh = spec.surfaceHeightPx
            if (surface == null || sw != width || sh != height) {
                width = sw
                height = sh
                surface?.close()
                surface = SkiaSurface.makeRasterN32Premul(sw, sh)
                cache.clear() // 视口/缩放变了：旧尺寸页整池回收
            }
            val s = surface ?: return@withLock null
            val t0 = System.nanoTime()
            // 缩放代理：surface 小于内容区时，把画布矩阵整体缩放，
            // drawPageContent 仍按内容区坐标画（几何/顺序单源，两端一致），
            // 贴回时再由平台侧放大到内容区。
            val canvas = s.canvas
            val saveCount = canvas.save()
            if (spec.rasterScale != 1f) canvas.scale(spec.rasterScale, spec.rasterScale)
            drawPageContent(
                canvas = canvas,
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
            canvas.restoreToCount(saveCount)
            // encode 拿走 snapshot 的所有权：桌面恒等（零拷贝，直接持 skia Image），Android 转 Bitmap。
            // 所以只有 encode 失败时才在这里回收——成功路径回收等于把返回给调用方的页图提前释放。
            val snapshot = s.makeImageSnapshot()
            val value = encode(snapshot)
            if (value == null) {
                snapshot.close()
                return@withLock null
            }
            if (key != null) cache.put(key, fingerprint, value)
            PageRasterResult(value, false, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /** 池状态（诊断/测试用）：页数 + 字节数。 */
    fun stats(): Pair<Int, Long> = cache.stats()

    /** 显式清空（宿主换代/换书）。 */
    fun clear() = cache.clear()
}