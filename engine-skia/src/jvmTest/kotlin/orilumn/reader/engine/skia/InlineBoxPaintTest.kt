package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyle
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.CornerRadius
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

    // ---- 像素助手（E16/E17 用） ----

    private val blueArgb = 0xFF0000FF.toInt()

    /** [argb] 原色的包围盒左上角（首个含原色的行、该行最左 x）。 */
    private fun topLeftOfColor(bmp: Bitmap, argb: Int): Pair<Int, Int> {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (px.getColor(x, y) == argb) return x to y
            }
        }
        error("画布上没有该颜色")
    }

    /** 首个含 [argb] 的行里、该行最右的 [argb] x（圆角切右上角时该值变小）。 */
    private fun topRowMaxX(bmp: Bitmap, argb: Int): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            var max = -1
            for (x in 0 until bmp.width) if (px.getColor(x, y) == argb) max = x
            if (max >= 0) return max
        }
        error("画布上没有该颜色")
    }

    /**
     * 有 [argb] 原色的行里、**段数最少**那行的段数（实线 =1、虚线 ≥2）。
     *
     * 取最少而非最多：边框带可能与字形墨重叠（升部切入带内），字形会把整段**再切碎**——
     * 最多段数会把「实线被字形切出的假缝隙」误判成虚线；最少段数只反映带自身的切段规则。
     * 无该色的行（抗锯齿未达原色）不计。
     */
    private fun minColorRuns(bmp: Bitmap, argb: Int): Int {
        val px = requireNotNull(bmp.peekPixels())
        var best = Int.MAX_VALUE
        for (y in 0 until bmp.height) {
            var runs = 0
            var inRun = false
            for (x in 0 until bmp.width) {
                val hit = px.getColor(x, y) == argb
                if (hit && !inRun) {
                    runs++
                    inRun = true
                } else if (!hit) {
                    inRun = false
                }
            }
            if (runs in 1 until best) best = runs
        }
        return if (best == Int.MAX_VALUE) 0 else best
    }

    // ---- E16：虚线按虚线画（与块盒同一份切段几何） ----

    @Test
    fun `行内盒虚线边框有缝隙、实线整带连续（E16）`() {
        val text = "abcdefgh"
        val topBand = InlineBoxRun(
            0, text.length,
            border = Edges(top = 4f),
            borderColors = BorderColorEdges("#0000ff"),
            borderStyles = BorderStyleEdges(top = BorderStyle.SOLID),
        )
        val dashed = topBand.copy(borderStyles = BorderStyleEdges(top = BorderStyle.DASHED))

        val solidBmp = render(listOf(line(text, listOf(topBand))))
        val dashedBmp = render(listOf(line(text, listOf(dashed))))

        assertTrue("实线顶边必须落墨", hasExactColor(solidBmp, blueArgb))
        assertEquals("实线整带一段", 1, minColorRuns(solidBmp, blueArgb))
        assertTrue("虚线必须切出缝隙（≥2 段），实测 ${minColorRuns(dashedBmp, blueArgb)} 段",
            minColorRuns(dashedBmp, blueArgb) >= 2)
    }

    // ---- E17：圆角背景 ----

    @Test
    fun `圆角背景把角上的底色切掉（E17）`() {
        val text = "oooo"
        val square = InlineBoxRun(0, text.length, bgHex = "#fff176")
        val round = square.copy(borderRadius = CornerRadius(8f, 8f, 8f, 8f))

        val sqBmp = render(listOf(line(text, listOf(square))))
        val rdBmp = render(listOf(line(text, listOf(round))))
        val sqTL = topLeftOfColor(sqBmp, markerArgb)
        val rdTL = topLeftOfColor(rdBmp, markerArgb)

        assertEquals("方形底色左上角顶到 border-box 左缘", contentLeft.toInt(), sqTL.first)
        assertTrue("圆角把左上角切掉（首个底色像素明显右移），sq=${sqTL.first} rd=${rdTL.first}",
            rdTL.first >= sqTL.first + 3)
        assertTrue("圆角盒仍须有底色", hasExactColor(rdBmp, markerArgb))
    }

    // ---- E17：圆角边框环 ----

    @Test
    fun `圆角行内边框是内缩描边环、角被弧切掉（E17）`() {
        val text = "abcd"
        val uniform = Edges(top = 4f, right = 4f, bottom = 4f, left = 4f)
        val squareRing = InlineBoxRun(
            0, text.length,
            border = uniform,
            borderColors = BorderColorEdges("#0000ff"),
            borderStyles = BorderStyleEdges.uniform(BorderStyle.SOLID),
        )
        val roundRing = squareRing.copy(borderRadius = CornerRadius(8f, 8f, 8f, 8f))

        val sqBmp = render(listOf(line(text, listOf(squareRing))))
        val rdBmp = render(listOf(line(text, listOf(roundRing))))
        val sqTL = topLeftOfColor(sqBmp, blueArgb)
        val rdTL = topLeftOfColor(rdBmp, blueArgb)

        assertTrue("方形环左上角顶到 border-box", hasExactColor(sqBmp, blueArgb))
        assertTrue("圆角环左上角被弧切掉（首个蓝像素右移），sq=${sqTL.first} rd=${rdTL.first}",
            rdTL.first >= sqTL.first + 3)
        assertTrue("圆角环仍须落蓝", hasExactColor(rdBmp, blueArgb))
    }

    // ---- E17：slice —— 只在真角保留圆角 ----

    @Test
    fun `切开的片段左端圆、右端方（E17 slice）`() {
        val text = "abcdefghij" // 10 字；本行只画前 3 字 ⇒ 盒被切成首片段
        val square = InlineBoxRun(0, text.length, bgHex = "#fff176")
        val round = square.copy(borderRadius = CornerRadius(8f, 8f, 8f, 8f))

        val sqBmp = render(listOf(line(text, listOf(square)).copy(range = 0 until 3)))
        val rdBmp = render(listOf(line(text, listOf(round)).copy(range = 0 until 3)))
        val sqTL = topLeftOfColor(sqBmp, markerArgb)
        val rdTL = topLeftOfColor(rdBmp, markerArgb)

        // 左端是元素真角 ⇒ 保留半径：首个底色像素右移。
        assertTrue("真角仍圆，sq=${sqTL.first} rd=${rdTL.first}", rdTL.first >= sqTL.first + 3)
        // 右端是切开端（非真角）⇒ 半径归 0 ⇒ 顶行右缘与方形对照完全同位。
        assertEquals("切开端必须是方角", topRowMaxX(sqBmp, markerArgb), topRowMaxX(rdBmp, markerArgb))
    }
}
