package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.DrawKind
import orilumn.reader.engine.laying.HiddenCheck
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.effectiveOpacity
import kotlin.math.roundToInt

/**
 * Q1 输出端单源：把盒式布局结果投影成 [DrawLine] 绘制指令（章节绝对 Y）。
 *
 * 原先只在桌面 `DesktopReaderHost`（S32）里私有实现；Q1-a 平移到 engine-skia 共享，
 * 桌面和平板共用这一份「盒叶 → 每行 DrawLine」，文本逐行在 [LineWindowDrawer] 用
 * [SkParagraphFactory] 单行整形绘制（区间即盒流塑形输入区间 `leafText`，几何不漂移）。
 *
 * 约定（对齐 BoxPageRenderer 旧绘制语义）：
 *  - `xLeft` = 表叶 contentLeft + 自身 border.left + padding.left（P3）；
 *  - `listMarker` 只挂首个载体叶（firstCarrierSet）的首行（P3 与平板「每 <li> 只画一个 bullet」
 *    同一标的）；
 *  - 替换块（img/table）不产出 DrawLine —— 行仍由盒流占位，像素绘制由宿主层叠加。
 */
object DrawLineBuilder {

    fun build(
        result: BoxLayoutResult,
        styleMap: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck,
        letterSpacingEm: Float,
        /** 文本墨色（ARGB Int，调用方喂 TypographicProfile.fgColor），逐行写入 DrawLine。 */
        inkColor: Int = 0xFF000000.toInt(),
        /** P3-c 生成内容查找（空即无，旧路径；调用方喂与塑形同一 phase-1 结果）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): Map<Int, DrawLine> {
        val out = HashMap<Int, DrawLine>()
        val leaves = ArrayList<LayoutBox>()
        fun collect(boxes: List<LayoutBox>) {
            for (b in boxes) if (b.isContainer) collect(b.childBoxes) else leaves.add(b)
        }
        collect(result.boxes)
        // P3：carriers 按盒叶（文档序）一次性求；marker 每叶同一 common 规则（ListMarkers.markerForLeaf）。
        val carriers = ListMarkers.firstCarrierSet(leaves.mapNotNull { it.el })
        for (leaf in leaves) {
            if (leaf.ranges.isEmpty() || leaf.table != null || leaf.replaceableHeight != 0) continue
            val el = leaf.el ?: continue
            val text = NormalFlowLayout.leafText(el, styleMap, classify, hidden, genOf)
            if (text.isEmpty()) continue
            val style = leaf.style
            val mono = style.monospace || el.tag == "pre"
            val tag = if (el.isText) el.parent?.tag ?: el.tag else el.tag
            val w = NormalFlowLayout.innerBreakWidth(style, leaf.contentWidth)
            // P3：xLeft = 表叶 contentLeft + 自身 border.left + padding.left（对齐平板 BoxPageRenderer xOff）.
            val xLeft = leaf.contentLeft + (style.border.left + style.padding.left).roundToInt()
            val marker = ListMarkers.markerForLeaf(el, carriers) { styleMap[it] }
            // 着色区间按叶一次算好（行循环内复用；无着色叶回空表零开销）。
            val runs = NormalFlowLayout.leafColorRuns(el, styleMap, classify, hidden, genOf)
            // 行内 face 段同样按叶一次算好（`<code>`/`<strong>` 等异 face 段；纯种叶回空表零开销）。
            val fontRuns = NormalFlowLayout.leafFontRuns(el, styleMap, classify, hidden, genOf)
            // P1-2: 行内基线位移同样按叶一次算好（纯基线叶回空表零开销）。
            val shifts = NormalFlowLayout.leafBaselineShifts(el, styleMap, classify, hidden, genOf)
            // P6-b: 叠排注音 runs 同样按叶一次算好（无注音叶回空表零开销；绘制侧按行过滤）。
            val rubyRuns = NormalFlowLayout.leafRubyRuns(el, styleMap, classify, hidden, genOf)
            // 下划线区间同样按叶一次算好（无下划线叶回空表零开销）。
            val underlineRuns = NormalFlowLayout.leafUnderlineRuns(el, styleMap, classify, hidden, genOf)
            // 首行缩进只给叶首行（断行侧同值已减宽，绘制侧右移同值；悬挂负值本轮不支持，归零）。
            val indent = style.textIndentPx.coerceAtLeast(0f)
            // P1-2: 不换行段绘制侧以无限宽整形（与 breakLeafLines 一致）。
            val nowrap = !orilumn.reader.engine.css.WhiteSpaceNormalize.wraps(style.whiteSpace)
            // P3-a: 行阴影（currentColor 按叶墨色解）＋着重号＋祖先 opacity。
            val shadow = style.textShadow?.let { sh ->
                val argb = sh.colorHex?.let(::cssHexToArgb) ?: style.colorHex?.let(::cssHexToArgb) ?: inkColor
                orilumn.reader.engine.css.TextShadow(sh.dx, sh.dy, sh.blur, "#%08x".format(argb))
            }
            val alpha = effectiveOpacity(el) { styleMap[it] }
            // P4-c2: 行文本在章内的字符基址（盒流行 FlowedLine.charStart 章内全局，减首区间起点即叶 text[0] 位置）。
            val firstFl = result.lines.getOrNull(leaf.firstLineIndex)
            val charBase = if (firstFl != null && leaf.ranges.isNotEmpty()) firstFl.charStart - leaf.ranges.first().first else 0
            for ((i, r) in leaf.ranges.withIndex()) {
                val lineIdx = leaf.firstLineIndex + i
                val fl = result.lines.getOrNull(lineIdx) ?: continue
                if (r.first < 0 || r.last >= text.length) continue
                // P4-a2: 环绕前导行按收缩宽整形（与塑形同宽），x 右移悬浮偏移；
                // 余行原宽原位（命中反查经 lineWidthPx 自动同源）。
                val lead = leaf.floatLead
                val intruded = lead != null && i < lead.lines
                val lineW = if (intruded) lead.widthPx else w
                val lineX = xLeft + if (intruded) lead.xOffPx.roundToInt() else 0
                out[lineIdx] = DrawLine(
                    text = text,
                    range = r,
                    yTop = fl.yTop,
                    yBottom = fl.yBottom,
                    alignment = style.textAlign,
                    fontSizePx = style.fontSizePx,
                    lineHeightRatio = style.lineHeightRatio,
                    tag = tag,
                    families = style.fontFamilies,
                    weight = style.fontWeight,
                    italic = style.italic,
                    monospace = mono,
                    letterSpacingEm = letterSpacingEm,
                    lineWidthPx = lineW,
                    xLeft = lineX,
                    listMarker = if (lineIdx == leaf.firstLineIndex) marker else null,
                    inkColor = inkColor,
                    colorRuns = runs,
                    fontRuns = fontRuns,
                    firstLineIndentPx = if (lineIdx == leaf.firstLineIndex) indent else 0f,
                    nowrap = nowrap,
                    baselineShifts = shifts,
                    textShadow = shadow,
                    emphasis = style.emphasisStyle,
                    emphasisUnder = style.emphasisUnder,
                    alpha = alpha,
                    charBase = charBase,
                    rubyRuns = rubyRuns,
                    underlineRuns = underlineRuns,
                )
            }
        }
        return out
    }

    /**
     * 本页可视窗口内的 `<img>` 替换叶几何（章节绝对 Y，与 [DrawLine] 同一坐标系）。
     *
     * 与 Android `BoxPageRenderer.pageImages` 同口径（xLeft = contentLeft + 自身左 border/padding、
     * used 宽经标准替换尺寸、行高即盒高），供尚无自有图片管线的宿主（桌面）复用：文本行走 [build]
     * 的 DrawLine 窗口，图片从不行——这里把图几何交出去，阅读面异步解码贴图。
     */
    fun pageImages(
        result: BoxLayoutResult,
        firstLine: Int,
        lastLineExclusive: Int,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): List<PageImage> {
        if (firstLine < 0 || lastLineExclusive <= firstLine) return emptyList()
        val pageEnd = minOf(lastLineExclusive, result.lines.size)
        if (firstLine >= pageEnd) return emptyList()
        val leaves = ArrayList<LayoutBox>()
        fun collect(boxes: List<LayoutBox>) {
            for (b in boxes) if (b.isContainer) collect(b.childBoxes) else leaves.add(b)
        }
        collect(result.boxes)
        val out = ArrayList<PageImage>()
        for (leaf in leaves) {
            if (leaf.replaceableHeight <= 0 || leaf.table != null) continue
            val el = leaf.el
            if (el == null || el.tag != "img") continue
            val gearIndex = leaf.firstLineIndex
            if (gearIndex < firstLine || gearIndex >= pageEnd) continue
            val src = el.attrs["src"] ?: continue
            if (chapterHref.isBlank()) continue
            val line = result.lines.getOrNull(gearIndex) ?: continue
            val xOff = leaf.contentLeft + (leaf.style.border.left + leaf.style.padding.left).roundToInt()
            val breakW = NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth)
            val usedW = NormalFlowLayout.replacedUsedSize(
                el, leaf.style, breakW, imageLoader, chapterHref,
            ).first.coerceAtLeast(1)
            out.add(
                PageImage(
                    src = src,
                    chapterHref = chapterHref,
                    xLeft = xOff,
                    yTop = line.yTop,
                    yBottom = line.yTop + leaf.replaceableHeight.coerceAtLeast(1),
                    widthPx = usedW,
                    heightPx = leaf.replaceableHeight.coerceAtLeast(1),
                ),
            )
        }
        return out
    }

    /**
     * 本页可视窗口内的盒背景/边框几何（章节绝对 Y，与 [DrawLine] 同一坐标系）。
     *
     * [orilumn.reader.engine.laying.BoxDrawer.drawOnPage] 单源：跨页撕裂按页可视带裁剪，
     * 推挤到他页的块不再出现；hex 解析失败的直接丢弃（回主题底，不崩版式）。
     * P3-b: 背景图随同一 rect 携带（`chapterHref` 为解码基准；缺失即纯色旧路径）。
     */
    fun pageBackgrounds(
        result: BoxLayoutResult,
        firstLine: Int,
        lastLineExclusive: Int,
        chapterHref: String = "",
    ): List<PageBackground> {
        if (firstLine < 0 || lastLineExclusive <= firstLine) return emptyList()
        val pageEnd = minOf(lastLineExclusive, result.lines.size)
        if (firstLine >= pageEnd) return emptyList()
        val bandTop = result.lines[firstLine].yTop
        val bandBottom = result.lines[pageEnd - 1].yBottom
        if (bandBottom <= bandTop) return emptyList()
        return BoxDrawer.drawOnPage(result.boxes, firstLine, pageEnd, bandTop, bandBottom).mapNotNull { r ->
            val argb = cssHexToArgb(r.colorHex) ?: return@mapNotNull null
            PageBackground(
                left = r.left,
                yTop = r.top,
                yBottom = r.bottom,
                right = r.right,
                argb = argb,
                border = r.kind == DrawKind.BORDER,
                radii = r.radii,
                alpha = r.alpha,
                shadow = r.shadow,
                strokeWidthPx = r.strokeWidthPx,
                bgSrc = r.bgImage?.url,
                bgChapterHref = chapterHref,
                bgRepeat = r.bgImage?.repeat ?: orilumn.reader.engine.css.BackgroundRepeat.REPEAT,
                bgPosition = r.bgImage?.position ?: orilumn.reader.engine.css.BackgroundPosition(),
                bgBoxTop = r.bgBoxTop,
                bgBoxBottom = r.bgBoxBottom,
            )
        }
    }
}