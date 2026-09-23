package orilumn.reader.data.font

/**
 * Font language/purpose classification: based on the cmap coverage weights of [FontParser.Result],
 * buckets a font into Chinese / Latin / generic / symbol / invalid.
 *
 * Design intent: mobile system fonts number dozens (including many with incomplete coverage,
 * symbol/icon, and language-fill fonts); listing them all would hide genuinely useful fonts. We judge
 * by a "coverage threshold" instead of "presence": only fonts with sufficient CJK coverage count as
 * Chinese candidates, sufficient Latin coverage as Latin candidates, and the rest fall to symbol/invalid —
 * thus auto-filtering junk fonts in the font-picker page.
 *
 * Thresholds scale with block size: the CJK Unified Ideographs block is ~20k glyphs, the Latin/basic-Latin
 * block ~700 glyphs; a "coverage ratio" is more stable than an absolute count; here the ratio is
 * converted into an absolute threshold.
 */
object FontClassifier {

    const val LANG_CJK = "cjk"
    const val LANG_LATIN = "latin"
    const val LANG_GENERIC = "generic"
    const val LANG_SYMBOL = "symbol"
    const val LANG_INVALID = "invalid"

    /** Minimum coverage code points for CJK judgment (~20k glyphs in the block, ≥12% counts as a real Chinese font). */
    private const val CJK_THRESHOLD = 2400
    /** Minimum coverage code points for Latin judgment (~700 glyphs in the block, ≥40% counts as a real Latin font). */
    private const val LATIN_THRESHOLD = 26

    /**
     * Classify by a parse result.
     * @param result result of [FontParser.parse]; [valid]==false is classified directly as [LANG_INVALID].
     */
    fun classify(result: FontParser.Result): String {
        if (!result.valid) return LANG_INVALID
        val cjk = result.cjkCoverage >= CJK_THRESHOLD
        val latin = result.latinCoverage >= LATIN_THRESHOLD
        return when {
            cjk && latin -> LANG_GENERIC
            cjk -> LANG_CJK
            latin -> LANG_LATIN
            else -> LANG_SYMBOL
        }
    }

    /** Whether it is a valid language class usable as a body-style candidate (Chinese/Latin/generic). */
    fun isUsable(lang: String): Boolean =
        lang == LANG_CJK || lang == LANG_LATIN || lang == LANG_GENERIC
}