package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.InlineMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选区完全落在元素内部时不给读者看标记」的拆分口径（用户定口）。
 */
class AmbientSpansTest {

    private fun span(start: Int, endExclusive: Int, tag: String, attrs: Map<String, String> = emptyMap()) =
        InlineMarkup.StyleSpan(start, endExclusive, InlineMarkup.StyleSig(tag, attrs))

    @Test
    fun wholeSelectionInsideElementIsAmbient() {
        val (ambient, visible) = splitAmbientSpans(listOf(span(0, 4, "code")), length = 4)
        assertEquals(1, ambient.size)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun elementStartCutBySelectionStaysVisible() {
        // 选区从元素中间开始：元素露了半截，读者要能看见并改得动。
        val (ambient, visible) = splitAmbientSpans(listOf(span(0, 6, "em")), length = 10)
        assertTrue(ambient.isEmpty())
        assertEquals(1, visible.size)
    }

    @Test
    fun innerElementStaysVisibleWhenOuterIsPartial() {
        // 外层只露出半截：内层再深也不能隐形，否则补回时会落到外层之外。
        val outer = span(0, 7, "em")
        val inner = span(2, 7, "code")
        val (ambient, visible) = splitAmbientSpans(listOf(outer, inner), length = 9)
        assertTrue(ambient.isEmpty())
        assertEquals(listOf(outer, inner), visible)
    }

    @Test
    fun nestedAmbientElementsAreBothHidden() {
        val outer = span(0, 5, "a", mapOf("href" to "https://example.com"))
        val inner = span(0, 5, "strong")
        val (ambient, visible) = splitAmbientSpans(listOf(outer, inner), length = 5)
        assertEquals(listOf(outer, inner), ambient)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun mixedSelectionKeepsOnlyTheCutElementVisible() {
        val ambient = span(0, 9, "code")
        val cut = span(2, 7, "em")
        val (hidden, visible) = splitAmbientSpans(listOf(ambient, cut), length = 9)
        assertEquals(listOf(ambient), hidden)
        assertEquals(listOf(cut), visible)
    }
}
