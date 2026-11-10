package orilumn.reader.engine.text.preprocess

import orilumn.reader.engine.css.FontRun

/**
 * One CJK↔Western boundary adjustment.
 *
 * @property leftIndex index of the boundary char's last UTF-16 unit (the gap sits between
 *   `text[leftIndex]` and `text[leftIndex] + 1`).
 * @property gapEm gap advance in em (0.25 = 1/4 em by default).
 * @property spaceCount 该边界上被**一律吃掉（画成零宽）**的连续**空白字符**个数（0 = 边界直接相邻，没有空白可吃）。
 *   空白 = 半角空格 / 制表 / NBSP / 窄空格 / 表意空格（U+3000）等**除换行符以外的一切空白**
 *   （判据见 [isSeparatingSpace]）；换行是硬断行，不在「可吃的分隔空白」之列。
 *   整形层把这 [spaceCount] 个字位画成零宽（字符本身留在文本模型里，选中/检索/索引不受影响），
 *   但「读者要看 / 要带走」的文字会按 [eatenSpaceSlots] 把它们摘掉（复制、摘录）。
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
 * At a boundary where the author already typed whitespace (half-width space, tab, NBSP, thin space,
 * 表意空格 U+3000 …), that whitespace is treated as the "surplus separation" of the document model:
 * [CjkLatinGap.spaceCount]>0 tells the shaping layer to render those slots zero-width and absorb them
 * into the fixed gap (删除多余空白，注入固定间隙). **一律删除**（产品口径 2026-10-03，
 * 2026-11-10 扩到**一切空白**）：边界上**连续的整串空白**都被吃掉，只留一个注入的固定间隙；
 * 旧实现「只吃一个、两个空格就一个间隙都不给」的例外已删 —— 那条例外本身就是一处「滑块完全不起作用」。
 *
 * 空白判据只看**字符本身是不是空白**（[isSeparatingSpace]），**与它夹在哪个行内元素里无关**：
 * 文本在下游被摊平成**叶文本**（`NormalFlowLayout.leafText` / `styledSegments`），
 * `中文 <a>Rust</a>` 与 `中文<a> Rust</a>` 摊平后都是 `中文 Rust`，边界照检、空格照吃。
 * **换行符不吃**：那是硬断行（`<br>` / `<pre>` 的 `\n`），两侧的字本来就不在同一行。
 *
 * > ⚠ 一律删除的**已知代价**：`<pre>` 等宽块里靠多空格对齐的字符画（ASCII art）若含中文字符，
 * > 中西两侧的连续空白会被折叠成一个固定间隙而错位。真书《Rust 程序设计语言》全书
 * > 中西边界上的多空格**出现 0 次**（25 章 28123 处边界全部是「相邻」或「单空格」），
 * > 故按散文口径执行；等宽块要保对齐时得单独裁决（不是本轮范围）。
 *
 * Output is sorted ascending by [CjkLatinGap.leftIndex]; adjustments never overlap (each eaten
 * space run is claimed by at most one adjustment, since an adjustment's left slot is the single
 * char right before that run).
 *
 * ## `gapEm = 0` 是**参数为 0 的那一档**，不是「关掉」那一档（产品口径 2026-10-03 改）
 *
 * 原实现把 0 当成「整条功能不生效」：`gapsForRange` 直接返空、所有调用点还有一层
 * `if (cjkLatinSpacingEm > 0f)` 短路 ⇒ 作者手打的分隔空格被原样保留 ⇒ `Rust 的所有权` 在 0 档
 * 仍然是 `Rust 的所有权`，**中西之间留着一个整空格宽**，与「混排字距 = 0」的字面意思相反。
 *
 * 现在 0 档与其它档**走同一条规则**：边界照检、那串分隔空格照吃（画成零宽）、注入的间隙宽 = 0。
 * 于是「混排字距 = 0」＝**字面意义上的距离 0**（只剩 [letterSpacing] 那一份字间距），
 * 与档位之间只有 `gapEm` 这一个数在变，没有第二个分叉点。**别再把 0 写成特例分支。**
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
            // 接缝判据用 [seamKind]（表意文字**或中文标点**）：标点那一侧只吃空白、不发间隙
            // （产品口径 2026-11-10，宽度与条目由 [emitGap] 决定）。
            val ciKind = seamKind(cp)
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
                    if (after < n) {
                        val afterCp = codePointAt(text, after)
                        if (opposite(ciKind, seamKind(afterCp))) {
                            emitGap(out, iNext - 1, cp, afterCp, gapEm, spaces)
                        }
                    }
                } else if (opposite(ciKind, seamKind(nextChar.code))) {
                    emitGap(out, iNext - 1, cp, nextChar.code, gapEm, 0)
                }
            }
            i = iNext
        }
        return out
    }

    /**
     * 接缝上被**吃成零宽**的分隔空白字位（升序；`text` 坐标系）。
     *
     * 这些字位**仍在文本模型里**（下标、批注、进度照常按含空白的原文算），只是画出来宽度为 0。
     * 凡是「读者要看 / 要带走」的文字（复制、摘录）都得按本表把它们摘掉，否则
     * 「汉字和字母之间还夹着一个空格」会漏进剪贴板。
     *
     * ## 选区必须另改（2026-11-10 真机纠正）
     *
     * 原以为「零宽字位的 x 与它后一个字相同 ⇒ 二分永远落到后一个字，手柄不会在接缝上多停一下」。
     * **这个前提是错的**：行内盒推进量记在「空格 → 后一个字」之间（`判断出 <code>customers</code>`
     * 的 17px 框内边距就是记在空格字位上的），于是 `xs[空白]` 与 `xs[后字]` 之间多出一段
     * **没有墨的 x 区间**，触点落在里面会二分成那个空白字位 ⇒ 读者拖手柄能选中一段纯空白
     * （真机反馈原话：「拖动选区手柄可以选到单独的空白字符」）。字间距、JUSTIFY 余量同理。
     * 命中侧现在用 [isEatenSpaceSlot] 跳过这些字位（`LineHitTest.hit`）。
     *
     * **不另立一套判据**：直接从 [gaps] 的 `spaceCount` 展开 —— 间隙检出一处，这里就摘一处，
     * 两处永远同源（同一条规则写成两份实现是本仓最贵的教训）。[isEatenSpaceSlot] 是同一个判据的
     * 「只判一位」形式（命中每帧都问一次，整段扫一遍太贵），一致性由测试钉住。
     */
    fun eatenSpaceSlots(text: CharSequence): IntArray {
        val gaps = gaps(text)
        var n = 0
        for (g in gaps) n += g.spaceCount
        if (n == 0) return IntArray(0)
        val out = IntArray(n)
        var k = 0
        for (g in gaps) {
            for (s in 1..g.spaceCount) out[k++] = g.leftIndex + s
        }
        return out
    }

    /**
     * [index] 这个字位是不是接缝上被吃成零宽的分隔空白（**命中侧判据：这些字位不可选**）。
     *
     * 与 [eatenSpaceSlots] 同源同判（[seamKind] + [isSeparatingSpace] + [opposite]），只是不展开
     * 整张表、只判一个位：拖选手柄时每帧都要问一次，整段扫一遍太贵（大章 / `<pre>` 叶上万字）。
     * 两条实现的一致性由 `CjkLatinSpacingTest` 的锁用例钉住 —— 改判据必须两处一起改。
     *
     * 判定：字位本身是分隔空白，且它所在的**整串**空白两侧（跳过整串）恰好是一对中西接缝；
     * 串头/串尾落在段首段尾（另一头没有码本）不算 —— 与 [gaps] 的 `after < n` 守卫同判。
     */
    fun isEatenSpaceSlot(text: CharSequence, index: Int): Boolean {
        if (index !in 0 until text.length) return false
        if (!isSeparatingSpace(text[index])) return false
        var from = index
        while (from > 0 && isSeparatingSpace(text[from - 1])) from--
        var to = index + 1
        while (to < text.length && isSeparatingSpace(text[to])) to++
        if (from == 0 || to >= text.length) return false
        return opposite(seamKind(codePointBefore(text, from)), seamKind(codePointAt(text, to)))
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
     * | 行**末尾**那个边界字的间隙（右邻在下一行） | 见下面「行末悬空间隙」一节：**这里原来判错了** |
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
     *
     * ## 行末悬空间隙：**宽度压 0，空格照吃**（2026-10-03，产品口径「没有另一头就没有缝」）
     *
     * `leftIndex` 在本行内**不等于**这条间隙该有宽度。间隙是「中西之间的距离」，**距离需要两头**；
     * 而中西接缝是合法断点（[orilumn.reader.engine.laying.LineBreakSegments.allowsBreakAt] 的
     * `isWideBreakChar(prev)`），断点正好落在这里时，西文那一头在**下一行** ⇒ 本行右侧没有任何字，
     * 那 `gapEm × 字号` 是一段**永远不会有墨的空白**，白占版心。
     *
     * ⚠ **当日把这行判成「无害」，判错了**（原话：「行比预留窄 ⇒ JUSTIFY 少摊一点，无害」——
     * 那是「按行子串检测」这条**备选**路线的后果，不是本函数这条路的后果）：本函数让**两侧同源**，
     * 断行侧把这段宽**预留**进了版心、绘制侧又把它**落进**了 `adv`，于是它两头都算、谁也没省 ——
     * 它变成版心里凭空多出来的一段，谁也填不满：
     * - `natural` 里含它 ⇒ `slack = 版心 − natural` 少了它 ⇒ JUSTIFY **铺不到右缘**；
     * - `visibleRight` 里也含它 ⇒ 该字段「= 末字墨迹右缘」的契约在这行**说谎**（虚报 `gapEm × 字号`）。
     *
     * 实测（真书《Rust 程序设计语言》25 篇 / 30956 行 / 版心 900 / 字号 44.4 / 0.25 档）：
     * **1673 行（5.404%）**是行末悬空间隙；按 [orilumn.reader.engine.skia.LineAlignerTest] 锁 1 的
     * 同口径（排除额度封顶行与可见字形 < 2 的行）逐行归因，**无悬空间隙的 24064 行右缘洞 = 0.000px**，
     * 而**有悬空间隙的 1464 行洞均值 11.099px、最大 11.100px，其中 1460 行恰好等于
     * `0.25 × 44.4 = 11.1px`** —— 洞**就是**这段间隙本身，一行不差；0 档（`gapEm = 0`）同一批行洞 = 0.000。
     *
     * ⇒ 判据：`partner = leftIndex + 1 + spaceCount`（西文侧第一个字位）落在本行外 ⇒ **宽度压 0**。
     * 而 **spaceCount 原样带走**：那串分隔空格若悬挂在本行尾（贪心的常规断点就在空格之后，
     * 形状占 1669/1673），它们仍要被吃成零宽，否则下一行会凭空多出一个空格宽的行首缩进。
     *
     * **只压宽度、不压「性质」**：本函数返回的条目照样进 [orilumn.reader.engine.skia.JustifySlack.plan]
     * 的分类。但 JUSTIFY 拉伸**根本碰不到它** —— `LineAligner` 的 `xsExtraLimit = visibleCount − 1`
     * 只到「末可见字形之前」，而悬空间隙的左槽**必是本行末可见槽**（右侧再无可见字形），
     * 天然落在那个上界之外。实测「洞恰好 = 间隙宽、从不更大」正是这条的证据（若它还能被拉伸，
     * 洞会是间隙宽 + 一份额度）。
     *
     * ⚠ **断行侧仍按整段预留，本行绘制侧比预留窄一个 `gapEm`**（方向安全：画 ≤ 量，永不右溢）。
     * 实测代价：1673 行 × 11.1px ≈ 20.6 行文本 / 30956 行 = **0.067%**，肉眼不可见。
     * 彻底对称要动断行器的 `hang` 记账（把间隙当行尾空白 deferred），会让这 1673 行的断点挪动、
     * 全书重新分页 —— 与本条「零断点变化」的价值不合，故登记在 [docs/TODO-未尽事宜.md]，不在本轮做。
     */
    fun gapsForRange(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        gapEm: Float,
        runs: List<FontRun> = emptyList(),
    ): List<CjkLatinGap> {
        // ⚠ **不再对 `gapEm == 0` 短路**（产品口径 2026-10-03 改，原为「0 = 整条关掉」）。
        //
        // **0 档不是特例档，它是参数为 0 的那一档**：「中西之间只由字间距分隔」意味着距离**真的
        // 是 0**，而作者为了让中英分开而手打的那串半角空格正是要替换掉的东西（CLREQ 4.1
        // 「删除多余半角空格，注入固定间隙」）—— 间隙宽可以是 0，**空格一律照吃**。
        // 于是 `Rust 的所有权` 在 0 档排成 `Rust的所有权`，与其它档位的差别**只有间隙宽这一个数**，
        // 而不在「有没有删空格」上分叉（原先的短路让 0 档独走一套规则，是同一条规则的两个实现）。
        //
        // ⚠ 代价与必须连带改的地方：
        //  - `LayoutParamKey` 的「`cjkLatinSpacingEm == 0` 复现历史流」固定点作废（0 现在会改版面）；
        //  - 所有 `if (cjkLatinSpacingEm > 0f)` 的短路都要删（`LineAligner` /
        //    [orilumn.reader.engine.skia.InhouseParagraphBreaker] /
        //    [orilumn.reader.engine.skia.KerningClusterTable] /
        //    [orilumn.reader.engine.skia.ClusterTrackGraft] /
        //    [orilumn.reader.engine.skia.SkiaRunMeasurer]），否则两侧对 0 档给出两套版面。
        if (endExcl <= start) return emptyList()
        val s = start.coerceAtLeast(0)
        val e = endExcl.coerceAtMost(text.length)
        if (e <= s) return emptyList()
        val out = ArrayList<CjkLatinGap>()
        for (g in gaps(text, gapEm, runs)) {
            if (g.leftIndex < s) continue
            // [gaps] 按 leftIndex 升序 ⇒ 越过上界即可收尾。
            if (g.leftIndex >= e) break
            // 行末悬空间隙：**宽度压 0**（西文那一头在下一行 ⇒ 右侧无墨，这段宽白占版心）。
            // `spaceCount` 原样带走 —— 悬挂在本行尾的那串分隔空格照吃，见 KDoc「行末悬空间隙」。
            val em = if (g.leftIndex + 1 + g.spaceCount < e) g.gapEm else 0f
            out.add(CjkLatinGap(g.leftIndex - s, em, g.spaceCount))
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
     * - `中　A` → U+3000 **表意空格**被判 CJK ⇒ 间隙插在**空格后面**，空格本身还在；
     *   现在 U+3000 判 [KIND_NONE]（不是 CJK 侧），但它作为**空白**在接缝处被吃掉
     *   （2026-11-10 口径「所有空白字符均删除」，见 [isSeparatingSpace]）⇒ 只剩一个注入的间隙。
     *   全角空格是**中文内部 / 段首**的版式留白（空一格、代替首行缩进），夹在**中英文之间**
     *   本就没有正当理由 —— 那里要的是「两种文字分开」，而这正是混排字距的职责，
     *   故接缝上照删、不回退。
     *
     * ⇒ 判据从「落在 CJK 码位段」收紧为「**是表意文字**」。全角拉丁 `Ａ-Ｚ`（0xFF21..0xFF3A）
     * 一并不再参与：它本身是等宽的刻意字形，与半角拉丁之间再加间隙只会更难读。
     *
     * ## 为什么是 `public` 而不是 `private`（渲染层反查）
     *
     * [orilumn.reader.engine.skia.JustifySlack]（**渲染层**·行内几何）判 JUSTIFY 槽位性质时，
     * 「哪些字符算 CJK / 哪些算西文」必须与本处**同一份**——两处各写一份码位区间，
     * 改了一处忘了另一处就会静默分叉（教训㩼：同一规则两处实现）。
     * 本对象已在 `common` 内、本仓唯一，渲染层**向下依赖**它不构成越层（AGENTS.md）。
     */
    fun isCjk(cp: Int): Boolean =
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
     *
     * ⚠ 公开理由与 [isCjk] 相同（`JustifySlack` 的槽位分类复用它），**别收回 `private`**。
     */
    fun isWestern(cp: Int): Boolean =
        cp in 0x0030..0x024F && cp.toChar().isLetterOrDigit()

    /**
     * 中文标点：**只在「删接缝空白」时算 CJK 侧**（[seamKind]），**永不参与混排间隙**
     * （[kindOf] 里它仍是 [KIND_NONE]，`gaps` 不会在标点旁边插间隙）。
     *
     * 收的是句读与括号引号这两族——它们与拉丁字母之间出现的空白，一律按「接缝空白」删掉；
     * **刻意不收**破折号 `—`/`―` 与省略号 `…`：这两个符号两侧留不留空白是作者的排版选择
     * （`Rust — 中文` 这种写法很常见），吃掉会改变作者意图，而它们本来也不会触发间隙。
     */
    private fun isCjkPunctuation(cp: Int): Boolean =
        cp in 0x3001..0x3003 || cp in 0x3008..0x3011 || cp in 0x3014..0x301F ||
            cp == 0x30FB || cp in 0x2018..0x201D ||
            cp == 0xFF01 || cp == 0xFF08 || cp == 0xFF09 || cp == 0xFF0C || cp == 0xFF0E ||
            cp == 0xFF1A || cp == 0xFF1B || cp == 0xFF1F || cp == 0xFF3B || cp == 0xFF3D ||
            cp == 0xFF5B || cp == 0xFF5D

    /**
     * 接缝判据（[gaps] / [eatenSpaceSlots] 共用）：CJK 侧 = 表意文字 **或中文标点**。
     *
     * 与 [kindOf] 的差别只有「标点算不算 CJK 侧」这一条。标点必须算进来，否则
     * `Int。 Kotlin` 里 `。` 与 `K` 之间的那个空格永远吃不掉；但**宽度另算** ——
     * 标点接缝只吃空白、不注入间隙（[emitGap]），因为全角标点的字身里本就带着半个字身的留白。
     */
    private fun seamKind(cp: Int): Int = when {
        isCjk(cp) || isCjkPunctuation(cp) -> KIND_CJK
        isWestern(cp) -> KIND_WESTERN
        else -> KIND_NONE
    }

    /**
     * 记一条接缝：**中文标点那一侧只吃空白、不注入间隙**（产品口径 2026-11-10）。
     *
     * 用户原话：「中文标点与英文字符之间不要有额外的混排字距」。全角标点的字身里本就带着
     * 半个字身的留白（`。` 的墨在字身左下、右侧空着），作者在它后面打的空格照吃（[spaceCount]），
     * 但不再叠一道间隙 —— 否则 `Int。 Kotlin` 会变成一个空格宽的空档。
     *
     * 两条不同的口径，别合并：
     * - **表意文字**接缝：间隙宽就是 `gapEm`，**哪怕是 0 也要记**。下游拿的是**位置**不是宽度 ——
     *   `InlineBoxRuns` 按 `em = 0` 调 [gaps] 判「这一侧是不是接缝」，据此决定吃不吃行内盒
     *   的内边距/外边距。少记一条，那一侧的白就白留了（`中<span>A</span>文` 的框内边距）。
     * - **中文标点**接缝：`A，B`、`Kotlin：` 这种「标点两侧本来就挨着」的形态本来就无事可做，
     *   记一条 0 宽 0 空的条目只会让下游以为这里有一道边界（测试
     *   `punctuation and symbols never participate` 钉的就是这个）。只有真吃了空白才记。
     */
    private fun emitGap(
        out: MutableList<CjkLatinGap>,
        leftIndex: Int,
        a: Int,
        b: Int,
        gapEm: Float,
        spaces: Int,
    ) {
        if (isCjkPunctuation(a) || isCjkPunctuation(b)) {
            if (spaces > 0) out += CjkLatinGap(leftIndex, 0f, spaces)
        } else {
            out += CjkLatinGap(leftIndex, gapEm, spaces)
        }
    }

    private fun kindOf(cp: Int): Int = when {
        isCjk(cp) -> KIND_CJK
        isWestern(cp) -> KIND_WESTERN
        else -> KIND_NONE
    }

    private fun opposite(a: Int, b: Int): Boolean =
        (a == KIND_CJK && b == KIND_WESTERN) || (a == KIND_WESTERN && b == KIND_CJK)

    /**
     * 「分隔空白」= 中西接缝上要被**吃成零宽**的那一串字符：**Unicode 空白，换行符除外**
     * （产品口径 2026-11-10，用户原话「中间的所有空白字符（不包括换行符）均删除，
     * 由混排字距统一来做隔离」）。
     *
     * ## 为什么不用 `Char.isWhitespace()`
     *
     * 这是 KMP 共享代码，三个平台的「空白」口径并不完全一致（JVM 的
     * `Character.isWhitespace` 把 NBSP / U+2007 / U+202F 判成**非**空白，而
     * `Character.isSpaceChar` 又判成空白；native 走的是另一套表）⇒ 同一本书在平板与
     * 桌面会排成两个版面。空白是**排版几何**的输入，必须逐码点写死、三端同值。
     *
     * ## 收哪些（Unicode `Zs` 全集 + 制表）
     *
     * | 码本 | 名字 | 典型来源 |
     * |---|---|---|
     * | U+0009 | TAB | 散文里的制表（NORMAL 会先折叠成空格；`<pre>` 会展开成空格） |
     * | U+0020 | SPACE | 作者手打的半角空格（主战场） |
     * | U+00A0 | NBSP | HTML `&nbsp;`、EPUB 转换产物（中西之间「不该断行」的间隔） |
     * | U+1680 | OGHAM SPACE MARK | 极罕见，按 `Zs` 归类并入 |
     * | U+2000..U+200A | EN QUAD … HAIR SPACE | 西文书排版的精细空格，`&thinsp;`(U+2009)、`&ensp;`、`&emsp;`、U+2007 数字空格 |
     * | U+202F | NARROW NBSP | 法文标点旁、部分 EPUB 的「细不断行空格」 |
     * | U+205F | MEDIUM MATHEMATICAL SPACE | 公式排版 |
     * | U+3000 | IDEOGRAPHIC SPACE | 全角空格（中文内部 / 段首的版式留白；夹在**中英文之间**没有正当理由，接缝上照删） |
     *
     * ## 不收哪些
     *
     * - **换行符**：`\n`(LF) / `\r`(CR) / `\u000B`(VT) / `\u000C`(FF) / `\u0085`(NEL) /
     *   `\u2028`(LS) / `\u2029`(PS) —— 两边本来就不在同一行，没有「中间」可吃；
     *   而且把换行吃掉会让行界与字形错位（`<br>` 的硬换行尤其）。
     * - **零宽字符**（U+200B ZWSP / U+FEFF BOM / U+2060 WJ）：它们本来就不占宽，
     *   吃不吃版面完全一样，收进来只会让 `spaceCount` 多算而**把后文的字一起置零**
     *   （`applyCjkLatinGaps` 按 `spaceCount` 逐槽置 0）—— 这是会造成丢字的坑，故明确不收。
     * - 全角逗号、全角字母这类**全角字形族**（U+FF00..U+FFEF）仍在 [isCjk] 之外：
     *   它们是**字形**不是空白，收进来会把标点一起吃掉。
     */
    private fun isSeparatingSpace(c: Char): Boolean {
        val cp = c.code
        return cp == 0x0009 || cp == 0x0020 || cp == 0x00A0 || cp == 0x1680 ||
            (cp in 0x2000..0x200A) || cp == 0x202F || cp == 0x205F || cp == 0x3000
    }

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

    /**
     * 读 [i] **之前**那个码本（代理对感知），与 [codePointAt] 反向配对。
     *
     * [isEatenSpaceSlot] 要判「空白串左边那个字」是不是中西接缝的一侧；扩展 B 的表意字是代理对，
     * 直接读 `s[i - 1]` 会读到低代理（判成 [KIND_NONE]）⇒ 接缝漏判 ⇒ 那个空白又变成可选。
     */
    private fun codePointBefore(s: CharSequence, i: Int): Int {
        val last = s[i - 1].code
        if (last in 0xDC00..0xDFFF && i >= 2) {
            val first = s[i - 2].code
            if (first in 0xD800..0xDBFF) return 0x10000 + ((first - 0xD800) shl 10) + (last - 0xDC00)
        }
        return last
    }
}