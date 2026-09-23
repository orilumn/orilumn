package orilumn.reader.engine.html

import kotlin.math.roundToInt

/**
 * Parser for an inline `style` string → [ResolvedStyle] (pure logic, unit-testable).
 *
 * Recognizes only the minimal subset relevant to the reader's typesetting (font-size / color /
 * line-height / text-indent / font-style / font-weight / text-decoration); all other properties
 * are ignored to avoid interference from messy-book styles.
 * Unit parsing: supports px (integer/float), em (relative to body font size), and % (relative to
 * body font size). When em/% have no absolute context, only the relative coefficient is kept,
 * converted to pixels by the typesetter together with
 * [orilumn.reader.engine.text.TypographicProfile].
 *
 * Whether to adopt the original inline values is decided upstream: only when
 * `ReaderSettings.useOriginalStyle` is set does this parser's result override the typesetting
 * parameters; otherwise the typesetting parameters dominate and the inline values are only a
 * reference (or even ignored).
 */
class CssInlineResolver(private val baseBodyPx: Float = 16f) {

    /** Parses an inline style string; an empty string or no valid properties returns an empty [ResolvedStyle]. */
    fun resolve(style: String?): ResolvedStyle {
        val out = ResolvedStyle()
        val raw = style ?: return out
        for (decl in raw.split(';')) {
            val pair = decl.trim().split(':', limit = 2)
            if (pair.size < 2) continue
            val prop = pair[0].trim().lowercase()
            val value = pair[1].trim()
            when (prop) {
                "font-size" -> out.fontSizePx = parseLength(value)
                "color" -> out.colorHex = parseColor(value)
                "line-height" -> parseLineHeight(value)?.let { out.lineHeightRatio = it }
                "text-indent" -> out.textIndentPx = parseLength(value)
                "font-style" -> out.italic = parseItalic(value)
                "font-weight" -> out.bold = parseBold(value)
                "text-decoration" -> out.underline = parseUnderline(value)
            }
        }
        return out
    }

    /** Parses a length value; returns null if unparseable. px as-is; em/% converted using [baseBodyPx]. */
    private fun parseLength(value: String): Float? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return null
        if (v.endsWith("px")) {
            return v.removeSuffix("px").trim().toFloatOrNull()
        }
        if (v.endsWith("em")) {
            return v.removeSuffix("em").trim().toFloatOrNull()?.times(baseBodyPx)
        }
        if (v.endsWith("%")) {
            return v.removeSuffix("%").trim().toFloatOrNull()?.times(baseBodyPx / 100f)
        }
        // A unitless number is treated as px (the simplest CSS2 approximation for the zero length)
        return v.toFloatOrNull()
    }

    /** Parses line-height: a unitless number = a multiplier; px/em/% = pixels then converted to a multiplier relative to the body font size. */
    private fun parseLineHeight(value: String): Float? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val plain = v.toFloatOrNull()
        if (plain != null && plain > 0f) return plain // "1.5" → 1.5x line spacing
        val px = parseLength(v) ?: return null
        return if (px > 0f) px / baseBodyPx else null
    }

    /** "#rrggbb" / "#rgb" / common English color names → 32-bit ARGB; returns null if unparseable. */
    private fun parseColor(value: String): String? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return null
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16)?.times(17) ?: return null
                    val g = hex[1].digitToIntOrNull(16)?.times(17) ?: return null
                    val b = hex[2].digitToIntOrNull(16)?.times(17) ?: return null
                    return "#%02x%02x%02x".format(r, g, b)
                }
                6 -> return "#$hex".ifAllHex()
                8 -> return "#${hex.substring(2)}".ifAllHex() // rgba padding, strips alpha
                else -> return null
            }
        }
        return NAMED[v]?.let { "#$it" } ?: null
    }

    /** Validates a 6-digit hex value. */
    private fun String.ifAllHex(): String? =
        if (this.length == 7 && all { it == '#' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }) this else null

    private fun parseItalic(value: String): Boolean? = when (value.lowercase()) {
        "italic", "oblique" -> true
        "normal" -> false
        else -> null
    }

    private fun parseBold(value: String): Boolean? = when (value.lowercase()) {
        "bold", "bolder", "600", "700", "800", "900" -> true
        "normal", "lighter", "400", "300", "200", "100" -> false
        else -> value.toIntOrNull()?.let { it >= 600 } ?: null
    }

    private fun parseUnderline(value: String): Boolean? = when (value.lowercase()) {
        "underline", "line-through" -> true
        "none" -> false
        else -> null
    }

    /** Common CSS color names (excluding transparent ones) → 6-digit hex. */
    private companion object {
        val NAMED = mapOf(
            "black" to "000000", "white" to "ffffff", "red" to "ff0000",
            "green" to "008000", "blue" to "0000ff", "gray" to "808080",
            "grey" to "808080", "yellow" to "ffff00", "orange" to "ffa500",
            "purple" to "800080", "brown" to "a52a2a", "pink" to "ffc0cb",
            "silver" to "c0c0c0", "maroon" to "800000", "olive" to "808000",
            "lime" to "00ff00", "aqua" to "00ffff", "teal" to "008080",
            "navy" to "000080", "fuchsia" to "ff00ff",
        )
    }
}

/**
 * The inline style parsed out (mutable during build; finalized when [CssInlineResolver.resolve] returns).
 */
class ResolvedStyle {
    /** font-size (px; em/% already converted to px based on the body font size); null = undeclared. */
    var fontSizePx: Float? = null

    /** color ("#rrggbb"); null = undeclared. */
    var colorHex: String? = null

    /** line-height (multiplier relative to the body font size); null = undeclared. */
    var lineHeightRatio: Float? = null

    /** text-indent (px; em/% already converted); null = undeclared. */
    var textIndentPx: Float? = null

    /** font-style italic; null = undeclared. */
    var italic: Boolean? = null

    /** font-weight bold; null = undeclared. */
    var bold: Boolean? = null

    /** text-decoration underline; null = undeclared. */
    var underline: Boolean? = null
}