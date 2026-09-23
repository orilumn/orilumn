package orilumn.reader.engine.laying

/**
 * min-content 断行机会分段——「最长不可断单元」的纯函数切分，[ParagraphBreaker.minContentWidth] 的输入，
 * 亦即 auto 分列 `max/min-content` 里的 min 一侧。**重/轻两路单源**（纯函数、不查字体），与 max-content
 * （[ParagraphBreaker.preferredWidth] 真测整段）同一度量源，故 `max ≥ min` 恒成立。
 *
 * 规则按 Chrome `width: min-content` 逐条实测标定（68 串样本零偏差，脚本与矩阵见
 * `docs/TODO-未尽事宜.md` 的表格条目），是 UAX#14 的**常用子集**而非全表——书籍表格单元格的
 * 实际字符集（拉丁词、假名/汉字、括号与标点、连字符）已全覆盖：
 *
 *  1. CSS 文档空白（space / tab / LF / CR / FF）处可断，空白本身不入段；
 *     NBSP、EM SPACE 等「非文档空白」**不可断**（Chrome 实测：`a\u2003b` 不换行）。
 *  2. CJK／假名／全角逐字可断（`0x2E80-0x303F`、`0x3040-0x30FF`、Ext A/B、兼容区、全角形式），
 *     故汉字串的 min-content = 单字宽（与浏览器一致）。
 *  3. 禁则在某字符**前**断：闭合/中缀/符号类（`) ] } ! ? , . : ; /`、`、。，．：；！？）】』」` 等）、
 *     连字符类（`- ‐ ‑ – —`，LB21）、非起始类（`・ … ‥ ゝ ゞ 〆 々`）。
 *  4. 禁则在某字符**后**断：开括/引号类（`( [ { "`、`（【『「〈《〔［｛｢`，LB14）。
 *  5. 连字符后可断（`a-b` → `a-` / `b`，`Wi-Fi` → `Wi-` / `Fi`）。
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

/** 位置 [i]（`text[i-1]` 与 `text[i]` 之间）是否可断——单源判定，[minContentSegments] 与测试共用。 */
private fun isBreakOpportunity(text: CharSequence, i: Int): Boolean {
    val prev = text[i - 1]
    val next = text[i]
    // 代理对内部永不断（Ext B 等宽字按整对成段，不能把一对劈成两段度量）。
    if (isHighSurrogate(prev) && isLowSurrogate(next)) return false
    if (isDocumentSpace(prev) || isDocumentSpace(next)) return true
    if (noBreakBefore(next) || noBreakAfter(prev)) return false
    return breakAfter(prev) || isWideBreakChar(prev) || isWideBreakChar(next)
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

/** 不在 [c] 之前断（LB13 闭合/中缀/EX/SY 类、LB21 连字符/非起始类）。 */
private fun noBreakBefore(c: Char): Boolean = NO_BREAK_BEFORE_CHARS.indexOf(c) >= 0

/** 不在 [c] 之后断（LB14 开括/引号类）。 */
private fun noBreakAfter(c: Char): Boolean = NO_BREAK_AFTER_CHARS.indexOf(c) >= 0

/** [c] 后可断（LB 连字符类；即使两侧都不是宽字/空白）。 */
private fun breakAfter(c: Char): Boolean = BREAK_AFTER_CHARS.indexOf(c) >= 0

/**
 * CJK／假名／全角判定（min-content 断点用，与 Chrome 实测集合一致）。
 * 代理对高位按宽字处理（[isBreakOpportunity] 保证不劈开代理对）。
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

// ---- 字符类表（顺序无关，仅做成员判断） ----

private const val NO_BREAK_BEFORE_CHARS =
    ")]}!,.:;/、。，．：；！？）】』」〉》〕］｝｣-‐‑–—・…‥ゝゞ〆々"
private const val NO_BREAK_AFTER_CHARS = "([{\"（【『「〈《〔［｛｢"
private const val BREAK_AFTER_CHARS = "-‐‑"
