package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.laying.HYPHEN_GLYPH
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing
import orilumn.reader.io.Logger
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * 逐码本量宽器（渲染层·几何测量）——自建断行器与 S4 `orilumn.reader.engine.laying.LineAligner`
 * 的**唯一取宽口**。
 *
 * 算法与全部硬约束由实测钉死，见 `docs/自建断行引擎-实施方案.md` §2.2(d) 与
 * `docs/自建断行引擎-测试计划.md` §T8（五节全部有数据）：
 *
 *  1. **逐码本**解字面（不是逐段、不是逐 run）：`[MySerif, STSong, serif]` 上「整段一个面」
 *     实测偏 **+32.69px** —— 段落是逐字形回退的。
 *  2. **外层族栈、内层 manager 链**（= `FontCollection` 语义）：写反实测偏 **+49.51px**
 *     （让内嵌面压过了族栈序）。**族栈序优先于 manager 序**。
 *  3. 覆盖判定**自建**：`matchFamilyStyle(族名, style)` + 「该面 `getUTF32Glyph(cp) != 0`」。
 *     **不能**用 `matchFamiliesStyleCharacter` —— 它是 `FontMgr` 上的 final 转发，对
 *     `TypefaceFontProvider`（内嵌字体）**一律返 `null`**，而内嵌字体是生产真实路径（这是 P0）。
 *  4. letterSpacing 模型 = `Σadv + n·lsPx`（**按码本数、含末字符**）：逐码本各加一次 `lsPx`，
 *     零特判。`lsPx` 按**该码本所属 run 的字号**算（与 `runTextStyle` 逐 run 传同一 `letterSpacingEm` 同源）。
 *  5. **不整形**（`Font.getWidths` 是裸 cmap 查表）：这是「量画一致」的**自洽前提**而非省事 ——
 *     断行量 `Σ` 逐码本、Aligner 的 `x_i = x_0 + Σ(w_j + delta_j)` 用同一个取宽口、
 *     S5 逐字 `drawString` 落位（每个 drawString 只含一个字符，Skia 无从整形），三者同源。
 *     代价与暴露面已量化并登记 `docs/TODO-未尽事宜.md` Q5（含拉丁字母的行失去 kerning 与 fi/fl 连字）。
 *
 * **同源约束（S2(a) 冻结）**：族栈走 [SkParagraphFactory.resolveFamilies]、字重走
 * [SkParagraphFactory.runFontStyle]、资产端走 [SkiaFontPool.managers]，与 `paragraphStyle` 同一出口。
 * 另起一份解析逻辑即量画错位（教训 ⑩：比值会从 1.20x 虚高到 4.05x）。
 *
 * **native 调用预算**：覆盖探测与取宽都**按面批量**（`getUTF32Glyphs(IntArray)` +
 * `getWidths(ShortArray)`），一个段（同 run 同字号）只付 `2 × 触达面数` 次 native 调用，
 * 与段长无关。CJK 段（首族即覆盖）恒 **2 次**。
 *
 * 线程：面表缓存（[faceCache]）在锁内读写、值不可变；**段内备忘是方法局部变量**（天然线程安全），
 * 故排版线程与绘制线程可各自调用本量宽器，无需外部串行化。
 */
class SkiaRunMeasurer(
    private val managers: () -> List<FontMgr> = SkiaFontPool::managers,
) {
    /**
     * 逐码本 advance（px，**含该码本所属 run 的 letterSpacing**）；长度 = `text.length`。
     *
     * 行内 face 段（[fontRuns]，同文本坐标）按浏览器 inline-run 语义逐段换面 —— 与 `paragraphStyle`
     * 的 `runTextStyle` 同一口径，量画一致。
     *
     * @param cjkGaps 混排字距的边界间隙（**下标与 [text] 同坐标系**，[CjkLatinSpacing.gaps] 的产物；
     *   空 = 关）。**施加只发生在 [applyCjkLatinGaps] 这一处**：断行器（预留版心）、
     *   [LineAligner]（落 x 位）、[KerningClusterTable]（cluster 轨迹）三条路都走本方法，
     *   故不存在「量宽算了一份、落墨算了另一份」的分叉（量画同源，教训 ⑩）。
     *
     *   ⚠ 形参收**间隙列表**而不是 `cjkLatinSpacingEm`（em）：三个调用方都还需要间隙的**位置**
     *   （aligner 的「行末悬挂空格把间隙一起挂掉」、cluster 轨道的累计位移），
     *   而检测是 O(n) 的扫描 —— 由调用方检测一次、传进来，才不会扫第二遍。
     *   em 值仍在每个 [CjkLatinGap.gapEm] 里（检测时写入），施加侧仍是唯一一处。
     */
    fun advances(
        text: CharSequence,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        cjkGaps: List<CjkLatinGap> = emptyList(),
    ): FloatArray {
        val n = text.length
        val out = FloatArray(n)
        if (n == 0) return out
        val mgrs = managers()
        val segs = segments(text, n, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns, mgrs)
        for (s in segs) measure(text, s.from, s.to, s, out)
        if (cjkGaps.isNotEmpty()) applyCjkLatinGaps(fontRuns, fontSizePx, cjkGaps, out)
        return out
    }

    /**
     * 混排字距的**唯一施加点**（渲染层·几何测量）：把每个边界间隙加进对应字位的 advance。
     *
     * ## 施加规则（两条，缺一不可）
     *
     *  1. `adv[leftIndex] += gapEm × 该字所在 run 的字号`。字位选 **leftIndex**（边界左侧那个字）而不是
     *     右侧那个：`x_i = x_0 + Σ_{j<i} adv[j]`（[LineAligner]），加在 leftIndex 上才把**右边的
     *     所有字**推开；加在 `leftIndex + 1` 上则连 `leftIndex` 自己都被推开（那等于把间隔画在左边）。
     *     字号的取法与 `lsPx` 同源（按 run 字号，即 `run.fontPxOr(fontSizePx)`）——同一条规则，
     *     不另立标准。
     *  2. `spaceCount > 0` 时**再把 `adv[leftIndex + 1 .. leftIndex + spaceCount]` 全部置 0**：
     *     边界上那串手打空格被收进固定间隙里（产品口径「原有空格一律删除」/ CLREQ 4.1
     *     「删除多余半角空格，注入固定间隙」），必须画成零宽，否则 `0.25em` 的间隙
     *     会变成 `0.25em + 一串空格宽`。逐槽置 0 而不是「只置第一个」是「一律删除」的要求：
     *     旧实现只吃一个、其余空格保留（且不给间隙），那条例外已删。
     *
     * > ⚠ 置 0 只改**绘制几何**，字符本身仍在文本模型里 ⇒ 选中/复制/检索/索引不受影响。
     *
     * ## 为什么放在量宽器的出口而不是段内
     *
     * 段内 [measure] 的 `adv[i]` 是「字形宽 + 该 run 的 lsPx」，一个段一个 `lsPx`、一个 `sizePx`；
     * 间隙的字号判定与之逐条一致，若塞进 [measure] 就得让每段都多跑一次边界扫描（而 CJK 段恒 2 次
     * native 调用的预算正是被 [measure] 的批量取宽守住的）。出口施加 = **段数不变、段内零改动**。
     */
    private fun applyCjkLatinGaps(
        fontRuns: List<FontRun>,
        fontSizePx: Float,
        cjkGaps: List<CjkLatinGap>,
        adv: FloatArray,
    ) {
        val n = adv.size
        for (g in cjkGaps) {
            val left = g.leftIndex
            // gap 必须落在**两个边界字之间**，故其左侧那个字位一定在 text 内（探测器保证 ≥ 0）。
            if (left < 0 || left >= n) continue
            val size = fontRuns.firstOrNull { it.start <= left && left < it.endExclusive }?.fontPxOr(fontSizePx) ?: fontSizePx
            adv[left] += g.gapEm * size
            // 一律删除：`spaceCount` 个空格字位全置零。`1..0` 是空区间（Kotlin 的 IntRange 不回绕），
            // 故「相邻、无空格可吃」的间隙不需要额外分支。
            for (k in 1..g.spaceCount) if (left + k < n) adv[left + k] = 0f
        }
    }

    /**
     * 供「不需要间隙位置」的调用方（[naturalWidth] 一类）的一行便捷入口：
     * 就地检测一次再交给 [advances]。
     */
    private fun advancesWithGaps(
        text: CharSequence,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
        cjkLatinSpacingEm: Float,
    ): FloatArray = advances(
        text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns,
        // ⚠ **0 档不短路**（产品口径 2026-10-03）：`gapEm = 0` 仍要检出边界并吃掉分隔空格，
        //   只是注入的间隙宽为 0。见 [CjkLatinSpacing] 类 KDoc「`gapEm = 0` 是参数为 0 的那一档」。
        CjkLatinSpacing.gaps(text, cjkLatinSpacingEm, fontRuns),
    )

    /** 整段自然宽（px，max-content / [orilumn.reader.engine.laying.ParagraphBreaker.preferredWidth]）。 */
    /**
 * S5 逐字绘制的取面入口：**必须复用 [faceTable]，不得另写一份取面逻辑。**
 *
 * ## 为什么不能另写（第一版就是这么栽的）
 *
 * 我第一版在 `GlyphPainter` 里写了 `baseGlyphStyle`，它有三处与生产段落侧不一致：
 * ① 用了裸 `FontStyle(weight, …)` 而非 [SkParagraphFactory.runFontStyle]（少了字重锚点解析）；
 * ② 只取族栈 `[0]` 首名，**丢掉整栈按字形回退**（CJK/Latin 混排必然取错面）；
 * ③ 用 `defaultFontMgr()` 而非内嵌池 manager（内嵌字体全取不到）。
 * 三条合起来 ⇒ **量画失配**（教训 ⑩ 的同一个坑：比值会从 1.20x 虚高到 4.05x），
 * 表现为 `LineWindowDrawerTest` 7 把全红「行无墨」—— 逐字 `drawString` 拿到了空字体。
 *
 * 故本方法只做一件事：把码本交给 [faceTable] 挑出**覆盖该码本的第一张面**，
 * 与量宽路径（[measure]）**逐字用同一张表**（§2.2(d) 同源约束）。
     *
     * @param cp 码本。找不到覆盖面时返 `null`，由调用方决定回落（绘制侧用空 Font，绝不崩）。
     */
    fun faceForCp(
        cp: Int,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        sizePx: Float,
    ): Font? {
        val mgrs = managers()
        val seg = Seg(
            0, 0, faceTable(tag, families, monospace, weight, italic, sizePx, mgrs),
            0f, sizePx, emptyArray(), SkParagraphFactory.runFontStyle(families, weight, italic), mgrs, monospace,
        )
        for (font in seg.fonts) {
            if (font.getUTF32Glyph(cp) != NOTDEF) return font
        }
        // 族栈全灭 ⇒ 保底面表（与 [universalPass] 同一张、同一条「第一个覆盖者即采用」规则 ⇒ 量画同源）。
        // 仍无 ⇒ 与 [measure] 同口径用首面 notdef（画出来也是 .notdef，量画一致）。
        for (tf in universalTypefaces(seg.mono, seg.style.weight, seg.style.slant == FontSlant.ITALIC, seg.mgrs)) {
            val font = Font(tf, seg.sizePx)
            if (font.getUTF32Glyph(cp) != NOTDEF) return font
        }
        // 保底表也没有 ⇒ **系统回退链**（旧 Skia 路径靠的就是它，见 [systemFallbackFace]）。
        // 枚举表按族名建，看不到 emoji 这类「宿主链能到、族名里没有」的面。
        systemFallbackFace(cp, seg.style, families.firstOrNull(), sizePx)?.let { return it }
        logUnresolvableCp(cp, seg)
        return if (seg.fonts.isEmpty()) null else seg.fonts[0]
    }

    fun naturalWidth(
        text: CharSequence,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        cjkLatinSpacingEm: Float = 0f,
    ): Float {
        val adv = advancesWithGaps(
            text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns, cjkLatinSpacingEm,
        )
        var sum = 0f
        for (a in adv) sum += a
        return sum
    }

    /**
     * **连字符宽**（px）：断词断点处行尾要补的那个 [HYPHEN_GLYPH] 的 advance。
     *
     * ## 为什么必须走本量宽器（量画同源，教训 20）
     *
     * 断行侧用它**预留**版心、绘制侧用它**落墨**、Aligner 用它算 JUSTIFY 拉伸基数 ——
     * 三处任一处另算就是量画失配（行溢出 / 铺不满）。故与 [advances] 共用同一条取面出口。
     *
     * @param at 连字符所在的**绝对下标**（行末）。用它查 [fontRuns] 得到该字的 face：
     *   `<code>hyphenation</code>` 里的连字符必须按等宽面量，不能按正文字体量。
     * @return 该 face 下 `-` 的 advance；无候选面时按 [advances] 同款兜底（不抛）。
     */
    fun hyphenWidthPx(
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        at: Int = -1,
    ): Float {
        val r = fontRuns.firstOrNull { at >= 0 && it.start <= at && at < it.endExclusive }
        val size = r?.fontPxOr(fontSizePx) ?: fontSizePx
        val mgrs = managers()
        val seg = Seg(
            0, 0,
            faceTable(
                r?.tag ?: tag, r?.families ?: families, r?.monospace ?: monospace,
                r?.weight ?: weight, r?.italic ?: italic, size, mgrs,
            ),
            letterSpacingEm * size, size,
            SkParagraphFactory.resolveFamilies(r?.tag ?: tag, r?.families ?: families, r?.monospace ?: monospace),
            SkParagraphFactory.runFontStyle(r?.families ?: families, r?.weight ?: weight, r?.italic ?: italic),
            mgrs, r?.monospace ?: monospace,
        )
        val cp = HYPHEN_GLYPH.code
        for (font in seg.fonts) {
            val w = font.getWidths(shortArrayOf(font.getUTF32Glyph(cp)))
            if (font.getUTF32Glyph(cp) != NOTDEF) return w[0] + seg.lsPx
        }
        // 无候选面覆盖：与 [measure] 同款兜底（字面上画出来也是 .notdef，量画一致）。
        return fallbackWidth(seg, cp) ?: notdefWidth(seg.fonts[0])
    }

    /**
     * **单个字形相对自身笔位的墨迹区间**（px）：`[left, right]`。
     *
     * 左右两端都可能有负值（负边距的字形），故这是**区间**而不是「墨迹宽」——
     * 标点挤压真正要回答的是「收窄字位后墨迹还在不在盒里、相邻两字的墨迹会不会相接」，
     * 那两条判据分别落在 `right` 与 `left` 上（见 [PunctuationSqueeze]）。
     *
     * 与 [advances] 的区别：那个量的是**笔位到笔位**的推进（`Σadv` 累加用），
     * 这个量的是**墨迹在笔位两侧的偏移**（重叠判定用）。两者不可互相换算 ——
     * advance 恒定而墨迹可变，这正是「挤压能省出版心」的全部空间。
     *
     * @param cp 码本（不是字符下标）。
     * @param at 该码本在**文本里的绝对下标**，用它查 [fontRuns] 得到该字的 face
     *   （`fontRuns` 与正文同坐标系，见 [advances]）。
     * @return 该 face 下 `cp` 的墨迹区间；**任何候选面都不覆盖它时返 `null`**
     *   （调用方按「额度 0」处理 —— 量不到墨迹就不许压，这是安全的一侧）。
     */
    fun glyphInkBoxPx(
        cp: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        at: Int = -1,
    ): GlyphInkBox? {
        val r = fontRuns.firstOrNull { at >= 0 && it.start <= at && at < it.endExclusive }
        val size = r?.fontPxOr(fontSizePx) ?: fontSizePx
        val mgrs = managers()
        val seg = Seg(
            0, 0,
            faceTable(
                r?.tag ?: tag, r?.families ?: families, r?.monospace ?: monospace,
                r?.weight ?: weight, r?.italic ?: italic, size, mgrs,
            ),
            letterSpacingEm * size, size,
            SkParagraphFactory.resolveFamilies(r?.tag ?: tag, r?.families ?: families, r?.monospace ?: monospace),
            SkParagraphFactory.runFontStyle(r?.families ?: families, r?.weight ?: weight, r?.italic ?: italic),
            mgrs, r?.monospace ?: monospace,
        )
        // **族栈优先**，取第一个覆盖者 —— 与 [measure] / [faceForCp] 同一条规则（量画同源，教训 ⑩）。
        for (font in seg.fonts) {
            val gid = font.getUTF32Glyph(cp)
            if (gid != NOTDEF) {
                val rect = font.getBounds(shortArrayOf(gid))[0]
                return GlyphInkBox(rect.left, rect.right)
            }
        }
        return null
    }

    // ---- 分段：按 run 边界切，同段共用一张面表与一个 lsPx ----

    private class Seg(
        val from: Int,
        val to: Int,
        val fonts: Array<Font>,
        val lsPx: Float,
        val sizePx: Float,
        val stack: Array<String>,
        val style: FontStyle,
        val mgrs: List<FontMgr>,
        /** 等宽语境（保底表按它定序：等宽 CJK 面优先），见 [universalTypefaces]。 */
        val mono: Boolean,
    )

    private fun segments(
        text: CharSequence,
        n: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
        mgrs: List<FontMgr>,
    ): List<Seg> {
        if (fontRuns.isEmpty()) {
            return listOf(
                Seg(
                    0, n,
                    faceTable(tag, families, monospace, weight, italic, fontSizePx, mgrs),
                    letterSpacingEm * fontSizePx, fontSizePx,
                    SkParagraphFactory.resolveFamilies(tag, families, monospace),
                    SkParagraphFactory.runFontStyle(families, weight, italic), mgrs, monospace,
                ),
            )
        }
        val edges = (fontRuns.flatMap { listOf(it.start, it.endExclusive) } + listOf(0, n))
            .filter { it in 0..n }.distinct().sorted()
        val out = ArrayList<Seg>(edges.size)
        for (k in 0 until edges.size - 1) {
            val rs = edges[k]
            val re = edges[k + 1]
            if (re <= rs) continue
            val r = fontRuns.firstOrNull { it.start <= rs && re <= it.endExclusive }
            val rTag = r?.tag ?: tag
            val rFam = r?.families ?: families
            val rMono = r?.monospace ?: monospace
            val rWeight = r?.weight ?: weight
            val rItalic = r?.italic ?: italic
            val size = r?.fontPxOr(fontSizePx) ?: fontSizePx
            out.add(
                Seg(
                    rs, re,
                    faceTable(rTag, rFam, rMono, rWeight, rItalic, size, mgrs),
                    letterSpacingEm * size, size,
                    SkParagraphFactory.resolveFamilies(rTag, rFam, rMono),
                    SkParagraphFactory.runFontStyle(rFam, rWeight, rItalic), mgrs, rMono,
                ),
            )
        }
        return out
    }

    // ---- 段内量宽：按面批量探覆盖 + 批量取宽（native 调用与段长无关）----

    private fun measure(text: CharSequence, from: Int, to: Int, seg: Seg, out: FloatArray) {
        val len = to - from
        // 去重：码本 -> 去重槽位。CJK 段里码本几乎全异，拉丁段重复多，去重是最大的一笔省。
        val slotOf = HashMap<Int, Int>(len * 2)
        val cps = IntArray(len)
        var nCp = 0
        for (i in from until to) {
            val at = codePointAt(text, i)
            // **代理对的低位代理尾不是独立字位**：它已被前一个位置合并成**一个码本**量过了。
            // 放它进来查字体必然查不到 ⇒ 落 notdef 宽（约 0.6em）⇒ **行凭空宽出一个字位**，
            // 后续字位全体右移。真机症状：书里 emoji 少 ⇒「只有没几个字符乱码」。
            if (at.isLowSurrogateTail) continue
            val cp = at.cp
            // **软连字符不进面表**（零宽占位符，见 [SOFT_HYPHEN]）：它不占宽度也不绘制，
            // 真去查面只会拿到 STSong 的 glyph 271 并算出一个 1em 的全宽（实测 42.18 @fs=42.18）。
            // 它的「可见性」由断点判定决定：行若断在它之后，那里画一个 [HYPHEN_GLYPH]。
            if (cp == SOFT_HYPHEN_CODE) continue
            if (slotOf.putIfAbsent(cp, nCp) == null) {
                cps[nCp] = cp
                nCp++
            }
        }
        if (nCp == 0) return
        val fonts = seg.fonts
        val width = FloatArray(nCp)
        val pending = IntArray(nCp)
        for (k in 0 until nCp) pending[k] = cps[k]
        var nPending = nCp
        // 逐面把「本面覆盖了的」定宽并从待定集里剔除，`pending[0 until nPending)` 始终是**尚未覆盖**的那批。
        //
        // `fonts` **只含 CSS 族栈的面**：保底面表（[universalTypefaces]，数百面）刻意**不进这个循环**。
        // 每个面要付 2 次 native 调用（`getUTF32Glyphs` + `getWidths`），把数百个保底面混进来
        // 等于给**每一段**都加几百次 native 调用 —— 实测正文段 0.017ms → 整书 5000 段要 85s，
        // 直接把 `:app:testDebugUnitTest` 的 15 秒预算探针顶爆。保底改走下面的 [universalPass]，
        // **只在族栈真的全灭时才付这个代价**（那时不兜底就是豆腐块，两害相权取其轻）。
        for (fi in fonts.indices) {
            if (nPending == 0) break
            val font = fonts[fi]
            val glyphs = font.getUTF32Glyphs(pending)
            val w = font.getWidths(glyphs)
            var keep = 0
            for (p in 0 until nPending) {
                val cp = pending[p]
                if (glyphs[p] != NOTDEF) {
                    width[slotOf.getValue(cp)] = w[p]
                } else {
                    pending[keep++] = cp
                }
            }
            nPending = keep
        }
        // 族栈一个都没覆盖 ⇒ 走保底面表。这是**唯一**会扫数百面的地方（`nPending > 0` 才进来）。
        if (nPending > 0) nPending = universalPass(pending, nPending, seg, slotOf, width)
        if (nPending > 0) {
            // 无候选面覆盖：交给系统 FontMgr 的单族字符匹配（与 FontCollection 的 default 回退同源），
            // 仍不覆盖则取首面的 notdef 宽（段落那边画出来也是 .notdef，宽度口径一致）。
            //
            // `fonts[0]` 的越界守卫：面表为空只可能是「一个已装字体都没有」（保底表也空）。
            // 那时按 0 宽兜底并让 [faceForCp] 同步返 null（绘制侧走空 Font），**两侧口径一致**，
            // 不在这里崩掉整个排版线程 —— 与「绝不崩 activity」的既有态度同款。
            val notdef = fonts.firstOrNull()?.let { notdefWidth(it) } ?: 0f
            for (p in 0 until nPending) {
                val cp = pending[p]
                val slot = slotOf.getValue(cp)
                // 最后一道：**系统回退链**（与落墨侧 [faceForCp] 同一个出口 ⇒ 量画同源）。
                width[slot] = systemFallbackFace(cp, seg.style, seg.stack.firstOrNull(), seg.sizePx)
                    ?.let { it.getWidths(shortArrayOf(it.getUTF32Glyph(cp)))[0] }
                    ?: fallbackWidth(seg, cp) ?: notdef
            }
        }
        val ls = seg.lsPx
        for (i in from until to) {
            val at = codePointAt(text, i)
            // 代理对的低位代理尾：**严格 0 宽**。它的宽度已经计在高位代理那个位置上了。
            if (at.isLowSurrogateTail) {
                out[i] = 0f
                continue
            }
            // 软连字符**严格 0 宽**（连 `lsPx` 也不给）：它是「不占位」的占位符。
            // 给 letterSpacing 会凭空多出一个幽灵间隙 → 行内出现看不见的洞。
            out[i] =
                if (at.cp == SOFT_HYPHEN_CODE) 0f
                else width[slotOf.getValue(at.cp)] + ls
        }
    }

    /**
     * **保底通道**（渲染层·几何测量）：族栈全灭时扫 [universalTypefaces] 给 `pending[0 until n)`
     * 定宽，**返回仍未定宽的个数**。
     *
     * ## 为什么单独一条通道，而不是把保底面并进 [measure] 的主循环
     *
     * 主循环每面要付 2 次 native 调用（`getUTF32Glyphs` + `getWidths`）。保底表有数百个面，
     * 并进去等于给**每一段**都加几百次 native 调用 —— 实测（正文段，首族本已覆盖）
     * 0.017ms/段 → 整书 5000 段 85s，直接把 `:app:testDebugUnitTest` 里两个 15 秒预算的
     * 跨章调度探针顶到超时。**这是本轮实测踩到的坑，不是理论担忧。**
     * 拆开后代价只落在「族栈真的一个都不覆盖」的段上：不兜底就是豆腐块，两害相权取其轻。
     *
     * ## 量画同源
     *
     * 落墨侧的 [faceForCp] 走**同一张** [universalTypefaces]、**同一条**「第一个
     * `getUTF32Glyph(cp) != 0` 即采用」规则，故这里量到的宽就是那里画的面，**不会量画失配**。
     */
    private fun universalPass(
        pending: IntArray,
        nPending: Int,
        seg: Seg,
        slotOf: HashMap<Int, Int>,
        width: FloatArray,
    ): Int {
        var remain = IntArray(nPending) { pending[it] }
        var n = nPending
        for (tf in universalTypefaces(seg.mono, seg.style.weight, seg.style.slant == FontSlant.ITALIC, seg.mgrs)) {
            if (n == 0) break
            val font = Font(tf, seg.sizePx)
            val glyphs = font.getUTF32Glyphs(remain)
            val w = font.getWidths(glyphs)
            var keep = 0
            for (p in 0 until n) {
                val cp = remain[p]
                if (glyphs[p] != NOTDEF) {
                    width[slotOf.getValue(cp)] = w[p]
                } else {
                    remain[keep++] = cp
                }
            }
            n = keep
        }
        for (p in 0 until n) pending[p] = remain[p]
        return n
    }

    private fun notdefWidth(font: Font): Float = font.getWidths(shortArrayOf(NOTDEF))[0]

    /** 系统端单族字符匹配的兜底取宽；null = 连兜底面都没有。 */
    private fun fallbackWidth(seg: Seg, cp: Int): Float? {
        val family = seg.stack.firstOrNull() ?: return null
        for (mgr in seg.mgrs.asReversed()) {
            val tf = runCatching { mgr.matchFamilyStyleCharacter(family, seg.style, null, cp) }.getOrNull() ?: continue
            val font = Font(tf, seg.sizePx)
            val g = font.getUTF32Glyphs(intArrayOf(cp))
            if (g[0] != NOTDEF) return font.getWidths(g)[0]
        }
        return null
    }

    /**
     * **系统回退链**（渲染层·字体解析）：[universalTypefaces] 也解不出时的**最后一道**通道。
     *
     * ## 为什么必须有它（这是「之前用 skia 是好的」那句话的直接兑现）
     *
     * 旧实现走 `SkParagraphFactory.defaultCollection()` = `FontCollection().setDefaultFontManager(systemFonts())`，
     * SkParagraph 内部靠 `FontCollection` 的**系统级回退链**（`defaultFallback(character, style, family)`）
     * 逐码本找面。那条链**不靠族名枚举**，它直接问宿主「哪个字体覆盖这个码本」。
     *
     * 我的 [universalTypefaces] 是**按族名枚举**建表的（平板 63 族），
     * 而**枚举能到的严格窄于系统回退链**。现场证据（vivo 平板，日志实证）：
     *
     * - 设备 `/system/fonts/NotoColorEmoji.ttf` **存在**，`U+1F44D`（👍）有字形；
     * - 但 skiko `FontMgr` 的 63 个族名里**没有任何 emoji 族** ⇒ 枚举表里根本没有这张面
     *   ⇒ 「按名字找」永远找不到它，「设备没字体」的结论是**假的**。
     *
     * 两级通道的关系：**枚举表在前、系统链在后**。前者便宜且可缓存（覆盖绝大多数缺字），
     * 后者是一次 native 调用、只在彻底解不出时才付。顺序反过来会让所有已正常的取面都多走一遍系统链，
     * 属于拿已验证的行为去冒险。
     *
     * ## 量画同源
     *
     * [measure] 与 [faceForCp] 调的是**同一个** [systemFallbackFace]、**同一张**缓存，
     * 且两侧都再验一次 `getUTF32Glyph(cp) != 0` 才采用（`defaultFallback` 本身也允许返 `null`）。
     */
    private fun systemFallbackFace(cp: Int, style: FontStyle, familyHint: String?, sizePx: Float): Font? {
        val key = SystemFallbackKey(cp, style.weight, style.slant.ordinal)
        systemFallbackCache[key]?.let { return it }
        val tf = runCatching {
            systemFallbackCollection().defaultFallback(cp, style, familyHint)
        }.getOrNull()
        val face = tf?.let {
            val f = Font(it, sizePx)
            // 再验一次覆盖：`defaultFallback` 可能给出一张并不含该码本的面（宿主链自身的兜底）。
            if (f.getUTF32Glyph(cp) != NOTDEF) f else null
        }
        synchronized(lock) { systemFallbackCache[key] = face }
        return face
    }

    private data class SystemFallbackKey(val cp: Int, val weight: Int, val slant: Int)

    /**
     * 系统回退用的 [FontCollection]：**整个进程只建一次**。
     *
     * `FontCollection.setDefaultFontManager(systemFonts())` 不是廉价构造 —— 它要把
     * **全部系统字体注册进集合**，是重量级 native 操作。而 [systemFallbackFace] 是
     * **按码本**调用的：书里每个「族名枚举够不到」的码本都要问一次系统链
     * （emoji、生僻字、组合符号都可能落进来）。
     *
     * 每个码本新建一个集合 ⇒ 无界的 native 对象 churn。第一版就是这么写的，
     * `WholeBookRelayoutEpochProbeTest`（15 秒预算的整书重排探针）在全量跑时**超时**。
     * 与 [universalTypefaces] 的缓存键踩的是同一类坑（教训 ⑩：缓存键/缓存粒度要按**代价**算，不是按调用次数）。
     */
    @Volatile
    private var fallbackCollection: org.jetbrains.skia.paragraph.FontCollection? = null

    private fun systemFallbackCollection(): org.jetbrains.skia.paragraph.FontCollection =
        fallbackCollection ?: synchronized(this) {
            fallbackCollection ?: SkParagraphFactory.defaultCollection().also { fallbackCollection = it }
        }

    /** 系统回退链的结果缓存（面与字号无关 ⇒ 键里不含 sizePx）。 */
    private val systemFallbackCache = HashMap<SystemFallbackKey, Font?>()

    // ---- 面表解析与缓存（跨 run / 跨叶复用；值不可变，读写在锁内）----

    private data class Key(val stack: String, val weight: Int, val slant: Int, val sizeBits: Int)

    private val faceCache = HashMap<Key, Array<Font>>()

    /**
     * 通用面表缓存（键 = mono/字重/斜体，**与字号和 CSS 族栈都无关**）——见 [universalTypefaces]。
     *
     * 两个「无关」各有理由：族栈无关 ⇒ 所有族栈共用一张；**字号无关** ⇒ 见 [universalTypefaces]
     * 的踩坑记录（带字号会把跨章调度探针顶到超时）。
     */
    private val universalCache = HashMap<UniversalKey, Array<Typeface>>()
    private val lock = Any()

    private fun faceTable(
        tag: String?,
        families: List<String>,
        monospace: Boolean,
        weight: Int,
        italic: Boolean,
        sizePx: Float,
        mgrs: List<FontMgr>,
    ): Array<Font> {
        val style = SkParagraphFactory.runFontStyle(families, weight, italic)
        val stack = SkParagraphFactory.resolveFamilies(tag, families, monospace)
        val key = Key(stack.joinToString(""), style.weight, style.slant.ordinal, sizePx.toRawBits())
        synchronized(lock) { faceCache[key] }?.let { return it }
        val out = ArrayList<Font>(stack.size * mgrs.size)
        // 外层族栈、内层 manager —— FontCollection 的语义。写反实测偏 49.51px（§T8.3）。
        // 具名族逐 manager 各取一次面；同字体被两个名字命中（`Times` / `Times New Roman`）时按引用去重。
        val seen = HashSet<Typeface>()
        for (family in stack) {
            for (mgr in mgrs) {
                val tf = runCatching { mgr.matchFamilyStyle(family, style) }.getOrNull() ?: continue
                if (!seen.add(tf)) continue
                out.add(Font(tf, sizePx))
            }
        }
        // **保底面刻意不在这里**（见 [universalPass]）：并进来等于给每段加数百次 native 调用
        // （实测正文段 0.017ms → 整书 5000 段 85s，把 15 秒预算的调度探针顶爆）。
        // 族栈表恒只含 CSS 族栈的面；保底由 [measure] 在全灭时另走 [universalPass]。
        val arr = out.toTypedArray()
        synchronized(lock) { faceCache[key] = arr }
        return arr
    }

    /**
     * **通用面表**（渲染层·字体解析）——「CSS 族栈一个都覆盖不到时，最后还能去哪」的单源答案。
     *
     * ## 为什么必须有它（这不是锦上添花，是逐字路径的必需品）
     *
     * SkParagraph 侧有系统级逐字形回退（[SkParagraphFactory.defaultCollection] 把系统 FontMgr
     * 挂成 default manager），**族栈覆盖不到的码本它照样画得出来**。自建断行引擎把落墨换成
     * S5 逐字 `drawString` 之后，取面只剩 [faceForCp] 一条路，而它只认 CSS 族栈
     * （[faceTable] 前半段）—— **保底能力随换实现一起丢了**。实测（Rust 书，族栈
     * `"Fira Code","Hack Nerd Font Mono","Roboto Mono",monospace`）：`U+4E2D 中` / `U+FF21 Ａ`
     * 族栈无人覆盖 ⇒ 落墨拿到 `.notdef` ⇒ 豆腐块；而 `─│├└` 与 `U+00A0` 恰好在等宽面里有字形，
     * 所以「有的显示、有的不显示」，与真机「要么不显示、要么显示乱码」的症状一致。
     *
     * ## 修法为什么是「接在末尾」而不是「另写一条兜底分支」
     *
     * [measure]（量宽）与 [faceForCp]（落墨）都已实现同一条规则：**逐面走，第一个
     * `getUTF32Glyph(cp) != 0` 的即采用**。所以只要把保底面**追加到同一张面表末尾**，两条路径
     * 同时被修好，且**不必在两处各写一份兜底** —— 也就不会发生「量取到保底面、画还在用 notdef」
     * 的量画失配（教训 ⑩）。接末尾还保证**当前能正常显示的码本逐值不变**（前面的族先命中）。
     *
     * ## 定序：通用候选优先，其余已装族兜底
     *
     * 先按 [SkParagraphFactory.genericFallbackFamilyNames] 的候选顺序（mono 时 `monospace` 候选提前，
     * 代码块里的 CJK 落到等宽 CJK 面而非无衬线面），再把系统里**其余**已装族按
     * [FontMgr.getFamilyName] 顺序补齐 —— 后者是「真·保底」：只要**任何**已装字体有该字形就找得到。
     * 各平台字体名与覆盖集不同，故一律用 `getUTF32Glyph(cp) != 0` 自建覆盖判定（同 §2.2(d) 第 3 条）。
     *
     * ## 缓存与代价（**这里踩过一次性能坑，键的取法有讲究**）
     *
     * 昂贵的那一步是 `matchFamilyStyle`（系统字体匹配，326 族 ≈ 毫秒级 native 调用），
     * 而它**与字号无关** ⇒ 缓存**只按 (mono, 字重, 斜体)** 分桶，存的是 `Typeface`（不是 `Font`）。
     * 拼进 [faceTable] 时才按当次字号 `Font(tf, sizePx)` 包一层，那只是 SkFont 分配，可忽略。
     *
     * **踩坑记录（务必别改回去）**：第一版把 `sizePx.toRawBits()` 也放进了缓存键，
     * 于是**每遇到一个新的 font-size 就重扫一遍全部 326 个已装族**。真书里 `pre code{font-size:0.8em}`、
     * 行内 `font-size`、上下标各自成尺寸 ⇒ 一本书几十个尺寸桶 ⇒ 几十次全量扫描，
     * 直接把 `:app:testDebugUnitTest` 里两个 15 秒预算的跨章调度探针
     * （`CrossChapterPreflightProbeTest` / `WholeBookCanonicalQueueProbeTest`）**顶到超时**。
     * 教训：**给缓存键做减法**——键里每一个分量都得回答「它变了，昂贵的那一步真的需要重做吗」。
     *
     * 与 CSS 族栈无关 ⇒ 各条规则共用一张，不重复付扫描代价。
     * 遍历侧 [measure] 在 `nPending == 0` 时提前退出，故**只有真正落到保底的码本**才会多走几次批量
     * native 调用，正常文本（首族即覆盖）零成本。
     *
     * ⚠ **未销的缺陷（教训 ⑰）**：`universalCache` 是**本类的实例字段**，
     * 而 `SkiaRunMeasurer()` 在生产路径上是 `GlyphPainter` / `KerningClusterTable` /
     * `InhouseParagraphBreaker` / `LineAligner` 四处的**默认实参** ⇒ 每个新实例都要重扫一遍
     * 全部已装族（平板 59 族、每族一次 `matchFamilyStyle` native），而扫出来的结果对所有实例**完全相同**。
     * 平板实测一次运行 39 秒内至少新建 **214 个实例**（按已删诊断 `logUniversalTableOnce` 那 641 行折算）。
     * **正确的作用域是进程级**（与 `systemFallbackCollection` 同一个模式），
     * 未改是因为还没量化收益/边界，先记账。
     */
    private fun universalTypefaces(
        monospace: Boolean,
        weight: Int,
        italic: Boolean,
        mgrs: List<FontMgr>,
    ): Array<Typeface> {
        val style = SkParagraphFactory.runFontStyle(emptyList(), weight, italic)
        val key = UniversalKey(monospace, style.weight, style.slant.ordinal)
        synchronized(lock) { universalCache[key] }?.let { return it }
        val seen = HashSet<Typeface>()
        val out = ArrayList<Typeface>()
        fun add(mgr: FontMgr, family: String) {
            if (family in DENIED_FAMILIES) return
            val tf = runCatching { mgr.matchFamilyStyle(family, style) }.getOrNull() ?: return
            if (seen.add(tf)) out.add(tf)
        }
        // ① 通用候选定序（书里常见的 CJK / 等宽 CJK / 符号面先试）。
        for (family in SkParagraphFactory.genericFallbackFamilyNames(monospace)) {
            for (mgr in mgrs) add(mgr, family)
        }
        // ② 真·保底：系统里其余已装族。谁有字形谁上，哪怕名字完全陌生。
        for (mgr in mgrs) {
            val n = runCatching { mgr.familiesCount }.getOrDefault(0)
            for (i in 0 until n) {
                val family = runCatching { mgr.getFamilyName(i) }.getOrNull() ?: continue
                add(mgr, family)
            }
        }
        val arr = out.toTypedArray()
        synchronized(lock) { universalCache[key] = arr }
        return arr
    }

    /**
     * 诊断（渲染层·字体解析）：记下**连保底表都解不出**的码本。
     *
     * 这是「还是乱码」时唯一能给出确定答案的信号：解不出的码本**在这台设备上真的没有字体**
     * （vivo 平板 63 族，缺 emoji / 泰文 / 部分符号区），**不是兜底逻辑没生效**。
     * 两者的修法完全不同：前者要装字体，后者要改代码。分不清就会一直改错地方。
     *
     * 同一个码本只记一次，条目有上限（否则生僻字多的书能把日志刷爆）。
     */
    private fun logUnresolvableCp(cp: Int, seg: Seg) {
        synchronized(lock) {
            if (unresolvable.size >= UNRESOLVABLE_LOG_CAP) return
            if (!unresolvable.add(cp)) return
        }
        val c = if (cp in 0..0xFFFF) cp.toChar() else '?'
        val shown = if (c.code < 0x20 || c.code == 0x7F) "(不可见控制符)" else c.toString()
        Logger.w(
            "Orilumn.FACE",
            "无字体的码本 U+%04X %s（stack=%s size=%.1f）——这台设备没装能画它的字体".format(
                cp, shown, seg.stack.joinToString(","), seg.sizePx,
            ),
        )
    }

    private val unresolvable = HashSet<Int>()

    /** 无字体码本的记录上限（防止生僻字多的书把日志刷爆）。 */
    private val UNRESOLVABLE_LOG_CAP = 40

    /** 保底表缓存键：**刻意不含字号** —— 见 [universalTypefaces] 的踩坑记录。 */
    private data class UniversalKey(val monospace: Boolean, val weight: Int, val slant: Int)
}

private const val NOTDEF: Short = 0

/**
 * 一个字形相对**自身笔位原点**的墨迹区间（px）—— [SkiaRunMeasurer.glyphInkBoxPx] 的返回值。
 *
 * 两端都允许为负（负边距字形），因此它是区间而非宽度。
 * @see PunctuationSqueeze 收窄字位时的两条墨迹判据分别用 [left] 与 [right]
 */
class GlyphInkBox(val left: Float, val right: Float)

/**
 * **保底面表的排除族**（渲染层·字体解析）。
 *
 * `Last Resort` 是 Skia/Google 的**诊断用**占位字体：它对任何码本都「有字形」，但画出来是一块
 * 带小字说明的豆腐块 —— 正是我们要消灭的那种「乱码」。留着它当保底等于把缺陷画得更显眼。
 * 其余字体一律不排挤：保底表的判据是「有真字形」，不是「好不好看」—— 好看由 §定序（通用候选优先）负责。
 */
private val DENIED_FAMILIES = setOf("Last Resort")

/** [SOFT_HYPHEN] 的码本（取宽路径的热路径判定，用 Int 常量免去 Char→Int 装箱）。 */
private const val SOFT_HYPHEN_CODE = 0x00AD

/** 码本：代理对取整个代理对的值（`text[i]` 为高位时读低位），否则原样。 */
