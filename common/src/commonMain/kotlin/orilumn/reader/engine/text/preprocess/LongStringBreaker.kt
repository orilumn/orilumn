package orilumn.reader.engine.text.preprocess

/**
 * Long-string line breaking (功能架构 §5 文本整形 — "长串断行", the 长串断行 / hard-break companion
 * to [CjkLatinSpacing]).
 *
 * Inside a run of text **without whitespace** longer than [minLength] (a URL, a UUID, a long hex
 * digest, an ID...), normal word-boundary breaking would only split right before the run ends,
 * overflowing the line. This returns the *safe internal break opportunities* of such runs.
 *
 * Semantics (all positions are "break **after** char k" indices):
 *  - The run is split by any whitespace; only runs whose length ≥ [minLength] are considered.
 *  - If the run contains a separator-like character (see [SEPARATORS]), breaks are offered after
 *    each such character, but never closer than [minPrefix] chars from the run start nor
 *    [minSuffix] chars from its end (keeps segments from becoming chewable fragments).
 *  - If the run has **no** separator (a pure alphanumeric/hex token), chunked hard-breaks are
 *    offered every [chunk] chars instead (deterministic segments), respecting the same
 *    [minPrefix]/[minSuffix] guard.
 *  - Nothing is inserted into the text: the positions are hints for the shaping/breaking layer
 *    to allow a line break at those points (they remain **opportunities** — a break happens only
 *    if the line actually overflows).
 *
 * Output is sorted ascending and distinct; positions always fall inside the run (never at its
 * exact end).
 */
object LongStringBreaker {

    /** Characters after which a break is considered safe inside an unbroken run (URL/UUID/ID delimiters). */
    val SEPARATORS: Set<Char> = setOf('/', '?', '&', '=', '.', '-', '_', ':', '#', '%', '~', '+', ',', '@', ';')

    /**
     * @param minLength run length below which no internal breaks are offered.
     * @param chunk safe chunk length used when a run has no separator.
     * @param minPrefix minimum distance (in code units) from the run start before any break.
     * @param minSuffix minimum distance (in code units) from the run end after any break.
     */
    fun breakOpportunities(
        text: String,
        minLength: Int = 24,
        chunk: Int = 16,
        minPrefix: Int = 4,
        minSuffix: Int = 2,
    ): List<Int> {
        require(minLength >= 1 && chunk >= 1 && minPrefix >= 0 && minSuffix >= 0) {
            "minLength/chunk/minPrefix/minSuffix must be non-negative"
        }
        val out = mutableListOf<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            val runLen = i - start
            if (runLen >= minLength) {
                // CJK runs break freely between any glyphs — no hints needed (they'd only be
                // meaningless chunking); separator/chunk semantics apply to Latin/digit runs only.
                if (containsCjk(text, start, i)) continue
                val last = i - minSuffix - 1
                var sawSeparator = false
                var k = start + minPrefix
                while (k <= last) {
                    if (text[k] in SEPARATORS) {
                        out += k
                        sawSeparator = true
                    }
                    k++
                }
                if (!sawSeparator) {
                    var k = start + chunk
                    while (k <= last) {
                        out += k
                        k += chunk
                    }
                }
            }
        }
        return out
    }

    /** True when [s][lo..hi) contains a CJK ideograph/symbol (BMP ranges that matter for URLs/paths). */
    private fun containsCjk(s: String, lo: Int, hi: Int): Boolean {
        for (k in lo until hi) {
            val c = s[k].code
            if (c in 0x3000..0x303F || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
                c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF
            ) return true
        }
        return false
    }
}