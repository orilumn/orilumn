package orilumn.reader.engine.html

import orilumn.reader.engine.css.Cascade
import orilumn.reader.engine.css.StyleSheet

/**
 * User-layer annotation pass: inspects the parsed chapter tree (author stylesheets included) and
 * adds the `orilumn-fullwidth-image` class to `<img>` elements that qualify for automatic full-width
 * stretching under the Traditional/Modern reader themes.
 *
 * An image qualifies only when BOTH hold:
 *  1. it is the **sole non-whitespace content** of its block container — a `<p><img/></p>`,
 *     `<figure><img/></figure>` or `<div><img/></div>`-style figure; inline images mixed with text
 *     are excluded;
 *  2. the **book** declared no `width`/`max-width` for it — in HTML attributes, inline `style`, or
 *     its own stylesheets (UA / theme / settings / UI layers are excluded, so the reader's own
 *     `img{max-width:100%}` and theme rules never count as a "book" constraint).
 *
 * The result is a copy-on-write rebuilt tree (`MarkupElement` fields are `val`), so [root] is
 * never structurally mutated. Because the class depends only on the DOM plus author CSS, it is safe
 * to cache with the chapter tree and survives theme switches — the stretch rule itself lives in a
 * theme stylesheet, so it only activates in Traditional/Modern modes.
 */
object ChapterPreprocessor {

    /** The class that the Traditional/Modern theme stylesheets target with `width: 100%`. */
    const val FULLWIDTH_CLASS = "orilumn-fullwidth-image"

    /**
     * Preprocesses [root] (plus its [authorSheets]) and returns a new tree in which qualifying
     * `<img>` elements carry [FULLWIDTH_CLASS]. The original tree is left readable; only `parent`
     * back-pointers of reused nodes are re-linked to the rebuilt copies.
     */
    fun preprocess(root: MarkupElement, authorSheets: List<StyleSheet>): MarkupElement {
        val cascade = Cascade(null, authorSheets)
        val newRoot = rebuild(root, cascade)
        relinkParents(newRoot)
        return newRoot
    }

    /** True when the raw value of a CSS size property declares "no constraint" and therefore must
     *  not trigger a skip ([bookDeclaredSize] / [qualifies]). */
    internal fun isNoneOrAuto(value: String): Boolean {
        val t = value.trim().lowercase()
        return t == "none" || t == "auto" || t == "initial" || t == "inherit"
    }

    /** Copy-on-write rebuild: copies only the path(s) from a changed leaf up to the root. */
    private fun rebuild(el: MarkupElement, cascade: Cascade): MarkupElement {
        if (el.isText) return el

        var changed = false
        val newChildren = el.children.map { child ->
            if (child.tag == "img" && qualifies(child, el, cascade)) {
                changed = true
                MarkupElement("img", child.attrs + ("class" to mergeClass(child.attrs["class"])))
            } else {
                val rebuilt = rebuild(child, cascade)
                if (rebuilt !== child) changed = true
                rebuilt
            }
        }
        if (!changed) return el
        return MarkupElement(el.tag, el.attrs, newChildren, el.text)
    }

    /** BOTH checks from the plan: sole-content block figure AND no book-declared size. */
    private fun qualifies(img: MarkupElement, parent: MarkupElement?, cascade: Cascade): Boolean {
        val p = parent ?: return false
        if (!isSoleContent(img, p)) return false
        return !bookDeclaredSize(img, cascade)
    }

    /** Whether [img] is the only non-whitespace content of [p] (whitespace-only text nodes and
     *  `<br>` placeholders around the image are ignored — publishers commonly emit `<p><img/><br/>`
     *  for a figure — so `<p><img/></p>`, `<p>  <img/> </p>` and `<p><img/><br/></p>` all qualify). */
    private fun isSoleContent(img: MarkupElement, p: MarkupElement): Boolean {
        var content = 0
        var selfFound = false
        for (child in p.children) {
            if (child.isText) {
                if (!child.text.isBlank()) content++
            } else if (child.tag == "br") {
                // formatting placeholder, not figure content
            } else {
                content++
                if (child === img) selfFound = true
            }
        }
        return content == 1 && selfFound
    }

    /** Whether the book declared a stopping `width`/`max-width` for [img] through any author origin:
     *  stylesheets (tier 20), inline `style` (tier 30) or HTML `width` attr (tier 15). `none`/`auto`/
     *  `initial`/`inherit` values express "no constraint" and are treated as undeclared. */
    private fun bookDeclaredSize(img: MarkupElement, cascade: Cascade): Boolean {
        // Ancestor chain root-first, element-last (the contract `Selector.matches` expects).
        val ancestors = img.ancestorsOrRoot.drop(1).toList().reversed()
        val winners = cascade.winningDeclarations(img, ancestors, cascade.parseInline(img.attrs["style"]))
        val width = winners["width"]
        if (width != null && !isNoneOrAuto(width)) return true
        val maxWidth = winners["max-width"] ?: return false
        return !isNoneOrAuto(maxWidth)
    }

    private fun mergeClass(existing: String?): String =
        if (existing.isNullOrBlank()) FULLWIDTH_CLASS else "$existing $FULLWIDTH_CLASS"

    /** Re-links `parent` back-pointers on the rebuilt tree (identical to the converter's post-parse
     *  pass): reused subtree roots have their parent pointer repointed to the new copies. */
    private fun relinkParents(root: MarkupElement) {
        for (child in root.children) {
            child.parent = root
            relinkParents(child)
        }
    }
}