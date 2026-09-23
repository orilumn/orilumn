package orilumn.reader.engine.layout

import android.graphics.Color
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.skia.shapeGeometry
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C2-P2b-2 等价锁：`shapeGeometry()`（engine-skia 纯几何）与 `ParagraphShapes.shapeOf`
 * （`:app` 几何 + 画笔包装）的几何面必须逐字段相等 —— 两端分页字节一致就靠这一条。
 * 画笔（`drawPaint`）故意不比：它只画不量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShapeGeometryEquivalenceTest {

    private val converter = HtmlTreeConverter()
    private val isBlock: (MarkupElement) -> Boolean = { it.tag in CssLayouter.BLOCK_TAGS }

    private fun profile() = TypographicProfile(
        bodyPx = 16f, headingScale = 1.4f, quoteScale = 1f, codeScale = 0.92f,
        lineSpacing = 1f, lineSpacingMult = 1f, paragraphSpacingPx = 0, firstLineIndentEm = 2f,
        fgColor = Color.BLACK, bgColor = Color.WHITE, quoteColor = Color.GRAY,
        marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
        fontBody = "", fontTitle = "", fontCode = "", useOriginalStyle = true,
        layoutTheme = "original",
        coverProportional = false, paragraphGapScale = 1f, letterSpacingEm = 0f,
    )

    private fun cascadeFor(root: MarkupElement, authorCss: String = "") =
        StyleComputer(16f, LightCssParser().parse(authorCss), emptyList()).compute(root)

    private fun findTag(root: MarkupElement, tag: String): MarkupElement {
        if (root.tag == tag) return root
        for (c in root.children) {
            val hit = runCatching { findTag(c, tag) }.getOrNull()
            if (hit != null) return hit
        }
        error("tag <$tag> not found")
    }

    private fun assertSameGeometry(html: String, tag: String, authorCss: String = "", widthPx: Int = 600) {
        val root = converter.convert("<html><body>$html</body></html>")!!
        val styles = cascadeFor(root, authorCss)
        val leaf = findTag(root, tag)
        val painted = ParagraphShapes.shapeOf(
            leaf, styles[leaf]!!, styles, profile(), widthPx, FontPool(),
        )
        val geo = shapeGeometry(
            leaf, styles[leaf]!!, styles, profile(), widthPx,
            isBlock = isBlock,
        )
        assertEquals(painted.text, geo.text)
        assertEquals(painted.lineCount, geo.lineCount)
        for (k in 0 until painted.lineCount) {
            assertEquals(painted.lineStart(k), geo.lineStart(k))
            assertEquals(painted.lineEnd(k), geo.lineEnd(k))
            assertEquals(painted.lineTop(k), geo.lineTop(k))
            assertEquals(painted.lineBottom(k), geo.lineBottom(k))
        }
        assertEquals(painted.alignment, geo.alignment)
        assertEquals(painted.shapeFontSizePx, geo.fontSizePx, 1e-6f)
        assertEquals(painted.colorRuns, geo.colorRuns)
        assertEquals(painted.fontRuns, geo.fontRuns)
        assertEquals(painted.baselineShifts, geo.baselineShifts)
        assertEquals(painted.rubyRuns, geo.rubyRuns)
        assertEquals(painted.underlineRuns, geo.underlineRuns)
        assertEquals(painted.textShadow, geo.textShadow)
        assertEquals(painted.emphasis, geo.emphasis)
        assertEquals(painted.emphasisUnder, geo.emphasisUnder)
        assertEquals(painted.alpha, geo.alpha, 1e-6f)
        assertEquals(painted.isReplaceable, geo.isReplaceable)
        assertEquals(painted.replaceableBottom, geo.replaceableBottom)
        assertEquals(painted.replaceableCharEnd, geo.replaceableCharEnd)
    }

    @Test
    fun `纯正文几何一致`() {
        assertSameGeometry("<p>纯正文段落第二段纯正文段落</p>", "p")
    }

    @Test
    fun `行内code与face段几何一致`() {
        assertSameGeometry(
            "<p>a<code>codeText</code>b</p>", "p",
            "code,kbd,samp,tt{font-family:monospace}",
        )
    }

    @Test
    fun `上下标位移几何一致`() {
        assertSameGeometry("<p>a<sub>b</sub>c<sup>d</sup></p>", "p", "sub{vertical-align:sub}sup{vertical-align:super}")
    }

    @Test
    fun `阴影与着重号几何一致`() {
        assertSameGeometry(
            "<p>强调文本</p>", "p",
            "p{text-shadow:2px 2px 2px #ff0000;text-emphasis:filled circle}",
        )
    }

    @Test
    fun `块级img替换行几何一致`() {
        assertSameGeometry("<img style=\"display:block\" src=\"a.png\">", "img")
    }
}
