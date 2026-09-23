package orilumn.reader.engine.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Converts chapter XHTML → [MarkupElement] semantic tree (pure, unit-testable).
 *
 * Uses Ksoup (jsoup's KMP port) HTML5 fault-tolerant parser exclusively, which:
 * - Accepts well-formed XHTML (most EPUBs)
 * - Repairs malformed XHTML (unclosed tags, bare `&`, comments with internal `--`)
 * - Preserves element structure so layout never sees a flat text blob
 *
 * Whitelist strategy: whitelisted tags are kept semantically; non-whitelisted tags are
 * "de-shelled" — only their child nodes pass through and the tag's own attributes are dropped,
 * eliminating messy-book interfering styles at the source. Layout-relevant style info is kept in
 * [MarkupElement.attrs]'s inline `style` value for later parsing by [CssInlineResolver].
 */
class HtmlTreeConverter {

    /**
     * Parses XHTML with Ksoup, returning the body tree and CSS sources.
     */
    private fun parse(xhtml: String): Pair<MarkupElement?, Pair<List<String>, List<String>>> {
        val cleaned = xhtml.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        val doc = Ksoup.parse(cleaned)
        val tree = bodyTree(doc)
        // The lazy-cascade path (`StyleComputer.resolve`) walks MarkupElement.parent for inheritance and
        // container-width propagation, so the parent chain MUST be populated here for every node.
        tree?.let { fillParents(it, null) }
        val css = collectCssSources(doc)
        return tree to css
    }

    /** Fills [MarkupElement.parent] for the whole subtree (roots have null). Single DFS after build, so
     *  every consumer — full-chapter and lazy — sees a consistent ancestor chain. */
    private fun fillParents(node: MarkupElement?, parent: MarkupElement?) {
        if (node == null) return
        node.parent = parent
        for (c in node.children) fillParents(c, node)
    }

    /**
     * Converts a chapter XHTML to its body [MarkupElement] tree only (legacy entry point).
     *
     * `<head>`/`<style>`/`<script>` content is intentionally dropped here, just like before; callers
     * that need the chapter's author stylesheets should use [convertWithStyles] instead.
     */
    fun convert(xhtml: String): MarkupElement? {
        val (tree, _) = parse(xhtml)
        return tree
    }

    /**
     * Parses a chapter and returns both the body [MarkupElement] tree and the chapter's CSS sources
     * (embedded `<style>` text and `rel=stylesheet` `<link>` hrefs) as a [ParsedChapter].
     *
     * The body tree is identical to [convert]; this is the entry point the typesetting engine uses
     * so author stylesheets can reach the cascade. Ksoup preserves element structure even for
     * malformed XHTML — callers never see a flat text blob.
     */
    fun convertWithStyles(xhtml: String): ParsedChapter? {
        val (tree, css) = parse(xhtml)
        val (styles, linkHrefs) = css
        return tree?.let { ParsedChapter(tree = it, styles = styles, linkHrefs = linkHrefs) }
    }

    // ------------------------------------------------------------------
    // Ksoup helpers
    // ------------------------------------------------------------------

    private fun bodyTree(doc: com.fleeksoft.ksoup.nodes.Document): MarkupElement? {
        val body = doc.body()
        return MarkupElement(
            "body",
            // 根 body 的 class/id 保留进级联（`body.xxx …` 选择器与 body id 锚点，浏览器标准行为；
            // 无属性即与旧 tree 逐字一致）。`style` 仍不保留，与 toMarkup 的 body/html 分支同口径，
            // 避免体级内联样式突然参与级联改变既有渲染。
            attribs(body, "class", "id", "bgcolor", "background"),
            children = body.children().mapNotNull { toMarkup(it) },
        )
    }

    private fun collectCssSources(doc: com.fleeksoft.ksoup.nodes.Document): Pair<List<String>, List<String>> {
        val styles = doc.select("style").mapNotNull { it.data().trim().takeIf { s -> s.isNotEmpty() } }
        val linkHrefs = doc.select("link[rel~=stylesheet]").mapNotNull {
            it.attr("href").trim().takeIf { h -> h.isNotEmpty() }
        }
        return styles to linkHrefs
    }

    private fun toMarkup(el: Element): MarkupElement? {
        val tag = el.tagName().lowercase()
        return when (tag) {
            "body", "html" -> MarkupElement(
                "body",
                // P3-b: body 的表示型背景属性保留（bgcolor/background 下沉进级联；style 仍不保留，
                // 避免体级内联样式突然参与级联改变既有渲染）。
                attribs(el, "bgcolor", "background"),
                children = el.children().mapNotNull { toMarkup(it) },
            )
            "style", "script", "head", "title", "link", "meta" -> null
            // `ol` keeps its start / reversed / type attributes (list numbering) — handled before the
            // generic block branch because `reversed` is a boolean attribute whose raw value is ""
            // and would otherwise be dropped by attribs().
            "ol" -> MarkupElement(tag, olAttrs(el), childNodesInOrder(el))
            in BLOCK_TAGS -> MarkupElement(
                tag,
                attribs(el, "style", "align", "class", "id", "bgcolor", "background", "cite", "value", "summary"),
                childNodesInOrder(el),
            )
            // `a` additionally keeps its `href` (link target) and `name` (anchor target) so the render
            // layer can build link/anchor navigation.
            "a" -> MarkupElement(tag, attribs(el, "style", "title", "class", "id", "href", "name"), childNodesInOrder(el))
            in INLINE_TAGS -> MarkupElement(tag, attribs(el, "style", "title", "class", "id", "cite", "datetime"), childNodesInOrder(el))
            // `br` keeps `clear`（HTML4 表示型布尔/关键字属性）so `br clear="all"` can later drive
            // block-level float clearing (P4); preservation happens here, consumption is later.
            "br" -> MarkupElement("br", attribs(el, "clear"))
            "img" -> MarkupElement("img", attribs(el, "src", "alt", "title", "style", "width", "height", "border", "align", "class", "srcset", "sizes"))
            // Tables are preserved as a nested structure (table > section/row > cell) instead of being
            // flattened, so the box layer can build a real 2D grid. Cells keep their colspan/rowspan.
            // colgroup/col are kept as column metadata: TableGridModel reads only tr/section children,
            // so they pass through layout untouched yet remain available to a future column model.
            "table", "thead", "tbody", "tfoot", "tr" ->
                MarkupElement(
                    tag,
                    attribs(el, "style", "align", "class", "id", "border", "bgcolor", "background", "summary", "cellpadding", "cellspacing", "valign"),
                    childNodesInOrder(el),
                )
            "colgroup", "col" ->
                MarkupElement(tag, attribs(el, "style", "align", "class", "id", "span", "width"), childNodesInOrder(el))
            "td", "th", "caption" ->
                MarkupElement(tag, cellAttrs(el), childNodesInOrder(el))
            else -> MarkupElement(tag = STRIP_TAG, children = childNodesInOrder(el))
        }
    }

    /**
     * Collects child DOM nodes in document order, interleaving element children with text runs.
     * Uses [TextNode.getWholeText] (not `.text()`) so raw whitespace inside `<pre>` and code blocks
     * is preserved — Ksoup's `.text()` would fold it.
     */
    private fun childNodesInOrder(el: Element): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        val text = StringBuilder()
        for (node in el.childNodes()) {
            when (node) {
                is Element -> {
                    if (text.isNotEmpty()) { out.add(MarkupElement("#text", text = text.toString())); text.setLength(0) }
                    toMarkup(node)?.let { out.add(it) }
                }
                is TextNode -> text.append(node.getWholeText())
                else -> Unit // comments, data nodes, etc. — skip
            }
        }
        if (text.isNotEmpty()) out.add(MarkupElement("#text", text = text.toString()))
        return out
    }

    /** `ol` list-numbering attributes; adds `reversed` (a boolean attr whose raw value is "") so
     *  the box/render layers can do ordered-list numbering. */
    private fun olAttrs(el: Element): Map<String, String> {
        val out = HashMap(attribs(el, "style", "align", "class", "id", "start", "type"))
        if (el.hasAttr("reversed")) out["reversed"] = "true"
        return out
    }

    /** `td`/`th`/`caption` HTML4 表示型属性：网格（colspan/rowspan）+ 单元格外观（bgcolor/background/
     *  valign）+ 无障碍（scope/headers）。`nowrap` 是布尔属性（原始值为 ""），仿 [olAttrs] 特判保留。 */
    private fun cellAttrs(el: Element): Map<String, String> {
        val out = HashMap(
            attribs(el, "style", "align", "class", "id", "colspan", "rowspan", "bgcolor", "background", "valign", "scope", "headers", "width", "height"),
        )
        if (el.hasAttr("nowrap")) out["nowrap"] = "true"
        return out
    }

    private fun attribs(el: Element, vararg names: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (n in names) {
            val v = el.attr(n)
            if (v.isNotEmpty()) out[n] = v
        }
        // Global semantic attributes, captured on any element so downstream box/render/nav layers and
        // attribute selectors can see them: canonical `lang` (prefer xml:lang, fall back to lang),
        // text direction `dir`, EPUB structure role `epub:type`, ARIA `role`, and the generic
        // `aria-*`/`data-*` prefixes.
        val xmlLang = el.attr("xml:lang")
        val lang = el.attr("lang")
        when {
            xmlLang.isNotEmpty() -> out["lang"] = xmlLang
            lang.isNotEmpty() -> out["lang"] = lang
        }
        el.attr("dir").takeIf { it.isNotEmpty() }?.let { out["dir"] = it }
        el.attr("epub:type").takeIf { it.isNotEmpty() }?.let { out["epub:type"] = it }
        el.attr("role").takeIf { it.isNotEmpty() }?.let { out["role"] = it }
        for (a in el.attributes()) {
            val k = a.key
            if (k.startsWith("aria-") || k.startsWith("data-")) out[k] = a.value
        }
        return out
    }

    companion object {
        /** Tag marker for a de-shelled container (see stripWrapper). */
        const val STRIP_TAG = ""

        /** Block-level tags (affect paragraph splitting). Kept at least as wide as the box authority
         *  set `NormalFlowLayout.BLOCK_TAGS` so every known box block survives parsing as a semantic
         *  node instead of being de-shelled into bare text. */
        val BLOCK_TAGS = setOf(
            "p", "div", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "section", "header", "footer", "figure", "figcaption",
            "article", "aside", "nav", "dl", "dt", "dd", "address", "hr",
            "main", "hgroup", "details", "summary",
        )

        /** Inline tags (follow the text flow; do not split paragraphs).
         *  `kbd`/`samp`/`tt` stay semantic alongside `code` so the UA sheet's
         *  `code, kbd, samp, tt { font-family: monospace }` reaches the cascade instead of
         *  being de-shelled (tag dropped, monospace lost). EPUB2/3 附加行内标签
         *  （s/del/ins/abbr/acronym/dfn/cite/var/mark/time/data/bdi/bdo/wbr/ruby/rt/rp）一并
         *  保留语义，UA 层给出对应默认渲染。P6-b：注音结构标签（rbc/rtc/rb）透明行内容器，
         *  子文本顺排，叠排配对见 [RubyRuns]。 */
        val INLINE_TAGS = setOf(
            "strong", "b", "em", "i", "u", "a", "code", "kbd", "samp", "tt",
            "span", "sub", "sup", "q", "small", "big",
            "s", "del", "ins", "abbr", "acronym", "dfn", "cite", "var",
            "mark", "time", "data", "bdi", "bdo", "wbr", "ruby", "rt", "rp",
            "rbc", "rtc", "rb",
        )
    }
}
