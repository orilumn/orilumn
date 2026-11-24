package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import orilumn.reader.engine.skia.NoteTextLayout
import orilumn.reader.engine.skia.NoteTextSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层旁注文字的落墨接缝：引擎出的行必须真的画到 Compose 画布上。
 *
 * 这条测的是**平台缝**（桌面 `nativeCanvas` 直画 skia），断行 / 截断 / 字距由
 * `NoteTextLayoutTest` 覆盖。像素判据只问「有没有墨」，不问字形细节——字形随机器字库变。
 */
class NoteTextPaintTest {

    @Test
    fun drawsEngineLinesIntoComposeCanvas() {
        val w = 240
        val h = 80
        val spec = NoteTextSpec(families = emptyList())
        val draw = NoteTextLayout.drawLines(
            spec = spec,
            text = "批注文字",
            fontPx = 20f,
            lineHeightRatio = 1.38f,
            widthPx = w - 20,
            maxLines = 2,
            inkArgb = 0xFF000000.toInt(),
        )
        val image = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(image),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawNoteTextLines(
                lines = draw.lines,
                leftPx = 10f,
                topPx = 10f,
                widthPx = w - 20,
                heightPx = draw.heightPx,
            )
        }
        val pixels = IntArray(w * h)
        image.readPixels(pixels)
        assertTrue("旁注文字应当画出了墨迹", pixels.any { (it ushr 24) != 0 })
    }
}
