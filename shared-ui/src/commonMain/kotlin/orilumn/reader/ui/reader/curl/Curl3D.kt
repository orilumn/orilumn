package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 3D 卷曲一帧（用户层的绘制入口）。
 *
 * 与折页（[drawCurlFrame]）的区别只在**纸的第三维**：折页是刚性纸绕折痕翻过去，屏幕上
 * 只有三块平面多边形；卷曲是同一张纸绕过折痕的圆柱，屏幕位置由「到折痕的弧长」决定，
 * 卷起来的那一段有法线朝向、有明暗、有投影。三角形网格由 [buildCurlMesh] 铺，光栅与
 * 采样交给 [drawCurlMeshBand]（桌面 skiko / 安卓 Canvas.drawVertices + RuntimeShader）。
 *
 * 画序（与 z 从小到大一致）：正面页 → 露出页 → 落影 → 网格三段（正面 / 卷背 / 纸背）。
 *
 * @return `false` 表示本端画不了网格（安卓 API < 33），调用方应退回折页；
 *   `true` 表示这一帧已经画完。
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

    // ① 正面页（还没卷起来的那部分；右侧会被 ②③ 盖住）
    drawPage(front, paper, texLeft, texTop, pageSize)

    // ② 露出页（折痕右侧）
    val corners = revealCorners(shape)
    if (corners.size < 3) return true
    val reveal = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    clipPath(reveal) {
        drawPage(back, paper, texLeft, texTop, pageSize)
    }

    // ③ 网格
    //
    // 折痕要**往回收一个半径**：读者拖的是纸的**自由边**（屏幕上那条轮廓），折痕在它后面
    // 一个半径处 —— 真实翻页就是这样。不收回来的话，露出区会被纸卷整整吃掉 R 宽度
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
    if (mesh.indexCount == 0) return true
    val m0 = Offset(meshShape.topX, 0f)
    val m1 = Offset(meshShape.bottomX, h)

    // ④ 落影：纸卷压在露出页上。影从纸卷的**轮廓**向外铺，长度跟着卷起来的高度走
    //    （抬得越高影越远），这样影和纸是同一个几何算出来的，不会各走各的。
    val sMax = listOf(0f to 0f, w to 0f, w to h, 0f to h)
        .maxOf { (px, py) -> (px - m0.x) * nx + (py - m0.y) * ny }
    val lift = radius * (1f - cos(minOf(sMax / radius, PI.toFloat())))
    val silhouette = mesh.maxOffsetAlong(nx, ny, m0)
    drawFanShadow(
        t0 = m0, t1 = m1, n = Offset(nx, ny),
        from = silhouette,
        width = lift * 1.2f,
        alpha = SHADOW_ALPHA * 1.35f,
    )
    // ⑤ 纸背自由边压在正面页上的一道影。
    val backEdge = mesh.minOffsetAlong(nx, ny, m0)
    if (backEdge < -1f) {
        drawFanShadow(
            t0 = m0, t1 = m1, n = Offset(nx, ny),
            from = backEdge,
            width = -lift * 0.75f,
            alpha = SHADOW_ALPHA,
        )
        // ⑥ 折痕处的暗角：纸背贴着卷起来的那一段，越靠近折痕越暗。
        //    （只在有平铺段时才画 —— 没有纸背就没有这块接触阴影。）
        drawFanShadow(
            t0 = m0, t1 = m1, n = Offset(nx, ny),
            from = 0f,
            width = -radius * 1.1f,
            alpha = SHADOW_ALPHA * 0.95f,
        )
    }

    // ⑥ 三段网格：正面 / 卷背 / 纸背，按 z 从小到大。
    for (band in mesh.bands) {
        val ok = drawCurlMeshBand(
            mesh = mesh,
            band = band,
            page = front.page ?: return false,
            overlay = front.overlay,
            paperArgb = paperArgb,
            texLeft = texLeft,
            texTop = texTop,
            foldTx = m0.x,
            foldTy = m0.y,
            foldNx = nx,
            foldNy = ny,
            radius = radius,
        )
        if (!ok) return false
    }

    // ⑦ 卷内暗角：卷是一个半圆柱的**内侧**朝着观察者，越靠折痕（θ 越接近 π）越封闭、
    //    越暗。画在网格之上，纸卷才有「往里凹」的走向，而不是一条平的浅色带。
    drawFanShadow(
        t0 = m0, t1 = m1, n = Offset(nx, ny),
        from = 0f,
        width = radius * 0.80f,
        alpha = SHADOW_ALPHA * 0.85f,
    )
    return true
}

/** 卷曲半径相对页宽。太小读不出卷，太大会把整页卷成筒。 */
internal const val CURL3D_RADIUS_RATIO: Float = 0.085f

/** 观察者高度相对页高：moon+ 的 `fov 45°` 且相机在 `1/tan(fov/2)` 处（`NewCurl3D.java:1122`）。 */
internal const val CURL3D_EYE_RATIO: Float = 2.2f

/** 圆柱横截面分段数。 */
internal const val CURL3D_SPLITS: Int = 40

/**
 * 沿折痕方向的一条渐变影带：从折痕坐标系里的 `from` 偏移处开始，向 `width` 方向铺开，
 * 起点 `alpha`、终点透明。
 *
 * 用一条平行四边形的渐变画笔（GPU 渐变），不是逐像素算的影 —— 影的形状只跟折痕和卷高
 * 有关，沿折痕方向是均匀的。
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
    val brush = Brush.linearGradient(
        colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
        start = a,
        end = b,
    )
    drawPath(path, brush)
}

private fun CurlMesh.maxOffsetAlong(nx: Float, ny: Float, t0: Offset): Float {
    var m = 0f
    var i = 0
    while (i < positions.size) {
        val v = (positions[i] - t0.x) * nx + (positions[i + 1] - t0.y) * ny
        if (v < m) m = v
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

/**
 * 画一段网格（**平台最后跳**）。
 *
 * 桌面：`org.jetbrains.skia.Canvas.drawVertices` + `RuntimeEffect`。
 * 安卓：`android.graphics.Canvas.drawVertices` + `RuntimeShader`（API 33+）；低于 33 返回
 * `false`，调用方退回折页 —— 不出现「有动画但很假」的中间档。
 *
 * 两端共用 [CURL_MESH_SHADER] 这一段源码，只有这一跳不同。
 */
/** 本端是否真的能画网格（安卓 API < 33 为 false，调用方直接用折页）。 */
internal expect fun curlMeshSupported(): Boolean

internal expect fun DrawScope.drawCurlMeshBand(
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
): Boolean
