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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
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
 * 1. 正面页整页 → 2. 露出页（多边形裁切）→ 3. 落影（纸卷压在露出页上、纸背的页边压在旧页上）
 * → 4. 各段网格：纹理 → 纸背压淡 → 纸卷明暗（按 z 顺序逐段完成，段与段之间不打架）
 * → 5. 纸背的页边接触影（**在全部纸段之后**落笔，见下）。
 *
 * ## 纸背的影只有一条：沿页边匀速收窄的那道
 *
 * 早先纸背这侧铺了三样影：两条直角边各一条等宽的带子，加一层卷筒内侧的暗影。三者
 * **互不交融**（直的等宽带子在纸卷边上硬停、卷筒内壁那条自成一块），读者判「左一条右一条」。
 * 现在只剩**一条沿页边走的接触影**（[drawBackEdgeShadow]）：形状跟着页边的折线走（平铺段是直线、
 * 卷起来那截是弧，一路铺到直角顶点），宽度从直角顶点往折痕端**匀速收到 0**；纸背刚出现时
 * 宽度从 0 渐入。卷筒内侧那层暗影已整体退役。
 *
 * **影只铺页边朝外的那一侧**（背离纸背）：纸背那一侧半条根本不画，所以纸背上不可能有灰带
 * （读者圈的就是这条）。朝外先在**平铺纸背**那一段定下，再由 [outwardNormals] 沿折线连续传递 ——
 * 不逐点看点乘：页边斜时扫线与页边不垂直，逐点判会在纸角翻向；卷起来那截页边在屏幕上还会
 * 掉头，只有「朝外连续」这条判据站得住。
 *
 * 影带**画在所有纸段之后**：只铺朝外一侧不会落到纸背上，而画在纸之前时，卷起来那截朝外
 * 的区域正好被圆柱远侧的纸投在身上，影就被盖掉 —— 读者判「阴影被卷筒截断」。
 *
 * 两条页边**各铺一条独立的带子**（不拼成一条会折返的折线，那会在纸角自交、被 NonZero 抵消出
 * 一个缺口），角外两段带子端头之间那一楔由 [drawCornerFan] 补上，层数与浓度与带子一致，
 * 拼起来是一整片，也不会在角上叠出更浓的一块。
 *
 * ## 纸背要让人看见
 *
 * 平铺段是「翻过去的那半页纸的反面」，它跟底下的旧页**同纸同色**。这条被判死过三次，
 * 最后定下来的做法是：
 *
 * 1. **筒要够粗**：[CURL3D_RADIUS_RATIO] 取 0.10 页宽。筒太细时掀起的纸一大半被压进几十个
 *    像素里，读者只看见一条白边，谈不上「反面」。
 * 2. **筒径整段不变**（[curlRadius]）：筒是同一根筒在滚，粗细不跟进度走。纸卷绕半圈要吃掉
 *    `πR` 的纸，所以**起手那一段**（掀起长度不足 `πR` 时）翻起来的纸整段贴在筒上、还没有
 *    平铺的纸背；过了这一段平铺段自己就出来了。早先为了让平铺段从第一下拖动就有，按
 *    `πR ≤ 掀起长度 × 0.7` 收细筒径 —— 代价是筒径十倍变化（读者判「直径会变、被压扁」），
 *    已退役：**筒径不变优先，纸背晚出现一会儿可以接受**。
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
    }

    // ⑤ 纸背的纸边落影：平铺段是一整页纸盖在旧页上，跟旧页同纸同色，不落影时读者
    //    只看见旧页的字被切断、切断处空无一物 —— 看起来就是「纸背没画」。
    //
    //    影带**沿页边自己的走向**铺（`pageEdges` 给的就是两条页边的折线，跨全部扫线行：
    //    贴着折痕那截是卷起来的花瓣弧、平铺段那截是直线），外沿始终跟着页边走。
    //    宽度**从最远的纸角往折痕端均匀线性收到 0**：角上满宽，一路匀速变细，不搞
    //    「先等宽、到某处忽然收窄」那种段落（原话「不能前面一段固定宽度，到某个位置忽然变细」）。
    //
    //    两条边**各铺一条独立带子**，只在纸角外那一楔由 [drawCornerFan] 补上（见 [drawBackEdgeShadow]）。
    //    **卷筒内侧那层暗影已退役**：它与沿边的影带不交融，读者判「三条影各画各的」。
    //    **只铺纸外（朝外）那一侧**：纸背上那半条（读者圈的「纸背右边那条灰带」）不再画；
    //    影带因此可以画在所有纸段之后，卷起来那截也不会被纸盖掉（读者判「被卷筒截断」）。
    val edges = mesh.pageEdges()
    // 影宽随「平铺段长度」渐入：纸背刚露出来（平铺段长度 = 0）时宽度是 0，
    // 随着翻页、平铺段长出来逐渐加到满宽。早先按 `progress` 给一个 0.4R 的起始宽度，
    // 纸背一出现就是一整条影，读者判「阴影突然出现」。
    val flatSpan = (mesh.maxArc() - PI.toFloat() * radius).coerceAtLeast(0f)
    val ramp = (flatSpan / (PI.toFloat() * radius)).coerceIn(0f, 1f)
    val backShadowWidth = radius * CURL3D_BACK_SHADOW_WIDTH * ramp
    val backShadowAlpha = DROP_SHADOW_ALPHA * CURL3D_BACK_SHADOW_ALPHA * shape.shadowFade

    // ⑥ 各段网格：纹理 → 纸背压淡 → 纸卷明暗
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
        fun drawBand(band: CurlMesh.Band) {
            if (band.indexCount <= 0) return
            val idx = List(band.indexCount) { mesh.indices[band.indexStart + it].toInt() }
            val ring = mesh.bandOutline(band)
            if (ring.size < 3) return
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
                // 筒身只有「反光」这一层明暗（往白里压），不给整段压暗：暗面已退役。
                drawBandShade(outline, mesh, band, radius, t0, up)
            }
        }
        // ⑥ 画序 = 各段网格（Recto → Verso → Back）→ 纸边落影。
        // 影只铺页边**朝外**那一侧（背离纸背），所以落在纸背上的那半条根本不存在；
        // 画在所有纸段之后，卷起来那一段的影才不会被卷筒盖掉（读者判「阴影被卷筒截断」）。
        for (band in mesh.bands) drawBand(band)
        val backRows = mesh.bands.firstOrNull { it.surface == CurlMesh.Surface.Back }?.let {
            it.firstRow..it.lastRow
        }
        if (edges != null && backRows != null) {
            drawBackEdgeShadow(edges, backRows, backShadowWidth, backShadowAlpha)
        }
    }
    return true
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
 * 纸背两条页边的落影：每条页边一条**只铺纸外一侧**的软带。
 *
 * 两条页边都是 `折痕端 → 最远的纸角`，**各铺一条**。两条边的末点重合（斜折痕切掉一个页角）时，
 * 两条带子各铺到纸角就停，角外那一楔两段带子都够不着 —— 那里补一块扇形（[drawCornerFan]），
 * 只补两段带子之间的那一楔，层数与浓度跟带子一致，拼起来是一整片。
 *
 * **只铺朝外那一侧**：判「整条影该往哪边铺」不能逐点看点乘 —— 纸卷成一圈后，同一块屏幕范围里
 * 叠着纸卷正反两面，重心、轮廓绕向、甚至页边法向在卷起来那截都会给出相反答案；页边斜时扫线
 * 与页边不垂直，纸角附近两者几乎平行，逐点判还会翻向。这里改成「朝外连续」：在**平铺纸背**
 * 那一段投票定下朝外，再沿折线传递（见 [outwardNormals]）。
 *
 * 影带**画在所有纸段之后**（见 [drawCurl3DFrame] 画序）：只铺朝外一侧不会落到纸背上，而卷起来
 * 那截的朝外区域正好被圆柱远侧的纸投在身上，画在纸之前就会被盖掉 —— 读者判「阴影被卷筒截断」。
 *
 * 收窄**全程匀速**（`w = maxWidth · t`，t 是到所在那条折痕端的归一化弧长距离），
 * 不分段落：任何一处突然变宽变窄都会被看成纸上有折痕。
 *
 * 软边用**分层叠**：第 k 层铺到 `maxWidth·k/K`，每层一个很低的常数浓度；越靠页边被叠中的
 * 层数越多、越淡的层数越少，于是叠出一条线性渐隐。为什么不用渐变刷子：影宽沿页边在变，
 * 一个线性渐变的等值线没法跟着弯，外沿会留一道能看见的硬边。
 *
 * @param edges 两条页边（都是 `折痕端 → 最远的纸角`）。
 * @param backRows 平铺纸背那一段的扫线行（`Back` 段）。**定朝外只在这里投票**：平铺段是
 *   一张平铺的纸，同一扫线上两个端点之间的连线一定穿过纸面；卷起来那截两端隔着筒壁，
 *   投影后还可能是反的。
 */
internal fun DrawScope.drawBackEdgeShadow(
    edges: List<List<Offset>>,
    backRows: IntRange,
    maxWidth: Float,
    alpha: Float,
) {
    if (maxWidth <= 0.5f || alpha <= 0f) return
    val first = edges[0]
    val second = edges[1]
    if (first.size < 2 || second.size < 2) return
    if (first.size != second.size) return
    val end1 = drawEdgeStrip(first, second, backRows, maxWidth, alpha)
    val end2 = drawEdgeStrip(second, first, backRows, maxWidth, alpha)
    // 两条页边的末点重合 = 折痕正好切掉一个页角：角外那一楔两段带子都够不着，补扇形。
    // 末点隔开一段时（自由边 = 与纸卷相切的切线，纸压着纸）没有纸角，不补。
    if (end1 == null || end2 == null) return
    val corner = first[first.size - 1]
    if ((corner - second[second.size - 1]).getDistance() > 0.5f) return
    drawCornerFan(corner, end1, end2, maxWidth, alpha)
}

/**
 * 单条页边：从折痕端（宽度 0）到最远的纸角（满宽）均匀加宽，**只铺朝外那一侧**。
 *
 * @param edge 这一条页边。
 * @param other 同一扫线行上的另一条页边（用来定朝外是哪一侧）。
 * @param backRows 平铺纸背那一段的扫线行（定朝外只在这一段投票）。
 * @return 这条边在纸角那一端的**朝外法向**（给 [drawCornerFan] 对缝）；算不出来时返回 `null`。
 */
private fun DrawScope.drawEdgeStrip(
    edge: List<Offset>,
    other: List<Offset>,
    backRows: IntRange,
    maxWidth: Float,
    alpha: Float,
): Offset? {
    val n = edge.size
    val tAt = FloatArray(n)
    var cum = 0f
    for (i in 1 until n) {
        cum += (edge[i] - edge[i - 1]).getDistance()
        tAt[i] = cum
    }
    if (cum < 1f) return null
    for (i in 0 until n) tAt[i] /= cum
    val outward = outwardNormals(edge, other, backRows) ?: return null
    drawShadowStrip(edge, tAt, outward, maxWidth, alpha)
    return outward[n - 1]
}

/**
 * 页边每一点的**朝外法向**（背离纸背）：先把折线每点的法向取出来，再让「朝外」沿着折线
 * 连续地传下去。
 *
 * **在哪一行定朝外**只能取平铺纸背那一段（[seedRows]）：那里同一扫线上的两个端点都在
 * 一张平铺的纸上，连线一定穿过纸面，「另一端在哪一侧」才可信。卷起来那截不行 —— 两端隔着
 * 筒壁，投影后甚至可能是反的；折痕端那一行更不行，它的「两端」是整条折痕（纸对角），
 * 据此定出来的朝外一旦沿着页边传到平铺段就会落在纸背上。
 *
 * 投票取整段的多数，不只看一行：某一行恰好退化（纸角附近扫线几乎与页边平行）时，
 * 单行会被一个几乎零投影的噪声定死。票数只看「另一端在法向的哪一侧」，与扫线有多斜无关。
 *
 * 定下之后不再逐点看点乘：朝外是一个**连续**的量，相邻两点的法向不可能突然掉头，所以只跟着
 * 上一点取同号。卷起来那截页边在屏幕上会掉头（圆柱投影不是单射），切线翻向，法向公式也跟着
 * 翻，正好靠这条连续性保持朝外不变。
 *
 * @param seedRows 平铺纸背那一段的扫线行。
 */
internal fun outwardNormals(
    edge: List<Offset>,
    other: List<Offset>,
    seedRows: IntRange,
): Array<Offset>? {
    val n = edge.size
    if (n < 2) return null
    val base = Array(n) { i ->
        val a = edge[maxOf(i - 1, 0)]
        val b = edge[minOf(i + 1, n - 1)]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) Offset.Zero else Offset(dy / len, -dx / len)
    }
    val lo = seedRows.first.coerceIn(0, n - 1)
    val hi = seedRows.last.coerceIn(lo, n - 1)
    var vote = 0.0
    for (i in lo..hi) {
        if (base[i] == Offset.Zero) continue
        val row = other[i] - edge[i]
        val len = hypot(row.x, row.y)
        if (len < 1e-3f) continue
        vote += (base[i].x * row.x + base[i].y * row.y) / len
    }
    // 起点取平铺段中间：两头的角点扫线会退化成一个点，法向不可用。
    var start = (lo + hi) / 2
    if (base[start] == Offset.Zero) {
        start = -1
        for (dx in 1..(hi - lo)) {
            val a = (lo + hi) / 2 + dx
            val b = (lo + hi) / 2 - dx
            if (a in lo..hi && base[a] != Offset.Zero) {
                start = a
                break
            }
            if (b in lo..hi && base[b] != Offset.Zero) {
                start = b
                break
            }
        }
    }
    if (start < 0) return null
    // `vote > 0` ⇒ 法向指向纸面（另一个端点那一侧）⇒ 朝外取反。
    val out = Array(n) { Offset.Zero }
    out[start] = if (vote > 0.0) -base[start] else base[start]
    // 朝外连续：只跟上一点的朝外法向取同向，不逐点看点乘。
    for (i in start + 1 until n) {
        val prev = out[i - 1]
        out[i] = when {
            base[i] == Offset.Zero -> prev
            base[i].x * prev.x + base[i].y * prev.y >= 0f -> base[i]
            else -> -base[i]
        }
    }
    for (i in start - 1 downTo 0) {
        val next = out[i + 1]
        out[i] = when {
            base[i] == Offset.Zero -> next
            base[i].x * next.x + base[i].y * next.y >= 0f -> base[i]
            else -> -base[i]
        }
    }
    return out
}

/**
 * 沿一条折线铺软带：**只铺朝外那一侧**到 `maxWidth · t`，分 [SHADOW_TAPER_LAYERS] 层叠出渐隐。
 *
 * @param pts 折线（屏幕坐标）。
 * @param tAt 每点的归一化宽度（0 = 无影，1 = 满宽）。
 * @param outward 每点的朝外单位法向（见 [outwardNormals]）。
 */
private fun DrawScope.drawShadowStrip(
    pts: List<Offset>,
    tAt: FloatArray,
    outward: Array<Offset>,
    maxWidth: Float,
    alpha: Float,
) {
    val n = pts.size
    if (n < 2) return
    // 每层常数浓度反解出来，叠满 K 层正好等于 alpha。
    val layerAlpha = (1.0 - (1.0 - alpha.toDouble()).pow(1.0 / SHADOW_TAPER_LAYERS)).toFloat()
    for (layer in 1..SHADOW_TAPER_LAYERS) {
        val scale = layer.toFloat() / SHADOW_TAPER_LAYERS
        val path = Path().apply { fillType = PathFillType.NonZero }
        for (i in 0 until n) {
            if (i == 0) path.moveTo(pts[i].x, pts[i].y) else path.lineTo(pts[i].x, pts[i].y)
        }
        for (i in n - 1 downTo 0) {
            val w = maxWidth * scale * tAt[i]
            path.lineTo(pts[i].x + outward[i].x * w, pts[i].y + outward[i].y * w)
        }
        path.close()
        drawPath(path, Color(0f, 0f, 0f, layerAlpha))
    }
}

/**
 * 纸角那 90° 的接触影：以纸角为心、影宽为半径的一块扇形，浓度从角上的满值径向淡到 0。
 *
 * 只铺两条页边时，每条影带都在「过纸角的垂线」处停住，两条带子之间就空出一个直角缺口 ——
 * 读者的原话是「直角两条边没有接上」。扇形的两条直边正好是这两条终止线，边上的浓度与影带
 * 一致（都是 `alpha · (1 − r / maxWidth)`），所以拼起来是一整片。
 *
 * **只铺两条影带之间那一楔**：早先整块 90° 扇形压在两条带子上面，角上叠了两层浓度，
 * 读者看到的是「一块明显的扇形痕迹」。现在只填两段带子端头之间的那道缝，
 * 宽度由远到近按层收，浓淡正好对平。
 *
 * @param corner 纸角。
 * @param n1 第一条页边在纸角处的朝外法向。
 * @param n2 第二条页边在纸角处的朝外法向。
 */
private fun DrawScope.drawCornerFan(
    corner: Offset,
    n1: Offset,
    n2: Offset,
    maxWidth: Float,
    alpha: Float,
) {
    if (maxWidth <= 0.5f || alpha <= 0f) return
    val a1 = atan2(n1.y, n1.x)
    val a2 = atan2(n2.y, n2.x)
    // 两道纸边在纸角上总是一个小于 180° 的夹角（页角的投影），缺口就是它们之间那一小段。
    var sweep = a2 - a1
    val twoPi = 2f * PI.toFloat()
    while (sweep > PI.toFloat()) sweep -= twoPi
    while (sweep < -PI.toFloat()) sweep += twoPi
    if (abs(sweep) < 1e-3f) return
    val layerAlpha = (1.0 - (1.0 - alpha.toDouble()).pow(1.0 / SHADOW_TAPER_LAYERS)).toFloat()
    for (layer in 1..SHADOW_TAPER_LAYERS) {
        val r = maxWidth * layer.toFloat() / SHADOW_TAPER_LAYERS
        val path = Path()
        path.moveTo(corner.x, corner.y)
        for (k in 0..SHADOW_FAN_STEPS) {
            val ang = a1 + sweep * k / SHADOW_FAN_STEPS
            path.lineTo(corner.x + cos(ang) * r, corner.y + sin(ang) * r)
        }
        path.close()
        drawPath(path, Color(0f, 0f, 0f, layerAlpha))
    }
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
 * 纸背两条页边落影的**满宽**（相对卷曲半径）与浓度（相对 [DROP_SHADOW_ALPHA]）。
 *
 * 纸背是**平铺**的、几乎贴着旧页，所以影是接触影而不是卷筒那道宽影（2.0R 那种宽影铺在
 * 平铺段上读起来就是「页面中间莫名其妙一道灰带」）。
 *
 * 这个宽度是**最远的纸角**处的宽度：沿页边往折痕端走按弧长匀速收到 0（见 [drawBackEdgeShadow]）。
 * 另外纸背刚出现时宽度从 0 渐入（按平铺段长度占半圈弧长 πR 的比例），不按进度给起始宽度
 * ——否则纸背一露头就是一整条影，读者判「阴影突然出现」。
 */
internal const val CURL3D_BACK_SHADOW_WIDTH: Float = 1.00f

/** 纸背落影的浓度（相对 [DROP_SHADOW_ALPHA]）。 */
internal const val CURL3D_BACK_SHADOW_ALPHA: Float = 0.62f

/** 沿页边那道影的分层数：每层铺到最大宽度的一个比例，叠出「边上深、往外淡」。 */
private const val SHADOW_TAPER_LAYERS: Int = 16

/** 纸角那块扇形影的弧段数（沿用旧版转角影的段数）。 */
private const val SHADOW_FAN_STEPS: Int = 12

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