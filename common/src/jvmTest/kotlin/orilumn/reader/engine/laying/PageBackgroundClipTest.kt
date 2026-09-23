package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BoxDrawer.drawOnPage] — background/border painting filtered by page ownership and
 * clipped to the page's visible line band. Covers the two reported artifacts:
 *
 *  1. **Pushed block strip**: a `break-inside:avoid` block pushed to the next page must not
 *     paint its top border+padding band over the current page's bottom lines.
 *  2. **Torn block overflow**: a block split across pages must not paint its background into the
 *     whitespace below the current page's last line.
 */
class PageBackgroundClipTest {

    private fun style(bg: String? = null, padTop: Int = 0, padBottom: Int = 0): ComputedStyle =
        ComputedStyle(
            fontSizePx = 12f,
            lineHeightRatio = 1.5f,
            padding = Edges(top = padTop.toFloat(), bottom = padBottom.toFloat()),
            backgroundColorHex = bg,
        )

    private fun box(
        bg: String? = null,
        contentTop: Int,
        contentBottom: Int,
        contentWidth: Int = 400,
        contentLeft: Int = 0,
        padTop: Int = 0,
        padBottom: Int = 0,
        firstLine: Int = -1,
        lastLineExcl: Int = -1,
    ): LayoutBox =
        LayoutBox(
            el = null,
            style = style(bg, padTop, padBottom),
            contentLeft = contentLeft,
            contentWidth = contentWidth,
        ).apply {
            this.contentTop = contentTop
            this.contentBottom = contentBottom
            this.firstLineIndex = firstLine
            this.lastLineExclusive = lastLineExcl
        }

    private fun draw(boxes: List<LayoutBox>, pageStart: Int, pageEnd: Int, bandTop: Int, bandBottom: Int) =
        BoxDrawer.drawOnPage(boxes, pageStart, pageEnd, bandTop, bandBottom)

    private fun DrawRect.centerY() = (top + bottom) / 2

    // ---------------------------------------------------------------- full draw compat

    @Test
    fun `unfiltered draw renders all rects without gating or clipping`() {
        val rect = BoxDrawer.draw(listOf(box(bg = "#ffeeeeee", contentTop = 0, contentBottom = 100, firstLine = 0, lastLineExcl = 5)))
        assertEquals(1, rect.size)
        assertEquals(0, rect[0].top)
        assertEquals(100, rect[0].bottom)
    }

    // ------------------------------------------------------------------- torn block

    @Test
    fun `torn block background is clipped to band bottom`() {
        // Block lines [2,7): torn at page boundary; page lines [2,5). band = [line2Top=100, line4Bot=240].
        // Block contentBottom=400 extends well past bandBottom.
        // 块起于本页：顶 padding（90..100）归属本页保留；底撕裂延续，仍裁到行带。
        val b = box(bg = "#ffaaaaaa", contentTop = 90, contentBottom = 400, firstLine = 2, lastLineExcl = 7)
        val rects = draw(listOf(b), pageStart = 2, pageEnd = 5, bandTop = 100, bandBottom = 240)
        assertEquals(1, rects.size)
        assertEquals(90, rects[0].top)
        assertEquals(240, rects[0].bottom)
        assertEquals("#ffaaaaaa", rects[0].colorHex)
    }

    @Test
    fun `torn block top is clipped to band top when block starts above page`() {
        // Block from previous page: contentTop=50, lines [0,6); page band [3,6)= [150,300].
        // 块止于本页末：底 padding（300..350）归属本页保留；顶上页延续，仍裁到行带。
        val b = box(bg = "#ffeeeeee", contentTop = 50, contentBottom = 350, firstLine = 0, lastLineExcl = 6)
        val rects = draw(listOf(b), pageStart = 3, pageEnd = 6, bandTop = 150, bandBottom = 300)
        assertEquals(1, rects.size)
        assertEquals(150, rects[0].top)
        assertEquals(350, rects[0].bottom)
    }

    // ------------------------------------------------------------------ pushed block

    @Test
    fun `pushed block is entirely excluded when its range does not intersect the page`() {
        // Block lines [5,10); page lines [0,5). No intersection → skip.
        val b = box(bg = "#ffeeeeee", contentTop = 200, contentBottom = 500, firstLine = 5, lastLineExcl = 10)
        val rects = draw(listOf(b), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 240)
        assertTrue(rects.isEmpty())
    }

    @Test
    fun `pushed block with top edges overlapping band is fully excluded by range gate`() {
        // Block lines [5,10), but border-box top at 235 (just above bandBottom=240 via padding).
        // Range gate: 5 >= 5 → skip entirely, regardless of border/padding overlap.
        val b = box(bg = "#ffeeeeee", contentTop = 235, contentBottom = 400, padTop = 5,
            firstLine = 5, lastLineExcl = 10)
        val rects = draw(listOf(b), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 240)
        assertTrue(rects.isEmpty())
    }

    @Test
    fun `pushed block with zero margin collapses right at band edge is excluded`() {
        // Block lines [5,10); page lines [0,5); contentTop = bandBottom = 240.
        // Range gate: firstLineIndex=5 >= pageEnd=5 → skip.
        val b = box(bg = "#ffeeeeee", contentTop = 240, contentBottom = 500, firstLine = 5, lastLineExcl = 10)
        val rects = draw(listOf(b), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 240)
        assertTrue(rects.isEmpty())
    }

    // ---------------------------------------------------------------- block on page

    @Test
    fun `block ending on page passes through unclipped`() {
        // Lines [3,5); page [3,5); contentBottom=200 ≤ bandBottom=240.
        val b = box(bg = "#ffeeeeee", contentTop = 100, contentBottom = 200, firstLine = 3, lastLineExcl = 5)
        val rects = draw(listOf(b), pageStart = 3, pageEnd = 5, bandTop = 100, bandBottom = 240)
        assertEquals(1, rects.size)
        assertEquals(100, rects[0].top)
        assertEquals(200, rects[0].bottom)
    }

    @Test
    fun `block fully within band is painted unchanged`() {
        val b = box(bg = "#ffeeeeee", contentTop = 50, contentBottom = 200, firstLine = 1, lastLineExcl = 4)
        val rects = draw(listOf(b), pageStart = 0, pageEnd = 10, bandTop = 0, bandBottom = 300)
        assertEquals(1, rects.size)
        assertEquals(50, rects[0].top)
        assertEquals(200, rects[0].bottom)
    }

    // ----------------------------------------------------------- mixed multi-rect

    @Test
    fun `mixed rects are gated and clipped independently`() {
        val pushed  = box(bg = "#ffaaaaaa", contentTop = 250, contentBottom = 500, firstLine = 5, lastLineExcl = 10)
        val current = box(bg = "#ffbbbbbb", contentTop = 100, contentBottom = 200, firstLine = 0, lastLineExcl = 3)
        val torn    = box(bg = "#ffcccccc", contentTop = 50,  contentBottom = 350, firstLine = 2, lastLineExcl = 7)

        val rects = draw(listOf(pushed, current, torn), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 240)
        assertEquals(2, rects.size)
        // current block unchanged
        assertEquals(100, rects[0].top)
        assertEquals(200, rects[0].bottom)
        assertEquals("#ffbbbbbb", rects[0].colorHex)
        // torn block clipped
        assertEquals(50, rects[1].top)
        assertEquals(240, rects[1].bottom)
        assertEquals("#ffcccccc", rects[1].colorHex)
    }

    // -------------------------------------------------------- edge / empty cases

    @Test
    fun `rect entirely outside band but intersecting page is clipped to band`() {
        // Block [2,8); page [3,6); contentBottom=500 far above bandBottom=240.
        val b = box(bg = "#ffeeeeee", contentTop = 100, contentBottom = 500, firstLine = 2, lastLineExcl = 8)
        val rects = draw(listOf(b), pageStart = 3, pageEnd = 6, bandTop = 150, bandBottom = 240)
        assertEquals(1, rects.size)
        assertEquals(150, rects[0].top)
        assertEquals(240, rects[0].bottom)
    }

    @Test
    fun `empty rects list returns empty`() {
        assertTrue(draw(emptyList(), pageStart = 0, pageEnd = 10, bandTop = 0, bandBottom = 100).isEmpty())
    }

    @Test
    fun `invalid band returns empty`() {
        val b = box(bg = "#ffeeeeee", contentTop = 0, contentBottom = 100, firstLine = 0, lastLineExcl = 5)
        assertTrue(draw(listOf(b), pageStart = 0, pageEnd = 5, bandTop = 100, bandBottom = 0).isEmpty())
    }

    @Test
    fun `box without line attribution is always painted`() {
        // firstLineIndex = -1 (no lines, e.g. empty block) → not gated → painted/clipped to band.
        val b = box(bg = "#ffeeeeee", contentTop = 50, contentBottom = 300, firstLine = -1, lastLineExcl = -1)
        val rects = draw(listOf(b), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 200)
        assertEquals(1, rects.size)
        assertEquals(50, rects[0].top)
        assertEquals(200, rects[0].bottom)
    }

    @Test
    fun `box whose range ends exactly at pageStart is excluded`() {
        val b = box(bg = "#ffeeeeee", contentTop = 0, contentBottom = 100, firstLine = 0, lastLineExcl = 5)
        assertTrue(draw(listOf(b), pageStart = 5, pageEnd = 10, bandTop = 100, bandBottom = 200).isEmpty())
    }

    // ------------------------------------------------------- container nesting

    @Test
    fun `container children are gated independently`() {
        // Container [0,10) contains childA [0,4) on page and childB [6,10) pushed.
        val childA = box(bg = "#ffaaaaaa", contentTop = 10, contentBottom = 100, firstLine = 0, lastLineExcl = 4)
        val childB = box(bg = "#ffbbbbbb", contentTop = 110, contentBottom = 200, firstLine = 6, lastLineExcl = 10)
        val container = LayoutBox(
            el = null,
            style = style(),
            contentLeft = 0,
            contentWidth = 400,
            childBoxes = listOf(childA, childB),
        ).apply {
            this.contentTop = 0
            this.contentBottom = 210
            this.firstLineIndex = 0
            this.lastLineExclusive = 10
        }

        val rects = draw(listOf(container), pageStart = 0, pageEnd = 5, bandTop = 0, bandBottom = 120)
        // childB excluded (6 >= 5); container has no bg; childA painted.
        assertEquals(1, rects.size)
        assertEquals("#ffaaaaaa", rects[0].colorHex)
    }
}
