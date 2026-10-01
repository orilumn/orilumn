package orilumn.reader.engine.laying

/**
 * S7 第二步：**Liang K-L** 断词引擎（纯函数、无平台依赖、逐字节对照 pyphen 实现）。
 *
 * ## 为什么自己实现而不是用平台的
 *
 * 平台**有**行断点、没有**音节断词**：
 *  - skiko 绑定了 `BreakIterator`（ICU `createLineInstance`）⇒ UAX #14 + UAX #29 的
 *    **词边界 + 标点/空白规则**，官方文档写「similar to word breaking, but not the same」。
 *    英文按 UAX#14 `AL × AL` 本来就禁断，**它不去词典里找音节**（实测单个拉丁词零断点）。
 *  - 音节断词在 ICU 里是**另一个组件**（`HyphenationEngine` + Liang pattern），skiko 没绑定；
 *    Android 有 `android.icu.text.Hyphenation` 而桌面/iOS 没有 ⇒ 「平台差异化来源」
 *    直接违反 AGENTS.md 的 KMP 跨平台共享约束（两端能力不对等）。
 *
 * 故唯一 KMP 合规的做法是**内置一张 Liang K-L pattern 表 + 在本仓实现匹配**。
 *
 * ## 算法（照 pyphen `HyphDict` 逐行对照，那是 Liang 算法验证最广的移植）
 *
 * 1. 模式串按 `(可选数字)(非数字)` 切成 `tags`（要匹配的字符）与 `values`（权重）；
 *    缺省权重 0、串尾补 0。
 * 2. **掐掉首尾的 0** 并记 `offset`（首部 0 不产生边界效应，去掉后模式更短、命中更快）。
 * 3. 词形前后补 `.`。
 * 4. 每处匹配把 `values` 与目标区间**逐位取 `max`** 合并进 `references`
 *    （**不是相加** —— 多个模式命中同一位置时取较大权重，这是 Liang 的定义）。
 * 5. `references[i]` 为**奇数** ⇒ 该处可断；再按 `left`/`right` 裁掉太靠边的断点。
 *
 * ## 踩过的坑（都表现为「结果明显不对」而非报错）
 *
 * - **模式里的数字不参与匹配**（它是前一个字符的权重）。当成字符比 ⇒ 表里几乎每条都带数字
 *   ⇒ 一条都匹配不上、结果恒为空。
 * - **权重用 `max` 合并，不是相加**：相加会让同一位置累加成任意奇偶 ⇒ 断点泛滥
 *   （`hyphenation` 断成 `h-y-p-he-na-t-i-o-n`）。
 * - **必须掐掉首尾 0**：不掐会让大量模式在词内产生虚假边界。
 */
object Hyphenator {

    /** 语言 tag（取前两字母）→ 原始模式表。**接新语种的唯一入口**。 */
    private val TABLES: Map<String, Array<String>> = mapOf(
        "en" to HyphEnUsPatterns.TABLE,
    )

    /** 已加载语种（供自检/调试）。 */
    fun loadedLanguages(): Set<String> = TABLES.keys

    /** 一条预处理后的模式：[chars] 要匹配的字符（长度 = [values]），[offset] 首部 0 的个数。 */
    private class Pattern(val chars: String, val offset: Int, val values: IntArray)

    /** 模式索引：`字符序列 → Pattern`。**只建一次**（表是编译期常量）。 */
    private val index: Map<String, Pattern> by lazy { buildIndex(TABLES["en"]!!) }

    /** 最长模式字符数（扫描窗口上界）。 */
    private val maxLen: Int by lazy { index.values.maxOf { it.chars.length } }

    private fun buildIndex(raw: Array<String>): Map<String, Pattern> {
        val m = HashMap<String, Pattern>(raw.size * 2)
        for (p in raw) {
            // **逐字节对照 pyphen `re.compile(r'(\d?)(\D?)').findall` + `zip(*[(s, int(i or '0'))])]`**：
            // 该正则产出 (前置数字, 非数字字符) 序列且**允许字符为空**。一个权重若出现在串尾
            // （`hy3`），它后面跟的是**空 tag** ⇒ 权重落在「字符之间的间隙」上而非字符本身。
            // `.ach4` 的真实结果是 tags=('.','a','c','h','','') values=(0,0,0,0,4,0)
            // —— `h` 的权重 4 落在它**之后**那一格。第一版把权重挂在字符上（整体错一位），
            // 结果 `hyphenation` 断成 `hy-p-he-na-tion` 而非 `hy-phen-ation`。
            //
            // 实现：`gap` 标记该格是否为空 tag（权重位置），key 只含**非空 tag**，
            // `values`/`offset` 仍按**含空格的格序号**计，与 pyphen 的 slice 偏移一致。
            val key = StringBuilder()
            val vals = ArrayList<Int>(p.length + 2)
            val gapAt = ArrayList<Boolean>()      // 该格是否为空 tag
            var i = 0
            while (i < p.length) {
                val w = if (p[i].isDigit()) p[i].digitToInt() else 0
                if (p[i].isDigit()) i++
                if (i < p.length) { key.append(p[i]); gapAt.add(false) }
                else { gapAt.add(true) }           // 串尾数字 ⇒ 空 tag（间隙）
                vals.add(w)
                i++
            }
            gapAt.add(true); vals.add(0)            // 串尾补一格

            if (vals.max() == 0) continue
            // 掐掉首尾 0 并记 offset（pyphen `chop zeros`）。offset 以**格**为单位。
            var start = 0
            var end = vals.size
            while (start < vals.size && vals[start] == 0) start++
            while (end > 0 && vals[end - 1] == 0) end--
            if (end <= start) continue
            val sub = vals.subList(start, end).toIntArray()
            if (key.isEmpty()) continue
            m[key.toString()] = Pattern(key.toString(), start, sub)
        }
        return m
    }

    /**
     * 词内断点（相对 [word] 起点的下标，升序、无重复、首尾不出界）。
     *
     * @param left 左侧最少保留字符数（英文实践 2）。
     * @param right 右侧最少保留字符数（英文实践 2 ⇒ 末两字母不拆）。
     * @param lang 语言 tag；未加载则返空（调用方退回 R1 硬切兜底）。
     */
    fun hyphenate(word: String, lang: String = "en", left: Int = 2, right: Int = 2): List<Int> {
        if (word.length < left + right + 1) return emptyList()
        if (!TABLES.containsKey(lang.lowercase().substring(0, 2))) return emptyList()
        if (!isLatinWord(word)) return emptyList()

        val body = "." + word.lowercase() + "."
        val n = body.length
        val refs = IntArray(n + 1)

        // 循环边界逐字节对照 pyphen `HyphDict.positions`（差一位就会漏模式）：
        //   pyphen: for i in range(n-1): stop = min(i+maxLen, n) + 1; for j in range(i+1, stop)
        // ⇒ `i < n-1`、`j` 上界是 **min(i+maxLen, n)**（含）。第一版把 j 上界写成 n-1 且 i 跑到 n-1，
        // 漏掉最长模式的匹配 ⇒ 断点偏少/位置偏（`hyphenation` 少 6 处）。数值不能凭直觉取整。
        for (i in 0 until n - 1) {
            val stop = minOf(i + maxLen, n)
            for (j in i + 1..stop) {
                val pat = index[body.substring(i, j)] ?: continue
                val base = i + pat.offset
                for (k in pat.values.indices) {
                    val p = base + k
                    if (p <= n) refs[p] = maxOf(refs[p], pat.values[k])
                }
            }
        }

        // 裁剪边界逐字节对照 pyphen：`left <= i <= len(word) - right`（**含右端**）。
        // 第一版写成 `idx in left until hi`（排他）⇒ 词尾少一个断点，
        // 与 pyphen 在 computer/university/identifier 上差 1 处。
        val hi = word.length - right
        val out = ArrayList<Int>(4)
        for (p in 1 until n) {
            if (refs[p] % 2 == 0) continue
            val idx = p - 1
            if (idx >= left && idx <= hi) out.add(idx)
        }
        return out.distinct().sorted()
    }

    /** 纯拉丁字母词（数字/符号/CJK 不适用，交给别的 source 或 R1 兜底）。 */
    private fun isLatinWord(w: String): Boolean {
        for (c in w) {
            if (c !in 'a'..'z' && c !in 'A'..'Z' && c !in '\u00C0'..'\u024F') return false
        }
        return true
    }
}