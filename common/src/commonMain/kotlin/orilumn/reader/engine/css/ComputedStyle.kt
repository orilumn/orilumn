package orilumn.reader.engine.css

/**
 * The final, per-element computed style (flat, resolved to concrete values, pure JVM).
 *
 * Produced by [StyleComputer] from the cascade + inheritance. All lengths here are absolute pixels
 * (em/rem/% already resolved against the right font-size context), colors are `#rrggbb`, and
 * font-weight/font-style are collapsed to the bold/italic flags the typesetter consumes.
 */
/**
 * The 8 box edges; kept on one object so the vertical spacing ([top] + [bottom]) is easy to sum
 * and the horizontal one ([left] + [right]) trims the line-breaking width.
 */
data class Edges(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
) {
    val vertical: Float get() = top + bottom
    val horizontal: Float get() = left + right
}

/** Text horizontal alignment within each line's content box. */
enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }

/** Page-break preference, reduced to the two values the reader's paginator consumes. */
enum class BreakRule { AUTO, AVOID }

/** CSS `border-style` per edge. `hidden` is folded into [NONE] at parse (same drawing outcome). */
enum class BorderStyle { NONE, SOLID, DASHED, DOTTED }

/** Per-edge `border-style`; each edge defaults to [BorderStyle.NONE]. */
data class BorderStyleEdges(
    val top: BorderStyle = BorderStyle.NONE,
    val right: BorderStyle = BorderStyle.NONE,
    val bottom: BorderStyle = BorderStyle.NONE,
    val left: BorderStyle = BorderStyle.NONE,
) {
    companion object {
        /** All four edges with the same [s]. */
        fun uniform(s: BorderStyle) = BorderStyleEdges(s, s, s, s)
    }
}

/** Per-edge resolved border colors (`#aarrggbb` hex); a null row value → currentColor (text color). */
data class BorderColorEdges(
    val top: String?,
    val right: String?,
    val bottom: String?,
    val left: String?,
) {
    constructor(uniform: String) : this(uniform, uniform, uniform, uniform)
}

/** Border corner radii (px). Consumed at draw time (P3); `%` lives in [ComputedStyle.borderRadiusPct]. */
data class CornerRadius(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
) {
    /** True when every corner is zero (legacy square path, zero behavior change). */
    fun isSquare(): Boolean = topLeft == 0f && topRight == 0f && bottomRight == 0f && bottomLeft == 0f

    /**
     * P3-a: CSS corner clamping + `%` resolution. Percent radii resolve against
     * `min(boxW, boxH)` (circular approximation of the elliptical spec), then every
     * corner is clamped so adjacent corners never exceed the box dims
     * (`r1 + r2 <= dim` per edge, scaled down proportionally on overflow).
     */
    fun resolved(boxW: Int, boxH: Int, pct: CornerRadius = CornerRadius()): CornerRadius {
        if (isSquare() && pct.isSquare()) return this
        val base = minOf(boxW, boxH).toFloat().coerceAtLeast(0f)
        var tl = topLeft + pct.topLeft * base
        var tr = topRight + pct.topRight * base
        var br = bottomRight + pct.bottomRight * base
        var bl = bottomLeft + pct.bottomLeft * base
        // Clamp per edge (CSS Backgrounds §5.2.2 overflow fold-in).
        val top = tl + tr
        if (top > boxW && top > 0f) {
            val f = boxW / top
            tl *= f
            tr *= f
        }
        val bottom = bl + br
        if (bottom > boxW && bottom > 0f) {
            val f = boxW / bottom
            bl *= f
            br *= f
        }
        val left = tl + bl
        if (left > boxH && left > 0f) {
            val f = boxH / left
            tl *= f
            bl *= f
        }
        val right = tr + br
        if (right > boxH && right > 0f) {
            val f = boxH / right
            tr *= f
            br *= f
        }
        return CornerRadius(tl.coerceAtLeast(0f), tr.coerceAtLeast(0f), br.coerceAtLeast(0f), bl.coerceAtLeast(0f))
    }
}

/** CSS `box-shadow` (none when null; blur/offset in px; null color = currentColor). */
data class BoxShadow(
    val dx: Float,
    val dy: Float,
    val blur: Float,
    val colorHex: String?,
)

/** CSS `text-shadow` (none when null; blur/offset in px; null color = currentColor). */
data class TextShadow(
    val dx: Float,
    val dy: Float,
    val blur: Float,
    val colorHex: String?,
)

/** CSS `text-emphasis-style` (none = off). */
enum class EmphasisStyle { NONE, DOT, CIRCLE }
/** CSS `background-repeat` (initial `repeat`; non-inherited). */
enum class BackgroundRepeat { REPEAT, REPEAT_X, REPEAT_Y, NO_REPEAT }

/**
 * CSS `background-position` (initial `0% 0%`; non-inherited). Percent axes resolve against
 * `box - image` per CSS Backgrounds §3.6 (`x = l + xPct * (bw - imgW) + xPx`); px axes resolve
 * at compute time (em/rem/unitless → px). Single-keyword shorthands default the other axis
 * to `center` per spec.
 */
data class BackgroundPosition(
    val xPct: Float = 0f,
    val yPct: Float = 0f,
    val xPx: Float = 0f,
    val yPx: Float = 0f,
)

/** CSS `white-space` (parsed in the compute layer; breaking semantics consumed with the breaker). */
enum class WhiteSpace { NORMAL, PRE, NOWRAP, PRE_WRAP, PRE_LINE }

/** CSS `float` (P4-a1 计算层；围排消费后续；非继承，初值 none）。 */
enum class FloatSide { NONE, LEFT, RIGHT }

/** CSS `clear` (P4-a1 计算层；消费后续；非继承，初值 none）。 */
enum class ClearSide { NONE, LEFT, RIGHT, BOTH }

/** CSS `text-transform`. */
enum class TextTransform { NONE, UPPERCASE, LOWERCASE, CAPITALIZE }

/** CSS `vertical-align` keyword (inline baseline offset; "现只有字号缩放" 的消费缺口). */
enum class VerticalAlign { BASELINE, SUB, SUPER, MIDDLE, TOP, BOTTOM }

/** CSS `box-sizing`. */
enum class BoxSizing { CONTENT_BOX, BORDER_BOX }

/**
 * P3-c: `quotes` 未声明时的 UA 默认引号对（浏览器惯例 “…” ‘…’；`q` 引号补丁与
 * `open-quote` 关键字无作者 `quotes` 时的回退）。
 */
val DEFAULT_QUOTES: List<String> = listOf("\u201C", "\u201D", "\u2018", "\u2019")

/** CSS `overflow-x/y` (ancestor clipping is an later consumption point). */
enum class OverflowValue { VISIBLE, HIDDEN, CLIP, SCROLL, AUTO }

/** CSS `overflow-wrap`. */
enum class OverflowWrap { NORMAL, BREAK_WORD }

/** CSS `word-break`. */
enum class WordBreak { NORMAL, BREAK_ALL }

/** CSS `font-variant`（P2 计算层；small-caps 字形合成是后续消费点）。 */
enum class FontVariant { NORMAL, SMALL_CAPS }

/**
 * CSS `content` 生成内容项（P3-c；仅 `::before/::after` 伪元素消费，元素自身值不用）。
 * `none`/`normal` 即空表（无生成内容旧路径）。
 */
sealed class ContentItem {
    /** 字符串字面量（引号已去）。 */
    data class Str(val text: String) : ContentItem()
    /** `attr(name)`（缺失属性即空串）。 */
    data class Attr(val name: String) : ContentItem()
    /** `counter(name[, style])`（缺失计数器即 0）。 */
    data class Counter(val name: String, val style: String = "decimal") : ContentItem()
    /** `counters(name, sep[, style])`（嵌套作用域连接）。 */
    data class Counters(val name: String, val sep: String, val style: String = "decimal") : ContentItem()
    object OpenQuote : ContentItem()
    object CloseQuote : ContentItem()
    object NoOpenQuote : ContentItem()
    object NoCloseQuote : ContentItem()
}

class ComputedStyle(
    val fontSizePx: Float,
    val lineHeightRatio: Float,
    val colorHex: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val textIndentPx: Float = 0f,
    /** Outer separation from surrounding boxes; adjacent vertical margins collapse to the larger. */
    val margin: Edges = Edges(),
    /** `margin-left/right: auto` 标记（表/块水平居中用；auto 值本身按 0 计入 [margin]）。 */
    val marginLeftAuto: Boolean = false,
    val marginRightAuto: Boolean = false,
    /** Inner padding between the border and the content box. */
    val padding: Edges = Edges(),
    /** Border widths; colors via [borderColors] / currentColor, styles via [borderStyles]. */
    val border: Edges = Edges(),
    val backgroundColorHex: String? = null,
    /** CSS `background-image: url(...)` (raw url; null = none). Non-inherited; drawn from P3-b. */
    val backgroundImageUrl: String? = null,
    /** CSS `background-repeat` (initial repeat). Non-inherited. */
    val backgroundRepeat: BackgroundRepeat = BackgroundRepeat.REPEAT,
    /** CSS `background-position` (initial 0% 0%). Non-inherited. */
    val backgroundPosition: BackgroundPosition = BackgroundPosition(),
    /** Per-edge resolved border colors; null edge → currentColor (text color). Null object = none
     *  declared anywhere (still falls back to currentColor when the edge has a width). */
    val borderColors: BorderColorEdges? = null,
    /** Per-edge `border-style`; null = none declared → solid fallback (old behavior), so existing
     *  `border`-width-only books still draw. Explicit `none`/`hidden` edges never draw. */
    val borderStyles: BorderStyleEdges? = null,
    /** Border corner radii (px, 0 = square). Geometry only — drawn from P3. */
    val borderRadius: CornerRadius = CornerRadius(),
    /** Border corner radii (`%` fractions 0..1 of min(w,h); resolved+clamped at draw, P3-a). */
    val borderRadiusPct: CornerRadius = CornerRadius(),
    /** CSS `box-shadow` (null = none; drawn from P3-a). Non-inherited. */
    val boxShadow: BoxShadow? = null,
    /** CSS `text-shadow` (null = none; drawn from P3-a). Inherited. */
    val textShadow: TextShadow? = null,
    /** CSS `text-emphasis-style` incl. `-epub-` prefixed (NONE = off; drawn from P3-a). Inherited. */
    val emphasisStyle: EmphasisStyle = EmphasisStyle.NONE,
    /** CSS `text-emphasis-position: under` (default over). Inherited. */
    val emphasisUnder: Boolean = false,
    val textAlign: TextAlign = TextAlign.LEFT,
    /** Paged-media break preferences; non-inherited (initial 0). */
    val breakInside: BreakRule = BreakRule.AUTO,
    val breakAfter: BreakRule = BreakRule.AUTO,
    val breakBefore: BreakRule = BreakRule.AUTO,
    /** Whether CSS `display` lays this element out as a block (block/list-item/flex/grid/table*);
     *  non-inherited; false when unspecified or `inline`. Applied block-ness is
     *  `tag-in-block-set || this`. */
    val displayBlock: Boolean = false,
    /** Whether CSS `display:none` hides this element entirely (no box, no text). Inherited: false.
     *  A hidden element and its subtree must contribute nothing to either layout path. */
    val displayNone: Boolean = false,
    /** Inherited CSS `font-family` — the first, non-generic family name from the value (for the
     *  browser-core font pairing). */
    val fontFamily: String? = null,
    /** Inherited full `font-family` stack in author order (generic keywords kept), so the font
     *  pairing can fall back past uninstalled names down to the trailing generic. Empty = unset. */
    val fontFamilies: List<String> = emptyList(),
    /** Inherited numeric CSS `font-weight` (100–900, default 400). */
    val fontWeight: Int = 400,
    /** Whether a monospace font-family (`font-family: monospace`/courier/…) resolves for this run;
     *  inherited. Let the block/draw shapers pick [android.graphics.Typeface.MONOSPACE] for it. */
    val monospace: Boolean = false,
    /** Resolved CSS `list-style-type` keyword ("" = undeclared). Non-inherited; read on the `ul/ol`
     *  element by [orilumn.reader.engine.layout.ListMarkers] to decide the list marker. */
    val listStyleType: String = "",
    /** Resolved CSS `list-style-position` ("" = undeclared, outside). Non-inherited. */
    val listStylePosition: String = "",
    /** Resolved CSS `width` in absolute px; null = auto (intrinsic sizing). Non-inherited.
     *  For `<img>` this also covers the HTML `width` attr (elevated to a low-tier cascade origin).
     *  NOTE: `%` values are NOT folded in here — they resolve against the containing-block width
     *  (unknown at cascade time), so they are carried separately in [widthPct]. */
    val widthPx: Float? = null,
    /** Resolved CSS `height` in absolute px; null = auto. Non-inherited.
     *  `%` against an auto-height containing block is indefinite per CSS 2.1 §10.6 — treated as
     *  auto (dropped at compute time), so this only ever holds px/em/rem/unitless. */
    val heightPx: Float? = null,
    /** CSS `width` as a containing-block percentage (e.g. `50` for `50%`); null = not %-specified. */
    val widthPct: Float? = null,
    /** Resolved `max-width` px; null = `none` (no constraint). Non-inherited. */
    val maxWidthPx: Float? = null,
    /** `max-width` as a containing-block percentage; null = not %-specified. */
    val maxWidthPct: Float? = null,
    /** Resolved `min-width` px; null = `0`. Non-inherited. */
    val minWidthPx: Float? = null,
    /** `min-width` as a containing-block percentage; null = not %-specified. */
    val minWidthPct: Float? = null,
    /** Resolved `max-height` px; null = `none`. `%` is indefinite (auto-height CB) → dropped. */
    val maxHeightPx: Float? = null,
    /** Resolved `min-height` px; null = `0`. `%` is indefinite (auto-height CB) → dropped. */
    val minHeightPx: Float? = null,
    /** CSS `white-space` (computed; consumption lands with the breaker). Non-inherited. */
    val whiteSpace: WhiteSpace = WhiteSpace.NORMAL,
    /** CSS `letter-spacing` (px; the reader's own letter-spacing is applied at shaping, separate). */
    val letterSpacingPx: Float = 0f,
    /** CSS `word-spacing` (px). */
    val wordSpacingPx: Float = 0f,
    /** CSS `text-transform`. Not consumed until leaf text assembly (char stream) — computed only. */
    val textTransform: TextTransform = TextTransform.NONE,
    /** CSS `vertical-align` keyword (inline baseline shift is a downstream consumption point). */
    val verticalAlign: VerticalAlign = VerticalAlign.BASELINE,
    /** CSS `box-sizing` (affects how width/height vs padding/border reconcile). */
    val boxSizing: BoxSizing = BoxSizing.CONTENT_BOX,
    /** CSS `opacity` (0..1; compositing lands with P3 draw). Non-inherited. */
    val opacity: Float = 1f,
    /** CSS `visibility: hidden` (subtree hidden but still occupies layout; consumption pending). */
    val visibilityHidden: Boolean = false,
    /** CSS `overflow-x/y` (ancestor clipping is a downstream consumption point). */
    val overflow: OverflowValue = OverflowValue.VISIBLE,
    /** CSS `position: relative` (absolute/static/fixed out of scope — 显式不做). */
    val positionRelative: Boolean = false,
    /** CSS `overflow-wrap` (word breaking is a downstream breaker concern). */
    val overflowWrap: OverflowWrap = OverflowWrap.NORMAL,
    /** CSS `word-break`. */
    val wordBreak: WordBreak = WordBreak.NORMAL,
    /** CSS `direction: rtl` / `unicode-bidi` isolation marker — RTL flow is a separate milestone. */
    val directionRtl: Boolean = false,
    // ---- P2: font-variant / font-stretch (defaults = old rendering, computed only) ----
    /** CSS `font-variant`（small-caps 字形合成是后续消费点）。Inherited. */
    val fontVariant: FontVariant = FontVariant.NORMAL,
    /** CSS `font-stretch` 宽度比（1＝normal；condensed 系＜1，expanded 系＞1；字形压缩是后续消费点）。Inherited. */
    val fontStretch: Float = 1f,
    // ---- P1-2: table family (defaults = old geometry, §6 inv.3) ----
    /** CSS `border-spacing` horizontal/vertical (px). 0 = separate-but-touching (old behavior). */
    val borderSpacingH: Float = 0f,
    /** CSS `border-spacing` vertical (px). */
    val borderSpacingV: Float = 0f,
    /** CSS `border-collapse: collapse` (adjacent cell borders merge, spacing forced 0). */
    val borderCollapse: Boolean = false,
    /** CSS `caption-side: bottom` (default top). */
    val captionSideBottom: Boolean = false,
    /** CSS `empty-cells: hide` (empty cell backgrounds/borders skipped at draw). */
    val emptyCellsHide: Boolean = false,
    /** CSS `table-layout: fixed` (equal-split columns; default auto measures content). */
    val tableLayoutFixed: Boolean = false,
    // ---- P4-a1: float / clear (non-inherited; NONE = old block behavior) ----
    /** CSS `float`（围排消费后续；此处只落计算值）。 */
    val floatSide: FloatSide = FloatSide.NONE,
    /** CSS `clear`（消费后续；此处只落计算值）。 */
    val clearSide: ClearSide = ClearSide.NONE,
    // ---- P3-c: generated content + quotes/counters (defaults = old rendering, §6 inv.3) ----
    /** CSS `content`（null = none/normal，无生成内容；仅伪元素消费）。Non-inherited. */
    val content: List<ContentItem>? = null,
    /** CSS `quotes`（开/闭引号对表；null = UA 默认）。Inherited. */
    val quotes: List<String>? = null,
    /** CSS `counter-reset`（null = none）。Non-inherited. */
    val counterReset: Map<String, Int>? = null,
    /** CSS `counter-increment`（null = none）。Non-inherited. */
    val counterIncrement: Map<String, Int>? = null,
) {
    /** Any border edge with a positive width (drawn unless its style is explicitly none). */
    fun hasBorderEdges(): Boolean =
        border.top > 0f || border.right > 0f || border.bottom > 0f || border.left > 0f

    /** Whether this element paints anything in the box background/border slab (P3 compositing reads it). */
    fun hasPaintedSlab(): Boolean = backgroundColorHex != null || backgroundImageUrl != null || hasBorderEdges()

    override fun toString(): String =
        "fs=${fontSizePx}px lh=${lineHeightRatio}" +
            if (colorHex != null) " color=$colorHex" else "" +
            listOfNotNull(bold.takeIf { it }?.let { "bold" }, italic.takeIf { it }?.let { "italic" }, underline.takeIf { it }?.let { "ul" }).joinToString(" ", " [", "]")
}