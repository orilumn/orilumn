package orilumn.reader.ui.reader.curl

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import orilumn.reader.io.Logger

/**
 * Android 后端：AGSL `RuntimeShader`（API 33 = Android 13 起）。
 *
 * 低版本没有等价能力，[drawCurlFrame] 直接返回 false（调用方按滑动翻页处理，
 * 设置面板也不提供这个档，见 `curlFlipSupported`）。
 *
 * 子纹理用 `BitmapShader` + 归一化 local matrix，和桌面 `Image.makeShader` 走同一套
 * `eval(uv)` 约定（见 [CURL_SKSL] 的「与平台无关的约定」）。
 */
private val curlShader: RuntimeShader? by lazy {
    if (Build.VERSION.SDK_INT < 33) return@lazy null
    runCatching { newRuntimeShader(CURL_SKSL) }
        .onFailure { Logger.w("Orilumn.Curl", "RuntimeShader 编译失败: ${it.message}") }
        .getOrNull()
}

@RequiresApi(33)
private fun newRuntimeShader(src: String): RuntimeShader = RuntimeShader(src)

private val curlPaint: android.graphics.Paint by lazy {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
}

actual fun DrawScope.drawCurlFrame(frame: CurlFrame, front: ImageBitmap, back: ImageBitmap): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    val shader = curlShader ?: return false
    val frontBmp = runCatching { front.asAndroidBitmap() }.getOrNull() ?: return false
    val backBmp = runCatching { back.asAndroidBitmap() }.getOrNull() ?: return false
    val w = frame.pageW
    val h = frame.pageH
    if (w < 1f || h < 1f) return false

    val u = frame.uniforms()
    shader.setFloatUniform("u_page", u[0], u[1], u[2], u[3])
    shader.setFloatUniform("u_geom1", u[4], u[5], u[6], u[7])
    shader.setFloatUniform("u_geom2", u[8], u[9], u[10], u[11])
    shader.setFloatUniform("u_paper", u[12], u[13], u[14], u[15])

    // 归一化采样：local matrix 把 uv ∈ [0,1] 映到纹理像素。
    val uv = Matrix().apply { setScale(1f / w, 1f / h) }
    val frontShader = BitmapShader(frontBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { setLocalMatrix(uv) }
    val backShader = BitmapShader(backBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { setLocalMatrix(uv) }
    shader.setInputBuffer("u_front", frontShader)
    shader.setInputBuffer("u_back", backShader)

    curlPaint.shader = shader
    drawContext.canvas.nativeCanvas.drawRect(0f, 0f, w, h, curlPaint)
    return true
}
