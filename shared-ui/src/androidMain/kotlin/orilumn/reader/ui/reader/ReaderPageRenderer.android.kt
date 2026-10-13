package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.io.Logger
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * Android actual：离屏 skia 光栅化整页 → `android.graphics.Bitmap` → Compose 画布。
 *
 * 为什么不能直画：CMP 的 DrawScope 在 Android 上是 `android.graphics.Canvas`，
 * 取不出 skia Canvas，故用离屏 [SkiaSurface] 走与 Desktop **同一份** [drawPageContent]
 * （绘制顺序/几何/占位语义单源，见 `PageRaster.kt`），再把像素桥回平台位图。
 *
 * 像素桥（P0a，取代 JPEG 单跳）：`Image.readPixels` 进 skia Bitmap → `readPixels(RGBA_8888)`
 * 拿行序确定的字节 → `Bitmap.copyPixelsFromBuffer`。纯 memcpy，真机实测 13–20ms
 * （旧路 `encodeToData(JPEG,85)+decodeByteArray` 约 100ms 且有损）。RGBA_8888 显式指定：
 * skia 的 N32 字节序随平台变，不指定就会在某些设备上红蓝互换。
 *
 * 页缓存（P0b）：[PageRasterStore] 多页 LRU，键是页身份（见 `PageRasterStore.kt`）。
 * 预算按字节给（`bmp.width*height*4`），淘汰时**显式 recycle**——Bitmap 像素在 native 侧，
 * 只靠 GC 会一路涨到 OOM（P0a 的单槽版本每次替换都 recycle，正是为此）。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer {
    val ctx = LocalContext.current
    val scale = remember { readRasterScaleDebug(ctx) ?: PAGE_RASTER_SCALE }
    val cacheBytes = remember { readCacheBytesDebug(ctx) ?: PAGE_CACHE_BYTES }
    return remember { AndroidReaderPageRenderer(ctx.applicationContext, scale, cacheBytes) }
}

/**
 * 临时调试（「缩小截图」真机验证用，定稿前删）：用 `Settings.Global` 覆盖
 * [PAGE_RASTER_SCALE]，一次构建即可在平板上扫多个档（改完须重启进程）：
 *
 * ```
 * adb shell settings put global orilumn_raster_scale 0.7
 * adb shell am force-stop orilumn.reader && adb shell am start -n orilumn.reader/.MainActivity
 * adb shell settings delete global orilumn_raster_scale   # 恢回常量默认
 * ```
 *
 * 未设/非法（不在 0.1..1）则回退常量默认。
 */
private const val RASTER_SCALE_SETTING = "orilumn_raster_scale"

private fun readRasterScaleDebug(context: android.content.Context): Float? = runCatching {
    android.provider.Settings.Global.getString(context.contentResolver, RASTER_SCALE_SETTING)
        ?.trim()?.toFloat()?.takeIf { it in 0.1f..1f }
}.getOrNull()

/**
 * 临时调试（同上，定稿前删）：用 `Settings.Global` 覆盖 [PAGE_CACHE_BYTES]（单位 MB），
 * 用于在真机上量「池容量 → 预栅格重做率」。
 *
 * ```
 * adb shell settings put global orilumn_page_cache_mb 192
 * ```
 */
private const val CACHE_MB_SETTING = "orilumn_page_cache_mb"

private fun readCacheBytesDebug(context: android.content.Context): Long? = runCatching {
    android.provider.Settings.Global.getString(context.contentResolver, CACHE_MB_SETTING)
        ?.trim()?.toLongOrNull()?.takeIf { it in 16L..1024L }?.times(1024L * 1024L)
}.getOrNull()

/**
 * skia Image → Compose [ImageBitmap]（Android actual，零编码像素搬运）。
 * 失败返回 null 由调用方回退（栅格化是显示路径，坏了不该崩阅读）。
 */
actual fun skiaImageToImageBitmap(image: org.jetbrains.skia.Image): ImageBitmap? =
    skiaImageToAndroidBitmap(image, image.width, image.height)?.asImageBitmap()

/** 页位图缓存预算（字节）：约 6 页（1200×1800 的页 ≈ 8.6MB）。低端机按内存调小。 */
private const val PAGE_CACHE_BYTES = 56L * 1024 * 1024

private class AndroidReaderPageRenderer(
    context: android.content.Context,
    /** 本次运行的栅格缩放（调试覆盖优先，见 [readRasterScaleDebug]）。 */
    private val rasterScale: Float,
    /** 页位图缓存预算（调试覆盖优先，见 [readCacheBytesDebug]）。 */
    cacheBytes: Long = PAGE_CACHE_BYTES,
) : ReaderPageRenderer {
    private val cacheBudgetMb = cacheBytes / 1024 / 1024
    // 淘汰回收的落点：预栅格已移到后台线程（见 Preraster.kt），LRU 淘汰可能发生在后台，
    // 而 `Bitmap.recycle()` 会把 native 像素立刻释放——若 UI 正在画同一张就崩/花屏。
    // 统一 post 到主线程回收：与绘制同线程，排在该帧之后，不会回收正在画的位图。
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 命中判据 / 池容量 / 淘汰 / 离屏 surface 生命周期全在 store 里（与 Desktop 同一份，
    // 可脱离 GUI 直测，见 PageRasterStoreTest）；本 actual 只剩最后一跳：skia 图 → Bitmap。
    private val store = PageRasterStore<android.graphics.Bitmap>(
        maxBytes = cacheBytes,
        sizeOf = { it.width * it.height * 4L },
        onEvict = { bmp -> mainHandler.post { runCatching { bmp.recycle() } } },
        // 唯一的平台缝：零编码像素桥（取代旧 JPEG 单跳）。
        encode = { img -> skiaImageToAndroidBitmap(img, img.width, img.height) },
    )

    override fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground>,
        bgImages: Map<String, DecodedImage>,
        images: List<PageImageSlot>,
        contentRevision: Int,
        rasterKey: PageRasterKey?,
        onMiss: (() -> Unit)?,
    ) {
        // 无 key（封面等无页身份的调用）⇒ store 不入池，但照样栅格并画出来。
        val r = store.obtain(
            key = rasterKey,
            onMiss = onMiss,
            spec = PageRasterSpec(
                lines = lines,
                backgrounds = backgrounds,
                bgImages = bgImages,
                images = images,
                pageBg = pageBg,
                contentLeft = contentLeft,
                contentRectLeft = contentRectLeft,
                contentRectTop = contentRectTop,
                contentRight = contentRectRight,
                contentBottom = contentRectBottom,
                contentRevision = contentRevision,
                rasterScale = rasterScale,
            ),
        )
        if (r == null) {
            // 像素桥失败（OOM 等）：显示路径不该崩阅读，与 skiaImageToImageBitmap 同口径回退。
            Logger.w("Orilumn.SkiaBridge", "pixel bridge FAILED n=${lines.size} imgs=${images.size}")
            return
        }
        val (pages, bytes) = store.stats()
        Logger.w(
            "Orilumn.SkiaBridge",
            "raster n=${lines.size} imgs=${images.size} ${r.rasterMs}ms hit=${r.cacheHit} " +
                "px=${r.value.width}x${r.value.height} scale=$rasterScale " +
                "pool=$pages/${bytes / 1024 / 1024}MB budget=${cacheBudgetMb}MB " +
                "key=${rasterKey?.chapter}/${rasterKey?.charStart}-${rasterKey?.charEnd}" +
                " rev=${rasterKey?.contentRevision}",
        )
        drawPageBitmap(canvas, r.value, contentRectLeft, contentRectTop, contentRectRight, contentRectBottom)
    }

    /**
     * 预栅格：与 [drawLines] 走**同一个** store（见接口 KDoc 的「线程：限 UI/渲染线程」）。
     *
     * 复用 store 意味着 surface 也共用 —— 这正是接口 KDoc 说的「不能后台另开 surface」
     * 的原因：后台与 UI 共用一个 surface 必然出事。所以调用方必须在 UI 线程择时调用，
     * 引擎后台线程只抛信号（`ReaderHost.onPrefillReady`），执行落在这里。
     */
    override fun preraster(
        lines: List<DrawLine>,
        contentLeft: Float,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground>,
        bgImages: Map<String, DecodedImage>,
        images: List<PageImageSlot>,
        contentRevision: Int,
        rasterKey: PageRasterKey?,
    ): Boolean {
        // 无页身份（封面等）无法入池，直接跳过：预栅格只服务翻页动画。
        if (rasterKey == null) return false
        if (lines.isEmpty() && images.isEmpty() && backgrounds.isEmpty()) return false
        val r = store.obtain(
            key = rasterKey,
            onMiss = null, // 已在 UI 线程，无需再抢后台（第 1 档本就在前台）
            spec = PageRasterSpec(
                lines = lines,
                backgrounds = backgrounds,
                bgImages = bgImages,
                images = images,
                pageBg = pageBg,
                contentLeft = contentLeft,
                contentRectLeft = contentRectLeft,
                contentRectTop = contentRectTop,
                contentRight = contentRectRight,
                contentBottom = contentRectBottom,
                contentRevision = contentRevision,
                rasterScale = rasterScale,
            ),
        ) ?: return false
        Logger.w(
            "Orilumn.SkiaBridge",
            "preraster n=${lines.size} imgs=${images.size} ${r.rasterMs}ms hit=${r.cacheHit} " +
                "key=${rasterKey.chapter}/${rasterKey.charStart}-${rasterKey.charEnd}" +
                " rev=${rasterKey.contentRevision}",
        )
        return true
    }

    /**
     * 贴回内容区的 Paint：显式开 FILTER_BITMAP —— `rasterScale < 1`（代理位图）时是放大贴回，
     * 默认 Paint（不滤波）会走最近邻，屏幕上是 2×2 块状锯齿；双线性只是变柔，不会块状。
     */
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    private fun drawPageBitmap(
        canvas: Canvas,
        bmp: android.graphics.Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        (canvas.nativeCanvas as android.graphics.Canvas).drawBitmap(
            bmp,
            android.graphics.Rect(0, 0, bmp.width, bmp.height),
            android.graphics.RectF(left, top, right, bottom),
            bitmapPaint,
        )
    }
}

/**
 * skia 离屏产物 → `android.graphics.Bitmap`（内部版：已知尺寸，省一次查询；
 * [skiaImageToImageBitmap] 是对外接缝走同一条路）。
 */
private fun skiaImageToAndroidBitmap(
    image: org.jetbrains.skia.Image,
    w: Int,
    h: Int,
): android.graphics.Bitmap? = runCatching {
    val tmp = org.jetbrains.skia.Bitmap()
    tmp.allocN32Pixels(w, h, true)
    if (!image.readPixels(tmp)) return@runCatching null
    val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    val bytes = tmp.readPixels(info, w * 4, 0, 0) ?: return@runCatching null
    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
    }
}.getOrNull()