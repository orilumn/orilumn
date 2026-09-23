package orilumn.reader.engine.css

import kotlin.math.roundToInt

/**
 * CSS value parsing shared by the cascade/compute layer (pure JVM, unit-testable).
 *
 * Lengths are kept as a small tagged model so they can be resolved against the right context:
 * `em`/`%` for font-size resolve against the **parent** font-size, while for other properties they
 * resolve against the **element's own** font-size; `rem` always resolves against the root font-size.
 * Resolving is done by [StyleComputer], which owns the font-size context ([Length.resolve]).
 */
sealed class Length {
    /** Absolute px. */
    data class Px(val value: Float) : Length()

    /** Multiple of a font-size (contextual), e.g. `1.5em`. */
    data class Em(val value: Float) : Length()

    /** Multiple of the root font-size, e.g. `1.2rem`. */
    data class Rem(val value: Float) : Length()

    /** Percentage, e.g. `80%`. */
    data class Percent(val value: Float) : Length()
}

/**
 * Parses a CSS length token into a [Length]; null when it is not a length (e.g. keywords like
 * `auto`/`inherit` are handled by callers first when relevant). Supports px (int/float), em, rem,
 * %, and unitless (treated as px).
 */
fun parseLength(value: String): Length? {
    val v = value.trim().lowercase()
    if (v.isEmpty()) return null
    return when {
        v.endsWith("px") -> v.removeSuffix("px").trim().toFloatOrNull()?.let { Length.Px(it) }
        // NB: "rem" must be checked before "em" — "1rem" also ends with "em".
        v.endsWith("rem") -> v.removeSuffix("rem").trim().toFloatOrNull()?.let { Length.Rem(it) }
        v.endsWith("em") -> v.removeSuffix("em").trim().toFloatOrNull()?.let { Length.Em(it) }
        v.endsWith("%") -> v.removeSuffix("%").trim().toFloatOrNull()?.let { Length.Percent(it) }
        else -> v.toFloatOrNull()?.let { Length.Px(it) } // unitless ≈ px (simplest zero-length approximation)
    }
}

/**
 * Converts a [Length] to absolute px given the element's font-size ([elementFontPx]), the parent
 * font-size ([parentFontPx]) and the root font-size ([rootFontPx]). `em`/`%` use [elementFontPx];
 * only callers resolving font-size itself pass a context where em/% map to [parentFontPx] instead.
 */
fun Length.resolve(elementFontPx: Float, parentFontPx: Float, rootFontPx: Float): Float = when (this) {
    is Length.Px -> value
    is Length.Em -> value * elementFontPx
    is Length.Percent -> value / 100f * elementFontPx
    is Length.Rem -> value * rootFontPx
}

/**
 * One explicitly-colored span inside a shaped leaf text ([start], [endExclusive) index into the
 * exact string the leaf fed the breaker, e.g. [NormalFlowLayout.leafText]). Only spans carrying an
 * author/UA color are listed — unlisted chars paint with the theme ink. Draw-time ink stamping
 * ([DrawLine.inkColor]) never touches these runs.
 *
 * Coordinates are full-text (same space as the leaf text); the drawer clips them to each line range.
 */
data class ColorRun(
    val start: Int,
    val endExclusive: Int,
    /** Packed ARGB Int. */
    val argb: Int,
)

/**
 * One run with a distinct face inside a shaped leaf text ([start], [endExclusive) index into the
 * exact string the leaf fed the breaker, e.g. [orilumn.reader.engine.laying.NormalFlowLayout.leafText]).
 * Carries the run element's computed font — the CSS `font-family` stack in author order, the owning
 * tag (generic-keyword / `code`-like pairing), numeric weight, italic flag and resolved monospace
 * flag — everything the skia paragraph needs to shape that substring under its own face (browser
 * inline-run semantics: `<code>`/`<kbd>` inside prose resolves monospace, `<strong>`/`<em>` bold /
 * italic, because it is exactly what the UA sheet declares and the cascade computed).
 *
 * [fontSizePx] is the run element's **computed** font size (its whole inline size — `font-size`
 * propagates via the cascade/inheritance into this value, e.g. the UA's `sub,sup{font-size:.7em}`
 * / `small{.8em}` / `big{1.2em}` and any author `span{font-size:…}`). The skia paragraph shapes the
 * run substring at this size, matching a browser's inline run. `0f` means "unset — resolve against
 * the leaf's own base font size" (kept for run-less call sites / test fixtures).
 *
 * Only substrings whose face **or font size** differs from the leaf's base face/size get a run;
 * uniform leaves emit no runs (zero run overhead, byte-identical to the base single-style path).
 * Coordinates are full-text (same space as the leaf text); the breaker shapes them and the drawer
 * clips per line.
 */
data class FontRun(
    val start: Int,
    val endExclusive: Int,
    val families: List<String>,
    val tag: String?,
    val weight: Int,
    val italic: Boolean,
    val monospace: Boolean,
    /** Computed CSS font-size (px); 0 = unset, resolve against the leaf base size. */
    val fontSizePx: Float = 0f,
) {
    /** The px to shape at: own size when set (> 0), else the caller's base leaf size. */
    fun fontPxOr(basePx: Float): Float = if (fontSizePx > 0f) fontSizePx else basePx
}

/**
 * Parses a #AARRGGBB/#RRGGBB hex (the [parseCssColor] output shape) into a packed ARGB Int.
 * Null on malformed input — callers drop the run and fall back to theme ink, never crash layout.
 */
fun cssHexToArgb(hex: String): Int? = runCatching {
    val h = hex.trim().removePrefix("#")
    val aarrggbb = when (h.length) {
        6 -> "ff" + h
        8 -> h
        else -> return null
    }
    if (!aarrggbb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val v = aarrggbb.toLong(16)
    (v and 0xFFFFFFFFL).toInt()
}.getOrNull()

/**
 * Parses a CSS color into **8-digit ARGB hex** like `"#ff0000ff"` (Android Color.parseColor native
 * format: `#AARRGGBB`). Returns null when the color cannot be parsed OR resolves to full transparency.
 *
 * Supported input forms:
 *   - `#rgb` / `#rrggbb` / `#rrggbbaa` hex notations (alpha defaults to 1.0 for 3/6-digit forms)
 *   - ~20 common named colors
 *   - `rgb(r, g, b)` / `rgba(r, g, b, a)` — channels 0–255 or 0%–100%, alpha 0–1 or 0%–100%
 *   - `hsl(h, s%, l%)` / `hsla(h, s%, l%, a)` — hue 0–360
 *   - `transparent` → null
 *
 * Returning `#AARRGGBB` lets every consumer (background fill, border stroke, text color) pipe the
 * result straight into [android.graphics.Color.parseColor], which understands that format natively.
 */
fun parseCssColor(value: String): String? {
    val v = value.trim().lowercase()
    if (v.isEmpty()) return null

    // transparent → no color (CSS: transparent is equivalent to rgba(0,0,0,0))
    if (v == "transparent") return null

    // Hex: #rgb, #rrggbb, #rrggbbaa
    if (v.startsWith("#")) {
        val hex = v.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16)?.times(17) ?: return null
                val g = hex[1].digitToIntOrNull(16)?.times(17) ?: return null
                val b = hex[2].digitToIntOrNull(16)?.times(17) ?: return null
                "#ff%02x%02x%02x".format(r, g, b)
            }
            6 -> if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#ff$hex" else null
            8 -> { // #rrggbbaa
                if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                val rgb = hex.substring(0, 6)
                val a = hex.substring(6, 8)
                if (a == "00") null
                else "#$a$rgb"
            }
            else -> null
        }
    }

    // rgb() / rgba() — range 0–255 or 0%–100%
    if (v.startsWith("rgb")) {
        val inner = v.removePrefix("rgba").removePrefix("rgb").trim()
        if (!inner.startsWith("(") || !inner.endsWith(")")) return null
        val args = inner.substring(1, inner.length - 1).split(',')
        if (args.size !in 3..4) return null
        val r = parseChannel(args[0].trim(), 255) ?: return null
        val g = parseChannel(args[1].trim(), 255) ?: return null
        val b = parseChannel(args[2].trim(), 255) ?: return null
        val a = if (args.size == 4) parseAlpha(args[3].trim()) else 1f
        if (a <= 0f) return null // fully transparent → no color
        val ai = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#%02x%02x%02x%02x".format(ai, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    // hsl() / hsla() — hue 0–360, sat/light 0%–100%
    if (v.startsWith("hsl")) {
        val inner = v.removePrefix("hsla").removePrefix("hsl").trim()
        if (!inner.startsWith("(") || !inner.endsWith(")")) return null
        val args = inner.substring(1, inner.length - 1).split(',')
        if (args.size !in 3..4) return null
        val h = args[0].trim().toFloatOrNull() ?: return null
        val s = args[1].trim().removeSuffix("%").toFloatOrNull()?.coerceIn(0f, 100f) ?: return null
        val l = args[2].trim().removeSuffix("%").toFloatOrNull()?.coerceIn(0f, 100f) ?: return null
        val a = if (args.size == 4) parseAlpha(args[3].trim()) else 1f
        if (a <= 0f) return null
        val ai = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        val (r, g, b) = hslToRgb(h.coerceIn(0f, 360f), s / 100f, l / 100f)
        return "#%02x%02x%02x%02x".format(ai, r, g, b)
    }

    // Named colors — full alpha
    return NAMED_COLORS[v]?.let { "#ff$it" }
}

/** Parses an rgb() channel: plain number (clamped to 0..255) or percentage (0%..100%). */
private fun parseChannel(raw: String, max: Int): Int? {
    return if (raw.endsWith("%")) {
        val pct = raw.removeSuffix("%").toFloatOrNull() ?: return null
        (pct / 100f * max).roundToInt()
    } else {
        raw.toFloatOrNull()?.roundToInt()
    }
}

/** Parses an alpha value: plain number 0..1 or percentage 0%..100%. */
private fun parseAlpha(raw: String): Float {
    return if (raw.endsWith("%")) {
        (raw.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
    } else {
        raw.toFloatOrNull() ?: 1f
    }
}

/** Converts HSL to RGB (output 0..255 integer channels). */
private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Int, Int, Int> {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hh = h / 60f
    val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Triple(
        ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
    )
}

/** Common CSS color names → 6-digit hex. */
private val NAMED_COLORS: Map<String, String> = mapOf(
    "black" to "000000", "white" to "ffffff", "red" to "ff0000",
    "green" to "008000", "blue" to "0000ff", "gray" to "808080",
    "grey" to "808080", "yellow" to "ffff00", "orange" to "ffa500",
    "purple" to "800080", "brown" to "a52a2a", "pink" to "ffc0cb",
    "silver" to "c0c0c0", "maroon" to "800000", "olive" to "808000",
    "lime" to "00ff00", "aqua" to "00ffff", "teal" to "008080",
    "navy" to "000080", "fuchsia" to "ff00ff",
)

/** Parses font-weight into a bold flag; null when unrecognized. */
fun parseFontWeightBold(value: String): Boolean? = when (value.trim().lowercase()) {
    "bold", "bolder", "600", "700", "800", "900" -> true
    "normal", "lighter", "400", "300", "200", "100", "500" -> false
    else -> value.trim().toIntOrNull()?.let { it >= 600 } ?: null
}

/** Parses font-weight into a numeric CSS weight (100–900); null when unrecognized. */
fun parseFontWeight(value: String): Int? = when (val v = value.trim().lowercase()) {
    "normal" -> 400
    "bold" -> 700
    "bolder" -> 700
    "lighter" -> 300
    else -> v.toIntOrNull()?.let { it.coerceIn(100, 900) }
}

/** Extracts the leading family list from a `font-family` value; null when empty. A concrete family
 *  wins when present ("Times New Roman"), otherwise a single generic keyword is passed through
 *  ("serif", "sans-serif", "monospace", …) so reader theme sheets (传统/现代) can resolve to a real
 *  platform face — Android `Typeface.create("serif"|"sans-serif"|…)` and the Skia paragraph factory
 *  both recognize the generic names. */
fun parseFontFamily(value: String): String? {
    val families = parseFontFamilyList(value)
    return families.firstOrNull { it.lowercase() !in GENERIC_FAMILIES } ?: families.firstOrNull()
}

/**
 * Parses a `font-family` value into its full ordered stack (quotes stripped, empties dropped,
 * generic keywords kept in place). The whole stack — not just the first name — must reach the
 * font pairing so it can walk down to the `serif`/`sans-serif` fallback instead of dying on an
 * uninstalled first name (e.g. `"思源宋体 VF", …, serif` on a device without that exact family).
 */
fun parseFontFamilyList(value: String): List<String> =
    value.split(',').map { it.trim().trim('\'').trim('"') }.filter { it.isNotEmpty() }

/** CSS generic family keywords (matched case-insensitively). */
private val GENERIC_FAMILIES = setOf("serif", "sans-serif", "monospace", "cursive", "fantasy")

/** True when [name] is a CSS generic family keyword (used to resolve stored font slots that hold a
 *  theme preset generic rather than an imported font alias, e.g. 现代→"sans-serif"). */
fun isGenericFontFamily(name: String): Boolean = name.isNotBlank() && name.trim().lowercase() in GENERIC_FAMILIES

/** Parses font-style; null when unrecognized. */
fun parseFontStyleItalic(value: String): Boolean? = when (value.trim().lowercase()) {
    "italic", "oblique" -> true
    "normal" -> false
    else -> null
}

/** Parses a line-height into a unitless ratio; null when unrecognized (px → ratio relative to [elementFontPx]). */
fun parseLineHeight(value: String, elementFontPx: Float): Float? {
    val plain = value.trim().toFloatOrNull()
    if (plain != null) return if (plain > 0f) plain else null // "1.5" → 1.5x
    val len = parseLength(value) ?: return null
    val px = len.resolve(elementFontPx, elementFontPx, elementFontPx)
    return if (px > 0f) px / elementFontPx else null
}