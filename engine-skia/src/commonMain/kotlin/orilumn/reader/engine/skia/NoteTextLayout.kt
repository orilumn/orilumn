package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.laying.lineHeightPx

/**
 * 旁注（页上批注气泡）短文本的排版参数（**排版层·上** 的接缝）。
 *
 * 为什么要单独一个参数对象：布点（后台车道量「整句多宽、能读几个字」）与落墨（覆盖层
 * 绘制线程）必须用**同一套**字形与字距参数，否则就是本仓最贵的那类 bug（量画失配）。
 * 参数只在设置变化时重建一次，同一个实例两处共用。
 *
 * 所有字段都是**字族名 / 数值**，不持有平台对象：与正文槽位同口径，排版层不认平台字体。
 *
 * @param families CSS 字族栈（作者顺序；通用关键字原样保留，由 [SkParagraphFactory.resolveFamilies] 展开）
 * @param weight 字重（100–900）
 * @param italic 斜体（批注恒 false，留给将来）
 * @param monospace 等宽（批注恒 false，留给将来）
 * @param letterSpacingEm 字间距（em，读者设置；0 = 不加）
 * @param cjkLatinSpacingEm 中西混排间距（em，读者设置；见 [InhouseParagraphBreaker] 的 0 档语义）
 */
data class NoteTextSpec(
    val families: List<String>,
    val weight: Int = 400,
    val italic: Boolean = false,
    val monospace: Boolean = false,
    val letterSpacingEm: Float = 0f,
    val cjkLatinSpacingEm: Float = 0f,
) {
    /**
     * 断行器（按实例携带字距）——**懒建**：没有旁注的页一次都不付这个代价。
     *
     * 实例本身无状态（面表缓存是进程级的，见 `SkiaRunMeasurer` 类 KDoc），
     * 因此布点线程与绘制线程共用一个实例是安全的。
     */
    private val breaker: ParagraphBreaker by lazy {
        bodyParagraphBreaker(letterSpacingEm, cjkLatinSpacingEm)
    }

    internal fun breaker(): ParagraphBreaker = breaker
}

/**
 * 一次旁注排版的结果（**只出文本行，不出几何**）。
 *
 * [lines] 的区间一律以 [text] 为坐标系：截断时 [text] 是「原文前缀 + 省略号」，
 * 于是绘制侧判定末行的条件（`range.last` 是否到文本末尾）无需任何特判。
 *
 * @property text 实际要画的文本（未截断时 == 原文；截断时末尾是 [ELLIPSIS]）
 * @property lines 逐行字符区间与行高（不含 Y 坐标，由调用方按行序堆叠）
 * @property naturalLineCount **不限行数**时整句要几行（布点要它判「整句放得下吗」）
 * @property shownChars 实际能读到的字数（不含换行符与省略号）
 * @property totalChars 原文总字数（不含换行符）
 */
class NoteTextBreak(
    val text: String,
    val lines: List<BrokenLine>,
    val naturalLineCount: Int,
    val shownChars: Int,
    val totalChars: Int,
) {
    val complete: Boolean get() = shownChars >= totalChars
}

/**
 * 旁注文本排版（**排版层·上**：自建断行引擎，与正文同一个量宽出口）。
 *
 * 批注气泡过去走 Compose 文本栈：断行、对齐、字距、混排间距全是另一套，读者调字间距时
 * 正文在变、批注不动。这里把批注接到正文同一个引擎上——[ParagraphBreaker.breakLines]
 * 出区间、[LineAligner] 出 x 位、[LineWindowDrawer] 落墨，三处同源。
 *
 * 与正文的唯一差别是**行数上限**：批注框只有几行，超出要截断并补省略号。引擎本身不提供
 * `maxLines + ellipsis`，这里用同一个量宽口（[ParagraphBreaker.preferredWidth]）逐字二分，
 * 保证「量出来放得下」就是「画出来放得下」。
 *
 * 线程：纯计算 + 进程级缓存，可在布点车道（后台）与绘制线程各自调用。
 */
object NoteTextLayout {

    /** 截断标记（单字符 U+2026；与 Compose `TextOverflow.Ellipsis` 同形）。 */
    const val ELLIPSIS: String = "…"

    /**
     * 整句一行的量宽余量（px）。
     *
     * 布点拿它判「整句放得下」、框宽按它落地；绘制侧的行宽是**取整像素**，
     * 不留这 1px 就会「布点说放得下、画出来多折一行被省略号截掉」。
     */
    const val NATURAL_SLACK_PX: Float = 1f

    /**
     * 整句排成一行需要多宽（px，不含内缩）。多段文本按**最长的一段**算（`\n` 是硬断行，
     * 不是更宽），并留 [NATURAL_SLACK_PX]。
     */
    fun naturalWidth(spec: NoteTextSpec, text: String, fontPx: Float): Float {
        if (text.isEmpty() || fontPx <= 0f) return 0f
        val breaker = spec.breaker()
        var widest = 0f
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) continue
            val w = breaker.preferredWidth(
                paragraph, fontPx, spec.families, spec.weight, spec.italic, spec.monospace,
            )
            if (w > widest) widest = w
        }
        return if (widest <= 0f) 0f else widest + NATURAL_SLACK_PX
    }

    /**
     * 断行 + 按 [maxLines] 截断（不出 Y 坐标、不建绘制指令——布点会问几千次）。
     *
     * @param widthPx 行宽（**整数像素**，调用方负责与绘制侧同一个取整口径）
     * @param maxLines 行数上限；[Int.MAX_VALUE] = 不限
     */
    fun breakText(
        spec: NoteTextSpec,
        text: String,
        fontPx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        maxLines: Int,
    ): NoteTextBreak {
        val totalChars = text.count { it != '\n' }
        if (totalChars == 0 || fontPx <= 0f || maxLines < 1) {
            return NoteTextBreak(text, emptyList(), 0, 0, totalChars)
        }
        val width = widthPx.coerceAtLeast(1)
        val breaker = spec.breaker()
        val natural = breaker.breakLines(
            text, fontPx, lineHeightRatio, width, TextAlign.JUSTIFY, null,
            spec.families, spec.weight, spec.italic, spec.monospace, 0f,
        )
        if (natural.size <= maxLines) {
            return NoteTextBreak(text, natural, natural.size, totalChars, totalChars)
        }
        // 截断：保留前 maxLines-1 行原样，末行在**同一量宽口**上二分出「还能放几个字 + …」。
        val kept = ArrayList<BrokenLine>(maxLines)
        for (i in 0 until maxLines - 1) kept.add(natural[i])
        val last = natural[maxLines - 1]
        val lineStart = last.range.first.coerceIn(0, text.length)
        var end = (last.range.last + 1).coerceIn(lineStart, text.length)
        // 行尾悬挂空白先去掉：省略号紧贴最后一个可见字，不留一道空白。
        while (end > lineStart && isTrailingSpace(text[end - 1])) end--

        fun fits(cut: Int): Boolean = breaker.preferredWidth(
            text.substring(lineStart, cut) + ELLIPSIS,
            fontPx, spec.families, spec.weight, spec.italic, spec.monospace,
        ) <= width

        var lo = lineStart
        var hi = end
        if (fits(lo)) {
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (fits(mid)) lo = mid else hi = mid - 1
            }
        }
        var cut = lo
        // 不把代理对劈成两半（半个 emoji 画出来是两个豆腐块，读起来也不完整）。
        if (cut in 1 until text.length && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) {
            cut--
        }
        val rendered = text.substring(0, cut) + ELLIPSIS
        // 渲染文本 = 原文前缀 + 省略号 ⇒ 省略号的坐标就是 cut（也是渲染文本的末字）。
        kept.add(BrokenLine(cut..cut, last.heightPx))
        val shown = totalChars - text.substring(cut).count { it != '\n' }
        return NoteTextBreak(rendered, kept, natural.size, shown, totalChars)
    }

    /**
     * 排版 + 生成绘制指令（**渲染层**：Y 从 0 起、按行序堆叠，行宽 = [widthPx]）。
     *
     * 与 [breakText] 完全同源（同一断行器 / 同一取整宽 / 同一次截断），只是多出
     * [DrawLine]：`LineWindowDrawer` 只认它，且它的 `text`/`range` 必须和量宽时逐字一致。
     *
     * @param inkArgb 墨色（ARGB Int）
     */
    fun drawLines(
        spec: NoteTextSpec,
        text: String,
        fontPx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        maxLines: Int,
        inkArgb: Int,
    ): NoteTextDraw {
        val broken = breakText(spec, text, fontPx, lineHeightRatio, widthPx, maxLines)
        val lineH = lineHeightPx(fontPx, lineHeightRatio)
        val width = widthPx.coerceAtLeast(1)
        val out = ArrayList<DrawLine>(broken.lines.size)
        var y = 0
        for (line in broken.lines) {
            out.add(
                DrawLine(
                    text = broken.text,
                    range = line.range,
                    yTop = y,
                    yBottom = y + line.heightPx,
                    alignment = TextAlign.JUSTIFY,
                    fontSizePx = fontPx,
                    lineHeightRatio = lineHeightRatio,
                    tag = null,
                    families = spec.families,
                    weight = spec.weight,
                    italic = spec.italic,
                    monospace = spec.monospace,
                    letterSpacingEm = spec.letterSpacingEm,
                    cjkLatinSpacingEm = spec.cjkLatinSpacingEm,
                    lineWidthPx = width,
                    inkColor = inkArgb,
                    hyphenAtEnd = line.hyphenAtEnd,
                    squeezeRatio = line.squeezeRatio,
                ),
            )
            y += lineH
        }
        return NoteTextDraw(broken, out, lineH)
    }

    private fun isTrailingSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\u00A0' || c == '\u3000'
}

/**
 * [NoteTextLayout.drawLines] 的结果：排版结论 + 逐行绘制指令 + 单行行高。
 *
 * @property lineHeightPx 单行行高（px，`round(fontPx × lineHeightRatio)`，与断行器同一式）
 */
class NoteTextDraw(
    val breakResult: NoteTextBreak,
    val lines: List<DrawLine>,
    val lineHeightPx: Int,
) {
    val heightPx: Int get() = lines.size * lineHeightPx
}
