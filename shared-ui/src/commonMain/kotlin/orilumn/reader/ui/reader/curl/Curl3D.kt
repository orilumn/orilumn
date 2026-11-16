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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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
 * ## 压淡与明暗：一律照着**网格自己的轮廓**画
 *
 * 纸背压淡与纸卷明暗都是「沿折痕法向的一维函数」，但它们只能落在**纸真正占的那块形状**上。
 * 上一版把它们画成「沿折痕的一条平行四边形」，折痕一斜、纸一少，这条带子就横穿整个折痕，
 * 读者看到的是几道半透明条。现在两样都取 [CurlMesh.bandOutline] 的外轮廓当裁剪形状：
 * 形状就是纸，纸有多少它就有多少，**不可能漏**。
 *
 * 明暗的取值照抄参考实现的顶点色公式
 * `√(sinθ + 1.5)·0.9 + 0.1`（θ 从 0 到 π，中间最亮）。参考实现是**乘**（过曝到白），
 * Compose 的渐变是 8 位色、表达不了大于 1 的乘数，所以这里按等价的「往白里压」实现：
 * 近白的纸直接到白，墨色只被压一点点，读起来就是「纸面在反光」。
 *
 * ## 纸卷在哪一侧、平铺段在哪一侧
 *
 * 卷起来的那一半朝**掀起侧**鼓出去（露出页就在这一侧），绕满半圈后的平铺段落回**没掀起**
 * 的那一侧（盖在还没翻走的纸上）。纸卷因此压在新页靠近折痕的那一条上，落影从纸卷轮廓
 * 再往外铺；平铺段是纸背，压淡之后能透出底下的字。
 *
 * 两侧接反（纸卷鼓向没掀起的那半页、平铺段盖住新页）就是上一版的两个症状：
 * 同一段文字在旧页上叠两遍（「三段文字」），翻到九成平铺段又把新页盖回去（「几乎没翻动」）。
 *
 * ## 画序（与 z 从小到大一致）
 *
 * 1. 正面页整页 → 2. 露出页（多边形裁切）→ 3. 落影（纸卷压在露出页上、纸背外沿压在旧页上）
 * → 4. 各段网格：纹理 → 纸背压淡 → 纸卷明暗（按 z 顺序逐段完成，段与段之间不打架）。
 *
 * ## 纸背要让人看见
 *
 * 平铺段是「翻过去的那半页纸的反面」，它跟底下的旧页**同纸同色**：不落影时读者只看见旧页的
 * 一行字被切断、切断处是同样的纸色，读起来就是「纸背根本没画」。所以纸背外沿要往外落一道影
 * （见 [CURL3D_BACK_SHADOW_WIDTH]），影落在旧页上 ⇒ 读者一眼看出那里压着一张纸。
 * 半径那边还有一条配套的上限（[CURL3D_BACK_SHARE]）：翻起来的那部分纸至少一半是平铺的。
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
): Boolean {
    val w = shape.pageW
    val h = shape.pageH
    val pageSize = Size(w, h)
    val paper = Color(paperArgb)

    // 还没开始翻：只画读者正在看的那一页（`front` 永远是被翻走的那一张）。
    if (shape.progress <= CURL_START_EPSILON) {
        drawPage(front, paper, texLeft, texTop, pageSize)
        return true
    }
    // 已经翻完：只剩目标页。收尾时纸卷半径趋近 0，但残留的一道细边会在撤层那一帧闪一下，
    // 所以最后这一段直接整页换成目标页（与折页那条 [drawCurlFrame] 同口径）。
    if (shape.progress >= 1f - CURL_END_EPSILON) {
        drawPage(back, paper, texLeft, texTop, pageSize)
        return true
    }

    // ① 正面页（被翻走的那一页，整页铺在底下）② 露出页（折痕另一侧的新页）
    drawPage(front, paper, texLeft, texTop, pageSize)
    val corners = revealCorners(shape)
    if (corners.size < 3) return true
    clipPath(polygonPath(corners)) { drawPage(back, paper, texLeft, texTop, pageSize) }

    // ③ 网格几何
    val radius = curlRadius(shape, radiusRatio)
    val mesh = buildCurlMesh(shape, radius = radius, splits = CURL3D_SPLITS)
    if (mesh.indexCount == 0) return false

    // 折痕坐标系（与 [buildCurlMesh] 同一套）：t 沿折痕向下，nR 是法向（指向 +x 一侧），
    // up 是「从折痕指向掀起侧」的方向——纸卷在这一侧鼓出去，平铺段落在另一侧；
    // 明暗与落影都沿它取值，两端（翻页方向）通用。
    val t0 = Offset(shape.topX, 0f)
    val t1 = Offset(shape.bottomX, h)
    val foldLen = hypot(t1.x - t0.x, h).coerceAtLeast(1e-3f)
    val nR = Offset(h / foldLen, -(t1.x - t0.x) / foldLen)
    val side = if (shape.direction >= 0) 1f else -1f
    val up = Offset(nR.x * side, nR.y * side)

    // ④ 落影：纸卷压在露出页上。参考实现里落影顶点的偏移是 `z/2`、浓度按 `z/(2R)` 插值
    //    （`SHADOW_INNER_COLOR` 黑 0.5 → 透明），所以影的宽度与浓度都只跟「纸抬多高」有关：
    //    纸刚离页时几乎没有影，卷得越高影越远越深。
    val lift = radius * (1f - cos(minOf(mesh.maxArc() / radius, PI.toFloat())))
    val silhouette = mesh.maxOffsetAlong(up.x, up.y, t0)
    clipRect(0f, 0f, w, h) {
        drawFanShadow(
            t0, t1, up,
            from = silhouette,
            width = lift * 0.5f,
            alpha = DROP_SHADOW_ALPHA * (lift / (2f * radius)).coerceIn(0f, 1f) * shape.shadowFade,
        )
        // 纸背自己的外沿也要落影：平铺段是一整页纸盖在旧页上，跟旧页同纸同色，
        // 不落影的话读者只看见旧页的字被切断、切断处空无一物 —— 看起来就是「纸背没画」。
        // 影从纸背外沿往外铺（朝 −up，也就是旧页那一侧），压在旧页上、被纸背自己盖住的部分为零。
        val backEdge = mesh.minOffsetAlong(up.x, up.y, t0)
        if (backEdge < -1f) {
            // `drawFanShadow` 的宽度是「沿 n 的正向」，所以这里取 −up、用正的宽度往旧页那边铺。
            drawFanShadow(
                t0, t1, Offset(-up.x, -up.y),
                from = -backEdge,
                width = radius * CURL3D_BACK_SHADOW_WIDTH,
                alpha = DROP_SHADOW_ALPHA * CURL3D_BACK_SHADOW_ALPHA * shape.shadowFade,
            )
        }
    }

    // ⑤ 各段网格（按 z 顺序）：纹理 → 纸背压淡 → 纸卷明暗
    val canvas = drawContext.canvas
    val nv = mesh.vertexCount
    val posList = List(nv) { Offset(mesh.positions[it * 2], mesh.positions[it * 2 + 1]) }
    // 纹理坐标要减去页栅格在页面里的落点（版心偏移）：`ImageShader` 没有局部矩阵，
    // 只能把偏移提前算进纹理坐标里。覆盖层是整页图，偏移恒为 0。
    val pageTex = List(nv) {
        Offset(mesh.texCoords[it * 2] - texLeft, mesh.texCoords[it * 2 + 1] - texTop)
    }
    val overlayTex = List(nv) { Offset(mesh.texCoords[it * 2], mesh.texCoords[it * 2 + 1]) }
    // 纸卷绕过半圈之后，纸背（Verso）盖在正面（Recto）上，但两段的屏幕范围是「整条 ÷ 最左一截」：
    // 明暗沿法向只跟 q 有关，所以叠在**整条**（Recto）上算一次就够，只有没有 Recto 时才用 Verso。
    // 明暗只画在**看得见**的那一面：纸卷隆起后外层是纸背（Verso，z ≥ R，压在正面那半圈之上），
    // 没绕到九十度时只有正面（Recto）露在外面。给被压住的那一段画明暗等于白画。
    val hasVerso = mesh.bands.any { it.surface == CurlMesh.Surface.Verso }
    // 纸边被卷起来时可以略微探出页面（斜折痕 + 抬起的纸），画布裁到页面内即可。
    clipRect(0f, 0f, w, h) {
        for (band in mesh.bands) {
            if (band.indexCount <= 0) continue
            val idx = List(band.indexCount) { mesh.indices[band.indexStart + it].toInt() }
            front.page?.let { drawMeshBand(canvas, posList, idx, pageTex, it) }
            front.overlay?.let { drawMeshBand(canvas, posList, idx, overlayTex, it) }

            val ring = mesh.bandOutline(band)
            if (ring.size < 3) continue
            val outline = polygonPath(ring)
            // 纸背：纸色盖上去 10% ⇒ 内容剩 90%（参考实现 `BACKFACE_ALPHA = 0.9`），
            // 正面页的内容从下面透出一点，正好是「隔着纸看见反面的字」。
            if (band.surface != CurlMesh.Surface.Recto) {
                drawPath(outline, paper, alpha = CURL_BACK_WASH_ALPHA)
            }
            val topmost = when (band.surface) {
                // 正面那半圈被外层（纸背）压住时不用画；只露正面时才是它。
                CurlMesh.Surface.Recto -> !hasVerso
                else -> true
            }
            if (CURL3D_SHADING && topmost) drawBandShade(outline, mesh, band, radius, t0, up)
        }
    }
    return true
}

/** 某一段的明暗：沿 `up` 方向的一条渐变，取值照参考实现的顶点色公式。 */
private fun DrawScope.drawBandShade(
    outline: Path,
    mesh: CurlMesh,
    band: CurlMesh.Band,
    radius: Float,
    origin: Offset,
    up: Offset,
) {
    var u0 = Float.MAX_VALUE
    var u1 = -Float.MAX_VALUE
    for (row in band.firstRow..band.lastRow) {
        for (k in 0..1) {
            val v = row * 2 + k
            val q = (mesh.positions[v * 2] - origin.x) * up.x +
                (mesh.positions[v * 2 + 1] - origin.y) * up.y
            if (q < u0) u0 = q
            if (q > u1) u1 = q
        }
    }
    if (u1 - u0 < 1f) return
    val stops = Array(ROLL_SHADE_STOPS + 1) { k ->
        val t = k.toFloat() / ROLL_SHADE_STOPS
        t to Color(1f, 1f, 1f, shadeAlpha(u0 + (u1 - u0) * t, radius))
    }
    drawPath(
        outline,
        Brush.linearGradient(
            colorStops = stops,
            start = origin + up * u0,
            end = origin + up * u1,
        ),
    )
}

/**
 * 「反光」强度：`q` 是该点沿 [up] 离折痕的屏幕偏移。
 *
 * 纸卷在 `0..R` 这一段（朝掀起侧鼓出去），绕满半圈后的平铺段在 `q < −πR`（落到另一侧）。
 *
 * 参考实现：`θ` 处的顶点色 = `√(sinθ + 1.5)·0.9 + 0.1`，`sinθ = clamp(x'/R)`，且只对
 * 卷起来的那一段生效（θ 从 0 到 π，中间 θ = π/2 时 `sinθ = 1` 最亮）。这里把它归一化成
 * 叠白的不透明度：最暗处（θ = 0 或 π）为 0，最亮处为 [ROLL_SHADE_MAX_ALPHA]。
 */
private fun shadeAlpha(q: Float, radius: Float): Float {
    val sinTheta = (q / radius).coerceIn(0f, 1f)
    val bright = sqrt(sinTheta + 1.5f) * 0.9f + 0.1f
    return ROLL_SHADE_MAX_ALPHA *
        ((bright - REFERENCE_MIN_BRIGHT) / REFERENCE_BRIGHT_SPAN).coerceIn(0f, 1f)
}

/** 参考实现最暗 / 最亮的顶点色：`√(sinθ+1.5)·0.9+0.1` 在 sinθ = 0 与 1 处的取值。 */
private val REFERENCE_MIN_BRIGHT: Float = sqrt(1.5f) * 0.9f + 0.1f // ≈ 1.202
private val REFERENCE_MAX_BRIGHT: Float = sqrt(2.5f) * 0.9f + 0.1f // ≈ 1.523
private val REFERENCE_BRIGHT_SPAN: Float = REFERENCE_MAX_BRIGHT - REFERENCE_MIN_BRIGHT

/** 明暗总开关（排查用）：关掉纸卷反光，只留纹理与纸背压淡。 */
private const val CURL3D_SHADING: Boolean = true

/** 明暗最深处的叠白不透明度。 */
private const val ROLL_SHADE_MAX_ALPHA: Float = 0.30f

/** 落影最深处的不透明度（参考实现 `SHADOW_INNER_COLOR` 的 alpha = 0.5）。 */
private const val DROP_SHADOW_ALPHA: Float = 0.32f

/** 明暗渐变的分段数（一维函数的采样密度，够到看不出折线）。 */
private const val ROLL_SHADE_STOPS: Int = 16

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

/**
 * 沿折痕方向的一条渐变影带：从折痕坐标系里的 `from` 偏移处开始，向 `width` 方向铺开，
 * 起点 `alpha`、终点透明。影的形状只跟折痕和卷高有关，沿折痕方向是均匀的，
 * 所以用一条平行四边形 + GPU 渐变画笔就够（调用方负责裁到页面内）。
 */
private fun DrawScope.drawFanShadow(
    t0: Offset,
    t1: Offset,
    n: Offset,
    from: Float,
    width: Float,
    alpha: Float,
) {
    if (width <= 0.5f || alpha <= 0f) return
    val a = t0 + n * from
    val b = t0 + n * (from + width)
    val c = t1 + n * (from + width)
    val d = t1 + n * from
    drawPath(
        polygonPath(listOf(a, b, c, d)),
        Brush.linearGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            start = a,
            end = b,
        ),
    )
}

/** 页面里的一个多边形（路径）。 */
private fun polygonPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    close()
}

/** 网格里沿折痕法向最远的偏移（纸卷轮廓），从折痕上端量起。 */
private fun CurlMesh.maxOffsetAlong(nx: Float, ny: Float, origin: Offset): Float {
    var m = 0f
    var i = 0
    while (i < positions.size) {
        val v = (positions[i] - origin.x) * nx + (positions[i + 1] - origin.y) * ny
        if (v > m) m = v
        i += 2
    }
    return m
}

/** 网格里沿折痕法向最负的偏移（平铺纸背的外沿；没有平铺段时是纸卷内侧，接近 0）。 */
private fun CurlMesh.minOffsetAlong(nx: Float, ny: Float, origin: Offset): Float {
    var m = 0f
    var i = 0
    while (i < positions.size) {
        val v = (positions[i] - origin.x) * nx + (positions[i + 1] - origin.y) * ny
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
 * 卷曲半径的上限（相对页宽）。
 *
 * **纸卷一定压在露出页靠近折痕的那一条上**：纸在折痕处抬离页面、朝掀起侧鼓成圆柱、绕满半圈
 * 再落到另一边，所以「被纸卷盖住的宽度」就等于半径。半径取多大，直接决定露出页被咬掉多宽。
 *
 * 参考实现的 `dWidth`（`NewCurl3D.updateCurlPos`：`页宽 × 1.5/3 × (1−压力)`，点按翻页用的压力
 * 是 0.7843）算出 ≈ 0.108 页宽，平板上是 200px。真机上读者看到的是：翻到半页之前，露出页只露
 * 每行的行尾、被压在一个大卷筒底下，像「纸一直往卷筒上卷」；翻到一半以后才看得出是新页，
 * 而且每行左边都缺一截。
 *
 * 取 0.05 页宽（平板上 92px）：**露出宽度 = 进度 × 页宽 − 半径**，翻到一半新页露出将近一半，
 * 纸卷加落影合计 0.15 页宽，压不出「一条灰色宽边」。见 [CURL3D_REFERENCE_RADIUS_RATIO]。
 */
internal const val CURL3D_RADIUS_RATIO: Float = 0.05f

/**
 * 平铺的纸背（掀起材料里没卷在筒上的那一半）**至少**占掀起材料的这个比例。
 *
 * 纸卷绕半圈吃掉 `πR` 的纸，所以 `R ≤ 掀起长度 × 0.5 / π` 时，翻过去的纸里至少一半是平铺的、
 * 看得见纸背。没有这条上限，半径按 5% 页宽算时「翻到 16% 页宽之前纸背根本不存在」，
 * 读者看到的是一页字被硬生生切断、切断处空无一物。见 [curlRadius]。
 */
internal const val CURL3D_BACK_SHARE: Float = 0.5f

/** 纸背外沿落影的宽度（相对卷曲半径）与浓度（相对 [DROP_SHADOW_ALPHA]）。 */
internal const val CURL3D_BACK_SHADOW_WIDTH: Float = 0.8f
internal const val CURL3D_BACK_SHADOW_ALPHA: Float = 0.65f

/** 参考实现的压力值（`pageScroll` 点按翻页用 200/255）。 */
internal const val CURL3D_REFERENCE_PRESSURE: Float = 0.7843f

/** 参考实现在这个压力下的半径上限（≈ 0.108 页宽）：只作对照，本仓不取这个值。 */
internal const val CURL3D_REFERENCE_RADIUS_RATIO: Float =
    0.5f * (1f - CURL3D_REFERENCE_PRESSURE)

/**
 * 卷曲半径的下限（相对页宽）。
 *
 * 只防止除零 / 扫线全部重合，不是「收尾也要留个筒」：翻到最后半径本来就该收敛到 0
 * （剩下的纸没有了，纸卷跟着消失），留一个百分之一的筒反而会在末帧留一道边。
 */
internal const val CURL3D_MIN_RADIUS_RATIO: Float = 0.0005f

/** 圆柱横截面分段数。 */
internal const val CURL3D_SPLITS: Int = 40
