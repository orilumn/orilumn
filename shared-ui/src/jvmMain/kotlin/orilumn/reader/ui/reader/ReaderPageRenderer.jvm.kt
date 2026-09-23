package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.drawPageBackground
import org.jetbrains.skia.Rect

/**
 * Desktop（JVM）actual：DrawScope 的 `nativeCanvas` 就是 skia [org.jetbrains.skia.Canvas]，
 * 行窗口直接交给 [LineWindowDrawer]，与断行共用同一 FontCollection。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer = remember { JvmReaderPageRenderer() }

private class JvmReaderPageRenderer : ReaderPageRenderer {
    private val drawer = LineWindowDrawer()

    override fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground>,
        bgImages: Map<String, DecodedImage>,
    ) {
        val native = canvas.nativeCanvas
        // 盒背景/边框画在文字之下（与行同一坐标系：X 相对内容区左缘，Y 已是页坐标）。
        // P3-a 单源（圆角/描边环/阴影/alpha，见 drawPageBackground）；P3-b 背景图同形平铺。
        if (backgrounds.isNotEmpty()) {
            for (bg in backgrounds) {
                native.drawPageBackground(bg, contentLeft, 0f, bg.bgKey()?.let { bgImages[it]?.image })
            }
        }
        drawer.drawLines(
            canvas = native,
            contentLeft = contentLeft,
            lines = lines,
            clip = Rect.makeLTRB(contentRectLeft, contentRectTop, contentRectRight, contentRectBottom),
        )
    }
}