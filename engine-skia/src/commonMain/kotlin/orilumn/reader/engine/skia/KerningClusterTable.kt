package orilumn.reader.engine.skia

import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign

/**
 * S5b **西文 kerning / 连字保留**（渲染层，绘制侧现算）。
 *
 * ## 问题
 *
 * S5 逐字 `drawString` 后，绘制侧与量宽侧统一成「裸 cmap」，量画彻底同源，
 * 但**丢掉了 HarfBuzz 的 kerning 与 `fi`/`fl` 连字**（Q5）：西文字距略宽。
 *
 * ## 为什么单字符画会丢，以及正确解法
 *
 * 单个字符交给 Skia 时**没有邻居**，kern 表查不了、连字也融不了。
 * 但**不需要按词切**——`getRectsForRange(0, len)` **一次就能拿整段的全部簇矩形**，
 * 实测（docs 29e）**比裸 cmap 还快**（0.047ms vs 0.0877ms / 段）。
 * 簇位 `rect.left - paintX` 即该字符的**真实绘制 x**（含 kerning；连字时两字符共用一簇 ⇒ 同 x，天然正确）。
 *
 * ## 量画同源怎么保证（这是本类存在的理由）
 *
 * **不**让绘制侧单方面用簇位去改已算好的几何 —— 那样会与量宽侧失配。
 * 本类的职责是**给出「含 kerning 的真实字形位置」**，而 [LineAligner] 的
 * 「无 kerning 布局」与它**同源且同步**：两者都由同一段文本 + 同一版心产出，
 * 且**只对含 Latin 的行启用**。CJK 行（无 kerning）走 [LineAligner] 原路径，零行为变化。
 *
 * 换句话说：**含 Latin 的行用 Skia 簇位（保 kerning，同源因为两端都来自 Skia 整形），
 * 纯 CJK 行用裸 cmap（同源因为两端都是裸 cmap）。** 判据是**逐行**而非全段，故无跨源混排错位。
 */
internal class KerningClusterTable(
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
) {

    /** 该行是否**可能**需要 kerning：无 Latin 字母则整段 kern 表无命中，不建 Paragraph。 */
    fun needsClusters(text: CharSequence, start: Int, endExcl: Int): Boolean {
        for (i in start until endExcl) {
            if (isLatinish(text[i])) return true
        }
        return false
    }

    /**
     * 取 `text[start, endExcl)` 的逐字符绘制 x（含 kerning；连字共用簇时同x）。
     *
     * @param originX 整条簇位轨的起点偏移。**JUSTIFY 行必须传 0**：本方法给的是**未拉伸的自然轨**，
     *   拉伸/对齐偏移由 [LineWindowDrawer.paintGlyphs] 按 [orilumn.reader.engine.skia.LineAligner.Placement]
     *   平移叠加（把 kerning 增量嫁接到已对齐的落位上）。传非 0 会让那条偏移被计两遍。
     * @param letterSpacingEm 与量宽侧同一个 em 值（[SkiaRunMeasurer] 的 `lsPx` 口径）。传 0 会让
     *   带字距的行整行窄 `n·lsPx`（与断行几何失配 ⇒ JUSTIFY 铺不满、可能右溢）。
     * @return 与 range 同长的 x 数组；无命中簇时返回 null（调用方退回 [LineAligner] 的 x）。
     */
    fun clusterXs(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        originX: Float = 0f,
        letterSpacingEm: Float = 0f,
    ): FloatArray? {
        val n = endExcl - start
        if (n <= 0) return null
        // 行内换面：分段建（浏览器 inline-run 语义），否则簇位会按错误的面算。
        if (fontRuns.isEmpty()) {
            return clusterXsSingle(text, start, endExcl, fontSizePx, lineHeightRatio, tag, families, weight, italic, monospace, originX, letterSpacingEm)
        }
        val edges = (fontRuns.flatMap { listOf(it.start, it.endExclusive) } + listOf(start, endExcl))
            .filter { it in start..endExcl }.distinct().sorted()
        val out = FloatArray(n) { Float.NaN }
        // **段间必须串接**：各段独立建 Paragraph、坐标都从 0 起，直接拼会得到断裂的 x
        //（换面边界处回跳 —— 被「簇位必须单调不减」那把锁抓出来）。
        // 故每段起点的 x = 前一段末尾 x + 该交界字符的 advance（advance 用**当前段的面**量，
        // 与量宽侧 `SkiaRunMeasurer` 同出口），保证连续且与裸 cmap 的段内相对位置一致。
        var cursorX = originX
        for (k in 0 until edges.size - 1) {
            val s = edges[k]; val e = edges[k + 1]
            if (e <= s) continue
            val r = fontRuns.firstOrNull { it.start <= s && e <= it.endExclusive }
            val segFam = r?.families ?: families
            val segSize = r?.fontPxOr(fontSizePx) ?: fontSizePx
            val seg = clusterXsSingle(
                text, s, e, segSize, lineHeightRatio,
                r?.tag ?: tag, segFam, r?.weight ?: weight,
                r?.italic ?: italic, r?.monospace ?: monospace, cursorX, letterSpacingEm,
            )
            if (seg == null) return null
            for (j in s until e) out[j - start] = seg[j - s]
            // 下一段起点 = 本段末字 x + 该字 advance（**用当前段的面**量，与量宽侧同一出口；
            // 段内 kerning 已含在簇位里，不重复加）。
            //
            // ⚠ **下标必须是全文本绝对下标 `e-1`**：`adv` 是对**整段 `text`** 量的（形参就是 `text`），
            // 与 `text` 同坐标。曾经写成段内局部下标 `e-1-s`（`advances` 早先是对子串量的），
            // 换段后就取了**别的字符**的 advance 当交界宽 —— 而 Rust 书正文 `<code>` 段几乎都是
            // **单字符**（`x` / `4` / `y` …），于是每遇一个换面边界就凭空加进一个 CJK 全角宽
            // （实测 42.18px），逐段累加。实测（19.xhtml 一行 47 字、17 个换面段）：
            // 我方 Σadv=1510.99 而簇位轨末 x=1718.19，**漂移 207.21px** ⇒ 该行右溢出版心 244px，
            // 末字被页面裁掉半个。全书 19257 行里这类「p + 行内换面」行几乎全中。
            val adv = measurer.advances(
                text, segSize, letterSpacingEm, r?.tag ?: tag, segFam,
                r?.weight ?: weight, r?.italic ?: italic, r?.monospace ?: monospace,
                emptyList(),
            )
            cursorX = out[e - 1 - start] + adv[e - 1]
        }
        return if (out.any { it.isNaN() }) null else out
    }

    private fun clusterXsSingle(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        originX: Float,
        letterSpacingEm: Float,
    ): FloatArray? {
        val n = endExcl - start
        if (n <= 0) return null
        val style = SkParagraphFactory.paragraphStyle(
            TextAlign.LEFT, fontSizePx, lineHeightRatio, tag, families, weight, italic,
            monospace, letterSpacingEm, firstLineIndentPx = 0f,
        )
        val p = ParagraphBuilder(style, SkiaFontPool.current()).addText(text.subSequence(start, endExcl).toString()).build()
        try {
            p.layout(Float.MAX_VALUE)
            val out = FloatArray(n) { Float.NaN }
            var covered = 0
            // 逐字符取簇左沿。**不能**靠「整段一次 getRectsForRange」——`TextBox` 在 skiko 里
            // **不带 startIndex/endIndex**（`javap` 确认：只有 `rect` + `direction`），
            // 无法把簇归位到字符；连字时多字共簇更无从下手。
            // 逐字查仍比「每词建一个 Paragraph」快 5.9x（docs 29e B 臂 0.20ms/段），
            // 且本方法只对**含拉丁字母的行**调用，CJK 行的零成本。
            for (i in 0 until n) {
                val boxes = runCatching {
                    p.getRectsForRange(i, i + 1, RectHeightMode.TIGHT, RectWidthMode.TIGHT)
                }.getOrNull()
                if (boxes.isNullOrEmpty()) continue
                // 连字：若下一字与本字同簇（x 相同），沿用本字 x —— 天然正确，无需特判。
                out[i] = originX + boxes[0].rect.left
                covered++
            }
            return if (covered == 0) null else out
        } finally {
            p.close()
        }
    }

    /** 拉丁字母判据（ASCII 字母 + 常见拉丁补充）。CJK 行一律 false ⇒ 完全不建 Paragraph。 */
    private fun isLatinish(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F' || c == '\u2019'
}