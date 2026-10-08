package orilumn.reader.ui.reader

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.drawPageBackground

/**
 * P0a 整页栅格的**绘制顺序单源**（用户层·渲染缝，两端共用一份）。
 *
 * 背景：翻页动画要「整页一张位图」当纹理，而此前两端各画各的——
 * 文字/盒背景进渲染器（Android 走离屏 surface，Desktop 直画），
 * 插图在 `ReaderPageCanvas` 的 Compose 层另画。于是：① Desktop 根本没有页位图；
 * ② 插图不在位图里（截图丢图、静止与动画像素不一致）。
 *
 * 本函数把「一页像素长什么样」收成一处，两端 actual 只负责**去哪画**
 * （Android：离屏 raster surface；Desktop：离屏 raster surface）——
 * 顺序、几何、占位语义由此只有一份，不会再各自漂移。
 *
 * 坐标约定（与 `ReaderPageCanvas` 同款，勿改）：
 *  - 调用方传入的 [lines]/[backgrounds]/[images] 已是**页坐标系**（章节绝对 Y 已减去 shift）；
 *  - 画布原点 = 内容区左上，故 X 传 `contentLeft - contentRectLeft`、Y 传 `-contentRectTop`；
 *  - 裁剪区恒为整块内容区 `[0,0,w,h]`。
 *
 * 绘制顺序（**改这里等于改屏幕像素**，顺序有物理含义：底 → 盒背景 → 文字 → 插图）：
 *  1. `clear(pageBg)`：页面底色打底（JPEG/无 alpha 时代的老问题如今是「卷曲时页背不能透明」）；
 *  2. 盒背景/边框/背景图（`drawPageBackground` 单源，含圆角/描边/阴影/alpha）；
 *  3. 行窗口（`LineWindowDrawer`，与断行共用同一 FontCollection）；
 *  4. 插图（`PageImageSlot`；缺图画灰色占位，不静默空白）。
 */
internal fun drawPageContent(
    canvas: Canvas,
    contentLeft: Float,
    lines: List<DrawLine>,
    backgrounds: List<PageBackground>,
    bgImages: Map<String, orilumn.reader.engine.skia.DecodedImage>,
    images: List<PageImageSlot>,
    pageBg: Int,
    w: Int,
    h: Int,
    drawer: LineWindowDrawer,
) {
    canvas.clear(pageBg)
    if (backgrounds.isNotEmpty()) {
        for (bg in backgrounds) {
            canvas.drawPageBackground(bg, contentLeft, 0f, bg.bgKey()?.let { bgImages[it]?.image })
        }
    }
    if (lines.isNotEmpty()) {
        drawer.drawLines(
            canvas = canvas,
            contentLeft = contentLeft,
            lines = lines,
            clip = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()),
        )
    }
    drawPageImages(canvas, images, contentLeft)
}

/**
 * 插图落位（与旧 `ReaderPageCanvas.drawPageImage` 逐项同形）：
 * `dstLeft = contentLeft + xLeft`（[orilumn.reader.engine.skia.PageImage.xLeft] 与 `DrawLine.xLeft`
 * 同口径，相对内容区左缘）、`dstTop = yTop`（调用方已平移到页坐标）、尺寸取盒流 used 尺寸
 * （`yBottom - yTop`）。缺图画灰色占位 `0xFFDDDDDD`。
 */
private fun drawPageImages(canvas: Canvas, images: List<PageImageSlot>, contentLeft: Float) {
    if (images.isEmpty()) return
    val paint = Paint()
    for (slot in images) {
        val img = slot.image
        val w = img.widthPx.coerceAtLeast(1)
        val h = (img.yBottom - img.yTop).coerceAtLeast(1)
        val left = contentLeft + img.xLeft
        val top = img.yTop.toFloat()
        val decoded = slot.decoded
        if (decoded != null) {
            // 采样显式给 LINEAR：与旧 Compose `drawImage` 默认 `FilterQuality.Low`（双线性）
            // 同档，避免「下沉进位图」顺带把图片变锯齿（这种降级肉眼可见，属静默回归）。
            canvas.drawImageRect(
                decoded.image,
                Rect.makeWH(decoded.width.toFloat(), decoded.height.toFloat()),
                Rect.makeLTRB(left, top, left + w, top + h),
                SamplingMode.LINEAR,
                paint,
                false,
            )
        } else {
            // 解码中/失败的灰色占位：保证「有图几何」永远可见。
            paint.color = PLACEHOLDER_GRAY
            canvas.drawRect(Rect.makeLTRB(left, top, left + w, top + h), paint)
        }
    }
}

/** 缺图占位色（复刻旧 `drawPageImage` 的 `0xFFDDDDDD`）。 */
private const val PLACEHOLDER_GRAY = 0xFFDDDDDD.toInt()