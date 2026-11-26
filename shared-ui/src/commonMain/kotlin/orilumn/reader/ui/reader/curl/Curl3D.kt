package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import orilumn.reader.ui.reader.RasterTexture
import orilumn.reader.ui.reader.drawRasterCached
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
 * ## 两张纸的纹理各归各的
 *
 * 页栅格只有版心那么大、要放在版心位置；封面是烤好的**整页**图、贴页左上角。所以
 * 「纹理在页坐标里放哪儿」是**每张纸自己的事**（[frontTex] / [backTex]），不是整层共用一个数：
 * 共用只会让其中一张错位（正文的字整体横移一个页边距，或者封面被缩进版心）。
 * 覆盖层不受它影响：旁注与页码恒为整页大小、贴 (0,0)。
 *
 * @param frontTex 正面（被翻走那张）页栅格在页坐标里的左上角
 * @param backTex 露出页（折痕另一侧那张）页栅格在页坐标里的左上角
 * @param arriving 被翻的那张是不是**上一页正在落位**（应为回翻那一张）。只有这种收尾才把落影在
 *   自由边附近收掉（见 [freeEdgeShadowFade]）；翻下一页时折痕同样贴在自由边，但那是起手，
 *   影正该在那里。
 * @return `false` 表示这一帧没有网格可画，调用方退回折页。
 */
internal fun DrawScope.drawCurl3DFrame(
    shape: CurlShape,
    front: CurlPageImages,
    back: CurlPageImages,
    paperArgb: Int,
    frontTex: Offset,
    backTex: Offset,
    radiusRatio: Float = CURL3D_RADIUS_RATIO,
    arriving: Boolean = false,
): Boolean {
    val w = shape.pageW
    val h = shape.pageH
    val pageSize = Size(w, h)
    val paper = Color(paperArgb)

    // 进度到 0 及以下才回到「只画当前页」：0 与 1 这两端几何本身就是退化的（折痕竖直贴边、
    // 卷曲区面积为零），照常画出来与静止页逐像素一致，不需要额外的死区。
    //
    // 曾经的 1.1% / 0.2% 死区（旧版同口径）在斜折痕下会把一整块折角**一帧掐掉**：
    // 倾斜 0.8、进度 3% 时折角已经很大，而 1.1% 以下整块不画 ⇒ 手指退到起手点附近
    // 折角忽然消失，像那里有一条屏幕边线。
    //
    // **跟手时这条总闸不许看进度**：折痕由手指反解（[CurlShape.anchored]），进度只是累计
    // 位移，手指滑回起手点会先把它压到 0 以下，而折角还有半个页宽（见 [noLiftYet]）。
    if (shape.noLiftYet()) {
        drawPage(front, paper, frontTex.x, frontTex.y, pageSize)
        return true
    }
    // 已经翻完：只剩目标页。
    if (shape.fullyTurned()) {
        drawPage(back, paper, backTex.x, backTex.y, pageSize)
        return true
    }

    // ① 底图：**只给露出页那条 1px 收边垫底**。被翻走的那一张现在由 ⑥ 的三角带整页画出
    //    （见 [buildCurlMesh]：平铺半页与卷筒是同一条带子，折痕是内部扫线）；这里再整页铺
    //    一遍只是为了让露出页收进来的那 1px 有东西垫着，不然会露出背景。
    drawPage(front, paper, frontTex.x, frontTex.y, pageSize)
    val corners = revealCorners(shape)
    if (corners.size < 3) return true
    drawRevealPage(
        corners = insetFoldEdge(corners, shape, REVEAL_FOLD_INSET),
        images = back,
        paper = paper,
        texLeft = backTex.x,
        texTop = backTex.y,
    )

    // ③ 网格几何
    //
    // 整张纸只贴**一张不透明整页贴图**：页栅格、纸色与覆盖层（旁注 + 页码）先合成一张
    // 整页图（见 [CurlPageBake]）。分两遍画不行：覆盖层是透明底的，网格那条路径会把它的
    // 透明像素抬成白色，卷起来的纸整个刷白、只剩旁注 —— 读者报的「反面没有文字，只有批注」。
    //
    // 贴图先取（下面 ⑥ 才用它画）：网格贴的就是这张整页图，整张纸因此只有一种材料，
    // 不需要为「版心 | 页边距」那条边补扫线。
    val sheet = front.bake.textureOf(
        images = front,
        pageW = w,
        pageH = h,
        paperArgb = paperArgb,
        texLeft = frontTex.x,
        texTop = frontTex.y,
    )
    val radius = curlRadius(shape, radiusRatio)
    val mesh = buildCurlMesh(
        shape = shape,
        radius = radius,
        splits = CURL3D_SPLITS,
    )
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
    val shadowAlpha = DROP_SHADOW_ALPHA * (lift / (2f * radius)).coerceIn(0f, 1f) *
        shape.shadowFade * freeEdgeShadowFadeIfArriving(shape, radius, arriving)
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
    // 起点 = **纸背刚露头的那一刻**：页角处的纸在筒顶从「正面朝上」翻成「背面朝上」
    // （弧长 πR/2，网格里第一段 `Verso` 出现）。
    // 渐入规律照旧（同一个斜率），只把零点挪到这里：到平铺段出现（弧长 πR）是半宽，
    // 再走一个 πR 到满宽 —— 与原来同一把尺子，起点提前半个 πR。
    // 早先零点在 πR（纸背平铺段出现），影要等纸角跨过卷筒左边缘才看得见，读者判「太晚了」。
    val flatTurn = PI.toFloat() * radius
    val ramp = ((mesh.maxArc() - flatTurn * 0.5f) / flatTurn).coerceIn(0f, 1f)
    val backShadowWidth = radius * CURL3D_BACK_SHADOW_WIDTH * ramp
    val backShadowAlpha = DROP_SHADOW_ALPHA * CURL3D_BACK_SHADOW_ALPHA * shape.shadowFade
    // 影**收到哪儿为止**：卷筒的俯视图投影线（纸在筒顶从正面翻成反面的那条最外沿，弧长 πR/2）。
    // 宽度从纸角满宽、沿页边走一路收到这条线上正好 0；越过它再往折痕那侧的
    // 一小截（页边绕筒退回折痕的钩子）不画 —— 读者圈出来的「多余的一段」就是它。
    // 这只是影**结束**的地方，跟影什么时候开始画（下面的 `ramp`）无关，`ramp` 一字未改。
    val taperFrom = mesh.firstRowAtArc(flatTurn * 0.5f)

    // ⑥ 整张纸：**一条纹理带铺满整张纸 → 纸背压淡 → 整张纸明暗**（没有贴图时才垫纸色）
    //
    // 贴图现在是**整页**（页栅格 + 纸色 + 覆盖层烤成一张不透明图，见 `CurlPageBake`），
    // 整张纸只有一种材料。参考实现 `Mesh3D.drawiBook` 是一条连续的三角带：正面那趟画到
    // 「筒顶」那一行，纸背那趟从 `max(0, mVerticesCountFront - 2)` 起画，共用那一行由后画的
    // 整行盖住。它之所以分两趟，是要给纸背单独压一层 0.9 的纸色；我们那层压淡是另一条路径，
    // 所以纹理**一趟铺满整张纸**：正反面贴的是同一张整页图（纸背的字由几何自己镜像），
    // 中间没有任何段界，也就不存在「两条半覆盖边相叠」的缝。
    //
    // 以前纹理只铺版心、页边距靠纸色补，材料边两侧各画一次，交界那一列必然拿到
    // 「半列纸色 + 半列纹理」—— 那条边已经随整页贴图去掉了。
    //
    // 但「整页贴图」只解决了材料分层：**平铺半页仍然是另一次 drawPage**，网格从折痕那一行起。
    // 折痕于是成了两次绘制的交界，两个方向各自抗锯齿，正面与卷筒之间留下一条 1px 接缝
    // （GPU 光栅化特有，CPU/Skia 复现不出来）。现在 [buildCurlMesh] 把平铺半页也放进同一条
    // 三角带，折痕是带子的内部扫线 —— 与两个参考实现一致，交界本身不存在了。
    val canvas = drawContext.canvas
    // 纸边被卷起来时可以略微探出页面（斜折痕 + 抬起的纸），画布裁到页面内即可。
    clipRect(0f, 0f, w, h) {
        val pageTexture = sheet?.texture
        val pageImage = pageTexture?.image
        // 烤图恒为整页，偏移 (0,0)；仍按 [sheet] 取一遍，将来换回版心贴图也不会错位。
        val texLeft = sheet?.texLeft ?: 0f
        val texTop = sheet?.texTop ?: 0f
        val texWidth = pageImage?.width?.toFloat() ?: 0f
        val texHeight = pageImage?.height?.toFloat() ?: 0f

        val lastRow = mesh.bands.last().lastRow
        val sheetOutline = polygonPath(mesh.bandOutline(0, lastRow))

        // 纹理裁在**几何**里（见 [textureStrip]），不画布裁切：折痕一斜，按材料形状裁出来的
        // 路径就不是矩形，抗锯齿裁切会让 Skia 为它开一张全页大小的图层（平板上一帧多三十来毫秒）。
        val strip = pageImage?.let {
            mesh.textureStrip(0, lastRow, texLeft, texTop, texWidth, texHeight)
        }
        // 纸色垫底**只在没有纹理时**：整页图是不透明的，贴图自己就是整张纸。
        //
        // 有贴图还垫一层，贴图的抗锯齿边就压在纸色上，折痕那一列拿到「半列纸色 + 半列纹理」，
        // 比两侧都亮 —— 就是读者报的「卷筒与纸正面交界处一条接缝线」本身。测试
        // `foldHasNoPaperSeamLine` 量到过：折痕列 0.503、两侧 0.125。
        if (strip == null) {
            drawPath(sheetOutline, paper)
        } else {
            drawTextureStrip(
                canvas = canvas,
                strip = strip,
                paint = front.textures.pagePaint(pageTexture!!),
            )
        }

        // 纸背压淡：纸背内容留九成（参考实现 `BACKFACE_ALPHA = 0.9`）。从筒顶渐入
        // （见 [CURL_BACK_WASH_RAMP]）：界上浓度为零，这条路径的抗锯齿边不会把纸色混进
        // 正面内容、在筒顶留一条比两侧都亮的细线。
        val backRows = mesh.backFaceRows()
        if (backRows != null) {
            val washStart = t0 + up * radius
            drawHardEdgePath(
                polygonPath(mesh.bandOutline(backRows.first, backRows.last)),
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to paper.copy(alpha = 0f),
                        1f to paper.copy(alpha = CURL_BACK_WASH_ALPHA),
                    ),
                    start = washStart,
                    end = washStart - up * (radius * CURL_BACK_WASH_RAMP),
                ),
            )
        }

        // 明暗：**整张纸一条路径、一条渐变**（参考实现是逐顶点色，沿整条带连续取值）。
        // 只给「看得见的那一面」画，会在筒顶那条界上留下一道亮线：正面那半圈一点不压亮、
        // 纸背那半圈一上来就是最亮。整张纸一起取 `shadeAlpha(q)`，q 是屏幕偏移、两面同值。
        //
        // 渐变从**折痕那一行**起算（不是整条带子的第 0 行）：折痕之前是还没掀起的半页，
        // `q < 0` 一律零浓度。从第 0 行起算会把整页的宽度都算进停止点，卷筒那一段只分到
        // 几个停止点，明暗出现可见的色阶。
        if (CURL3D_SHADING) {
            val shadeFrom = mesh.bands.firstOrNull { it.surface == CurlMesh.Surface.Recto }?.firstRow ?: 0
            drawSheetShade(sheetOutline, mesh, shadeFrom, lastRow, radius, t0, up)
        }
        // 起点 = **纸背刚露头**：网格里已经有了 `Verso`（纸卷外圈那半段，纸背朝上）就画，
        // 不必等平铺段。朝外由材料坐标定（见 [outwardNormals]），不再按平铺段的弦投票。
        if (edges != null && taperFrom != null) {
            // 影宽的斜坡铺向**最远的纸角**那一头；折痕几乎竖直时，扫线从哪个页角出界会随
            // 倾斜的正负换一头（屏幕上下两端的宽度整段对调）。所以斜坡的陡度按倾斜渐变。
            drawBackEdgeShadow(
                mesh,
                shape,
                radius,
                edges,
                taperFrom,
                backShadowWidth,
                backShadowAlpha,
                shadowTaperBias(shape.lean, shape.shadowFade),
            )
        }
        }
    return true
}

/**
 * 纸卷贴近**自由边**时，落影再淡一次：折痕离自由边越近，影越淡，贴边时归零。
 *
 * ## 修的是「翻上一页收尾，右侧留白整条竖带闪一下」
 *
 * 折痕扫到自由边附近时，筒已经没剩多少纸可卷，几何上却仍是满径的筒：落影照旧按满宽铺出去，
 * 落在露出页仅剩的那条**留白**上。折痕一路扫到页边、影跟着扫过去再突然消失，读者看到的就是
 * 一条灰竖带闪一下。桌面结算 600ms（平板 300ms，且平板上手指多半已经带着走了大半页）、
 * 又总是从进度 0 整页扫一遍，所以只有桌面报得出来；平板偶尔也闪，只是短到来不及辨认。
 *
 * 与 [CurlShape.shadowFade] 分工：那个按**进度**淡（头尾 10%），管起手与收尾的阴影淡出
 * （倾斜另走 `settleLeanFade`，不共用这个系数）；
 * 这个按**折痕位置**淡，管「纸已经卷到页边、没有纸可卷了」。两者相乘。
 *
 * **它不是通用口径**：调用方只许经 [freeEdgeShadowFadeIfArriving] 用它，落在「自动播放的
 * 翻上一页收尾」上；翻下一页起手时折痕同样贴在自由边，那时影必须照画（见那个函数的 KDoc）。
 *
 * 中段（折痕离自由边还有 [CURL3D_FREE_EDGE_SHADOW_FADE] 个半径以上）恒为 1，落影深浅一分不动；
 * 只有最后这一段收掉 —— 正好是影会压进留白的那一段。收的时候按**平方**走（而不是直线）：
 * 影带本身只有几十像素宽，线性淡到一半时它还整条压在留白里，看着仍是一条灰带；平方让它
 * 在**刚进留白**的时候就已经很淡，贴边时正好归零。
 *
 * 距离的取法跟着几何走：`direction = -1` 是左右镜像几何，自由边在左缘，所以量的是 `foldX`；
 * 其余情况自由边在右缘，量的是 `pageW - foldX`。
 */
internal fun freeEdgeShadowFade(shape: CurlShape, radius: Float): Float {
    val freeGap = if (shape.direction >= 0) {
        shape.pageW - shape.foldX
    } else {
        shape.foldX
    }
    val span = (radius * CURL3D_FREE_EDGE_SHADOW_FADE).coerceAtLeast(1f)
    val k = (freeGap / span).coerceIn(0f, 1f)
    return k * k
}

/**
 * 上面那条系数**只准用在一种情形**：自动播放的「翻上一页」收尾（[arriving] 且非跟手）。
 *
 * ## 为什么必须限定，而不是「折痕贴边就淡」
 *
 * 折痕离自由边很近这件事，在两种翻法里都会发生，但含义正相反：
 * - **翻上一页收尾**：纸几乎盖平，筒没纸可卷，影却压在露出页仅剩的留白上，一路扫到页边再突然
 *   消失 —— 读者看到的是「闪过一条灰竖带」，影在这里确实是多余的；
 * - **翻下一页起手**：折痕本来就从自由边起步，筒刚离页，影正落在纸卷脚下的露出页上 ——
 *   那是整幅画面里唯一交代「纸抬起来了」的暗部，收掉就成了「折痕右侧没有阴影」。
 *
 * 只按位置淡分不开这两件事（同一组 `(pageW, foldX)` 两边完全一样），所以判据只能加在
 * **翻页方向 + 是否在跟手**上：跟手时影的浓淡一律交给几何（`curlShape` 的「跟手时阴影也不淡」），
 * 起手里的那段同样不许淡。
 */
internal fun freeEdgeShadowFadeIfArriving(shape: CurlShape, radius: Float, arriving: Boolean): Float =
    if (arriving && !shape.anchored) freeEdgeShadowFade(shape, radius) else 1f

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
 * 与页边不垂直，纸角附近两者几乎平行，逐点判还会翻向。朝外由**材料坐标**定（见 [outwardNormals]）：
 * 纸边本身是哪一条页边（左/右/上/下）在材料里是确定的，把它的外法向经网格的映射投到屏幕上，
 * 得到的就是「背离纸面」的那一侧。
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
 */
internal fun DrawScope.drawBackEdgeShadow(
    mesh: CurlMesh,
    shape: CurlShape,
    radius: Float,
    edges: List<List<Offset>>,
    taperFrom: Int,
    maxWidth: Float,
    alpha: Float,
    taperBias: Float = 1f,
) {
    if (maxWidth <= 0.5f || alpha <= 0f) return
    val first = edges[0]
    val second = edges[1]
    if (first.size < 2 || second.size < 2) return
    if (first.size != second.size) return
    val edge1 = sampleShadowEdge(mesh, shape, radius, 0, first, taperFrom) ?: return
    val edge2 = sampleShadowEdge(mesh, shape, radius, 1, second, taperFrom) ?: return
    val n = edge1.pts.size
    // 一条图层路径在整个影里复用（每层 reset 后重填）：一帧一个 `Path`，不再一帧几十个。
    val path = Path()
    // 三角带的顶点缓冲：两条边共用一段，逐层覆写（最宽那一层 2n 个顶点）。
    val corners = FloatArray(n * 4)
    val paint = Paint().apply { blendMode = BlendMode.SrcOver }
    drawShadowStrip(edge1, maxWidth, taperBias, alpha, path, corners, paint)
    drawShadowStrip(edge2, maxWidth, taperBias, alpha, path, corners, paint)
    // 两条页边的末点重合 = 折痕正好切掉一个页角：角外那一楔两段带子都够不着，补扇形。
    // 末点隔开一段时（自由边 = 与纸卷相切的切线，纸压着纸）没有纸角，不补。
    val corner = first[first.size - 1]
    if ((corner - second[second.size - 1]).getDistance() > 0.5f) return
    drawCornerFan(corner, edge1.outward.last(), edge2.outward.last(), maxWidth, alpha, path)
}

/**
 * 一条页边的影采样：点（**网格的每一行都留**）、归一化弧长、朝外法向。
 *
 * 早先为了省路径段数抽稀到二十几个点。影带的内沿就是纸边本身，抽稀后的折线会在两个
 * 采样点之间切进纸里（弦高两三个像素），影带就从纸边上啃出一排三角缺口 —— 页边看着
 * 就是「一段段」。影是软的，但内沿不能糊：这里把每一行都留着。
 */
private class ShadowEdge(
    val pts: List<Offset>,
    val tAt: FloatArray,
    val outward: Array<Offset>,
)

/**
 * 把一条页边采成 [ShadowEdge]；算不出来时返回 `null`。
 *
 * @param edgeIndex 0 / 1，这条页边是网格每行的第一个还是第二个端点（材料坐标按它取）。
 */
private fun sampleShadowEdge(
    mesh: CurlMesh,
    shape: CurlShape,
    radius: Float,
    edgeIndex: Int,
    edge: List<Offset>,
    fromRow: Int,
): ShadowEdge? {
    // 只留「收到哪儿为止」那一行及其之后：之前是绕筒退回折痕的钩子，不铺影。
    val from = fromRow.coerceIn(0, edge.size - 1)
    val n = edge.size - from
    if (n < 2) return null
    val pts = ArrayList<Offset>(n)
    for (i in from until edge.size) pts.add(edge[i])
    val tAt = FloatArray(n)
    var cum = 0f
    for (i in 1 until n) {
        cum += (pts[i] - pts[i - 1]).getDistance()
        tAt[i] = cum
    }
    if (cum < 1f) return null
    for (i in 0 until n) tAt[i] /= cum
    // 朝外由**材料坐标**定（见 [outwardNormals]），与影收到哪儿无关。
    val outwardAll = outwardNormals(mesh, shape, radius, edgeIndex, edge, from) ?: return null
    return ShadowEdge(pts, tAt, Array(n) { outwardAll[from + it] })
}

/**
 * 单条页边的软带：从折痕端（宽度 0）到最远的纸角（满宽）均匀加宽，**只铺朝外那一侧**。
 *
 * 十六层照旧叠色、浓度照旧（每层常数浓度反解出来，叠满 K 层正好等于 [alpha]），
 * 但**每层不再各填一条路径**：路径填充在 Skia 里走「按包围盒出覆盖率掩码」的抗锯齿
 * 栅格化，而这条带子又长又细、斜着横过页面，包围盒撑到大半页 —— 一帧三十几次这种填充
 * 是斜划掉帧的大头（见 `docs/卷曲3D实施计划.md` 第三节）。
 *
 * 现在的画法：
 * - **最内侧那一档**（`0 … maxWidth/K`，影最深的一段）仍用**路径**填：整条影只有它的
 *   内边压着纸边，必须是真的抗锯齿（三角带没有 AA，硬边会让影看起来「画在页边上」）。
 *   它与外侧只差一层浓度（≈ 0.02），交界处少一层抗锯齿也看不出来，而且只有一条路径。
 * - **其余各层**换成同样形状的三角带（`Vertices` + `TriangleStrip`）：顶点位置与路径
 *   一模一样，只是走普通三角形光栅化，不再出掩码。
 * - 浓度仍靠 `Paint.color`（顶点色一律全白）：真机上**可变顶点色不可靠**（见文件头），
 *   白顶点色 + 画笔色已经由纸面纹理那条路验证过。
 *
 * @param path 复用的图层路径（最内侧那一档）。
 * @param corners 复用的顶点缓冲（每层 `2n` 个顶点，逐层覆写）。
 * @param paint 复用的画笔（逐层只改浓度）。
 */
private fun DrawScope.drawShadowStrip(
    edge: ShadowEdge,
    maxWidth: Float,
    taperBias: Float,
    alpha: Float,
    path: Path,
    corners: FloatArray,
    paint: Paint,
) {
    fun profile(t: Float) = shadowWidthProfile(t, taperBias)
    val pts = edge.pts
    val n = pts.size
    if (n < 2) return
    val outward = edge.outward
    val tAt = edge.tAt
    val layerAlpha = (1.0 - (1.0 - alpha.toDouble()).pow(1.0 / SHADOW_TAPER_LAYERS)).toFloat()
    // 最内侧一档的宽度（第一层的宽度）：这一整段被 K 层盖住，浓度就是 alpha。
    val core = maxWidth / SHADOW_TAPER_LAYERS
    path.reset()
    path.fillType = PathFillType.NonZero
    for (i in 0 until n) {
        if (i == 0) path.moveTo(pts[i].x, pts[i].y) else path.lineTo(pts[i].x, pts[i].y)
    }
    for (i in n - 1 downTo 0) {
        val w = core * profile(tAt[i])
        path.lineTo(pts[i].x + outward[i].x * w, pts[i].y + outward[i].y * w)
    }
    path.close()
    drawPath(path, Color(0f, 0f, 0f, alpha))

    // 第 2 层到第 K 层：形状与逐层填路径完全一致，只是改走三角带。
    //
    // 浓度**不能**指望画笔色：没有着色器时真机只认顶点色（白 = 直接画成白的，整条影消失），
    // 桌面却拿画笔色 —— 两端不一致的坑。这里给画笔一张**纯色着色器**（黑色，常数），
    // 每层只改画笔 alpha，顶点色仍是全白，与纸面纹理那条路同一个组合。
    paint.shader = ShadowSolid.shader()
    paint.alpha = layerAlpha
    for (layer in 2..SHADOW_TAPER_LAYERS) {
        val scale = layer.toFloat() / SHADOW_TAPER_LAYERS
        for (i in 0 until n) {
            val inner = core * profile(tAt[i])
            val outer = maxWidth * scale * profile(tAt[i])
            val at = i * 4
            corners[at] = pts[i].x + outward[i].x * inner
            corners[at + 1] = pts[i].y + outward[i].y * inner
            corners[at + 2] = pts[i].x + outward[i].x * outer
            corners[at + 3] = pts[i].y + outward[i].y * outer
        }
        val vertices = Vertices(
            vertexMode = VertexMode.TriangleStrip,
            positions = FloatPairList(corners, 0, n * 2),
            // 没有着色器时纹理坐标不参与取样，但 Compose 要求长度与位置一致。
            textureCoordinates = ZeroCoords(n * 2),
            // 顶点色一律白色（真机上可变顶点色不可靠），浓度由画笔色给。
            colors = WhiteColorList(n * 2),
            indices = emptyList(),
        )
        drawIntoCanvas { it.drawVertices(vertices, BlendMode.SrcOver, paint) }
    }
}

/**
 * 影宽斜坡的陡度：0 = 两端一样宽（折痕基本竖直），1 = 纸角那头满宽、收尾那头收成 0。
 *
 * 折痕转到竖直时，扫线从哪个页角出界会随倾斜的正负换一头 —— 屏幕上端的宽度整段搬到下端。
 * 按倾斜插值就能把这次对调摊成一段渐变（见 [SHADOW_TAPER_TILT]）。
 *
 * @param lean 折痕倾斜量（已按方向镜像）。
 * @param fade 阴影淡出系数（[CurlShape.shadowFade]）。倾斜也可能被**另一个**起手扶正系数收小
 *   （跟手交接的翻页，见 `settleLeanFade`），所以 `lean / fade` 只在两者同口径（点按 / 程序
 *   翻页）时正好还原读者摆出的倾斜；跟手交接时它跟随实际折痕角度，折痕扶直、斜坡自然摊平。
 */
internal fun shadowTaperBias(lean: Float, fade: Float): Float {
    val tilt = if (fade > 1e-3f) lean / fade else 0f
    return (abs(tilt) / SHADOW_TAPER_TILT).coerceIn(0f, 1f)
}

/**
 * 页边某一点相对满宽的宽度比例。
 *
 * @param t 到收尾那一行（卷筒俯视图投影线）的归一化弧长：0 = 收尾处，1 = 最远的纸角。
 */
internal fun shadowWidthProfile(t: Float, taperBias: Float): Float = 1f - taperBias * (1f - t)

/**
 * 页边每一点的**朝外法向**（背离纸面）。
 *
 * ## 朝外由材料坐标定，不投票
 *
 * 每条页边都是页面矩形某一条边的投影（折痕斜时一条页边折线可能跨过页角、接上另一条边，
 * 但每一行落在哪条材料边上由 `texCoords` 直接给出）。材料边只有四条，外法向是常数：
 * 左 `(-1,0)`、右 `(1,0)`、上 `(0,-1)`、下 `(0,1)`。把它经网格的映射投到屏幕上：
 *
 * ```
 *   J·m = n·off'(x')·(m·n) + t·(m·t)
 * ```
 *
 * 其中 `n` 是折痕法向、`t` 沿折痕，`off'` 是卷曲偏移的导数（平面段 1、圆筒段 `cos(x'/R)`、
 * 绕满半圈后 `-1`）。影只从卷筒俯视图投影线（`x' = -πR/2`）之后开始画，那一整段 `off' ≤ 0`。
 *
 * 早先这里是「在平铺纸背那一段投票」。投票用的是同一扫线两端的连线方向，而纸角附近
 * 两条页边在屏幕上挤成一点、连线短到几像素，方向全是噪声：右下角折起时平铺段只有两三行、
 * 弦长全在阈值以下，票数为零、符号取默认值，整条**右侧竖页边**的影就翻到纸上，再拖一段、
 * 平铺段长出来又翻回去 —— 读者说的「竖着的页边阴影画到了错误的一侧，再拖又正常」正是它。
 * 材料坐标没有这个问题：它问的是「这一行在哪条页边上」，与弦长无关。
 *
 * ## 材料边逐行走，不把符号一竿子传到底
 *
 * 一条页边折线在纸角上会接上**另一条**材料边：右下角折起时，沿底边走的折线绕过页角后
 * 改沿右边走，两段的外法向差 90°。所以每一行取**它自己**那条材料边。页角那一行材料边
 * 不明确（两条边同时成立），沿用上一行的材料边 —— 影带先沿原边走到角上，角外那一楔
 * 交给扇形补（[drawCornerFan]）。
 *
 * 页边在屏幕上被压扁时（投影后几乎顺着法向），材料外法向与折线法向的夹角接近 90°，
 * 这一行的符号是噪声，也沿用上一行（见 [SHADOW_MATERIAL_MIN_COS]）。
 *
 * 材料页角上折线会在屏幕上**掉头**（圆柱投影不是单射，两条材料边在屏幕上折回来）。掉头那一行
 * 恰好就是材料边不明确的那一行，它沿用上一行的符号，`base` 又已经反向，所以 `out` 在那里会
 * 跳 180°。那一行是纸角本身、探出页面外，被画布裁掉，看不见（实测 1840×2800 与 400×600 的
 * 1322 个右下角跟手帧，跳变点全在页面外 18–90px）；看得见的每条材料边上，影带都连续。
 *
 * @param edgeIndex 0 / 1，这条页边是网格每行的第一个还是第二个端点。
 * @param fromRow 影从哪一行开始（卷筒俯视图投影线）；只在这一行之后找起始行。
 */
internal fun outwardNormals(
    mesh: CurlMesh,
    shape: CurlShape,
    radius: Float,
    edgeIndex: Int,
    edge: List<Offset>,
    fromRow: Int,
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
    val lo = fromRow.coerceIn(0, n - 1)
    // 折痕坐标系：与 [buildCurlMesh] 同一套。
    val pageH = shape.pageH
    val foldLen = hypot(shape.bottomX - shape.topX, pageH).coerceAtLeast(1e-3f)
    val tx = (shape.bottomX - shape.topX) / foldLen
    val ty = pageH / foldLen
    val nx = ty
    val ny = -tx
    val halfTurn = PI.toFloat() * radius
    val signAt = FloatArray(n) { i ->
        if (base[i] == Offset.Zero) {
            Float.NaN
        } else {
            materialOutwardSign(mesh, shape, radius, edgeIndex, i, base[i], nx, ny, tx, ty, halfTurn)
        }
    }
    // 起始行：影的范围内第一条材料边可靠的行；范围内一条都没有就往前找一条。
    var start = -1
    for (i in lo until n) if (!signAt[i].isNaN()) { start = i; break }
    if (start < 0) for (i in lo - 1 downTo 0) if (!signAt[i].isNaN()) { start = i; break }
    if (start < 0) return null
    val out = Array(n) { Offset.Zero }
    fun walk(from: Int, to: Int, step: Int, initial: Float, fallback: (Int) -> Offset) {
        var sign = initial
        var i = from
        while (i != to) {
            if (base[i] == Offset.Zero) {
                out[i] = fallback(i)
            } else {
                val s = signAt[i]
                if (!s.isNaN()) sign = s
                out[i] = base[i] * sign
            }
            i += step
        }
    }
    walk(start, n, 1, signAt[start]) { out[it - 1] }
    walk(start - 1, -1, -1, signAt[start]) { out[it + 1] }
    return out
}

/**
 * 某一行的材料外法向投到屏幕上之后，与折线法向 [base] 同号还是反号；不可靠时返回 NaN。
 *
 * 页角上两条材料边同时成立，外法向差 90°，那里交给纸角扇形去补，不在这里取。
 * 页边在屏幕上压扁到几乎顺着法向时，两者的夹角接近 90°、符号是噪声，也不在这里取。
 */
private fun materialOutwardSign(
    mesh: CurlMesh,
    shape: CurlShape,
    radius: Float,
    edgeIndex: Int,
    row: Int,
    base: Offset,
    nx: Float,
    ny: Float,
    tx: Float,
    ty: Float,
    halfTurn: Float,
): Float {
    val eps = 0.75f
    val u = mesh.texCoords[row * 4 + edgeIndex * 2]
    val v = mesh.texCoords[row * 4 + edgeIndex * 2 + 1]
    val onLeft = u <= eps
    val onRight = u >= shape.pageW - eps
    val onTop = v <= eps
    val onBottom = v >= shape.pageH - eps
    val horizontal = if (onLeft || onRight) 1 else 0
    val vertical = if (onTop || onBottom) 1 else 0
    if (horizontal + vertical != 1) return Float.NaN
    val mx = when {
        onLeft -> -1f
        onRight -> 1f
        else -> 0f
    }
    val my = when {
        onTop -> -1f
        onBottom -> 1f
        else -> 0f
    }
    val xArc = -mesh.arc[row * 2]
    val off = when {
        xArc >= 0f -> 1f
        xArc >= -halfTurn -> cos(xArc / radius)
        else -> -1f
    }
    val alongN = mx * nx + my * ny
    val alongT = mx * tx + my * ty
    val sx = off * alongN * nx + alongT * tx
    val sy = off * alongN * ny + alongT * ty
    val len = hypot(sx, sy)
    if (len < 1e-3f) return Float.NaN
    val cos = (base.x * sx + base.y * sy) / len
    if (abs(cos) < SHADOW_MATERIAL_MIN_COS) return Float.NaN
    return if (cos > 0f) 1f else -1f
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
    path: Path,
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
    val color = Color(0f, 0f, 0f, layerAlpha)
    for (layer in 1..SHADOW_TAPER_LAYERS) {
        val r = maxWidth * layer.toFloat() / SHADOW_TAPER_LAYERS
        path.reset()
        path.moveTo(corner.x, corner.y)
        for (k in 0..SHADOW_FAN_STEPS) {
            val ang = a1 + sweep * k / SHADOW_FAN_STEPS
            path.lineTo(corner.x + cos(ang) * r, corner.y + sin(ang) * r)
        }
        path.close()
        drawPath(path, color)
    }
}

/**
 * 整张纸的明暗：沿 `up` 方向的一条渐变，取值照参考实现的顶点色公式。
 *
 * 一次画满 `firstRow..lastRow`（生产上就是整张纸）：明暗只跟屏幕偏移 `q` 有关，两面同值，
 * 所以一整条渐变本身就是连续的；分成两段画时，正面那半圈不压亮、纸背那半圈压到最亮，
 * 筒顶那条段界上就是一道亮线。 */
private fun DrawScope.drawSheetShade(
    outline: Path,
    mesh: CurlMesh,
    firstRow: Int,
    lastRow: Int,
    radius: Float,
    origin: Offset,
    up: Offset,
) {
    var u0 = Float.MAX_VALUE
    var u1 = -Float.MAX_VALUE
    for (row in firstRow..lastRow) {
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
    drawHardEdgePath(
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

/**
 * 折痕贴近自由边时落影的淡出距离（相对卷曲半径）。
 *
 * 2.5R 是「影宽 + 留白」的量级：折痕离自由边还剩 2.5 个半径时影全浓，贴边时归零（见
 * [freeEdgeShadowFade]）。取小了影带仍会压进留白，取大了中段落影就没了。
 */
private const val CURL3D_FREE_EDGE_SHADOW_FADE: Float = 2.5f

/** 明暗渐变的分段数（一维函数的采样密度，够到看不出折线）。 */
private const val ROLL_SHADE_STOPS: Int = 16

/** 页面纹理的画笔缓存：一张图的**这一版像素**只建一次 `ImageShader`（见 [CurlTextureCache]）。 */
internal class CurlTextureCache {
    private var pageTexture: RasterTexture? = null
    private var pagePaint: Paint? = null
    private var overlayTexture: RasterTexture? = null
    private var overlayPaint: Paint? = null

    /** 累计真正重建过几次 `ImageShader`（诊断用，见 [CurlPerf]）。 */
    internal var rebuilds: Int = 0
        private set

    /** 正文页栅格的画笔（`ImageShader` 绑在这张图的这一版上）。图/代数换了才重建。 */
    fun pagePaint(texture: RasterTexture): Paint = paintFor(texture, overlay = false)

    /** 覆盖层（旁注 + 页码）的画笔。 */
    fun overlayPaint(texture: RasterTexture): Paint = paintFor(texture, overlay = true)

    private fun paintFor(texture: RasterTexture, overlay: Boolean): Paint {
        val cached = if (overlay) overlayTexture else pageTexture
        val paint = if (overlay) overlayPaint else pagePaint
        if (paint != null && cached != null &&
            cached.image === texture.image && cached.generation == texture.generation
        ) {
            return paint
        }
        val fresh = newMeshPaint(texture.image)
        rebuilds++
        if (overlay) {
            overlayTexture = texture
            overlayPaint = fresh
        } else {
            pageTexture = texture
            pagePaint = fresh
        }
        return fresh
    }
}

/**
 * 用**不抗锯齿**的画笔填一条路径：与网格纹理带的硬边保持一致（见 [newMeshPaint]）。
 *
 * 这些路径的边与纸的轮廓是同一条线；两侧都抗锯齿时，两个半覆盖叠在一起，交界处会
 * 留下一条比两边都暗（或都亮）的发丝线。硬边对硬边则严格互补，没有半覆盖可言。
 */
private fun DrawScope.drawHardEdgePath(path: Path, brush: Brush) {
    drawIntoCanvas { canvas ->
        val hard = Paint().apply {
            isAntiAlias = false
            shader = (brush as ShaderBrush).createShader(size)
        }
        canvas.drawPath(path, hard)
    }
}

/** 网格纹理画笔：`ImageShader` 建的时候就把像素拷成快照，所以画笔只跟**图的这一版**有关，跟几何无关。 */
private fun newMeshPaint(image: ImageBitmap): Paint = Paint().apply {
    // 纹理**只在版心材料范围内**绘制，越界像素靠 `Clamp` 兜底不会跨页抹开：
    // 几何已经裁到版心边上（见 [CurlMesh.textureStrip]），采样走不到页边距里。
    // 早先按材料形状 `clipPath` 裁纹理：效果一样，但折痕一斜那条裁剪路径就不是矩形，
    // 抗锯齿裁切会给每帧多开一张全页大小的图层（见 [drawRevealPage] 的 KDoc）。
    shader = ImageShader(image, TileMode.Clamp, TileMode.Clamp)
    // 纸卷把材料压进不到一个半径的屏幕宽度，最邻近采样会把文字抽成锯齿斜条。
    filterQuality = FilterQuality.Low
    // **硬边**：参考实现跑在 GL 上，纸的轮廓天生不抗锯齿。Skia 默认给 drawVertices
    // 抗锯齿，轮廓那一像素只有半覆盖，压在它下面的落影就从这半个像素里透出来，
    // 正脸与卷筒交界处留下一条 1px 暗线（真机实测：该像素比两侧低 40 级）。
    isAntiAlias = false
    blendMode = BlendMode.SrcOver
}

/**
 * 把两个 `FloatArray` 里的一段 `(x, y)` 对看成 `List<Offset>`（**逐帧零装箱**）。
 *
 * `Vertices` 要的是 `List<Offset>`，而网格本来就存在 `FloatArray` 里。用 `List(n) { Offset(...) }`
 * 转换时每帧要造上千个 `Offset` 对象（一帧六次纹理绘制就是几千个），全都在绘制热路径上。
 * 这个视图只在 `Vertices` 复制顶点时按索引取值，取到的 `Offset` 是临时值，不留存。
 *
 * @param fromVertex/toVertex 顶点下标区间（`data` 每顶点两个 float）。
 * @param offsetX/offsetY 纹理坐标要减掉的版心落点（`ImageShader` 没有局部矩阵）。
 */
private class FloatPairList(
    private val data: FloatArray,
    private val fromVertex: Int,
    private val toVertex: Int,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f,
) : AbstractList<Offset>() {
    override val size: Int get() = toVertex - fromVertex

    override fun get(index: Int): Offset {
        val v = (fromVertex + index) * 2
        return Offset(data[v] + offsetX, data[v + 1] + offsetY)
    }
}

/** 全白顶点色（Compose 要求颜色数与顶点数等长；同色不装箱）。 */
private class WhiteColorList(override val size: Int) : AbstractList<Color>() {
    override fun get(index: Int): Color = Color.White
}

/**
 * 全零纹理坐标（Compose 要求纹理坐标与顶点数等长）。
 *
 * 没有着色器时纹理坐标不参与取样（页边影只要纯色三角带），但长度必须对得上。
 */
private class ZeroCoords(override val size: Int) : AbstractList<Offset>() {
    override fun get(index: Int): Offset = Offset.Zero
}

/**
 * 页边影三角带用的**纯色着色器**（黑，不透明）。
 *
 * 顶点色只能是白色（真机上可变顶点色不可靠），而画笔色在 `drawVertices` 上没有着色器时
 * 两端行为不一致：桌面按画笔色画、真机只认顶点色（白）。给它一张常数着色器就回到
 * 「着色器 × 顶点色」这个两端都认的语义，浓度靠 `Paint.alpha` 逐层乘。
 *
 * 着色器只跟颜色有关，建一次全局复用；渐变两端同色，任意取样点都是纯黑。
 */
private object ShadowSolid {
    private var cached: Shader? = null

    fun shader(): Shader {
        val hit = cached
        if (hit != null) return hit
        val img = ImageBitmap(1, 1, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(img),
            size = Size(1f, 1f),
        ) {
            drawRect(Color.Black)
        }
        val fresh = ImageShader(img, TileMode.Clamp, TileMode.Clamp)
        cached = fresh
        return fresh
    }
}

/**
 * 用一段**已裁到版心内的**三角带画页栅格（见 [CurlTextureStrip]）。
 *
 * 顶点本来就只覆盖版心材料，`Clamp` 采样不会跨页抹开，所以不需要任何画布裁切。
 */
private fun DrawScope.drawTextureStrip(
    canvas: androidx.compose.ui.graphics.Canvas,
    strip: CurlTextureStrip,
    paint: Paint,
) {
    val n = strip.vertexCount
    if (n < 6) return
    val vertices = Vertices(
        vertexMode = VertexMode.TriangleStrip,
        positions = FloatPairList(strip.positions, 0, n),
        textureCoordinates = FloatPairList(strip.texCoords, 0, n),
        colors = WhiteColorList(n),
        // 空索引 = 按顺序画这条三角带；行与行在数组里本来就是连续的。
        indices = emptyList(),
    )
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
    images.page?.let { drawRasterCached(images.pageRaster, it, Offset(texLeft, texTop), alpha) }
    images.overlay?.let {
        drawRasterCached(images.overlayRaster, it, Offset.Zero, alpha)
    }
}

/**
 * 露出页贴折痕那条边往里收多少像素（见 [insetFoldEdge]）。
 *
 * 1px 就够：抗锯齿的半覆盖只跨一列。收多了会在折痕内侧留一条正面页的颜色（网格没盖到时
 * 看得出来）。
 */
private const val REVEAL_FOLD_INSET: Float = 1f

/**
 * 露出页贴着折痕的那条边**往露出侧收** [REVEAL_FOLD_INSET] 像素。
 *
 * 露出页是**垫在纸底下**的一张纸：折痕这一列本来就归翻走的那一页（`drawPage(front)` 已经
 * 整页铺过一遍，网格第一行再盖一次）。露出页却也从折痕起笔，它自己的抗锯齿边于是有半列
 * 压在折痕上，把「新页那半列颜色」混进正面内容里 —— 深色页面上看是一条白缝、浅色页面上
 * 看是一条灰缝，与折痕平行、一直在（读者原话「纸正面与卷筒左侧的接缝」）。纸色、页图、
 * 覆盖层三层都有这条边，所以收边要收**整个多边形**，不能只收纸色那一层。
 *
 * 收进来的那半像素交给正面纸自己填；网格第一行盖在上面，两处内容本来就同源，看不出接缝。
 */
private fun insetFoldEdge(corners: List<Offset>, shape: CurlShape, inset: Float): List<Offset> {
    if (corners.size < 3 || inset <= 0f) return corners
    val top = Offset(shape.topX, 0f)
    val bottom = Offset(shape.bottomX, shape.pageH)
    val dx = bottom.x - top.x
    val dy = bottom.y - top.y
    val len = hypot(dx, dy)
    if (len < 1e-3f) return corners
    // 折痕单位法向，取指向露出页那一侧（多边形重心在那一侧）的那一支。
    var nx = dy / len
    var ny = -dx / len
    var cx = 0f
    var cy = 0f
    for (p in corners) {
        cx += p.x
        cy += p.y
    }
    cx /= corners.size
    cy /= corners.size
    if (nx * (cx - top.x) + ny * (cy - top.y) < 0f) {
        nx = -nx
        ny = -ny
    }
    // 落在折痕上的顶点才挪（端点可能被页边截出来一个重复点，两个一起挪）。
    val tol = inset * 2f
    return List(corners.size) { i ->
        val p = corners[i]
        val d = (p.x - top.x) * nx + (p.y - top.y) * ny
        if (d > -tol && d < tol) Offset(p.x + nx * inset, p.y + ny * inset) else p
    }
}

/**
 * 露出页：按折痕那一侧的四边形**填纸色 + 页图 shader**，不走画布裁切。
 *
 * 与 [drawPage] 的唯一区别是「形状」：这里只填折痕外侧那一块，不是整页。
 *
 * ## 为什么不用 `clipPath { drawPage(...) }`
 *
 * 折痕竖直时这块四边形正好是矩形（轴对齐裁切，Skia 走裁剪矩形快路，几乎不要钱）；
 * 折痕一斜它就是个梯形，**非矩形抗锯齿裁切会让 Skia 为它开一张全页大小的图层**
 *（页宽 × 页高 × 4 字节，平板上二十多兆），一帧多出三十来毫秒。
 * 读者手指横划时倾斜总不为零（`curlLean`），实测帧率从 60fps 掉到 20fps；
 * 脚本的直线滑动倾斜恒 0，所以一直是 60fps——「手拖才卡、脚本不卡」的真身就在这里。
 *
 * 换成「形状当路径、页图当 shader」后填的还是同一块面积，但只有一次路径填充、没有图层。
 *
 * ## 纸色打底只补版心之外，折痕那条边再往里收一像素
 *
 * 这块四边形的**最后一条边就是折痕**（`revealCorners` 的末点接首点）。整块先铺一层纸色时，
 * 纸色自己的抗锯齿边正好压在折痕上，叠上网格第一行（也是半覆盖）就多出一条比两侧都亮的
 * 接缝线（读者原话「卷筒与纸正面交界处有一条多余的接缝线」的正面对应那条）。
 * 所以纸色只铺在页栅格之外的四条边（各裁一个轴对齐矩形），版心那一块由页图自己铺满。
 * 这里能用轴对齐裁切，是因为露出页是**平的**、屏幕坐标就是材料坐标；网格那几段在屏幕上
 * 卷了起来，同样的裁法在那里不成立（见 `drawBand`）。
 *
 * 但只收纸色不够：页图 shader 与覆盖层也是从折痕起笔，它们自己的抗锯齿边同样有半列压在
 * 折痕上（露出页在折痕附近是纸色时最明显 —— 半列纸色混进正面内容，深色页面上是白缝、
 * 浅色页面上是灰缝）。所以**整个多边形**都往里收（见 [insetFoldEdge]）。
 */
private fun DrawScope.drawRevealPage(
    corners: List<Offset>,
    images: CurlPageImages,
    paper: Color,
    texLeft: Float,
    texTop: Float,
) {
    val path = polygonPath(corners)
    val pageTexture = images.page
    val pageImage = pageTexture?.image
    if (pageImage == null) {
        drawPath(path, paper)
    } else {
        // 纸色打底：版心之外是纸 —— 底下那张正面页的字不能从新页的页边距里透出来。
        // 但纸色**只垫在页栅格之外**（理由见上面的 KDoc）：版心那一块页图自己会铺满。
        // 四条边分开裁，裁的都是轴对齐矩形，Skia 走裁剪矩形快路，不开图层。
        val texRight = texLeft + pageImage.width
        val texBottom = texTop + pageImage.height
        val pageW = size.width
        val pageH = size.height
        if (texTop > 0f) clipRect(0f, 0f, pageW, texTop) { drawPath(path, paper) }
        if (texBottom < pageH) clipRect(0f, texBottom, pageW, pageH) { drawPath(path, paper) }
        if (texLeft > 0f) clipRect(0f, texTop, texLeft, texBottom) { drawPath(path, paper) }
        if (texRight < pageW) clipRect(texRight, texTop, pageW, texBottom) { drawPath(path, paper) }
    }
    if (pageImage != null) {
        // 页栅格只有版心那么大：把梯形裁到材料矩形再填 shader，页边距里就不会出现
        // 「版心最边一列被拉成一条横线」（`Clamp` 越界采样）。
        val texPts = clipPolygonToRect(
            pts = corners,
            left = texLeft,
            top = texTop,
            right = texLeft + pageImage.width,
            bottom = texTop + pageImage.height,
        )
        if (texPts.size >= 3) {
            // 路径按**页栅格自己的坐标**画（平移到 (texLeft, texTop) 的画布），
            // shader 的原点才对得上图 (0,0)。
            val local = List(texPts.size) { Offset(texPts[it].x - texLeft, texPts[it].y - texTop) }
            withTransform({ translate(texLeft, texTop) }) {
                drawIntoCanvas { it.drawPath(polygonPath(local), images.textures.pagePaint(pageTexture!!)) }
            }
        }
    }
    // 覆盖层是整页图（旁注 + 页码），贴 (0,0)，不用平移也不用裁。
    images.overlay?.let { overlay ->
        drawIntoCanvas { it.drawPath(path, images.textures.overlayPaint(overlay)) }
    }
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
 * 整体的宽度从 **纸背刚露头那一刻**（页角处的纸在筒顶翻成背面朝上，弧长 πR/2）开始渐入，
 * 到平铺段出现（弧长 πR）半宽、再走一个 πR 满宽；早先零点在 πR，影要等纸角跨过卷筒左边缘
 * 才看得见（读者判「太晚了」）。
 */
internal const val CURL3D_BACK_SHADOW_WIDTH: Float = 1.00f

/** 纸背落影的浓度（相对 [DROP_SHADOW_ALPHA]）。 */
internal const val CURL3D_BACK_SHADOW_ALPHA: Float = 0.62f

/** 沿页边那道影的分层数：每层铺到最大宽度的一个比例，叠出「边上深、往外淡」。 */
private const val SHADOW_TAPER_LAYERS: Int = 16

/** 纸角那块扇形影的弧段数（沿用旧版转角影的段数）。 */
private const val SHADOW_FAN_STEPS: Int = 12

/**
 * 材料外法向与折线法向的**最小夹角余弦**：低于它这一行的符号算噪声，沿用上一行。
 *
 * 页边在屏幕上被压扁（投影后几乎顺着法向）时，投影出来的外法向与折线法向接近垂直，
 * 点乘在 0 附近，倾斜千分之一就换号。这类行不取自己的符号，靠前后行把它连起来。
 */
private const val SHADOW_MATERIAL_MIN_COS: Float = 0.2f

/**
 * 影宽那把斜坡「按倾斜渐变」的倾斜范围（0 = 竖直，1 = 斜到极限）。
 *
 * 影宽是**从最远的纸角往卷筒那头匀速收到 0**的。可折痕一转到竖直，扫线从哪个页角出界
 * 就换了一头：屏幕上端的宽度整段搬到下端、下端搬到上端，来回摇摆时看着就是「两头的宽度
 * 突然切换」。所以斜坡的陡度按倾斜插值：竖直（以及这个范围以内）两头一样宽，斜出去才慢慢
 * 收成一头满宽、一头为 0。范围取这么小是因为它只该盖住「基本竖直」那一小段 —— 这段之外的
 * 斜坡本来就是对的。
 */
private const val SHADOW_TAPER_TILT: Float = 0.06f

/** 参考实现的压力值（`pageScroll` 点按翻页用 200/255）。 */
internal const val CURL3D_REFERENCE_PRESSURE: Float = 0.7843f

/** 参考实现在这个压力下的半径上限（≈ 0.108 页宽）：只作对照，本仓不取这个值。 */
internal const val CURL3D_REFERENCE_RADIUS_RATIO: Float =
    0.5f * (1f - CURL3D_REFERENCE_PRESSURE)

/** 圆柱横截面分段数。 */
internal const val CURL3D_SPLITS: Int = 40