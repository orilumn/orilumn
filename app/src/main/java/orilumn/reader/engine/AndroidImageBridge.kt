package orilumn.reader.engine

import android.graphics.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer

/**
 * E1 — skia `Image` → `android.graphics.Bitmap` 的唯一像素桥。
 *
 * 统一解码（[orilumn.reader.engine.skia.ImageCodec]）后产物是 [orilumn.reader.engine.skia.DecodedImage]，
 * 但旧 StaticLayout 绘制接缝（BoxPageRenderer / ImageSpan / 封面绘制）仍在 Android Canvas 上
 * 画 Bitmap；此桥收敛那块壳级 android 依赖（skia 栅格面读回 N32 premul 像素 → Bitmap.copyPixelsFromBuffer）。
 * Q1 StaticLayout 退役后连同 [orilumn.reader.ui.reader] 的 Bitmap 桥一并删除。
 */
internal fun skiaImageToAndroidBitmap(image: Image): Bitmap? = runCatching {
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return@runCatching null
    // m144 起 Bitmap.readPixels 真机回 null（见阅读面像素桥）：改 PNG 单跳，保 alpha。
    // 封面小图编码快，且调用方按书缓存，不在帧循环里。
    val png = image.encodeToData()?.bytes ?: return@runCatching null
    android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
}.getOrNull()