package orilumn.reader.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Q1-6 收敛：字节→[ImageBitmap] 最后一跳的 expect 接缝。
 *
 * 取字节/解析/LRU 已在 `engine-skia` 单源（`ImageLoader`/`ImageCodec`），只剩各端把字节
 * 变成 Compose 位图的平台代码：Android 经 `BitmapFactory`（灰块主因的采样逻辑亦在此），
 * JVM 经 Skia `Image.makeFromEncoded`。两壳正文图与书架封面统一调这里，
 * 消灭 BitmapFactory/PNG 单跳/JPEG 单跳三口径。
 */
expect fun imageBitmapOf(bytes: ByteArray): ImageBitmap?

/**
 * 按目标宽采样的解码（正文大图省内存）：Android 用 `inSampleSize` 粗采样+精缩一次到位；
 * JVM 端 Skia 无采样语义，直解后由 Compose 绘制期缩放（内存由调用方缓存约束）。
 */
expect fun sampledImageBitmapOf(bytes: ByteArray, targetWidth: Int): ImageBitmap?
