package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
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
    ): FloatArray {
        val n = text.length
        val out = FloatArray(n)
        if (n == 0) return out
        val mgrs = managers()
        val segs = segments(text, n, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns, mgrs)
        for (s in segs) measure(text, s.from, s.to, s, out)
        return out
    }

    /** 整段自然宽（px，max-content / [orilumn.reader.engine.laying.ParagraphBreaker.preferredWidth]）。 */
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
    ): Float {
        val adv = advances(text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns)
        var sum = 0f
        for (a in adv) sum += a
        return sum
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
                    SkParagraphFactory.runFontStyle(families, weight, italic), mgrs,
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
                    SkParagraphFactory.runFontStyle(rFam, rWeight, rItalic), mgrs,
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
            val cp = cpAt(text, i)
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
        if (nPending > 0) {
            // 无候选面覆盖：交给系统 FontMgr 的单族字符匹配（与 FontCollection 的 default 回退同源），
            // 仍不覆盖则取首面的 notdef 宽（段落那边画出来也是 .notdef，宽度口径一致）。
            val notdef = notdefWidth(fonts[0])
            for (p in 0 until nPending) {
                val cp = pending[p]
                val slot = slotOf.getValue(cp)
                width[slot] = fallbackWidth(seg, cp) ?: notdef
            }
        }
        val ls = seg.lsPx
        for (i in from until to) out[i] = width[slotOf.getValue(cpAt(text, i))] + ls
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

    // ---- 面表解析与缓存（跨 run / 跨叶复用；值不可变，读写在锁内）----

    private data class Key(val stack: String, val weight: Int, val slant: Int, val sizeBits: Int)

    private val faceCache = HashMap<Key, Array<Font>>()
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
        val arr = out.toTypedArray()
        synchronized(lock) { faceCache[key] = arr }
        return arr
    }
}

private const val NOTDEF: Short = 0

/** 码本：代理对取整个代理对的值（`text[i]` 为高位时读低位），否则原样。 */
private fun cpAt(text: CharSequence, i: Int): Int {
    val c = text[i]
    if (c.code in 0xD800..0xDBFF && i + 1 < text.length && text[i + 1].code in 0xDC00..0xDFFF) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
    }
    return c.code
}
