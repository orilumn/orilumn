package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.laying.lineHeightPx
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder

/**
 * [ParagraphBreaker] 的 SkParagraph 实现（G 阶段 S24；Q1-c 后 App 侧唯一生产断行器）。
 *
 * 只向盒子流输出「char 范围 + 行高」（App 旧管线 `StaticLayoutBreaker` 已随 Q1 退役）。差异点：
 *  - 断行/度量用 SkParagraph（原生支持 `kJustify` 中文两端对齐、多语言混排）；
 *  - 行高报告统一行框 `lineHeightPx(fontSizePx, lineHeightRatio)`（与旧管线公式完全一致——单一来源），
 *    且断行与后续绘制（S25）共享 [SkParagraphFactory] 的同一配置，几何不回漂移。
 *
 * 约束（方案铁律）：SkParagraph 仅做单行整形 + 断行度量，行字段按 reported 高度由盒子流以绝对 Y 落位，
 * 不依赖 SkParagraph 内部行高的累加（浮点差不会累积）。
 */
class SkiaParagraphBreaker(
    private val letterSpacingEm: Float,
    private val collections: () -> FontCollection = SkiaFontPool::current,
) : ParagraphBreaker {

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
    ): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, 0f)

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        firstLineIndentPx: Float,
    ): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx, emptyList())

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        firstLineIndentPx: Float,
        fontRuns: List<FontRun>,
        baselineShifts: List<orilumn.reader.engine.laying.BaselineShift>,
    ): List<BrokenLine> {
        if (widthPx <= 0) {
            // 退化路径：与 StaticLayoutBreaker 同口径——每个可用单元格一行（非法/零宽护栏）。
            return if (text.isEmpty()) emptyList()
            else listOf(BrokenLine(0 until text.length, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
        if (text.isEmpty()) return emptyList()

        // 整条 CSS font-family 栈 (作者顺序) 交给 FontCollection 按字形回退 — 与浏览器一致, 且与
        // Android FontPairing.SYSTEM 的整栈语义对齐。只取首名会让首族未装的书籍 (如"思源宋体 VF")
        // 在渲染时退化成默认无衬线, 偏离原书/浏览器结果。
        val style = SkParagraphFactory.paragraphStyle(
            alignment, fontSizePx, lineHeightRatio, tag, families, weight, italic, monospace, letterSpacingEm,
            firstLineIndentPx = firstLineIndentPx,
            // P1-2: 有基线位移的行固定行盒基线（与绘制侧同开，基线不浮动）。
            forceStrut = baselineShifts.isNotEmpty(),
        )
        val builder = ParagraphBuilder(style, collections())
        if (fontRuns.isEmpty() && baselineShifts.isEmpty()) {
            builder.addText(text.toString())
        } else {
            // 行内 face 段＋基线位移段（浏览器 inline-run 语义）：两套区间合并切分，
            // 每段按自己的 face/位移整形；其余按基底。与绘制侧 [LineWindowDrawer] 相同源，
            // 度量即画得。位移不改变宽度（探针锁定），断行区间与无位移一致。
            val edges = (fontRuns.flatMap { listOf(it.start, it.endExclusive) } +
                baselineShifts.flatMap { listOf(it.start, it.endExclusive) } +
                listOf(0, text.length))
                .filter { it in 0..text.length }
                .distinct().sorted()
            for (k in 0 until edges.size - 1) {
                val rs = edges[k]
                val re = edges[k + 1]
                if (re <= rs) continue
                val r = fontRuns.firstOrNull { it.start <= rs && re <= it.endExclusive }
                val shift = baselineShifts.firstOrNull { it.start <= rs && re <= it.endExclusive }?.shiftEm ?: 0f
                if (r == null && shift == 0f) {
                    builder.addText(text.subSequence(rs, re).toString())
                } else {
                    builder.pushStyle(
                        // 行内字号（r.fontSizePx；0 = 未设，回基底）：与浏览器一致，段按自己的字号整形。
                        // 位移 em 相对叶基底字号（fontSizePx），Skia 正值下移故取反。
                        SkParagraphFactory.runTextStyle(alignment, r?.fontPxOr(fontSizePx) ?: fontSizePx, lineHeightRatio, r?.tag ?: tag, r?.families ?: families, r?.weight ?: weight, r?.italic ?: italic, r?.monospace ?: monospace, letterSpacingEm, baselineShiftPx = -shift * fontSizePx),
                    )
                    builder.addText(text.subSequence(rs, re).toString())
                    builder.popStyle()
                }
            }
        }
        val paragraph = builder.build()
        try {
            paragraph.layout(widthPx.toFloat())
            val metrics = paragraph.lineMetrics
            val targetLh = lineHeightPx(fontSizePx, lineHeightRatio)
            val out = ArrayList<BrokenLine>(metrics.size)
            for (m in metrics) {
                var s = m.startIndex
                var e = m.endIndex
                // Skia LineMetrics 对硬换行的归属不统一：行首可能「预领」'\n'、行尾也可能「多算」'\n'，
                // 且文本以 '\n' 结尾时会生成一条仅含换行符的幻影行。统一归一化为「不含换行符的可绘制区间」：
                // 与 StaticLayoutBreaker 的 `e > s`（并排除 '\n'）口径一致，行区间不重叠、可逐一单行整形绘制。
                if (s < e && text[s] == '\n') s += 1
                if (s < e && text[e - 1] == '\n') e -= 1
                if (e > s) out.add(BrokenLine(s until e, targetLh))
            }
            if (out.isEmpty()) out.add(BrokenLine(0 until text.length, targetLh))
            return out
        } finally {
            paragraph.close()
        }
    }

    /**
     * P6-a: shrink-to-fit 真测（SkParagraph 在极大宽度下整形，取最长行宽）。
     * 与 [breakLines] 同一 factory 配置，测得即排得。
     */
    override fun preferredWidth(
        text: CharSequence,
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
    ): Float {
        if (text.isEmpty()) return 0f
        val style = SkParagraphFactory.paragraphStyle(
            TextAlign.LEFT, fontSizePx, 1f, null, families, weight, italic, monospace, letterSpacingEm,
        )
        val builder = ParagraphBuilder(style, collections())
        if (fontRuns.isEmpty()) {
            builder.addText(text.toString())
        } else {
            val edges = (fontRuns.flatMap { listOf(it.start, it.endExclusive) } + listOf(0, text.length))
                .filter { it in 0..text.length }.distinct().sorted()
            for (k in 0 until edges.size - 1) {
                val rs = edges[k]
                val re = edges[k + 1]
                if (re <= rs) continue
                val r = fontRuns.firstOrNull { it.start <= rs && re <= it.endExclusive }
                if (r == null) {
                    builder.addText(text.subSequence(rs, re).toString())
                } else {
                    builder.pushStyle(
                        SkParagraphFactory.runTextStyle(
                            TextAlign.LEFT, r.fontPxOr(fontSizePx), 1f, r.tag, r.families, r.weight, r.italic, r.monospace, letterSpacingEm,
                        ),
                    )
                    builder.addText(text.subSequence(rs, re).toString())
                    builder.popStyle()
                }
            }
        }
        val paragraph = builder.build()
        try {
            paragraph.layout(1e6f)
            var w = 0f
            for (m in paragraph.lineMetrics) w = maxOf(w, m.width.toFloat())
            return w
        } finally {
            paragraph.close()
        }
    }
}