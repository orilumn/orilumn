package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Locks the two cascade paths to a single source of truth: the full-chapter [StyleComputer.compute] and
 * the lazy [StyleComputer.resolve] must yield identical [ComputedStyle] for the same node. This guarantees
 * a style fix made in one place (computeOne) propagates to BOTH the line-level (heavy) and the block/temp
 * (lazy) paths — there is no second implementation to keep in sync, only this equivalence.
 */
class LazyCascadeConsistencyTest {

    private val ua = StyleSheet(emptyList())

    /** Builds a tree whose parents are wired (the lazy path resolves via the parent chain). */
    private fun buildParentedTree(): MarkupElement {
        val root = MarkupElement(
            "body",
            children = listOf(
                MarkupElement(
                    "h1",
                    children = listOf(
                        MarkupElement("#text", text = "Heading "),
                        MarkupElement(
                            "span",
                            attrs = mapOf("class" to "t"),
                            children = listOf(MarkupElement("#text", text = "bold")),
                        ),
                    ),
                ),
                MarkupElement(
                    "p",
                    children = listOf(
                        MarkupElement("#text", text = "A paragraph with "),
                        MarkupElement(
                            "code",
                            attrs = mapOf("style" to "font-size:120%"),
                            children = listOf(MarkupElement("#text", text = "x = 1;")),
                        ),
                    ),
                ),
                MarkupElement(
                    "pre",
                    children = listOf(
                        MarkupElement("code", children = listOf(MarkupElement("#text", text = "multi\nline\ncode"))),
                    ),
                ),
            ),
        )
        fun link(n: MarkupElement, p: MarkupElement?) {
            n.parent = p
            for (c in n.children) link(c, n)
        }
        link(root, null)
        return root
    }

    private fun assertStyleEqual(expected: ComputedStyle?, actual: ComputedStyle?) {
        assertNotNull("compute() missing style", expected)
        assertNotNull("resolve() missing style", actual)
        assertEquals("fontSizePx", expected!!.fontSizePx, actual!!.fontSizePx, 1e-3f)
        assertEquals("lineHeightRatio", expected.lineHeightRatio, actual.lineHeightRatio, 1e-4f)
        assertEquals("colorHex", expected.colorHex, actual.colorHex)
        assertEquals("backgroundColorHex", expected.backgroundColorHex, actual.backgroundColorHex)
        assertEquals("borderColors", expected.borderColors, actual.borderColors)
        assertEquals("borderStyles", expected.borderStyles, actual.borderStyles)
        assertEquals("borderRadius", expected.borderRadius, actual.borderRadius)
        assertEquals("whiteSpace", expected.whiteSpace, actual.whiteSpace)
        assertEquals("letterSpacingPx", expected.letterSpacingPx, actual.letterSpacingPx, 1e-3f)
        assertEquals("wordSpacingPx", expected.wordSpacingPx, actual.wordSpacingPx, 1e-3f)
        assertEquals("textTransform", expected.textTransform, actual.textTransform)
        assertEquals("verticalAlign", expected.verticalAlign, actual.verticalAlign)
        assertEquals("boxSizing", expected.boxSizing, actual.boxSizing)
        assertEquals("opacity", expected.opacity, actual.opacity, 1e-4f)
        assertEquals("visibilityHidden", expected.visibilityHidden, actual.visibilityHidden)
        assertEquals("overflow", expected.overflow, actual.overflow)
        assertEquals("positionRelative", expected.positionRelative, actual.positionRelative)
        assertEquals("overflowWrap", expected.overflowWrap, actual.overflowWrap)
        assertEquals("wordBreak", expected.wordBreak, actual.wordBreak)
        assertEquals("directionRtl", expected.directionRtl, actual.directionRtl)
        assertEquals("bold", expected.bold, actual.bold)
        assertEquals("italic", expected.italic, actual.italic)
        assertEquals("underline", expected.underline, actual.underline)
        assertEquals("textIndentPx", expected.textIndentPx, actual.textIndentPx, 1e-3f)
        assertEquals("margin", expected.margin, actual.margin)
        assertEquals("padding", expected.padding, actual.padding)
        assertEquals("border", expected.border, actual.border)
        assertEquals("textAlign", expected.textAlign, actual.textAlign)
    }

    @Test
    fun lazyResolveMatchesFullComputeOnStyledTree() {
        val author = LightCssParser().parse(
            "h1{font-size:32px;line-height:1.2;color:#e00000;font-weight:bold}" +
                ".t{color:#00f;text-decoration:underline}" +
                "code{font-size:13px;background-color:#f0f0f0}" +
                "pre{color:#080}",
        )
        val engine = StyleComputer(16f, ua, listOf(author))
        val root = buildParentedTree()
        val full = engine.compute(root)
        val cache = HashMap<MarkupElement, ComputedStyle>()

        fun check(el: MarkupElement) {
            assertStyleEqual(full[el], engine.resolve(el, cache))
            for (c in el.children) check(c)
        }
        check(root)
    }
}