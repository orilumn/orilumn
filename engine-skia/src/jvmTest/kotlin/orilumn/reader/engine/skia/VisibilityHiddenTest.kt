package orilumn.reader.engine.skia

import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.InlineBoxRun
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.data.settings.ReaderSettings
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * E10 `visibility: hidden`：**不落墨、但保留占位**（与 `display:none` 的去盒语义相反）。
 *
 * 三层锁：
 * 1. **级联 → 运行表**（真 DOM / 真级联 / 真断行）：隐藏区间与叶文本下标对齐，可被后代 `visible` 翻回；
 * 2. **重轻两路同源**：正典（`DrawLineBuilder` 自算）与增量/临时页（塑形 shape 透传）产出同一张表；
 * 3. **真栅格化**：隐藏字符零墨而**右缘不动**（占位保留）；隐藏字符的装饰线同隐；
 *    `visibility: hidden` 的行内盒底色不落墨而推进照旧。还原被修代码（不填表 / 不跳过 / 不减装饰）任一处即变红。
 */
@RunWith(Parameterized::class)
class VisibilityHiddenTest(private val variant: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "breaker={0}")
        fun variants() = breakerVariantParams()
    }

    @Before fun applyVariant() = applyBreakerVariant(variant)

    @After fun resetVariant() = resetBreakerVariant()

    private val contentLeft = 20f
    private val contentWidth = 320
    private val markerArgb = 0xFFFFF176.toInt() // ua.css 的 mark 高亮底色
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    // ---- 级联 → 运行表 ----

    private fun build(html: String, ua: String = ""): Map<Int, DrawLine> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(ua), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, variantBreaker()).layoutBoxes(root, 600, styleMap, classify)
        return DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
    }

    private fun only(html: String, ua: String = ""): DrawLine =
        build(html, ua).values.singleOrNull() ?: error("期望单行，实得 ${build(html, ua).size} 行")

    @Test
    fun `隐藏 span 的字符进表且文本不丢`() {
        val dl = only("<html><body><p>甲乙<span style=\"visibility:hidden\">丙丁</span>戊己</p></body></html>")
        assertEquals("隐藏字符仍在字符流里（占位）", "甲乙丙丁戊己", dl.text.substring(dl.range))
        assertEquals(listOf(2..3), dl.visibilityHiddenRuns)
    }

    @Test
    fun `后代 visible 可以把祖先隐藏的段翻回显形`() {
        val dl = only(
            "<html><body><p style=\"visibility:hidden\">甲<span style=\"visibility:visible\">乙</span>丙</p></body></html>",
        )
        assertEquals("甲乙丙", dl.text.substring(dl.range))
        assertEquals("只有甲、丙隐藏（继承可被后代关掉）", listOf(0..0, 2..2), dl.visibilityHiddenRuns)
    }

    @Test
    fun `无 visibility 声明零回归（空表）`() {
        val dl = only("<html><body><p>甲乙丙</p></body></html>")
        assertTrue("未用 visibility 的书必须回空表（绘制零开销）", dl.visibilityHiddenRuns.isEmpty())
    }

    @Test
    fun `隐藏容器整叶进表而文字仍成行`() {
        val dl = only("<html><body><div style=\"visibility:hidden\"><p>甲乙丙</p></div></body></html>")
        assertEquals("甲乙丙", dl.text.substring(dl.range))
        assertEquals(listOf(0..2), dl.visibilityHiddenRuns)
    }

    @Test
    fun `隐藏容器的底色盒不落墨`() {
        val root = HtmlTreeConverter().convert(
            "<html><body><div style=\"background-color:#fff176;visibility:hidden\"><p>甲乙丙</p></div></body></html>",
        )!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, variantBreaker()).layoutBoxes(root, 600, styleMap, classify)
        assertTrue(
            "隐藏容器的底色盒必须整块不落墨",
            DrawLineBuilder.pageBackgrounds(result, 0, result.lines.size).none { it.argb == markerArgb },
        )
        // 对照：去掉 visibility 后同一形状必须有底色盒（证明上面不是空跑）。
        val shownRoot = HtmlTreeConverter().convert(
            "<html><body><div style=\"background-color:#fff176\"><p>甲乙丙</p></div></body></html>",
        )!!
        val shownMap = engine.compute(shownRoot)
        val shownClassify = NormalFlowLayout.heavyClassify(shownMap, engine.hasDisplayDeclaration())
        val shown = BoxLayouter(16f, variantBreaker()).layoutBoxes(shownRoot, 600, shownMap, shownClassify)
        assertTrue(
            "对照（未隐藏）必须画出该底色盒",
            DrawLineBuilder.pageBackgrounds(shown, 0, shown.lines.size).any { it.argb == markerArgb },
        )
    }

    @Test
    fun `正典与塑形 shape 两路同表`() {
        val html = "<html><body><p>甲乙<span style=\"visibility:hidden\">丙丁</span>戊己</p></body></html>"
        val heavy = only(html)
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val p = findTag(root, "p") ?: error("no <p>")
        val shape = shapeGeometry(
            p, styleMap[p]!!, styleMap, profile, 600,
            classify = classify, hidden = HIDDEN_NONE,
            isBlock = { classify.isBlock(it) },
        )
        assertEquals(
            "增量/临时页（shape 透传）必须与正典同一张隐藏表",
            heavy.visibilityHiddenRuns,
            shape.visibilityHiddenRuns,
        )
    }

    private fun findTag(el: MarkupElement, tag: String): MarkupElement? {
        if (el.tag == tag) return el
        for (c in el.children) findTag(c, tag)?.let { return it }
        return null
    }

    // ---- 真栅格化 ----

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
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, contentLeft, lines, Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    private fun minInkX(bmp: Bitmap): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) if (px.getColor(x, y) != Color.WHITE) return x
        }
        return -1
    }

    private fun maxInkX(bmp: Bitmap): Int {
        val px = requireNotNull(bmp.peekPixels())
        for (x in bmp.width - 1 downTo 0) {
            for (y in 0 until bmp.height) if (px.getColor(x, y) != Color.WHITE) return x
        }
        return -1
    }

    private fun hasExactColor(bmp: Bitmap, argb: Int): Boolean {
        val px = requireNotNull(bmp.peekPixels())
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) if (px.getColor(x, y) == argb) return true
        }
        return false
    }

    @Test
    fun `隐藏字符不落墨但推进量保留`() {
        val text = "abcdefgh"
        val control = render(listOf(line(text)))
        assertTrue("对照必须有墨", minInkX(control) >= 0)
        // 全隐藏 ⇒ 整行零墨（保留的行框照占）。
        val whole = render(listOf(line(text).copy(visibilityHiddenRuns = listOf(0 until text.length))))
        assertEquals("全隐藏行必须零墨", -1, minInkX(whole))
        // 只留首字：墨必须与「只有首字」的对照完全同缘 ⇒ 其后字符既不落墨、也不挪位。
        val onlyFirst = render(listOf(line("a")))
        val restHidden = render(listOf(line(text).copy(visibilityHiddenRuns = listOf(1 until text.length))))
        assertEquals("隐藏后墨左缘不动", minInkX(onlyFirst), minInkX(restHidden))
        assertEquals("隐藏字符绝不落墨（右缘 = 首字右缘）", maxInkX(onlyFirst), maxInkX(restHidden))
    }

    @Test
    fun `隐藏字符的装饰线同隐`() {
        val text = "abcdefgh"
        val dl = build(
            "<html><body><p><a href=\"#t\">$text</a></p></body></html>",
            ua = "a{text-decoration:underline}",
        ).values.single()
        assertTrue("前置：链接下划线必须在", dl.decorationRuns.isNotEmpty())
        assertTrue("对照（未隐藏）必须有墨", minInkX(render(listOf(dl))) >= 0)
        val allHidden = render(listOf(dl.copy(visibilityHiddenRuns = listOf(0 until text.length))))
        assertEquals("装饰线与字形同隐 ⇒ 全隐藏行零墨", -1, minInkX(allHidden))
    }

    @Test
    fun `隐藏的 li 不画 bullet 而占位照旧`() {
        val map = build(
            "<html><body><ul><li>first</li><li style=\"visibility:hidden\">second</li></ul></body></html>",
        )
        val lines = map.values.sortedBy { it.range.first }
        val shown = lines.filter { it.visibilityHiddenRuns.isEmpty() }
        val hidden = lines.filter { it.visibilityHiddenRuns.isNotEmpty() }
        assertTrue("可见 li 的行必须有 bullet", shown.isNotEmpty() && shown.all { it.listMarker != null })
        assertTrue("隐藏 li 的行不得画 bullet（占位照旧）", hidden.isNotEmpty() && hidden.all { it.listMarker == null })
        assertEquals(
            "隐藏 li 的文字仍在字符流里",
            "second",
            map.values.single { it.visibilityHiddenRuns.isNotEmpty() }.let { it.text.substring(it.range) },
        )
    }

    @Test
    fun `隐藏的行内盒不落墨但推进照旧`() {
        val text = "a".repeat(20)
        val box = InlineBoxRun(0, 3, bgHex = "#fff176", padding = Edges(right = 24f), visibilityHidden = true)
        // 同一形状只改 visibility：占位（推进）必须逐值相同，落差只允许出现在「有没有墨」上。
        val shown = render(listOf(line(text, listOf(box.copy(visibilityHidden = false)))))
        val hidden = render(listOf(line(text, listOf(box))))
        assertTrue("对照（未隐藏）必须落墨", hasExactColor(shown, markerArgb))
        assertFalse("隐藏的行内盒底色不得落墨", hasExactColor(hidden, markerArgb))
        assertEquals("隐藏与否左缘不动", minInkX(shown), minInkX(hidden))
        assertEquals("隐藏元素的 padding 仍占宽（占位保留，浏览器语义）", maxInkX(shown), maxInkX(hidden))
    }
}
