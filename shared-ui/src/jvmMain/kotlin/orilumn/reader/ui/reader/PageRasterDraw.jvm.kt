package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/**
 * 桌面 actual：按位图实例缓存 skia [Image]，再经 `nativeCanvas` 直画。
 *
 * Compose 桌面的 `DrawScope.drawImage(ImageBitmap)` 内部每次都 `Image.makeFromBitmap`，
 * 一整页的像素因此每帧重新交给 Skia 一次（等于每帧重传纹理）。翻页动画一秒 60 帧、
 * 一帧贴四张整页图时，这条路径把动画压到 8～20fps（见 [RasterDrawCache]）。
 *
 * 采样显式给 `LINEAR`：与 Compose `drawImage` 的默认 `FilterQuality.Low`（双线性）
 * 同档，避免「换了画法顺带把纹理变锯齿」这种静默回归。
 */
internal actual fun DrawScope.drawRasterCached(
    cache: RasterDrawCache,
    image: ImageBitmap,
    topLeft: Offset,
    alpha: Float,
) {
    val skia = (cache.handle as? Image)?.takeIf { cache.source === image }
        ?: Image.makeFromBitmap(image.asSkiaBitmap()).also {
            cache.source = image
            cache.handle = it
        }
    val w = skia.width.toFloat()
    val h = skia.height.toFloat()
    val paint = Paint()
    if (alpha < 1f) paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    drawContext.canvas.nativeCanvas.drawImageRect(
        skia,
        Rect.makeWH(w, h),
        Rect.makeXYWH(topLeft.x, topLeft.y, w, h),
        SamplingMode.LINEAR,
        paint,
        false,
    )
}
