package orilumn.reader.ui.reader.curl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.VertexMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/**
 * 卷曲网格的桌面落点：skiko `Canvas.drawVertices` + `RuntimeEffect`（SkSL）。
 *
 * 着色器与纹理各自缓存：一帧里三段网格共用同一个 `RuntimeEffect` 与同一份页纹理，
 * 重建的只有 11 个浮点 uniform 与索引子区间。
 */
internal actual fun curlMeshSupported(): Boolean = true

private val shaderEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(CURL_MESH_SHADER) }

private val imageShaders = WeakHashMap<ImageBitmap, Shader>()

private val transparentShader: Shader by lazy {
    val bmp = org.jetbrains.skia.Bitmap()
    bmp.allocN32Pixels(1, 1)
    Image.makeFromBitmap(bmp).makeShader()
}

private fun shaderOf(image: ImageBitmap): Shader =
    imageShaders.getOrPut(image) { Image.makeFromBitmap(image.asSkiaBitmap()).makeShader() }

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
    if (band.indexCount <= 0) return true
    val idx = mesh.indices.copyOfRange(band.indexStart, band.indexStart + band.indexCount)
    val surface = when (band.surface) {
        CurlMesh.Surface.Recto -> 0f
        CurlMesh.Surface.Verso -> 1f
        CurlMesh.Surface.Back -> 2f
    }
    val uniforms = ByteBuffer.allocate(11 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(foldTx); putFloat(foldTy)
        putFloat(foldNx); putFloat(foldNy)
        putFloat(radius)
        putFloat(surface)
        putFloat(texLeft); putFloat(texTop)
        putFloat(((paperArgb shr 16) and 0xFF) / 255f)
        putFloat(((paperArgb shr 8) and 0xFF) / 255f)
        putFloat((paperArgb and 0xFF) / 255f)
    }.array()

    val paint = Paint().apply {
        shader = shaderEffect.makeShader(
            Data.makeFromBytes(uniforms),
            arrayOf(shaderOf(page), overlay?.let { shaderOf(it) } ?: transparentShader),
            null,
        )
        isAntiAlias = false
    }
    drawContext.canvas.nativeCanvas.drawVertices(
        VertexMode.TRIANGLES,
        mesh.positions,
        null,
        mesh.texCoords,
        idx,
        BlendMode.MODULATE,
        paint,
    )
    return true
}
