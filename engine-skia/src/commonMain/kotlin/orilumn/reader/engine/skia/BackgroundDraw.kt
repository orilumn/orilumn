package orilumn.reader.engine.skia

import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.laying.BackgroundTiles
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/**
 * P3-a: 盒背景/边框绘制单源（JVM 直画与 Android 离屏 surface 共用）。
 *
 * - 方形即旧矩形路径（逐字节一致）；圆角走 `RRect`（CSS 钳制已在 [BoxDrawer] 完成）。
 * - 均匀边框＋圆角走描边环（`strokeWidthPx`）；其余走填充带。
 * - 阴影走位移叠画（blur>0 时 3 层阶梯近似；skija 无 drop-shadow 原语）。
 * - alpha 统一乘（setAlphaf）。
 *
 * P3-b: 背景图（`image` 非空且 `bg.bgSrc` 非空时）：底色之上、边框/文字之下按
 * `repeat/position` 平铺（1:1 原尺寸，见 [BackgroundTiles]），裁在盒形（圆角同形）内；
 * 跨页撕裂以未裁剪锚盒（`bgBoxTop/Bottom`）定原点，翻页不断纹。`image` 缺失即纯色旧路径。
 */
fun Canvas.drawPageBackground(bg: PageBackground, xOff: Float, yOff: Float, image: Image? = null) {
    val argb = bg.argb
    val l = xOff + bg.left
    val t = yOff + bg.yTop.toFloat()
    val r = xOff + bg.right
    val b = yOff + bg.yBottom.toFloat()
    if (r <= l || b <= t) return
    // 阴影先画（形下）：位移＋阶梯模糊近似。
    bg.shadow?.let { sh ->
        val sc = sh.colorHex?.let(::cssHexToArgb) ?: return@let
        if (sh.blur <= 0f) {
            val shadowPaint = Paint().apply { color = withBgAlpha(sc, bg.alpha) }
            drawShape(shadowPaint, l + sh.dx, t + sh.dy, r + sh.dx, b + sh.dy, bg.radii.toArray(), 0f)
        } else {
            for (k in 3 downTo 1) {
                val e = sh.blur * k / 6f
                val layerPaint = Paint().apply { color = withBgAlpha(sc, (bg.alpha * 0.3f / k).coerceIn(0f, 1f)) }
                drawShape(layerPaint, l + sh.dx - e, t + sh.dy - e, r + sh.dx + e, b + sh.dy + e, bg.radii.grown(e), 0f)
            }
        }
    }
    val paint = Paint().apply {
        color = withBgAlpha(argb, bg.alpha)
        if (bg.strokeWidthPx > 0f) {
            mode = PaintMode.STROKE
            strokeWidth = bg.strokeWidthPx.coerceAtLeast(1f)
        }
    }
    drawShape(paint, l, t, r, b, bg.radii.toArray(), bg.strokeWidthPx)
    // P3-b: 背景图（底色之上；边框环是独立 rect，后画即在上，合规范层序）。
    if (image != null && !bg.bgSrc.isNullOrBlank() && image.width > 0 && image.height > 0) {
        drawBgTiles(this, bg, l, t, r, b, xOff, yOff, image)
    }
}

/** P3-b: 背景图平铺（调用方已保证 image 有效；盒形裁剪内逐幅贴图）。 */
private fun drawBgTiles(canvas: Canvas, bg: PageBackground, l: Float, t: Float, r: Float, b: Float, xOff: Float, yOff: Float, image: Image) {
    val anchorTop = yOff + bg.bgBoxTop.toFloat()
    val anchorBottom = yOff + bg.bgBoxBottom.toFloat()
    // 未裁剪锚盒退化（旧 PageBackground 构造）即回落本 rect。
    val aTop = if (anchorBottom > anchorTop) anchorTop else t
    val aBottom = if (anchorBottom > anchorTop) anchorBottom else b
    val tiles = BackgroundTiles.tiles(
        (xOff + bg.left).roundToInt(), aTop.roundToInt(),
        (xOff + bg.right).roundToInt(), aBottom.roundToInt(),
        image.width, image.height, bg.bgRepeat, bg.bgPosition,
    )
    if (tiles.isEmpty()) return
    val saved = canvas.save()
    try {
        val radii = bg.radii.toArray()
        if (radii.all { it == 0f }) {
            canvas.clipRect(Rect.makeLTRB(l, t, r, b))
        } else {
            canvas.clipRRect(RRect.makeComplexLTRB(l, t, r, b, radii), true)
        }
        val paint = Paint().apply { alpha = (255f * bg.alpha).roundToInt().coerceIn(0, 255) }
        val src = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
        for (tile in tiles) {
            // 本页裁剪带之外的幅跳过（跨页撕裂的页外部分不画）。
            if (tile.right <= l || tile.left >= r || tile.bottom <= t || tile.top >= b) continue
            canvas.drawImageRect(
                image, src,
                Rect.makeLTRB(tile.left.toFloat(), tile.top.toFloat(), tile.right.toFloat(), tile.bottom.toFloat()),
                SamplingMode.MITCHELL, paint, false,
            )
        }
    } finally {
        canvas.restoreToCount(saved)
    }
}

/** P3-a alpha 合成（1 即原文）。 */
private fun withBgAlpha(argb: Int, alpha: Float): Int {
    if (alpha >= 1f) return argb
    if (alpha <= 0f) return argb and 0x00FFFFFF
    val a = ((argb ushr 24) * alpha).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0x00FFFFFF)
}

private fun orilumn.reader.engine.css.CornerRadius.toArray(): FloatArray {
    val v = floatArrayOf(topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft)
    return v
}

private fun orilumn.reader.engine.css.CornerRadius.grown(e: Float): FloatArray {
    val v = toArray()
    for (i in v.indices) v[i] = (v[i] + e).coerceAtLeast(0f)
    return v
}

/** 方形走 drawRect（旧路径），圆角走 RRect（描边环由 paint mode 决定）。 */
private fun Canvas.drawShape(paint: Paint, l: Float, t: Float, r: Float, b: Float, radii: FloatArray, strokeWidthPx: Float) {
    val square = radii.all { it == 0f }
    if (square && strokeWidthPx <= 0f) {
        drawRect(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b), paint)
    } else {
        drawRRect(RRect.makeComplexLTRB(l, t, r, b, radii), paint)
    }
}
