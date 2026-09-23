package orilumn.reader.engine.layout

import android.graphics.Canvas
import orilumn.reader.engine.paging.BookLayout

/**
 * A [BookLayout] that can also draw itself onto a [Canvas] — the widen seam between the pure
 * line-level geometry engine and Android's rendering.
 *
 * Both the legacy [orilumn.reader.engine.text.StaticLayoutBookLayout] and the new
 * [orilumn.reader.engine.text.BoxDrawableLayout] implement this, so [orilumn.reader.engine.render.PageRenderer]
 * draws either without knowing the backing. The caller has already translated the canvas to the
 * content-area origin and will clip to the viewport.
 */
interface DrawableBookLayout : BookLayout {

    /**
     * Draws one page: the line range `[firstLine, lastLineExclusive)`.
     *
     * Drawing is strictly bounded to the slice's own lines — never past [lastLineExclusive] — so a
     * page that the paginator deliberately shortens to a paragraph boundary (break-inside / long-
     * paragraph retreat) does not overdraw the next page's first lines (which would otherwise show a
     * repeated paragraph at this page's bottom and a half line at its edge).
     *
     * @param canvas target canvas, already translated to the content-area origin.
     * @param firstLine first line of this page (aligned to the content-area top).
     * @param lastLineExclusive exclusive last line of this page.
     * @param contentW content-area width (px).
     * @param contentH content-area height (px).
     */
    fun drawPageSlice(canvas: Canvas, firstLine: Int, lastLineExclusive: Int, contentW: Int, contentH: Int)

    /**
     * **Single-source** first-line top-alignment: translates [canvas] so the page's first line sits at
     * the content-area top (vertical reset to the slice's leading line). Both draw implementations
     * ([orilumn.reader.engine.text.BoxDrawableLayout] and [orilumn.reader.engine.BoxChapterLayouter]'s
     * `PartialDrawableLayout`) call this instead of re-deriving the translate, so they cannot drift.
     * @return the page's original first-line top offset (px), should a caller need it.
     */
    fun alignPageTop(canvas: Canvas, firstLine: Int): Int {
        val pageTop = getLineTop(firstLine)
        canvas.translate(0f, -pageTop.toFloat())
        return pageTop
    }

    /**
     * Debug only: the exact text this drawable paints for lines `[firstLine, lastLineExclusive)`,
     * read back from each leaf's own shaped paragraph (the same source [`drawPageSlice`] feeds to
     * `StaticLayout.draw`). Because it reads the draw layout rather than re-walking the markup tree,
     * it shares the engine's char space and **cannot drift from the screen** the way a whole-tree text
     * walk does (which counts inter-block whitespace the leaf accumulator drops).
     *
     * Inline image placeholders (`U+FFFC`) render as `[img]`; replaceable blocks (img / table) as
     * `[img]` / `[table]`. Returns "" when there is nothing to read.
     */
    fun debugPageText(firstLine: Int, lastLineExclusive: Int): String = ""
}