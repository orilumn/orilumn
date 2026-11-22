package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import orilumn.reader.engine.skia.DrawLine
import org.jetbrains.skia.Rect

/**
 * JVM actual：Compose 的 `nativeCanvas` 就是 skia Canvas，直接交给 [noteLineDrawer]，零拷贝。
 *
 * 不能自己另开离屏 surface：桌面这条路径每帧都可能重画覆盖层，多一次整页像素搬运就是
 * 翻页动画里肉眼可见的一顿。
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
    val canvas = drawContext.canvas.skiaCanvas
    canvas.save()
    try {
        canvas.translate(leftPx, topPx)
        // 裁剪到文本块：行宽与框宽取整差一像素时，末字也不该越出框外。
        noteLineDrawer.drawLines(
            canvas = canvas,
            contentLeft = 0f,
            lines = lines,
            clip = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()),
        )
    } finally {
        canvas.restore()
    }
}
