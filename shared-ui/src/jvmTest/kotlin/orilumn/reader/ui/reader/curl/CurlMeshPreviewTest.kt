package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import org.junit.Test
import java.io.File

/**
 * 卷曲网格的**目视自查**（不是断言，随时可删）：把几帧写成 PNG 到 `shared-ui/build/`。
 */
class CurlMeshPreviewTest {

    private val pageW = 900
    private val pageH = 1300

    private fun fakePage(ink: Int, bar: Float): ImageBitmap {
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawRect(Color(0xFFFAF6EC.toInt()))
            val inkColor = Color(ink)
            var y = pageH * 0.10f
            var line = 0
            while (y < pageH * 0.92f) {
                val len = pageW * (0.80f - ((line * 37) % 17) / 100f)
                drawRect(inkColor, topLeft = Offset(pageW * 0.10f, y), size = Size(len, pageH * 0.012f))
                y += pageH * 0.035f
                line++
            }
            drawRect(inkColor, topLeft = Offset(pageW * 0.10f, pageH * 0.04f), size = Size(pageW * 0.45f * bar, pageH * 0.02f))
            // 左缘一道竖条：一眼看出镜像方向。
            drawRect(Color(0xFFB03A2E.toInt()), topLeft = Offset(pageW * 0.02f, pageH * 0.05f), size = Size(pageW * 0.02f, pageH * 0.9f))
        }
        return img
    }

    private fun drawMesh(
        c: org.jetbrains.skia.Canvas,
        mesh: CurlMesh,
        tex: ImageBitmap,
        alpha: Float,
        foldTx: Float,
        foldTy: Float,
        foldNx: Float,
        foldNy: Float,
        radius: Float,
        surface: CurlMesh.Surface,
    ) {
        if (mesh.indexCount == 0) return
        val pageShader = Image.makeFromBitmap(tex.asSkiaBitmap()).makeShader()
        val effect = org.jetbrains.skia.RuntimeEffect.makeForShader(CURL_SHADER)
        val uniforms = org.jetbrains.skia.Data.makeFromBytes(
            java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                putFloat(foldTx); putFloat(foldTy)
                putFloat(foldNx); putFloat(foldNy)
                putFloat(radius)
                putFloat(when (surface) { CurlMesh.Surface.Recto -> 0f; CurlMesh.Surface.Verso -> 1f; else -> 2f })
                putFloat(0.945f); putFloat(0.933f); putFloat(0.902f)
            }.array(),
        )
        val paint = Paint().apply {
            shader = effect.makeShader(uniforms, arrayOf(pageShader), null)
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            isAntiAlias = false
        }
        c.drawVertices(
            VertexMode.TRIANGLES,
            mesh.positions,
            null,
            mesh.texCoords,
            mesh.indices,
            org.jetbrains.skia.BlendMode.MODULATE,
            paint,
        )
    }

    private val CURL_SHADER = """
        uniform shader pageTex;
        uniform float2 foldT;
        uniform float2 foldN;
        uniform float radius;
        uniform float surface;   // 0 recto, 1 verso, 2 back
        uniform float3 paper;    // 纸色

        half4 main(float2 p) {
            float s = dot(p - foldT, foldN);
            float th = clamp(s / radius, 0.0, 3.14159265);
            half4 c = pageTex.eval(p);
            float nx = sin(th);
            float nz = cos(th);
            if (surface > 0.5) { nx = -nx; nz = -nz; }
            float lit = clamp(nx * 0.45 + nz * 0.89, 0.0, 1.0);
            float shade = 0.55 + 0.45 * lit;
            float spec = pow(clamp(nz, 0.0, 1.0), 8.0) * 0.06;
            float3 ink = clamp(c.rgb * shade + spec, 0.0, 1.0);
            // 纸背不透光：背面就是「纸色 + 0.39 的透字」，不是把正面半透明叠上来。
            float k = surface > 0.5 ? 0.39 : 1.0;
            float3 outC = mix(paper, ink, k);
            return half4(outC, 1.0);
        }
    """.trimIndent()

    private fun dump(name: String, progress: Float, tilt: Float = 0f, ratio: Float = CURL_RADIUS_RATIO, eye: Float = 0f) {
        val shape = curlShape(pageW.toFloat(), pageH.toFloat(), progress, 1, tilt, false)
        val front = fakePage(0xFF2A2A2A.toInt(), 1f)
        val back = fakePage(0xFF2A2A2A.toInt(), 0.45f)
        val radius = pageW * ratio
        val mesh = buildCurlMesh(shape, radius = radius, splits = 40, eyeZ = if (eye > 0f) pageH * eye else 0f)
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawRect(Color(0xFFF1EEE6.toInt()))
            // 正面页整页
            drawImage(front)
            // 露出页（折痕右侧）
            val corners = revealCorners(shape)
            if (corners.size >= 3) {
                val reveal = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
                    close()
                }
                clipPath(reveal) { drawImage(back) }
            }
            // 落影：纸卷/纸背压在正面页上，边缘向外一道渐变（GPU 渐变画笔）。
            val backEdgeX = mesh.positions.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull() ?: 0f
            val bandW = pageW * 0.07f
            if (backEdgeX > 1f) {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colorStops = arrayOf(0f to Color(0f, 0f, 0f, 0.22f), 1f to Color(0f, 0f, 0f, 0f)),
                        startX = backEdgeX - bandW,
                        endX = backEdgeX,
                    ),
                    topLeft = Offset(backEdgeX - bandW, 0f),
                    size = Size(bandW, pageH.toFloat()),
                )
            }
            drawIntoCanvas { dc ->
                val c = dc.nativeCanvas
                val ux = (shape.bottomX - shape.topX) / kotlin.math.hypot(shape.bottomX - shape.topX, shape.pageH)
                val uy = shape.pageH / kotlin.math.hypot(shape.bottomX - shape.topX, shape.pageH)
                val nx = uy
                val ny = -ux
                for (band in mesh.bands) {
                    val sub = buildSubMesh(mesh, band)
                    val alpha = 1f
                    drawMesh(
                        c, sub, front, alpha,
                        foldTx = shape.topX, foldTy = 0f,
                        foldNx = nx, foldNy = ny,
                        radius = radius,
                        surface = band.surface,
                    )
                }
            }
        }
        val out = File("build/curlm-$name.png")
        out.parentFile?.mkdirs()
        val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        out.writeBytes(data.bytes)
        println("wrote ${out.absolutePath} rows=${mesh.vertexCount / 2} bands=${mesh.bands.map { it.surface }}")
        println("  fold=${shape.topX}..${shape.bottomX} meshX=${mesh.positions.filterIndexed { i, _ -> i % 2 == 0 }.let { if (it.isEmpty()) "none" else "${it.min()}..${it.max()}" }}")
    }

    private fun buildSubMesh(mesh: CurlMesh, band: CurlMesh.Band): CurlMesh {
        val idx = mesh.indices.copyOfRange(band.indexStart, band.indexStart + band.indexCount)
        return CurlMesh(
            positions = mesh.positions,
            texCoords = mesh.texCoords,
            arc = mesh.arc,
            indices = idx,
            indexCount = idx.size,
            bands = listOf(band),
        )
    }

    @Test
    fun dumpFrames() {
        for (r in listOf(0.06f, 0.10f, 0.16f)) {
            dump("R${(r*100).toInt()}-p030", 0.30f, ratio = r, eye = 5f)
            dump("R${(r*100).toInt()}-p050", 0.50f, ratio = r, eye = 5f)
            dump("R${(r*100).toInt()}-p075", 0.75f, ratio = r, eye = 5f)
        }
    }
}
