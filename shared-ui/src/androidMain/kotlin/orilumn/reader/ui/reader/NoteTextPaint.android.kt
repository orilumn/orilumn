package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import orilumn.reader.engine.skia.DrawLine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Android actual：Compose 画布是 `android.graphics.Canvas`，取不出 skia Canvas
 * （与整页栅格同一条平台限制，见 `ReaderPageRenderer.android.kt`）。
 *
 * 所以只把**这一段旁注文字**画进一张按文本块大小开的离屏 skia surface，再贴回 Compose 画布。
 * 不开整页 surface：旁注通常只占页边一小块，按块开图既省像素搬运，也让这张临时图随调用回收。
 * 覆盖层本身按内容键缓存（`PageOverlayLayer`），这条路径只在内容真的变了时走一次。
 */
internal actual fun DrawScope.drawNoteTextLines(
    lines: List<DrawLine>,
    leftPx: Float,
    topPx: Float,
    widthPx: Int,
    heightPx: Int,
) {
    if (lines.isEmpty()) return
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        noteLineDrawer.drawLines(
            canvas = surface.canvas,
            contentLeft = 0f,
            lines = lines,
            clip = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()),
        )
        val image = surface.makeImageSnapshot()
        val bitmap = try {
            skiaImageToAndroidBitmap(
                image = image,
                w = w,
                h = h,
                alphaType = ColorAlphaType.PREMUL,
            )?.asImageBitmap()
        } finally {
            image.close()
        }
        if (bitmap != null) drawImage(bitmap, topLeft = Offset(leftPx, topPx))
    } finally {
        surface.close()
    }
}
