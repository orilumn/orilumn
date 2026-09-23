package orilumn.reader.engine

import orilumn.reader.engine.html.MarkupElement

/**
 * Shared TOC-fragment ↔ page-char anchors (C1-2): pure markup walks both the Android
 * orchestration shell and the desktop host resolve identically, so the TOC "current page
 * headings" highlight and fragment jumps agree on every platform.
 *
 * Moved verbatim from the app shell — [MarkupElement] is already common, so no adaptation
 * was needed.
 *
 * P4-c1: anchor identity is now [anchorKeyOf] — `id` on any element, plus `name` on `<a>`
 * (HTML4 anchor target; `HtmlTreeConverter` preserves it). The legacy entry points below
 * walk raw markup text (all text nodes counted, no exclusions); the `*With` variants take
 * an injectable text-length/exclusion so callers with styled layout info (white-space
 * normalization, `display:none`) can resolve in the same character space the shaper used.
 * Exact leaf-exact resolution (leaf global start + in-leaf styled offset) lands in P4-c2.
 */

/** Anchor identity of [el]: `id` on any element, else `name` on `<a>`; null when neither. */
fun anchorKeyOf(el: MarkupElement): String? =
    el.attrs["id"] ?: (if (el.tag == "a") el.attrs["name"] else null)

/** All anchor identities of [el] (`id` and `<a name>` both count when present on one element). */
fun anchorKeysOf(el: MarkupElement): Set<String> {
    val out = LinkedHashSet<String>(2)
    el.attrs["id"]?.let { out.add(it) }
    if (el.tag == "a") el.attrs["name"]?.let { out.add(it) }
    return out
}

/**
 * The set of element `id`s in [markup] whose document-positioned text-start offset lands within
 * [charStart, charEnd). Pure so the TOC "current page headings" logic is unit-testable.
 */
fun contentFragmentIdsInPage(markup: MarkupElement, charStart: Int, charEnd: Int): Set<String> =
    contentFragmentIdsInPageWith(markup, charStart, charEnd, { it.text.length.toLong() }, { false })

/**
 * P4-c1 styled-capable variant of [contentFragmentIdsInPage].
 *
 * @param textLengthOf length contribution of a `#text` node in the caller's character space
 *   (raw `text.length`, or white-space-normalized length for styled resolution).
 * @param isExcluded true for elements whose subtree contributes no text (e.g. `display:none`).
 */
fun contentFragmentIdsInPageWith(
    markup: MarkupElement,
    charStart: Int,
    charEnd: Int,
    textLengthOf: (MarkupElement) -> Long,
    isExcluded: (MarkupElement) -> Boolean,
): Set<String> {
    val ids = LinkedHashSet<String>()
    fun walk(el: MarkupElement, base: Long): Long {
        if (isExcluded(el)) return base
        val keys = anchorKeysOf(el)
        if (keys.isNotEmpty()) {
            for (k in keys) if (base in charStart until charEnd) ids.add(k)
        }
        var run = base
        if (el.isText) run += textLengthOf(el)
        else for (c in el.children) run = walk(c, run)
        return run
    }
    walk(markup, 0)
    return ids
}

/**
 * The document-positioned text-start char offset of the first element carrying [id], or null when
 * the markup has no such element. Mirrors [contentFragmentIdsInPage]'s leaf walk exactly, so fragment
 * targeting agrees with the TOC highlight set: a depth>0 TOC row (e.g. `fragment = "sec3"`) resolves
 * to the char where its own heading's content starts. Pure → JVM unit-testable.
 *
 * P4-c1: matches `id` or `<a name>` (see [anchorKeyOf]).
 */
fun contentFragmentIdCharStart(markup: MarkupElement, id: String): Int? =
    contentFragmentIdCharStartWith(markup, id, { it.text.length.toLong() }, { false })

/**
 * P4-c1 styled-capable variant of [contentFragmentIdCharStart] (same [textLengthOf]/[isExcluded]
 * contract as [contentFragmentIdsInPageWith]).
 */
fun contentFragmentIdCharStartWith(
    markup: MarkupElement,
    id: String,
    textLengthOf: (MarkupElement) -> Long,
    isExcluded: (MarkupElement) -> Boolean,
): Int? {
    var hit: Long? = null
    fun walk(el: MarkupElement, base: Long): Long {
        if (hit != null || isExcluded(el)) return base
        if (id in anchorKeysOf(el)) { hit = base; return base }
        var run = base
        if (el.isText) run += textLengthOf(el)
        else for (c in el.children) run = walk(c, run)
        return run
    }
    walk(markup, 0)
    return hit?.toInt()
}
