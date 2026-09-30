package orilumn.reader.engine.laying

/**
 * 断点增强器（分行控制，渲染层）——「谁有权往断点集里加位置」的一元接缝。
 *
 * 形状由 S2(b) 冻结：**断点集是被动的值对象，规则全部从外面注入**，本轮不硬编码任何语言。
 * - 禁则表（[KinsokuRules]）经 [KinsokuBreakSource] 注入；
 * - S7 的 K-L 断词作为**另一个** source 注入，**只加 source、不改本文件**；
 * - [BreakOpportunitySet.of] 顺序无关（各 source 只置位、不清位，注入顺序不影响结果集）。
 *
 * 加 language 策略时新开 source，不扩 [orilumn.reader.engine.laying.ParagraphBreaker] 签名
 * （S2 语言策略：适用范围按 script 判定，不接 `lang`）。
 */
fun interface BreakOpportunitySource {
    /** 把本 source 认定的断点打进 [into]（`text[i-1]` 与 `text[i]` 之间）；不改动其它 source 已标的位。 */
    fun mark(text: CharSequence, into: BreakOpportunitySet)
}

/**
 * 整段断点集（分行控制，渲染层）——「`text` 的哪些位置可以断」的单次算完结果。
 *
 * **为什么是值对象而不是算法**：断行器只认「断点在哪」，不认「为什么」（禁则 / 断词 / 未来规则）。
 * 规则与载体分离后，S7 加断词不触碰断行主循环，禁则表（S1）也不必知道断行器怎么填。
 *
 * **下标约定**：位置 `i` 指 `text[i-1]` 与 `text[i]` 之间，与 [isBreakOpportunity] 一致。
 * 内部数组长 `n + 1`：**下标 `n`（段末）恒 false** —— 段末不是「两字符之间」，
 * 这样贪心填充读 `opportunityAt(i)` 时不需要区分「段内」和「段末」。
 *
 * **热路径**：段级一次性算完（O(n)），断行主循环只做 O(1) 读，故方法内一律不做边界检查
 * （越界由 Kotlin 自身的数组下标检查兜底，加显式 `if` 只是白付一次分支）。
 */
class BreakOpportunitySet private constructor(
    private val flags: BooleanArray,
) {
    /** 段长（`text.length`）：下标上界（不含）——`opportunityAt(n)` 恒 false。 */
    val size: Int get() = flags.size - 1

    /**
     * 标一个断点（前置条件 `0 <= i <= size`）。
     *
     * [BreakOpportunitySource] 的唯一写入口：只置位不清位，故多 source 叠加顺序无关。
     */
    fun mark(i: Int) {
        flags[i] = true
    }

    /**
     * 紧急态：**每个位置都算断点**（R1 core ——「无可用断点且该单元放不下时，在任意字符处断开」）。
     *
     * 前置条件 `0 <= i <= size`。含下标 0：整段第一个字符之后允许断开，即「一字一行」是可达状态
     * （S2 R1 core 行为规格：`abcd` @Roboto 20px，W=12/14/16/20 → 逐字一行）。
     */
    fun markAll() {
        flags.fill(true)
    }

    /** 位置 [i] 是否可断（前置条件 `0 <= i <= size`；`i == size` 恒 false）。 */
    fun opportunityAt(i: Int): Boolean = flags[i]

    /**
     * `<= i` 的最后一个断点位置；一个都没有返回 `-1`。
     *
     * 贪心填充用增量变量 `lastOpp` 即可（O(1)），本方法供「从一个已知位置往回找最近断点」
     * 的调用点（如回溯收紧当前行）与回归测试使用。
     */
    fun lastOpportunityAtOrBefore(i: Int): Int {
        var j = i.coerceAtMost(size)
        while (j >= 0 && !flags[j]) j--
        return j
    }

    /** 逐位相等（回归测试用；不含 [size]，两个不同长度的段自然不等）。 */
    fun contentEquals(other: BreakOpportunitySet): Boolean = flags.contentEquals(other.flags)

    companion object {
        /**
         * 整段算一次断点集（收全部 source，**顺序无关**）。
         *
         * @param sources 断点增强器；空列表得到「无任何断点」的集合（段末兜底由 [lastOpportunityAtOrBefore]
         *   返回 -1 与断行器的紧急态共同承担）。
         */
        fun of(text: CharSequence, sources: List<BreakOpportunitySource>): BreakOpportunitySet {
            val n = text.length
            val set = BreakOpportunitySet(BooleanArray(n + 1))
            for (s in sources) s.mark(text, set)
            return set
        }

        /** 紧急态集合：整段每个位置都可断（R1 core 的唯一入口，断行器只在兜底时用）。 */
        fun allPositions(n: Int): BreakOpportunitySet =
            BreakOpportunitySet(BooleanArray(n + 1).also { it.fill(true) })
    }
}

/**
 * 禁则表作为 [BreakOpportunitySource]（S2 冻结的形状之第一处消费方）。
 *
 * 规则本体在 [isBreakOpportunity]（单源），本对象只做「把整段扫一遍」的外壳，
 * **不复制任何规则**——与 [minContentSegments] 走同一个判定入口，两条路径对同一输入必然同断点集。
 */
object KinsokuBreakSource : BreakOpportunitySource {
    override fun mark(text: CharSequence, into: BreakOpportunitySet) {
        // 下标 0 不标：位置 0 是段首、之前没有字符，「断在这里」无意义（R1 紧急态才可能标 0）。
        for (i in 1 until text.length) {
            if (isBreakOpportunity(text, i)) into.mark(i)
        }
    }
}
