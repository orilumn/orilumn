package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 决策 6 — the reader upper layers (theme → per-item settings → UI) override the book's own styles
 * by fixed tier, and vacant layers contribute nothing:
 *  - UI beats author, even author `!important`;
 *  - UI beats settings beats theme beats author;
 *  - an empty layer is a no-op.
 */
class ReaderLayerPriorityTest {

    private fun root() = MarkupElement("body", children = listOf(MarkupElement("p", children = listOf(MarkupElement("#text", text = "hi")))))

    private fun pRatio(theme: StyleSheet?, settings: StyleSheet?, ui: StyleSheet?, author: StyleSheet? = null): Float {
        val root = root()
        val p = root.children[0]
        val map = StyleComputer(16f, LightCssParser().parse(""), listOfNotNull(author), theme, settings, ui).compute(root)
        return map[p]!!.lineHeightRatio
    }

    private fun sheet(css: String) = LightCssParser().parse(css)

    /** UI beats author, even author `!important`. */
    @Test
    fun `ui layer beats author important`() {
        val r = pRatio(theme = null, settings = null, ui = sheet("body{line-height:2.0}"), author = sheet("body{line-height:1.2 !important}"))
        assertEquals(2.0f, r, 1e-3f)
    }

    /** Exact upper order: UI > settings > theme, and each beats the book. */
    @Test
    fun `upper layers order is ui then settings then theme`() {
        // All three -> UI wins
        assertEquals(
            1.9f,
            pRatio(theme = sheet("p{line-height:1.3}"), settings = sheet("p{line-height:1.6}"), ui = sheet("p{line-height:1.9}")),
            1e-3f,
        )
        // Only theme -> theme wins over the book default
        assertEquals(1.3f, pRatio(theme = sheet("p{line-height:1.3}"), settings = null, ui = null), 1e-3f)
    }

    /** A fully vacant reader side leaves the book default untouched. */
    @Test
    fun `vacant upper layers are no-ops`() {
        assertEquals(1.5f, pRatio(theme = null, settings = null, ui = null), 1e-3f)
    }

    /**
     * UI 行距 must beat a *paragraph-own* book `line-height` (not just the inherited `body` rule):
     * an explicitly declared property on the paragraph would otherwise beat an inherited `body` value.
     * The production UI sheet declares line-height on the text blocks for exactly this reason.
     */
    @Test
    fun `ui line-height wins over the book paragraph-own rule`() {
        assertEquals(
            2.0f,
            pRatio(theme = null, settings = null, ui = sheet("p{line-height:2.0}"), author = sheet("p{line-height:1.2}")),
            1e-3f,
        )
    }
}