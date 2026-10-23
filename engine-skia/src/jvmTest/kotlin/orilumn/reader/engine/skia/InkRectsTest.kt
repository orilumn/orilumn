package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旁注箭头用的**逐字墨迹几何**（实施计划 §4.4.1）。
 *
 * 断言只看三件事：粒度是一个字一个矩形、空白/隐藏字符不产出、矩形与真正画出来的墨迹同源。
 */
class InkRectsTest {

    private fun line(
        text: String,
        range: IntRange,
        charBase: Int = 0,
        yTop: Int = 0,
        yBottom: Int = 40,
    ) = DrawLine(
        text = text,
        range = range,
        yTop = yTop,
        yBottom = yBottom,
        alignment = TextAlign.LEFT,
        fontSizePx = 20f,
        lineHeightRatio = 2f,
        tag = "p",
        families = emptyList(),
        weight = 400,
        italic = false,
        monospace = false,
        letterSpacingEm = 0f,
        lineWidthPx = 300,
        charBase = charBase,
    )

    private fun inkRects(vararg lines: DrawLine, range: IntRange? = null): List<Rect> {
        val drawer = LineWindowDrawer()
        val from = range?.first ?: lines.minOf { it.charBase + it.range.first }
        val to = range?.last ?: lines.maxOf { it.charBase + it.range.last }
        return drawer.inkRects(lines.toList(), chapter = 0, range = from..to)
    }

    @Test
    fun `one rect per visible character`() {
        val rects = inkRects(line("一二三", 0..2))
        assertEquals(3, rects.size)
        // 从左到右、互不交叠（墨迹边界允许 1px 级的字形外扩）。
        for (i in 0 until rects.size - 1) {
            assertTrue(
                "第 $i 个字应在前一个字右侧：$rects",
                (rects[i].left + rects[i].right) / 2f < (rects[i + 1].left + rects[i + 1].right) / 2f,
            )
            assertTrue("不该整段糊成一块：$rects", rects[i].right <= rects[i + 1].left + 1.5f)
        }
    }

    @Test
    fun `blank characters produce no rect`() {
        // 空格占宽但不落墨：不该给箭头留一个「假墨迹」。
        val rects = inkRects(line("a b", 0..2))
        assertEquals(2, rects.size)
        // 两个字的矩形之间正好是那个空格的位置（中间有缝）。
        assertTrue("空格处应留缝：$rects", rects[0].right < rects[1].left)
    }

    @Test
    fun `punctuation ink is narrower than its advance slot`() {
        // 逗号的墨迹明显窄于字位：箭头才能从标点前后的空白穿过去。
        val rects = inkRects(line("中，中", 0..2))
        assertEquals(3, rects.size)
        assertTrue("逗号墨迹应窄于汉字：${rects.map { it.width }}", rects[1].width < rects[0].width)
    }

    @Test
    fun `ink rect matches the painted glyph`() {
        // 量画同源：矩形并集应与真正画到画布上的墨迹包围盒重合（±2px，抗锯齿边缘）。
        val contentLeft = 20f
        val l = line("国国", 0..1)
        val rects = inkRects(l).map {
            Rect.makeLTRB(
                it.left + contentLeft,
                it.top,
                it.right + contentLeft,
                it.bottom,
            )
        }
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(360, 160, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(
            canvas,
            contentLeft,
            listOf(l),
            Rect.makeXYWH(0f, 0f, 360f, 160f),
        )
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        val px = requireNotNull(bitmap.peekPixels())
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = px.getColor(x, y)
                val dark = (c shr 16 and 0xFF) < 96 && (c shr 8 and 0xFF) < 96 && (c and 0xFF) < 96
                if (!dark) continue
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        val painted = Rect.makeLTRB(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
        val union = rects.reduce { a, b ->
            Rect.makeLTRB(
                minOf(a.left, b.left),
                minOf(a.top, b.top),
                maxOf(a.right, b.right),
                maxOf(a.bottom, b.bottom),
            )
        }
        assertEquals("左缘", painted.left, union.left, 2f)
        assertEquals("右缘", painted.right, union.right, 2f)
        assertEquals("上缘", painted.top, union.top, 2f)
        assertEquals("下缘", painted.bottom, union.bottom, 2f)
    }

    @Test
    fun `rects stay inside their own line`() {
        val first = line("一二", 0..1, charBase = 0, yTop = 0, yBottom = 40)
        val second = line("三四", 0..1, charBase = 2, yTop = 40, yBottom = 80)
        val rects = inkRects(first, second)
        assertEquals(4, rects.size)
        assertEquals(2, rects.count { it.top < 40f })
        assertEquals(2, rects.count { it.top >= 40f })
    }
}
