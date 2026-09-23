package orilumn.reader.engine.layout

import android.graphics.Color
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CssLayouterTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    private fun profile() = TypographicProfile(
        bodyPx = 16f, headingScale = 1.4f, quoteScale = 1f, codeScale = 0.92f,
        lineSpacing = 1f, lineSpacingMult = 1f, paragraphSpacingPx = 0, firstLineIndentEm = 2f,
        fgColor = Color.BLACK, bgColor = Color.WHITE, quoteColor = Color.GRAY,
        marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
        fontBody = "", fontTitle = "", fontCode = "", useOriginalStyle = true,
        layoutTheme = "original",
        coverProportional = false, paragraphGapScale = 1f, letterSpacingEm = 0f,
    )

    private fun layoutOf(root: MarkupElement): Spanned =
        CssLayouter(profile()).layout(root, authorSheets = emptyList())

    private fun <T> spansOf(spanned: Spanned, chunk: String, of: Class<T>): List<T> {
        val idx = spanned.toString().indexOf(chunk)
        assertTrue("text not found: [$chunk]", idx >= 0)
        return spanned.getSpans(idx, idx + chunk.length, of).toList()
    }

    @Test
    fun `内联颜色与字号映射到文本 span`() {
        val p = node("p", mapOf("style" to "color: #ff0000; font-size: 20px"), listOf(text("你好世界")))
        val root = node("body", children = listOf(p))
        val spanned = layoutOf(root)
        assertEquals(Color.parseColor("#ff0000"), spansOf(spanned, "你好世界", ForegroundColorSpan::class.java).first().foregroundColor)
        assertEquals(20, spansOf(spanned, "你好世界", AbsoluteSizeSpan::class.java).first().size)
    }

    @Test
    fun `内联字重与下划线映射`() {
        val p = node("p", mapOf("style" to "font-weight: bold; text-decoration: underline"), listOf(text("加粗下划线")))
        val root = node("body", children = listOf(p))
        val spanned = layoutOf(root)
        assertTrue(spansOf(spanned, "加粗下划线", StyleSpan::class.java).any { it.style == android.graphics.Typeface.BOLD })
        assertEquals(1, spansOf(spanned, "加粗下划线", android.text.style.UnderlineSpan::class.java).size)
    }

    @Test
    fun `颜色沿继承传递到后代文本`() {
        val p = node("p", children = listOf(text("继承色")))
        val root = node("body", mapOf("style" to "color: #0000ff"), children = listOf(p))
        val spanned = layoutOf(root)
        assertEquals(Color.parseColor("#0000ff"), spansOf(spanned, "继承色", ForegroundColorSpan::class.java).first().foregroundColor)
    }

    @Test
    fun `作者样式表参与级联`() {
        val p = node("p", mapOf("class" to "lead"), listOf(text("作者样式")))
        val root = node("body", children = listOf(p))
        val author = LightCssParser().parse(".lead { color: #008000; font-weight: bold }")
        val spanned = CssLayouter(profile()).layout(root, authorSheets = listOf(author))
        assertEquals(Color.parseColor("#008000"), spansOf(spanned, "作者样式", ForegroundColorSpan::class.java).first().foregroundColor)
        assertTrue(spansOf(spanned, "作者样式", StyleSpan::class.java).any { it.style == android.graphics.Typeface.BOLD })
    }
}