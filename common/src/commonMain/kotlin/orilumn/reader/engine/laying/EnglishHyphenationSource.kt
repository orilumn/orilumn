package orilumn.reader.engine.laying

/**
 * S7：**英文音节断点**源（K-L + 软连字符），只对**自然语言散文**生效。
 *
 * ## 适用范围（刻意窄）
 *
 * 只对普通段落（`tag == "p"` 且非代码/引用）注入。**代码标识符不走这里** —— 见
 * [CodeIdentifierBreakSource]：K-L 是为散文设计的，`parse_config_file` 按音节断会产出
 * `parse_con-fig_file`，既不认得又破坏可读性，浏览器对代码也是用 `overflow-wrap` 硬切（R1）。
 *
 * ## 断点即「连字符该出现的位置」
 *
 * [Hyphenator] 给的是词内音节边界。**绘制侧在断点处补一个 U+2010/连字符**即 CSS
 * `hyphenate-character`；本仓当前不自动补字符（见 docs 29g 的 TODO），只提供断点位置，
 * 因为「哪一行恰好断在词中间」由贪心决定，绘制时才知道该不该补。
 *
 * ## 语言来源
 *
 * 目前统一按 `en`（[Hyphenator] 只有 en 表）。接入元素 `lang` 属性是下一步 ——
 * `BreakOpportunitySource` 形状允许，但 source 拿不到 `ComputedStyle`，需在接线处按 `tag`
 * 之外的维度决定用哪个 source，见 docs 29g。
 */
object EnglishHyphenationSource {

    /** 最小词长：短于此不断（太短的词断出来两侧都不成词）。 */
    private const val MIN_WORD = 6

    /** 按语言的 source 缓存：同一叶多次排版（翻页回看）不该重建。 */
    private val byLang = HashMap<String, BreakOpportunitySource>()

    /** 取该语言的断词源（未加载语言返 [NoHyphenation]，退到 R1 硬切）。 */
    fun forLang(lang: String): BreakOpportunitySource =
        byLang.getOrPut(lang) {
            if (lang in Hyphenator.loadedLanguages()) LangHyphenationSource(lang) else NoHyphenation
        }

    /** 实际断词实现（一个固定语言）。不可变、可缓存。 */
    class LangHyphenationSource(private val lang: String) : BreakOpportunitySource {
        override fun mark(text: CharSequence, into: BreakOpportunitySet) {
            val n = text.length
            var wordStart = 0
            var i = 0
            while (i <= n) {
                if (i == n || !isWordChar(text[i])) {
                    if (i - wordStart >= MIN_WORD) {
                        markWord(text.subSequence(wordStart, i), wordStart, into)
                    }
                    wordStart = i + 1
                }
                i++
            }
        }

        private fun markWord(word: CharSequence, base: Int, into: BreakOpportunitySet) {
            // 词内可能因数字/连字符被拆开，只对纯字母段断词。
            var s = 0
            while (s < word.length) {
                if (!isLatin(word[s])) { s++; continue }
                var e = s
                while (e < word.length && isLatin(word[e])) e++
                if (e - s >= MIN_WORD) {
                    for (p in Hyphenator.hyphenate(word.subSequence(s, e).toString(), lang = lang)) {
                        into.mark(base + s + p)
                    }
                }
                s = e
            }
        }
    }

    /** 未加载语言：不加任何断点，由 R1 兜底硬切。**显式类型**，不是「空实现」以便区分。 */
    object NoHyphenation : BreakOpportunitySource {
        override fun mark(text: CharSequence, into: BreakOpportunitySet) = Unit
    }

    private fun isWordChar(c: Char): Boolean = isLatin(c) || c.isDigit() || c == '\'' || c == '-'

    private fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F'
}