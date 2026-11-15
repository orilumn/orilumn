package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 3D 卷曲一帧（**用户层**的绘制入口，全部走 Compose 公共绘制 API）。
 *
 * ## 技术选型（踩完坑之后的定论）
 *
 * 纸面是一张要**空间弯曲**的纹理，只有网格能表达，所以翻页的纸必须用
 * `Canvas.drawVertices` 画。要点是**必须用 Compose 自己的**：
 * `Vertices` + `ImageShader` + `Canvas.drawVertices`（`androidx.compose.ui.graphics`）。
 * 它是跨平台公共 API，两端语义由 Compose 保证；绕开它直接调 `nativeCanvas`
 * 会拿到两套不同的框架语义，这里为此踩过三个坑：
 *
 * 1. **逐像素着色器**（`RuntimeShader`）：安卓硬件画布上它拿到的坐标不是网格纹理坐标，
 *    采样落到页外、`CLAMP` 成白纸 —— 整段网格画成一片白。已整体删除。
 * 2. **顶点色明暗**（moon+ 的做法）：同一份 `colors` 数组在桌面正确、在真机上变成
 *    满屏随机彩色 —— 安卓这条路的顶点色不可靠。已整体删除。
 * 3. **`BitmapShader` 直接吃页栅格**：页栅格是 GPU 后端的位图，框架不保证可作纹理。
 *
 * 所以现在：**纹理**走 `ImageShader` + `Vertices`；**明暗与纸背压淡**走路径 +
 * `Brush`（明暗本来就是「沿折痕方向的一维函数」，一条多段渐变就能精确表达）。
 * 一条纹理、一条渐变，全是文档里写清楚的公共 API，没有平台分叉，没有版本门槛。
 *
 * ## 画序（与 z 从小到大一致）
 *
 * 1. 正面页整页 → 2. 露出页（多边形裁切）→ 3. 落影 →
 * 4. 各段网格纹理（按 z 顺序）→ 5. 纸背压淡 → 6. 纸卷明暗。
 *
 * 压淡**必须排在明暗之前**：压淡是「这是纸的背面，内容只透出 39%」，明暗是
 * 「这张纸现在怎么弯、光怎么落」。反过来会把光影一起压掉，纸卷就成一块没有起伏的浅色板。
 *
 * @return `false` 表示这一帧没有网格可画，调用方退回折页。
 */
internal fun DrawScope.drawCurl3DFrame(
    shape: CurlShape,
    front: CurlPageImages,
    back: CurlPageImages,
    paperArgb: Int,
    texLeft: Float,
    texTop: Float,
    radiusRatio: Float = CURL3D_RADIUS_RATIO,
    eyeRatio: Float = CURL3D_EYE_RATIO,
): Boolean {
    val w = shape.pageW
    val h = shape.pageH
    val pageSize = Size(w, h)
    val paper = Color(paperArgb)

    if (shape.progress <= CURL_START_EPSILON) {
        drawPage(if (shape.direction >= 0) front else back, paper, texLeft, texTop, pageSize)
        return true
    }

    // ① 正面页 + ② 露出页
    drawPage(front, paper, texLeft, texTop, pageSize)
    val corners = revealCorners(shape)
    if (corners.size < 3) return true
    val reveal = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    clipPath(reveal) { drawPage(back, paper, texLeft, texTop, pageSize) }

    // ③ 网格几何
    //
    // 折痕要**往回收一个半径**：读者拖的是纸的**自由边**（屏幕上那条轮廓），折痕在它后面
    // 一个半径处 —— 真实翻页就是这样。不回收的话，露出区会被纸卷整整吃掉 R 宽度
    // （低进度时几乎一点新页都看不到），手指和画面对不上。
    val radius = w * radiusRatio
    val t0 = Offset(shape.topX, 0f)
    val t1 = Offset(shape.bottomX, h)
    val foldLen = hypot(t1.x - t0.x, h).coerceAtLeast(1e-3f)
    val ux = (t1.x - t0.x) / foldLen
    val uy = h / foldLen
    val nx = uy
    val ny = -ux
    // 沿 n 平移直线会同时改变两个端点的 y；换算成「仍过 y=0 与 y=h 的平行线」的 x 偏移。
    val shift = -radius * (nx - ny * (t1.x - t0.x) / h)
    val meshShape = shape.copy(topX = shape.topX + shift, bottomX = shape.bottomX + shift)
    val mesh = buildCurlMesh(meshShape, radius = radius, splits = CURL3D_SPLITS, eyeZ = h * eyeRatio)
    if (mesh.indexCount == 0) return false
    val m0 = Offset(meshShape.topX, 0f)
    val m1 = Offset(meshShape.bottomX, h)
    val n = Offset(nx, ny)

    // ④ 落影：纸卷压在露出页上。影从纸卷的**轮廓**向外铺，长度跟着卷起来的高度走
    //    （抬得越高影越远），这样影和纸是同一个几何算出来的，不会各走各的。
    val sMax = listOf(0f to 0f, w to 0f, w to h, 0f to h)
        .maxOf { (px, py) -> (px - m0.x) * nx + (py - m0.y) * ny }
    val lift = radius * (1f - cos(minOf(sMax / radius, PI.toFloat())))
    val silhouette = mesh.maxOffsetAlong(nx, ny, m0)
    drawFanShadow(m0, m1, n, from = silhouette, width = lift * 1.2f, alpha = SHADOW_ALPHA * 1.35f)
    // ⑤ 纸背自由边压在正面页上的一道影 + 折痕接触处的暗角
    val backEdge = mesh.minOffsetAlong(nx, ny, m0)
    if (backEdge < -1f) {
        drawFanShadow(m0, m1, n, from = backEdge, width = -lift * 0.75f, alpha = SHADOW_ALPHA)
        drawFanShadow(m0, m1, n, from = 0f, width = -radius * 1.1f, alpha = SHADOW_ALPHA * 0.95f)
    }

    // ⑥ 各段网格纹理（按 z 顺序），然后是纸背压淡、纸卷明暗
    val canvas = drawContext.canvas
    val nv = mesh.vertexCount
    val posList = List(nv) { Offset(mesh.positions[it * 2], mesh.positions[it * 2 + 1]) }
    // 纹理坐标要减去页栅格在页面里的落点（版心偏移）：`ImageShader` 没有局部矩阵，
    // 只能把偏移提前算进纹理坐标里。覆盖层是整页图，偏移恒为 0。
    val pageTex = List(nv) {
        Offset(mesh.texCoords[it * 2] - texLeft, mesh.texCoords[it * 2 + 1] - texTop)
    }
    val overlayTex = List(nv) { Offset(mesh.texCoords[it * 2], mesh.texCoords[it * 2 + 1]) }
    for (band in mesh.bands) {
        if (band.indexCount <= 0) continue
        val idx = List(band.indexCount) { mesh.indices[band.indexStart + it].toInt() }
        drawMeshBand(canvas, posList, idx, pageTex, front.page!!)
        front.overlay?.let { drawMeshBand(canvas, posList, idx, overlayTex, it) }
    }

    // ⑦ 纸背压淡：纸色 61% 盖上去 ⇒ 内容只剩 39%（与折页同一个口径）。
    val versoMin = mesh.minOffsetOf(CurlMesh.Surface.Verso, nx, ny, m0)
    val paperColor = Color(paperArgb.toLong())
    if (backEdge < -1f) drawBandQuad(m0, m1, n, backEdge, 0f, paperColor, WASH_ALPHA)
    if (versoMin != null && silhouette > versoMin) {
        drawBandQuad(m0, m1, n, versoMin, silhouette, paperColor, WASH_ALPHA)
    }

    // ⑧ 纸卷明暗：沿折痕法向的一维渐变（管状曲线：中间最亮、两侧收暗）。
    val shadeFrom = if (versoMin != null) versoMin.coerceAtLeast(0f) else 0f
    if (silhouette > shadeFrom + 1f) {
        val span = silhouette - shadeFrom
        val stops = Array(ROLL_SHADE_STOPS + 1) { k ->
            val t = k.toFloat() / ROLL_SHADE_STOPS
            val u = shadeFrom + span * t
            // 看到的是纸背那半圈 ⇒ 管状曲线；还没绕过去 ⇒ 平的正面，只略微压暗。
            val shade = if (versoMin != null && u >= versoMin - 0.01f) {
                val tt = (u / radius).coerceIn(0f, 1f)
                (0.52f + 0.48f * sin((PI * tt).toFloat()).pow(0.6f)).coerceIn(0f, 1f)
            } else {
                1f - 0.12f * t
            }
            t to Color(0f, 0f, 0f, 1f - shade)
        }
        drawBandQuadBrush(
            m0, m1, n, shadeFrom, silhouette,
            Brush.linearGradient(
                colorStops = stops,
                start = m0 + n * shadeFrom,
                end = m0 + n * silhouette,
            ),
        )
    }
    return true
}

/** 某一段可见面沿法向 `n` 的最小偏移（没有这一段就是 `null`）。 */
private fun CurlMesh.minOffsetOf(
    surface: CurlMesh.Surface,
    nx: Float,
    ny: Float,
    origin: Offset,
): Float? {
    var min = Float.MAX_VALUE
    var found = false
    for (b in bands) {
        if (b.surface != surface) continue
        found = true
        for (k in b.indexStart until b.indexStart + b.indexCount) {
            val v = indices[k].toInt()
            val u = (positions[v * 2] - origin.x) * nx + (positions[v * 2 + 1] - origin.y) * ny
            if (u < min) min = u
        }
    }
    return if (found) min else null
}

/** 明暗渐变的分段数（一维函数的采样密度，够到看不出折线）。 */
private const val ROLL_SHADE_STOPS: Int = 24

/** 纸背压淡的 alpha：纸色盖上去 ⇒ 内容剩 0.39（与折页同一个口径）。 */
private const val WASH_ALPHA: Float = 0.61f

/** 用一段网格画一张纹理（**Compose 公共 API**：`Vertices` + `ImageShader`）。 */
private fun DrawScope.drawMeshBand(
    canvas: androidx.compose.ui.graphics.Canvas,
    positions: List<Offset>,
    indices: List<Int>,
    texCoords: List<Offset>,
    image: ImageBitmap,
) {
    val vertices = Vertices(
        vertexMode = VertexMode.Triangles,
        positions = positions,
        textureCoordinates = texCoords,
        // Compose 要求顶点色与顶点数**等长**（空列表会抛「lengths must match」）。
        // 全部给白色 + SrcOver ⇒ 只取纹理本身，不做顶点级着色。
        colors = List(positions.size) { Color.White },
        indices = indices,
    )
    val paint = Paint().apply {
        shader = ImageShader(image, TileMode.Clamp, TileMode.Clamp)
        // 纸卷把材料压进不到一个半径的屏幕宽度，最邻近采样会把文字抽成锯齿斜条。
        filterQuality = FilterQuality.Low
        blendMode = BlendMode.SrcOver
    }
    drawIntoCanvas { it.drawVertices(vertices, BlendMode.SrcOver, paint) }
}

/** 沿折痕方向的一条平行四边形色块（`from`/`to` 是沿法向 `n` 的偏移）。 */
private fun DrawScope.drawBandQuad(
    t0: Offset,
    t1: Offset,
    n: Offset,
    from: Float,
    to: Float,
    color: Color,
    alpha: Float,
) {
    if (to - from <= 0.5f) return
    drawPath(bandQuadPath(t0, t1, n, from, to), color, alpha = alpha)
}

/** 同上，但用一把画笔（渐变）。 */
private fun DrawScope.drawBandQuadBrush(
    t0: Offset,
    t1: Offset,
    n: Offset,
    from: Float,
    to: Float,
    brush: Brush,
) {
    if (to - from <= 0.5f) return
    drawPath(bandQuadPath(t0, t1, n, from, to), brush)
}

private fun bandQuadPath(t0: Offset, t1: Offset, n: Offset, from: Float, to: Float): Path {
    val a = t0 + n * from
    val b = t0 + n * to
    val c = t1 + n * to
    val d = t1 + n * from
    return Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
}

/**
 * 卷曲半径相对页宽。
 *
 * moon+ 的卷曲区宽度是 `屏幕宽 × 1.5 / 3`（`NewCurl3D.java:1122` 的 `dWidth`），
 * 也就是半页。半径给到 0.085 时，纸卷在屏幕上只有 8% 页宽 —— 一条亮线，读不出「纸立起来了」。
 * 0.18 让纸卷占 18% 页宽：低进度时纸是一张弯着的片，高进度时才绕成筒，
 * 中间那段「正在卷」的状态才是读者认得出的翻页。
 */
internal const val CURL3D_RADIUS_RATIO: Float = 0.12f

/**
 * 观察者高度相对页高。
 *
 * moon+ 是 `fov 45°` 且相机放在 `1/tan(fov/2)` 处（`NewCurl3D.java:1122`），换算过来约
 * 1.2 页高；那个量在平板近处会把页边顶出画面，所以收到 2.2 —— 近处略大、远处略小，
 * 读得出「纸立起来了」，又不至于鱼眼。
 */
internal const val CURL3D_EYE_RATIO: Float = 2.2f

/** 圆柱横截面分段数。 */
internal const val CURL3D_SPLITS: Int = 40

/**
 * 沿折痕方向的一条渐变影带：从折痕坐标系里的 `from` 偏移处开始，向 `width` 方向铺开，
 * 起点 `alpha`、终点透明。影的形状只跟折痕和卷高有关，沿折痕方向是均匀的，
 * 所以用一条平行四边形 + GPU 渐变画笔就够。
 */
private fun DrawScope.drawFanShadow(
    t0: Offset,
    t1: Offset,
    n: Offset,
    from: Float,
    width: Float,
    alpha: Float,
) {
    if (width == 0f) return
    val a = t0 + n * from
    val b = t0 + n * (from + width)
    val c = t1 + n * (from + width)
    val d = t1 + n * from
    val path = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
    drawPath(
        path,
        Brush.linearGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            start = a,
            end = b,
        ),
    )
}

/** 网格里沿法向最远的偏移（纸卷轮廓）。 */
private fun CurlMesh.maxOffsetAlong(nx: Float, ny: Float, t0: Offset): Float {
    var m = 0f
    var i = 0
    while (i < positions.size) {
        val v = (positions[i] - t0.x) * nx + (positions[i + 1] - t0.y) * ny
        if (v > m) m = v
        i += 2
    }
    return m
}

/** 网格里沿法向最近的偏移（纸背自由边）。 */
private fun CurlMesh.minOffsetAlong(nx: Float, ny: Float, t0: Offset): Float {
    var m = 0f
    var i = 0
    while (i < positions.size) {
        val v = (positions[i] - t0.x) * nx + (positions[i + 1] - t0.y) * ny
        if (v < m) m = v
        i += 2
    }
    return m
}

/** 画一整页：纸色打底 + 页栅格（版心）+ 覆盖层（整页）。 */
private fun DrawScope.drawPage(
    images: CurlPageImages,
    paper: Color,
    texLeft: Float,
    texTop: Float,
    pageSize: Size,
    alpha: Float = 1f,
) {
    drawRect(paper, size = pageSize, alpha = alpha)
    images.page?.let { drawImage(it, topLeft = Offset(texLeft, texTop), alpha = alpha) }
    images.overlay?.let { drawImage(it, topLeft = Offset.Zero, alpha = alpha) }
}
