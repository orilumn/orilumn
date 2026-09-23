package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BaselineShift
import orilumn.reader.engine.laying.EmptyGen
import orilumn.reader.engine.laying.FloatLead
import orilumn.reader.engine.laying.GenOf
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.RubyRun
import orilumn.reader.engine.laying.ShapeFontRequest
import orilumn.reader.engine.laying.ShapedGeometry
import orilumn.reader.engine.laying.UnderlineRun
import orilumn.reader.engine.laying.adjustLineHeightsForRuby
import orilumn.reader.engine.laying.breakWrappedLines
import orilumn.reader.engine.laying.collectBaselineShifts
import orilumn.reader.engine.laying.collectColorRuns
import orilumn.reader.engine.laying.collectFontRuns
import orilumn.reader.engine.laying.collectRubyRuns
import orilumn.reader.engine.laying.collectUnderlineRuns
import orilumn.reader.engine.laying.effectiveOpacity
import orilumn.reader.engine.laying.normalizeAnonymousRun
import orilumn.reader.engine.laying.styledSegments
import orilumn.reader.engine.laying.wsOfNode
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.text.TypographicProfile

/**
 * C2-P2b-2: 段落纯几何整形（无画笔、无字体池），[ParagraphShapes.shapeOf] 的几何单源。
 *
 * 本函数即 `:app` `shapeOf` 的几何体**原样搬运**：同样的文本抽取、同样的伴生 runs、
 * 同一个 `SkiaParagraphBreaker` 断行、同样的注音行高增量 —— 只是不建 `TextPaint`、
 * 不碰 `FontPool`（KDoc 自证：pairing 只画不量）。`:app` 的 `shapeOf` 退化成
 * "本函数 + 画笔包装"，桌面直接调本函数：两端几何逐字节同源（等价单测锁定）。
 *
 * @param isBlock 块判定（`:app` 传 `CssLayouter.BLOCK_TAGS`，桌面传同规则；抽参是因为
 *   `CssLayouter` 是 Android 绘制桥，搬不动）。
 */
fun shapeGeometry(
    el: MarkupElement,
    rootStyle: ComputedStyle,
    styles: Map<MarkupElement, ComputedStyle>,
    profile: TypographicProfile,
    widthPx: Int,
    listMarker: ListMarkers.ListMarker? = null,
    imageLoader: ImageLoader? = null,
    chapterHref: String = "",
    ancestorStyleOf: ((MarkupElement) -> ComputedStyle?)? = null,
    genOf: GenOf = EmptyGen,
    floatLead: FloatLead? = null,
    isBlock: (MarkupElement) -> Boolean,
): ShapedGeometry {
    // Replaceable (img) leaf: no text to shape — a synthetic single line whose height is the
    // resolved image height. The box flow already sized the geo; this shape only reports it.
    // Only reached for block-level img (CSS display:block); inline img never becomes its own leaf.
    if (el.tag == "img") {
        return ShapedGeometry(
            isReplaceable = true,
            replaceableBottom = NormalFlowLayout.replaceableHeightOf(el, rootStyle, widthPx, imageLoader, chapterHref),
        )
    }
    // An anonymous text leaf belongs to its container block, so pair it with the parent's tag
    // (e.g. "入门指南" inside <h2> uses the heading font).
    val pairTag = if (el.tag == "#text") el.parent?.tag else el.tag
    // 取画笔请求（只装配不消费：`:app` 包装/未来卷曲凭它取 face，桌面忽略）。
    val fontRequest = ShapeFontRequest(
        pairTag, rootStyle.fontFamilies, rootStyle.fontWeight, rootStyle.italic, rootStyle.monospace,
    )
    val sb = StringBuilder()
    emitPlainText(el, styles, sb, rootStyle, genOf, isBlock)
    val text = sb.toString()
    if (text.isEmpty()) {
        return ShapedGeometry(
            text = "",
            alignment = rootStyle.textAlign,
            fontSizePx = rootStyle.fontSizePx.coerceAtLeast(1f),
            listMarker = listMarker,
            fontRequest = fontRequest,
        )
    }
    // 行内 face 段（`<code>`/`<strong>` 等）：与着色同构的同一遍历产出，断行与绘制按它整形。
    val fontRuns = fontRunsOf(el, styles, rootStyle, genOf, isBlock)
    // P1-2: 行内基线位移段（sub/sup 等）：不断行几何，随段整形使量画一致。
    val baselineShifts = baselineShiftsOf(el, styles, rootStyle, genOf, isBlock)
    // P1-2: white-space 断行单源（NOWRAP/PRE 不换行；与盒流同式）。
    // Whole-paragraph single-style break (canonical semantics): code-like mono 解析走
    // 级联 monospace 标志（与盒流 `style.monospace || tag == "pre"` 同式）。
    val broken = breakWrappedLines(
        SkiaParagraphBreaker(profile.letterSpacingEm), text, rootStyle, widthPx.coerceAtLeast(1),
        floatLead, pairTag, fontRuns, rootStyle.textIndentPx.coerceAtLeast(0f), baselineShifts,
    )
    // P6-b: 叠排注音 runs（与 text 同构遍历；无注音回空表零回归）＋行高增量（与重路径同式）。
    val rubyRuns = rubyRunsOf(el, styles, rootStyle, genOf, isBlock)
    val grownHeights = adjustLineHeightsForRuby(broken, broken.map { it.heightPx }, rubyRuns)
    // Whole-paragraph single-style break (canonical semantics): code-like blocks resolve mono
    // exactly like the skia font stack ([SkParagraphFactory] CODE_TAGS rule).
    return ShapedGeometry(
        text = text,
        lineRanges = broken.map { it.range },
        lineHeights = grownHeights,
        listMarker = listMarker,
        alignment = rootStyle.textAlign,
        fontSizePx = rootStyle.fontSizePx.coerceAtLeast(1f),
        colorRuns = colorRunsOf(el, styles, rootStyle.colorHex?.let(::cssHexToArgb), rootStyle.whiteSpace, genOf, isBlock),
        fontRuns = fontRuns,
        baselineShifts = baselineShifts,
        // P3-a: 行阴影（currentColor 按块墨色解）＋着重号＋祖先 opacity（回退绘制同式）。
        // 手工 hex（`"#%08x".format` 是 JVM-only）：负数按补码 8 位，与 format 同串。
        textShadow = rootStyle.textShadow?.let { sh ->
            val argb = sh.colorHex?.let(::cssHexToArgb)
                ?: rootStyle.colorHex?.let(::cssHexToArgb) ?: profile.fgColor
            orilumn.reader.engine.css.TextShadow(sh.dx, sh.dy, sh.blur, "#" + argb.toUInt().toString(16).padStart(8, '0'))
        },
        emphasis = rootStyle.emphasisStyle,
        emphasisUnder = rootStyle.emphasisUnder,
        // 样式表命中即用；表外（匿名叶/内联表未覆盖的祖先）按根块→祖先回退。
        alpha = effectiveOpacity(el) { styles[it] ?: ancestorStyleOf?.invoke(it) ?: rootStyle },
        rubyRuns = rubyRuns,
        // 下划线区间（与 text 同构遍历；无下划线回空表零回归）。
        underlineRuns = underlineRunsOf(el, styles, rootStyle, genOf, isBlock),
        fontRequest = fontRequest,
    )
}

/** `shapeOf` 的文本抽取（同跳过规则；块判定由调用方经 [isBlock] 喂）。 */
fun emitPlainText(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    sb: StringBuilder,
    rootStyle: ComputedStyle,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
) {
    if (el.isText) {
        sb.append(normalizeAnonymousRun(el.text, rootStyle.whiteSpace))
        return
    }
    sb.append(
        styledSegments(
            el,
            { n -> wsOfNode(n, styles, el, rootStyle.whiteSpace) },
            { styles[it]?.displayNone == true },
            { isBlock(it) },
            styles[el]?.whiteSpace ?: rootStyle.whiteSpace,
            genOf,
            { styles[it] },
        ).text,
    )
}

/** 着色伴生（与文本抽取同一遍历骨架）。 */
fun colorRunsOf(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    baseArgb: Int? = null,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
) = collectColorRuns(
    el,
    styles,
    { styles[it]?.displayNone == true },
    { isBlock(it) },
    baseArgb,
    rootWs,
    genOf,
)

/** 字体伴生（只发与块自身 face 不同的段）。 */
fun fontRunsOf(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    base: ComputedStyle,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
) = collectFontRuns(
    el,
    styles,
    { styles[it]?.displayNone == true },
    { isBlock(it) },
    FontRun(
        0, 0,
        base.fontFamilies,
        if (el.isText) el.parent?.tag else el.tag,
        base.fontWeight, base.italic, base.monospace,
        base.fontSizePx,
    ),
    base.whiteSpace,
    genOf,
)

/** 基线位移伴生（只发非基线段）。 */
fun baselineShiftsOf(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    base: ComputedStyle,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
): List<BaselineShift> = collectBaselineShifts(
    el,
    styles,
    { styles[it]?.displayNone == true },
    { isBlock(it) },
    base.whiteSpace,
    genOf,
)

/** 叠排注音伴生（无注音回空表）。 */
fun rubyRunsOf(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    base: ComputedStyle,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
) = collectRubyRuns(
    el,
    styles,
    { styles[it]?.displayNone == true },
    { isBlock(it) },
    base.whiteSpace,
    genOf,
)

/** 下划线伴生（无下划线回空表）。 */
fun underlineRunsOf(
    el: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    base: ComputedStyle,
    genOf: GenOf = EmptyGen,
    isBlock: (MarkupElement) -> Boolean,
) = collectUnderlineRuns(
    el,
    styles,
    { styles[it]?.displayNone == true },
    { isBlock(it) },
    base.whiteSpace,
    genOf,
)
