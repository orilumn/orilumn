package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.extraHeightPx
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.Shadow
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.roundToInt

/**
 * 单行文本绘制指令。
 *
 * 由测度引擎（[SkiaParagraphBreaker]）产出的每一 `range` 对应一张绘制指令，
 * [yTop]..[yBottom] 为该框在所属页/滚动容器内的**绝对 Y**几何（统一行高，
 * `lineHeightPx = round(fontSizePx × lineHeightRatio)`），
 * [lineWidthPx] 为该行的整形宽（所在内容区宽度）。分页与连续滚动对绘制层
 * 只差这一组绝对坐标，其余完全共用（见 [LineWindowDrawer]）。
 *
 * @property text 所属整段文本；[range] 指向其中可见子区间（不含换行符）。
 * @property families 级联结果里该行的整条 CSS `font-family` 栈（作者顺序；空 = 默认族），
 *   与测度（[SkiaParagraphBreaker]）交给 [SkParagraphFactory] 的栈完全一致，绘制即量得。
 * @property xLeft 文本相对内容区的水平起点（px）；表叶 = `contentLeft + border.left + padding.left`，
 *   普通带头叶 = 0（对齐 [orilumn.reader.engine.layout] 各 BoxPageRenderer 的 xOff，P 系列 B 行）。
 * @property listMarker 行首列表 marker（OUTSIDE 悬垂沟槽 / INSIDE 行首内嵌）；null = 无 marker。
 * @property inkColor 文本墨色（ARGB Int，与 TypographicProfile.fgColor 同一值）：主题换色即换墨，
 *   底色由调用方打底（Android 离屏 surface / 桌面 Compose 背景），不在此持有。
 * @property colorRuns 书内显色区间（[orilumn.reader.engine.css.ColorRun]，[text] 全文本坐标系）：
 *   有区间的字按书色画，无区间仍走 [inkColor]；绘制时墨色盖章只改 [inkColor]，不动 runs。
 * @property fontRuns 行内 face 段（[orilumn.reader.engine.css.FontRun]，[text] 全文本坐标系，空 = 全行
 *   用 [families]/[weight]/[italic]/[monospace] 基底）——浏览器 inline-run 语义：有段的字按段的
 *   face 画（正文里的 `<code>`/`<kbd>` 等宽、`<strong>`/`<em>` 加粗/斜体），无段仍走基底；与测度
 *   （[SkiaParagraphBreaker]）同样源的 runs，量画一致。
 * @property firstLineIndentPx 本行是段首行时的 CSS `text-indent` 偏移（px；非首行恒 0）：
 *   断行侧已把首行按减宽排过，绘制侧把本行整体右移该值（与旧 LeadingMarginSpan 同位）。
 * @property nowrap 本行来自 `white-space: pre/nowrap` 不换行段：整形不限宽（单行溢出绘制），
 *   与断行侧 `breakLeafLines` 不换行语义一致（P1-2）。
 */
data class DrawLine(
    val text: String,
    val range: IntRange,
    val yTop: Int,
    val yBottom: Int,
    val alignment: TextAlign,
    val fontSizePx: Float,
    val lineHeightRatio: Float,
    val tag: String?,
    val families: List<String>,
    val weight: Int,
    val italic: Boolean,
    val monospace: Boolean,
    val letterSpacingEm: Float,
    val lineWidthPx: Int,
    val xLeft: Int = 0,
    val listMarker: ListMarkers.ListMarker? = null,
    val inkColor: Int = 0xFF000000.toInt(),
    val colorRuns: List<orilumn.reader.engine.css.ColorRun> = emptyList(),
    val fontRuns: List<orilumn.reader.engine.css.FontRun> = emptyList(),
    val firstLineIndentPx: Float = 0f,
    val nowrap: Boolean = false,
    /**
     * P1-2 行内基线位移（[orilumn.reader.engine.laying.BaselineShift]，[text] 全文本坐标系，
     * 空 = 纯基线）：随段整形（`SkParagraphFactory.runTextStyle` 位移＋行盒 strut 固定基线），
     * 不断行几何。
     */
    val baselineShifts: List<orilumn.reader.engine.laying.BaselineShift> = emptyList(),
    /**
     * P3-a 行阴影（颜色已解 currentColor；null 即无）：随段整形（Skia TextStyle
     * 原生阴影，量画同源；位移/模糊不改变 advances，不断行几何）。
     */
    val textShadow: orilumn.reader.engine.css.TextShadow? = null,
    /** P3-a 着重号样式（NONE 即无；圆点/圆圈画在行上/下方，见 emphasisUnder）。 */
    val emphasis: orilumn.reader.engine.css.EmphasisStyle = orilumn.reader.engine.css.EmphasisStyle.NONE,
    /** P3-a 着重号位置（false＝行上方，true＝行下方）。 */
    val emphasisUnder: Boolean = false,
    /** P3-a 祖先链 opacity 连乘（1 即旧路径；墨色/段色统一乘）。 */
    val alpha: Float = 1f,
    /**
     * P4-c2: `text[0]` 的章内全局字符偏移（= 叶全局起点；`text[k]` ↔ `charBase + k`）。
     * 点按命中（行内 glyph → 叶内偏移）经它换算成控制器 `linkTargetAt` 要的章内 char。
     * 默认 0（旧构造点行为不变）。
     */
    val charBase: Int = 0,
    /**
     * P6-b 叠排注音 runs（[text] 全文本坐标系，空 = 无注音旧路径）：
     * 由 [DrawLineBuilder] 从与 [text] 同构的遍历产出；绘制把 rt 居中画在基字上方，
     * 内联 rt 源文以透明墨隐藏（占宽保守，字符流/断行不变）。
     */
    val rubyRuns: List<orilumn.reader.engine.laying.RubyRun> = emptyList(),
    /**
     * 下划线区间（[text] 全文本坐标系，空 = 无下划线旧路径）：
     * 由 [DrawLineBuilder] 从与 [text] 同构的遍历产出（声明元素整段传播）；
     * Skia 端随段挂原生下划线装饰（量画同源，不改 advances）。
     */
    val underlineRuns: List<orilumn.reader.engine.laying.UnderlineRun> = emptyList(),
)

/**
 * 行窗口绘制器：按**行**的绝对 Y 坐标用 SkParagraph 单行整形绘制。
 *
 * 与测度共用 [SkParagraphFactory] 的同一套 ParagraphStyle 构造（kJustify / 行高 /
 * letterSpacing 恒一），保证"画出来的"就是"量出来的"。每一行只用其 [DrawLine.range]
 * 子串、以 [DrawLine.lineWidthPx] 整形——区间由测度保证 ≤ 行宽，故 `layout` 不二次折行；
 * 对齐（含两端对齐/居中/右对齐）在行宽内生效，与 Android 排版对齐语义一致。
 *
 * 分页与连续滚动共用此接口：两者均产出若干 [DrawLine]（绝对 Y）→ `drawLines`。
 * [clip] 用于行窗口裁剪（可选）：给定可视内容区后，窗口外行由裁剪丢弃。
 */
class LineWindowDrawer(
    /**
     * 字体集合按次解析（默认 [SkiaFontPool.current]），而非构造时快照：绘制器是常驻实例
     *（Compose remember / 引擎复用），用户开书后导入字体会换池；快照即永远看不见新字库
     *（断行器按次新建不受影响，两边还会量画不一致）。
     */
    private val collections: () -> FontCollection = SkiaFontPool::current,
) {

    fun drawLines(canvas: Canvas, contentLeft: Float, lines: List<DrawLine>, clip: Rect? = null) {
        if (lines.isEmpty()) return
        val collection = collections()
        val saved = if (clip != null) {
            canvas.save()
            canvas.clipRect(clip)
            true
        } else {
            false
        }
        try {
            for (line in lines) {
                val style = SkParagraphFactory.paragraphStyle(
                    line.alignment,
                    line.fontSizePx,
                    line.lineHeightRatio,
                    line.tag,
                    line.families,
                    line.weight,
                    line.italic,
                    line.monospace,
                    line.letterSpacingEm,
                    // P3-a: 祖先 opacity 统一乘墨色（段色在 paintText 内乘）。
                    inkColor = withAlpha(line.inkColor, line.alpha),
                    // P1-2: 有基线位移的行固定行盒基线（与度量侧同开，基线不浮动）。
                    forceStrut = line.baselineShifts.isNotEmpty(),
                )
                var textX = contentLeft + line.xLeft
                val marker = line.listMarker
                if (marker != null) {
                    val markerString = ListMarkers.markerText(marker.kind, marker.order)
                    val markerW = measureMarkerWidth(style, markerString, collection)
                    when (marker.position) {
                        // OUTSIDE：marker 在沟槽悬垂（文本左缘左侧 markerW+gap），文本位置不变.
                        ListMarkers.Position.OUTSIDE -> {
                            val gap = ListMarkers.markerGapPx(line.fontSizePx)
                            paintText(canvas, style, markerString, textX - markerW - gap, line, collection)
                        }
                        // INSIDE：marker 行首内嵌，文本向右让 markerW+gap（近似平板 LeadingMarginSpan）.
                        ListMarkers.Position.INSIDE -> {
                            val gap = ListMarkers.markerGapPx(line.fontSizePx)
                            paintText(canvas, style, markerString, textX, line, collection)
                            textX += markerW + gap
                        }
                    }
                }
                paintText(canvas, style, line, textX, collection)
            }
        } finally {
            if (saved) canvas.restore()
        }
    }

    /** 主文本行绘制：JUSTIFY 追加换行制造首行以获得铺满间距（中部行两端对齐语义），见类文档。 */
    private fun paintText(canvas: Canvas, style: ParagraphStyle, line: DrawLine, textX: Float, collection: FontCollection) {
        val appendTrailingNewline = line.alignment == orilumn.reader.engine.css.TextAlign.JUSTIFY
        // P3-a: 祖先 opacity 统一乘墨色/段色；行阴影随段整形（Skia 原生，不改 advances）。
        // 注意 skija `textStyle` getter 返回拷贝：改完必须重赋回 paragraphStyle。
        val ink = withAlpha(line.inkColor, line.alpha)
        val lineShadow = line.textShadow?.let { ts ->
            val c = ts.colorHex?.let(::cssHexToArgb) ?: return@let null
            Shadow(c, ts.dx, ts.dy, (ts.blur / 2).toDouble())
        }
        // 基底样式挂阴影（无 run 快径走它；有 run 段在 textStyleFor 内同挂）。
        if (lineShadow != null) {
            val base = style.textStyle
            base.addShadow(lineShadow)
            style.textStyle = base
        }
        if (lineShadow != null) style.textStyle.addShadow(lineShadow)
        // 钳位：range 越界只跳过该行（Skia 度量版本差异曾让末行 end 超出），绝不在绘制线程崩 activity。
        val start = line.range.first.coerceIn(0, line.text.length)
        val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)
        // P6-b: 本行相交的叠排 runs（叶坐标）；内联 rt 源文以透明墨隐藏（占宽保守，字符流/断行不变）。
        val rubyHits = line.rubyRuns.filter { it.start < endExcl && it.endExclusive > start }
        val rtHidden = rubyHits.mapNotNull { run ->
            val s = run.rtStart.coerceIn(start, endExcl)
            val e = run.rtEndExclusive.coerceIn(start, endExcl)
            if (e > s && run.rtStart >= 0) s until e else null
        }
        fun isRtHidden(from: Int, to: Int): Boolean {
            for (r in rtHidden) if (from >= r.first && to <= r.last) return true
            return false
        }
        val lineExtra = rubyHits.maxOfOrNull { it.extraHeightPx() } ?: 0
        // 下划线：行区间相交段（叶坐标），随段挂原生装饰（CSS 标准：声明元素整段传播）。
        val ulHits = line.underlineRuns.mapNotNull { r ->
            val s = r.start.coerceIn(start, endExcl)
            val e = r.endExclusive.coerceIn(start, endExcl)
            if (e > s) s until e else null
        }
        fun isUnderlined(from: Int, to: Int): Boolean {
            for (r in ulHits) if (from >= r.first && to <= r.last) return true
            return false
        }
        val builder = ParagraphBuilder(style, collection)
        val hasRuns = line.colorRuns.isNotEmpty() || line.fontRuns.isNotEmpty() || line.baselineShifts.isNotEmpty() || rtHidden.isNotEmpty() || ulHits.isNotEmpty()
        if (!hasRuns) {
            builder.addText(line.text.substring(start, endExcl) + if (appendTrailingNewline) "\n" else "")
        } else {
            // 行内着色 + 行内 face + 基线位移 + 下划线：把行区间按四份 runs 合并切成段（sorted,
            // 段内肤色/face/位移/下划线恒定），无色无 face 无位移无下划线的段走基底（默认 style）, 其余段 push
            // 同配置换 style。位移 em 相对行基底字号，Skia 正值下移故取反。
            // 同 style 段按「argb+face+位移+下划线」缓存复用；run 越界按行钳位（防御，正常不会触发）。
            val styles = HashMap<StyleKey, TextStyle>()
            fun textStyleFor(argb: Int?, run: orilumn.reader.engine.css.FontRun?, shiftEm: Float, underlined: Boolean): TextStyle = styles.getOrPut(
                StyleKey(argb, run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx, run?.tag ?: line.tag, run?.families ?: line.families, run?.weight ?: line.weight, run?.italic ?: line.italic, run?.monospace ?: line.monospace, shiftEm, underlined),
            ) {
                val segInk = withAlpha(argb ?: line.inkColor, line.alpha)
                SkParagraphFactory.runTextStyle(
                    line.alignment,
                    run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx,
                    line.lineHeightRatio,
                    run?.tag ?: line.tag,
                    run?.families ?: line.families,
                    run?.weight ?: line.weight,
                    run?.italic ?: line.italic,
                    run?.monospace ?: line.monospace,
                    line.letterSpacingEm,
                    inkColor = segInk,
                    baselineShiftPx = -shiftEm * line.fontSizePx,
                ).apply {
                    if (lineShadow != null) addShadow(lineShadow)
                    // 下划线装饰色跟段墨色（CSS 无 text-decoration-color 解析时与文字同色，浏览器标准）。
                    if (underlined) {
                        setDecorationStyle(
                            org.jetbrains.skia.paragraph.DecorationStyle(
                                true, false, false, false, segInk,
                                org.jetbrains.skia.paragraph.DecorationLineStyle.SOLID, 1f,
                            ),
                        )
                    }
                }
            }
            fun paintSegment(from: Int, to: Int, argb: Int?, run: orilumn.reader.engine.css.FontRun?, shiftEm: Float, underlined: Boolean) {
                if (to <= from) return
                // P6-b: 内联 rt 源文透明（占宽保留）：按 rt 区间切分，rt 段走透明墨。
                var c = from
                // 收集本段内的 rt 边界，切成透明/可见子段。
                val cuts = ArrayList<Int>(4)
                cuts.add(c)
                for (r in rtHidden) {
                    if (r.first > c && r.first < to) cuts.add(r.first)
                    if (r.last > c && r.last < to) cuts.add(r.last)
                }
                cuts.add(to)
                val sorted = cuts.distinct().sorted()
                for (k in 0 until sorted.size - 1) {
                    val s = sorted[k]
                    val e = sorted[k + 1]
                    if (e <= s) continue
                    val hidden = isRtHidden(s, e)
                    if (!hidden && argb == null && run == null && shiftEm == 0f && !underlined) {
                        builder.addText(line.text.substring(s, e))
                    } else {
                        builder.pushStyle(textStyleFor(if (hidden) 0x00000000 else argb, run, shiftEm, underlined))
                        builder.addText(line.text.substring(s, e))
                        builder.popStyle()
                    }
                }
            }
            var cursor = start
            for (band in mergeBands(line, start, endExcl)) {
                if (band.start > cursor) paintSegment(cursor, band.start, null, null, 0f, isUnderlined(cursor, band.start))
                paintSegment(band.start, band.end, band.argb, band.font, band.shiftEm, band.underlined)
                if (band.end > cursor) cursor = band.end
            }
            if (cursor < endExcl) paintSegment(cursor, endExcl, null, null, 0f, isUnderlined(cursor, endExcl))
            if (appendTrailingNewline) builder.addText("\n")
        }
        val paragraph = builder.build()
        try {
            // P1-2: 不换行段以无限宽整形（单行溢出，CSS overflow 可见语义；对齐退为行首）。
            paragraph.layout(if (line.nowrap) Float.MAX_VALUE else line.lineWidthPx.coerceAtLeast(1).toFloat())
            // 首行缩进：断行侧已按减宽排过，这里把本行整体右移（marker 另行绘制，不动）。
            val paintX = textX + line.firstLineIndentPx.coerceAtLeast(0f)
            // P6-b: 有注音的行基文下移注音高（行顶留给叠排 rt），无注音走旧 yTop。
            // 半行距居中（CSS 2.2 §10.8.1）按平台能力分两路，覆盖两个平台的 Skia 差异：
            //  - jvm：`Paragraph.paint` 按 setHeight 缩放后的 ascent 落基线；整形侧开 setHalfLeading(true)
            //    （见 [SkParagraphFactory]）即得浏览器语义——leading 上下各半，行盒内文字垂直居中，
            //    此时 lineMetrics.height ≈ 行高，无需再手工位移。
            //  - android：`Paragraph.paint` 忽略 setHeight / setHalfLeading，基线 = 画笔原点 + 字体自然盒的一半，
            //    比浏览器少了 (行高 − 自然字体高)。下面把绘制原点整体下移这一差值补回，落笔即与浏览器一致
            //    （Android 的 lineMetrics.height 就是自然盒高，不受 setHeight 影响）。
            // 平台判定经 [paragraphPaintHonorsLineHeight]（jvm true / android false）。
            // 只动绘制原点：DrawLine 的 yTop/yBottom（页行几何）不变，分页与行盒窗口完全不动。
            val lineMetrics0 = paragraph.lineMetrics.getOrNull(0)
            val deviceBaselineShift = if (
                !paragraphPaintHonorsLineHeight &&
                lineMetrics0 != null &&
                lineMetrics0.height > 0.0
            ) {
                (line.lineHeightRatio * line.fontSizePx - lineMetrics0.height).coerceAtLeast(0.0)
            } else {
                0.0
            }
            val baseY = line.yTop + lineExtra + deviceBaselineShift
            paragraph.paint(canvas, paintX, baseY.toFloat())
            // 注音/着重号与文字同盒，随 half-leading 一并下移，保持与基字的相对位置。
            if (rubyHits.isNotEmpty() && endExcl > start) {
                drawRuby(canvas, paragraph, line, paintX, start, endExcl, rubyHits, ink, collection, deviceBaselineShift.toFloat())
            }
            // P3-a: 着重号（圆点/圆圈，逐字定位，行上/下方）。
            if (line.emphasis != orilumn.reader.engine.css.EmphasisStyle.NONE && endExcl > start) {
                drawEmphasis(canvas, paragraph, line, paintX, ink, start, endExcl, deviceBaselineShift.toFloat())
            }
        } finally {
            paragraph.close()
        }
    }

    /**
     * P6-b 叠排注音绘制（Skia 端）：每个相交 run 的 rt 文本以注音字号居中画在基字上方。
     * 基字中心经基文段落的 `getRectsForRange` 并集求（量画同源）；rt 段落独立整形居中落位。
     * 内联 rt 源文已透明（占宽保守），字符流/断行不变；水平溢出允许（与浏览器同式）。
     */
    private fun drawRuby(
        canvas: Canvas,
        paragraph: org.jetbrains.skia.paragraph.Paragraph,
        line: DrawLine,
        paintX: Float,
        start: Int,
        endExcl: Int,
        hits: List<orilumn.reader.engine.laying.RubyRun>,
        ink: Int,
        collection: FontCollection,
        halfLeading: Float,
    ) {
        for (run in hits) {
            val bs = maxOf(run.start, start)
            val be = minOf(run.endExclusive, endExcl)
            if (be <= bs || run.rtText.isEmpty()) continue
            // 基字在段落坐标系的区间（段落文本即 line.text[start,endExcl)）。
            val ps = (bs - start).coerceAtLeast(0)
            val pe = (be - start).coerceAtLeast(0)
            if (pe <= ps) continue
            val rects = runCatching {
                paragraph.getRectsForRange(
                    ps, pe,
                    org.jetbrains.skia.paragraph.RectHeightMode.TIGHT,
                    org.jetbrains.skia.paragraph.RectWidthMode.TIGHT,
                )
            }.getOrNull() ?: continue
            if (rects.isEmpty()) continue
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (b in rects) {
                left = minOf(left, b.rect.left)
                right = maxOf(right, b.rect.right)
            }
            if (right <= left) continue
            val baseCenter = paintX + (left + right) / 2f
            val rtSize = run.rtFontSizePx.coerceAtLeast(1f)
            val rtStyle = SkParagraphFactory.paragraphStyle(
                orilumn.reader.engine.css.TextAlign.CENTER,
                rtSize,
                1f,
                "rt",
                line.families,
                line.weight,
                line.italic,
                false,
                0f,
                inkColor = ink,
            )
            val rtBuilder = ParagraphBuilder(rtStyle, collection)
            rtBuilder.addText(run.rtText)
            val rtPara = rtBuilder.build()
            try {
                rtPara.layout(Float.MAX_VALUE)
                val rtRects = runCatching {
                    rtPara.getRectsForRange(
                        0, run.rtText.length,
                        org.jetbrains.skia.paragraph.RectHeightMode.TIGHT,
                        org.jetbrains.skia.paragraph.RectWidthMode.TIGHT,
                    )
                }.getOrNull()
                var rtW = 0f
                if (rtRects != null && rtRects.isNotEmpty()) {
                    var rl = Float.MAX_VALUE
                    var rr = -Float.MAX_VALUE
                    for (b in rtRects) {
                        rl = minOf(rl, b.rect.left)
                        rr = maxOf(rr, b.rect.right)
                    }
                    if (rr > rl) rtW = rr - rl
                }
                if (rtW <= 0f) rtW = run.rtText.length * rtSize * 0.6f
                rtPara.paint(canvas, baseCenter - rtW / 2f, line.yTop + halfLeading)
            } finally {
                rtPara.close()
            }
        }
    }

    /** P3-a alpha 合成：ARGB 高字节乘系数（1 即原文）。 */
    private fun withAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        if (alpha <= 0f) return argb and 0x00FFFFFF
        val a = ((argb ushr 24) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * P3-a 着重号绘制：逐字取墨盒中心置点/圈（空格亦置点，与浏览器一致）。
     * 位置：行上 = 行顶内，行下 = 行底内；半径相对行字号。
     */
    private fun drawEmphasis(
        canvas: Canvas,
        paragraph: org.jetbrains.skia.paragraph.Paragraph,
        line: DrawLine,
        paintX: Float,
        ink: Int,
        start: Int,
        endExcl: Int,
        halfLeading: Float,
    ) {
        val filled = line.emphasis == orilumn.reader.engine.css.EmphasisStyle.DOT
        val r = line.fontSizePx * (if (filled) 0.09f else 0.11f)
        if (r <= 0f) return
        val paint = Paint().apply {
            color = ink
            if (!filled) {
                mode = org.jetbrains.skia.PaintMode.STROKE
                strokeWidth = (r * 0.3f).coerceAtLeast(1f)
            }
        }
        val y = if (line.emphasisUnder) line.yBottom + halfLeading - r - 1f else line.yTop + halfLeading + r + 1f
        for (i in start until endExcl) {
            val boxes = runCatching {
                paragraph.getRectsForRange(
                    i, i + 1,
                    org.jetbrains.skia.paragraph.RectHeightMode.TIGHT,
                    org.jetbrains.skia.paragraph.RectWidthMode.TIGHT,
                )
            }.getOrNull() ?: continue
            for (b in boxes) {
                val cx = paintX + (b.rect.left + b.rect.right) / 2f
                canvas.drawCircle(cx, y.toFloat(), r, paint)
            }
        }
    }

    /** 行区间内四套 runs（色 + face + 基线位移 + 下划线）合并后的 [lo, hi) 段序列：每段的肤色/face/位移/下划线恒定，供逐段渲染。 */
    private data class Band(val start: Int, val end: Int, val argb: Int?, val font: orilumn.reader.engine.css.FontRun?, val shiftEm: Float, val underlined: Boolean)

    private fun mergeBands(line: DrawLine, lo: Int, hi: Int): List<Band> {
        val colorBounds = line.colorRuns.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) ColorBand(s, e, r.argb) else null
        }
        val fontBounds = line.fontRuns.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) FontBand(s, e, r) else null
        }
        val shiftBounds = line.baselineShifts.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) ShiftBand(s, e, r.shiftEm) else null
        }
        val ulBounds = line.underlineRuns.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) UlBand(s, e) else null
        }
        if (colorBounds.isEmpty() && fontBounds.isEmpty() && shiftBounds.isEmpty() && ulBounds.isEmpty()) return emptyList()
        val edges = (colorBounds.flatMap { listOf(it.start, it.end) } +
            fontBounds.flatMap { listOf(it.start, it.end) } +
            shiftBounds.flatMap { listOf(it.start, it.end) } +
            ulBounds.flatMap { listOf(it.start, it.end) } + listOf(lo, hi))
            .filter { it in lo..hi }
            .distinct().sorted()
        val bands = ArrayList<Band>(edges.size - 1)
        for (k in 0 until edges.size - 1) {
            val s = edges[k]
            val e = edges[k + 1]
            if (e <= s) continue
            val argb = colorBounds.firstOrNull { it.start <= s && e <= it.end }?.argb
            val font = fontBounds.firstOrNull { it.start <= s && e <= it.end }?.run
            val shiftEm = shiftBounds.firstOrNull { it.start <= s && e <= it.end }?.shiftEm ?: 0f
            val underlined = ulBounds.any { it.start <= s && e <= it.end }
            bands.add(Band(s, e, argb, font, shiftEm, underlined))
        }
        return bands
    }

    private data class ColorBand(val start: Int, val end: Int, val argb: Int)
    private data class FontBand(val start: Int, val end: Int, val run: orilumn.reader.engine.css.FontRun)
    private data class ShiftBand(val start: Int, val end: Int, val shiftEm: Float)
    private data class UlBand(val start: Int, val end: Int)

    /** [StyleKey] = 段样式缓存键（argb + face + 字号 + 位移 + 下划线全部字段，忽略 run 区间）。字号必须入键：
     *  行内 `<sub>/<span{font-size}>` 与基底同 face 不同号的段不可相互复用 style。 */
    private data class StyleKey(val argb: Int?, val fontSizePx: Float, val tag: String?, val families: List<String>, val weight: Int, val italic: Boolean, val monospace: Boolean, val shiftEm: Float, val underlined: Boolean)

    /** Marker 字形绘制（listMarker 的同一 paragraphStyle 整形，单行短文本，无需 JUSTIFY 尾行技巧）。 */
    private fun paintText(canvas: Canvas, style: ParagraphStyle, text: String, textX: Float, line: DrawLine, collection: FontCollection) {
        val paragraph = ParagraphBuilder(style, collection).addText(text).build()
        try {
            paragraph.layout(line.lineWidthPx.coerceAtLeast(1).toFloat())
            paragraph.paint(canvas, textX, line.yTop.toFloat())
        } finally {
            paragraph.close()
        }
    }

    /** 用同 [DrawLine] 的 paragraphStyle 实测 marker 文本宽度（SkParagraph，与平板 measureText 同标的）。 */
    private fun measureMarkerWidth(style: ParagraphStyle, marker: String, collection: FontCollection): Int {
        val paragraph = ParagraphBuilder(style, collection).addText(marker).build()
        try {
            paragraph.layout(Float.MAX_VALUE)
            val w = paragraph.lineMetrics.getOrNull(0)?.right ?: 0.0
            return w.roundToInt().coerceAtLeast(1)
        } finally {
            paragraph.close()
        }
    }
}