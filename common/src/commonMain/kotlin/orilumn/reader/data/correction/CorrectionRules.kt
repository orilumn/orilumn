package orilumn.reader.data.correction

import orilumn.reader.data.assets.BundledAssets
import orilumn.reader.io.Logger

/**
 * 修订卡片的**纠错词典**（用户层数据：只给读者挑词，不进排版、不进渲染）。
 *
 * 词条不写死在代码里，全部来自 `common/src/commonMain/resources/text/corrections.txt`：
 * 随包发布一份默认词表，将来读者自定义 / 导入的词条按同一格式追加即可（见 [candidatesFor] 的
 * `extraRules`）。解析是纯文本 + 纯函数，可单测。
 *
 * 匹配口径**只有一种**：选区必须**整段**命中（字面量整词相等，或正则 `matchEntire`）。
 * 多一个字、少一个字、带标点都不算——猜错上下文会把对的字改坏，比不改更糟。所以这里
 * 不做模糊匹配、不做上下文判断，也不猜「的 / 地 / 得」该用哪个。
 *
 * 文件格式（一行一条，空行与 `#` 开头忽略）：
 * ```
 * group 的 地 得                 同音族：组内任一词被选中，候选就是其余词
 * word  按耐 => 按捺             定向：左边是常见错写，右边是正确写法（反方向不给）
 * regex 「([^「」]+)」 => “$1”   正则：整段匹配，$1 / ${1} 引用捕获组，$$ 表示一个 $
 * bracket 「 」                   成对符号：整段是「左 + 内容 + 右」时给三条候选
 *                                （“内容” / ‘内容’ / 内容，即换成双引号、单引号、去掉两边）
 * ```
 * 不带前缀的行按 `word` 处理（手写词表时少打两个字，也不会因为正则元字符误伤）。
 */
object CorrectionRules {

    /** 随包词表文件名（`resources/text/` 下）。 */
    const val FILE_NAME = "corrections.txt"

    /** 随包词表：第一次用到时解析，之后常驻（内容不会在运行中变）。 */
    private val bundled: List<CorrectionRule> by lazy {
        val text = BundledAssets.text(FILE_NAME)
        if (text == null) {
            Logger.w(LOG_TAG, "bundled $FILE_NAME missing")
            emptyList()
        } else {
            parseRules(text.lineSequence().toList())
        }
    }

    /**
     * 选区对应的候选词（按词表顺序，去重，且不含选区自己）。
     *
     * 每条候选带 `value`（点下去提交的完整文字）与 `label`（栏里显示的形态）：
     * 成对符号规则包着一整段内容时，`label` 会把中间收成省略号（见 [INNER_DISPLAY_LIMIT]）。
     *
     * @param extraRules 读者自定义 / 导入的词条（与随包词表同一格式，逐行）。为空时只查随包词表。
     */
    fun candidatesFor(selection: String, extraRules: List<String> = emptyList()): List<CorrectionCandidate> {
        if (selection.isEmpty()) return emptyList()
        val rules = if (extraRules.isEmpty()) bundled else bundled + parseRules(extraRules)
        val out = ArrayList<CorrectionCandidate>(2)
        for (rule in rules) {
            val candidate = rule.apply(selection) ?: continue
            if (candidate.value != selection && out.none { it.value == candidate.value }) out += candidate
        }
        return out
    }

    /** 随包词表解析后的规则（只读，测试与调试用）。 */
    internal fun bundledRules(): List<CorrectionRule> = bundled
}

/**
 * 成对符号候选的**显示**门槛：内容超过这么多字就只显示首尾引号，中间收成省略号
 * （`“…”` / `‘…’` / `…`）。提交的仍是完整文字，这里只管读者看到的长度。
 */
private const val INNER_DISPLAY_LIMIT = 4

/** 一条纠错候选。
 *
 * [value] 是点下去真正写进正文的文字（完整、不做任何截断）；[label] 只是操作栏里的显示形态，
 * 长内容可以收成省略号——两者只有长度不同，语义一致。
 */
data class CorrectionCandidate(val value: String, val label: String) {
    constructor(value: String) : this(value, value)
}

/** 一条纠错规则：命中选区就给出一个候选词。 */
internal sealed interface CorrectionRule {

    /** 选区整段命中返回候选词，否则返回 `null`。 */
    fun apply(selection: String): CorrectionCandidate?
}

/** 字面量整词规则：选区必须与 [word] 逐字相等。 */
internal data class WordRule(val word: String, val replacement: String) : CorrectionRule {
    override fun apply(selection: String): CorrectionCandidate? =
        if (selection == word) CorrectionCandidate(replacement) else null
}

/** 正则规则：`matchEntire`（整段命中）后按 [replacement] 展开捕获组。 */
internal data class RegexRule(val regex: Regex, val replacement: String) : CorrectionRule {
    override fun apply(selection: String): CorrectionCandidate? =
        regex.matchEntire(selection)?.let { CorrectionCandidate(expandReplacement(replacement, it)) }
}

/**
 * 成对符号规则：整段是「左符号 + 内容 + 右符号」时，换成 [open] + 内容 + [close]。
 *
 * 显示时内容超过 [INNER_DISPLAY_LIMIT] 字就收成省略号（`“…”` / `‘…’` / `…`）：
 * 三条候选的差别只在引号，内容原样重复三遍既占地方又没什么信息量。
 */
internal data class BracketRule(val regex: Regex, val open: String, val close: String) : CorrectionRule {
    override fun apply(selection: String): CorrectionCandidate? {
        val match = regex.matchEntire(selection) ?: return null
        val content = match.groupValues.getOrNull(1) ?: return null
        return CorrectionCandidate(
            value = open + content + close,
            label = open + collapseInner(content) + close,
        )
    }
}

/** 长内容在显示时收成省略号；短内容原样显示（`「你好」` → `“你好”`）。 */
private fun collapseInner(content: String): String =
    if (content.length > INNER_DISPLAY_LIMIT) "…" else content

/**
 * 解析词表行。坏行只跳过并记一条 w，绝不因为一条写错就让整份词表失效。
 */
internal fun parseRules(lines: List<String>): List<CorrectionRule> {
    val out = ArrayList<CorrectionRule>(lines.size)
    for ((index, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        // 同音族没有 `=>`：组内两两互指。
        if (line.startsWith("group ")) {
            val words = line.removePrefix("group ").trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (words.size < 2) {
                warn(index, line, "group 至少要两个词")
                continue
            }
            for (a in words) {
                for (b in words) {
                    if (a != b) out += WordRule(a, b)
                }
            }
            continue
        }
        // 成对符号：一个 bracket 生成三条候选（双引号 / 单引号 / 去掉两边）。
        if (line.startsWith("bracket ")) {
            val parts = line.removePrefix("bracket ").trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (parts.size != 2) {
                warn(index, line, "bracket 要左右两个符号")
                continue
            }
            val regex = runCatching { Regex(bracketPattern(parts[0], parts[1])) }.getOrElse {
                warn(index, line, "括号规则无效：${it.message}")
                continue
            }
            out += BracketRule(regex, "“", "”")
            out += BracketRule(regex, "‘", "’")
            out += BracketRule(regex, "", "")
            continue
        }
        val arrow = line.indexOf("=>")
        if (arrow <= 0) {
            warn(index, line, "缺少 =>")
            continue
        }
        val left = line.substring(0, arrow).trim()
        val right = line.substring(arrow + 2).trim()
        if (left.isEmpty() || right.isEmpty()) {
            warn(index, line, "=> 两侧不能为空")
            continue
        }
        when {
            left.startsWith("regex ") -> {
                val body = left.removePrefix("regex ").trim()
                val regex = runCatching { Regex(body) }.getOrElse {
                    warn(index, line, "正则无效：${it.message}")
                    continue
                }
                out += RegexRule(regex, right)
            }
            left.startsWith("word ") -> out += WordRule(left.removePrefix("word ").trim(), right)
            // 不带前缀 = 字面量（安全默认：正则元字符不会误伤手写词表）。
            else -> out += WordRule(left, right)
        }
    }
    return out
}

/**
 * `bracket 左 右` 生成的正则：`左([^左右]+)右`。
 *
 * 内容里**排除左右两个符号本身**，避免把两对同种括号当成一对（`「甲」「乙」` 不该整段匹配）；
 * 左右符号按字面量转义，`[` / `]` / `(` / `)` 这类元字符直接写进词表也不会出错。
 */
internal fun bracketPattern(open: String, close: String): String {
    val inner = (open + close).toSet()
        .joinToString("") { escapeInCharClass(it) }
    return escapeLiteral(open) + "([^" + inner + "]+)" + escapeLiteral(close)
}

/** 正则里的字面量转义（括号、引号、点号等元字符）。 */
private fun escapeLiteral(text: String): String = buildString(text.length + 8) {
    for (c in text) {
        if (c in "\\^$.|?*+()[]{}") append('\\')
        append(c)
    }
}

/** 字符类内部的转义（`\` / `]` / `[` / `^` / `-` / `&` 有特殊含义）。 */
private fun escapeInCharClass(c: Char): String =
    if (c in "\\[]^-&") "\\$c" else c.toString()

/**
 * 展开候选词模板里的捕获组引用：`$1` / `${12}` 取对应组，`$$` 是一个 `$`。
 *
 * 组号越界或写法不完整时**原样保留**（`$x` 仍是 `$x`），不悄悄吞字符——词表写错时
 * 读者看到的是原样的模板，而不是一段缺字的结果。
 */
internal fun expandReplacement(template: String, match: MatchResult): String {
    if ('$' !in template) return template
    val out = StringBuilder(template.length + 8)
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c != '$') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= template.length) {
            out.append('$')
            break
        }
        when {
            template[i + 1] == '$' -> {
                out.append('$')
                i += 2
            }
            template[i + 1] == '{' -> {
                val close = template.indexOf('}', i + 2)
                val digits = if (close > i + 2) template.substring(i + 2, close) else ""
                val value = digits.takeIf { d -> d.isNotEmpty() && d.all { it.isDigit() } }
                    ?.toIntOrNull()
                    ?.let { n -> match.groupValues.getOrNull(n) }
                if (value != null && close > 0) {
                    out.append(value)
                    i = close + 1
                } else {
                    out.append(c)
                    i++
                }
            }
            template[i + 1].isDigit() -> {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                val value = template.substring(i + 1, j).toIntOrNull()
                    ?.let { n -> match.groupValues.getOrNull(n) }
                if (value != null) {
                    out.append(value)
                    i = j
                } else {
                    out.append(c)
                    i++
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

private const val LOG_TAG = "Orilumn.CORRECTION"

private fun warn(lineIndex: Int, line: String, reason: String) {
    Logger.w(LOG_TAG, "词表第 ${lineIndex + 1} 行已跳过（$reason）：$line")
}
