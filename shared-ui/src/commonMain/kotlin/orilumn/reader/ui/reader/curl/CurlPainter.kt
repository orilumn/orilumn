package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 折页的**绘制**（用户层，纯 Compose 画布，两端同一份代码）。
 *
 * 照旧版 `orilum.bak` 的 `CurlView.drawBody` 一步一步抄：
 *
 * 1. **整页正面**：被翻的那一页（纸还没掀起来的部分）。
 * 2. **露出区**：折痕右侧那一块，画露出的那一页（多边形裁切，形状见 [revealCorners]）。
 * 3. **纸背**：露出区沿折痕镜像回来的那一条。先铺纸色，再在镜像变换里画正面页的低透明度
 *    内容（0.39）——纸的背面就是正面的镜像，不需要第三张图。
 * 4. **纸背外缘的影**：沿纸背每条自由边向外的一道渐变（折痕那一条不画）。
 *    「自由边」= 纸的轮廓，所以影跟着纸的形状走，而不是顺着折痕拖一条灰带。
 * 5. **折痕旁的影**：折痕朝露出页那一侧的一道渐变，结算头尾随 [CurlShape.shadowFade] 淡出。
 *
 * 为什么用多边形裁切而不是逐像素卷曲：折页在物理上就是一张**刚性**纸绕折痕翻过去，
 * 屏幕上看到的三块（正面页 / 纸背 / 露出页）都能由折痕一条直线切出来，不需要曲面。
 * 逐像素着色器只在「纸卷」这种圆柱投影上才有优势，而那个姿态在折页里根本不存在
 * （纸不会自己卷成筒），画出来的筒会从折角的上下两侧漏到正面页上——已废弃。
 */
internal fun DrawScope.drawCurlFrame(
    shape: CurlShape,
    front: CurlPageImages,
    back: CurlPageImages,
    paperArgb: Int,
    texLeft: Float,
    texTop: Float,
) {
    val w = shape.pageW
    val h = shape.pageH
    val paper = Color(paperArgb)
    val pageSize = Size(w, h)

    // 还没开始翻（手指刚动的一点点）：只画读者正在看的那一页。
    // 向前翻时「正在看」的是露出页那一张（两张图在 [FlipCurlLayer] 里按方向换过位）。
    if (shape.progress <= CURL_START_EPSILON) {
        drawWholePage(if (shape.direction >= 0) front else back, paper, texLeft, texTop, pageSize)
        return
    }

    // ① 整页正面
    drawWholePage(front, paper, texLeft, texTop, pageSize)

    // ② 露出区
    val corners = revealCorners(shape)
    if (corners.size < 3) return
    val reveal = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    clipPath(reveal) {
        drawWholePage(back, paper, texLeft, texTop, pageSize)
    }

    // ③ 纸背：露出区沿折痕镜像回来的那一条
    val creaseTop = Offset(shape.topX, 0f)
    val creaseBottom = Offset(shape.bottomX, h)
    val mirror = Reflection.about(creaseTop, creaseBottom)
    val stripPts = corners.map { mirror.map(it) }
    val strip = Path().apply {
        moveTo(stripPts[0].x, stripPts[0].y)
        for (i in 1 until stripPts.size) lineTo(stripPts[i].x, stripPts[i].y)
        close()
    }
    clipPath(strip) {
        drawRect(paper, size = pageSize)
        withTransform({
            // 沿折痕镜射的分解式：平移 → 转到折痕方向 → 上下镜像 → 转回 → 平移回来。
            // 不直接拼 Matrix：Compose 的 Matrix 数组在「点映射」与「画布连接」两处的行列
            // 约定不一致，拼顺手了整块纸背会画到别处（实测踩过一次，内容是整块画丢）。
            val deg = (atan2(creaseBottom.y - creaseTop.y, creaseBottom.x - creaseTop.x) * 180f / PI.toFloat())
            translate(creaseTop.x, creaseTop.y)
            rotate(deg, pivot = Offset.Zero)
            scale(1f, -1f, pivot = Offset.Zero)
            rotate(-deg, pivot = Offset.Zero)
            translate(-creaseTop.x, -creaseTop.y)
        }) {
            // 纸背的内容是正面的镜像，压到 39% 才是「纸的背面」而不是「另一张正面」。
            // 覆盖层（旁注 / 页码）**不进纸背**：那是读者层的浮层，不是印在纸上的东西。
            front.page?.let {
                drawImage(it, topLeft = Offset(texLeft, texTop), alpha = BACK_CONTENT_ALPHA)
            }
        }
    }

    // ④ 纸背外缘的影 ⑤ 折痕旁的影
    drawStripShadow(shape, stripPts)
    drawCreaseShadow(shape)
}

/** 纸背内容压到的透明度（旧版纸背内容 `alpha = 100 / 255`）。 */
internal const val BACK_CONTENT_ALPHA: Float = 0.39f

/** 两道影的起始不透明度（旧版 `Color.argb(60, 0, 0, 0)`）。 */
internal const val SHADOW_ALPHA: Float = 60f / 255f

/** 画一整页：纸色打底 + 页栅格（放在版心位置）+ 覆盖层（旁注 / 页码，整页大小）。 */
private fun DrawScope.drawWholePage(
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
 * 纸背外缘的影：沿每条**自由边**向外画一道渐变（折痕那一条不算）。
 *
 * 长边（通常是页边那一侧）用梯形：一头的宽度由它自己离右缘多远决定，贴着右缘那一头
 * 收成 0，于是折痕斜着扫到页角时影也跟着收尖，不会在角上留一块方影。其余边是等宽条。
 * 拐角处两侧各补一块三角，拼成 L 形——这样拐角处的影不会因为两条条带相接而叠出一块更深的斑。
 */
private fun DrawScope.drawStripShadow(shape: CurlShape, pts: List<Offset>) {
    val n = pts.size
    if (n < 3) return
    val w = shape.pageW
    val edgeW = w * CURL_SHADOW_BAND
    if (edgeW <= 0f) return

    var cx = 0f
    var cy = 0f
    for (p in pts) {
        cx += p.x
        cy += p.y
    }
    cx /= n
    cy /= n

    // 每条边（最后一条是折痕，不画）朝外的单位法向：用中点相对形心的方向判内外。
    val norms = ArrayList<Offset>(n - 1)
    for (i in 0 until n - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        var dx = b.x - a.x
        var dy = b.y - a.y
        val l = hypot(dx, dy).coerceAtLeast(1e-3f)
        dx /= l
        dy /= l
        var nx = -dy
        var ny = dx
        val mx = (a.x + b.x) / 2f - cx
        val my = (a.y + b.y) / 2f - cy
        if (nx * mx + ny * my < 0f) {
            nx = -nx
            ny = -ny
        }
        norms.add(Offset(nx, ny))
    }

    fun bandBrush(from: Offset, to: Offset) = Brush.linearGradient(
        colorStops = arrayOf(0f to Color(0f, 0f, 0f, SHADOW_ALPHA), 1f to Color(0f, 0f, 0f, 0f)),
        start = from,
        end = to,
    )

    // 最长的自由边 = 纸的外缘长边，单独用梯形画（其余等宽）。
    var longIdx = -1
    var longLen = -1f
    for (e in 0 until n - 1) {
        val ll = hypot(pts[e + 1].x - pts[e].x, pts[e + 1].y - pts[e].y)
        if (ll > longLen) {
            longLen = ll
            longIdx = e
        }
    }

    for (i in 0 until n - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val nrm = norms[i]
        if (i == longIdx) {
            // 两端的带宽由「折痕两端各自离右缘多远」决定：贴着右缘 → 0（影收尖）。
            val wTop = (edgeW * ((w - shape.topX) / (w - shape.bottomX).coerceAtLeast(1e-3f)))
                .coerceIn(0f, edgeW)
            val wBot = (edgeW * ((w - shape.bottomX) / (w - shape.topX).coerceAtLeast(1e-3f)))
                .coerceIn(0f, edgeW)
            val dirx = b.x - a.x
            val diry = b.y - a.y
            val elen = hypot(dirx, diry).coerceAtLeast(1e-3f)
            val eux = dirx / elen
            val euy = diry / elen
            val aIsTop = a.y <= b.y
            val wA = if (aIsTop) wTop else wBot
            val wB = if (aIsTop) wBot else wTop
            val steps = 24
            for (k in 0 until steps) {
                val u0 = k.toFloat() / steps
                val u1 = (k + 1f) / steps
                val wi0 = wA + (wB - wA) * u0
                val wi1 = wA + (wB - wA) * u1
                if (wi0 < 0.3f) continue
                val p0 = Offset(a.x + eux * u0 * elen, a.y + euy * u0 * elen)
                val p1 = Offset(a.x + eux * u1 * elen, a.y + euy * u1 * elen)
                val slice = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p1.x + nrm.x * wi1, p1.y + nrm.y * wi1)
                    lineTo(p0.x + nrm.x * wi0, p0.y + nrm.y * wi0)
                    close()
                }
                drawPath(slice, bandBrush(p0, Offset(p0.x + nrm.x * wi0, p0.y + nrm.y * wi0)))
            }
            continue
        }
        val band = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(b.x + nrm.x * edgeW, b.y + nrm.y * edgeW)
            lineTo(a.x + nrm.x * edgeW, a.y + nrm.y * edgeW)
            close()
        }
        drawPath(band, bandBrush(a, Offset(a.x + nrm.x * edgeW, a.y + nrm.y * edgeW)))
    }

    // 拐角：两侧各补一块三角，拼成完整的 L 形拐角影。
    for (i in 1 until n - 1) {
        val v = pts[i]
        val prev = norms[i - 1]
        val cur = norms[i]
        val ax = v.x + prev.x * edgeW
        val ay = v.y + prev.y * edgeW
        val bx = v.x + cur.x * edgeW
        val by = v.y + cur.y * edgeW
        val mx = v.x + (prev.x + cur.x) * edgeW
        val my = v.y + (prev.y + cur.y) * edgeW
        val tri1 = Path().apply {
            moveTo(v.x, v.y)
            lineTo(ax, ay)
            lineTo(mx, my)
            close()
        }
        drawPath(tri1, bandBrush(v, Offset(ax, ay)))
        val tri2 = Path().apply {
            moveTo(v.x, v.y)
            lineTo(bx, by)
            lineTo(mx, my)
            close()
        }
        drawPath(tri2, bandBrush(v, Offset(bx, by)))
    }
}

/** 折痕旁的一道影，落在**露出页**那一侧（纸边压在新页上的接触影）。 */
private fun DrawScope.drawCreaseShadow(shape: CurlShape) {
    val h = shape.pageH
    val w = shape.pageW
    val topC = Offset(shape.topX, 0f)
    val botC = Offset(shape.bottomX, h)
    var nx = -(botC.y - topC.y)
    var ny = (botC.x - topC.x)
    val nl = hypot(nx, ny).coerceAtLeast(1e-3f)
    nx /= nl
    ny /= nl
    // 朝「平铺过去的那一侧」（纸背一侧）取反 ⇒ 影落在折痕的另一侧。
    val toFlatX = -topC.x - botC.x
    val toFlatY = h - topC.y - botC.y
    val sgn = if (nx * toFlatX + ny * toFlatY < 0f) -1f else 1f
    val bandW = w * CURL_SHADOW_BAND
    val alpha = SHADOW_ALPHA * shape.shadowFade
    if (alpha <= 0.002f) return
    val band = Path().apply {
        moveTo(topC.x, topC.y)
        lineTo(botC.x, botC.y)
        lineTo(botC.x - nx * sgn * bandW, botC.y - ny * sgn * bandW)
        lineTo(topC.x - nx * sgn * bandW, topC.y - ny * sgn * bandW)
        close()
    }
    drawPath(
        band,
        Brush.linearGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            start = topC,
            end = Offset(topC.x - nx * sgn * bandW, topC.y - ny * sgn * bandW),
        ),
    )
}
