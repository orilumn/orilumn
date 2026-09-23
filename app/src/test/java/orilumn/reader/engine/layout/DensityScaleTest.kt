package orilumn.reader.engine.layout

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two-slider semantics (决策 of the reader layer), pinned end-to-end through the real pipeline:
 *
 *  段间距 (paragraphSpacing)  = 替换: the UI layer replaces p/li vertical margins outright; it must
 *    override author per-side `p` margins and be immune to 疏密.
 *  疏密 (paragraphGapScale)  = 调节: it must scale the COMPUTED vertical margin of every OTHER block
 *    (heading/quote/pre/div/...) proportionally — the author/UA value is preserved, never replaced by
 *    a reader baseline. 1.0 = 原书排版. Horizontal margins are never scaled.
 *
 * 行距 (lineSpacing) -> line-height on all text blocks (NOT tested here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DensityScaleTest {

    private val layouter = BoxChapterLayouter()

    /** Computes [tag]'s computed margin under [settings] — UI sheet (段间距/行距) + gapScale (疏密) exactly
     *  as [orilumn.reader.engine.BoxChapterLayouter.styleComputerFor] wires them in production.
     *  p/li 测相邻对的后者（段间距口径：只在 p/li 相邻对之间生效，单块基线恒 0）。 */
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
            gapScale = profile.paragraphGapScale,
        ).compute(root)
        return map[el]!!.margin
    }

    private fun withGap(paragraphGap: Double) = ReaderSettings.DEFAULT.copy(paragraphGap = paragraphGap)

    /** 疏密 (调节) 缩放作者声明的块级 margin: h1 1em -> 16px (gap100) / 64px (gap400). */
    @Test
    fun `author block margins scale with paragraphGap`() {
        val author = "h1{margin-top:1em}"
        assertEquals(16.0f, margin(withGap(100.0), "h1", author).top, 0.1f)
        assertEquals(64.0f, margin(withGap(400.0), "h1", author).top, 0.1f)
    }

    /** 疏密 (调节) 缩放 UA 默认 margin, 作者/UA 值都被保留而非替换. */
    @Test
    fun `UA default block margins scale with paragraphGap`() {
        val ua = "h1{margin:1em 0 0.6em}\nblockquote{margin:1em 2.5em}\npre{margin:1em 0}"
        assertEquals(16.0f, margin(withGap(100.0), "h1", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withGap(400.0), "h1", uaCss = ua).top, 0.1f)
        assertEquals(16.0f, margin(withGap(100.0), "blockquote", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withGap(400.0), "blockquote", uaCss = ua).top, 0.1f)
        assertEquals(16.0f, margin(withGap(100.0), "pre", uaCss = ua).top, 0.1f)
        assertEquals(64.0f, margin(withGap(400.0), "pre", uaCss = ua).top, 0.1f)
    }

    /** 无任何声明的块保持 0 — 疏密不注入人工间距基线. */
    @Test
    fun `unstyled blocks keep zero margin`() {
        assertEquals(0f, margin(withGap(100.0), "div").top, 0.001f)
        assertEquals(0f, margin(withGap(400.0), "div").top, 0.001f)
    }

    /** 疏密只缩放垂直 margin, 水平 margin 原样保留. */
    @Test
    fun `horizontal margins are not scaled`() {
        val m = margin(withGap(400.0), "blockquote", authorCss = "blockquote{margin:2em 3em}")
        assertEquals(128f, m.top, 0.1f)   // 2.0em x 16 x 4 (scaled)
        assertEquals(48f, m.left, 0.1f)   // 3.0em x 16 (NOT scaled)
    }

    /** 段间距 (替换) governs body paragraph/li margin and must be immune to 疏密. */
    @Test
    fun `paragraph and li margins depend on paragraphSpacing not paragraphGap`() {
        for (tag in listOf("p", "li")) {
            val a = margin(withGap(100.0), tag).top
            val b = margin(withGap(400.0), tag).top
            assertEquals("$tag margin must be immune to 疏密", a, b, 0.001f)
            assertTrue("expected a non-zero $tag gap, got $a", a > 0f)
        }

        // Turning 段间距 to 0 kills the paragraph margin regardless of 疏密.
        val zero = margin(ReaderSettings.DEFAULT.copy(paragraphSpacing = 0.0, paragraphGap = 400.0), "p").top
        assertEquals(0f, zero, 0.001f)
    }

    /** body paragraphs keep a gap (not flattened to 0) when the page is set dense. */
    @Test
    fun `paragraph margin is not disabled by paragraphGap`() {
        assertNotEquals(0f, margin(withGap(400.0), "p").top, 0.001f)
    }

    /** 段间距 (替换) must override an author/inline per-side `p` margin: a book that declares
     *  `p { margin-top: ... }` must not defeat the reader's paragraph spacing. (UI layer emits the
     *  spacing as per-side margin-top/bottom so it wins the property-level tier.) */
    @Test
    fun `paragraph spacing overrides author per-side p margin`() {
        val settings = ReaderSettings.DEFAULT.copy(paragraphSpacing = 1.0, paragraphGap = 400.0)
        val plain = margin(settings, "p").top
        val fought = margin(settings, "p", authorCss = "p{margin-top:2em}").top // author per-side must NOT win
        assertEquals(plain, fought, 0.001f)
        assertTrue("expected non-zero paragraph spacing, got $fought", fought > 0f)
        // 段间距 is replace: same 1em at any 疏密.
        val dense = margin(settings, "p").top
        assertEquals(dense, fought, 0.001f)
    }
}