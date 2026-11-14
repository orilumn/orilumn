package orilumn.reader.ui.reader.curl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import orilumn.reader.io.Logger
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import java.util.Collections

/**
 * 桌面（JVM）后端：skia `RuntimeEffect`。
 *
 * 着色器只编译一次（失败则永久回落到「不画卷曲」，不反复重试）。
 * 纹理按 `ImageBitmap` 身份缓存成 skia `Image`——`Image.makeFromBitmap` 可能复制像素，
 * 每帧重建会平白拷 20MB；缓存 4 条足够覆盖「当前页 + 目标页 + 刚翻过去的两页」。
 */
private val curlEffect: RuntimeEffect? by lazy {
    runCatching { RuntimeEffect.makeForShader(CURL_SKSL) }
        .onFailure { Logger.w("Orilumn.Curl", "RuntimeEffect 编译失败: ${it.message}") }
        .getOrNull()
}

private val curlImages: MutableMap<ImageBitmap, Image> = Collections.synchronizedMap(
    object : LinkedHashMap<ImageBitmap, Image>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ImageBitmap, Image>) = size > 4
    },
)

private fun skiaImageOf(bitmap: ImageBitmap): Image? = curlImages.getOrPut(bitmap) {
    runCatching { Image.makeFromBitmap(bitmap.asSkiaBitmap()) }.getOrNull() ?: return null
}

actual fun DrawScope.drawCurlFrame(frame: CurlFrame, front: ImageBitmap, back: ImageBitmap): Boolean {
    val effect = curlEffect ?: return false
    val imgFront = skiaImageOf(front) ?: return false
    val imgBack = skiaImageOf(back) ?: return false
    val w = frame.pageW
    val h = frame.pageH
    if (w < 1f || h < 1f) return false

    // 归一化采样：子着色器与片元共用 uv ∈ [0,1]，与 Android 的 BitmapShader 约定一致。
    val uv = Matrix33.makeScale(1f / w, 1f / h)
    val selector = {
        val s = imgFront.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, uv)
        val t = imgBack.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, uv)
        arrayOf<org.jetbrains.skia.Shader?>(s, t)
    }
    val uniforms = Data.makeFromBytes(frame.uniforms().toBytesLe())
    val shader = runCatching {
        effect.makeShader(uniforms, selector(), Matrix33.makeTranslate(0f, 0f))
    }.onFailure { Logger.w("Orilumn.Curl", "makeShader 失败: ${it.message}") }.getOrNull() ?: return false

    val paint = Paint().apply { this.shader = shader }
    drawContext.canvas.skiaCanvas.drawRect(Rect.makeWH(w, h), paint)
    return true
}

private fun FloatArray.toBytesLe(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = this[i].toRawBits()
        out[i * 4] = (bits and 0xFF).toByte()
        out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
        out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
        out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
    return out
}
