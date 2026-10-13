package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.io.Logger
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage

/**
 * Desktop（JVM）actual：**离屏栅格整页 → skia Image → 画布**。
 *
 * P0a 之前这里是**直画**（行窗口直接交给 `LineWindowDrawer`，无离屏、无位图）。
 * 改成离屏不是为了好看，是三条硬需求：
 *  1. 翻页动画要「整页一张图」当纹理，桌面原来根本没有这个东西；
 *  2. 插图已下沉进渲染器（与 Android 同形），若仍直画就会「插图在 Compose 层、文字在离屏层」
 *     双真相，卷曲时插图不跟着卷；
 *  3. 有了整页图才能挂多页缓存（P0b），翻页邻页位图提前就绪。
 *
 * 代价：每页多一次整页拷贝（毫秒级 memcpy）。绘制顺序/几何见 [drawPageContent]（两端共用一份）。
 *
 * 平台差异只剩最后一跳：本端 `nativeCanvas` 就是 skia Canvas，缓存直接持 skia [SkiaImage]
 * 并按 1:1 贴回（动画层要纹理时也正是它，Skia runtime effect 可直接当子着色器）；
 * Android 那边 `nativeCanvas` 是 `android.graphics.Canvas`，必须桥成 `android.graphics.Bitmap`。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer = remember { JvmReaderPageRenderer() }

/** skia Image → Compose [ImageBitmap]（Desktop actual，零拷贝 backing）。 */
actual fun skiaImageToImageBitmap(image: SkiaImage): ImageBitmap? = runCatching {
    if (image.width <= 0 || image.height <= 0) return@runCatching null
    image.toComposeImageBitmap()
}.getOrNull()

/** 页位图缓存预算（字节）：约 8 页（桌面窗口更大，单页动辄 8MB+）。 */
private const val PAGE_CACHE_BYTES = 64L * 1024 * 1024

private class JvmReaderPageRenderer : ReaderPageRenderer {
    // 命中判据 / 池容量 / 淘汰 / 离屏 surface 生命周期全在 store 里（可脱离 GUI 直测，见
    // PageRasterStoreTest）；本 actual 只剩最后一跳：把 store 给的 skia Image 贴回画布。
    private val store = PageRasterStore<SkiaImage>(
        maxBytes = PAGE_CACHE_BYTES,
        sizeOf = { it.width * it.height * 4L },
        onEvict = { runCatching { it.close() } },
        // 恒等：nativeCanvas 就是 skia，零拷贝直接持 Image（动画层要纹理时也正是它）。
        encode = { it },
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
                rasterScale = PAGE_RASTER_SCALE,
            ),
        )
        if (r == null) {
            // 栅格化是显示路径，坏了不该崩阅读（对齐 skiaImageToImageBitmap 的回退口径）。
            Logger.w("Orilumn.Desktop", "page-raster FAILED n=${lines.size} imgs=${images.size}")
            return
        }
        val (pages, bytes) = store.stats()
        Logger.w(
            "Orilumn.Desktop",
            "page-raster n=${lines.size} imgs=${images.size} ${r.value.width}x${r.value.height} " +
                "${r.rasterMs}ms hit=${r.cacheHit} pool=$pages/${bytes / 1024 / 1024}MB",
        )
        // 贴回内容区。栅格缩放 = 1 时是 1:1（坐标取整避免亚像素抖动，保持与旧路径逐像素一致）；
        // 小于 1（代理位图）时按内容区放大，LINEAR 采样避免锯齿。
        val img = r.value
        val dstL = contentRectLeft.toInt().toFloat()
        val dstT = contentRectTop.toInt().toFloat()
        val dstW = (contentRectRight - contentRectLeft).toInt()
        val dstH = (contentRectBottom - contentRectTop).toInt()
        if (img.width == dstW && img.height == dstH) {
            canvas.skiaCanvas.drawImage(img, dstL, dstT)
        } else {
            canvas.skiaCanvas.drawImageRect(
                img,
                Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                Rect.makeLTRB(dstL, dstT, dstL + dstW, dstT + dstH),
                SamplingMode.LINEAR,
                Paint(),
                false,
            )
        }
    }

    /** 预栅格：与 [drawLines] 共用同一个 store，故同样限 UI 线程（见接口 KDoc）。 */
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
        if (rasterKey == null) return false
        if (lines.isEmpty() && images.isEmpty() && backgrounds.isEmpty()) return false
        val r = store.obtain(
            key = rasterKey,
            onMiss = null,
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
                rasterScale = PAGE_RASTER_SCALE,
            ),
        )
        if (r == null) {
            Logger.w("Orilumn.Desktop", "preraster FAILED n=${lines.size} imgs=${images.size}")
            return false
        }
        val (pages, bytes) = store.stats()
        Logger.w(
            "Orilumn.Desktop",
            "preraster n=${lines.size} imgs=${images.size} ${r.rasterMs}ms hit=${r.cacheHit} " +
                "pool=$pages/${bytes / 1024 / 1024}MB",
        )
        return true
    }
}