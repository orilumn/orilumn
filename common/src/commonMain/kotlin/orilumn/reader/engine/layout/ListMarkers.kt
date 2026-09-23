package orilumn.reader.engine.layout

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.HiddenCheck
import kotlin.math.roundToInt

/**
 * List rendering support: computes the marker (bullet / ordered number) for a `<li>` and the
 * horizontal gutter it needs, **without touching the character stream**.
 *
 * Markers never affect [orilumn.reader.engine.laying.NormalFlowLayout.visibleCharAdvance] /
 * `globalCharStarts` / `LayoutBox.textLength` — they are a purely visual overlay (the engine's own
 * `BulletSpan`-style mechanism), so heavy/light pagination consistency is preserved by construction.
 * The `<li>` text's left indent is expressed as a `LeadingMarginSpan` per shape; the marker glyph is
 * drawn separately in the gutter at the leaf's first-line baseline.
 *
 * Marker formatting follows EPUB 2/3 (a browser-compatible subset):
 *  - `ul`: disc / circle / square / none (default disc).
 *  - `ol`: decimal / decimal-leading-zero / lower-alpha / upper-alpha / lower-roman / upper-roman /
 *    none (default decimal) plus `start` / `reversed` attributes and per-level reset.
 *  - `list-style-position`: inside / outside (default outside).
 *
 * Pure JVM (no Android dependency); [markerGapPx]/[shapeMarkerWidthPx] are consumers' pixel helpers.
 */
object ListMarkers {

    /** Per list level, how many em of gap to leave between the marker and the following text. */
    const val LIST_MARKER_GAP_EM = 0.6f

    /** Bullet/shape marker diameter in em of the list item body font (disc/circle/square). */
    const val SHAPE_MARKER_EM = 0.55f

    enum class Kind {
        DISC, CIRCLE, SQUARE,
        DECIMAL, DECIMAL_LEADING_ZERO, LOWER_ALPHA, UPPER_ALPHA, LOWER_ROMAN, UPPER_ROMAN,
    }

    enum class Position { INSIDE, OUTSIDE }

    /** A resolved marker for one `<li>`: what to draw and how to reserve its gutter. */
    data class ListMarker(
        val kind: Kind,
        /** Rendered label for text kinds ("1"/"i"/"a"…); ignored by the shape kinds (disc/circle/square). */
        val text: String,
        val position: Position,
        /** 1-based item ordinal within its list (used for ol numbering). */
        val order: Int,
        /** List nesting depth (1 = top-level list). */
        val level: Int,
    ) {
        /** True for the bullet/shape kinds that are drawn as vector shapes (not a text glyph). */
        val isShapeKind: Boolean get() = kind == Kind.DISC || kind == Kind.CIRCLE || kind == Kind.SQUARE
    }

    /* ------------------------------------------------ node attribution ------------------------------------------------ */

    /** The owning `<li>` of [el] — the nearest `li` on [el]'s ancestor chain (itself included), or
     *  null. Any leaf under a `<li>` belongs to that item: a simple leaf of tag `li` itself (direct
     *  text), a nested `<li>`'s leading anonymous `#text` leaf, or a block child such as the `p` leaf
     *  of `<li><p>…</p></li>`. Consumers that draw exactly one marker per `<li>` gate on
     *  [firstCarrierSet], so only the first leaf of an item carries the bullet. Geometry (heavy),
     *  char count (light) and render all route through this same rule, so the marker stays consistent
     *  across the dual paths. */
    fun liOf(el: MarkupElement): MarkupElement? {
        var n: MarkupElement? = el
        while (n != null) {
            if (n.tag == "li") return n
            n = n.parent
        }
        return null
    }

    /** The leaves that draw a list marker. For each `<li>` appearing in [leaves] (document order)
     *  exactly one leaf is the "marker carrier": its **first** leaf. Later leaves under the same
     *  `<li>` — e.g. the second `<p>` of `<li><p>a</p><p>b</p></li>` — render as plain paragraphs with
     *  no second bullet, matching CSS, where the marker belongs to the `display:list-item` box rather
     *  than to each nested block. [leaves] must be in document order (both the light and heavy paths
     *  enumerate their leaves exactly that way). */
    fun firstCarrierSet(leaves: List<MarkupElement>): Set<MarkupElement> {
        val seenLi = HashSet<MarkupElement>()
        val carriers = HashSet<MarkupElement>()
        for (leaf in leaves) {
            val li = liOf(leaf) ?: continue
            if (seenLi.add(li)) carriers.add(leaf)
        }
        return carriers
    }

    /** The containing `ul`/`ol` of a `<li>` (must always be the parent), else null. */
    fun listOf(li: MarkupElement): MarkupElement? {
        val p = li.parent ?: return null
        return if (p.tag == "ul" || p.tag == "ol") p else null
    }

    /** Number of `ul`/`ol` ancestors of [el] (the leaf/li's own containing list(s)) = nesting level. */
    fun levelOf(el: MarkupElement): Int {
        var n = 0
        var p = el.parent
        while (p != null) {
            if (p.tag == "ul" || p.tag == "ol") n++
            p = p.parent
        }
        return n
    }

    /* ------------------------------------------------ list-style resolution ------------------------------------------------ */

    /** Resolves `list-style-type` → [Kind]; null = no marker (list-style-type: none / unknown). */
    fun kindOf(listEl: MarkupElement, cssType: String?): Kind? {
        val t = cssType?.trim()?.lowercase()
        when (t) {
            "disc" -> return Kind.DISC
            "circle" -> return Kind.CIRCLE
            "square" -> return Kind.SQUARE
            "decimal" -> return Kind.DECIMAL
            "decimal-leading-zero" -> return Kind.DECIMAL_LEADING_ZERO
            "lower-alpha" -> return Kind.LOWER_ALPHA
            "upper-alpha" -> return Kind.UPPER_ALPHA
            "lower-roman" -> return Kind.LOWER_ROMAN
            "upper-roman" -> return Kind.UPPER_ROMAN
            "none", "", null -> return null
            else -> return null // unknown keyword → no marker
        }.also { }
    }

    /** Resolves `list-style-position` (default outside). */
    fun positionOf(cssPos: String?): Position =
        if (cssPos?.trim()?.lowercase() == "inside") Position.INSIDE else Position.OUTSIDE

    /** `ul` → disc, `ol` → decimal when no explicit `list-style-type`. */
    fun defaultKind(listEl: MarkupElement): Kind =
        if (listEl.tag == "ol") Kind.DECIMAL else Kind.DISC

    /** 0-based index of [li] among its `<li>` siblings (skipping display:none). */
    fun itemIndex(li: MarkupElement, hidden: HiddenCheck = HIDDEN_NONE): Int {
        val parent = li.parent ?: return 0
        var idx = 0
        for (c in parent.children) {
            if (c === li) return idx
            if (c.tag == "li" && !hidden.isHidden(c)) idx++
        }
        return idx
    }

    /* ------------------------------------------------ ol numbering ------------------------------------------------ */

    /** `start` attribute (default 1). */
    fun startFor(listEl: MarkupElement): Int = listEl.attrs["start"]?.toIntOrNull() ?: 1

    /** `reversed` attribute present? */
    fun reversedFor(listEl: MarkupElement): Boolean = listEl.attrs["reversed"] != null

    /** The marker label's numeric value for the `order`-th (1-based) item of [listEl]. */
    fun numberFor(listEl: MarkupElement, order: Int): Int {
        val start = startFor(listEl)
        val step = if (reversedFor(listEl)) -(order - 1) else (order - 1)
        return (start + step).coerceAtLeast(1)
    }

    /** The marker number for [li], honoring an explicit `li[value]` override: renumbering takes
     *  effect from that item onward (HTML4 `value` semantics within a single `ol`). Items without a
     *  `value` continue the running counter (upwards, or downwards for `reversed`); `start` seeds the
     *  first item. [hidden] skips `display:none` items exactly like [itemIndex]. */
    fun numberForItem(listEl: MarkupElement, li: MarkupElement, hidden: HiddenCheck = HIDDEN_NONE): Int {
        var current = startFor(listEl)
        val reversed = reversedFor(listEl)
        for (c in listEl.children) {
            if (c.tag != "li" || hidden.isHidden(c)) continue
            c.attrs["value"]?.toIntOrNull()?.let { current = it }
            if (c === li) return current.coerceAtLeast(1)
            current += if (reversed) -1 else 1
        }
        return current.coerceAtLeast(1)
    }

    /* ------------------------------------------------ formatting ------------------------------------------------ */

    /** The actual marker label to draw for [kind] at numeric [n] (bullets ignore n). */
    fun markerText(kind: Kind, n: Int): String = when (kind) {
        Kind.DISC -> "•"
        Kind.CIRCLE -> "○"
        Kind.SQUARE -> "▪"
        Kind.DECIMAL -> "$n"
        Kind.DECIMAL_LEADING_ZERO -> n.toString().padStart(2, '0')
        Kind.LOWER_ALPHA -> toAlpha(n)
        Kind.UPPER_ALPHA -> toAlpha(n).uppercase()
        Kind.LOWER_ROMAN -> toRoman(n)
        Kind.UPPER_ROMAN -> toRoman(n).uppercase()
    }

    /** Bijective base-26 letters: 1→a … 26→z, 27→aa (Excel-style column naming). */
    internal fun toAlpha(n: Int): String {
        var v = n
        val sb = StringBuilder()
        while (v > 0) {
            v--
            sb.insert(0, ('a'.code + v % 26).toChar())
            v /= 26
        }
        return sb.toString()
    }

    /** Standard subtractive Roman numerals, lowercase (1→i, 4→iv, 9→ix, 40→xl, 90→xc, …). */
    internal fun toRoman(n: Int): String {
        var v = n
        val sb = StringBuilder()
        for ((value, sym) in ROMAN) {
            while (v >= value) { sb.append(sym); v -= value }
        }
        return sb.toString()
    }

    private val ROMAN = listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l",
        40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
    )

    /* ------------------------------------------------ gutter ------------------------------------------------ */

    /** Px of the fixed marker→text gap for the `<li>` body font size (em-based). The per-level text
     *  indent is the measured marker width plus this gap, so the marker sits right beside its text
     *  and wrapped lines align under the text start. */
    fun markerGapPx(bodyFontSizePx: Float): Int =
        (LIST_MARKER_GAP_EM * bodyFontSizePx).roundToInt().coerceAtLeast(1)

    /** Px width reserved for a bullet/shape marker (disc/circle/square) at [bodyFontSizePx]. */
    fun shapeMarkerWidthPx(bodyFontSizePx: Float): Int =
        (SHAPE_MARKER_EM * bodyFontSizePx).roundToInt().coerceAtLeast(1)

    /* ------------------------------------------------ single entry point ------------------------------------------------ */

    /**
     * Builds the [ListMarker] for the node `el` reached while shaping/drawing — the first leaf under
     * its `<li>` (a `<li>` text leaf, a nested `<li>`'s leading anonymous `#text` leaf, or a block
     * child like the `p` of `<li><p>…</p></li>`; callers gate via [firstCarrierSet] so only that
     * first leaf reaches here). Returns null when there is no marker to draw
     * (not a list item, or `list-style-type: none`).
     *
     * The injected [styleOf] resolves an element's computed style (heavy supplies from its style map;
     * light from its lazy resolver), so this stays Android- and path-independent. A `list-style-type`/
     * `list-style-position` set directly on the `<li>` overrides the list's; the marker's horizontal
     * start (cssInsetPx) is driven by the book CSS's `padding-left`/`margin-left`.
     */
    fun specOf(
        el: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle?,
        hidden: HiddenCheck = HIDDEN_NONE,
    ): ListMarker? {
        val li = liOf(el) ?: return null
        val listEl = listOf(li) ?: return null
        fun typeOf(node: MarkupElement): String? = styleOf(node)?.listStyleType
        fun posOf(node: MarkupElement): String? = styleOf(node)?.listStylePosition
        // li-level override wins; otherwise fall back to the containing ul/ol's list-style.
        val cssType = (typeOf(li) ?: typeOf(listEl))?.trim()?.lowercase()
        // `list-style-type: none` → no marker; a known keyword → that kind; unset/unknown → type default.
        val kind = when {
            cssType == "none" -> null
            else -> kindOf(listEl, cssType) ?: defaultKind(listEl)
        } ?: return null
        val cssPos = (posOf(li) ?: posOf(listEl))?.trim()?.lowercase()
        val n = numberForItem(listEl, li, hidden)
        return ListMarker(
            kind = kind,
            text = markerText(kind, n),
            position = positionOf(cssPos),
            order = itemIndex(li, hidden) + 1,
            level = levelOf(li),
        )
    }

    /**
     * Thin wrapper over [specOf] gated by the first-carrier rule（P2，`docs/平台一致性整改方案.md` B 行）：
     * 只给 `<li>` 的**首个载体叶**（[firstCarrierSet]，仅这里）解析 marker；同一项的后续叶（如
     * `<li><p>a</p><p>b</p></li>` 的第二个 p）与非 li 叶都返回 null。平板
     * `BoxChapterLayouter.listMarkerFor`（app/.../BoxChapterLayouter.kt）与桌面
     * `DesktopReaderHost.buildDrawLines` 都经它拿 marker，两端"每 li 只有一个 bullet"同一标的。
     */
    fun markerForLeaf(
        leafEl: MarkupElement?,
        carriers: Set<MarkupElement>,
        styleOf: (MarkupElement) -> ComputedStyle?,
    ): ListMarker? =
        if (leafEl != null && leafEl in carriers) specOf(leafEl, styleOf) else null
}