package orilumn.reader.ui.reader.curl

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import java.util.WeakHashMap

/**
 * 卷曲网格的安卓落点：`Canvas.drawVertices` + `RuntimeShader`（AGSL，API 33+）。
 *
 * 低于 33 返回 `false`，调用方退回折页 —— 没有「假 3D」这一档。
 *
 * 纹理着色器按位图缓存：一帧三段网格复用同一个 `BitmapShader`，只有 uniform 每次写。
 */
internal actual fun curlMeshSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private val bitmapShaders = WeakHashMap<ImageBitmap, BitmapShader>()

private val transparentBitmap: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

private fun shaderOf(image: ImageBitmap): BitmapShader =
    bitmapShaders.getOrPut(image) {
        BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

internal actual fun DrawScope.drawCurlMeshBand(
    mesh: CurlMesh,
    band: CurlMesh.Band,
    page: ImageBitmap,
    overlay: ImageBitmap?,
    paperArgb: Int,
    texLeft: Float,
    texTop: Float,
    foldTx: Float,
    foldTy: Float,
    foldNx: Float,
    foldNy: Float,
    radius: Float,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (band.indexCount <= 0) return true

    val shader = RuntimeShader(CURL_MESH_SHADER)
    shader.setInputShader("pageTex", shaderOf(page))
    shader.setInputShader(
        "overlayTex",
        overlay?.let { shaderOf(it) }
            ?: BitmapShader(transparentBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
    )
    shader.setFloatUniform("foldTx", foldTx)
    shader.setFloatUniform("foldTy", foldTy)
    shader.setFloatUniform("foldNx", foldNx)
    shader.setFloatUniform("foldNy", foldNy)
    shader.setFloatUniform("radius", radius)
    shader.setFloatUniform(
        "surface",
        when (band.surface) {
            CurlMesh.Surface.Recto -> 0f
            CurlMesh.Surface.Verso -> 1f
            CurlMesh.Surface.Back -> 2f
        },
    )
    shader.setFloatUniform("texOx", texLeft)
    shader.setFloatUniform("texOy", texTop)
    shader.setFloatUniform("paperR", ((paperArgb shr 16) and 0xFF) / 255f)
    shader.setFloatUniform("paperG", ((paperArgb shr 8) and 0xFF) / 255f)
    shader.setFloatUniform("paperB", (paperArgb and 0xFF) / 255f)

    val idx = mesh.indices.copyOfRange(band.indexStart, band.indexStart + band.indexCount)
    val paint = Paint().apply {
        this.shader = shader
        isAntiAlias = false
        isFilterBitmap = false
    }
    drawContext.canvas.nativeCanvas.drawVertices(
        android.graphics.Canvas.VertexMode.TRIANGLES,
        mesh.vertexCount,
        mesh.positions,
        0,
        mesh.texCoords,
        0,
        null,
        0,
        idx,
        0,
        idx.size,
        paint,
    )
    return true
}
