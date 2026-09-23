package orilumn.reader.engine.layout

import android.text.TextPaint
import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.breakWrappedLines
import orilumn.reader.engine.laying.normalizeAnonymousRun
import orilumn.reader.engine.laying.styledSegments
import orilumn.reader.engine.laying.wsOfNode
import orilumn.reader.engine.skia.SkiaParagraphBreaker
import orilumn.reader.engine.text.TypographicProfile

/**
 * Builds a [ParagraphShape] from a leaf block's markup + cascade styles.
 *
 * C1-0: line breaking is the shared skia breaker ([SkiaParagraphBreaker] — the same
 * single source the canonical box flow breaks with). The paragraph text is the block's
 * absorbed inline/text descendants ([emitPlainText], same char stream the old StaticLayout
 * path shaped: `display:none` skipped, `<br>` → newline, inline `<img>` → U+FFFC
 * placeholder, nested blocks excluded). Every line's height is the CSS line box reported
 * by the breaker (uniform across first/interior/last), so geometry, pagination and the
 * painted glyph pitch stay in phase.
 *
 * What this deliberately does NOT do (vs the retired StaticLayout path):
 *  - per-run inline faces (code/mono spans, bold/italic synthesis, color/underline spans,
 *    `LeadingMarginSpan` indent, `ImageSpan` bitmaps) never influence breaks; the whole
 *    paragraph breaks under its block root style — exactly like the canonical box flow.
 *    Run styling remains the drawing layer's job ([orilumn.reader.engine.skia.DrawLine] carries
 *    the full CSS run that [orilumn.reader.engine.skia.LineWindowDrawer] shapes per line).
 *  - a [TextPaint] is still built (block root size + reader letter-spacing + pooled face)
 *    but only feeds the Android-canvas fallback drawing ([ParagraphShape.drawPaint]);
 *    it never measures.
 *
 * Block-level img (explicit `display:block`) is handled by the replaceable-leaf branch
 * before any text shaping happens. Inline `<img>` contributes a U+FFFC slot (the box
 * flow sizes the real geometry via [NormalFlowLayout.replacedUsedSize]); the breaker
 * measures the slot as a regular glyph — a known approximation shared with the desktop
 * geometry-only host.
 */
object ParagraphShapes {

    /**
     * @param el the leaf block element (whose descendants provide the paragraph text).
     * @param rootStyle the block's own computed style (base size, line height, alignment).
     * @param styles per-node computed styles for [el]'s text leaves (display:none gate).
     * @param profile reader profile (letter-spacing for the breaker + base paint color).
     * @param widthPx the block's line-breaking content width (px).
     * @param pairing the reader's font pool (draw-paint face only; never measures).
     * @param listMarker optional list marker overlay (gutter only, never in the text).
     * @param imageLoader unused (kept for call-site stability; inline images are placeholders).
     * @param chapterHref unused (kept for call-site stability).
     */
    fun shapeOf(
        el: MarkupElement,
        rootStyle: ComputedStyle,
        styles: Map<MarkupElement, ComputedStyle>,
        profile: TypographicProfile,
        widthPx: Int,
        pairing: orilumn.reader.engine.text.FontPool = orilumn.reader.engine.text.FontPool(),
        listMarker: ListMarkers.ListMarker? = null,
        imageLoader: ImageLoader? = null,
        chapterHref: String = "",
        /**
         * P3-a: 祖先样式回退（轻路径内联表只含子树，容器祖先 opacity 经此补；
         * 缺省即整表，匿名叶按根块回退）。
         */
        ancestorStyleOf: ((MarkupElement) -> ComputedStyle?)? = null,
        /** P3-c 生成内容查找（空即无；调用方喂与塑形同一 phase-1 结果）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
        /**
         * P4-a3 悬浮环绕前导（null = 无环绕旧路径；调用方经 `LightPrepare.floatLeadAt`
         * 与重路径同源，断行逐字节一致）。
         */
        floatLead: orilumn.reader.engine.laying.FloatLead? = null,
    ): ParagraphShape {
        // C2-P2b-3: 几何走共享单源（`shapeGeometry`，等价单测锁定）；这里只加画笔包装。
        val geo = orilumn.reader.engine.skia.shapeGeometry(
            el, rootStyle, styles, profile, widthPx, listMarker,
            imageLoader, chapterHref, ancestorStyleOf, genOf, floatLead,
        ) { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS }
        if (geo.isReplaceable) {
            return ParagraphShape(
                isReplaceable = true,
                replaceableBottom = geo.replaceableBottom,
                replaceableCharEnd = geo.replaceableCharEnd,
                fontRequest = geo.fontRequest,
            )
        }
        // An anonymous text leaf belongs to its container block, so pair it with the parent's tag
        // (e.g. "入门指南" inside <h2> uses the heading font).
        val pairTag = if (el.tag == "#text") el.parent?.tag else el.tag
        // Base paint for canvas fallback drawing only (table cells / list markers): block root
        // size + reader letter-spacing + the pooled face. Never measures.
        val baseTf = pairing.resolve(pairTag, rootStyle.fontFamilies, rootStyle.fontWeight, rootStyle.italic, rootStyle.monospace)
        val paint = TextPaint().apply {
            textSize = rootStyle.fontSizePx.coerceAtLeast(1f)
            color = profile.fgColor
            this.typeface = baseTf
            letterSpacing = profile.letterSpacingEm
        }
        return ParagraphShape(
            text = geo.text,
            lineRanges = geo.lineRanges,
            lineHeights = geo.lineHeights,
            listMarker = listMarker,
            drawPaint = paint,
            alignment = geo.alignment,
            colorRuns = geo.colorRuns,
            fontRuns = geo.fontRuns,
            baselineShifts = geo.baselineShifts,
            textShadow = geo.textShadow,
            emphasis = geo.emphasis,
            emphasisUnder = geo.emphasisUnder,
            alpha = geo.alpha,
            rubyRuns = geo.rubyRuns,
            underlineRuns = geo.underlineRuns,
            fontRequest = geo.fontRequest,
        )
    }

    /**
     * [emitPlainText] 的着色伴生：引擎单源委托 [orilumn.reader.engine.laying.collectColorRuns]
     *（与 [emitPlainText] 同一套跳过规则：`displayNone` 门、`BLOCK_TAGS` 块判定）。
     * 调用方必须传与塑形同一份 [styles]。
     *
     * @param baseArgb 根块自身解析色（[shapeOf] 的 `rootStyle`）：合成匿名叶的 styles 表
     *   为空（连父级都查不到），靠它兜底；普通叶与表内色一致，无影响。
     */
    fun colorRunsOf(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        baseArgb: Int? = null,
        /** 根块回退 white-space（匿名叶归一化依据；默认 NORMAL）。 */
        rootWs: orilumn.reader.engine.css.WhiteSpace = orilumn.reader.engine.css.WhiteSpace.NORMAL,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): List<ColorRun> = orilumn.reader.engine.laying.collectColorRuns(
        el,
        styles,
        { styles[it]?.displayNone == true },
        { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS },
        baseArgb,
        rootWs,
        genOf,
    )

    /**
     * [emitPlainText] 的**字体**伴生：与 [colorRunsOf] 同一遍历骨架的 [orilumn.reader.engine.laying.collectFontRuns]，
     * 产出与 [text] 恒对齐的行内 face 段（`<code>/<kbd>/<samp>/<tt>` 按 UA 等宽、`<strong>/<em>` 加粗/斜体）。
     * 只发「与块自身 face 不同」的段——纯种叶回空表，断行/绘制零开销。调用方必须传与塑形同一份 [styles]。
     *
     * @param base 块自身计算字体（[shapeOf] 的 `rootStyle`）：段与之比对，等价 DrawLine 的
     *   families/weight/italic/mono 基底；合成匿名叶不在样式表时也靠它兜底。
     */
    fun fontRunsOf(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        base: ComputedStyle,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): List<orilumn.reader.engine.css.FontRun> = orilumn.reader.engine.laying.collectFontRuns(
        el,
        styles,
        { styles[it]?.displayNone == true },
        { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS },
        orilumn.reader.engine.css.FontRun(
            0, 0,
            base.fontFamilies,
            if (el.isText) el.parent?.tag else el.tag,
            base.fontWeight, base.italic, base.monospace,
            base.fontSizePx,
        ),
        base.whiteSpace,
        genOf,
    )

    /**
     * [emitPlainText] 的基线位移伴生：与 [colorRunsOf]/[fontRunsOf] 同一遍历骨架的
     * [orilumn.reader.engine.laying.collectBaselineShifts]，产出与 [text] 恒对齐的位移区间。
     * 只发非基线段——纯基线叶回空表，断行/绘制零开销。
     */
    fun baselineShiftsOf(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        base: ComputedStyle,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): List<orilumn.reader.engine.laying.BaselineShift> = orilumn.reader.engine.laying.collectBaselineShifts(
        el,
        styles,
        { styles[it]?.displayNone == true },
        { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS },
        base.whiteSpace,
        genOf,
    )

    /**
     * [emitPlainText] 的叠排注音伴生：与 [baselineShiftsOf] 同一遍历骨架的
     * [orilumn.reader.engine.laying.collectRubyRuns]，产出与 [text] 恒对齐的注音 runs。
     * 无注音叶回空表，行高/绘制零回归。轻路径喂内联子树表（后代全在表内）。
     */
    fun rubyRunsOf(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        base: ComputedStyle,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): List<orilumn.reader.engine.laying.RubyRun> = orilumn.reader.engine.laying.collectRubyRuns(
        el,
        styles,
        { styles[it]?.displayNone == true },
        { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS },
        base.whiteSpace,
        genOf,
    )

    /**
     * [emitPlainText] 的下划线伴生：与 [baselineShiftsOf] 同一遍历骨架的
     * [orilumn.reader.engine.laying.collectUnderlineRuns]，产出与 [text] 恒对齐的下划线区间
     * （声明元素整段向后代传播）。无下划线叶回空表，绘制零回归。
     */
    fun underlineRunsOf(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        base: ComputedStyle,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    ): List<orilumn.reader.engine.laying.UnderlineRun> = orilumn.reader.engine.laying.collectUnderlineRuns(
        el,
        styles,
        { styles[it]?.displayNone == true },
        { it.tag in orilumn.reader.engine.layout.CssLayouter.BLOCK_TAGS },
        base.whiteSpace,
        genOf,
    )
}
