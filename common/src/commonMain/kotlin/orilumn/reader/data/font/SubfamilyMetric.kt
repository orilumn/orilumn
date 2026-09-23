package orilumn.reader.data.font

/**
 * Maps a font **subfamily** name (e.g. `Bold`, `Italic`, `Light`, `509R`) to concrete CSS weight /
 * italic flags, making the reader able to pick the real Bold/Italic file of a family for
 * `font-weight: bold` / `font-style: italic`. Pure logic (no Android), unit-testable.
 */
object SubfamilyMetric {

    /**
     * Deduce a CSS numeric weight (100–900, or an inline weight-class code such as `509`) from a
     * subfamily name. Empty / unknown names default to 400 (Regular).
     *
     * Keywords win over embedded digits: vendor-prefixed names like Alibaba PuHuiTi 3.0's
     * `55 Regular`…`115 Black` carry non-CSS numbers (35..115) that must NOT shadow the style
     * word (`55` alone would read as 55 and make body text pick the Black file). Pure numeric
     * names (`509R`) still fall through to the digit rule below.
     */
    fun weight(subfamily: String?): Int {
        val s = (subfamily ?: "").trim().lowercase()
        if (s.isEmpty()) return 400
        return when {
            s.contains("thin") -> 100
            s.contains("extralight") || s.contains("ultralight") -> 200
            s.contains("light") -> 300
            s.contains("regular") || s.contains("roman") || s.contains("normal") || s.contains("book") -> 400
            s.contains("medium") -> 500
            s.contains("semibold") || s.contains("demibold") -> 600
            // NB: "extrabold"/"ultrabold" must be matched before generic "bold".
            s.contains("extrabold") || s.contains("ultrabold") -> 800
            s.contains("bold") || s.contains("heavy") -> 700
            s.contains("black") || s.contains("ultra") -> 900
            else -> {
                // Numeric style code embedded in the name, e.g. "509R" → 509. Only plausible weights.
                s.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..1000 } ?: 400
            }
        }
    }

    /** Whether the subfamily denotes an italic/oblique variant. */
    fun italic(subfamily: String?): Boolean {
        val s = (subfamily ?: "").trim().lowercase()
        return s.contains("italic") || s.contains("oblique") || s.contains("kursiv")
    }
}