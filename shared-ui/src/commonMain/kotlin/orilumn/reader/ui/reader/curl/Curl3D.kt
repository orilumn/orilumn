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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.atan2
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
 * 1. 正面页整页 → 2. 露出页（多边形裁切）→ 3. 落影（纸卷压在露出页上、纸背的直角边压在旧页上）
 * → 4. 各段网格：纹理 → 纸背压淡 → 纸卷明暗（按 z 顺序逐段完成，段与段之间不打架）。
 *
 * ## 纸背要让人看见
 *
 * 平铺段是「翻过去的那半页纸的反面」，它跟底下的旧页**同纸同色**。这条被判死过三次，
 * 最后定下来的做法是**三件事一起做**：
 *
 * 1. **筒要够粗**：[CURL3D_RADIUS_RATIO] 取 0.10 页宽。筒太细时掀起的纸一大半被压进几十个
 *    像素里，读者只看见一条白边，谈不上「反面」。
 * 2. **平铺段从第一下拖动就有**：[CURL3D_BACK_SHARE] 取 0.7，卷进筒的最多七成，剩下三成平铺
 *    在旧页上。纸卷绕半圈要吃掉 `πR` 的纸，不留这条余量时掀起的纸整段在筒上，一段平铺的
 *    纸背都没有。
 * 3. **纸背只靠自己的镜像内容和折角那两条直角边的接触影**证明它是一张纸。**不描边、不压暗**：
 *    描一条与折痕平行的实线，读者看到的就是「卷筒左侧凭空多出一条痕迹」；把整片纸背压暗，
 *    读者看到的就是「反面整体比正面暗」。
 *
 * 纸卷那边**一律不压暗**。纸从筒上绕回平铺段是**平滑切线**：那里两边都是纸、亮度本来就接得上，
 * 在交界处压一层暗（哪怕只有一成、哪怕铺成缓坡）都会在筒的左侧留下一道硬边 —— 读者一眼就判
 * 「这里平白画了一条折痕」。筒身只靠明暗（往白里压的反光）与落影站立，暗面一概不画。
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
    val shadowWidth = lift * 0.5f
    val shadowAlpha = DROP_SHADOW_ALPHA * (lift / (2f * radius)).coerceIn(0f, 1f) * shape.shadowFade
    clipRect(0f, 0f, w, h) {
        drawRollShadow(
            mesh = mesh,
            start = t0 + up * silhouette,
            up = up,
            width = shadowWidth,
            alpha = shadowAlpha,
        )
        // 纸背的纸边要落影：平铺段是一整页纸盖在旧页上，跟旧页同纸同色，
        // 不落影时读者只看见旧页的字被切断、切断处空无一物 —— 看起来就是「纸背没画」。
        //
        // 只给**折角那两条直角边**落影（纸的上下边转到屏幕上来的那两条，斜折痕时它们在页面
        // 中间相交成直角）。远端那条与折痕平行的自由边**不落影**：它挨着纸卷，铺一条影过去
        // 就成了读者说的「卷筒左侧那条平行痕迹 + 一道影」，那里是纸自己压着纸、没有影。
        //
        // 每条影按**这条边自己的法向**往外铺（背离纸心），不按折痕方向：纸边跟着折痕一起斜。
        // 两条影带在纸角相交，各自铺到角上就停；转角那块直角缺口用一把径向扇形影补上
        // （[drawCornerShadow]），接缝上浓度相同，所以是一整片影，不是两条带子各停各的。
        val corners = mesh.backCorners()
        if (corners != null) {
            val (near0, far0, far1, near1) = corners
            val center = corners.reduce { acc, p -> acc + p } / corners.size.toFloat()
            val width = radius * CURL3D_BACK_SHADOW_WIDTH
            val alpha = DROP_SHADOW_ALPHA * CURL3D_BACK_SHADOW_ALPHA * shape.shadowFade
            for ((a, b) in listOf(far0 to near0, far1 to near1)) {
                drawEdgeShadow(edgeA = a, edgeB = b, center = center, width = width, alpha = alpha)
            }
            // 自由边退化成一点时（斜折痕掀到页角），两条直角边共用这个纸角：补齐转角。
            // 自由边还有长度时，每个角只有一条边落影，另一边是「纸压着纸」，不补。
            if ((far0 - far1).getDistance() < 0.5f) {
                drawCornerShadow(far0, near0, near1, center, width, alpha)
            }
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
            val ring = mesh.bandOutline(band)
            if (ring.size < 3) continue
            val outline = polygonPath(ring)
            // 纸的每一段都是**不透明的一张纸**：先照这一段自己的轮廓铺一层纸色。
            // 纹理只在下面那块「版心形状」里画，版心之外的页边距就露出这一层纸
            //（纸背跟旧页同纸同色，所以纸背的页边距看起来仍是纸，不是空白条）。
            drawPath(outline, paper)
            // 纹理**只能画在版心材料范围内**：页栅格只有版心那么大（平板 1585×2400 放在
            // 1840×2800 里），页边距里根本没有像素。越界采样时 `Clamp` 会把版心的最边一列
            // 拉成一条横贯页边距的短线——纸背那一段扫线正好落在页边距里，读者看到的就是
            // 「反面页边距上有好几条短横线」。按材料坐标裁出形状再画，越界就不存在了
            // （纸卷那边同理：卷进页边距的一截只露纸色）。折痕一斜，一条扫线上的材料 x
            // 也在变，所以裁剪按**两条边**做（见 `textureBandOutline`）。
            val pageImage = front.page
            val texRing = pageImage?.let {
                mesh.textureBandOutline(band, texLeft, texTop, it.width.toFloat(), it.height.toFloat())
            }
            if (pageImage != null && texRing != null && texRing.size >= 3) {
                clipPath(polygonPath(texRing)) {
                    drawMeshBand(canvas, posList, idx, pageTex, pageImage)
                }
            }
            // 覆盖层是整页图（旁注 + 页码），材料坐标本来就在页内，不需要裁。
            front.overlay?.let { drawMeshBand(canvas, posList, idx, overlayTex, it) }

            // ⑥ 卷筒**内侧**的阴影：只吃正面那四分之一圈（`z < R` 的内壁），
            //    折痕处为 0、越靠筒口越深（见 [drawCreaseShade]）。
            //    画在正面这一段之上、纸背与外圈之下 ⇒ 中间那段被后画的纸背/外圈盖掉，
            //    真正露出来的只剩**卷筒探出折角（平铺纸背那个三角形）以外的上下两头**，
            //    那里露出来的是筒的内壁。纸背是纸的正面、外圈是纸的外侧，都不吃这层影。
            if (CURL3D_SHADING && band.surface == CurlMesh.Surface.Recto) {
                drawCreaseShade(ring, up, radius, ROLL_INNER_SHADOW_ALPHA * shape.shadowFade)
            }

            // 纸背：纸色只盖一成 ⇒ 内容仍留九成（参考实现 `BACKFACE_ALPHA = 0.9`）。
            if (band.surface != CurlMesh.Surface.Recto) {
                drawPath(outline, paper, alpha = CURL_BACK_WASH_ALPHA)
            }
            // 纸背**不描边、不压暗**：它的边是纸自己的边，画成实线就成了读者说的
            // 「卷筒左侧那条与折痕平行的痕迹」。纸背只留自己的镜像内容 + 一层纸色压淡。
            val topmost = when (band.surface) {
                // 正面那半圈被外层（纸背）压住时不用画；只露正面时才是它。
                CurlMesh.Surface.Recto -> !hasVerso
                else -> true
            }
            if (CURL3D_SHADING && topmost) {
                // 筒身只有「反光」这一层明暗（往白里压）；筒内侧的暗另走折痕那条渐变
                // （见上面的 `drawCreaseShade`），不在这里给整段压暗。
                drawBandShade(outline, mesh, band, radius, t0, up)
            }
        }
    }
    return true
}

/**
 * 卷筒**内侧**的阴影：沿折痕法向 [up] 的一条渐变，`q = 0`（折痕）处为 0，越靠**筒口**
 * （筒的外沿、纸背那半圈包住它的地方）越深。
 *
 * 方向别接反：折痕这一侧的纸面还朝着光，暗的是**背离折痕的筒口**那一侧 —— 参考实现
 * （moon+ `NewCurl3D` 的自阴影）就是拿筒外半圈（`z > R`）的顶点往外铺，深的一头在外沿。
 * 早先接反成「折痕最深」，读者的原话是「圆筒内部的阴影方向画反了，应该是右侧深、左侧浅」。
 *
 * 形状只取「正面那一段纸卷」的轮廓 [ring]（见 `CurlMesh.bandOutline`），所以它**只落在纸卷上**：
 * 平铺的纸面、纸背（折角三角形）与外圈都不吃这层影。中段被外圈压住，露出来的只有
 * **卷筒探出折角以外的上下两头** —— 那里看见的正是筒里的内壁。
 */
private fun DrawScope.drawCreaseShade(
    ring: List<Offset>,
    up: Offset,
    radius: Float,
    alpha: Float,
) {
    if (alpha <= 0f || ring.size < 3) return
    val reach = radius * ROLL_INNER_SHADOW_REACH
    if (reach <= 0.5f) return
    // 折痕这条边 = 轮廓的首个顶点；渐变从它往筒口铺，`q = 0` 透明、`q = reach` 最深。
    val fold = ring.first()
    drawPath(
        polygonPath(ring),
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color(0f, 0f, 0f, 0f),
                1f to Color(0f, 0f, 0f, alpha),
            ),
            start = fold,
            end = fold + up * reach,
        ),
    )
}

/**
 * 纸背的四个角（屏幕坐标），顺序 `[近-侧0, 远-侧0, 远-侧1, 近-侧1]`。
 *
 * 「远」是离折痕最远的那一头（纸伸得最远的那条自由边），「近」是纸绕上纸卷的那一头。
 * 三条可见纸边 = 自由边（远0→远1）+ 两条直角边（远0→近0、远1→近1）；**只有两条直角边落影**：
 * 自由边挨着纸卷，那里是纸压着纸；近的那条不是纸边（纸在那里绕上筒）。
 */
internal fun CurlMesh.backCorners(): List<Offset>? {
    val back = bands.firstOrNull { it.surface == CurlMesh.Surface.Back } ?: return null
    val span = back.lastRow - back.firstRow
    if (span < 1) return null
    val ring = bandOutline(back)
    val last = ring.size - 1
    if (last <= span + 1) return null
    return listOf(ring[0], ring[span], ring[span + 1], ring[last])
}

/**
 * 从一条纸边 `edgeA..edgeB` 朝**背离纸心**的一侧铺一道窄影：边上 `alpha`、到 `width` 处透明。
 *
 * 方向按**这条边自己的法向**算，不按折痕算 —— 纸背那两条直角边跟着折痕一起斜、法向互不相同；
 * 统一按折痕铺时，影会在两条直角边上跑到纸底下去、或者飘在页面中间。
 */
private fun DrawScope.drawEdgeShadow(
    edgeA: Offset,
    edgeB: Offset,
    center: Offset,
    width: Float,
    alpha: Float,
) {
    val ex = edgeB.x - edgeA.x
    val ey = edgeB.y - edgeA.y
    val len = hypot(ex, ey)
    if (len < 1f || width <= 0.5f || alpha <= 0f) return
    var outward = Offset(ey / len, -ex / len)
    val midX = (edgeA.x + edgeB.x) * 0.5f
    val midY = (edgeA.y + edgeB.y) * 0.5f
    if ((midX - center.x) * outward.x + (midY - center.y) * outward.y < 0f) {
        outward = Offset(-outward.x, -outward.y)
    }
    // 影带只铺到纸角为止；纸角那个「直角缺口」由 [drawCornerShadow] 用一把扇形径向影补上，
    // 两边的浓度在接缝上正好相等，所以既有转角又不留硬边。
    val farA = edgeA + outward * width
    val farB = edgeB + outward * width
    drawPath(
        polygonPath(listOf(edgeA, farA, farB, edgeB)),
        Brush.linearGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            start = edgeA,
            end = farA,
        ),
    )
}

/**
 * 纸角那 90° 的接触影：以纸角为心、影宽为半径的一块扇形，浓度从角上的满值径向淡到 0。
 *
 * 只铺两条纸边时，每条影带都在「过纸角的垂线」处停住，两条带子之间就空出一个直角缺口。
 * 扇形的两条直边正好是这两条终止线，边上的浓度与影带一致（都是 `alpha · (1 − r / width)`）
 * —— 所以拼起来是一整片，没有接缝也没有双重加深。
 */
private fun DrawScope.drawCornerShadow(
    corner: Offset,
    nearA: Offset,
    nearB: Offset,
    center: Offset,
    width: Float,
    alpha: Float,
) {
    if (width <= 0.5f || alpha <= 0f) return

    /** 纸边朝纸外的那条法向。 */
    fun outward(to: Offset): Offset {
        val dx = to.x - corner.x
        val dy = to.y - corner.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) return Offset.Zero
        var n = Offset(dy / len, -dx / len)
        val mx = (corner.x + to.x) * 0.5f - center.x
        val my = (corner.y + to.y) * 0.5f - center.y
        if (mx * n.x + my * n.y < 0f) n = Offset(-n.x, -n.y)
        return n
    }

    val n1 = outward(nearA)
    val n2 = outward(nearB)
    if (n1 == Offset.Zero || n2 == Offset.Zero) return
    val a1 = atan2(n1.y, n1.x)
    val a2 = atan2(n2.y, n2.x)
    val twoPi = 2f * PI.toFloat()
    var delta = a2 - a1
    // 两条法向之间取那个 90°（另一侧是 270°）。
    while (delta > PI) delta -= twoPi
    while (delta < -PI) delta += twoPi
    val steps = 12
    val pts = ArrayList<Offset>(steps + 2)
    pts.add(corner)
    for (k in 0..steps) {
        val ang = a1 + delta * k / steps
        pts.add(Offset(corner.x + cos(ang) * width, corner.y + sin(ang) * width))
    }
    drawPath(
        polygonPath(pts),
        Brush.radialGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            center = corner,
            radius = width,
        ),
    )
}

/**
 * 某一段的明暗：沿 `up` 方向的一条渐变，取值照参考实现的顶点色公式。 */
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
        // 纹理**只在版心材料范围内**绘制（见 [drawCurl3DFrame] 里的 `textureBandOutline`）：
        // 页栅格只有版心那么大，越界采样会把版心的最边一列/一行拉成条纹——读者看到的
        // 「反面页边距上的几条短横线」就是 `Clamp` 抹出来的。形状已经裁好，
        // 这里保留 `Clamp` 只是兜底（越界像素不参与绘制）。
        shader = ImageShader(image, TileMode.Clamp, TileMode.Clamp)
        // 纸卷把材料压进不到一个半径的屏幕宽度，最邻近采样会把文字抽成锯齿斜条。
        filterQuality = FilterQuality.Low
        blendMode = BlendMode.SrcOver
    }
    drawIntoCanvas { it.drawVertices(vertices, BlendMode.SrcOver, paint) }
}

/**
 * 纸卷压在露出页上的落影：形状由 [rollShadowStrip] 给出（贴着纸卷轮廓的一条带），
 * 浓度沿折痕法向线性衰减 —— [start] 就是筒的轮廓（最深），往外 [width] 散尽。
 *
 * 影的内侧边界必须**就是纸卷的边**（不能是与折痕平行的直线：纸边在两端是弯的，
 * 直线与弯边之间会裂出一条没影的窄带）。
 */
private fun DrawScope.drawRollShadow(
    mesh: CurlMesh,
    start: Offset,
    up: Offset,
    width: Float,
    alpha: Float,
) {
    if (width <= 0.5f || alpha <= 0f) return
    val strip = mesh.rollShadowStrip(width, up) ?: return
    // 渐变照折痕坐标系取：`start` 就是筒的轮廓（最深），往外 `width` 散尽。
    // 轮廓平移后的近侧落在纸底下，由后画的纸盖住，不参与可见区。
    drawPath(
        strip,
        Brush.linearGradient(
            colorStops = arrayOf(0f to Color(0f, 0f, 0f, alpha), 1f to Color(0f, 0f, 0f, 0f)),
            start = start,
            end = start + up * width,
        ),
    )
}

/**
 * 落影的形状：**贴着纸卷外轮廓的一条带** —— 把纸卷的轮廓逐段沿 [up] 挤出 [width]
 * （参考实现是「网格顶点统一偏移 `z/2`」）。
 *
 * 为什么必须贴着轮廓走：影是筒压在露出页上压出来的，**离筒边越远越淡**，所以影的内侧边界
 * 就是纸卷的边。取「与折痕平行的直线」当内侧边界时，纸的切边在两端是弯的（圆弧），
 * 直线与弯边之间就裂出一条**没影的窄带**——读者原话「中间出现奇怪的无阴影区，这时候已经
 * 没有折痕了，应该沿着卷起的页边继续画阴影」。
 *
 * 轮廓在折痕那一侧的半圈落在纸底下，由后画的纸盖掉，不参与可见区；露出页这一侧才是读者
 * 看见的那条影。影到纸边即止（不顺着折痕拖到纸外面），到屏幕边也照样铺满（纸被页边切断
 * 的地方，影跟着被切断）。
 *
 * 为什么取 [CurlMesh.Surface.Recto]：它是「折痕 → 筒口」那半圈，外沿就是筒的轮廓（`q` 最大处），
 * 纸背（Verso）与折痕另一侧的外圈都在它里面或背后。没有筒（还没到九十度）时它也一定在；
 * 真连它都没有，这一帧就不该有影（那时纸背那半圈的**首行**才是轮廓）。
 */
internal fun CurlMesh.rollShadowStrip(width: Float, up: Offset): Path? {
    val band = bands.firstOrNull { it.surface == CurlMesh.Surface.Recto }
        ?: bands.firstOrNull { it.surface == CurlMesh.Surface.Verso }
        ?: return null
    if (width <= 0.5f) return null
    val ring = bandOutline(band)
    if (ring.size < 3) return null
    val shift = up * width
    // 逐段挤成小四边形，一次填满：绕向统一成「相对 [shift] 同号」，否则 nonZero 会拿反向的
    // 相邻块去减，接缝上会出现一条没影的细缝。
    val path = Path().apply { fillType = PathFillType.NonZero }
    var prev = ring.last()
    for (cur in ring) {
        val dx = cur.x - prev.x
        val dy = cur.y - prev.y
        val cross = dx * shift.y - dy * shift.x
        val (a, b) = if (cross >= 0f) prev to cur else cur to prev
        path.moveTo(a.x, a.y)
        path.lineTo(b.x, b.y)
        path.lineTo(b.x + shift.x, b.y + shift.y)
        path.lineTo(a.x + shift.x, a.y + shift.y)
        path.close()
        prev = cur
    }
    return path
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
 * 再落到另一边，所以「被纸卷盖住的宽度」就等于半径。半径取多大，直接决定:
 * 露出页被咬掉多宽（= 半径），以及**翻过去的那张纸有多少还卷在筒里**（= πR）。
 *
 * 参考实现的 `dWidth`（`NewCurl3D.updateCurlPos`：`页宽 × 1.5/3 × (1−压力)`，点按翻页用的压力
 * 是 0.7843）算出 ≈ 0.108 页宽，平板上是 200px。早先取 0.03 / 0.05 页宽时读者报「筒太细、
 * 折页反面根本看不出来」—— 筒细到一定程度，掀起的纸被压进几十像素，纸背就只剩一条白边。
 *
 * 定稿取 0.10 页宽（平板上 184px，接近参考实现）：筒身看得见、纸背有地方落。
 * 代价是筒会盖住露出页靠折痕的那一条（宽度 = 半径），翻到一半时约 0.03 页宽压在行首上；
 * 这是「看得见卷筒」与「新页少被咬一口」之间的取舍，读者优先要前者。
 * 见 [CURL3D_REFERENCE_RADIUS_RATIO]。
 */
internal const val CURL3D_RADIUS_RATIO: Float = 0.10f

/**
 * 允许卷进筒里的掀起材料比例上限。
 *
 * 纸卷绕半圈吃掉 `πR` 的纸。取名 `SHARE` 的是「卷进筒里」的比例：`R ≤ 掀起长度 × SHARE / π`
 * ⇒ 平铺段至少占 `1 − SHARE`。取 0.7 ⇒ **至少三成的掀起纸是平铺的、看得见纸背**。
 *
 * 为什么不是更小（更多平铺）：平铺段再宽，筒就细到读者看不见；为什么不取 1.0（全卷进筒）：
 * 起手那一段一段平铺纸背都没有，纸背的轮廓、落影、明暗全都不存在，读者仍会判「反面没画」。
 * 0.7 是「筒一眼看得见」与「纸背从第一下拖动就在」的交点。
 */
internal const val CURL3D_BACK_SHARE: Float = 0.7f

/**
 * 纸背两条直角边落影的宽度（相对卷曲半径）与浓度（相对 [DROP_SHADOW_ALPHA]）。
 *
 * 纸背是**平铺**的、几乎贴着旧页，所以影是接触影而不是卷筒那道宽影（2.0R 那种宽影铺在
 * 平铺段上读起来就是「页面中间莫名其妙一道灰带」）。0.7R 是「看得出来纸压着纸」与
 * 「不至于像一条灰带」之间取的宽度：早先 0.28R 在平板上只有三十几像素，读者判「太细」。
 */
internal const val CURL3D_BACK_SHADOW_WIDTH: Float = 0.7f
internal const val CURL3D_BACK_SHADOW_ALPHA: Float = 0.62f

/**
 * 卷筒内侧那条阴影：**筒口**（背离折痕的外沿）处最深，到折痕淡完。
 *
 * [ROLL_INNER_SHADOW_REACH] 是渐变铺到多远（相对卷曲半径，1 = 筒口）。
 * 早先这里是一层「整段压暗」，边界钉在纸从筒上绕回平铺段的切线上，压出一道硬边；
 * 也曾按整条折痕铺一条矩形式渐变（还把深浅方向接反），结果影越过卷筒落到纸背（纸的正面）
 * 与筒外圈上。现在只画在**正面那一段纸卷的轮廓里**（[drawCreaseShade]），中间被纸背与外圈
 * 压住，露出来的只有卷筒探出折角的两头。
 */
internal const val ROLL_INNER_SHADOW_ALPHA: Float = 0.22f
internal const val ROLL_INNER_SHADOW_REACH: Float = 1.0f

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
