package orilumn.reader.engine.skia

import orilumn.reader.engine.css.DecorationLine
import orilumn.reader.engine.css.DecorationStyle
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.DecorationPaint
import orilumn.reader.engine.laying.TextDecorationRun
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本装饰（引擎欠账第三刀）的**无头像素锁**。
 *
 * 与 [InlineBoxPaintTest] 同一套办法：同一个 `LineWindowDrawer` 软件栅格到 `Bitmap`
 * （与 Android 软件绘制目标同构），A/B 两个输入**就在同一个测试二进制里**。
 *
 * 判据全部落在「**线的位置与形状**」上：
 * - **线型归位（E3）**：`line-through` 必须画在**文字腰部**、`underline` 必须画在**基线下方**、
 *   `overline` 在**文字墨迹之上** —— 旧实现把删除线当下划线画，第一条就是它的反向锁；
 * - **颜色**：线用**声明色**（`text-decoration-color`），不是行墨色；
 * - **线型样式**：`double` 两行、`dotted`/`dashed` 断开成多段、`wavy` 的纵向跨度大于实线；
 * - **skip-ink（E4）**：下划线在**降部**（`g`）处断开、在无降部的字（`m`）下不断，
 *   且按规范**不作用于 `line-through`**；
 * - **零回归**：挂上装饰不移动任何字形（文字墨迹的左右缘逐值不变）。
 *
 * 字体度量取本机默认面（同 `InlineBoxPaintTest`：装机候选面不同，故这里只锁**相对关系**，
 * 「三种线型各在其位」这类关系与具体面无关）。
 */
class TextDecorationPaintTest {

    private val contentLeft = 20f
    private val contentWidth = 320
    private val textInk = 0xFF000000.toInt()

    private fun deco(
        line: DecorationLine,
        colorHex: String? = "#ff0000",
        style: DecorationStyle = DecorationStyle.SOLID,
        thicknessPx: Float? = 3f,
        skipInk: Boolean = false,
    ) = DecorationPaint(
        lines = setOf(line),
        colorHex = colorHex,
        style = style,
        thicknessPx = thicknessPx,
        skipInk = skipInk,
        fontSizePx = 16f,
        families = emptyList(),
        weight = 400,
        italic = false,
        monospace = false,
    )

    private fun line(text: String, vararg paints: DecorationPaint): DrawLine {
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
            inkColor = textInk,
            decorationRuns = if (paints.isEmpty()) {
                emptyList()
            } else {
                listOf(TextDecorationRun(0, text.length, paints.toList()))
            },
        )
    }

    private fun render(lines: List<DrawLine>, width: Int = 400, height: Int = 200): Bitmap {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, contentLeft, lines, Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    // ---- 像素分类与小工具 ----

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    /**
     * 「红/绿」判据用**相对优势**而非绝对通道值：装饰线常常只有 1px 宽，抗锯齿后一行只有
     * 六成不透明度（红 255 / 绿 102），绝对阈值会把细线整条判成「没画」。
     */
    private fun isRed(argb: Int): Boolean {
        val r = channel(argb, 16)
        return r >= 80 && r - channel(argb, 8) >= 48 && r - channel(argb, 0) >= 48
    }

    private fun isGreen(argb: Int): Boolean {
        val g = channel(argb, 8)
        return g >= 80 && g - channel(argb, 16) >= 48 && g - channel(argb, 0) >= 48
    }

    private fun isTextInk(argb: Int) = channel(argb, 16) < 96 && channel(argb, 8) < 96 && channel(argb, 0) < 96

    /**
     * 「任意落墨」（含浅抗锯齿边缘）：只用于**位置**判据（字在哪儿）。
     * 严格阈值 [isTextInk] 会把字的上/下抗锯齿行剔掉（`H` 的收笔边缘只有三成覆盖），
     * 于是「删除线在字腰、下划线在字下」会比字形实际范围偏一两个像素 —— 位置判据不能受这个影响。
     */
    private fun isAnyInk(argb: Int) =
        channel(argb, 16) < 200 || channel(argb, 8) < 200 || channel(argb, 0) < 200

    /** 命中像素所在行区间（null = 一个都没有）。 */
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

    /** 命中像素所在列区间（null = 一个都没有）。 */
    private fun colsOf(bmp: Bitmap, pred: (Int) -> Boolean): IntRange? {
        val px = requireNotNull(bmp.peekPixels())
        var lo = -1
        var hi = -1
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                if (pred(px.getColor(x, y))) {
                    if (lo < 0) lo = x
                    hi = x
                    break
                }
            }
        }
        return if (lo < 0) null else lo..hi
    }

    /** 某一行里命中像素的**连续段数**（点线/虚线/skip-ink 断开都会 > 1）。 */
    private fun runsInRow(bmp: Bitmap, y: Int, pred: (Int) -> Boolean): Int {
        val px = requireNotNull(bmp.peekPixels())
        var runs = 0
        var inRun = false
        for (x in 0 until bmp.width) {
            val hit = pred(px.getColor(x, y))
            if (hit && !inRun) runs++
            inRun = hit
        }
        return runs
    }

    /** 命中像素占的**横向行带数**（`double` 两条线 = 2 带）。 */
    private fun rowBands(bmp: Bitmap, pred: (Int) -> Boolean): Int {
        val px = requireNotNull(bmp.peekPixels())
        var bands = 0
        var inBand = false
        for (y in 0 until bmp.height) {
            var hitRow = false
            for (x in 0 until bmp.width) {
                if (pred(px.getColor(x, y))) {
                    hitRow = true
                    break
                }
            }
            if (hitRow && !inBand) bands++
            inBand = hitRow
        }
        return bands
    }

    /** 装饰线的行区间；一个像素都没有 = 断言失败（装饰线根本没画出来）。 */
    private fun redRows(bmp: Bitmap): IntRange =
        requireNotNull(rowsOf(bmp, ::isRed)) { "装饰线一个像素都没画出来" }

    /** 取装饰线横带里居中的一行（避开上下抗锯齿边缘，断段判据看这一行）。 */
    private fun midRow(rows: IntRange): Int = rows.first + (rows.last - rows.first) / 2

    /**
     * 本行基线 y —— 与绘制侧**同一个入口**取（`SkiaRunMeasurer.faceForCp` 的度量 +
     * [LineAligner.baselineOffset]），故测试里的「基线上/下方」与落墨口径同源。
     */
    private fun baselineY(): Float {
        val fm = requireNotNull(
            SkiaRunMeasurer().faceForCp('H'.code, "p", emptyList(), 400, false, false, 16f),
        ) { "默认面无度量" }.metrics
        return LineAligner().baselineOffset(
            yTop = 0f,
            fontSizePx = 16f,
            lineHeightRatio = 2f,
            ascentPx = -fm.ascent,
            descentPx = fm.descent,
        )
    }

    /** 约 1px 的抗锯齿/取整宽容度（字形墨迹与度量在同一条线上争 1px）。 */
    private val aaSlack = 1f

    // ---- E3 核心：三种线型各在其位 ----

    @Test
    fun `删除线画在文字腰部而下划线画在基线下方`() {
        // 'H' 有升部：删除线上下都还有字形墨迹 ⇒「跨在字上」这条也能查。
        val text = "HHHHHH"
        val baseline = baselineY()
        val textRows = requireNotNull(rowsOf(render(listOf(line(text))), ::isAnyInk))

        val underline = redRows(render(listOf(line(text, deco(DecorationLine.UNDERLINE)))))
        val strike = redRows(render(listOf(line(text, deco(DecorationLine.LINE_THROUGH)))))

        assertTrue(
            "删除线必须整个在基线之上（文字腰部），实得行 $strike，基线 y=$baseline",
            strike.last < baseline + aaSlack,
        )
        assertTrue(
            "下划线必须整个在基线之下，实得行 $underline，基线 y=$baseline",
            underline.first > baseline - aaSlack,
        )
        assertTrue("两条线不得同高（旧实现把删除线当下划线画）", strike.last < underline.first)
        assertTrue(
            "删除线应落在字形墨迹之内（字腰），实得行 $strike，文字墨迹行 $textRows",
            textRows.first < strike.first && strike.last <= textRows.last,
        )
        assertTrue(
            "下划线应整个落在字形墨迹之下（与删除线分居基线两侧），实得行 $underline，文字墨迹行 $textRows",
            underline.first > textRows.last,
        )
    }

    @Test
    fun `上划线画在文字墨迹之上`() {
        val text = "HHHHHH"
        val textRows = requireNotNull(rowsOf(render(listOf(line(text))), ::isAnyInk))
        val overline = redRows(render(listOf(line(text, deco(DecorationLine.OVERLINE)))))

        assertTrue(
            "上划线必须在升部之上，实得行 $overline，文字墨迹行 $textRows",
            overline.last <= textRows.first + aaSlack,
        )
        assertTrue(
            "上划线必须在基线上方",
            overline.last < baselineY(),
        )
    }

    @Test
    fun `三种线型可同时存在且各在其位`() {
        val text = "mmmmmm"
        val both = render(
            listOf(
                line(
                    text,
                    deco(DecorationLine.UNDERLINE),
                    deco(DecorationLine.OVERLINE),
                    deco(DecorationLine.LINE_THROUGH),
                ),
            ),
        )
        assertEquals("上/腰/下三条线各自成带", 3, rowBands(both, ::isRed))
        val rows = redRows(both)
        assertTrue("三线应铺开一段纵向距离，实得 $rows", rows.last - rows.first >= 6)
    }

    // ---- 颜色：用声明色 ----

    @Test
    fun `装饰线用声明色而非行墨色`() {
        val text = "mmmmmm"
        val green = render(listOf(line(text, deco(DecorationLine.UNDERLINE, colorHex = "#00ff00"))))
        assertTrue("绿色装饰线必须出现", rowsOf(green, ::isGreen) != null)
        assertNull("声明了绿色就不该再画红色", rowsOf(green, ::isRed))
    }

    @Test
    fun `无声明色的装饰线落回行墨色`() {
        val text = "mmmmmm"
        val control = render(listOf(line(text)))
        val viaInk = render(listOf(line(text, deco(DecorationLine.UNDERLINE, colorHex = null))))
        val controlText = requireNotNull(rowsOf(control, ::isTextInk))
        val viaInkText = requireNotNull(rowsOf(viaInk, ::isTextInk))
        assertTrue(
            "无色声明时下划线用行墨色画 ⇒ 墨迹纵向范围向下延伸（$controlText → $viaInkText）",
            viaInkText.last > controlText.last,
        )
    }

    // ---- 线型样式 ----

    @Test
    fun `双线画成上下两条`() {
        // 线宽取 6px：两条细线之间留出**真正的空行**，才能按行带数区分（3px 时两线在行上相接）。
        val text = "mmmmmm"
        val solid = render(listOf(line(text, deco(DecorationLine.UNDERLINE, thicknessPx = 6f))))
        val double = render(listOf(line(text, deco(DecorationLine.UNDERLINE, style = DecorationStyle.DOUBLE, thicknessPx = 6f))))

        assertEquals("实线只有一条带", 1, rowBands(solid, ::isRed))
        assertEquals("双线有两条带（中间空行隔开）", 2, rowBands(double, ::isRed))
        val s = redRows(solid)
        val d = redRows(double)
        assertTrue("双线纵跨应不小于实线（实线 $s / 双线 $d）", d.last - d.first >= s.last - s.first)
    }

    @Test
    fun `点线与虚线断开成多段`() {
        val text = "mmmmmmmmmmmmmmmmmmmm"
        val solid = render(listOf(line(text, deco(DecorationLine.UNDERLINE))))
        val dotted = render(listOf(line(text, deco(DecorationLine.UNDERLINE, style = DecorationStyle.DOTTED))))
        val dashed = render(listOf(line(text, deco(DecorationLine.UNDERLINE, style = DecorationStyle.DASHED))))

        val y = midRow(redRows(solid))
        assertEquals("实线连续一段", 1, runsInRow(solid, y, ::isRed))
        assertTrue("点线必须断开，实得 ${runsInRow(dotted, y, ::isRed)} 段", runsInRow(dotted, y, ::isRed) > 1)
        assertTrue("虚线必须断开，实得 ${runsInRow(dashed, y, ::isRed)} 段", runsInRow(dashed, y, ::isRed) > 1)
    }

    @Test
    fun `波浪线的纵向跨度大于实线`() {
        val text = "mmmmmmmmmmmmmmmmmmmm"
        val solid = redRows(render(listOf(line(text, deco(DecorationLine.UNDERLINE)))))
        val wavy = redRows(render(listOf(line(text, deco(DecorationLine.UNDERLINE, style = DecorationStyle.WAVY)))))
        assertTrue(
            "波浪上下起伏 ⇒ 纵跨明显大于实线（实线 $solid / 波浪 $wavy）",
            (wavy.last - wavy.first) > (solid.last - solid.first) + 1,
        )
    }

    // ---- skip-ink ----

    @Test
    fun `skip-ink 在降部处断开下划线`() {
        // 交替 `m`/`g`：`m` 段保留下划线、`g` 的降部处断开 ⇒ 一条线被切成多段。
        val mixed = "mgmgmgmgmgmg"
        val noDescender = "mmmmmmmmmmmm"

        val off = render(listOf(line(mixed, deco(DecorationLine.UNDERLINE, skipInk = false))))
        val on = render(listOf(line(mixed, deco(DecorationLine.UNDERLINE, skipInk = true))))
        val onFlat = render(listOf(line(noDescender, deco(DecorationLine.UNDERLINE, skipInk = true))))

        val yOff = midRow(redRows(off))
        val yOn = midRow(redRows(on))
        val yFlat = midRow(redRows(onFlat))
        assertEquals("关掉 skip-ink 的连续下划线不得断开", 1, runsInRow(off, yOff, ::isRed))
        assertTrue(
            "打开 skip-ink 后下划线必须在 g 的降部处断开，实得 ${runsInRow(on, yOn, ::isRed)} 段",
            runsInRow(on, yOn, ::isRed) > 1,
        )
        assertEquals(
            "无降部的字（m）不得被切碎（否则整段下划线都会断）",
            1,
            runsInRow(onFlat, yFlat, ::isRed),
        )
    }

    @Test
    fun `skip-ink 不作用于删除线`() {
        val text = "gggggggggggg"
        val on = render(listOf(line(text, deco(DecorationLine.LINE_THROUGH, skipInk = true, thicknessPx = 2f))))
        assertEquals("规范：skip-ink 只作用于下划线/上划线", 1, runsInRow(on, midRow(redRows(on)), ::isRed))
    }

    // ---- 零回归：装饰不移动字形 ----

    @Test
    fun `挂上装饰不移动任何字形`() {
        val text = "mmmmmmmmmm"
        val control = render(listOf(line(text)))
        val painted = render(
            listOf(
                line(
                    text,
                    deco(DecorationLine.UNDERLINE),
                    deco(DecorationLine.LINE_THROUGH),
                    deco(DecorationLine.OVERLINE),
                ),
            ),
        )
        assertEquals(
            "文字墨迹左缘不得移动",
            requireNotNull(colsOf(control, ::isTextInk)),
            requireNotNull(colsOf(painted, ::isTextInk)),
        )
        assertEquals(
            "文字墨迹行范围不得移动",
            requireNotNull(rowsOf(control, ::isTextInk)),
            requireNotNull(rowsOf(painted, ::isTextInk)),
        )
    }
}
