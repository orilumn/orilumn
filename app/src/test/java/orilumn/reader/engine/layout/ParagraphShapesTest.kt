package orilumn.reader.engine.layout

import android.graphics.Color
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.CssLayouter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard (C1-0): [ParagraphShapes] breaks through the shared skia breaker —
 * the same single source the canonical box flow uses. Inline `<code>` (and `kbd`/`samp`/`tt`)
 * stays in the shaped char stream (run styling is the drawing layer's job now), and every
 * produced line carries the CSS line box. The retired StaticLayout per-run span assertions
 * (`InlineTypefaceSpan`) are gone with the old shaper; mono-face parity is covered by
 * engine-skia `ResolveFamiliesReconcileTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParagraphShapesTest {

    private val converter = HtmlTreeConverter()

    private fun profile() = TypographicProfile(
        bodyPx = 16f, headingScale = 1.4f, quoteScale = 1f, codeScale = 0.92f,
        lineSpacing = 1f, lineSpacingMult = 1f, paragraphSpacingPx = 0, firstLineIndentEm = 2f,
        fgColor = Color.BLACK, bgColor = Color.WHITE, quoteColor = Color.GRAY,
        marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
        fontBody = "", fontTitle = "", fontCode = "", useOriginalStyle = true,
        layoutTheme = "original",
        coverProportional = false, paragraphGapScale = 1f, letterSpacingEm = 0f,
    )

    private fun cascadeFor(root: MarkupElement) =
        StyleComputer(
            16f,
            LightCssParser().parse("code,kbd,samp,tt{font-family:monospace}"),
            emptyList(),
        ).compute(root)

    private fun findTag(root: MarkupElement, tag: String): MarkupElement {
        if (root.tag == tag) return root
        for (c in root.children) {
            val hit = runCatching { findTag(c, tag) }.getOrNull()
            if (hit != null) return hit
        }
        error("tag <$tag> not found")
    }

    @Test
    fun `sub 上标位移随 shape 携带且对齐字符流`() {
        val root = converter.convert("<html><body><p>a<sub>b</sub>c</p></body></html>")!!
        val ua = LightCssParser().parse("sub { vertical-align: sub }")
        val styles = StyleComputer(16f, ua, emptyList()).compute(root)
        val p = findTag(root, "p")
        val shape = ParagraphShapes.shapeOf(
            p, styles[p]!!, styles, profile(), 600, FontPool(),
        )
        assertEquals("abc", shape.text)
        assertFullCoverage(shape)
        assertEquals(1, shape.baselineShifts.size)
        assertEquals(1, shape.baselineShifts[0].start)
        assertEquals(2, shape.baselineShifts[0].endExclusive)
        assertEquals(-0.20f, shape.baselineShifts[0].shiftEm, 1e-6f)
    }

    @Test
    fun `p 内联 code 不断裂字符流且行覆盖全文`() {
        val root = converter.convert("<html><body><p>a<code>codeText</code>b</p></body></html>")!!
        val styles = cascadeFor(root)
        val p = findTag(root, "p")
        // Cascade sanity: the text inside <code> inherits monospace, the body text does not.
        val codeTextNode = findTag(root, "code").children.first { it.isText }
        assertTrue(styles[codeTextNode]!!.monospace)

        val shape = ParagraphShapes.shapeOf(
            p, styles[p]!!, styles, profile(), 600, FontPool(),
        )
        assertEquals("acodeTextb", shape.text)
        assertTrue("must produce at least one line", shape.lineCount >= 1)
        assertFullCoverage(shape)
        // 行内 `<code>` 必须产出等宽 face 段（浏览器 inline-run：断行与绘制都按段整形）。
        assertEquals(1, shape.fontRuns.size)
        assertEquals(1, shape.fontRuns[0].start)
        assertEquals(9, shape.fontRuns[0].endExclusive)
        assertTrue("code 段须是等宽 face", shape.fontRuns[0].monospace)
    }

    @Test
    fun `纯正文叶不产出行内 face 段`() {
        val root = converter.convert("<html><body><p>纯正文段落</p></body></html>")!!
        val styles = cascadeFor(root)
        val p = findTag(root, "p")
        val shape = ParagraphShapes.shapeOf(
            p, styles[p]!!, styles, profile(), 600, FontPool(),
        )
        assertEquals("纯正文段落", shape.text)
        assertTrue("无行内异 face 时 fontRuns 必须为空", shape.fontRuns.isEmpty())
        assertFullCoverage(shape)
    }

    @Test
    fun `匿名文本叶以根块 face 为基底恒无字体段`() {
        // 合成匿名叶（级联表里查不到）：base 兜底 = 根块 face，自身 face 相等 → 零 run。
        val parent = MarkupElement("h2")
        val textEl = MarkupElement("#text", text = "标题文字").also { it.parent = parent }
        val base = orilumn.reader.engine.css.ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f)
        assertTrue(ParagraphShapes.fontRunsOf(textEl, emptyMap(), base).isEmpty())
    }

    @Test
    fun `原书设置下滑块0强制无缩进`() {
        // 滑块值绝对：0 就是 0，即使书有 `body p{text-indent:2em}` 也被 UI 层覆盖。
        // 书的真实缩进靠切换时探测写入滑块（BookStyleProbe），不是靠 0 跟随。
        val s = TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "original")
        val profile = TypographicProfile.build(s)
        assertTrue(profile.useOriginalStyle)
        assertEquals(0f, profile.firstLineIndentEm)
        val ui = BoxChapterLayouter().uiSheetFromProfile(profile)
        val ua = CssLayouter(profile).uaSheetFromProfile()
        val author = LightCssParser().parse("body p{text-indent:2em}")
        val root = converter.convert("<html><body><p>正文段落</p></body></html>")!!
        val styles = StyleComputer(profile.bodyPx, ua, listOf(author), ui = ui).compute(root)
        assertEquals(0f, styles[findTag(root, "p")]!!.textIndentPx, 0.001f)
    }

    @Test
    fun `探测快照写入滑块后UI层还原书排版`() {
        // 切换原书设置时的组合：withLayoutTheme(original) + BookStyleProbe 快照
        // （缩进 2em / 段距 0.3 / 行距 1.3）→ UI 层照常最高优先级渲染，还原书的排版。
        val authorCss = "html{font-size:18px} body{font-size:0.95rem;line-height:1.3rem} " +
            "p{margin-top:0;margin-bottom:0.3rem;line-height:1.3rem} body p{text-indent:2em}"
        val detected = orilumn.reader.engine.css.BookStyleProbe.snapshot(
            StyleComputer(
                18f,
                LightCssParser().parse(""),
                listOf(LightCssParser().parse(authorCss)),
            ).compute(converter.convert("<html><body><p>正文段落</p></body></html>")!!),
        )
        assertEquals(2.0, detected.firstLineIndent, 1e-9)
        val s = TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "original").copy(
            firstLineIndent = detected.firstLineIndent,
            paragraphSpacing = detected.paragraphSpacing,
            lineSpacing = detected.lineSpacing,
        )
        val profile = TypographicProfile.build(s)
        val ui = BoxChapterLayouter().uiSheetFromProfile(profile)
        val ua = CssLayouter(profile).uaSheetFromProfile()
        val author = LightCssParser().parse(authorCss)
        // 段间距口径：只在 p/li 相邻对之间生效 —— 双段验证后者取段间距（还原书的段间 0.3rem）。
        val root = converter.convert("<html><body><p>正文段落一</p><p>正文段落二</p></body></html>")!!
        val styles = StyleComputer(profile.bodyPx, ua, listOf(author), ui = ui).compute(root)
        val paras = ArrayList<orilumn.reader.engine.html.MarkupElement>()
        fun walk(n: orilumn.reader.engine.html.MarkupElement) {
            if (n.tag == "p") paras.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        val first = styles[paras[0]]!!
        val second = styles[paras[1]]!!
        assertEquals(2 * second.fontSizePx, second.textIndentPx, second.fontSizePx * 0.05f)
        assertEquals(0f, first.margin.top, 0.001f)
        assertEquals((0.3 * second.fontSizePx).toFloat(), second.margin.top, second.fontSizePx * 0.05f)
        assertEquals(0f, second.margin.bottom, 0.001f)
        assertEquals(1.3f, second.lineHeightRatio, 0.01f)
    }

    @Test
    fun `非原书模式下滑块0仍表示无缩进`() {
        val s = ReaderSettings.DEFAULT.copy(layoutTheme = "modern", firstLineIndent = 0.0)
        val profile = TypographicProfile.build(s)
        val ui = BoxChapterLayouter().uiSheetFromProfile(profile)
        val ua = CssLayouter(profile).uaSheetFromProfile()
        val author = LightCssParser().parse("body p{text-indent:2em}")
        val root = converter.convert("<html><body><p>正文段落</p></body></html>")!!
        val styles = StyleComputer(profile.bodyPx, ua, listOf(author), ui = ui).compute(root)
        assertEquals(0f, styles[findTag(root, "p")]!!.textIndentPx, 0.001f)
    }

    @Test
    fun `kbd samp tt 保留语义并不断裂字符流`() {
        for (tag in listOf("kbd", "samp", "tt")) {
            val root = converter.convert("<html><body><p>a<$tag>k</$tag>b</p></body></html>")!!
            // Parser must keep the tag (previously de-shelled to "", losing the UA monospace rule).
            val kept = findTag(root, tag)
            val textNode = kept.children.first { it.isText }
            val styles = cascadeFor(root)
            assertTrue("<$tag> text must resolve monospace", styles[textNode]!!.monospace)

            val p = findTag(root, "p")
            val shape = ParagraphShapes.shapeOf(
                p, styles[p]!!, styles, profile(), 600, FontPool(),
            )
            assertEquals("akb", shape.text)
            assertFullCoverage(shape)
        }
    }

    @Test
    fun `匿名文本叶用父块色兜底`() {
        // 合成匿名叶的 inlineStyles 为空表（连父级都查不到），须用 shapeOf 传入的根块色。
        val parent = MarkupElement("h2")
        val textEl = MarkupElement("#text", text = "标题文字").also { it.parent = parent }
        val red = 0xFFFF0000.toInt()
        assertEquals(
            listOf(orilumn.reader.engine.css.ColorRun(0, 4, red)),
            ParagraphShapes.colorRunsOf(textEl, emptyMap(), red),
        )
        assertTrue(ParagraphShapes.colorRunsOf(textEl, emptyMap(), null).isEmpty())
    }

    /** Lines tile the shaped text exactly: first starts at 0, last ends at length, no gaps. */    private fun assertFullCoverage(shape: ParagraphShape) {
        assertEquals(0, shape.lineStart(0))
        assertEquals(shape.text.length, shape.lineEnd(shape.lineCount - 1))
        for (k in 0 until shape.lineCount - 1) {
            assertEquals("line $k must tile into line ${k + 1}", shape.lineEnd(k), shape.lineStart(k + 1))
        }
        for (k in 0 until shape.lineCount) {
            assertTrue("line $k height must be a valid line box", shape.lineBottom(k) - shape.lineTop(k) >= 1)
        }
    }
}
