package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Android actual：直接交回 Compose 的 `drawImage`。
 *
 * Android 的 `ImageBitmap` 本来就是平台位图，画的时候不做像素搬运，不需要像桌面那样
 * 另缓存画柄（见 [RasterDrawCache]）。
 */
internal actual fun DrawScope.drawRasterCached(
    cache: RasterDrawCache,
    image: ImageBitmap,
    topLeft: Offset,
    alpha: Float,
    generation: Int,
) {
    drawImage(image, topLeft = topLeft, alpha = alpha)
}
