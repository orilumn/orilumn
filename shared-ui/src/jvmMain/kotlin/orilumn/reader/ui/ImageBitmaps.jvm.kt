package orilumn.reader.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Q1-6 JVM actual：Skia 解码栈（与正文绘制同栈；桌面封面/正文同一口径）。
 * 采样版无 Skia 语义，直解后由 Compose 绘制期缩放。
 */
actual fun imageBitmapOf(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

actual fun sampledImageBitmapOf(bytes: ByteArray, targetWidth: Int): ImageBitmap? =
    imageBitmapOf(bytes)
