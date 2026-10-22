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
 * 标注装饰投影 / 几何 / 落墨的无头锁。
 *
 * 不经过任何 UI：`projectDecor` 只做区间裁切，`decorRects` 与 `LineWindowDrawer` 共用
 * `placementFor` 的逐字几何，像素锁则把同一套 `DrawLine` 软件栅格到 `Bitmap`。
 */
class DecorProjectionTest {

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

    // ---- 投影：只裁到本行，不丢颜色/类型/优先级 ----

    @Test
    fun `projectDecor clips chapter ranges to each line`() {
        val lines = listOf(
            line("abcdef", 0..5, charBase = 100),
            line("ghij", 0..3, charBase = 106),
            line("klm", 0..2, charBase = 120),
        )
        val out = projectDecor(
            lines,
            chapter = 3,
            ranges = listOf(
                DecorRange(99, 102, DecorKind.BACKGROUND, 0x80FF0000.toInt()),
                DecorRange(104, 109, DecorKind.UNDERLINE, 0xFF0000FF.toInt(), priority = 2),
                DecorRange(106, 108, DecorKind.BACKGROUND, 0x8000FF00.toInt(), priority = 1),
                DecorRange(130, 140, DecorKind.UNDERLINE, 0xFF000000.toInt()),
            ),
        )

        // 第一行：高亮裁到 [100,102)，划线裁到 [104,106)；第二行：高亮 [106,108)、划线 [106,109)。
        assertEquals(
            listOf(100 to 102),
            out[0].decorRuns.filter { it.kind == DecorKind.BACKGROUND }.map { it.start to it.endExclusive },
        )
        assertEquals(
            listOf(104 to 106),
            out[0].decorRuns.filter { it.kind == DecorKind.UNDERLINE }.map { it.start to it.endExclusive },
        )
        assertEquals(
            listOf(106 to 108),
            out[1].decorRuns.filter { it.kind == DecorKind.BACKGROUND }.map { it.start to it.endExclusive },
        )
        assertEquals(
            listOf(106 to 109),
            out[1].decorRuns.filter { it.kind == DecorKind.UNDERLINE }.map { it.start to it.endExclusive },
        )
        assertTrue(out[2].decorRuns.isEmpty())

        // 同一行内按 priority 先低后高。
        val p = out[1].decorRuns.indexOfFirst { it.kind == DecorKind.BACKGROUND }
        val u = out[1].decorRuns.indexOfFirst { it.kind == DecorKind.UNDERLINE }
        assertTrue("低优先级的底色应先于高优先级的划线", p < u)
    }

    @Test
    fun `empty ranges clear previous decor runs`() {
        val withDecor = line("abc", 0..2).copy(
            decorRuns = listOf(DecorRange(0, 2, DecorKind.BACKGROUND, 0x80FF0000.toInt())),
        )
        val out = projectDecor(listOf(withDecor), chapter = 0, ranges = emptyList())
        assertTrue(out.single().decorRuns.isEmpty())
    }

    // ---- 几何：区间 → 每行矩形，x 相对内容区、y 用行框 ----

    @Test
    fun `decorRects returns one rect per intersected line`() {
        val drawer = LineWindowDrawer()
        val lines = listOf(
            line("abcdef", 0..5, charBase = 0, yTop = 0, yBottom = 20),
            line("ghij", 0..3, charBase = 6, yTop = 20, yBottom = 40),
        )

        val rects = drawer.decorRects(lines, chapter = 1, range = 2..7)

        assertEquals(2, rects.size)
        assertEquals(0f, rects[0].top, 0.01f)
        assertEquals(20f, rects[0].bottom, 0.01f)
        assertEquals(20f, rects[1].top, 0.01f)
        assertEquals(40f, rects[1].bottom, 0.01f)
        assertTrue(rects[0].left >= 0f)
        assertTrue(rects[0].right > rects[0].left)
        // 第二行从 'g' 起，左缘应回到行首。
        assertEquals(0f, rects[1].left, 0.01f)
        assertTrue(rects[1].right > rects[1].left)
    }

    @Test
    fun `decorRects outside all lines is empty`() {
        val drawer = LineWindowDrawer()
        val lines = listOf(line("abc", 0..2, charBase = 10))
        assertTrue(drawer.decorRects(lines, chapter = 1, range = 0..5).isEmpty())
        assertTrue(drawer.decorRects(lines, chapter = 1, range = 20..30).isEmpty())
        assertTrue(drawer.decorRects(lines, chapter = 1, range = 10..9).isEmpty())
    }

    // ---- 落墨：底色垫在字下，划线在字下更靠下 ----

    private val contentLeft = 20f

    private fun render(lines: List<DrawLine>, width: Int = 360, height: Int = 160): Bitmap {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(
            canvas,
            contentLeft,
            lines,
            Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()),
        )
        return bitmap
    }

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    private fun isPink(argb: Int): Boolean =
        channel(argb, 16) >= 200 && channel(argb, 8) in 60..200 && channel(argb, 0) in 60..200

    private fun isBlue(argb: Int): Boolean {
        val b = channel(argb, 0)
        return b >= 100 && b - channel(argb, 16) >= 60 && b - channel(argb, 8) >= 60
    }

    private fun isDark(argb: Int): Boolean =
        channel(argb, 16) < 96 && channel(argb, 8) < 96 && channel(argb, 0) < 96

    private fun findDarkPixel(bmp: Bitmap): Pair<Int, Int>? {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (isDark(px.getColor(x, y))) return x to y
            }
        }
        return null
    }

    private fun rowsOf(bmp: Bitmap, pred: (Int) -> Boolean): IntRange? {
        val px = requireNotNull(bmp.peekPixels())
        var lo = -1
        var hi = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (pred(px.getColor(x, y))) {
                    if (lo < 0) lo = y
                    hi = y
                    break
                }
            }
        }
        return if (lo < 0) null else lo..hi
    }

    @Test
    fun `highlight is below glyphs and underline is below baseline`() {
        val plain = line("HHHH", 0..3).copy(charBase = 100)
        val withDecor = plain.copy(
            decorRuns = listOf(
                DecorRange(102, 104, DecorKind.BACKGROUND, 0x80FF0000.toInt()),
                DecorRange(102, 104, DecorKind.UNDERLINE, 0xFF0000FF.toInt()),
            ),
        )

        val plainBmp = render(listOf(plain))
        val decorated = render(listOf(withDecor))

        // 底色铺在行框内、字没有被盖住：从「无装饰」渲染里找一个字形暗像素，装饰版同点仍暗。
        val dark = requireNotNull(findDarkPixel(plainBmp)) { "测试字体没有落下字形" }
        val decoratedDark = requireNotNull(decorated.peekPixels()).getColor(dark.first, dark.second)
        assertTrue("高亮不能盖住字形：$decoratedDark", isDark(decoratedDark))

        // 区间外仍是白底（高亮没有铺满整行）。
        val rects = LineWindowDrawer().decorRects(listOf(withDecor), 0, 102..103)
        assertEquals(1, rects.size)
        val r = rects.single()
        val px = requireNotNull(decorated.peekPixels())
        val insideX = (contentLeft + (r.left + r.right) / 2f).toInt().coerceIn(0, decorated.width - 1)
        val outsideX = (contentLeft + r.right + 10f).toInt().coerceIn(0, decorated.width - 1)
        assertTrue("高亮区间内应见粉色", isPink(px.getColor(insideX, 2)))
        assertEquals("高亮区间外应仍是白底", Color.WHITE, px.getColor(outsideX, 2))

        // 划线在基线下方，且颜色是标注色（蓝），不是文字墨色。
        val blueRows = requireNotNull(rowsOf(decorated, ::isBlue)) { "标注划线没有画出来" }
        val baseline = baselineY()
        assertTrue("划线应在基线下方：rows=$blueRows baseline=$baseline", blueRows.first > baseline)
        val blue = px.getColor((contentLeft + (r.left + r.right) / 2f).toInt(), blueRows.first)
        assertTrue("划线颜色应是标注蓝：$blue", isBlue(blue))
    }

    /**
     * **选区矩形与落墨同源**：西文行的横向几何必须走 kerning 嫁接后的落墨轨
     * （[lineGlyphTrack]），不能读 [LineAligner.Placement.xs]。
     *
     * 真机缺陷：长按选词后高亮整体偏右，行首那个字母「没选上」、右侧标点「被选上」；
     * 复制出来的文字却是对的（区间对、只是矩形位置错）。成因是落墨用簇位轨（含 kerning
     * 收紧），矩形还用 Aligner 的自然轨 ⇒ 矩形相对墨迹右移「从行首到该字的累计收紧量」，
     * 真书实测 10~17px（大半个字宽），且**随字在行内的位置增大**、无 kerning 的行恰好看不出
     * —— 用户看到的正是「有时对、有时偏」。
     *
     * 锁法：像素级。渲染一行真字形，把「整行区间」的矩形右缘与墨迹右缘比（误差只允许
     * 右留白那个量级），并先断言这一行**确有**收紧（否则本锁在无 kerning 的机器上假绿）。
     */
    @Test
    fun `选区矩形跟随落墨轨而不是 Aligner 自然轨`() {
        val text = "AVATAR To Ye Wa Tuesday value pairs"
        val fs = 42f
        val l = DrawLine(
            text = text,
            range = 0..text.length - 1,
            yTop = 0,
            yBottom = 60,
            alignment = TextAlign.LEFT,
            fontSizePx = fs,
            lineHeightRatio = 1.5f,
            tag = "p",
            families = listOf("serif"),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = 1400,
            charBase = 0,
        )
        val placement = LineAligner().align(
            text, 0..text.length - 1, fs, 1400f, 0f, "p", listOf("serif"), 400, false, false,
            emptyList(), TextAlign.LEFT, 0f, isLastLine = false, hyphenAtEnd = false,
        )
        val graft = lineGlyphTrack(l, placement, KerningClusterTable(), 0, text.length)
        val shifted = graft != null &&
            kotlin.math.abs(graft.xs[text.length - 1] - placement.xs[text.length - 1]) > 5f
        assertTrue("语料必须真产生 kerning 收紧，否则本锁假绿（无 kerning 的机器）", shifted)
        assertTrue("前提：收紧后末字必须比自然轨更靠左", graft!!.xs[text.length - 1] < placement.xs[text.length - 1])

        val bmp = render(listOf(l), width = 1400, height = 120)
        val px = requireNotNull(bmp.peekPixels())
        // 墨迹左右缘：整行任意暗像素的 x 极值。
        var inkLeft = Int.MAX_VALUE
        var inkRight = Int.MIN_VALUE
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (isDark(px.getColor(x, y))) {
                    if (x < inkLeft) inkLeft = x
                    if (x > inkRight) inkRight = x
                }
            }
        }
        assertTrue("测试字体没有落下字形", inkRight > inkLeft)

        val r = LineWindowDrawer().decorRects(listOf(l), 0, 0..text.length - 1).single()
        val rectLeft = contentLeft + r.left
        val rectRight = contentLeft + r.right

        // 左缘天然对（首个槽不受累计收紧影响），右缘才是这条缺陷的判据。
        assertTrue(
            "矩形右缘 ${rectRight} 与墨迹右缘 ${inkRight} 相差 ${rectRight - inkRight}px（应只有右留白的量级）",
            rectRight - inkRight in -8f..6f,
        )
        assertTrue("矩形左缘 ${rectLeft} 不应偏离墨迹左缘 ${inkLeft}", kotlin.math.abs(rectLeft - inkLeft) < 8f)
        // 反向判据：若退回读 placement.xs，右缘会整整多出一个累计收紧量。
        assertTrue(
            "矩形右缘不得退回到自然轨（${contentLeft + placement.xs[text.length - 1] + placement.advs[text.length - 1]}）",
            rectRight < contentLeft + placement.xs[text.length - 1] + placement.advs[text.length - 1] - 5f,
        )
        // 行中间的词也一样：矩形左右缘就是落墨轨上该词的起止（旧实现会整体右移一个累计收紧量）。
        val ws = text.indexOf("value")
        val word = LineWindowDrawer().decorRects(listOf(l), 0, ws..ws + 4).single()
        assertEquals("词左缘必须是落墨轨的起笔", graft.xs[ws], word.left, 0.01f)
        assertEquals(
            "词右缘必须是落墨轨上该词末字的右缘",
            graft.xs[ws + 4] + placement.advs[ws + 4],
            word.right,
            0.01f,
        )
    }

    /** 与 `LineWindowDrawer.baselineY` 同源的基线（同一字体度量 + 同一个 Aligner 入口）。 */
    private fun baselineY(): Float {
        val fm = requireNotNull(
            SkiaRunMeasurer().faceForCp('H'.code, "p", emptyList(), 400, false, false, 20f),
        ) { "默认面无度量" }.metrics
        return LineAligner().baselineOffset(
            yTop = 0f,
            fontSizePx = 20f,
            lineHeightRatio = 2f,
            ascentPx = -fm.ascent,
            descentPx = fm.descent,
        )
    }
}
