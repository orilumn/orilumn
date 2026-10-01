package orilumn.reader.engine.laying

/**
 * S7 第一步：**代码标识符**的断点源（纯函数、无外部数据依赖）。
 *
 * ## 为什么代码标识符**不走**音节断词（K-L），这是有意的
 *
 * K-L 是为**自然语言散文**设计的（德语前缀 `Grund-` / `ver-`）。而 `parse_config_file`
 * 是代码标识符，按音节断会产出 `parse_con-fig_file` —— 既不是英文读者认得的断法，
 * 也破坏代码可读性。**浏览器对代码同样不套 `hyphenation: auto`**，而是用
 * `overflow-wrap: break-word` 硬切（即本仓 R1 已有的兜底）。
 *
 * 故本 source 只在**两类自然边界**上给断点，不碰词内部：
 *
 * 1. **分隔符之后**：`_` `.` `:` `/` `->` 等标识符/成员访问分隔处（`foo_.bar` / `foo._bar`）。
 * 2. **驼峰交界**：小写/数字 → 大写（`parse|Config|File`、`get|User|By|Id`）。
 *    camelCase 命名法自带的词边界，读者天然认得。
 *
 * ## 为什么不改禁则表，而是加一个 source
 *
 * `.` 在 [KinsokuRules.ZH_EN] 的 `noBreakBefore` 里（`.` 不能出现在行首 ⇒ `config|.file` 禁断），
 * 这对**中文散文**是对的（句号不能落行首），对**代码**则不然。两条规则语境不同，
 * 但 [BreakOpportunitySource] 只**置位不清位**（[BreakOpportunitySet.of] 顺序无关），
 * 所以本 source 可安全地在 `.` 之前置位，**不需要改禁则表**，也不影响散文行为。
 *
 * ## 生效范围（不全局开）
 *
 * 只对**代码语境**的叶生效：由 [InhouseParagraphBreaker.sourcesFor] 按 `tag`
 * （`pre`/`code`/`kbd`/`samp`/`var`/`tt`）按需注入。正文散文不注入 ⇒
 * 汉字与中文标点的断点行为**逐值不变**。
 */
object CodeIdentifierBreakSource : BreakOpportunitySource {

    /** 标识符分隔符：其后可断（`foo_.bar`、`foo._bar`、`a::b`、`x->y`）。 */
    private const val SEPARATORS = "_.:/>\u2192"

    override fun mark(text: CharSequence, into: BreakOpportunitySet) {
        val n = text.length
        if (n < 2) return
        for (i in 1 until n) {
            val prev = text[i - 1]
            val next = text[i]
            // ① 分隔符之后。
            if (prev in SEPARATORS) {
                into.mark(i)
                continue
            }
            // ② 驼峰交界：小写或数字 → 大写（`parse|Config`）。**不含连续大写**（`HTTPServer` 不断）。
            if (isLower(prev) && isUpper(next)) into.mark(i)
        }
    }

    private fun isLower(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'
    private fun isUpper(c: Char): Boolean = c in 'A'..'Z'
}
