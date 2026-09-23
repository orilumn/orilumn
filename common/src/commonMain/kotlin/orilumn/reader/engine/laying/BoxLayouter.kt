package orilumn.reader.engine.laying

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout

/**
 * S3 — box-flow layouter entry point.
 *
 * Computes per-node styles (real cascade), flattens the body into paragraph blocks, flows them into a
 * continuous line stream via [NormalFlowLayout], and wraps it as a [BookLayout] ([BoxBookLayout]) ready
 * for the existing Paginator. It deliberately produces only the line-level geometry [BookLayout]
 * exposes — rendering/pagination consume the same interface, so nothing here touches drawing yet.
 *
 * @param rootFontPx the base/UA body font size (root for `rem` and default size).
 * @param breaker the text shaper (production: the engine-skia `SkiaParagraphBreaker`; tests: a deterministic fake).
 */
class BoxLayouter(
    private val rootFontPx: Float,
    private val breaker: ParagraphBreaker,
) {
    /**
     * Lays a body tree out as a continuous [BookLayout] line stream.
     *
     * @param root the body tree.
     * @param widthPx content width (px) for line breaking.
     * @param authorSheets parsed chapter stylesheets to participate in the cascade.
     * @param uaSheet reading-profile UA sheet; empty means author + inline only.
     */
    fun layout(root: MarkupElement, widthPx: Int, authorSheets: List<orilumn.reader.engine.css.StyleSheet>, uaSheet: orilumn.reader.engine.css.StyleSheet = orilumn.reader.engine.css.StyleSheet(emptyList())): BookLayout {
        val styleMap = StyleComputer(rootFontPx, uaSheet, authorSheets).compute(root)
        return layoutWith(root, widthPx, styleMap)
    }

    /** Same as [layout] but with a precomputed style map (allows tests to reuse one cascade). */
    fun layoutWith(root: MarkupElement, widthPx: Int, styleMap: Map<MarkupElement, ComputedStyle>): BookLayout =
        BoxBookLayout(layoutBoxes(root, widthPx, styleMap))

    /**
     * Full box-model layout: returns the continuous line stream **and** the box tree (for
     * background/border drawing via [BoxDrawer]).
     *
     * @param root the body tree.
     * @param widthPx content width (px) for line breaking.
     * @param styleMap per-node computed styles from the cascade.
     * @param classify block classification (default: tag blocks + cascaded CSS display:block).
     *   Callers with a [StyleComputer] should pass [NormalFlowLayout.heavyClassify] so the display gate
     *   stays symmetric with the light path.
     */
    fun layoutBoxes(
        root: MarkupElement,
        widthPx: Int,
        styleMap: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true },
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): BoxLayoutResult =
        NormalFlowLayout.layout(
            root, styleMap, breaker, widthPx, classify,
            HiddenCheck { styleMap[it]?.displayNone == true },
            imageLoader, chapterHref, genOf,
        )
}