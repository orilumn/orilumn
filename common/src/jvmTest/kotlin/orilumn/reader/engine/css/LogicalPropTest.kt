package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalPropTest {

    @Test
    fun noteSectionEndToEnd() {
        val css = """
            .note {
                margin: 20px 0;
                padding: 0 20px;
                color: #98a3ad;
                background-color: hsl(234, 21%, 18%);
                border-block-start: 0.1em solid hsl(234, 21%, 23%);
                border-block-end: 0.1em solid hsl(234, 21%, 23%);
            }
        """.trimIndent()
        val sheet = LightCssParser().parse(css)

        val noteEl = MarkupElement("div", mapOf("class" to "note"), emptyList())
        val rootEl = MarkupElement("body", emptyMap(), listOf(noteEl))

        val ua = StyleSheet(emptyList())
        val sc = StyleComputer(16f, ua, listOf(sheet))
        val allStyles = sc.compute(rootEl)
        val style = allStyles[noteEl]!!

        println("=== Computed for .note ===")
        println("backgroundColorHex = ${style.backgroundColorHex}")
        println("border.top = ${style.border.top}")
        println("border.bottom = ${style.border.bottom}")
        println("borderColors = ${style.borderColors}")
        println("borderStyles = ${style.borderStyles}")
        println("padding.top = ${style.padding.top}, left = ${style.padding.left}")
        println("colorHex = ${style.colorHex}")

        assertNotNull("background-color should not be null", style.backgroundColorHex)
        assertTrue("border-top width should be > 0, got ${style.border.top}", style.border.top > 0f)
        assertTrue("border-bottom width should be > 0, got ${style.border.bottom}", style.border.bottom > 0f)
        assertNotNull("border-color should not be null", style.borderColors?.top)
        assertNotNull("border-style should be recorded", style.borderStyles?.top)
    }
}
