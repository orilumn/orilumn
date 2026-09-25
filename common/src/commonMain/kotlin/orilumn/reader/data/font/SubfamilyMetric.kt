package orilumn.reader.data.font

/**
 * Maps a font **subfamily** name (e.g. `Bold`, `Italic`, `Light`, `509R`) to concrete CSS weight /
 * italic flags, making the reader able to pick the real Bold/Italic file of a family for
 * `font-weight: bold` / `font-style: italic`. Pure logic (no Android), unit-testable.
 */
object SubfamilyMetric {

    /** 单数字字重档（可选 W 前缀）：Hiragino Sans/明朝的 W0–W9（W3→300）。 */
    private val SingleDigitWeight = Regex("^[w]?([0-9])$")

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
            s.contains("thin") || s.contains("纤细") -> 100
            s.contains("extralight") || s.contains("ultralight") || s.contains("特细") || s.contains("超细") || s.contains("极细") -> 200
            s.contains("light") || s.contains("细") -> 300
            s.contains("regular") || s.contains("roman") || s.contains("normal") || s.contains("book") || s.contains("常规") -> 400
            s.contains("medium") || s.contains("中等") || s.contains("中黑") -> 500
            s.contains("semibold") || s.contains("demibold") || s.contains("半粗") || s.contains("中粗") -> 600
            // NB: "extrabold"/"ultrabold" must be matched before generic "bold" (特粗/超粗同理在粗之前).
            s.contains("extrabold") || s.contains("ultrabold") || s.contains("特粗") || s.contains("超粗") -> 800
            s.contains("bold") || s.contains("heavy") || s.contains("粗") -> 700
            s.contains("black") || s.contains("ultra") || s.contains("黑") -> 900
            else -> {
                // Hiragino W0–W9 这类单数字档（可选 W 前缀）：档位×100（W3→300…W9→900，
                // W0 钳到 100）；多位数字沿旧口径（509R→509 这类厂商内码）。
                // 注意 Alibaba 的 55 Regular… 走上面的关键词分支，进不到这里。
                val single = SingleDigitWeight.matchEntire(s)?.groupValues?.get(1)?.toIntOrNull()
                if (single != null) return (single * 100).coerceIn(100, 900)
                // Numeric style code embedded in the name, e.g. "509R" → 509. Only plausible weights.
                s.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..1000 } ?: 400
            }
        }
    }

    /** Whether the subfamily denotes an italic/oblique variant (英文斜体词 + 中文"斜"). */
    fun italic(subfamily: String?): Boolean {
        val s = (subfamily ?: "").trim().lowercase()
        return s.contains("italic") || s.contains("oblique") || s.contains("kursiv") || s.contains("斜")
    }
}