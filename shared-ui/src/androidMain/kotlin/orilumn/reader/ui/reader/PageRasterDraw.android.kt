package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Android actual：直接交回 Compose 的 `drawImage`。
 *
 * Android 的 `ImageBitmap` 本来就是平台位图，画的时候不做像素搬运，不需要像桌面那样
 * 另缓存画柄（见 [RasterDrawCache]）；位图原地重画时平台自己按位图的 generation id
 * 重新上传，所以 [RasterTexture.generation] 在这里不参与判断。
 */
internal actual fun DrawScope.drawRasterCached(
    cache: RasterDrawCache,
    texture: RasterTexture,
    topLeft: Offset,
    alpha: Float,
) {
    drawImage(texture.image, topLeft = topLeft, alpha = alpha)
}
