package orilumn.reader.engine.layout

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 两个间距滑块的语义，经真实管线一次钉死（2026-10-29 段间距改回绝对值）：
 *
 *  段间距 (paragraphSpacingEm) = **绝对值 em（0..2）**：非 null 时 p/li 的上下外边距直接是该
 *    em × 字号，不看书原来写多少；null = 不覆盖（书的 margin 原样）。标题等结构块不受影响。
 *  疏密 (paragraphGapScale) = 按比例乘算 p/li 之外一切块级纵边距：100 = 原书节奏，
 *    0 = 结构块边距清零。作者/UA/主题值一律保留，UI 层纵边距零声明。
 *  水平 margin 永不被段间距改写。（疏密只动垂直，见下。）
 *
 * 行距 (lineSpacing) -> line-height on all text blocks (NOT tested here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DensityScaleTest {

    private val layouter = BoxChapterLayouter()

    /** Computes [tag]'s computed margin under [settings] — UI sheet (行距/缩进) + 双 scale exactly
     *  as [orilumn.reader.engine.BoxChapterLayouter.styleComputerFor] wires them in production. */
    private fun margin(settings: ReaderSettings, tag: String, authorCss: String = "", uaCss: String = ""): Edges {
        val profile = TypographicProfile.build(settings)
        val ui = layouter.uiSheetFromProfile(profile)
        val text = listOf(MarkupElement("#text", text = "t"))
        val el = MarkupElement(tag, children = text)
        val root = if (tag == "p" || tag == "li")
            MarkupElement("body", children = listOf(MarkupElement(tag, children = text), el))
        else
            MarkupElement("body", children = listOf(el))
        // 相邻兄弟选择器走 parent 指针：手工树需像 HtmlTreeConverter 一样回链。
        fun relink(n: MarkupElement) {
            n.children.forEach { it.parent = n; relink(it) }
        }
        relink(root)
        val author = if (authorCss.isBlank()) emptyList() else listOf(LightCssParser().parse(authorCss))
        val map = StyleComputer(
            16f,
            LightCssParser().parse(uaCss),
            author,
            ui = ui,
            paragraphEm = profile.paragraphSpacingEm, gapScale = profile.paragraphGapScale,
        ).compute(root)
        return map[el]!!.margin
    }

    private fun withSpacing(v: Double) = ReaderSettings.DEFAULT.copy(paragraphSpacing = v)
    private fun withDensity(v: Double) = ReaderSettings.DEFAULT.copy(paragraphGap = v)

    /** 疏密 (调节) 缩放作者声明的块级 margin: h1 1em -> 16px (gap100) / 64px (gap400). */
    @Test
    fun `author block margins scale with paragraphSpacing`() {
        val author = "h1{margin-top:1em}"
        assertEquals(16.0f, margin(withDensity(100.0), "h1", author).top, 0.1f)
        assertEquals(64.0f, margin(withDensity(400.0), "h1", author).top, 0.1f)
    }

    /** 疏密 (调节) 缩放 UA 默认 margin, 作者/UA 值都被保留而非替换. */
    @Test
    fun `UA default block margins scale with paragraphSpacing`() {
        val ua = "h1{margin:1em 0 0.6em}\nblockquote{margin:1em 2.5em}\npre{margin:1em 0}"
        assertEquals(16.0f, margin(withDensity(100.0), "h1", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withDensity(400.0), "h1", uaCss = ua).top, 0.1f)
        assertEquals(16.0f, margin(withDensity(100.0), "blockquote", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withDensity(400.0), "blockquote", uaCss = ua).top, 0.1f)
        assertEquals(16.0f, margin(withDensity(100.0), "pre", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withDensity(400.0), "pre", uaCss = ua).top, 0.1f)
    }

    /** 无任何声明的块保持 0 — 疏密不注入人工间距基线. */
    @Test
    fun `unstyled blocks keep zero margin`() {
        assertEquals(0f, margin(withDensity(100.0), "div").top, 0.001f)
        assertEquals(0f, margin(withDensity(400.0), "div").top, 0.001f)
    }

    /** 疏密只缩放垂直 margin, 水平 margin 原样保留. */
    @Test
    fun `horizontal margins are not scaled`() {
        val m = margin(withDensity(400.0), "blockquote", authorCss = "blockquote{margin:2em 3em}")
        assertEquals(128f, m.top, 0.1f)   // 2.0em x 16 x 4 (scaled)
        assertEquals(48f, m.left, 0.1f)   // 3.0em x 16 (NOT scaled)
    }

    /** 段间距是绝对值：1em → 16px、2em → 32px，与书自己写多少无关；疏密动它不得。 */
    @Test
    fun `p and li margins follow absolute paragraph spacing not density`() {
        for (tag in listOf("p", "li")) {
            val a = margin(withSpacing(1.0), tag, authorCss = "$tag{margin:1em 0}").top
            val b = margin(withSpacing(2.0), tag, authorCss = "$tag{margin:1em 0}").top
            assertEquals("expected 1em $tag gap, got $a", 16f, a, 0.5f)
            assertEquals("expected 2em $tag gap, got $b", 32f, b, 0.5f)
            val c = margin(withDensity(400.0), tag, authorCss = "$tag{margin:1em 0}").top
            assertEquals("疏密不得碰 p/li，got $c", 16f, c, 0.5f)
        }
    }

    /** 段间距 0 即 p/li 紧贴（标题等结构块不受影响，见下条 h1 用例）。 */
    @Test
    fun `zero spacing clears p margins but keeps headings`() {
        val author = "p{margin:1.8em 0 0.6em}"
        assertEquals(20f, margin(withSpacing(1.25), "p", authorCss = author).top, 0.5f)
        assertEquals(0f, margin(withSpacing(0.0), "p", authorCss = author).top, 0.001f)
        assertEquals(16f, margin(withSpacing(0.0), "h1", authorCss = "h1{margin:1em 0}").top, 0.5f)
    }

    /** 书里写的 p margin 大小不再影响绝对段间距：书 2em、读者 0.5em ⇒ 8px（不是 2em × 0.5）。 */
    @Test
    fun `reader absolute spacing overrides the book margin instead of scaling it`() {
        val settings = ReaderSettings.DEFAULT.copy(paragraphSpacing = 0.5)
        val plain = margin(settings, "p").top
        val declared = margin(settings, "p", authorCss = "p{margin-top:2em}").top
        assertEquals(8f, plain, 0.001f) // 0.5em × 16px，无声明也照样是这个值
        assertEquals(8f, declared, 0.001f) // 书 2em 不参与，绝对值就是 0.5em
    }
}
