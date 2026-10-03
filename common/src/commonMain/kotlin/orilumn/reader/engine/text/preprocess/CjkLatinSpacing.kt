package orilumn.reader.engine.text.preprocess

import orilumn.reader.engine.css.FontRun

/**
 * One CJK↔Western boundary adjustment.
 *
 * @property leftIndex index of the boundary char's last UTF-16 unit (the gap sits between
 *   `text[leftIndex]` and `text[leftIndex] + 1`).
 * @property gapEm gap advance in em (0.25 = 1/4 em by default).
 * @property spaceCount 该边界上被**一律删除**的连续分隔空格个数（0 = 边界直接相邻，没有空格可吃）。
 *   整形层把这 [spaceCount] 个字位画成零宽（字符本身留在文本模型里，选中/检索/索引不受影响）。
 */
data class CjkLatinGap(
    val leftIndex: Int,
    val gapEm: Float,
    val spaceCount: Int = 0,
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
 * At a boundary where the author already typed half-width (or NBSP) spaces, those spaces are
 * treated as the "surplus half-width space" of the document model: [CjkLatinGap.spaceCount]>0 tells
 * the shaping layer to render them zero-width and absorb them into the fixed gap (删除多余半角空格，
 * 注入固定间隙). **一律删除**（产品口径 2026-10-03）：边界上**连续的全部**分隔空格都被吃掉，
 * 只留一个注入的固定间隙；旧实现「只吃一个、两个空格就一个间隙都不给」的例外已删 ——
 * 那条例外本身就是一处「滑块完全不起作用」。
 *
 * > ⚠ 一律删除的**已知代价**：`<pre>` 等宽块里靠多空格对齐的字符画（ASCII art）若含中文字符，
 * > 中西两侧的连续空格会被折叠成一个固定间隙而错位。真书《Rust 程序设计语言》全书
 * > 中西边界上的多空格**出现 0 次**（25 章 28123 处边界全部是「相邻」或「单空格」），
 * > 故按散文口径执行；等宽块要保对齐时得单独裁决（不是本轮范围）。
 *
 * Output is sorted ascending by [CjkLatinGap.leftIndex]; adjustments never overlap (each eaten
 * space run is claimed by at most one adjustment, since an adjustment's left slot is the single
 * char right before that run).
 *
 * ## 「按段检测」与「按行检测」为什么不会打架（**画 == 量**，2026-10-03 改）
 *
 * 间隙的**施加**发生在两处：断行侧对**整段**检测
 * （[orilumn.reader.engine.skia.InhouseParagraphBreaker] 预留版心），绘制侧落 x 位与簇位轨
 * （[orilumn.reader.engine.skia.LineAligner] / [orilumn.reader.engine.skia.KerningClusterTable]）。
 *
 * **两侧现在都必须走 [gapsForRange]：同一个 [text]、同一个 em、同一份 [gaps]，绘制侧只裁到本行。**
 * 此前绘制侧是「按行子串检测」，两侧集合不同 —— 而「少」在不同位置的后果是**相反**的：
 *
 * | 少掉的是什么 | 后果 |
 * |---|---|
 * | 行**末尾**那个边界字的间隙（右邻在下一行） | 行比预留窄 ⇒ JUSTIFY 少摊一点，无害 |
 * | 「被吃掉的空格」（挂在行尾空白里，**仍在行区间内**） | **行比预留宽整整一个空格宽** ⇒ 右溢被裁 |
 *
 * 第二行是真缺陷，且**末行没有 JUSTIFY 兜底**：实测（fs=40 / STSong+serif / 版心 300）
 * `中文 Rust` 在 `文│空` 处断行时，断行侧预留 73.08、绘制侧落墨 83.08 = **画比量宽 10.000px**
 * （正好一个空格）。真机症状是「英文与中文之间距离不受滑块控制」：作者那个空格还在
 * （间隙没注入），而空格的宽与滑块无关。真书 29238 行里这种行占 8.7%（2540 行）。
 *
 * ⇒ 改成同源之后，两侧拿到**同一个集合**，逐行「画 == 量」（断行侧预留到哪、墨就落到哪）。
 * 下面那条「逐位置无状态」的性质**仍然必须保持** —— 它是 [gapsForRange] 只做一次整段检测
 * 就能覆盖所有行的前提（旧实现那一跳跳掉一个真边界，产出**依赖遍历路径**，
 * 子串检出会**多于**整段检出 ⇒ 画比量宽 ⇒ 右溢被裁；见「被删掉的那一跳」）。
 *
 * ## 被删掉的那一跳（2026-10-02 接线时查清，**这条不变式就靠它**）
 *
 * 旧实现在发出一个「吃空格」的间隙后写的是
 * `i = after + charCountOf(cpAfter); continue`。那一跳**跳掉了一个真边界**：
 * `中 A中文` 里 `中`(0)↔`A`(2) 的间隙刚发出去，紧接着 `A`(2)↔`中`(3) 这个同样合法的边界
 * 就被跳过了 ⇒ `A` 与它后面的中文之间一个字距都不加。
 *
 * 改成 `i = iNext`（' ' 类别为 NONE，本就发不出 gap；新间隙的左槽是 `after`，与被吃掉的空格槽
 * `iNext` 相邻但不同位，不重叠也不受影响）之后，本函数回到逐位置无状态。
 *
 * ## 判据只看「字符类别」，且**只有字母与数字参与**
 *
 * 两侧各取一个类别（[KIND_CJK] / [KIND_WESTERN] / 其它），异类即成边界。关键是**谁才算
 * CJK、谁才算 Western**，判据只有一条：**必须是表意文字 / 字母 / 数字**。
 * 标点、符号、空白一律 [KIND_NONE]，于是它们**天然隔断邻接** ——
 * `中文,English` 里 `,` 两侧各是一个「CJK↔NONE」「NONE↔Western」，一处都不成边界
 * （这正是要的行为：作者已经用标点给出了视觉间隔，不必再插间隙）。
 *
 * 2026-10-03 按真机反馈把这条判据收紧了一轮（此前 CJK 侧含 `U+3000..U+303F` 与
 * `U+FF00..U+FFEF`、Western 侧是整段 `U+0030..U+024F`）：
 * - `Kotlin：` 的 `：`(U+FF1A)、`Rust。` 的 `。`(U+3002) 都被判成 CJK 侧 ⇒ 间隙插到了**标点**旁边；
 * - `中　A` 的 U+3000 **表意空格**也被判成 CJK 侧 ⇒ 间隙插在**作者留的全角空格后面**；
 * - Latin-1 里的 `± × ÷ ° © « » ¢ £ ¥ § ¶ ·` 都是符号，却被当成「西方字母」⇒ 符号两侧也插间隙。
 *
 * 收紧后的具体范围与逐条理由见 [isCjk] / [isWestern] 的 KDoc。
 * **已知不覆盖**：假名（`U+3041..U+30FF`）与谚文等仍判 NONE ⇒ 日文/韩文与拉丁之间没有间隙。
 * 那是本项目语料（中译技术书）之外的形态，本轮**未裁决**，不在此处凭空定死。
 *
 * ## 被删掉的一段死代码（2026-10-02 接线时查清）
 *
 * 旧实现在「相邻」分支里还有一层 `isWesternPunctOrSymbol` 判定 + 「附近有没有手打空格」的
 * 附加条件，两条都**恒不成立**：
 * - `isWesternPunctOrSymbol(nextCp)` 要求 `nextCp` 落在 ASCII 标点区，而能走到这一分支的前提
 *   是 `opposite(ciKind, kindOf(nextCp))` —— 即 `nextCp` 必须**已经**被判为 CJK 或 Western，
 *   而 ASCII 标点两者都不是 ⇒ 该判定恒 false；
 * - 「附近空格」查的是 `text[j-1]`（= 边界字自己）与 `text[i+1]`（仅当边界字是代理对才成立），
 *   前者要成立就意味着边界字是空格 —— 而空格本身不是 CJK/Western，压根进不了这个分支。
 * ⇒ 那段 `if (isSpecial) { ... } else { 发 gap }` 等价于**永远不发 gap**，即
 * 「含 ASCII 标点的边界一个间隙都不给」。已按上面「判据只看字符类别」的口径删净。
 */
object CjkLatinSpacing {

    /**
     * Returns the CJK↔Western boundary gaps of [text].
     *
     * @param runs 行内 face 段（预留；**当前实现不消费**）。跨 run 边界的语义尚未定义：
     *   `<code>`/等宽段与正文的交界该不该插间隙、CSS `text-autospace` 对 `ideograph-alpha`
     *   的取值范围都还没有裁决，故这里刻意不按 run 分流，避免凭空定死一个语义。
     *   形参保留是因为它是本函数将来唯一的分流入口（删了再加就是改签名）。
     */
    fun gaps(text: CharSequence, gapEm: Float = DEFAULT_GAP_EM, runs: List<FontRun> = emptyList()): List<CjkLatinGap> {
        @Suppress("UNUSED_EXPRESSION")
        runs
        val out = mutableListOf<CjkLatinGap>()
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = codePointAt(text, i)
            val iNext = i + charCountOf(cp)
            val ciKind = kindOf(cp)
            // ⚠ 后继存在与否必须按 `iNext < n` 判，**不能**用旧写法的 `i + 1 < n`：
            //   边界字是代理对（U+20000 起的 CJK 扩展 B）时 `iNext == i + 2`，
            //   旧判据在「该字位于段末」时会放行到 `text[i + 2]` → **越界崩排版线程**
            //   （实测 `"\uD840\uDC00"` 单独一段即崩；旧实现是死代码所以从未触发，
            //   接线即触发）。此处 `iNext < n` 同时覆盖单码元（与旧判据等价）与代理对两种情形。
            if (ciKind != KIND_NONE && iNext < n) {
                val nextChar = text[iNext]
                if (isSeparatingSpace(nextChar)) {
                    // ⚠ **必须再确认空格后面还有码本**（`iNext + 1 < n`），否则读 `text[iNext + 1]` 越界
                    //   **崩排版线程**。触发形态极常见：**文本以分隔空格结尾**（`"中 "`）。
                    //   这不是构造出来的边角料 —— 生产里 [LineAligner] 与
                    //   [KerningClusterTable] 都是**按行子串**检测的，而行尾悬挂的文档空白
                    //   **仍在行区间内**（[InhouseParagraphBreaker.greedy] 的 UAX#14 LB SP 语义），
                    //   也就是说**几乎每一行以空格收尾的正文都会走到这里** ⇒ 每次排版都崩。
                    //   实测（本轮接线锁抓到的）：`"中A "` 于
                    //   `CjkLatinSpacing.gaps` 第 91 行抛 `StringIndexOutOfBoundsException`。
                    //   与上面那条 `iNext < n` 是**同一族的第二个越界点**：修一处不够，两处都要。
                    //
                    // 一律删除（产品口径 2026-10-03）：把边界上**连续的全部**分隔空格吃掉，
                    // 只留一个注入的固定间隙。旧实现「只吃一个、两个空格就一个间隙都不给」
                    // 的例外已删 —— 那条例外本身就是一处「滑块完全不起作用」。
                    //
                    // ⚠ 下面这个数空格的 `while` 是**纯前瞻**：它不推进 `i`、不吞掉任何码元，
                    //   `i` 仍由循环末尾那条 `i = iNext` 推进（本函数只有**一个**推进点，
                    //   「逐位置无状态」不变式就靠它；旧写法 `i = after + cc(after); continue`
                    //   那一跳跳掉了一个真边界 `中 A中文` 的 `A`↔`中`，并让产出**依赖遍历路径**
                    //   ⇒ 子串检出多于整段检出 ⇒ 画比量宽 ⇒ 右溢被裁。记录见类 KDoc）。
                    var after = iNext
                    var spaces = 0
                    while (after < n && isSeparatingSpace(text[after])) {
                        after++
                        spaces++
                    }
                    // ⚠ 空格串后面没有码本 ⇒ 它不是「中西之间的分隔空格」，只是段末/行末的空白
                    //   ⇒ 不成边界、不发 gap。这个守卫同时**消掉了越界读** `text[after]`：
                    //   触发形态极常见（文本以分隔空格结尾 `"中 "`；生产里行尾悬挂的文档空白
                    //   **仍在行区间内**，于是几乎每行以空格收尾的正文都会走到这里）。
                    if (after < n && opposite(ciKind, kindOf(codePointAt(text, after)))) {
                        out += CjkLatinGap(iNext - 1, gapEm, spaces)
                    }
                } else if (opposite(ciKind, kindOf(nextChar.code))) {
                    out += CjkLatinGap(iNext - 1, gapEm, 0)
                }
            }
            i = iNext
        }
        return out
    }


    /**
     * 本行 `[start, endExcl)` 要施加的间隙，**下标已平移成行内局部**（0 = `text[start]`）。
     *
     * ## 为什么绘制侧必须走本函数、而不是直接 `gaps(text.subSequence(start, endExcl))`
     *
     * 断行侧 [orilumn.reader.engine.skia.InhouseParagraphBreaker.breakLines] 是在**整段**上检测的，
     * 并把间隙与「被吃掉的空格」一起算进 `adv` 预留给了版心。绘制侧若改成「按行子串检测」，
     * 检出的集合会**天然更少**，而两个方向的后果完全不同：
     *
     * | 少掉的是什么 | 后果 |
     * |---|---|
     * | 行**中间**的间隙（不可能发生，见下） | 行比预留窄 ⇒ 安全，只是白留一点版心 |
     * | 行**末尾**那个边界字的间隙（右邻在下一行） | 行比预留窄 ⇒ JUSTIFY 少摊一点，无害 |
     * | 「被吃掉的空格」（它挂在行尾空白里，仍在行区间内） | **行比预留宽整整一个空格宽** ⇒ 右溢被裁 |
     *
     * 第三条是真缺陷（`NoLineExceedsContentWidthTest` 那条硬约束的反面），且**末行无 JUSTIFY
     * 兜底**：实测（fs=40 / STSong+serif / 版心 300）`中文 Rust` 在 `文│空` 处断行时，
     * 断行侧预留 73.08、绘制侧落墨 83.08 —— **画比量宽 10.000px**，正好一个空格。
     * 真机侧的表现是「英文与中文之间距离不受滑块控制」：作者那个空格还在（间隙没注入），
     * 而空格的宽与滑块无关。
     *
     * ⇒ 正确做法是**与断行侧同源**：同一个 [text]、同一个 [gapEm]、同一份 [gaps] 结果，
     * 再裁到本行。这样两侧拿到的是**同一个集合**，逐行「画 == 量」（分页器预留到哪、墨就落到哪）。
     *
     * ## 为什么裁剪只按 `leftIndex ∈ [start, endExcl)` 判（`spaceCount` 可以越界）
     *
     * 间隙的**左槽**就是边界字自己；它一定在本行内当且仅当 `leftIndex ∈ [start, endExcl)`。
     * 而 `leftIndex < start`（左槽在**上一行**）**不可能发生**：UAX#14 LB 禁止在空格之前断行
     * （`× SP`），本仓断行器据此把行尾空白**悬挂**在行区间内 —— 真书 29238 行里
     * **以分隔空格开头的行 = 0 行**（实测），所以边界字与它后面的那串空格必然同进同出。
     * 反过来 `leftIndex ≥ endExcl` 就是纯下一行的事，与本行无关。
     *
     * [CjkLatinGap.spaceCount] 越界**合法且必须容忍**：一串连续空格中间断开时（LB SP 允许
     * 空格之后断行），本行只持有其中前几个 —— 剩下的字位不在 `adv` 里，
     * [orilumn.reader.engine.skia.SkiaRunMeasurer.applyCjkLatinGaps] 已有 `left + k < n` 守卫。
     */
    fun gapsForRange(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        gapEm: Float,
        runs: List<FontRun> = emptyList(),
    ): List<CjkLatinGap> {
        if (gapEm <= 0f || endExcl <= start) return emptyList()
        val s = start.coerceAtLeast(0)
        val e = endExcl.coerceAtMost(text.length)
        if (e <= s) return emptyList()
        val out = ArrayList<CjkLatinGap>()
        for (g in gaps(text, gapEm, runs)) {
            if (g.leftIndex < s) continue
            // [gaps] 按 leftIndex 升序 ⇒ 越过上界即可收尾。
            if (g.leftIndex >= e) break
            out.add(CjkLatinGap(g.leftIndex - s, g.gapEm, g.spaceCount))
        }
        return out
    }

    private const val DEFAULT_GAP_EM = 0.25f
    private const val KIND_NONE = 0
    private const val KIND_CJK = 1
    private const val KIND_WESTERN = 2

    /**
     * CJK 侧：**只认表意文字本身**（扩展 A / 基本区 / 兼容表意 / 扩展 B）。
     *
     * ## 为什么剔掉 `U+3000..U+303F` 与 `U+FF00..U+FFEF`（产品口径 2026-10-03）
     *
     * 这两段是「CJK 符号与标点」和「全角形」，里面**没有一个表意文字**，却都被旧口径算成
     * CJK 侧，于是混排字距插到了**标点**旁边（真机实测《Kotlin 程序设计语言》）：
     * - `Kotlin：` → `：`(U+FF1A) 被判 CJK ⇒ `n`与`：`之间插了一个间隙（用户报「冒号受影响」）；
     * - `Rust。` → `。`(U+3002) 同理 ⇒ 句号前凭空多半个身位；
     * - `中　A` → U+3000 **表意空格**被判 CJK ⇒ 间隙插在**空格后面**，空格本身还在
     *   （全角空格是作者有意留的版式空格，不该被动）。
     *
     * ⇒ 判据从「落在 CJK 码位段」收紧为「**是表意文字**」。全角拉丁 `Ａ-Ｚ`（0xFF21..0xFF3A）
     * 一并不再参与：它本身是等宽的刻意字形，与半角拉丁之间再加间隙只会更难读。
     */
    private fun isCjk(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x2A6DF

    /**
     * Western 侧：Latin 基本字母数字 + Latin-1 补充 + Latin 扩展 A/B（`0x0030..0x024F`）之中
     * **确实是字母或数字**的那些码本。
     *
     * ## 为什么要加 `isLetterOrDigit()` 这一层（产品口径 2026-10-03）
     *
     * `0x0030..0x024F` 这一段里混着大量**标点与符号**：`± × ÷ ° © « » ‹ › ¢ £ ¥ § ¶ · ¤` ……
     * 旧口径一律当「西方字母」，于是 `中文±`、`中文°` 这类**符号**两侧也插间隙（用户报
     * 「英文标点和异种字符不该受影响」）。加这一层后它们落到 [KIND_NONE]，
     * 与 ASCII 标点一样**天然隔断邻接** —— 语义也就统一了：*只有字母/数字参与混排*。
     *
     * 副作用两条，都已核过：
     * - `0x00A0`(NBSP) / `0x00AD`(SHY) 的显式排除**已被这一层完全覆盖**（两者都不是字母也不是
     *   数字）⇒ 旧的 `if` 分支删净，不必保留恒 false 的写法；
     * - 上标 `² ³ ¹`（Unicode 类别 `No`，不是 `Nd`）也一并落到 NONE。它们极少与中文相邻，
     *   即便相邻，少一个间隙也不构成缺陷 —— 换来的是判据只有一条、不必逐个列例外码本。
     */
    private fun isWestern(cp: Int): Boolean =
        cp in 0x0030..0x024F && cp.toChar().isLetterOrDigit()

    private fun kindOf(cp: Int): Int = when {
        isCjk(cp) -> KIND_CJK
        isWestern(cp) -> KIND_WESTERN
        else -> KIND_NONE
    }

    private fun opposite(a: Int, b: Int): Boolean =
        (a == KIND_CJK && b == KIND_WESTERN) || (a == KIND_WESTERN && b == KIND_CJK)

    /** 半角空格 / NBSP：作者手打的那个「多余半角空格」（**用码点写死**，不可见字符写成字面量会被 IDE 吃掉）。 */
    private fun isSeparatingSpace(c: Char): Boolean = c == ' ' || c.code == 0x00A0

    /**
     * Reads the code point starting at [i] (surrogate-pair aware), so CJK Extension B code points
     * classify correctly instead of as two isolated surrogates.
     *
     * 刻意**只返码本、不返 (码本, 下一下标) 的 Pair**：本函数在**每一次量宽**里被调用（每个叶、
     * 每行至少一次），而 `Pair` 是装箱对象 —— 每码本一个的分配量比整条量宽路径的其它开销都大。
     * 下一码元数由 [charCountOf] 单独算（两个纯 Int 函数都可被 JIT 内联与标量化）。
     */
    private fun codePointAt(s: CharSequence, i: Int): Int {
        val first = s[i].code
        if (first in 0xD800..0xDBFF && i + 1 < s.length) {
            val second = s[i + 1].code
            if (second in 0xDC00..0xDFFF) return 0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
        }
        return first
    }

    /** 码本占用的 UTF-16 码元数（代理对 2、其余 1）。 */
    private fun charCountOf(cp: Int): Int = if (cp > 0xFFFF) 2 else 1
}