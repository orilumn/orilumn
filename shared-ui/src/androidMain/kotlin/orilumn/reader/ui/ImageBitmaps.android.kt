package orilumn.reader.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Q1-6 Android actual：`BitmapFactory` 直解（封面/回退）与按宽采样（正文大图，
 * 与退役 BitmapFactory 管线同语义：2 的幂粗采样 + 精缩等比，失败回 null 由调用方回退）。
 */
actual fun imageBitmapOf(bytes: ByteArray): ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

actual fun sampledImageBitmapOf(bytes: ByteArray, targetWidth: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().also { it.inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val rawW = bounds.outWidth
    val rawH = bounds.outHeight
    if (rawW <= 0 || rawH <= 0) return@runCatching null
    var sample = 1
    if (targetWidth > 0) {
        while (rawW / (sample * 2) >= targetWidth) sample *= 2
    }
    val opts = android.graphics.BitmapFactory.Options().also {
        it.inSampleSize = sample
        it.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: return@runCatching null
    val tw = targetWidth.coerceAtLeast(1)
    if (bmp.width != tw) {
        val th = ((bmp.height * tw.toFloat() / bmp.width).toInt()).coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) runCatching { bmp.recycle() }
        scaled.asImageBitmap()
    } else {
        bmp.asImageBitmap()
    }
}.getOrNull()
