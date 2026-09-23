package orilumn.reader.engine.text.preprocess

/**
 * One CJK↔Western boundary adjustment.
 *
 * @property leftIndex index of the boundary char's last UTF-16 unit (the gap sits between
 *   `text[leftIndex]` and `text[leftIndex] + 1`).
 * @property gapEm gap advance in em (0.25 = 1/4 em by default).
 * @property suppressSpace when true, the char at `leftIndex + 1` is a separating half-width/NBSP
 *   space that the shaping layer must render zero-width, absorbed into the gap.
 */
data class CjkLatinGap(
    val leftIndex: Int,
    val gapEm: Float,
    val suppressSpace: Boolean,
)

/**
 * CJK↔Western text shaping (功能架构 §5 文本整形 — "CJK↔西文间隙").
 *
 * Detects boundaries between a CJK ideograph and a Western glyph (or the reverse) inside a text
 * run and reports a **fixed gap** (default 0.25em, the classic CJK/Latin micro-gap) at each such
 * boundary. It never inserts a real character: the gap is emitted as metadata for the shaping
 * layer to advance by `gapEm × fontSize` between the two glyphs, keeping the underlying text
 * untouched for selection/search/indexing.
 *
 * At a boundary where the author already typed a half-width (or NBSP) space, that space is
 * treated as the "surplus half-width space" of the document model: [CjkLatinGap.suppressSpace]=true
 * tells the shaping layer to render the space zero-width and absorb it into the fixed gap (删除多余
 * 半角空格，注入固定间隙). Only a single separating space is collapsed; repeated spaces keep their
 * user-visible separation and produce no gap.
 *
 * Output is sorted ascending by [CjkLatinGap.leftIndex]; adjustments never overlap (a suppressed
 * space is consumed, so no later adjustment re-uses it).
 */
object CjkLatinSpacing {

    /** Returns the CJK↔Western boundary gaps of [text]. */
    fun gaps(text: String, gapEm: Float = DEFAULT_GAP_EM): List<CjkLatinGap> {
        val out = mutableListOf<CjkLatinGap>()
        var i = 0
        val n = text.length
        while (i < n) {
            val ci = codePointAt(text, i)
            val ciKind = kindOf(ci.first)
            if (ciKind != KIND_NONE && i + 1 < n) {
                val j = ci.second
                // Left index of the boundary = index of the boundary char's last UTF-16 unit
                // (a surrogate-pair CJK's low surrogate), so the gap always sits between
                // text[left] and text[left+1].
                val left = ci.second - 1
                val leftC = text[j]
                if (isSeparatingSpace(leftC)) {
                    // CJK SPACE Western / Western SPACE CJK: collapse the space into a fixed gap.
                    if (j + 1 < n) {
                        val ck = codePointAt(text, j + 1)
                        if (opposite(ciKind, kindOf(ck.first))) {
                            out += CjkLatinGap(left, gapEm, suppressSpace = true)
                            // consume the space and the second char as left side of later pairs
                            i = ck.second
                            continue
                        }
                    }
                } else if (opposite(ciKind, kindOf(text[j].code))) {
                    out += CjkLatinGap(left, gapEm, suppressSpace = false)
                }
            }
            i = ci.second
        }
        return out
    }

    private const val DEFAULT_GAP_EM = 0.25f
    private const val KIND_NONE = 0
    private const val KIND_CJK = 1
    private const val KIND_WESTERN = 2

    // CJK sides: symbols & punctuation, Extension A, Unified, Compatibility, Fullwidth Forms, Extension B.
    private fun isCjk(cp: Int): Boolean =
        cp in 0x3000..0x303F || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0xFF00..0xFFEF || cp in 0x20000..0x2A6DF

    // Western sides: basic-Latin digits/letters + Latin Supplement (minus NBSP / soft hyphen) +
    // Latin Extended-A and B — the scripts the reader mixes with CJK.
    private fun isWestern(cp: Int): Boolean {
        if (cp == 0x00A0 || cp == 0x00AD) return false
        return cp in 0x0030..0x024F
    }

    private fun kindOf(cp: Int): Int = when {
        isCjk(cp) -> KIND_CJK
        isWestern(cp) -> KIND_WESTERN
        else -> KIND_NONE
    }

    private fun opposite(a: Int, b: Int): Boolean =
        (a == KIND_CJK && b == KIND_WESTERN) || (a == KIND_WESTERN && b == KIND_CJK)

    private fun isSeparatingSpace(c: Char): Boolean = c == ' ' || c == '\u00A0'

    /**
     * Reads the code point starting at [i] and its next char index (surrogate-pair aware), so
     * CJK Extension B code points classify correctly instead of as two isolated surrogates.
     */
    private fun codePointAt(s: String, i: Int): Pair<Int, Int> {
        val first = s[i].code
        if (first in 0xD800..0xDBFF && i + 1 < s.length) {
            val second = s[i + 1].code
            if (second in 0xDC00..0xDFFF) {
                val cp = 0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                return cp to i + 2
            }
        }
        return first to i + 1
    }
}