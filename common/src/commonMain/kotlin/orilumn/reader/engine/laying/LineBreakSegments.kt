package orilumn.reader.engine.laying

/**
 * 禁则规则（分行控制，纯数据值对象）——「哪些位置不许断」的规则集。
 *
 * **单源**：[isBreakOpportunity] 是唯一的断点判定入口，[minContentSegments]（min-content 分段）
 * 与正文断行器（本轮为 `ParagraphBreaker` 的自建实现）共用它，**不复制规则**。
 *
 * 规则按 Chrome `width: min-content` + Skia 实际断点双向实测标定，见 [KinsokuRules.ZH_EN] 的说明
 * 与 `docs/自建断行引擎-测试计划.md` 的 T1/T2/T2b/T2c，回归由 `LineBreakRuleTest` 承担。
 *
 * **中英合一，不是两套表**：ASCII 侧（`)]}!?,.:;/` / `([{"` / `-‐‑`）与 CJK 侧共用同一条禁则语义，
 * 实测两者无冲突面（T1 的 25 例差异**全部**落在 4 个引号字符上，而 `’` `”` 正是英文 curly quote，
 * `don’t` 的 apostrophe 即 `’`）。**不要为「英文禁则」另开一张表**——另开会制造两套规则的冲突面。
 *
 * @param noBreakBefore 不许在其**前**断的字符集（LB13 闭合/中缀/EX/SY、LB21 连字符/非起始类）。
 * @param noBreakAfter 不许在其**后**断的字符集（LB14 开括/引号类）。
 * @param breakAfter 其**后**必可断的字符集（LB 连字符类；即使两侧都不是宽字/空白）。
 */
class KinsokuRules(
    val noBreakBefore: String,
    val noBreakAfter: String,
    val breakAfter: String,
) {
    /**
     * 位置 [i]（`text[i-1]` 与 `text[i]` 之间）是否可断——**单源判定**。
     *
     * 判定顺序即规则优先级：代理对 → 文档空白 → 禁则 → 宽字/连字符。
     * 空白优先于禁则是 CSS 语义（空白永远可断且可折叠）；禁则优先于宽字，
     * 否则 `。|` 这类组合会被「两侧都是宽字」放行。
     *
     * @param i 断点位置，**前置条件** `1 <= i < text.length`（调用方自行保证，不做越界检查——
     *        这是分页热路径，加边界检查不划算）。
     */
    fun allowsBreakAt(text: CharSequence, i: Int): Boolean {
        val prev = text[i - 1]
        val next = text[i]
        // 代理对内部永不断（Ext B 等宽字按整对成段，不能把一对劈成两段度量）。
        if (isHighSurrogate(prev) && isLowSurrogate(next)) return false
        if (isDocumentSpace(prev) || isDocumentSpace(next)) return true
        if (noBreakBefore.indexOf(next) >= 0 || noBreakAfter.indexOf(prev) >= 0) return false
        return breakAfter.indexOf(prev) >= 0 || isWideBreakChar(prev) || isWideBreakChar(next)
    }

    companion object {
        /**
         * **中英合一的默认禁则规则**——本轮唯一取值。
         *
         * 三张表相对本轮改动前的差异：
         * ```
         * noBreakBefore +=  "”‘’〞%℃"   // T2：引号闭合类 + 单位符号（填|” 禁断）
         * noBreakBefore +=  "?"          // S1：转写漏字，见下方「ASCII ? 」专条
         * noBreakBefore -=  "〆"          // T2：Skia 允许 填|〆
         * noBreakAfter  +=  "“‘’"        // T2：引号开启类（“|填 禁断）
         * breakAfter     +=  "…‥"         // T2c：IN 类，两侧都不宽时仍需放行（…|填）
         * ```
         *
         * > ⚠ **`‘`(U+2018) / `’`(U+2019) 同时出现在 `noBreakBefore` 与 `noBreakAfter`**，
         * > 即两侧都粘。这是 T2 实测的 Skia 行为，不是笔误。
         *
         * > ⚠ **「—」(U+2014) 故意留在 `noBreakBefore`**（T2c 已否决移出）：Skia 的 LB21b
         * > （`—|—` 禁断）与「`—` 前后可断」冲突，移出反而会放行 `—|—` **制造假阴性**。
         * > 代价是 `填|—` 上我们比 Skia **严**（假阳性）——有意接受，中文排版上破折号永不落行首更稳。
         *
         * > ⚠ **ASCII `?` 故意收进来，尽管 Skia 实测放行 `填|?`**。
         * > 证据：① 本文件原 KDoc 一直把 `?` 写进闭合类（`) ] } ! ? , . : ; /`），是**转写漏字**；
         * > ② 同族的 `!` 在表里、全角 `？` 在表里，只有 ASCII `?` 缺席，无任何区分理由；
         * > ③ `?` 属 UAX#14 的 EX 类（`× EX`，不可落行首），而 Skia 疑似按旧版 LB13 的字面清单实现
         * > （旧清单只有 `]` `!` `;` `/`，无 `?`）——**这更像 Skia 的偏差，不是本表的**。
         * > 代价：`填|?` 上我们比 Skia 严（假阳性）。与「—」同性质，有意接受。
         * > 🔜 **待 S3 的 T1 复跑验证**：若该格实测为「Skia 放行 / 我们禁断」，即预期中的假阳性，
         * > 须在 T1 报告里显式列为已知例外，不得计入「假阴性 = 0」的达标判定。
         *
         * **按语言选择规则是将来才做的事**（多语言口子，见方案 §0.2）：本轮不加选择逻辑，
         * 选择点留在 [isBreakOpportunity] 里读 `KinsokuRules.ZH_EN` 这一处，不散进判定内部。
         */
        val ZH_EN: KinsokuRules = KinsokuRules(
            noBreakBefore = ")]}!?,.:;/、。，．：；！？）】』」〉》〕］｝｣-‐‑–—・…‥ゝゞ々”‘’〞%℃",
            noBreakAfter = "([{\"“‘’（【『「〈《〔［｛｢",
            breakAfter = "-‐‑…‥",
        )
    }
}

/**
 * 位置 [i]（`text[i-1]` 与 `text[i]` 之间）按**默认禁则规则**是否可断——**正文断行的单源入口**。
 *
 * 自建断行器与 [minContentSegments] 都走这里，两者对同一输入必然给出同一断点集。
 * 需要别的规则集时直接用 [KinsokuRules.allowsBreakAt]，不要另写一份判定。
 *
 * @param i 断点位置，前置条件 `1 <= i < text.length`（同 [KinsokuRules.allowsBreakAt]）。
 */
fun isBreakOpportunity(text: CharSequence, i: Int): Boolean =
    KinsokuRules.ZH_EN.allowsBreakAt(text, i)

/**
 * min-content 断行机会分段——「最长不可断单元」的纯函数切分，[ParagraphBreaker.minContentWidth] 的输入，
 * 亦即 auto 分列 `max/min-content` 里的 min 一侧。**重/轻两路单源**（纯函数、不查字体），与 max-content
 * （[ParagraphBreaker.preferredWidth] 真测整段）同一度量源，故 `max ≥ min` 恒成立。
 *
 * 断点判定走 [isBreakOpportunity]（→ [KinsokuRules.ZH_EN]），规则说明与实测来源见那里的注释。
 * 分段规则本身：
 *
 *  1. CSS 文档空白（space / tab / LF / CR / FF）处可断，空白本身不入段；
 *     NBSP、EM SPACE 等「非文档空白」**不可断**（Chrome 实测：`a\u2003b` 不换行）。
 *  2. CJK／假名／全角逐字可断（`0x2E80-0x303F`、`0x3040-0x30FF`、Ext A/B、兼容区、全角形式），
 *     故汉字串的 min-content = 单字宽（与浏览器一致）。
 *  3. 禁则（闭合/中缀/开括/引号/连字符/非起始类）—— 见 [KinsokuRules.ZH_EN]。
 *
 * @return 覆盖 [text] 的**非空**、从左到右、互不重叠的区间（区间之外只有被丢弃的文档空白）。
 */
fun minContentSegments(text: CharSequence): List<IntRange> {
    val n = text.length
    if (n == 0) return emptyList()
    val out = ArrayList<IntRange>()
    var start = 0
    for (i in 1 until n) {
        if (!isBreakOpportunity(text, i)) continue
        emitSegment(text, start, i, out)
        start = i
    }
    emitSegment(text, start, n, out)
    return out
}

/** 把 `[from, to)` 内的非空白段（两端文档空白裁掉）收进 [out]；全空白则丢弃。 */
private fun emitSegment(text: CharSequence, from: Int, to: Int, out: ArrayList<IntRange>) {
    var a = from
    var b = to
    while (a < b && isDocumentSpace(text[a])) a++
    while (b > a && isDocumentSpace(text[b - 1])) b--
    if (b > a) out.add(a until b)
}

/** CSS「文档空白」：只有这几个可断且可折叠（NBSP/EM SPACE 等不算，Chrome 实测）。 */
private fun isDocumentSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

/**
 * CJK／假名／全角判定（断点用，与 Chrome 实测集合一致）。
 * 代理对高位按宽字处理（[KinsokuRules.allowsBreakAt] 保证不劈开代理对）。
 */
private fun isWideBreakChar(c: Char): Boolean {
    val cp = c.code
    return cp in 0x2E80..0x303F || cp in 0x3040..0x30FF ||
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
        cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF ||
        cp in 0xD800..0xDBFF
}

private fun isHighSurrogate(c: Char): Boolean = c.code in 0xD800..0xDBFF

private fun isLowSurrogate(c: Char): Boolean = c.code in 0xDC00..0xDFFF