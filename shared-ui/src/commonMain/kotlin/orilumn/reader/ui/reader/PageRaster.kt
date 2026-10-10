package orilumn.reader.ui.reader

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt
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
 *  - 调用方传入的 [lines]/[backgrounds]/[images] 一律是**页坐标系**（章节绝对 Y 已减去 shift）；
 *  - 本函数**自己**把屏幕坐标换算到离屏画布（原点 = 内容区左上）：X 减 `contentRectLeft`、
 *    Y 减 `contentRectTop`——三类元素统一在这里做，**不许各 actual 各自平移**。
 *    （P0a 教训：Android actual 起初只平移了 lines/backgrounds 而漏了 images，插图整体下移
 *    一个上边距，肉眼可见；像素 A/B 逮到。故把换算收进本函数，让"漏一类"不可表达。）
 *  - 裁剪区恒为整块内容区 `[0,0,w,h]`。
 *
 * 绘制顺序（**改这里等于改屏幕像素**，顺序有物理含义：底 → 盒背景 → 文字 → 插图）：
 *  1. `clear(pageBg)`：页面底色打底（卷曲时页背不能透明）；
 *  2. 盒背景/边框/背景图（`drawPageBackground` 单源，含圆角/描边/阴影/alpha）；
 *  3. 行窗口（`LineWindowDrawer`，与断行共用同一 FontCollection）；
 *  4. 插图（`PageImageSlot`；缺图画灰色占位，不静默空白）。
 */
internal fun drawPageContent(
    canvas: Canvas,
    contentLeft: Float,
    contentRectLeft: Float,
    contentRectTop: Float,
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
    val xOff = contentLeft - contentRectLeft
    val yOff = contentRectTop.roundToInt()
    if (backgrounds.isNotEmpty()) {
        for (bg in backgrounds) {
            canvas.drawPageBackground(bg, xOff, -yOff.toFloat(), bg.bgKey()?.let { bgImages[it]?.image })
        }
    }
    if (lines.isNotEmpty()) {
        drawer.drawLines(
            canvas = canvas,
            contentLeft = xOff,
            lines = if (yOff != 0) lines.map { it.copy(yTop = it.yTop - yOff, yBottom = it.yBottom - yOff) } else lines,
            clip = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()),
        )
    }
    drawPageImages(
        canvas = canvas,
        images = if (yOff != 0) images.map { it.shiftBy(-yOff) } else images,
        contentLeft = xOff,
    )
}

/** 槽位整体上移 [dy]（页坐标 → 离屏坐标；几何与解码结果都不变，只换 y）。 */
private fun PageImageSlot.shiftBy(dy: Int): PageImageSlot =
    if (dy == 0) this else PageImageSlot(
        image.copy(yTop = image.yTop + dy, yBottom = image.yBottom + dy),
        decoded,
    )

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

/**
 * 页栅格缩放（「两级分辨率」第二级，见设计文档 §3.3）。
 *
 * **1f** = 与内容区 1:1 全分辨率（P0b 起的原状）。**小于 1** ⇒ 离屏 surface 按比例缩小、
 * 贴回内容区时放大：内存按 `scale²` 下降（0.5 ⇒ 1/4），栅格耗时基本不变
 * （真机实测 0.5/0.7/0.85/1.0 冷栅格都在 680–910ms —— 成本在文字排版与字形，不在像素填充）。
 *
 * 动机：卷曲/滑动过程中纹理被压缩/拉伸，全分辨率无视觉收益（平板单页 14.3MB、
 * 56MB 池只装 3 页，零余量）。1/2 代理把单页压到 3.6MB，池能装十几页。
 *
 * **固定为 1f，阅读页维持原样**（2026-10 决定）：本值作用于**包括静止显示页**在内的
 * 全部栅格，0.5 时正文边能量掉到 12%，肉眼能看出柔——静止阅读不该承担这个代价。
 * 缩图只能出现在**翻页动画那一级**，且不得改动静置画面的像素；
 * 分工与落地方式见 §3.3（代理位图），不要用「把本常量调小」来实现。
 * `ReaderPageRenderer.android.kt` 里的 `readRasterScaleDebug` 是实验钩子，不是入口。
 */
internal const val PAGE_RASTER_SCALE = 1f