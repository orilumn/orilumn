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

        val first = layoutOnce(
            text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic,
            monospace, firstLineIndentPx, fontRuns, baselineShifts,
        )
        // R1 补偿（SkParagraph `TextIndent` 的「整段单行」快捷路径漏算缩进）。
        //
        // 实测（探针 + 真书全链路扫版心，见 [orilumn.reader.engine.skia.FirstLineIndentSingleLineOverflowTest]）：
        // `TextIndent` 只在首行**真的折行**时扣减首行可用宽；「整段放得下一行」的快捷路径拿整段
        // 自然宽 `nat` 直接比 layout 宽 `widthPx`，**不扣 indent**。真机字体栈（`STSong, serif`
        // @44.4px，26 字段 `nat=1287.60`，`indent=88.80`）下「整段单行」区从 `ceil(nat)=1288`
        // 起，**对 indent 三个取值（0 / 44.4 / 88.8）完全相同**——缩进一点没参与。
        // 于是窗口 `widthPx - indent < nat ≤ widthPx` 内断行侧判「放得下」不换行，绘制侧
        // （[LineWindowDrawer]：`paintX = textX + firstLineIndentPx`）仍把整行右移 indent，
        // 右缘越出版心至多 indent（默认 `text-indent: 2em` = 2 字）→ 同行尾部被版心右缘裁掉
        // （用户可见的「一段只有一行、版心只差一两个字却不换行、尾部被截断」）。真机实测：
        // 版心 1288、右缘 1376.43，**溢出 88.4px = 2 个字**。只在一行时发作：一旦折行，
        // Skia 自己就把首行按 `widthPx - indent` 排对了。
        //
        // 修法：命中该窗口时按首行真实可用宽整段重排，`firstLineIndentPx` 传 0 避免二次扣减。
        // 修后单行区从 `ceil(nat + indent)` 起——正是 CSS 语义。
        // 为何整段都用这个窄宽仍与 CSS 等价：命中窗口时整段自然宽 ≤ widthPx，贪心首行把
        // `widthPx - indent` 填到「差一个字就溢出」，剩余尾巴宽 ≤ indent + 一字，远窄于
        // `widthPx`，故第 2 行起在两种宽下的断点必然相同（不存在「尾行比 CSS 早断」）。
        // 窄宽向下取整（`toInt`），只会让首行更保守，永不溢出。
        // 唯一放不下的例外是「整段一个断点都没有」的不可断长串（如超长 URL）：重排后仍是
        // 一行，与修复前逐值相同——CSS 对不可断长串本就是溢出可见语义，不是本条要治的病。
        //
        // 字体相关（回归必须钉真机字体栈，无族回退字体下该失配不复现，见测试类注释）：
        // 判据用 `maxIntrinsicWidth`（与绘制侧同整形器量出的自然宽），不用 `lineMetrics.width`
        // （后者会被对齐拉伸/去尾空白污染）。
        if (firstLineIndentPx > 0f &&
            first.lines.size == 1 &&
            first.unwrappedWidthPx + firstLineIndentPx > widthPx
        ) {
            return layoutOnce(
                text, fontSizePx, lineHeightRatio,
                (widthPx - firstLineIndentPx).toInt().coerceAtLeast(1),
                alignment, tag, families, weight, italic, monospace,
                0f, fontRuns, baselineShifts,
            ).lines
        }
        return first.lines
    }

    /** [breakLines] 单次整形的产物：断行区间 + 整段「不折行」自然宽（`maxIntrinsicWidth`，R1 补偿的判据）。 */
    private class Once(val lines: List<BrokenLine>, val unwrappedWidthPx: Float)

    /**
     * 单次整形：按 [widthPx] 断行并归一化区间（[breakLines] 的实际实现，含行内 face 段与基线位移段）。
     * 抽出来是为了让 R1 补偿能以「同配置、换版心宽」再排一次，两次配置严格同源。
     */
    private fun layoutOnce(
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
    ): Once {
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
            return Once(out, paragraph.maxIntrinsicWidth)
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