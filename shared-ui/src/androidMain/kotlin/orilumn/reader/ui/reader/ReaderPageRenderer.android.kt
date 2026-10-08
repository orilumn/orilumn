package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.io.Logger
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * Android actual：离屏 skia 光栅化整页 → `android.graphics.Bitmap` → Compose 画布。
 *
 * 为什么不能直画：CMP 的 DrawScope 在 Android 上是 `android.graphics.Canvas`，
 * 取不出 skia Canvas，故用离屏 [SkiaSurface] 走与 Desktop **同一份** [drawPageContent]
 * （绘制顺序/几何/占位语义单源，见 `PageRaster.kt`），再把像素桥回平台位图。
 *
 * 像素桥（P0a，取代 JPEG 单跳）：`Image.readPixels` 进 skia Bitmap → `readPixels(RGBA_8888)`
 * 拿行序确定的字节 → `Bitmap.copyPixelsFromBuffer`。纯 memcpy（1200×1800 约 8.6MB），
 * 而旧路 `encodeToData(JPEG,85)+decodeByteArray` 实测 ~100ms/页且有损——
 * 动画要按帧拿页纹理，100ms/页直接不可用。RGBA_8888 显式指定：skia 的 N32 字节序
 * 随平台变（N32 在小端是 BGRA），不指定就会在某些设备上红蓝互换。
 *
 * 页缓存：同页命中直接 `drawBitmap` 复用成品位图，零整形+零栅格化。命中条件必须含
 * [contentRevision]（字重这类"行数据完全相等"的纯字形变更只能靠它失效，见字段注释）。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer = remember { AndroidReaderPageRenderer() }

/**
 * skia Image → Compose [ImageBitmap]（Android actual，零编码像素搬运）。
 * 失败返回 null 由调用方回退（不抛：栅格化是显示路径，坏了不该崩阅读）。
 */
actual fun skiaImageToImageBitmap(image: org.jetbrains.skia.Image): ImageBitmap? =
    skiaImageToAndroidBitmap(image, image.width, image.height)?.asImageBitmap()

private class AndroidReaderPageRenderer : ReaderPageRenderer {
    private val drawer = LineWindowDrawer()

    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1
    private var cachedLines: List<DrawLine>? = null
    private var cachedBg: Int = 0
    private var cachedBgs: List<PageBackground>? = null
    private var cachedBgImgKeys: Set<String>? = null
    /** P0a：插图也进缓存比对（DecodedImage 无值相等，按实例比，命中即可——同页同实例）。 */
    private var cachedImages: List<PageImageSlot>? = null
    private var cachedRevision: Int = -1
    private var cachedPage: android.graphics.Bitmap? = null

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
    ) {
        val w = (contentRectRight - contentRectLeft).toInt().coerceAtLeast(1)
        val h = (contentRectBottom - contentRectTop).toInt().coerceAtLeast(1)
        if (surface == null || w != width || h != height) {
            width = w
            height = h
            surface?.close()
            surface = SkiaSurface.makeRasterN32Premul(w, h)
            cachedLines = null
            cachedPage?.recycle()
            cachedPage = null
        }
        // 同页命中：行、底色、背景矩形、背景图键集、插图槽位、修订号都相等才复用。
        // shiftToPageFrame 每次 map 出新 List，=== 永不命中；data class == 按值比对是微秒级。
        val bgKeys = backgrounds.mapNotNullTo(HashSet()) { it.bgKey()?.takeIf { k -> bgImages.containsKey(k) } }
        val hit = cachedPage?.takeIf {
            pageBg == cachedBg && backgrounds == cachedBgs && lines == cachedLines &&
                bgKeys == cachedBgImgKeys && images == cachedImages && contentRevision == cachedRevision
        }
        if (hit != null) {
            drawPageBitmap(canvas, hit, contentRectLeft, contentRectTop, contentRectRight, contentRectBottom)
            return
        }

        val t0 = android.os.SystemClock.uptimeMillis()
        val s = surface!!
        // [drawPageContent] 收口了「一页像素长什么样」+ 坐标换算：打底 → 盒背景 → 文字 → 插图，
        // 页坐标 → 离屏坐标（减 contentRectTop/Leftover）由它统一做，本 actual 不再各自平移
        // （P0a 初版在这里只平移了 lines/backgrounds 而漏 images，插图整体下移一个上边距）。
        drawPageContent(
            canvas = s.canvas,
            contentLeft = contentLeft,
            contentRectLeft = contentRectLeft,
            contentRectTop = contentRectTop,
            lines = lines,
            backgrounds = backgrounds,
            bgImages = bgImages,
            images = images,
            pageBg = pageBg,
            w = w,
            h = h,
            drawer = drawer,
        )
        val t1 = android.os.SystemClock.uptimeMillis()
        val bmp = skiaImageToAndroidBitmap(s.makeImageSnapshot(), w, h)
        val t2 = android.os.SystemClock.uptimeMillis()
        if (bmp == null) {
            Logger.w("Orilumn.SkiaBridge", "pixel bridge FAILED w=$w h=$h shape=${t1 - t0}ms bridge=${t2 - t1}ms")
            return
        }
        Logger.w("Orilumn.SkiaBridge", "raster n=${lines.size} imgs=${images.size} shape=${t1 - t0}ms bridge=${t2 - t1}ms")
        cachedLines = lines
        cachedBg = pageBg
        cachedBgs = backgrounds
        cachedBgImgKeys = bgKeys
        cachedImages = images
        cachedRevision = contentRevision
        cachedPage?.recycle()
        cachedPage = bmp
        drawPageBitmap(canvas, bmp, contentRectLeft, contentRectTop, contentRectRight, contentRectBottom)
    }

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
            null,
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