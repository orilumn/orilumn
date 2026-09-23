package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderStyle
import kotlin.math.roundToInt

/**
 * Resolves a [LayoutBox] tree into an ordered list of [DrawRect]s for painting (pure geometry).
 *
 * This is the drawing **model**, independent of any canvas: given the border-box geometry of every
 * box (filled during the flow pass in [NormalFlowLayout]), it emits the background fills and the
 * four border-edge bands that a renderer would stroke. Living far from Android keeps it JVM-testable;
 * a future renderer only needs to clip to the page's line region and paint these rects in order.
 *
 * Emission order is parent-before-child so backgrounds of ancestor boxes always sit beneath their
 * descendants' content/borders.
 *
 * [drawOnPage] additionally gates each box by its line-range intersection with the target page and
 * clips every rect to the page's visible line band — so pushed/torn blocks never leak a background
 * strip across page boundaries.
 */
object BoxDrawer {

    /**
     * Produces the draw rects for [boxes] in paint order (ancestors first).
     * Unfiltered: every box in the tree contributes a rect if it carries a background or border.
     *
     * @param boxes the top-level boxes (e.g. the root container) from a [BoxLayoutResult].
     * @return the resolved paint rects; empty when nothing carries a background or border.
     */
    fun draw(boxes: List<LayoutBox>): List<DrawRect> =
        drawOnPage(boxes, pageStartLine = 0, pageEndLine = Int.MAX_VALUE, bandTop = Int.MIN_VALUE, bandBottom = Int.MAX_VALUE)

    /**
     * Produces the draw rects for [boxes] whose line ranges intersect the target page
     * `[pageStartLine, pageEndLine)`, clipped to the page's visible vertical band
     * `[bandTop, bandBottom]`.
     *
     * This is the single source of page-aware background/border painting: a box whose
     * [LayoutBox.firstLineIndex] is entirely outside the page is skipped (a block pushed to the
     * next page or belonging to a previous page). Clipping is per-box, not one-size:
     * a box that *starts/ends inside* the page keeps its full border-box (padding included,
     * margin never painted) even past the line band — otherwise a chapter-ending block would
     * lose its bottom padding despite empty page space below; only torn continuations
     * (starting before / ending after the page) are cut at the band so they never leak strips
     * across page boundaries.
     *
     * @param pageStartLine first line index of the page (inclusive).
     * @param pageEndLine exclusive last line index of the page.
     * @param bandTop the first line's top (layout y) — the page's visual top edge.
     * @param bandBottom the last line's bottom (layout y) — the page's visual bottom edge.
     */
    fun drawOnPage(
        boxes: List<LayoutBox>,
        pageStartLine: Int,
        pageEndLine: Int,
        bandTop: Int,
        bandBottom: Int,
    ): List<DrawRect> {
        if (bandBottom <= bandTop) return emptyList()
        val out = ArrayList<DrawRect>()
        for (box in boxes) emit(box, out, pageStartLine, pageEndLine, bandTop, bandBottom)
        return out
    }

    private fun emit(
        box: LayoutBox,
        out: MutableList<DrawRect>,
        pageStartLine: Int,
        pageEndLine: Int,
        bandTop: Int,
        bandBottom: Int,
        parentAlpha: Float = 1f,
    ) {
        // Skip boxes whose lines don't intersect the target page — the block belongs entirely
        // to a different page (pushed via break-inside:avoid, or ended on a previous page).
        if (box.firstLineIndex >= 0 &&
            (box.lastLineExclusive <= pageStartLine || box.firstLineIndex >= pageEndLine)
        ) return

        // 渲染层：页带裁剪只约束撕裂块。在本页内开始/结束的箱子，其 border-box（含 padding，
        // 不含 margin）归属本页可见区，不得一刀切——否则章末块底 padding 明明有页空间却被裁掉。
        // 撕裂延续（起于页前/止于页后）仍按页带裁剪，不向邻页漏底。
        val top = if (box.firstLineIndex >= pageStartLine) minOf(bandTop, box.contentTop) else bandTop
        val bottom =
            if (box.lastLineExclusive in (pageStartLine + 1)..pageEndLine) maxOf(bandBottom, box.contentBottom)
            else bandBottom

        // P3-a: 祖先链 opacity 连乘（本盒叠乘后传给后代；1 即旧路径）。
        val alpha = (parentAlpha * box.style.opacity.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        emitBackground(box, out, top, bottom, alpha)
        emitBorders(box, out, top, bottom, alpha)
        for (child in box.childBoxes) emit(child, out, pageStartLine, pageEndLine, bandTop, bandBottom, alpha)
    }

    private fun emitBackground(box: LayoutBox, out: MutableList<DrawRect>, bandTop: Int, bandBottom: Int, alpha: Float) {
        if (box.contentWidth <= 0 || box.contentBottom <= box.contentTop) return
        val color = box.style.backgroundColorHex
        val shadow = box.style.boxShadow
        // P3-b: 背景图（url 为空即无图；随盒几何走同一 DrawRect，绘制层按 repeat/position 平铺）。
        val bgImage = box.style.backgroundImageUrl?.takeIf { it.isNotBlank() }?.let {
            BackgroundImage(it, box.style.backgroundRepeat, box.style.backgroundPosition)
        }
        if (color == null && shadow == null && bgImage == null) return
        val w = box.contentWidth
        val h = box.contentBottom - box.contentTop
        val radii = box.style.borderRadius.resolved(w, h, box.style.borderRadiusPct)
        val resolvedShadow = shadow?.copy(
            colorHex = shadow.colorHex ?: box.style.colorHex ?: FALLBACK_INK_HEX,
        )
        pushRect(
            out, DrawKind.BACKGROUND, box.contentTop, box.contentLeft, box.contentBottom, box.contentLeft + w,
            color ?: TRANSPARENT_HEX, bandTop, bandBottom, radii, alpha, resolvedShadow, bgImage = bgImage,
            bgBoxTop = box.contentTop, bgBoxBottom = box.contentBottom,
        )
    }

    /** Fallback ink for currentColor when no text color is chained (matches the theme default). */
    const val FALLBACK_INK_HEX = "#ff000000"

    /** Transparent fill (shadow-only carrier: no visible fill, only its shadow paints). */
    const val TRANSPARENT_HEX = "#00000000"

    /**
     * Emits the border edges. Square boxes keep the legacy per-edge bands (byte-identical).
     * A rounded box with a UNIFORM border (same width/color/solid style on all edges) emits one
     * stroked ring (outer radii); non-uniform/dashed/dotted rounded borders fall back to square
     * bands (documented approximation — corners stick out over the rounded background).
     */
    private fun emitBorders(box: LayoutBox, out: MutableList<DrawRect>, bandTop: Int, bandBottom: Int, alpha: Float) {
        val b = box.style.border
        if (box.contentWidth <= 0) return
        val st = box.style
        val current = st.colorHex ?: FALLBACK_INK_HEX
        val colors = st.borderColors
        val styles = st.borderStyles
        val l = box.contentLeft
        val r = box.contentLeft + box.contentWidth
        val top = box.contentTop
        val bottom = box.contentBottom
        val radii = st.borderRadius.resolved(box.contentWidth, (bottom - top).coerceAtLeast(0), st.borderRadiusPct)
        val ring = if (!radii.isSquare()) uniformRing(st, current) else null
        if (ring != null) {
            pushRect(out, DrawKind.BORDER, top, l, bottom, r, ring.colorHex, bandTop, bandBottom, radii, alpha, null, ring.widthPx)
            return
        }
        // Horizontal edges: band runs x ∈ [l, r].
        emitEdge(segKind = SegKind.HORIZONTAL, x0 = l, x1 = r, band0 = top, widthPx = b.top,
            color = colors?.top ?: current,
            style = styles?.top ?: BorderStyle.SOLID,
            out = out, bandTop = bandTop, bandBottom = bandBottom, alpha = alpha)
        emitEdge(segKind = SegKind.HORIZONTAL, x0 = l, x1 = r, band0 = bottom - b.bottom.roundToInt(), widthPx = b.bottom,
            color = colors?.bottom ?: current,
            style = styles?.bottom ?: BorderStyle.SOLID,
            out = out, bandTop = bandTop, bandBottom = bandBottom, alpha = alpha)
        // Vertical edges: band runs y ∈ [top, bottom].
        emitEdge(segKind = SegKind.VERTICAL, x0 = top, x1 = bottom, band0 = l, widthPx = b.left,
            color = colors?.left ?: current,
            style = styles?.left ?: BorderStyle.SOLID,
            out = out, bandTop = bandTop, bandBottom = bandBottom, alpha = alpha)
        emitEdge(segKind = SegKind.VERTICAL, x0 = top, x1 = bottom, band0 = r - b.right.roundToInt(), widthPx = b.right,
            color = colors?.right ?: current,
            style = styles?.right ?: BorderStyle.SOLID,
            out = out, bandTop = bandTop, bandBottom = bandBottom, alpha = alpha)
    }

    /** Uniform solid border (width/color resolved, currentColor folded) or null. */
    private fun uniformRing(st: orilumn.reader.engine.css.ComputedStyle, current: String): Ring? {
        val b = st.border
        if (b.top <= 0f || b.top != b.right || b.top != b.bottom || b.top != b.left) return null
        val cTop = st.borderColors?.top ?: current
        val cRight = st.borderColors?.right ?: current
        val cBottom = st.borderColors?.bottom ?: current
        val cLeft = st.borderColors?.left ?: current
        if (cTop != cRight || cTop != cBottom || cTop != cLeft) return null
        val s = st.borderStyles
        val solid = { v: BorderStyle? -> (v ?: BorderStyle.SOLID) == BorderStyle.SOLID }
        if (!solid(s?.top) || !solid(s?.right) || !solid(s?.bottom) || !solid(s?.left)) return null
        return Ring(b.top, cTop)
    }

    private class Ring(val widthPx: Float, val colorHex: String)

    private enum class SegKind { HORIZONTAL, VERTICAL }

    /**
     * Emits one edge band. [x0..x1] is the axis range (x for horizontal edges, y for vertical),
     * [band0] the band start on the cross axis and [widthPx] its thickness. Dashed/dotted edges
     * become a series of filled segment rects (dash 3×thickness, gap 2×thickness; dots are
     * thickness-sized squares every 2×thickness) — paintable by both renderers as plain fills.
     */
    private fun emitEdge(
        segKind: SegKind,
        x0: Int, x1: Int,
        band0: Int,
        widthPx: Float,
        color: String,
        style: BorderStyle,
        out: MutableList<DrawRect>,
        bandTop: Int,
        bandBottom: Int,
        alpha: Float = 1f,
    ) {
        val w = widthPx.roundToInt().coerceAtLeast(1)
        if (widthPx <= 0f || x1 <= x0) return
        if (style == BorderStyle.NONE) return
        fun emit(t: Int, l: Int, b: Int, r: Int) =
            pushRect(out, DrawKind.BORDER, t, l, b, r, color, bandTop, bandBottom, alpha = alpha)
        when (style) {
            BorderStyle.SOLID -> if (segKind == SegKind.HORIZONTAL) emit(band0, x0, band0 + w, x1)
            else emit(x0, band0, x1, band0 + w)
            BorderStyle.DASHED -> {
                val dash = (3 * w).coerceAtLeast(2)
                val gap = (2 * w).coerceAtLeast(1)
                var p = x0
                while (p < x1) {
                    val e = minOf(p + dash, x1)
                    if (segKind == SegKind.HORIZONTAL) emit(band0, p, band0 + w, e)
                    else emit(p, band0, e, band0 + w)
                    p += dash + gap
                }
            }
            BorderStyle.DOTTED -> {
                val step = (2 * w).coerceAtLeast(2)
                var p = x0
                while (p < x1) {
                    val e = minOf(p + w, x1)
                    if (segKind == SegKind.HORIZONTAL) emit(band0, p, band0 + w, e)
                    else emit(p, band0, e, band0 + w)
                    p += step
                }
            }
            BorderStyle.NONE -> Unit
        }
    }

    /** Clamps a rect to `[bandTop, bandBottom]` and appends if non-empty. */
    private fun pushRect(
        out: MutableList<DrawRect>,
        kind: DrawKind,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        color: String,
        bandTop: Int,
        bandBottom: Int,
        radii: orilumn.reader.engine.css.CornerRadius = orilumn.reader.engine.css.CornerRadius(),
        alpha: Float = 1f,
        shadow: orilumn.reader.engine.css.BoxShadow? = null,
        strokeWidthPx: Float = 0f,
        bgImage: BackgroundImage? = null,
        bgBoxTop: Int = 0,
        bgBoxBottom: Int = 0,
    ) {
        val t = maxOf(top, bandTop)
        val b = minOf(bottom, bandBottom)
        if (b > t) out.add(DrawRect(kind, t, left, b, right, color, radii, alpha, shadow, strokeWidthPx, bgImage, bgBoxTop, bgBoxBottom))
    }
}

/**
 * P3-a: 祖先链 opacity 连乘（根→叶；缺省样式按 1）。重/轻两路＋桌面三处同函数，
 * 与盒流几何无关，纯绘制合成。
 */
fun effectiveOpacity(
    el: orilumn.reader.engine.html.MarkupElement?,
    styleOf: (orilumn.reader.engine.html.MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
): Float {
    var a = 1f
    var n = el
    while (n != null) {
        a *= styleOf(n)?.opacity?.coerceIn(0f, 1f) ?: 1f
        n = n.parent
    }
    return a.coerceIn(0f, 1f)
}
