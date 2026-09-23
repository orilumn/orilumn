package orilumn.reader.engine

import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile

/**
 * The **layout seam** between [BookDocumentController] and the rendering engine (the shared box
 * engine behind which both platforms read the same geometry).
 *
 * It turns a chapter's semantic tree + its author CSS + the reading profile into a drawable layout
 * plus its pages. [BookDocumentController.buildLayout]/`prepareRelayout` route exclusively through
 * this, so the controller never touches rendering internals. The legacy StaticLayout path retired
 * with Q1; only the box engine remains.
 */
interface ChapterLayouter {

    /**
     * @param markup the chapter body tree.
     * @param cssBundle the chapter's author stylesheets (may be null/empty).
     * @param profile the effective reading profile.
     * @param contentW content-area width (px), the line-wrapping basis.
     * @param contentH content-area height (px), the pagination basis.
     * (C2-P2b-3: `pairing` deleted — shaping is paint-free; paint lives in `:app` only.)
     * @return the laid-out pages, or null when layout fails (caller degrades to an empty chapter).
     */
    fun layout(
        markup: MarkupElement,
        cssBundle: CssBundle?,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
    ): ChapterLayoutProduct?

    /** The product of a successful layout: a paged layout plus its page slices. */
    class ChapterLayoutProduct(
        val layout: BookLayout,
        val slices: List<PageSlice>,
    )
}