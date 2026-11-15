package orilumn.reader.ui.reader.curl

import kotlin.math.hypot

/**
 * 一帧卷曲几何（**用户层纯函数**，页坐标系：原点左上、y 向下、单位 px）。
 *
 * 只描述「折痕在哪、纸往哪边翻、阴影多重」，不含任何平台类型；桌面与 Android 各自把
 * 它翻译成着色器 uniform（见 [uniforms]）。手感参数全部继承自旧 2D 版 `CurlView.kt`：
 * 折痕从页边扫到对侧、倾斜量 0.9 页宽、折痕末端在接近书脊时扶正。
 *
 * 几何（与 `CurlView.drawBody` 同一套折痕，截面换成实物翻页的刚性卷）：
 * ```
 *   折痕 = 过 top 与 bottom 的直线，方向 a = normalize(bottom - top)
 *   法向 n ⟂ a，**恒指纸的右缘**（两个方向同布局，与旧版一致）
 *   s = 片段到折痕的带符号距离（沿 n）
 *   s ≥ 0        → 露出页（目标页），最靠折痕的 R 宽是纸卷的投影
 *   s < 0        → 纸背平铺段（被翻那页的镜像，1:1 刚性），再往外是还没掀起的正面页
 *   R = 纸卷半径（0.10 页宽，且 ≤ 0.30·D），arc = πR
 *   D = 折痕中点沿 n 到页边的距离（整帧一个值，只用来定半径）
 * ```
 *
 * 纸的范围由**页矩形**决定，不由 D 决定：每个像素算出它的材质点，落在页矩形里就有纸。
 * 于是纸背的外缘是页边镜像、折痕两端自然收成纸的四个角（harism 的网格也是这么裁的，
 * 旧 2D 版把「露出区多边形」整体镜像也是同一个形状）。用 D 画等宽带子会把两端切成直角。
 *
 * 为什么纸卷在**露出页这一侧**：纸是从右半边掀起来、绕过折痕落到左半边的，
 * 所以半圆柱必然长在纸料那一侧，平铺段落在另一侧。旧版把卷画在平铺段那一侧，
 * 等于让纸往页面里折，是画不出来的姿态。
 *
 * 为什么平铺段必须 1:1：纸不可拉伸。旧版把剩余纸料压进屏幕带宽（斜率 < 1），
 * 纸背被放大约 1.45 倍——这是实测报上来的比例问题，见 [CURL_SKSL] 文件头。
 */
data class CurlFrame(
    val pageW: Float,
    val pageH: Float,
    /** 折痕与页上边（y = 0）的交点。 */
    val topX: Float,
    val topY: Float = 0f,
    /** 折痕与页下边（y = H）的交点。 */
    val bottomX: Float,
    val bottomY: Float,
    /** 折痕方向的单位向量（自上而下）。 */
    val dirX: Float,
    val dirY: Float,
    /** 法向单位向量，指向露出页一侧。 */
    val normalX: Float,
    val normalY: Float,
    /** 阴影强度 0..1（靠近书脊时淡出）。 */
    val shadow: Float,
    /** 归一化进度（0 = 未翻，1 = 翻完）。 */
    val progress: Float,
    /** +1 = 向后翻（下一页），-1 = 向前翻（上一页）。 */
    val direction: Int,
    /** 纸背底色（ARGB）。 */
    val paperArgb: Int,
) {
    /**
     * 着色器 uniform：五个 `float4`（顺序与 `CurlShader` 的声明严格一致）。
     *
     * 全部用 float4 是为了绕开 uniform 对齐规则（float2/float 混排时要自己补 padding，
     * 补错不会编译失败，只会把阴影强度当成坐标——这类错极难查）。多传几个零的代价可忽略。
     *
     * @param overlay true = 覆盖层那一遍（旁注 + 页码）：纸背与纸卷返回透明。
     * @param texX/texY/texW/texH 纹理在页坐标里的位置与尺寸（页栅格是版心、覆盖层是整页）。
     */
    fun uniforms(
        overlay: Boolean = false,
        texX: Float = 0f,
        texY: Float = 0f,
        texW: Float = pageW,
        texH: Float = pageH,
    ): FloatArray = floatArrayOf(
        pageW, pageH, if (overlay) 1f else 0f, 0f,
        texX, texY, texW, texH,
        topX, topY, dirX, dirY,
        normalX, normalY, shadow, 0f,
        ((paperArgb shr 16) and 0xFF) / 255f,
        ((paperArgb shr 8) and 0xFF) / 255f,
        (paperArgb and 0xFF) / 255f,
        ((paperArgb ushr 24) and 0xFF) / 255f,
    )
}

/**
 * 由进度算一帧几何。
 *
 * @param direction +1 向后翻 / -1 向前翻（来自 [orilumn.reader.ui.reader.FlipSession.direction]）
 * @param tilt 手指位移归一化后的上下分量（-1..1），0 = 竖折痕；旧版 `CurlView.frozenTilt`
 * @param anchorX 手指在页坐标系的 x（仅向后翻时跟随；null = 不跟随，按进度算折痕）
 * @param anchorY 手指在页坐标系的 y
 */
fun curlFrame(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
    anchorX: Float? = null,
    anchorY: Float? = null,
    paperArgb: Int = 0xFFFFFFFF.toInt(),
): CurlFrame {
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val forward = direction >= 0

    // 折痕位置：向后翻从右缘扫到左缘，向前翻从左缘扫到右缘（旧版 curl 同款）；
    // 允许越界（p<0 / p>1）——那是「翻回去 / 翻过头」的两端，折痕整体滑出页外。
    var foldX = if (forward) w * (1f - progress.coerceIn(-0.9f, 1f))
    else w * progress.coerceIn(0f, 1.9f)
    // 倾斜：折痕上下端各外扩 0.9 页宽；向前翻镜像（否则折痕与滑动方向平行，看不出在翻）。
    val lean = if (forward) tilt.coerceIn(-1f, 1f) else -tilt.coerceIn(-1f, 1f)
    val tiltW = w * 0.9f

    // 跟手锚定（仅向后翻）：折过去的纸边（折痕的镜像）恰好过手指。
    //
    // 注意：纸卷吃掉 πR 的纸料，所以**可见**的纸边比这个镜像近 πR（见 [CURL_SKSL]）。
    // 目前卷曲层按进度算折痕、不传 anchor（[FlipCurlLayer]），这条分支只留给
    // 「折痕跟手」的后续调参；真要接上，得把折痕再往左挪 πR/2 才能让纸边压住手指。
    if (forward && anchorX != null && anchorY != null) {
        foldX = anchoredFoldX(
            pageW = w, pageH = h, lean = lean, tiltW = tiltW,
            fingerX = anchorX, fingerY = anchorY,
        )
    }

    val ax = (foldX - lean * tiltW).coerceIn(0f, w * 2f)
    val bx = (foldX + lean * tiltW).coerceIn(0f, w * 2f)

    val dx = bx - ax
    val dy = h
    val len = hypot(dx, dy).coerceAtLeast(1e-3f)
    val ux = dx / len
    val uy = dy / len
    // 法向取 (uy, -ux)：**永远指向纸的右缘**，两个方向都是。
    //
    // 为什么不分方向翻：旧版 `CurlView.drawBody` 的露出多边形恒以右缘 `w` 为边，两个方向同布局，
    // 方向只换「折痕扫向」与「两张图谁是谁」（见 [FlipCurlFrame] 的 front/back 绑定）。
    // 早先按方向翻法向，向前翻时纸卷落在折痕**右侧**、露出页跑到了左边——与旧版手感相反。
    val nx0 = uy
    val ny0 = -ux

    // 阴影：接近书脊（另一端）时淡出，避免折痕末端在屏幕中间「停一下再跳走」。
    val shadow = (minOf(progress, 1f - progress) * 8f).coerceIn(0f, 1f)

    return CurlFrame(
        pageW = w,
        pageH = h,
        topX = ax,
        topY = 0f,
        bottomX = bx,
        bottomY = h,
        dirX = ux,
        dirY = uy,
        normalX = nx0,
        normalY = ny0,
        shadow = shadow,
        progress = progress,
        direction = direction,
        paperArgb = paperArgb,
    )
}

/**
 * 跟手锚定：解「折痕位置 fx 使折过去的纸边镜像恰好过手指」。
 * 与旧版 `CurlView.anchoredFoldX` 同一闭式解（竖直折痕时退化成 `(手指x + 页宽) / 2`）。
 */
internal fun anchoredFoldX(
    pageW: Float,
    pageH: Float,
    lean: Float,
    tiltW: Float,
    fingerX: Float,
    fingerY: Float,
): Float {
    val d = 2f * lean * tiltW
    val den = pageH * pageH + d * d
    val k = (fingerX - pageW) * den / (2f * pageH)
    return (fingerX + lean * tiltW - (k + d * fingerY) / pageH).coerceIn(0f, pageW * 2f)
}
