package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.skia.SkiaParagraphBreaker
import orilumn.reader.engine.skia.SkParagraphFactory
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LineWindowDrawerTest {

    private val contentLeft = 20f
    private val contentWidth = 320

    private fun lineHeightPx(fontSizePx: Float, ratio: Float): Int =
        (fontSizePx * ratio).toInt()

    private fun drawAndScan(
        lines: List<DrawLine>,
        width: Int = 400,
        height: Int = 200,
        clip: Rect? = null,
    ): Bitmap {
        // 说明：本机 awt runtimes 里 SkParagraph 的 _nPaint 对 Surface 的 raster canvas 是空操作
        //（probe 验证过：同一段落画到 Bitmap 背衬 canvas 即有墨）；软件栅格化走 Bitmap→Canvas
        // 这一合法路径，Android 上的 `Bitmap`/软件绘制目标与此同构。
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, contentLeft, lines, clip)
        return bitmap
    }

    /** 在内容区横向条带 [xLo,xHi) 内有墨的 y 行序。 */
    private fun inkRows(bmp: Bitmap, xLo: Int, xHi: Int, height: Int): Set<Int> {
        val px = requireNotNull(bmp.peekPixels())
        val rows = linkedSetOf<Int>()
        for (y in 0 until height) {
            for (x in xLo until xHi) {
                if (px.getColor(x, y) != Color.WHITE) {
                    rows += y
                    break
                }
            }
        }
        return rows
    }

    private fun bandHasInk(bmp: Bitmap, yLo: Int, yHi: Int, xLo: Int, xHi: Int): Boolean {
        val px = requireNotNull(bmp.peekPixels())
        for (y in yLo until yHi) {
            for (x in xLo until xHi) {
                if (px.getColor(x, y) != Color.WHITE) return true
            }
        }
        return false
    }

    /** 条带 [xLo,xHi) × [yLo,yHi) 内有墨的最小 x（无墨返回 Int.MAX_VALUE）。 */
    private fun minInkX(bmp: Bitmap, yLo: Int, yHi: Int, xLo: Int, xHi: Int): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in xLo until xHi) {
            for (y in yLo until yHi) {
                if (px.getColor(x, y) != Color.WHITE) return x
            }
        }
        return Int.MAX_VALUE
    }

    /** 与 LineWindowDrawer 同法实测 marker 文本宽度（同 paragraphStyle 的 SkParagraph）。 */
    private fun measureMarker(text: String): Int {
        val style = SkParagraphFactory.paragraphStyle(
            TextAlign.LEFT, 16f, 2f, "p", emptyList(), 400, false, false, 0f,
        )
        val p = ParagraphBuilder(style, SkParagraphFactory.defaultCollection()).addText(text).build()
        try {
            p.layout(Float.MAX_VALUE)
            return (p.lineMetrics.getOrNull(0)?.right ?: 0.0).roundToInt().coerceAtLeast(1)
        } finally {
            p.close()
        }
    }

    private fun sampleLine(text: String, alignment: TextAlign): DrawLine {
        val fontSizePx = 16f
        return DrawLine(
            text = text,
            range = 0 until text.length,
            yTop = 0,
            yBottom = lineHeightPx(fontSizePx, 2f),
            alignment = alignment,
            fontSizePx = fontSizePx,
            lineHeightRatio = 2f,
            tag = "p",
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = contentWidth,
        )
    }

    // 每个画出的行都是单一字形行（无二次折行），且行数 == 区间数。
    private fun assertSingleRowPerDrawLine(lines: List<DrawLine>, widthPx: Int) {
        for (line in lines) {
            val style = SkParagraphFactory.paragraphStyle(
                line.alignment, line.fontSizePx, line.lineHeightRatio,
                line.tag, line.families, line.weight, line.italic, line.monospace, line.letterSpacingEm,
            )
            val paragraph = ParagraphBuilder(style, SkParagraphFactory.defaultCollection())
                .addText(line.text.substring(line.range))
                .build()
            try {
                paragraph.layout(widthPx.coerceAtLeast(1).toFloat())
                assertEquals("每 DrawLine 必须正好画一行", 1, paragraph.lineNumber)
            } finally {
                paragraph.close()
            }
        }
    }

    @Test
    fun genericFamiliesResolveToConcreteCandidates() {
        // CSS 通用族在桌面 FontMgr 下不可直接匹配（matchNull → 空字形、无法栅格），必须被展开成
        // 真实候选族（个别候选在特定平台未安装时由 FontCollection 按字形跳过）；且按「普通浏览器
        // 通用族回退」排列：serif 含 CJK 衬线候选、sans-serif 含 CJK 无衬线候选，CJK 字形经系统
        // CJK 衬线/无衬线落到与浏览器一致的结果。空栈回到默认实族（几何基线不漂移）。
        val fm = SkParagraphFactory.defaultFontMgr()
        val serif = SkParagraphFactory.resolveFamilies("p", listOf("serif"), false, fm)
        val sans = SkParagraphFactory.resolveFamilies("p", listOf("sans-serif"), false, fm)
        val mono = SkParagraphFactory.resolveFamilies("code", emptyList(), false, fm)
        val default = SkParagraphFactory.resolveFamilies("p", emptyList(), false, fm)
        for (fams in listOf(serif, sans, mono, default)) {
            assertTrue("通用族必须展开成至少一个实族", fams.isNotEmpty())
            for (f in fams) {
                assertFalse(
                    "不得把通用关键字原样传给 FontCollection（会画不出字形）",
                    f.lowercase() in setOf("serif", "sans-serif", "monospace", "cursive", "fantasy"),
                )
            }
        }
        assertTrue("serif 通用族应含 CJK 衬线候选", serif.any { it.contains("Songti", ignoreCase = true) || it.contains("STSong", ignoreCase = true) })
        assertTrue("serif 通用族应含 Noto CJK 衬线候选（认不出通用名的平台靠它）", serif.any { it.contains("Noto Serif CJK", ignoreCase = true) })
        assertTrue("sans-serif 通用族应含 Noto CJK 无衬线", sans.any { it.contains("Noto Sans CJK", ignoreCase = true) })
        assertTrue("sans-serif 通用族应含 CJK 无衬线候选", sans.any { it.contains("PingFang", ignoreCase = true) })
        assertEquals("空栈应落成单一默认实族（几何基线不漂移）", 1, default.size)
    }

    @Test
    fun codeStackHonorsNonEmptyFamilies() {
        // 回归“无法修改字体（代码槽）”：代码族非空栈即 honor（书内 code 字体与用户代码槽），
        // 空栈才回 monospace 默认。
        val fm = SkParagraphFactory.defaultFontMgr()
        val named = SkParagraphFactory.resolveFamilies("code", listOf("霞鹜文楷"), false, fm)
        assertTrue("具名栈首名必须保留", named.first() == "霞鹜文楷")
        val mono = SkParagraphFactory.resolveFamilies("pre", emptyList(), true, fm)
        assertTrue("空栈仍回 monospace", mono.isNotEmpty())
    }

    @Test
    fun rustBodyStackFallsToCjkSerifAndRasterizes() {
        // 回归：Rust 程序设计语言 正文 `font-family: "思源宋体 VF", …, "Times New Roman", serif`
        // 在未装思源宋体的机器上必须沿整栈走到尾部 `serif`（CJK 衬线），而不是只取首名退化成默认黑体。
        val rustBody = listOf("思源宋体 VF", "思源宋体 SC", "思源宋体 CN", "思源宋体", "DK-SONGTI", "STSong", "SimSong", "Times New Roman", "serif")
        val fm = SkParagraphFactory.defaultFontMgr()
        val resolved = SkParagraphFactory.resolveFamilies("p", rustBody, false, fm)
        assertTrue("尾部 serif 须展开出 CJK 衬线候选（Songti SC / STSong）", resolved.any { it.contains("Songti", ignoreCase = true) || it.contains("STSong", ignoreCase = true) })
        // 栅格化冒烟：经整栈按字形回退，CJK 应有墨（修复前只取首名「思源宋体 VF」→ 未装 → 默认无衬线）。
        val line = DrawLine(
            text = "中文测试", range = 0 until 4, yTop = 0, yBottom = lineHeightPx(16f, 2f),
            alignment = TextAlign.LEFT, fontSizePx = 16f, lineHeightRatio = 2f,
            tag = "p", families = rustBody, weight = 400, italic = false,
            monospace = false, letterSpacingEm = 0f, lineWidthPx = contentWidth,
        )
        val bmp = drawAndScan(listOf(line))
        assertTrue("整栈回退应让 CJK 字形着墨", bandHasInk(bmp, 0, 40, contentLeft.toInt() + 1, contentLeft.toInt() + contentWidth))
    }

    @Test
    fun firstLineIndentLeavesLeftGutterBlank() {
        // 首行缩进绘制：缩进带内无墨，文本区有墨；无缩进对照组 gutter 有墨。
        val plain = sampleLine("Hello world", TextAlign.LEFT)
        val plainBmp = drawAndScan(listOf(plain))
        assertTrue("对照：无缩进行首有墨", bandHasInk(plainBmp, 0, 32, contentLeft.toInt(), contentLeft.toInt() + 30))
        val indented = plain.copy(firstLineIndentPx = 60f)
        val bmp = drawAndScan(listOf(indented))
        assertFalse(
            "缩进带 [20,80) 内须无墨",
            bandHasInk(bmp, 0, 32, contentLeft.toInt(), contentLeft.toInt() + 60),
        )
        assertTrue(
            "文本落在缩进之后",
            bandHasInk(bmp, 0, 32, contentLeft.toInt() + 60, contentLeft.toInt() + contentWidth),
        )
    }

    @Test
    fun baselineShiftMovesInkVertically() {
        // P1-2: 上标位移（+0.5em）须把字形墨迹抬高，基线不动（对照组同行同字）。
        val plain = sampleLine("H", TextAlign.LEFT)
        val xLo = contentLeft.toInt()
        val plainTop = inkRows(drawAndScan(listOf(plain)), xLo, xLo + 60, 200).minOrNull()
            ?: error("对照行须有墨")
        val shifted = plain.copy(
            baselineShifts = listOf(orilumn.reader.engine.laying.BaselineShift(0, 1, 0.5f)),
        )
        val shiftedTop = inkRows(drawAndScan(listOf(shifted)), xLo, xLo + 60, 200).minOrNull()
            ?: error("位移行须有墨")
        assertTrue("上标墨迹须更高（行首 $plainTop → $shiftedTop）", shiftedTop < plainTop - 2)
    }

    @Test
    fun stackAbsoluteYFromBreakerRanges() {
        // 由 SkiaParagraphBreaker 的区间堆成绝对 Y 窗口，逐带检查落墨，且窗口下无越界墨迹。
        val text = "line one\nline two\nline three"
        val breaker = SkiaParagraphBreaker(0.02f)
        val broken = breaker.breakLines(text, 16f, 2f, contentWidth, TextAlign.LEFT, "p", emptyList(), 400, false, false)
        assertEquals(3, broken.size)
        val lineHeight = lineHeightPx(16f, 2f)
        val drawLines = broken.mapIndexed { i, l ->
            DrawLine(
                text = text, range = l.range,
                yTop = i * lineHeight, yBottom = (i + 1) * lineHeight,
                alignment = TextAlign.LEFT, fontSizePx = 16f, lineHeightRatio = 2f,
                tag = "p", families = emptyList(), weight = 400, italic = false,
                monospace = false, letterSpacingEm = 0.02f, lineWidthPx = contentWidth,
            )
        }
        assertSingleRowPerDrawLine(drawLines, contentWidth)

        val bmp = drawAndScan(drawLines, height = 200)
        val scanLo = contentLeft.toInt()
        val scanHi = contentLeft.toInt() + 200
        val inkY = inkRows(bmp, scanLo, scanHi, 200)
        assertTrue("至少三行都应有字形墨迹", inkY.isNotEmpty())
        val (lo, hi) = inkY.first() to inkY.last()
        assertTrue("墨迹起点应在第一行带内", lo in 0 until lineHeight)
        val lastBottom = 3 * lineHeight
        assertTrue("墨迹不得越过最后一行底线", hi < lastBottom)
        for (band in 0 until 3) {
            val bandY = (band * lineHeight until (band + 1) * lineHeight).any { it in inkY }
            assertTrue("行带 #$band 应着墨（绝对Y落位正确）", bandY)
        }
        for (band in 0 until 3) {
            assertTrue("行带 #$band 全带扫描应有着墨", bandHasInk(bmp, band * lineHeight, (band + 1) * lineHeight, scanLo, scanHi))
        }
    }

    @Test
    fun lastLineInclusiveRangeNeverThrows() {
        // 回归（平板 0.144.6 首装崩回书架）：增量路径曾把开区间 end 直接拼成 s..e，
        // 末行 substring 越界（begin 111, end 151, length 150）崩 activity。
        // 绘制端钳位后：合法末行照画、超界区间只跳行不抛。
        val text = "a".repeat(150)
        val lineHeight = lineHeightPx(16f, 2f)
        fun line(range: IntRange, alignment: TextAlign = TextAlign.JUSTIFY) = DrawLine(
            text = text, range = range,
            yTop = 0, yBottom = lineHeight,
            alignment = alignment, fontSizePx = 16f, lineHeightRatio = 2f,
            tag = "p", families = emptyList(), weight = 400, italic = false,
            monospace = false, letterSpacingEm = 0f, lineWidthPx = contentWidth,
        )
        // 合法末行闭区间 + 历史错误形态（多含一位）+ 完全越界，三种都不许抛。
        drawAndScan(listOf(line(111 until 150)))
        drawAndScan(listOf(line(111..150)))
        drawAndScan(listOf(line(111..200)))
    }

    @Test
    fun colorAndFontRunsMergeAndInkInBand() {        // 行内着色 + 行内 face 同时存在（正文里的显色 `<code>`）：合并切段绘制不崩、颜色落在区间内、
        // 只画在行带内。无 runs 对照不得出现该颜色。
        val red = 0xFFFF0000.toInt()
        val text = "abCDef"
        val line = sampleLine(text, TextAlign.LEFT).copy(
            colorRuns = listOf(orilumn.reader.engine.css.ColorRun(1, 4, red)),
            fontRuns = listOf(orilumn.reader.engine.css.FontRun(2, 4, listOf("Courier"), "code", 400, false, true)),
        )
        val bmp = drawAndScan(listOf(line))
        assertTrue("合并段必须有着墨", bandHasInk(bmp, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth))
        val hit = findColorRect(bmp, red)
        assertTrue("着色段的红必须被画出（color+font 合并段）, hit=$hit", hit != null)
        // 着色区间 [1,4)；同一行无 runs 对照没有红。
        val plain = drawAndScan(listOf(sampleLine(text, TextAlign.LEFT)))
        assertTrue("无 runs 对照不得出现红", findColorRect(plain, red) == null)
    }

    @Test
    fun fontOnlyRunsInkAndFillBand() {
        // 只有行内 face 段（无着色）：整行照画，段内不得因换 style 而丢字形。
        val text = "abcXYdef"
        val line = sampleLine(text, TextAlign.LEFT).copy(
            fontRuns = listOf(orilumn.reader.engine.css.FontRun(3, 5, listOf("Courier"), "code", 400, false, true)),
        )
        val bmp = drawAndScan(listOf(line))
        assertTrue("仅 fontRuns 行仍须整行着墨", bandHasInk(bmp, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth))
        // LEFT 对齐下文本左缘恒在 contentLeft，与无 runs 对照一致（push/popStyle 不破坏布局）。
        val plain = drawAndScan(listOf(sampleLine(text, TextAlign.LEFT)))
        val inkXLo = minInkX(bmp, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth)
        val plainLo = minInkX(plain, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth)
        assertTrue("行内段不改变文本左缘（$plainLo vs $inkXLo）", (inkXLo - plainLo) <= 1)
    }

    @Test
    fun fontSizeRunsPaintInBandAtOwnSize() {
        // 行内字号段（`<small>/<sub>` 等）按段自己的字号整形绘制：整行照画、段内有墨，不崩、
        // 不因字号换 style 丢失字形（回归"行内字号整条丢失"的绘制侧）。
        val text = "abXdef"
        val line = sampleLine(text, TextAlign.LEFT).copy(
            fontRuns = listOf(orilumn.reader.engine.css.FontRun(2, 3, emptyList(), "small", 400, false, false, 8f)),
        )
        val bmp = drawAndScan(listOf(line))
        assertTrue("字号段行仍须整行着墨", bandHasInk(bmp, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth))
        // 无 runs 对照同位置左缘一致（push/popStyle 不破坏布局）。
        val plain = drawAndScan(listOf(sampleLine(text, TextAlign.LEFT)))
        val inkXLo = minInkX(bmp, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth)
        val plainLo = minInkX(plain, 0, lineHeightPx(16f, 2f), contentLeft.toInt(), contentLeft.toInt() + contentWidth)
        assertTrue("字号段不改变文本左缘（$plainLo vs $inkXLo）", (inkXLo - plainLo) <= 1)
    }

    private fun findColorRect(bmp: Bitmap, argb: Int): Pair<Int, Int>? {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (px.getColor(x, y) == argb) return x to y
            }
        }
        return null
    }

    @Test
    fun clipBuildsOnlyVisibleBand() {
        val fontSizePx = 16f
        val lineHeight = lineHeightPx(fontSizePx, 2f)
        val lines = (0 until 3).map { i ->
            DrawLine(
                text = "line $i", range = 0 until ("line $i").length,
                yTop = i * lineHeight, yBottom = (i + 1) * lineHeight,
                alignment = TextAlign.LEFT, fontSizePx = fontSizePx, lineHeightRatio = 2f,
                tag = "p", families = emptyList(), weight = 400, italic = false,
                monospace = false, letterSpacingEm = 0f, lineWidthPx = contentWidth,
            )
        }
        // 窗口只盖住第一个行带：其余行带裁剪。
        val bmp = drawAndScan(lines, height = 200, clip = Rect.makeLTRB(0f, 0f, 400f, lineHeight.toFloat()))
        val scanLo = contentLeft.toInt()
        val scanHi = contentLeft.toInt() + 200
        val inkY = inkRows(bmp, scanLo, scanHi, 200)
        assertTrue("第一行带内应有墨", inkY.isNotEmpty())
        assertTrue("窗口外行不得漏墨", inkY.all { it < lineHeight })
    }

    @Test
    fun alignmentPaintsWithinBandPerSemantics() {
        val left = sampleLine("alpha", TextAlign.LEFT)
        val center = sampleLine("alpha", TextAlign.CENTER)
        val justify = sampleLine("The quick brown fox jumps over the lazy dog and keeps running without stopping.", TextAlign.JUSTIFY)

        val bmpLeft = drawAndScan(listOf(left))
        assertTrue("LEFT 行左缘应起墨", bandHasInk(bmpLeft, 3, 14, contentLeft.toInt() + 1, contentLeft.toInt() + 30))

        // CENTER：内容左缘保持白，居中处有墨。
        val bmpCenter = drawAndScan(listOf(center))
        assertTrue("CENTER 行左缘保持空", !bandHasInk(bmpCenter, 3, 14, contentLeft.toInt() + 1, contentLeft.toInt() + 40))
        val cLo = contentLeft.toInt() + contentWidth / 2 - 60
        val cHi = contentLeft.toInt() + contentWidth / 2 + 60
        assertTrue("CENTER 行中段应有墨", bandHasInk(bmpCenter, 3, 14, cLo, cHi))

        // JUSTIFY：中部行（非末行）应铺满到右缘与左缘（与 Android kJustify 同向）。
        val bmpJust = drawAndScan(listOf(justify))
        assertTrue("JUSTIFY 行左缘应起墨", bandHasInk(bmpJust, 3, 14, contentLeft.toInt() + 1, contentLeft.toInt() + 30))
        assertTrue("JUSTIFY 行右缘应收墨", bandHasInk(bmpJust, 3, 14, contentLeft.toInt() + contentWidth - 30, contentLeft.toInt() + contentWidth - 1))
    }

    @Test
    fun xLeftShiftsTextRightOfTheContentBand() {
        // P1：xLeft>0 的行整体右移 —— 文本相对内容区从 contentLeft+xLeft 起画，左缘条带保持空白。
        val bmp = drawAndScan(listOf(sampleLine("alpha", TextAlign.LEFT).copy(xLeft = 40)))
        assertFalse("xLeft 前的条带应保持白", bandHasInk(bmp, 3, 14, contentLeft.toInt() + 1, contentLeft.toInt() + 39))
        assertTrue("xLeft 起的条带着墨", bandHasInk(bmp, 3, 14, contentLeft.toInt() + 41, contentLeft.toInt() + 120))
    }

    @Test
    fun outsideMarkerHangsInTheGutterLeftOfText() {
        // P1：OUTSIDE marker 在沟槽悬垂（文本左缘左侧 markerW+gap），文本左缘位置不变。
        val plain = drawAndScan(listOf(sampleLine("alpha", TextAlign.LEFT)))
        assertFalse("无 marker 时沟槽应空白", bandHasInk(plain, 2, 30, 0, contentLeft.toInt() - 1))

        val mk = ListMarkers.ListMarker(ListMarkers.Kind.DISC, "•", ListMarkers.Position.OUTSIDE, 1, 1)
        val bmp = drawAndScan(listOf(sampleLine("alpha", TextAlign.LEFT).copy(listMarker = mk)))
        assertTrue("OUTSIDE marker 应悬垂于沟槽", bandHasInk(bmp, 2, 30, 0, contentLeft.toInt() - 1))
        assertTrue("文本左缘应起墨（marker 不挤占文本）", bandHasInk(bmp, 3, 14, contentLeft.toInt() + 1, contentLeft.toInt() + 30))
    }

    @Test
    fun insideMarkerEmbedsInlineAndPushesTextRight() {
        // P1：INSIDE marker 行首内嵌，文本向右让 markerW+gap（近似平板 LeadingMarginSpan）。
        val plain = drawAndScan(listOf(sampleLine("alpha", TextAlign.LEFT)))
        val mk = ListMarkers.ListMarker(ListMarkers.Kind.DECIMAL, "1", ListMarkers.Position.INSIDE, 1, 1)
        val bmp = drawAndScan(listOf(sampleLine("alpha", TextAlign.LEFT).copy(listMarker = mk)))
        val mw = measureMarker("1")
        val gap = ListMarkers.markerGapPx(16f)
        // INSIDE 不悬垂沟槽。
        assertFalse("INSIDE marker 不得进沟槽", bandHasInk(bmp, 2, 30, 0, contentLeft.toInt() - 1))
        // marker 字形内嵌在行首。
        assertTrue("INSIDE marker 应内嵌于行首", bandHasInk(bmp, 2, 30, contentLeft.toInt(), contentLeft.toInt() + mw))
        // 文本被向右推 markerW+gap：原位置（无 marker 行同段）在 INSIDE 时空白，新位置有墨。
        assertTrue("无 marker 时同一 x 条带着墨", bandHasInk(plain, 2, 30, contentLeft.toInt() + 1, contentLeft.toInt() + mw + gap))
        assertTrue("INSIDE 后文本右移后的条带着墨", bandHasInk(bmp, 2, 30, contentLeft.toInt() + mw + gap + 2, contentLeft.toInt() + mw + gap + 60))
    }

    /** 有墨列的最大 x（无墨返回 Int.MIN_VALUE）。 */
    private fun maxInkX(bmp: Bitmap, yLo: Int, yHi: Int, xLo: Int, xHi: Int, height: Int): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in xHi - 1 downTo xLo) {
            for (y in yLo until yHi) {
                if (px.getColor(x, y) != Color.WHITE) return x
            }
        }
        return Int.MIN_VALUE
    }

    @Test
    fun textShadowOffsetsInk() {
        // P3-a: 行阴影按位移复画（此处 dx=10 无模糊）：阴影墨迹伸到字形右缘之外。
        val line = sampleLine("H", TextAlign.LEFT)
        val xLo = contentLeft.toInt()
        val xHi = xLo + 120
        val plainMax = maxInkX(drawAndScan(listOf(line)), 0, 40, xLo, xHi, 200)
        assertTrue("对照行须有墨", plainMax > xLo)
        val shadowed = drawAndScan(
            listOf(line.copy(textShadow = orilumn.reader.engine.css.TextShadow(10f, 0f, 0f, "#ff000000"))),
        )
        val shadowMax = maxInkX(shadowed, 0, 40, xLo, xHi, 200)
        assertTrue("阴影墨迹须右伸超字形 (plain=$plainMax shadow=$shadowMax)", shadowMax >= plainMax + 8)
    }

    @Test
    fun emphasisDotsSitAboveGlyphs() {
        // P3-a: 着重号点在字形上方（对照行顶条带空白）。
        val xLo = contentLeft.toInt()
        val plain = drawAndScan(listOf(sampleLine("H", TextAlign.LEFT)))
        val plainTop = inkRows(plain, xLo, xLo + 60, 200).minOrNull() ?: error("对照行须有墨")
        val dotted = drawAndScan(
            listOf(sampleLine("H", TextAlign.LEFT).copy(emphasis = orilumn.reader.engine.css.EmphasisStyle.DOT)),
        )
        assertTrue(
            "着重号须在字形之上 (glyphTop=$plainTop)",
            bandHasInk(dotted, 0, plainTop, xLo, xLo + 60),
        )
        assertTrue("对照行顶条带须空白", !bandHasInk(plain, 0, plainTop, xLo, xLo + 60))
    }

    @Test
    fun alphaBlendsTowardBackground() {
        // P3-a: alpha=0.5 黑字盖白底 → 灰（非白非黑），同坐标对照为纯黑。
        val line = sampleLine("H", TextAlign.LEFT)
        val xLo = contentLeft.toInt()
        val plain = drawAndScan(listOf(line))
        val px = requireNotNull(plain.peekPixels())
        var gx = -1
        var gy = -1
        outer@ for (y in 0 until 40) {
            for (x in xLo until xLo + 60) {
                if (px.getColor(x, y) == Color.BLACK) {
                    gx = x
                    gy = y
                    break@outer
                }
            }
        }
        assertTrue("对照须有纯黑字像素", gx >= 0)
        val faded = drawAndScan(listOf(line.copy(alpha = 0.5f)))
        val fc = requireNotNull(faded.peekPixels()).getColor(gx, gy)
        assertTrue("alpha 行同坐标须有墨", fc != Color.WHITE)
        assertTrue("alpha 行须变灰而非纯黑 (c=$fc)", fc != Color.BLACK)
    }
}