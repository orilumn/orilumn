package orilumn.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * 阅读器封面页（用户层前置页）：只读展示，不进分页/存档/目录。
 *
 * - 拉伸全屏（默认开）：`FillBounds` 铺满内容区（可能变形）；
 * - 等比（关）：`Fit` 居中，周围露底色。
 */
@Composable
fun ReaderCoverPage(
    cover: ImageBitmap,
    proportional: Boolean,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
        Image(
            bitmap = cover,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (proportional) ContentScale.Fit else ContentScale.FillBounds,
        )
    }
}

/**
 * 把封面按 [ReaderCoverPage] 的同一套画法烤成**页大小**位图（[pageW] × [pageH]，底色 + 封面）。
 *
 * 为什么要烤：卷曲层的纹理坐标是**页**坐标，页栅格只占版心（`CurlPageImages.page`）；
 * 封面没有页栅格、是纯组合件（[ReaderCoverPage] 自己按 `ContentScale` 画）。烤成整页位图后
 * 它就能当「一张页」交给卷曲层，纹理偏移恒为 (0,0)，与正文页共用同一套卷曲几何。
 *
 * 不烤成版心大小：卷曲层页边露的是纸色，封面缩进版心会在四边多出一圈纸边。
 */
internal fun bakeCoverPageBitmap(
    cover: ImageBitmap,
    pageW: Int,
    pageH: Int,
    proportional: Boolean,
    bgArgb: Int,
): ImageBitmap {
    val w = pageW.coerceAtLeast(1)
    val h = pageH.coerceAtLeast(1)
    val out = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = false)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(out),
        size = androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
    ) {
        drawRect(Color(bgArgb))
        drawCover(cover, proportional)
    }
    return out
}

/** 与 `Image(contentScale = ...)` 同一套落位：拉伸铺满，等比按短边缩放后居中。 */
private fun DrawScope.drawCover(cover: ImageBitmap, proportional: Boolean) {
    val pageW = size.width
    val pageH = size.height
    if (cover.width <= 0 || cover.height <= 0) return
    if (!proportional) {
        drawImage(cover, dstOffset = IntOffset.Zero, dstSize = IntSize(pageW.roundToInt(), pageH.roundToInt()))
        return
    }
    val scale = minOf(pageW / cover.width, pageH / cover.height)
    val dw = (cover.width * scale).roundToInt().coerceAtLeast(1)
    val dh = (cover.height * scale).roundToInt().coerceAtLeast(1)
    // 居中按半像素平移（与 `ContentScale.Fit` 的画法一致），不取整：取整会在一边多出 1px 纸边。
    translate((pageW - dw) / 2f, (pageH - dh) / 2f) {
        drawImage(cover, dstOffset = IntOffset.Zero, dstSize = IntSize(dw, dh))
    }
}
