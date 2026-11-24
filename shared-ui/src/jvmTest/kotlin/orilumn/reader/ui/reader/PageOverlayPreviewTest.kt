package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import orilumn.reader.engine.skia.NoteTextSpec
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Test
import java.io.File

/**
 * 覆盖层栅格的**目视自查**（不是像素基准）：把旁注框 + 引线 + 页码画成 PNG，
 * 写到 `shared-ui/build/overlay-preview.png`，人眼看一眼有没有画错位置/画丢。
 */
class PageOverlayPreviewTest {

    @Test
    fun renderPreview() {
        val w = 900
        val h = 1300
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        val style = PageOverlayStyle(
            fgArgb = 0xFF202020.toInt(),
            pageBgArgb = 0xFFFFFFFF.toInt(),
            noteSpec = NoteTextSpec(emptyList()),
            showBorder = true,
            showBackground = true,
            textSameColor = false,
        )
        val notes = listOf(
            NoteBubble(
                annotationId = 1,
                text = "这句话说得对，但我觉得还有一层意思没讲透。",
                leftPx = 640f,
                topPx = 300f,
                widthPx = 240f,
                maxLines = 3,
                argb = 0xFFFFE082.toInt(),
                fontPx = 28f,
                padRightPx = 10f,
                arrow = NoteArrow.Straight(Offset(640f, 340f), Offset(600f, 360f)),
                heightPx = 3 * 28f * 1.38f,
            ),
            NoteBubble(
                annotationId = 2,
                text = "嗯",
                leftPx = 20f,
                topPx = 700f,
                widthPx = 120f,
                maxLines = 2,
                argb = 0xFF80D8FF.toInt(),
                fontPx = 28f,
                padRightPx = 6f,
                arrow = NoteArrow.Elbow(
                    listOf(Offset(140f, 720f), Offset(180f, 720f), Offset(180f, 760f)),
                ),
                heightPx = 2 * 28f * 1.38f,
            ),
            NoteBubble(
                annotationId = 3,
                text = "",
                leftPx = 700f,
                topPx = 900f,
                widthPx = 46f,
                maxLines = 1,
                argb = 0xFFB39DDB.toInt(),
                fontPx = 28f,
                compact = true,
                heightPx = 30f,
            ),
        )
        val content = PageOverlayContent(
            notes = notes,
            style = style,
            pageNumber = pageNumberLayoutOf(
                index = 42,
                pageHeightPx = h.toFloat(),
                contentBottomPx = 1150f,
                bodyFontPx = 44f,
                inkArgb = 0xFF202020.toInt(),
                endInsetPx = 80f,
            ),
        )
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(img),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawPageOverlay(content, measurer)
        }
        val out = File("build/overlay-preview.png")
        out.parentFile?.mkdirs()
        val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            ?: error("encode failed")
        out.writeBytes(data.bytes)
        println("wrote ${out.absolutePath}")
    }
}
