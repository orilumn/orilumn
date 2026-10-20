package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.CapFloat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `::first-letter{float:left}` 首字下沉的**落墨**锁（渲染层）。
 *
 * 几何（首行不收窄、后续行收窄右移）由断行/盒流侧单测锁（`FirstLetterEndToEndTest`）；
 * 这里锁绘制侧那一半：**正文基线不因首字而下移**，首字自己按下沉盒基线落墨。
 *
 * 「下移」是修前的错法：首字按行内语义参与行框 ⇒ 行框被 2em 撑高、基线随之下移，
 * 整段正文都比浏览器低一截（Chromium 实测：首字压低 14 CSS px，正文基线不动）。
 */
class FirstLetterFloatPaintTest {

    private val baseSize = 20f
    private val ratio = 1.5f
    private val capSize = 2f * baseSize
    private val capHeight = (capSize * ratio).toInt()

    /** 首字 2em 的一段行；[cap] 为 null = 旧口径（行内首字，无下沉盒）。 */
    private fun line(cap: CapFloat?, withCapChar: Boolean = true): DrawLine {
        val text = if (withCapChar) "Tabcdefgh" else "abcdefgh"
        val runs = if (withCapChar) {
            listOf(FontRun(0, 1, emptyList(), "p", 400, false, false, fontSizePx = capSize))
        } else {
            emptyList()
        }
        return DrawLine(
            text = text,
            range = 0 until text.length,
            yTop = 0,
            yBottom = (baseSize * ratio).toInt(),
            alignment = TextAlign.LEFT,
            fontSizePx = baseSize,
            lineHeightRatio = ratio,
            tag = "p",
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = 300,
            fontRuns = runs,
            capFloat = cap,
        )
    }

    private fun render(l: DrawLine): Bitmap {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(400, 120, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, 10f, listOf(l), Rect.makeXYWH(0f, 0f, 400f, 120f))
        return bitmap
    }

    /** 画布 [l, r] 列区间内**最下**的有墨行（-1 = 无墨）。 */
    private fun inkBottom(bmp: Bitmap, l: Int, r: Int): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (y in bmp.height - 1 downTo 0) {
            for (x in l..r) if (px.getColor(x, y) != Color.WHITE) return y
        }
        return -1
    }

    @Test
    fun `正文基线不因下沉首字而下移`() {
        val cap = CapFloat(widthPx = 24, lines = 1, endExclusive = 1, heightPx = capHeight)
        // 对照：段落没有首字（同字号同族）—— 正文基线就是这个位置。
        val control = render(line(null, withCapChar = false))
        val sunk = render(line(cap))
        val inline = render(line(null))

        // 正文（首字右侧，x ≥ 60 只含 'd' 起的字）在两版里都要与对照同位。
        val body = inkBottom(control, 0, 399)
        assertTrue("对照必须有正文墨迹", body > 0)
        assertEquals("下沉首字不得移动正文基线", body, inkBottom(sunk, 60, 399))
        assertTrue(
            "反例：行内首字（修前）会把整行基线往下推，实测 ${inkBottom(inline, 60, 399)} vs 对照 $body",
            inkBottom(inline, 60, 399) > body,
        )

        // 首字自己：下沉后必须比正文基线更靠下（它压在下面几行上）。
        assertTrue(
            "下沉首字应低于正文基线（实测 ${inkBottom(sunk, 0, 30)} vs 正文 $body）",
            inkBottom(sunk, 0, 30) > body,
        )
    }
}
