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
    /**
     * 表格图占位隐藏区间（[text] 全文本坐标系，空 = 无表图旧路径）：
     * [TableCellLines] 为已进 [PageImage] 的 U+FFFC 占位记录本区间；绘制以透明墨隐藏
     *（占宽保留，与 P6-b 注音源文同法），字符流/断行/点按坐标不变。
     */
    val imgHidden: List<IntRange> = emptyList(),
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

    /**
     * S4/S5 协作件：几何（[LineAligner]）与落墨（[GlyphPainter]）。
     *
     * **两侧同源**：二者都用 [SkiaRunMeasurer] 取宽，与断行侧算断点同一出口 ——
     * 所以「量到的宽」与「画出来的宽」逐值一致（量画一致生死线）。
     * 按次构造而非快照：本类与绘制器同为常驻实例，字体池会因导入新字库而换，
     * 快照即永远看不见新字库（与 [collections] 同一理由）。
     */
    private val aligner = LineAligner()
    private val glyphPainter = GlyphPainter()
    private val kerningTable = KerningClusterTable()

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
                    val gap = ListMarkers.markerGapPx(line.fontSizePx)
                    if (marker.isShapeKind) {
                        // 矢量圆点/圈/方块：不随字体字形走（老 StaticLayout 管线 drawShapeMarker 同口径，
                        // C1-0 重构搬运；字形 "•/○" 在 CJK 字库下一大一小即此回归）。
                        val markerW = ListMarkers.shapeMarkerWidthPx(line.fontSizePx)
                        val x = if (marker.position == ListMarkers.Position.INSIDE) textX
                        else textX - markerW - gap
                        drawShapeMarker(
                            canvas, marker.kind, x, (line.yTop + line.yBottom) / 2f,
                            line.fontSizePx * ListMarkers.SHAPE_MARKER_EM,
                            withAlpha(line.inkColor, line.alpha),
                        )
                        if (marker.position == ListMarkers.Position.INSIDE) textX += markerW + gap
                    } else {
                        val markerString = ListMarkers.markerText(marker.kind, marker.order)
                        val markerW = measureMarkerWidth(style, markerString, collection)
                        when (marker.position) {
                            // OUTSIDE：marker 在沟槽悬垂（文本左缘左侧 markerW+gap），文本位置不变.
                            ListMarkers.Position.OUTSIDE -> {
                                paintText(canvas, style, markerString, textX - markerW - gap, line, collection)
                            }
                            // INSIDE：marker 行首内嵌，文本向右让 markerW+gap（近似平板 LeadingMarginSpan）.
                            ListMarkers.Position.INSIDE -> {
                                paintText(canvas, style, markerString, textX, line, collection)
                                textX += markerW + gap
                            }
                        }
                    }
                }
                paintText(canvas, style, line, textX, collection)
            }
        } finally {
            if (saved) canvas.restore()
        }
    }

    /** 主文本行绘制（S5 逐字路径）：几何由 [LineAligner] 给、落墨由 [GlyphPainter] 逐字做。 */
    /**
     * 主文本行绘制 —— **S5 逐字路径，本函数内不出现 `Paragraph`**。
     *
     * 几何（逐字 x / 可见右边界 / 拉伸）由 [LineAligner] 给，落墨由 [GlyphPainter] 逐字
     * `drawString` 做；两者与断行侧共用 [SkiaRunMeasurer] 取宽，**量画同源**。
     *
     * ## 为什么这里一句 `Paragraph` 都没有（S5 清理）
     *
     * S5 落地时为了少改，`paragraph.layout()` 被留着只用来取 `lineMetrics.height` 算基线，
     * 于是**两套基线计算并存**、60 行 `ParagraphBuilder` 分段喂文本成了死代码。
     * 「换断行器是为了真正实现 JUSTIFY」这个主要目的会被这种残骸掩盖 ——
     * 读代码的人看到 `ParagraphBuilder` + `Alignment.JUSTIFY` 会以为对齐还是 Skia 在做。
     *
     * 现在基线由 [LineAligner.baselineOffset] 单独算（半行距居中 + ascent，CSS 2.2 §10.8.1），
     * **Android 的 `setHeight`/`setHalfLeading` 平台差异一并消失**（旧路径靠
     * `deviceBaselineShift` 补，现在两侧同式）。故整段 Paragraph 装配可以删净。
     *
     * [style] 形参仍在：ruby rt 与 marker 两处仍需自建 `ParagraphStyle`（注音独立居中整形、
     * marker 单行短文本），它们与本行的对齐无关。
     */
    private fun paintText(canvas: Canvas, style: ParagraphStyle, line: DrawLine, textX: Float, collection: FontCollection) {
        // P3-a: 祖先 opacity 统一乘墨色/段色。
        val ink = withAlpha(line.inkColor, line.alpha)
        // 钳位：range 越界只跳过该行，绝不在绘制线程崩 activity。
        val start = line.range.first.coerceIn(0, line.text.length)
        val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)

        // P6-b 本行相交的叠排 runs；内联 rt 源文以透明墨隐藏（占宽保守，字符流/断行不变）。
        val rubyHits = line.rubyRuns.filter { it.start < endExcl && it.endExclusive > start }
        // 表格图占位隐藏（与注音源文同法：占宽保留，字符流/断行不变）。
        val hiddenRuns = buildList {
            for (run in rubyHits) {
                val s = run.rtStart.coerceIn(start, endExcl)
                val e = run.rtEndExclusive.coerceIn(start, endExcl)
                if (e > s && run.rtStart >= 0) add(s until e)
            }
            for (r in line.imgHidden) {
                val s = r.first.coerceIn(start, endExcl)
                val e = (r.last + 1).coerceIn(start, endExcl)
                if (e > s) add(s until e)
            }
        }
        // 子段 [from,to) 被隐藏 ⟺ 其末字符 to-1 落在区间内（区间按 `until` 存，last = 排外末端-1）。
        fun isHidden(from: Int, to: Int): Boolean {
            for (r in hiddenRuns) if (from >= r.first && to <= r.last + 1) return true
            return false
        }
        val lineExtra = rubyHits.maxOfOrNull { it.extraHeightPx() } ?: 0
        // 下划线：行区间相交段（叶坐标）。CSS 标准是整段传播，故按区间并集画，不逐字跳。
        val ulHits = line.underlineRuns.mapNotNull { r ->
            val s = r.start.coerceIn(start, endExcl)
            val e = r.endExclusive.coerceIn(start, endExcl)
            if (e > s) s until e else null
        }

        // ── 几何：Aligner 给逐字 x / 拉伸 / 可见右边界（P1-2 不换行段以无限宽排，单行溢出）──
        val placement = aligner.align(
            text = line.text,
            range = start until endExcl,
            fontSizePx = line.fontSizePx,
            lineWidthPx = if (line.nowrap) Float.MAX_VALUE else line.lineWidthPx.coerceAtLeast(1).toFloat(),
            letterSpacingEm = line.letterSpacingEm,
            tag = line.tag,
            families = line.families,
            weight = line.weight,
            italic = line.italic,
            monospace = line.monospace,
            fontRuns = line.fontRuns,
            align = line.alignment,
            firstLineIndentPx = 0f,
            // 末行不拉伸：两端对齐的定义本身。
            isLastLine = isLastLineOf(line, endExcl),
        )
        // 首行缩进：断行侧已按减宽排过，这里把本行整体右移（marker 另行绘制，不动）。
        val paintX = textX + line.firstLineIndentPx.coerceAtLeast(0f)
        // 基线：半行距居中 + ascent（CSS 2.2 §10.8.1）。**自算**，不再借道 Paragraph。
        val baseY = baselineY(line, lineExtra)
        // paintX 已含缩进 ⇒ placement 的 x 起点归零，这里统一加 paintX。
        paintGlyphs(canvas, line, placement, paintX, baseY, start, endExcl, { f, t -> isHidden(f, t) }, ulHits)

        // 注音/着重号与文字同盒，随 half-leading 一并下移，保持与基字的相对位置。
        if (rubyHits.isNotEmpty() && endExcl > start) {
            drawRuby(canvas, line, placement, paintX, start, endExcl, rubyHits, ink, collection, 0f)
        }
        if (line.emphasis != orilumn.reader.engine.css.EmphasisStyle.NONE && endExcl > start) {
            drawEmphasis(canvas, line, placement, paintX, ink, start, endExcl, 0f)
        }
    }

    /**
     * 本行基线 y（绝对坐标）。
     *
     * **替换掉旧路径的两套基线计算**：旧代码先按 `Paragraph.paint` 的内部语义定位，再按平台
     * （`paragraphPaintHonorsLineHeight`：jvm true / android false）用 `deviceBaselineShift`
     * 补差；现在两侧同式 —— [LineAligner.baselineOffset] 一次算完，平台差异不再需要分支。
     */
    private fun baselineY(line: DrawLine, lineExtra: Int): Float {
        val font = glyphPainter.baseGlyphStyle(
            line.fontSizePx, line.families, line.weight, line.italic, line.monospace,
            0xFF000000.toInt(), 'H'.code, line.tag,
        ).font
        val fm = font.metrics
        return aligner.baselineOffset(
            yTop = line.yTop.toFloat(),
            fontSizePx = line.fontSizePx,
            lineHeightRatio = line.lineHeightRatio,
            ascentPx = -fm.ascent,
            descentPx = fm.descent,
            extraTop = lineExtra.toFloat(),
        )
    }


    /**
     * P6-b 叠排注音绘制（Skia 端）：每个相交 run 的 rt 文本以注音字号居中画在基字上方。
     * 基字中心经基文段落的 `getRectsForRange` 并集求（量画同源）；rt 段落独立整形居中落位。
     * 内联 rt 源文已透明（占宽保守），字符流/断行不变；水平溢出允许（与浏览器同式）。
     */
    private fun drawRuby(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        start: Int,
        endExcl: Int,
        hits: List<orilumn.reader.engine.laying.RubyRun>,
        ink: Int,
        collection: FontCollection,
        halfLeading: Float,
    ) {
        val n = endExcl - start
        for (run in hits) {
            val bs = maxOf(run.start, start)
            val be = minOf(run.endExclusive, endExcl)
            if (be <= bs || run.rtText.isEmpty()) continue
            // S5：基字区间改读 Aligner 的 `xs`（T0 落点 2）。不再用 `getRectsForRange(TIGHT)`：
            // 那是 Skia **整形后**的墨盒，与本仓「裸 cmap 量宽」不同源（教训 20）；
            // `xs` 才是落墨真用的坐标，注音居中因此与基字严格对齐。
            val ps = (bs - start).coerceIn(0, n)
            val pe = (be - start).coerceIn(ps, n)
            if (pe <= ps) continue
            val span = aligner.visibleSpan(placement, n, ps, pe) ?: continue
            val (left, right) = span
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
        line: DrawLine,
        placement: LineAligner.Placement,
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
        val n = endExcl - start
        for (i in start until endExcl) {
            // S5：逐字墨盒中心改读 Aligner 的 `xs`（T0 落点 3），不再 `getRectsForRange(TIGHT)`。
            val local = i - start
            if (local >= n || local < 0) continue
            // 「空格亦置点，与浏览器一致」：尾随空白照样占一个点。
            aligner.visibleSpan(placement, n, local, local + 1)?.let { (lx, rx) ->
                val cx = paintX + (lx + rx) / 2f
                canvas.drawCircle(cx, y.toFloat(), r, paint)
            }
        }
    }

    /**
     * S5 逐字落墨：按 [placement] 的 x 逐字 `drawString`，段内样式由 `mergeBands` 供（不重写切段）。
     *
     * **下划线改为自绘**（原挂在 `TextStyle` 的 `DecorationStyle` 上）：
     * 按 CSS，`text-decoration` 覆盖整个行区间、与是否 JUSTIFY 无关，故用 [ulHits] 的区间并集
     * 画一条线 —— 与 `TextStyle` 版在字距/换面上更稳（装饰线不随字形盒跳变）。
     *
     * **字阴影**：原挂 `TextStyle.addShadow`（随段整形）。逐字路径下 `Paint` 不带阴影，
     * 故先按阴影参数画一遍偏移的同色字作底，再画正字 —— CSS `text-shadow` 的常规实现
     * （blur 用多层近似，量级为 `blur/2`）。
     */
    private fun paintGlyphs(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        isHidden: (Int, Int) -> Boolean,
        ulHits: List<IntRange>,
    ) {
        if (endExcl <= start) return
        val n = endExcl - start
        // S5b：含拉丁字母的行改用 Skia 簇位（保 kerning + fi/fl 连字），纯 CJK 行零成本走 Aligner 的 x。
        // **逐行判据、不混用**：一行要么全用簇位、要么全用 Aligner —— 两套 x 混在一行里就是错位。
        val xs: FloatArray = if (kerningTable.needsClusters(line.text, start, endExcl)) {
            // 簇位是**段落内**绝对 x（从 0 起），不含 JUSTIFY 拉伸与 CENTER/RIGHT 的整体偏移。
            // 逐字画时要在**同一坐标系**里落，故先把该行整体偏移补上：
            // 段落坐标 0 ≡ 本行 placement 的首字 x（= 缩进 + 对齐偏移）。
            val origin = placement.xs.firstOrNull() ?: 0f
            kerningTable.clusterXs(
                line.text, start, endExcl, line.fontSizePx, line.lineHeightRatio,
                line.tag, line.families, line.weight, line.italic, line.monospace,
                line.fontRuns, originX = origin,
            ) ?: placement.xs
        } else {
            placement.xs
        }
        // 段样式缓存：同一 (色, 面, 位移) 只解析一次 Font（matchFamilyStyle 是 native 调用）。
        val fontCache = HashMap<Any, org.jetbrains.skia.Font>()
        val fonts = object : FontResolver {
            override fun resolve(
                cp: Int, key: Any, sizePx: Float, fam: List<String>, wt: Int, ital: Boolean,
                mono: Boolean, tag: String?,
            ): org.jetbrains.skia.Font = fontCache.getOrPut(key) {
                glyphPainter.baseGlyphStyle(sizePx, fam, wt, ital, mono, 0xFF000000.toInt(), cp, tag).font
            }
        }

        val shadow = line.textShadow
        // 先画阴影层（偏移同色），再画正字 —— CSS text-shadow 的常规两层近似。
        if (shadow != null) {
            val sc = shadow.colorHex?.let(::cssHexToArgb) ?: 0xFF000000.toInt()
            drawGlyphPass(canvas, line, placement, paintX, baseY, start, endExcl, isHidden, shadow.dx, shadow.dy, sc, true, fonts, xs)
        }
        drawGlyphPass(canvas, line, placement, paintX, baseY, start, endExcl, isHidden, 0f, 0f, 0, false, fonts, xs)

        // 下划线：行区间相交段的 x 并集，用线画（不逐字跳）。
        if (ulHits.isNotEmpty()) {
            val ulPaint = org.jetbrains.skia.Paint().apply {
                color = withAlpha(line.inkColor, line.alpha)
                mode = org.jetbrains.skia.PaintMode.STROKE
                strokeWidth = (line.fontSizePx * 0.06f).coerceAtLeast(1f)
            }
            val y = baseY + line.fontSizePx * 0.12f
            for (r in ulHits) {
                val lo = (r.first - start).coerceIn(0, n)
                val hi = (r.last + 1 - start).coerceIn(lo, n)
                if (hi <= lo) continue
                aligner.visibleSpan(placement, n, lo, hi)?.let { (lx, rx) ->
                    canvas.drawLine(paintX + lx, y, paintX + rx, y, ulPaint)
                }
            }
        }
    }

    /** 段内面解析（抽出成接口：inline 函数不能收函数类型参数）。 */
    private interface FontResolver {
        /**
         * **按码本**解析面（`cp`）：逐字绘制时不同码本可能落到族栈里不同的面
         * （CJK 落宋体、Latin 落 Times），故缓存键**必须含码本**——
         * 少这一点就是「一个字用错面」，肉眼可见且不报错。
         */
        fun resolve(
            cp: Int, key: Any, sizePx: Float, fam: List<String>, wt: Int, ital: Boolean,
            mono: Boolean, tag: String?,
        ): org.jetbrains.skia.Font
    }

    /** 单趟逐字绘制（阴影层与正字层各一趟）。 */
    private fun drawGlyphPass(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        isHidden: (Int, Int) -> Boolean,
        dx: Float,
        dy: Float,
        overrideInk: Int,
        isShadowPass: Boolean,
        fontFor: FontResolver,
        xs: FloatArray = placement.xs,
    ) {
        val n = endExcl - start
        // **切段每行算一次**（不是每字一次）。第一版写成 `mergeBands(line, i, i+1).firstOrNull()`
        // 在逐字循环里，等于每字重建整行的 band 列表 —— JUSTIFY 行实测 13.00ms vs
        // LEFT 行 4.73ms（同文本、同字号），**拉伸不该让绘制慢 2.7 倍**，那全是这份重复计算。
        // 走游标：band 列表已按区间排序且无缝，顺序扫即可。
        val bands = mergeBands(line, start, endExcl)
        var bandIdx = 0
        for (i in start until endExcl) {
            val local = i - start
            if (local >= n || local < 0) continue
            val x = xs.getOrNull(local) ?: continue
            // 游标推进到覆盖 i 的那一段（band 之间有「无 run 覆盖」的间隙，band 为 null）。
            while (bandIdx < bands.size && bands[bandIdx].end <= i) bandIdx++
            val band = bands.getOrNull(bandIdx)?.takeIf { i >= it.start && i < it.end }
            val hidden = isHidden(i, i + 1)
            // 注音源文/表图占位：透明墨占宽（字符流不变）——逐字路径直接跳过落墨即可。
            if (hidden) continue
            val run = band?.font
            val sizePx = run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx
            val cp = line.text[i].code
            val famList = run?.families ?: line.families
            val wt = run?.weight ?: line.weight
            val ital = run?.italic ?: line.italic
            val mono = run?.monospace ?: line.monospace
            val rtag = run?.tag ?: line.tag
            // 缓存键含码本：CJK 与 Latin 会落到族栈里不同的面（少这一点 = 一个字用错面）。
            val key = glyphPainter.cacheKey(famList, wt, ital, sizePx, cp)
            val font = fontFor.resolve(cp, key, sizePx, famList, wt, ital, mono, rtag)
            val argb = if (isShadowPass) overrideInk else withAlpha(band?.argb ?: line.inkColor, line.alpha)
            val paint = org.jetbrains.skia.Paint().apply { color = argb }
            // 行内基线位移（Skia 正值下移，故原点上移）。
            val shiftEm = band?.shiftEm ?: 0f
            val y = baseY - shiftEm * line.fontSizePx + dy
            canvas.drawString(line.text.substring(i, i + 1), paintX + x + dx, y, font, paint)
        }
    }

    /**
     * 该行是否为段落末行（末行不拉伸，两端对齐的定义本身）。
     *
     * 绘制侧只看本 [DrawLine] 无法知道段末，故取 `DrawLine` 已携带的判据：
     * [DrawLine.listMarker] 只挂首行 ⇒ 不能用；`range.last` 覆盖到叶文本末即末行。
     * 叶文本末由 [orilumn.reader.engine.skia.DrawLineBuilder] 传入的 range 全文本坐标决定，
     * 这里用「行区间右端 == 该行所属叶的文本长度」近似 —— 不精确时会退化为多拉伸末行，
     * 故判据保守：仅当整行右端 == 全文本长度时才算末行。
     */
    private fun isLastLineOf(line: DrawLine, endExcl: Int): Boolean = endExcl >= line.text.length

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

    /**
     * 矢量 marker 绘制（disc 实心圆 / circle 空心圈 / square 实心方），行中线居中，
     * 尺寸恒相对行字号（不随字体字形走）。[x] 为形状左缘，[midY] 为行中线。
     */
    private fun drawShapeMarker(canvas: Canvas, kind: ListMarkers.Kind, x: Float, midY: Float, size: Float, ink: Int) {
        if (size <= 0f) return
        val half = size / 2f
        when (kind) {
            ListMarkers.Kind.SQUARE -> {
                val paint = Paint().apply { color = ink }
                canvas.drawRect(Rect.makeLTRB(x, midY - half, x + size, midY + half), paint)
            }
            ListMarkers.Kind.CIRCLE -> {
                val stroke = (size / 7f).coerceAtLeast(1f)
                val paint = Paint().apply {
                    color = ink
                    mode = org.jetbrains.skia.PaintMode.STROKE
                    strokeWidth = stroke
                }
                canvas.drawCircle(x + half, midY, half - stroke / 2f, paint)
            }
            else -> { // DISC
                val paint = Paint().apply { color = ink }
                canvas.drawCircle(x + half, midY, half, paint)
            }
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