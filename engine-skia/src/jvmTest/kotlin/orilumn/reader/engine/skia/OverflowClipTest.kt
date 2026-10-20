package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.ClipRect
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.contentClip
import orilumn.reader.engine.laying.paddingBox
import orilumn.reader.engine.text.TypographicProfile
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * E11 `overflow` 祖先裁剪：`overflow != visible` 的盒把后代内容裁到自己的 **padding box**，链上取交集。
 *
 * 四层锁：
 * 1. **级联 → 盒几何**（真 DOM / 真级联 / 真排版）：无 `overflow` 声明时全链裁剪恒 null（零回归）；
 *    有声明时叶内容裁剪 == 该祖先盒的 `paddingBox()`（同一单源）；
 * 2. **载体透传**：文本行、插图、表格格内容/边框都带上裁剪；
 * 3. **真栅格化**：裁剪区外的墨被画布裁掉（还原逐行 `clipRect` 即变红）；
 * 4. **重轻两路同几何**：正典盒树算出的裁剪与临时页（窗口聚合）算出的裁剪 X 与高度逐值相同。
 */
@RunWith(Parameterized::class)
class OverflowClipTest(private val variant: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "breaker={0}")
        fun variants() = breakerVariantParams()
    }

    @Before fun applyVariant() = applyBreakerVariant(variant)

    @After fun resetVariant() = resetBreakerVariant()

    private val contentLeft = 20f
    private val contentWidth = 320
    private val chapterHref = "Text/Ch01.htm"

    private class Run(val result: BoxLayoutResult, val styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>, val classify: BlockClassify) {
        fun lines() = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
    }

    private fun run(html: String, ua: String = ""): Run {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(ua), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, variantBreaker()).layoutBoxes(root, 600, styleMap, classify)
        return Run(result, styleMap, classify)
    }

    private fun allBoxes(result: BoxLayoutResult): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) {
                out.add(b)
                if (b.childBoxes.isNotEmpty()) walk(b.childBoxes)
            }
        }
        walk(result.boxes)
        return out
    }

    private fun boxOf(result: BoxLayoutResult, tag: String): LayoutBox =
        allBoxes(result).firstOrNull { it.el?.tag == tag && !it.el!!.isText }
            ?: error("找不到 <$tag> 盒：${allBoxes(result).map { it.el?.tag }}")

    private fun leafOf(result: BoxLayoutResult, tag: String): LayoutBox =
        allBoxes(result).firstOrNull { it.el?.tag == tag && it.el!!.isText }.let { leaf ->
            leaf ?: allBoxes(result).firstOrNull { it.el?.tag == tag && !it.isContainer }
        } ?: error("找不到 <$tag> 叶")

    // ---- 1. 级联 → 盒几何 ----

    @Test
    fun `无 overflow 声明时全链裁剪为 null`() {
        val r = run("<html><body><div style=\"border:2px solid #000\"><p>甲乙丙丁</p></div></body></html>")
        assertTrue("未声明 overflow 必须全链不裁（绘制零开销）", allBoxes(r.result).all { it.contentClip() == null })
        assertTrue("行也必须不带裁剪", r.lines().values.all { it.clip == null })
        assertTrue(
            "底色/边框盒同样不裁",
            DrawLineBuilder.pageBackgrounds(r.result, 0, r.result.lines.size).all { it.clip == null },
        )
    }

    @Test
    fun `overflow hidden 祖先把叶内容裁到自己的 padding box`() {
        val r = run(
            "<html><body>" +
                "<div style=\"overflow:hidden;border:2px solid #000;padding:6px\"><p>甲乙丙丁戊己庚辛</p></div>" +
                "</body></html>",
        )
        val div = boxOf(r.result, "div")
        val leaf = leafOf(r.result, "p")
        val clip = leaf.contentClip()
        assertNotNull("overflow:hidden 的祖先必须给出裁剪区", clip)
        assertEquals("裁剪区 == 该祖先盒的 padding box（同一单源）", div.paddingBox(), clip)
        val lines = r.lines()
        assertTrue("有行产出", lines.isNotEmpty())
        assertTrue("每一行都带上同一份裁剪", lines.values.all { it.clip == clip })
    }

    @Test
    fun `overflow clip 与 scroll 同样构成裁剪`() {
        for (v in listOf("clip", "scroll", "auto")) {
            val r = run(
                "<html><body><div style=\"overflow:$v\"><p>甲乙丙丁</p></div></body></html>",
            )
            assertNotNull("overflow:$v 必须构成裁剪", leafOf(r.result, "p").contentClip())
        }
        val shown = run("<html><body><div style=\"overflow:visible\"><p>甲乙丙丁</p></div></body></html>")
        assertNull("对照：overflow:visible 不裁", leafOf(shown.result, "p").contentClip())
    }

    @Test
    fun `多层裁剪祖先取交集`() {
        val r = run(
            "<html><body>" +
                "<div style=\"overflow:hidden;padding:8px\"><section style=\"overflow:hidden;padding:8px\">" +
                "<p>甲乙丙丁戊己庚辛</p></section></div>" +
                "</body></html>",
        )
        val outer = boxOf(r.result, "div").paddingBox()
        val inner = boxOf(r.result, "section").paddingBox()
        assertEquals("内层（更窄）赢 ⇒ 交集 = 内层 padding box", inner, leafOf(r.result, "p").contentClip())
        // 反空跑：内层不比外层窄时交集必须仍是外层（证明真的求交，不是「取最内层」）。
        assertTrue(inner.left >= outer.left && inner.right <= outer.right)
    }

    @Test
    fun `插图带上祖先裁剪`() {
        val r = run(
            "<html><body><div style=\"overflow:hidden\">" +
                "<p><img src=\"a.png\" width=\"40\" height=\"40\"/></p></div></body></html>",
        )
        val div = boxOf(r.result, "div").paddingBox()
        val imgs = DrawLineBuilder.pageImages(r.result, 0, r.result.lines.size, null, chapterHref)
        assertEquals("独图应产出一张图几何", 1, imgs.size)
        assertEquals("插图必须带祖先裁剪", div, imgs[0].clip)
        // 对照（反空跑）：同一形状去掉 overflow 后不得带裁剪。
        val shown = run(
            "<html><body><div>" +
                "<p><img src=\"a.png\" width=\"40\" height=\"40\"/></p></div></body></html>",
        )
        val shownImgs = DrawLineBuilder.pageImages(shown.result, 0, shown.result.lines.size, null, chapterHref)
        assertEquals(1, shownImgs.size)
        assertNull(shownImgs[0].clip)
    }

    @Test
    fun `浮动内容被 overflow hidden 祖先包住时不被裁掉`() {
        // 真书模式（`internallinks.epub` 的 `.opening div.creater dl { overflow:hidden }` 内含
        // `dt{float:left}`）：浏览器里 `overflow != visible` 建立 BFC、把浮动包进高度，
        // 于是容器 padding box 必须**包住**浮动内容；否则 E11 的裁剪会把整块浮动切掉 —— 真书直接丢内容。
        val r = run(
            "<html><body>" +
                "<div style=\"overflow:hidden;border:2px solid #000;padding:6px\">" +
                "<p style=\"float:left;margin:0;width:70px\">左浮动甲乙丙丁戊己庚辛壬癸子丑寅卯</p>" +
                "<p style=\"margin:0\">右侧正文</p>" +
                "</div></body></html>",
        )
        val clip = boxOf(r.result, "div").paddingBox()
        val floats = allBoxes(r.result).filter {
            it.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE
        }
        assertTrue("应有浮动盒（否则本用例失去意义）", floats.isNotEmpty())
        for (f in floats) {
            assertTrue(
                "浮动内容不得被祖先裁剪切掉：浮动底 ${f.contentBottom}，裁剪下沿 ${clip.bottom}",
                f.contentBottom <= clip.bottom,
            )
        }
    }

    @Test
    fun `包住浮动的容器高度进正文流，后随内容排在容器之下`() {
        // §10.6.7 的这份高度**是流高**，不是只改绘制：容器的边框/底色下沿、父容器高度、
        // 后随兄弟与分页全吃同一份。此前只改绘制 ⇒ 父级少算一截，容器下方间距被吃掉。
        val r = run(
            "<html><body>" +
                "<div style=\"border:1px solid #000;padding:2px\">" +
                "<section style=\"overflow:hidden;border:1px solid #000;padding:4px\">" +
                "<div style=\"float:left;width:30px;height:30px;margin:2px\"></div>" +
                "<div style=\"float:left;width:40px;height:40px;margin:2px\"></div>" +
                "</section><p>后随正文</p></div></body></html>",
        )
        val boxes = allBoxes(r.result)
        val section = boxes.first { it.el?.tag == "section" }
        val outer = boxes.first { it.el?.tag == "div" && it.style.floatSide == orilumn.reader.engine.css.FloatSide.NONE }
        val floats = boxes.filter { it.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE }
        assertTrue("应有浮动盒（否则本用例失去意义）", floats.size >= 2)
        val deepestOuter = floats.maxOf { it.contentBottom + it.style.margin.bottom }
        val edgesBottom = section.style.padding.bottom + section.style.border.bottom
        assertEquals(
            "容器内容盒底 == 最深浮动外边盒底（§10.6.7）",
            deepestOuter,
            section.contentBottom - edgesBottom,
        )
        val p = boxes.first { it.el?.tag == "p" && !it.el!!.isText }
        assertTrue(
            "后随兄弟必须排在容器之下：p.top=${p.contentTop} 容器底=${section.contentBottom}",
            p.contentTop >= section.contentBottom,
        )
        assertTrue(
            "父容器高度必须包住这块：父底=${outer.contentBottom} 容器底=${section.contentBottom}",
            outer.contentBottom > section.contentBottom,
        )
    }

    // ---- 3. 真栅格化 ----

    private fun line(text: String, clip: ClipRect? = null): DrawLine = DrawLine(
        text = text,
        range = 0 until text.length,
        yTop = 40,
        yBottom = 72,
        alignment = TextAlign.LEFT,
        fontSizePx = 16f,
        lineHeightRatio = 2f,
        tag = "p",
        families = emptyList(),
        weight = 400,
        italic = false,
        monospace = false,
        letterSpacingEm = 0f,
        lineWidthPx = contentWidth,
        clip = clip,
    )

    private fun render(lines: List<DrawLine>, width: Int = 800, height: Int = 200): Bitmap {
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

    @Test
    fun `裁剪区外的行墨被画布裁掉`() {
        val text = "abcdefgh".repeat(6)
        val control = render(listOf(line(text)))
        assertTrue("对照必须有墨且远超裁剪右缘", maxInkX(control) > contentLeft.toInt() + 120)
        val clip = ClipRect(left = 0, top = 0, right = 100, bottom = 100)
        val clipped = render(listOf(line(text, clip)))
        assertTrue("裁剪区内必须仍有墨（不是整行消失）", minInkX(clipped) >= 0)
        assertTrue(
            "墨不得越过裁剪右缘：maxInk=${maxInkX(clipped)} 限=${contentLeft.toInt() + 100}",
            maxInkX(clipped) <= contentLeft.toInt() + 100,
        )
        assertEquals("左缘不动（裁掉的是右侧）", minInkX(control), minInkX(clipped))
    }

    @Test
    fun `裁剪区的 Y 也生效`() {
        val text = "abcdefgh".repeat(6)
        val control = render(listOf(line(text)))
        val clip = ClipRect(left = 0, top = 0, right = contentWidth, bottom = 50)
        val clipped = render(listOf(line(text, clip)))
        // 行占 y ∈ [40,72)，裁到 [0,50) ⇒ 只有上半行有墨：下半行区域必须全白。
        val px = requireNotNull(clipped.peekPixels())
        var lower = false
        for (y in 52 until 72) for (x in 0 until clipped.width) {
            if (px.getColor(x, y) != Color.WHITE) lower = true
        }
        assertTrue("对照下半行有墨", run {
            val p2 = requireNotNull(control.peekPixels())
            var any = false
            for (y in 52 until 72) for (x in 0 until control.width) if (p2.getColor(x, y) != Color.WHITE) any = true
            any
        })
        assertTrue("裁剪下缘以外必须零墨", !lower)
    }

    // ---- 4. 重轻两路同几何 ----

    private val contentH = 800

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"))

    /** 重路径：整章盒树上叶的内容裁剪。 */
    private fun heavyClip(html: String, authorCss: String): Pair<ClipRect, BoxLayoutResult> {
        val l = BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile())).also { it.bindChapterContext(chapterHref) }
        val root = HtmlTreeConverter().convert(html)!!
        val p = l.prepare(root, CssBundle(listOf(authorCss)), profile(), 500, contentH)
        val leaf = allBoxes(p.structure).first { it.el?.tag == "p" && !it.el!!.isText }
        return requireNotNull(leaf.contentClip()) to p.structure
    }

    /** 轻路径（临时页）：窗口聚合出的裁剪（坐标系是窗口局部 Y，故只比 X 与高度）。 */
    private fun lightClip(html: String, authorCss: String): ClipRect {
        val l = BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile())).also { it.bindChapterContext(chapterHref) }
        val root = HtmlTreeConverter().convert(html)!!
        val p = l.prepareLight(root, CssBundle(listOf(authorCss)), profile(), 500, ChapterStructureCache(), contentH)
        l.tempShape(null, p, 0, profile())
        val page = requireNotNull(l.shapeTempPageForward(p, profile(), 500, contentH, 0, null, 0))
        val rb = page.page.layout as LayoutReadback
        val lines = requireNotNull(rb.skiaLineWindow())
        val dl = lines.values.firstOrNull { it.text.isNotBlank() } ?: error("临时页无文本行")
        return requireNotNull(dl.clip) { "临时页的行必须带裁剪（窗口聚合源）" }
    }

    @Test
    fun `重轻两路的裁剪同几何`() {
        val css = "p{margin:0}"
        val html = "<html><body>" +
            "<div style=\"overflow:hidden;border:3px solid #000;padding:7px\">" +
            "<p>甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥一二三四五</p></div>" +
            "</body></html>"
        val (heavy, _) = heavyClip(html, css)
        val light = lightClip(html, css)
        assertEquals("左沿（内容坐标，两路同框架）", heavy.left, light.left)
        assertEquals("右沿", heavy.right, light.right)
        assertEquals("高度（Y 框架不同，只比高度）", heavy.bottom - heavy.top, light.bottom - light.top)
    }

    @Test
    fun `浮动后代不会被 overflow hidden 祖先裁掉（重轻两路一致）`() {
        // §10.6.7：`overflow != visible` 的盒高包住浮动后代 ⇒ 裁剪区也必须包住。真书的 clearfix
        // 写法（裁剪容器里全是浮动子块）若照 padding box 硬裁，会**整块丢内容**。
        val css = "p{margin:0}"
        val html = "<html><body>" +
            "<div style=\"overflow:hidden;border:2px solid #000;padding:6px\">" +
            "<img src=\"a.png\" width=\"60\" height=\"120\" style=\"float:left\"/>" +
            "<p style=\"margin:0\">右侧正文</p></div></body></html>"

        // 重路径：图叶的内容裁剪必须包住整张图。
        val l = BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile())).also { it.bindChapterContext(chapterHref) }
        val p = l.prepare(HtmlTreeConverter().convert(html)!!, CssBundle(listOf(css)), profile(), 500, contentH)
        val img = allBoxes(p.structure).first { it.el?.tag == "img" && !it.el!!.isText }
        val heavy = requireNotNull(img.contentClip())
        assertTrue(
            "重路径：图底 ${img.contentBottom} 被裁到 ${heavy.bottom}（丢内容）",
            img.contentBottom <= heavy.bottom,
        )

        // 轻路径：同一条规则（替换型浮动叶在行流里是零高行，几何须按替换高还原）。
        val l2 = BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile())).also { it.bindChapterContext(chapterHref) }
        val p2 = l2.prepareLight(
            HtmlTreeConverter().convert(html)!!, CssBundle(listOf(css)), profile(), 500,
            ChapterStructureCache(), contentH,
        )
        l2.tempShape(null, p2, 0, profile())
        val page = requireNotNull(l2.shapeTempPageForward(p2, profile(), 500, contentH, 0, null, 0))
        val rb = page.page.layout as LayoutReadback
        val drawn = rb.pageImages(0, Int.MAX_VALUE).firstOrNull() ?: error("临时页没有插图")
        val clip = requireNotNull(drawn.clip) { "临时页的插图必须带裁剪（窗口聚合源）" }
        assertTrue(
            "轻路径：图底 ${drawn.yBottom} 被裁到 ${clip.bottom}（丢内容）",
            drawn.yBottom <= clip.bottom,
        )
        assertEquals("重轻两路的裁剪同高", heavy.bottom - heavy.top, clip.bottom - clip.top)
    }
}
