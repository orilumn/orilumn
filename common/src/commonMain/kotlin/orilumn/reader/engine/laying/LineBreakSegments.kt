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
 * @param breakAfter 其**后**必可断的字符集（LB 连字符类、B2 类；即使两侧都不是宽字/空白）。
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
        // 软连字符之后可断（UAX#14 class BA「break after」）。**置禁则之后**是刻意的：
        // 否则 `a&shy;。` 会把句号丢到行首破禁则；词内字符本就不触发禁则，故不损失词内断点。
        // 「补连字符」这件事不在这里判定，由 [SoftHyphenBreakSource] 另标 [BreakOpportunitySet.markHyphen]。
        if (prev == SOFT_HYPHEN) return true
        return breakAfter.indexOf(prev) >= 0 || isWideBreakChar(prev) || isWideBreakChar(next)
    }

    companion object {
        /**
         * **中英合一的默认禁则规则**——本轮唯一取值。
         *
         * 四张表相对本轮改动前的差异：
         * ```
         * noBreakBefore +=  "”‘’〞%℃"   // T2：引号闭合类 + 单位符号（填|” 禁断）
         * noBreakBefore +=  "?"          // S1：转写漏字，见下方「ASCII ? 」专条
         * noBreakBefore -=  "〆"          // T2：Skia 允许 填|〆
         * noBreakAfter  +=  "“‘’"        // T2：引号开启类（“|填 禁断）
         * breakAfter     +=  "…‥"         // T2c：IN 类，两侧都不宽时仍需放行（…|填）
         * breakAfter     +=  "—–"         // T2e：B2 类，「破折号后」可断而「破折号前」仍禁断
         * breakAfter     +=  "/”]"        // T2d：SY/QU/CL 类，两侧都非宽时其后可断（见下方专条）
         * ```
         *
         * > ⚠ **`‘`(U+2018) / `’`(U+2019) 同时出现在 `noBreakBefore` 与 `noBreakAfter`**，
         * > 即两侧都粘。这是 T2 实测的 Skia 行为，不是笔误。
         *
         * > ⚠ **「—」(U+2014) 同时出现在 `noBreakBefore` 与 `breakAfter`**（T2c 只解决了前半、
         * > T2e 才补上后半），三格语义各自独立成立，**不要动其中一格**：
         * >  ① `填|—` 禁断 —— 中文破折号永不落行首（R4），比 Skia 严，是有意接受的假阳性；
         * >  ② `—|—` 禁断 —— UAX#14 LB17（B2 × B2 不断），破折号成对不拆开；
         * >  ③ `—|填` 可断 —— UAX#14 B2 类「前后可断」，**这条不补就会与 Skia 差一整段**：
         * >     T2e 的 `P×N` 矩阵实测 `—|A`、`—|1`、`—|(`、`—|[` 四格 Skia **全部可断**
         * >     （两侧都非宽字，异填充/异位置/尾部垫空格三路径复测 10/10 稳定），而 `—|—`、`—|–` 禁断。
         * >     不补这半格，这些位置上的断点集为空，贪心会一路退到更早的断点、
         * >     **白扔版心并把整段行数抬高**——正是 S3 判据里「总行数比值」要防的。
         * >     `—` 与 `–`(U+2013) 同属 UAX#14 **B2** 类，一并补齐，不引入表外新字符。
         * >  ④ ⚠ **另有一格比 ① 更宽**：T2e 实测 **`A|—`（两侧都非宽）Skia 可断、我们禁断**，
         * >     即本表在「拉丁词 + 破折号」接缝上比 Skia 严。`—` 在 `noBreakBefore` 正是为此，
         * >     要放行得移出，而移出会放行 `—|—` **制造假阴性**（与 LB21b 冲突）——
         * >     三表结构下无解，按「假阴性必须为 0」优先，记为族B1 的已知假阳性。
         * >
         * > ⚠ **`‘`(U+2018)/`’`(U+2019)`/`—` 这类「两侧都出现在表里」不是矛盾**：
         * > [allowsBreakAt] 的判定顺序是「空白 → `noBreakBefore`/`noBreakAfter` 否决 → `breakAfter` 放行」，
         * > 否决在前，故 `—|—`、`‘|’` 这类被否决，`—|填` 才落到放行。
         *
         * > ⚠ **`/`(SY) `”`(QU) `]`(CL) 收进 `breakAfter`（T2d），代价是 `]|A` 与 `]|1` 比 Skia 松**。
         * > 依据：T2d 的 `P×N` 二维断点矩阵（14×14 = 196 格，三条独立路径复测 33/33 稳定）实测，
         * > Skia 在**两侧都非宽字**时放行 `X|/N`、`X|”A`、`X|](` —— 这三格此前只被「两侧含宽字」覆盖，
         * > 是书库残差族Y 两条（`…ANSI/NISO…` 与 `…した[1](Rea…`）的唯一根因。
         * > **已知代价**：LB30 的 `CP × (AL | HL | NU)` 要求 `]|字母` 禁断，而三表结构表达不了这条 pair 规则；
         * > 收进 `breakAfter` 后 `]|a` 被放行 = 假阳性。书库实测该签名 **0 例**
         * > （补表前后假阳性计数均为 4、未增加），故按「假阴性必须为 0」优先接受。
         * > **要彻底修需引入 pair 规则层，属后续项**（同 T2c 已登记的 LB25 数字序列盲区一类）。
         * >
         * > ⚠ **ASCII `?` 故意收进来，尽管 Skia 实测放行 `填|?`**。
         * > 证据：① 本文件原 KDoc 一直把 `?` 写进闭合类（`) ] } ! ? , . : ; /`），是**转写漏字**；
         * > ② 同族的 `!` 在表里、全角 `？` 在表里，只有 ASCII `?` 缺席，无任何区分理由；
         * > ③ `?` 属 UAX#14 的 EX 类（`× EX`，不可落行首），而 Skia 疑似按旧版 LB13 的字面清单实现
         * > （旧清单只有 `]` `!` `;` `/`，无 `?`）——**这更像 Skia 的偏差，不是本表的**。
         * > 代价：`填|?` 上我们比 Skia 严（假阳性）。与「—」行首同性质，有意接受。
         *
         * **按语言选择规则是将来才做的事**（多语言口子，见方案 §0.2）：本轮不加选择逻辑，
         * 选择点留在 [isBreakOpportunity] 里读 `KinsokuRules.ZH_EN` 这一处，不散进判定内部。
         */
        val ZH_EN: KinsokuRules = KinsokuRules(
            noBreakBefore = ")]}!?,.:;/、。，．：；！？）】』」〉》〕］｝｣-‐‑–—・…‥ゝゞ々”‘’〞%℃",
            noBreakAfter = "([{\"“‘’（【『「〈《〔［｛｢",
            breakAfter = "-‐‑…‥—–/”]",
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
 * **可挤压的全角收尾标点**（标点挤压的候选集，纯字符类判定，**不含量**）。
 *
 * ## 它是「收尾类」而不是从 [KinsokuRules.ZH_EN.noBreakBefore] 里划子集
 *
 * 禁则表回答的是「**能不能断**」，挤压回答的是「**能不能收窄**」——两个不同的问题，
 * 成员的判据也不同（`/` `%` `℃` 不可落行首，但它们一个字位也不该收窄）。故独立成表，
 * 只在**「全角 + 墨迹偏左 + 高频」**这一个口径上相交。
 *
 * ## 逐类取舍（数据来自真书全量探测 @fs43.75，Source Han Sans SC）
 *
 * | 类 | 实测 advance / 墨迹 | 收不收 | 理由 |
 * |---|---|---|---|
 * | 句读 `、。，．：；！？` | 1.000em / 0.13–0.50em ⇒ 内置空白 **0.497–0.817em** | ✅ 收 | 收益最大，全角收尾标点的绝大多数 |
 * | 闭括 `）］｝〕〉》」』】` | 同上（`》` 0.497em 最紧） | ✅ 收 | 同属收尾类，内置空白够 |
 * | 闭引号 `”’` | `”` 0.589em、`’` 0.794em | ✅ 收 | 同上 |
 * | 省略号 `…‥` | `…` 墨迹 **0.869em** ⇒ 内置空白仅 **0.131em** | ❌ 不收 | 压了收益接近零，而两侧墨迹立刻相接 |
 * | 破折号 `—–` / 连字符 `-‐‑` | `—` advance **0.894em**、墨迹填满字身 | ❌ 不收 | 内置空白 ≈ 0（额度公式也会算成 0，但列进来候选集就失去筛选意义） |
 * | 单位 `%℃` | 同上，墨迹填满 | ❌ 不收 | 单位符号不是收尾标点 |
 * | 迭代记号 `ゝゞ々〻〞` | 未实测（按字身居中处理） | ❌ 不收 | 墨迹居中 ⇒ 压右侧只会让后继字贴上来，得不偿失 |
 * | ASCII 收尾类 `)]}!?,.:;/` | 半角，内置空白 ~0.05em 量级 | ❌ 不收 | 收益可忽略，却要给每次出现加一次墨迹测量 |
 *
 * > ⚠ **额度公式（`min(上限, 字宽 − 墨迹宽)`）本身已把「误收」变成无害**：
 * > 收进来但内置空白不足的字符会算出 0 或极小的额度。表因此是**筛选器**（省测量）而不是**安全网**，
 * > 安全网在 `orilumn.reader.engine.skia.PunctuationSqueeze`。
 *
 * @see orilumn.reader.engine.skia.PunctuationSqueeze 额度公式与施加点（渲染层·几何测量）
 */
const val SQUEEZABLE_CLOSING_PUNCT: String = "、。，．：；！？）］｝〕〉》」』】”’"

/** [text] 在 [i] 处是否是**可挤压的全角收尾标点**（标点挤压的**唯一**判据入口）。 */
fun isSqueezableClosingPunct(text: CharSequence, i: Int): Boolean =
    i in text.indices && SQUEEZABLE_CLOSING_PUNCT.indexOf(text[i]) >= 0

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

/**
 * CSS「文档空白」：只有这几个可断且可折叠（NBSP/EM SPACE 等不算，Chrome 实测）。
 *
 * 公开给自建断行器（[orilumn.reader.engine.skia.InhouseParagraphBreaker]）做**行尾空白悬挂**判据 ——
 * 「哪些字符算行尾可悬挂的空白」必须与断点判定同一份定义，故收成同一处单源。
 */
fun isDocumentSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

/**
 * **软连字符** `U+00AD SOFT HYPHEN`（HTML 里写作 `&shy;`）——断点规则单源。
 *
 * ## 它在文本流里是什么（CSS Text 3 §5.2）
 *
 * 一个**零宽、不渲染**的占位符，语义是「**这里可以断，断了就显示一个连字符**」。
 * 与音节断词（[EnglishHyphenationSource] 的 K-L 断点）产出的断点是**同一类断点**，
 * 故两者共用 [BreakOpportunitySet.markHyphen] 这一个「需要补连字符」的标记位。
 *
 * ## 实测（本仓改造前的四处缺陷，全部由 [TmpShyProbe] 量出）
 *
 * | 缺陷 | 实测 |
 * |---|---|
 * | 占 1em 全宽 | `adv[U+00AD] = 42.18` @fs=42.18（与 `-` 的 13.20 差 3.2 倍） |
 * | 会被画出来 | `faceForCp(0x00AD)` = STSong glyph **271**（有字形） |
 * | 其后不能断 | `hy­phen­ation` @W=120 断成 `hy­p` / `hen­` / `ation` —— **R1 硬切在字母中间** |
 * | K-L 断点无连字符 | `hyphenation` @W=120 断成 `hy`/`phen`/`ation`，行尾无 `-` |
 *
 * ## 判定顺序（[KinsokuRules.allowsBreakAt]）
 *
 * 排在**禁则之后**而非之前：`a&shy;。` 若因 SHY 放行，会把句号丢到行首，违反中文禁则。
 * 排在**文档空白之后**：空白已 return true，不受影响。软连字符的实际用法都在词内，
 * 词内字符本就不触发禁则，故「置禁则之后」既拿到词内断点、又不破禁则。
 */
const val SOFT_HYPHEN: Char = '\u00AD'

/** 是否软连字符 [SOFT_HYPHEN]（零宽占位符，其后是断点）。 */
fun isSoftHyphen(c: Char): Boolean = c == SOFT_HYPHEN

/**
 * **连字符字形**（CSS Text 4 `hyphenate-character` 的默认取值 `U+002D HYPHEN-MINUS`）。
 *
 * 「断在该处」时行尾补画的就是它；它的宽度必须与断行侧预留的宽度同源，
 * 否则要么超版心、要么 JUSTIFY 铺不满。
 */
const val HYPHEN_GLYPH: Char = '-'

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