package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.InlineBoxRun
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 行内盒（引擎欠账第一刀）的**无头像素锁**。
 *
 * 对接装机像素 A/B（`docs/引擎欠账-对齐浏览器语义.md` §5）的可自动化部分：本机是同一个
 * `LineWindowDrawer` 软件栅格到 `Bitmap`，与 Android 软件绘制目标同构，故 A/B 的两个输入
 * **就在同一个测试二进制里**（比「两份 APK 各截一次图」更严：连构建都不是变量）。
 *
 * 覆盖面：
 * - **1a**：`background-color` 真的落墨（对照：无盒时无色）；且横向为 0 的盒
 *   （`<mark>` / `border-bottom` 那一类）**不移动任何文字**（零回归）。
 * - **1b**：盒的横向 `padding`/`border` 把**盒内及其后**文字右移，盒前文字不动
 *   （对照：无盒 / 关闸两版的墨迹右缘都回到原位）。
 * - **回退纪律**：闸门关掉时**只画不推**（底色仍在、右缘不动），两侧一致 ⇒ 不会画比量宽。
 */
class InlineBoxPaintTest {

    private val contentLeft = 20f
    private val contentWidth = 320
    private val markerArgb = 0xFFFFF176.toInt() // ua.css 的 mark 高亮底色

    private fun line(text: String, boxes: List<InlineBoxRun> = emptyList()): DrawLine {
        val fontSizePx = 16f
        return DrawLine(
            text = text,
            range = 0 until text.length,
            yTop = 0,
            yBottom = (fontSizePx * 2f).toInt(),
            alignment = TextAlign.LEFT,
            fontSizePx = fontSizePx,
            lineHeightRatio = 2f,
            tag = "p",
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = contentWidth,
            inlineBoxRuns = boxes,
        )
    }

    private fun render(lines: List<DrawLine>, width: Int = 400, height: Int = 200): Bitmap {
        // 与 LineWindowDrawerTest 同款：软件栅格到 Bitmap 背衬 canvas（Android 上同构）。
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, contentLeft, lines, Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    /** 最右的有墨列（-1 = 整幅无墨）。 */
    private fun maxInkX(bmp: Bitmap): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in bmp.width - 1 downTo 0) {
            for (y in 0 until bmp.height) {
                if (px.getColor(x, y) != Color.WHITE) return x
            }
        }
        return -1
    }

    /** 最左的有墨列（-1 = 整幅无墨）。 */
    private fun minInkX(bmp: Bitmap): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                if (px.getColor(x, y) != Color.WHITE) return x
            }
        }
        return -1
    }

    private fun hasExactColor(bmp: Bitmap, argb: Int): Boolean {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (px.getColor(x, y) == argb) return true
            }
        }
        return false
    }

    // ---- 1a：底色真的落墨 ----

    @Test
    fun `行内背景按 border-box 落墨`() {
        val text = "abcd"
        val box = InlineBoxRun(0, text.length, bgHex = "#fff176")

        val withBox = render(listOf(line(text, listOf(box))))
        val control = render(listOf(line(text)))

        assertTrue("盒的底色必须出现在画布上", hasExactColor(withBox, markerArgb))
        assertFalse("对照（无盒）不得出现该底色", hasExactColor(control, markerArgb))
    }

    // ---- 1a：横向为 0 的盒零回归 ----

    @Test
    fun `横向边距为零的盒不移动任何文字`() {
        val text = "a".repeat(20)
        // only 底色：与 <mark> / border-bottom 同类（横向边距 0）。
        val box = InlineBoxRun(0, text.length, bgHex = "#fff176")

        val withBox = render(listOf(line(text, listOf(box))))
        val control = render(listOf(line(text)))

        assertTrue(hasExactColor(withBox, markerArgb))
        assertEquals("左缘不得移动", minInkX(control), minInkX(withBox))
        assertEquals("右缘不得移动（零回归）", maxInkX(control), maxInkX(withBox))
    }

    // ---- 1b：横向边距把盒内及其后文字推开 ----

    @Test
    fun `盒的右侧内边距把其后文字右移`() {
        val text = "a".repeat(20)
        val pad = 24f
        // 盒覆盖前 3 字：右边内边距记在其后，推开花 3 起的所有文字。
        val box = InlineBoxRun(0, 3, bgHex = "#fff176", padding = Edges(right = pad))

        val withBox = render(listOf(line(text, listOf(box))))
        val control = render(listOf(line(text)))

        val shifted = maxInkX(withBox) - maxInkX(control)
        assertTrue("其后文字应右移约 $pad px，实测 $shifted", abs(shifted - pad) <= 2f)
        assertEquals("盒前文字不得移动（左缘）", minInkX(control), minInkX(withBox))
    }

    @Test
    fun `盒的左侧内边距把盒内及其后文字右移`() {
        val text = "a".repeat(20)
        val pad = 20f
        // 盒从第 3 字起：左边内边距记在其前，推开第 3 字起的所有文字。
        val box = InlineBoxRun(3, 6, bgHex = "#fff176", padding = Edges(left = pad))

        val withBox = render(listOf(line(text, listOf(box))))
        val control = render(listOf(line(text)))

        val shifted = maxInkX(withBox) - maxInkX(control)
        assertTrue("盒内及其后文字应右移约 $pad px，实测 $shifted", abs(shifted - pad) <= 2f)
        assertEquals("盒前文字不得移动（左缘）", minInkX(control), minInkX(withBox))
    }

    // ---- 回退纪律：关闸只画不推 ----

    @Test
    fun `关闸时行内盒只画不推`() {
        val text = "a".repeat(20)
        val box = InlineBoxRun(0, 3, bgHex = "#fff176", padding = Edges(right = 24f))

        val control = render(listOf(line(text)))
        try {
            AbSwitch.apply("inhouseBreak=0")
            val gated = render(listOf(line(text, listOf(box))))

            assertTrue("关闸也必须画底色（1a 行为）", hasExactColor(gated, markerArgb))
            assertEquals("关闸不得移动右缘（否则画比量宽）", maxInkX(control), maxInkX(gated))
        } finally {
            AbSwitch.resetForTest()
        }
    }
}
