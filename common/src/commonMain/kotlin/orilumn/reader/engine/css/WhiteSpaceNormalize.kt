package orilumn.reader.engine.css

/**
 * P1-2: CSS `white-space` 文本归一化单源（pure JVM）。
 *
 * 盒流（[orilumn.reader.engine.laying.NormalFlowLayout]）、engine-skia 塑形
 * （`emitPlainText`）、字符计数（`visibleCharStarts`）、着色/字体 run
 * 收集（[orilumn.reader.engine.laying.collectColorRuns]/`collectFontRuns`）必须从同一函数取
 * 同一字符串，否则 run 下标与字符错位、重轻两路 `globalCharStarts` 漂移（§6 inv.1）。
 *
 * 语义（CSS 2.1 §16.6 + CSS-text-3）：
 *  - NORMAL/NOWRAP：任意空白序列（空格/制表/换行/回车/换页）折叠为单个空格；源码换行不分段
 *    （只有 `<br>` 产生的 `\n` 才是硬换行，调用方在吸收时直接写入 `\n`，本函数不动它）。
 *    节点级归一不 trim（跨行内边界空格合并由叶级 trim 收尾），只把内部序列压成单个空格。
 *  - PRE/PRE_WRAP：原样保留（含换行），制表符按 CSS `tab-size: 8` 展开为空格（列对齐近似）。
 *  - PRE_LINE：空格/制表折叠为空格，换行保留。
 *
 * 注意：本函数只处理**单个文本节点**的内容；`<br>` 由调用方映射为 `\n`（任何模式都分段），
 * 叶级首尾 trim 由调用方按模式决定（NORMAL 系 trim，PRE 系保留）。
 */
object WhiteSpaceNormalize {

    /**
     * 归一化结果 + 每个输出字符的原文区间（修订正文时把归一化偏移映射回原始文本节点用）。
     *
     * [rawStart]/[rawEnd] 与 [text] 等长：一个字符可能来自原文的一段（折叠空白时整段空白映射成
     * 一个空格，[rawStart] 是空白段起点、[rawEnd] 是空白段终点），普通字符恒为 `[i, i+1)`。
     * 删除一个归一化空格时要连带删掉整段原文空白，否则「删了看不见变化」。
     */
    class NormalizedText(
        val text: String,
        val rawStart: IntArray,
        val rawEnd: IntArray,
    )

    /** 是否换行（false = PRE/NOWRAP：不断行，只在 `\n` 硬换行处分段）。 */
    fun wraps(ws: WhiteSpace): Boolean = when (ws) {
        WhiteSpace.NORMAL, WhiteSpace.PRE_WRAP, WhiteSpace.PRE_LINE -> true
        WhiteSpace.PRE, WhiteSpace.NOWRAP -> false
    }

    /** 归一化单个文本节点 [text]（调用方已按该节点继承的 [ws] 传入）。 */
    fun normalizeNode(text: String, ws: WhiteSpace): String {
        if (text.isEmpty()) return text
        return when (ws) {
            WhiteSpace.NORMAL, WhiteSpace.NOWRAP -> collapseWhitespace(text)
            WhiteSpace.PRE, WhiteSpace.PRE_WRAP -> expandTabs(text)
            WhiteSpace.PRE_LINE -> collapseSpacesKeepNewlines(text)
        }
    }

    /**
     * 与 [normalizeNode] 同规则，额外给出每个输出字符对应的原文区间（[NormalizedText]）。
     *
     * 只给修订正文用（把归一化偏移映射回原始文本节点）；**渲染路径仍走 [normalizeNode]**，
     * 两条规则必须逐字一致，单测 `WhiteSpaceNormalizeMapTest` 交叉校验。
     */
    fun normalizeNodeWithMap(text: String, ws: WhiteSpace): NormalizedText {
        if (text.isEmpty()) return NormalizedText("", IntArray(0), IntArray(0))
        return when (ws) {
            WhiteSpace.NORMAL, WhiteSpace.NOWRAP -> collapseWhitespaceWithMap(text)
            WhiteSpace.PRE, WhiteSpace.PRE_WRAP -> expandTabsWithMap(text)
            WhiteSpace.PRE_LINE -> collapseSpacesKeepNewlinesWithMap(text)
        }
    }

    /** 叶级收尾：NORMAL 系去首尾空格（块边界空白按 CSS 丢弃），PRE 系原样。 */
    fun finishLeaf(text: String, ws: WhiteSpace): String = when (ws) {
        WhiteSpace.NORMAL, WhiteSpace.NOWRAP, WhiteSpace.PRE_LINE -> text.trim(' ', '\t', '\r', '\u000C')
        WhiteSpace.PRE, WhiteSpace.PRE_WRAP -> text
    }

    /**
     * 无换行模式的分段：按 `\n` 硬换行切分（`<br>` 与 PRE 源码换行），每段一行、不换行。
     * 末尾空段丢弃（文本以 `\n` 结尾不产生额外空行，与断行器幻影行口径一致）。
     */
    fun splitNoWrap(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        // 丢弃末尾由结尾 `\n` 产生的空段（但保留中间空行）。
        var end = parts.size
        while (end > 1 && parts[end - 1].isEmpty()) end--
        return parts.subList(0, end)
    }

    /** 空白序列 → 单个空格（保留首尾各至多一个空格，由 finishLeaf 收尾）。 */
    private fun collapseWhitespace(s: String): String {
        var sawWs = false
        var dirty = false
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                sawWs = true
                if (c != ' ') dirty = true
            }
        }
        if (!sawWs) return s
        val sb = StringBuilder(s.length)
        var pending = false
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                pending = true
                if (c != ' ') dirty = true
            } else {
                if (pending) {
                    sb.append(' ')
                    pending = false
                }
                sb.append(c)
            }
        }
        if (pending) sb.append(' ')
        // 全空白节点 → 单个空格（调用方叶级 trim 会处理块首尾；行内边界空格保留一位）。
        return if (dirty || sb.length != s.length) sb.toString() else s
    }

    /** 制表符 → 8 空格（CSS `tab-size` 默认；列制表位近似以后续扩展）。 */
    private fun expandTabs(s: String): String {
        if (s.indexOf('\t') < 0) return s
        return s.replace("\t", "        ")
    }

    /** 空格/制表折叠，换行保留（PRE_LINE）。 */
    private fun collapseSpacesKeepNewlines(s: String): String {
        if (s.indexOf(' ') < 0 && s.indexOf('\t') < 0) return s
        val sb = StringBuilder(s.length)
        var pending = false
        for (c in s) {
            when (c) {
                ' ', '\t' -> pending = true
                else -> {
                    if (pending) {
                        sb.append(' ')
                        pending = false
                    }
                    sb.append(c)
                }
            }
        }
        if (pending) sb.append(' ')
        return sb.toString()
    }

    // ---- 归一化 + 原文位置映射（修订正文用；与上面的渲染函数同规则） ----

    private fun isWsChar(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun collapseWhitespaceWithMap(s: String): NormalizedText {
        val sb = StringBuilder(s.length)
        val starts = ArrayList<Int>(s.length)
        val ends = ArrayList<Int>(s.length)
        var runStart = -1
        for (i in s.indices) {
            val c = s[i]
            if (isWsChar(c)) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    sb.append(' ')
                    starts.add(runStart)
                    ends.add(i)
                    runStart = -1
                }
                sb.append(c)
                starts.add(i)
                ends.add(i + 1)
            }
        }
        if (runStart >= 0) {
            sb.append(' ')
            starts.add(runStart)
            ends.add(s.length)
        }
        return NormalizedText(sb.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun expandTabsWithMap(s: String): NormalizedText {
        val sb = StringBuilder(s.length)
        val starts = ArrayList<Int>(s.length)
        val ends = ArrayList<Int>(s.length)
        for (i in s.indices) {
            if (s[i] == '\t') {
                repeat(8) {
                    sb.append(' ')
                    starts.add(i)
                    ends.add(i + 1)
                }
            } else {
                sb.append(s[i])
                starts.add(i)
                ends.add(i + 1)
            }
        }
        return NormalizedText(sb.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun collapseSpacesKeepNewlinesWithMap(s: String): NormalizedText {
        val sb = StringBuilder(s.length)
        val starts = ArrayList<Int>(s.length)
        val ends = ArrayList<Int>(s.length)
        var runStart = -1
        for (i in s.indices) {
            val c = s[i]
            when (c) {
                ' ', '\t' -> {
                    if (runStart < 0) runStart = i
                }
                else -> {
                    if (runStart >= 0) {
                        sb.append(' ')
                        starts.add(runStart)
                        ends.add(i)
                        runStart = -1
                    }
                    sb.append(c)
                    starts.add(i)
                    ends.add(i + 1)
                }
            }
        }
        if (runStart >= 0) {
            sb.append(' ')
            starts.add(runStart)
            ends.add(s.length)
        }
        return NormalizedText(sb.toString(), starts.toIntArray(), ends.toIntArray())
    }
}
