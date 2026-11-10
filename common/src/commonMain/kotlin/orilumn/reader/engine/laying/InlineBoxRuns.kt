package orilumn.reader.engine.laying

import kotlinx.serialization.Serializable
import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.CornerRadius
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

/**
 * 一个行内元素的「行内盒」在其叶文本上的字符区间 `[start, endExclusive)`
 * （叶文本坐标系，与 [TextDecorationRun] / [orilumn.reader.engine.css.FontRun] 同源、同一次归一化段）。
 *
 * ## 浏览器语义（本 run 是它的几何载体）
 *
 * CSS 2.1 §8.6 / CSS Inline Layout：行内（非替换）元素在每个出现的行上生成一个**行内盒片段**。
 *  - **内容区**：高度 = 该元素**自身字体**的 em 盒（`ascent + descent`），**不是行高**；
 *  - **`padding` / `border`**：向外扩张。**纵向不撑高行框**（与相邻行重叠，浏览器如此），
 *    **横向推进后续文字**（参与断行宽度，见 1b）；
 *  - **背景**：铺满 **border-box**（默认 `background-clip: border-box`，含 padding 与 border 区）；
 *  - **四边边框**：`border-left` 只在元素**首个片段**、`border-right` 只在**末片段**，
 *    上/下边框在**每个片段**上（`box-decoration-break: slice` 默认）。
 *
 * ## 为什么单独成类（不塞进既有四个 run）
 *
 * 这是「行内盒」这一整块语义的几何载体：颜色/字体/基线/注音/下划线都只影响**字形**，
 * 唯独它要**画矩形**（背景 + 四条边框带）。故它携带的是**盒的几何参数**（padding/border/字体度量），
 * 而不是字形参数。
 *
 * ## 产出纪律
 *
 * **只对「有可见物或横向内外边距」的元素发 run**（有背景色 / 有边框宽 / 有水平 padding 或 margin）
 * ⇒ `<em>` / `<a>` 这类无装饰行内元素**零开销**。
 * 不参与 CSS 级联、不依赖 `text-decoration`（装饰走专用通道，见标注方案 D-2）。
 *
 * @property bgHex 背景色（`#rrggbb` / `#aarrggbb`，与 [ComputedStyle.colorHex] 同形）；null = 无。
 *   **`background-image` 在行内不绘制** —— 记录在案的缺口（见引擎欠账 §4.3）。
 * @property padding 内边距（px，已解 em/%）。
 * @property margin 外边距（px，已解 em/%；`auto` 对行内元素按 0 解）。**不绘制**（浏览器语义：
 *   背景与边框只到 border-box），但**横向推进后续文字**（参与断行宽度，与 [padding] 同一条推进规则）。
 *   纵向与 [padding] 同口径：不撑高行框。
 * @property border 四边边框宽度（px）；只画**宽 > 0 且 style 非 none** 的边。
 * @property borderColors 每边解析后的边框色（null 行值 = `currentColor`）；整体 null = 全未声明。
 * @property borderStyles 每边 `border-style`；null = 未声明（**按实线画**，与 [ComputedStyle.borderStyles]
 *   的「老书只有宽度也照样画」口径一致）。`dashed` / `dotted` 走与块盒**同一份**切段几何
 *   （[borderEdgeRects]，E16）。
 * @property colorHex 该元素自身的 `color`（`#rrggbb`）：边框 `currentColor` 的解析依据；
 *   null = 未声明 ⇒ 绘制侧回退到叶墨色（[orilumn.reader.engine.skia.DrawLine.inkColor]）。
 * @property borderRadius / borderRadiusPct 圆角（px / `%`，E17）：绘制侧按**本片段**的
 *   border-box 尺寸解（[CornerRadius.resolved]，与块盒同式钳制）；跨行片段按
 *   `box-decoration-break: slice` 只在**真角**上保留半径（切开端为 0）。
 * @property fontSizePx / families / weight / italic / monospace 该元素**自身字体**：
 *   决定 em 盒高度（绘制侧取 font metrics）与合成斜体/粗体判定。
 * @property visibilityHidden 该元素是否 `visibility: hidden`：**占位照旧、只不落墨**
 *   （与 `display:none` 相反）。横向推进量不受影响（隐藏元素的 padding/border 仍占宽，浏览器如此），
 *   只有绘制侧跳过本 run 的背景与边框。
 */
@Serializable
data class InlineBoxRun(
    val start: Int,
    val endExclusive: Int,
    val bgHex: String? = null,
    val padding: Edges = Edges(),
    val margin: Edges = Edges(),
    val border: Edges = Edges(),
    val borderColors: BorderColorEdges? = null,
    val borderStyles: BorderStyleEdges? = null,
    val colorHex: String? = null,
    val fontSizePx: Float = 0f,
    val families: List<String> = emptyList(),
    val weight: Int = 400,
    val italic: Boolean = false,
    val monospace: Boolean = false,
    val visibilityHidden: Boolean = false,
    /** E17 圆角（px）；`%` 部分在 [borderRadiusPct]。绘制侧按本片段 border-box 解。 */
    val borderRadius: CornerRadius = CornerRadius(),
    /** E17 圆角的百分比部分（对 `min(w, h)` 解，与 [CornerRadius.resolved] 同式）。 */
    val borderRadiusPct: CornerRadius = CornerRadius(),
    /**
     * E12 行内相对定位偏移（px，相对本 run 覆盖字符的**原始落墨位置**）。
     *
     * 取「本元素自身 + 其**行内**祖先（到叶块为止）」的累积值：块级祖先的偏移已由
     * [LayoutBox.relOffsetX]/[relOffsetY] 整体平移整行（见 [applyRelativeOffsets]），
     * 行内祖先（`<span>` 之类）不是盒，只能在这里按字符区间补 —— 两者相加才是 CSS 2.1
     * §9.4.3 的「原位置」平移。偏移**不改排版**（占位/断行/分页全不动），只平移落墨。
     */
    val relDx: Int = 0,
    val relDy: Int = 0,
) {
    /** 有边框宽（不看 style）：style 的 none 判定留给绘制侧（与 [ComputedStyle.hasBorderEdges] 同口径）。 */
    val hasBorderWidth: Boolean
        get() = border.top > 0f || border.right > 0f || border.bottom > 0f || border.left > 0f

    /** 这个 run 有没有东西要画（背景色或任一非零边框宽）。 */
    val visible: Boolean get() = bgHex != null || hasBorderWidth

    /**
     * 有没有**横向推进量**要记：可见装饰（背景/边框）或**水平内/外边距**（`padding` 与 `margin`
     * 不出现在画面上，但把后面的字推开 —— CSS 2.1 §8.6 行内盒横向参与行内排版）。
     *
     * 与 [visible] 分开：只有 margin 的 `<span style="margin:0 4px">` 要推开文字、却什么都不画；
     * 只有 padding 的 `<span style="padding:0 4px">` 同理（浏览器里它照样占位）。
     */
    val advancesHorizontally: Boolean
        get() = visible || padding.horizontal > 0f || margin.horizontal > 0f
}

/**
 * 行内盒单源（pure JVM）：把叶内**有可见装饰的行内元素**投影成叶文本坐标系的 [InlineBoxRun]，
 * 与叶文本同一归一化段构建（下标恒对齐，重/轻两路同源）。
 *
 * 骨架与 [collectTextDecorationRuns] **完全同构**（同一 `styledSegments`、同一 `isExcluded`/`isBlock`
 * 跳过规则、同一段折叠），差别只在「属性」与「聚合口径」：
 *
 *  - **下划线**是**继承型**属性：声明元素的后代全部带下划线 ⇒ 逐节点向下传播布尔。
 *  - **行内盒**是**盒型**属性：一个元素就是**一个盒**，盒的范围 = 该元素**子树贡献的全部字符**。
 *    故按段位置反查「本段落在哪些（带装饰的）祖先盒里」，逐盒累积 `[min, max)` 区间。
 *    嵌套盒（`<mark><em style=background>`）两边都发，绘制按「外先内后」叠。
 *
 * 跳转规则（与 `styledSegments` 同一套）：
 *  - `isExcluded`（`display:none`）子树不计；
 *  - `isBlock` **不越块边界** —— 块元素有自己的盒路径，绝不能在这里重复发 run；
 *  - `<br>` / `<img>` 各贡献一位字符（`\n` / U+FFFC），归属其**父元素**的盒。
 *
 * @param rootWs 叶块的 `white-space`（段归一化依据）。
 * @param genOf P3-c 生成内容查找（空即无，旧路径）。
 * @param precomputed 预计算段表（同参复用，`shapeGeometry` 一次分段多家共享；null 即现算）。
 * @param styleOf 样式回退（默认读 [styles] 表；轻路径可喂懒级联）。
 * @return 按文档序排序的 run 表（同起点时**外层盒在前**，即 `endExclusive` 大的在前，保证绘制时
 *   外层背景先落、内层后落）。
 */
fun collectInlineBoxRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    genOf: GenOf = EmptyGen,
    precomputed: List<StyledSegment>? = null,
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
    /** `::first-letter` 伪元素查找（null = 无该规则；`precomputed` 非空时直接吃它，现算才用）。 */
    firstLetterOf: FirstLetterOf? = null,
): List<InlineBoxRun> {
    // 表/图根、文本根：无行内盒可言（与 TextDecorationRuns 同口径早退）。
    if (root.tag == "table" || root.tag == "img") return emptyList()
    if (root.isText) return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = precomputed ?: styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve, firstLetterOf = firstLetterOf).segments
    if (segs.isEmpty()) return emptyList()

    // 每个「带装饰的行内元素」累积其子树贡献的字符区间 [min, maxExclusive)。
    val extents = HashMap<MarkupElement, IntArray>()
    var pos = 0
    for (seg in segs) {
        val n = seg.text.length
        if (n <= 0) continue
        // 本段归属的**最内层元素**：生成内容是「原发元素自己」，文本/br/img 是「父元素」。
        val owner = if (seg.genStyle != null) seg.node else seg.node?.parent
        if (owner != null) {
            var node: MarkupElement? = owner
            while (node != null && node !== root) {
                if (!isExcluded(node) && !isBlock(node)) {
                    val st = resolve(node)
                    if (st != null &&
                        (st.backgroundColorHex != null || st.hasBorderEdges() ||
                            st.padding.horizontal != 0f || st.margin.horizontal != 0f ||
                            !relativeSelfOffset(st).isZero)
                    ) {
                        val e = extents.getOrPut(node) { intArrayOf(pos, pos + n) }
                        if (pos < e[0]) e[0] = pos
                        if (pos + n > e[1]) e[1] = pos + n
                    }
                }
                node = node.parent
            }
        }
        pos += n
    }
    if (extents.isEmpty()) return emptyList()

    val out = ArrayList<InlineBoxRun>(extents.size)
    // E12：行内相对定位偏移 = 本元素 + 其**行内**祖先（到叶块为止）的自偏移之和。
    // 叶块自身及更上层块级祖先的偏移已由 [applyRelativeOffsets] 写进 `LayoutBox.relOffset*`
    // 整体平移整行（这里不再计一次，否则双算）。
    fun inlineOffsetOf(el: MarkupElement): BoxOffset {
        var acc = BoxOffset.ZERO
        var n: MarkupElement? = el
        while (n != null && n !== root) {
            if (!isExcluded(n) && !isBlock(n)) acc += relativeSelfOffset(resolve(n))
            n = n.parent
        }
        return acc
    }
    for ((el, e) in extents) {
        val st = resolve(el) ?: continue
        val off = inlineOffsetOf(el)
        out.add(
            InlineBoxRun(
                start = e[0],
                endExclusive = e[1],
                bgHex = st.backgroundColorHex,
                padding = st.padding,
                margin = st.margin,
                border = st.border,
                borderColors = st.borderColors,
                borderStyles = st.borderStyles,
                colorHex = st.colorHex,
                fontSizePx = st.fontSizePx,
                families = st.fontFamilies,
                weight = st.fontWeight,
                italic = st.italic,
                monospace = st.monospace,
                visibilityHidden = st.visibilityHidden,
                borderRadius = st.borderRadius,
                borderRadiusPct = st.borderRadiusPct,
                relDx = off.x,
                relDy = off.y,
            ),
        )
    }
    // 文档序；同起点时外层盒（end 大）在前 —— 绘制外层背景先落。
    out.sortWith(compareBy({ it.start }, { -it.endExclusive }))
    // 混排字距吃掉边界侧的 margin/padding（产品口径：间隙取代它们，有背景色的 padding 除外）。
    // ⚠ **只在这里改 run**：收集之后的一切消费方（[inlineBoxEdges] 的推进、断行器预留版心、
    //   绘制侧的背景/边框落墨、表格列宽测量）拿到的都是同一份，量画不会分叉。
    return eatMixedBoundarySides(StyledSegments(segs).text, out)
}

/**
 * 混排字距**吃掉**行内盒在 CJK↔Latin 边界那一侧的水平 `margin` / `padding`（产品口径 2026-11-10）。
 *
 * ## 一句话规则
 *
 * 中西接缝上本来就有间隙（[orilumn.reader.engine.text.preprocess.CjkLatinSpacing]），行内元素在这一侧
 * 再自带一段 `margin` / `padding` 就是**两份留白叠在一起** ⇒ 把那一侧的 margin/padding **归零**，
 * 由间隙取代（与「作者手打的分隔空格一律吃成零宽、只留注入的间隙」同一条产品口径）。
 *
 * **例外：有背景色的 `padding` 不吃。** 背景铺满 border-box（[InlineBoxRun.padding] 那一段也在其中），
 * 把 padding 归零等于把背景框掐窄到字形上，看起来就像「底色贴着字」—— 那种盒子的 padding 原样保留，
 * 间隙照旧画在框**外**（绘制侧由 `LineWindowDrawer.trailingCjkGapPx` 从 border-box 右缘扣掉）。
 * 「有没有背景色」按**盒自己或它的行内祖先**判：`<mark style="background">` 里面那个无背景的
 * `<span style="padding">` 的 padding **也是画在别人的底色上的**，同样不能吃。
 *
 * `border` 一律不吃：那是**看得见的墨**，不是留白。`margin` 一律吃：margin 不绘制，任何层级都不画。
 *
 * ## 判据：只吃「落在边界跨度里」的那一侧
 *
 * 边界跨度 = `[CjkLatinGap.leftIndex, leftIndex + spaceCount]`（与 [inlineBoxEdges] 的记账字位
 * 同一坐标系：盒的**左**侧记在 `start - 1`、**右**侧记在 `endExclusive - 1`）。两种接缝都覆盖：
 *
 * | 形状 | 盒的哪一侧被吃 | 记账字位 |
 * |---|---|---|
 * | `中<code>R</code>` | 右 / 左（两个盒各一侧） | `leftIndex`（= 左盒末字 = 右盒 `start - 1`） |
 * | `<code>中</code> R`（空格在盒外） | 右 | `leftIndex` |
 * | `<code>中 </code>R`（空格在盒内被吃） | 右 | 跨度包含盒末的空格字位 |
 * | `中 <code>R</code>`（空格在盒外） | 左 | 跨度含 `start - 1` |
 *
 * 盒**内部**的中西边界（`<code>中R</code>`）没有盒边落在跨度里 ⇒ 不动：盒是一个整体，盒内的
 * 中西分隔照旧由底色/边框圈着（那是盒自身的内容，见 `LineWindowDrawer.trailingCjkGapPx` 同一判据）。
 *
 * ## 为什么改 run 而不是只扣推进量
 *
 * 盒子是**一个整体**：padding 被吃的那一侧，背景 / 边框 / 圆角 / slice 片段必须与推进量**同时**变窄，
 * 否则「画 > 量」—— 盒边压到后文的字上（验收样书 V1 样例1.2 的老账，见 `inlineBoxEdges` KDoc）。
 * 改 run 之后，[inlineBoxEdges] 与 `LineWindowDrawer.paintInlineBoxes` 自然同源。
 *
 * ## 0 档照样吃
 *
 * 与「0 档照吃作者的空格」同一条：0 档只把**注入的间隙宽**压到 0，边界照检、该吃的照吃
 * （见 `CjkLatinSpacing` 类 KDoc「`gapEm = 0` 是参数为 0 的那一档」）。故这里按 0f 检测位置 ——
 * 吃的只是**边界在哪**，与档位无关。
 */
internal fun eatMixedBoundarySides(text: CharSequence, runs: List<InlineBoxRun>): List<InlineBoxRun> {
    if (runs.isEmpty() || text.isEmpty()) return runs
    val gaps = CjkLatinSpacing.gaps(text, 0f)
    if (gaps.isEmpty()) return runs

    /** [slot] 是否落在某个边界跨度 `[leftIndex, leftIndex + spaceCount]` 里（gaps 按 leftIndex 升序）。 */
    fun onBoundary(slot: Int): Boolean {
        var lo = 0
        var hi = gaps.size - 1
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (gaps[mid].leftIndex <= slot) {
                hit = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return hit >= 0 && slot <= gaps[hit].leftIndex + gaps[hit].spaceCount
    }

    /** 该盒那一侧的 padding **画在底色上**吗（盒自己或其行内祖先有背景色 ⇒ 不能吃）。 */
    fun padsOnPaintedBackground(r: InlineBoxRun): Boolean =
        runs.any { a ->
            a.start <= r.start && r.endExclusive <= a.endExclusive &&
                a.bgHex != null && !a.visibilityHidden
        }

    var changed = false
    val out = ArrayList<InlineBoxRun>(runs.size)
    for (r in runs) {
        val left = r.start > 0 && onBoundary(r.start - 1)
        val right = onBoundary(r.endExclusive - 1)
        if (!left && !right) {
            out.add(r)
            continue
        }
        val painted = padsOnPaintedBackground(r)
        out.add(
            r.copy(
                padding = Edges(
                    top = r.padding.top,
                    right = if (right && !painted) 0f else r.padding.right,
                    bottom = r.padding.bottom,
                    left = if (left && !painted) 0f else r.padding.left,
                ),
                margin = Edges(
                    top = r.margin.top,
                    right = if (right) 0f else r.margin.right,
                    bottom = r.margin.bottom,
                    left = if (left) 0f else r.margin.left,
                ),
            ),
        )
        changed = true
    }
    return if (changed) out else runs
}

/**
 * 行内盒横向内边距 / 边框折算出的**逐字位推进量**（px）。
 *
 * 记在 [index] 这个字位的 advance 上（`x_i = x_0 + Σ_{j<i} adv[j]`，故记在**边界左侧那个字位**
 * 才把右侧全部文字推开 —— 与 [orilumn.reader.engine.text.preprocess.CjkLatinGap] 同一施加规则）。
 *
 * [index] 在**当时被测量/落墨的文本坐标系**里；跨行/跨子串时先切（[sliceInlineBoxEdges]）再交下去。
 * 「盒起始于叶首字符」的左边内边距**不在本表内**（advance 数组表达不了「首字符之前」），
 * 由 [inlineBoxLeadInsetPx] 单列、折入首行缩进。
 */
data class InlineBoxEdge(val index: Int, val px: Float)

/**
 * 行内盒横向推进单源（pure JVM）：把叶文本坐标系的 [runs] 折算成逐字位推进量（[textLength] = 叶文本长度）。
 *
 * ## 两侧各算什么
 *
 *  - **左边** = `margin.left + padding.left + border.left` → 记在 `start - 1`（`start == 0` 时无处可记 ⇒ 走
 *    [inlineBoxLeadInsetPx]，**不在此表**，避免与首行缩进双算）；
 *  - **右边** = `margin.right + padding.right + border.right` → 记在 `endExclusive - 1`（末字符位)：既推开
 *    后续文字，也计入本行宽（末字符在行尾时体现为「行尾多出一段留白」）。
 *
 * `margin` 也在内：它**不画**（背景/边框到 border-box 为止），但横向推进后续文字（CSS 2.1 §8.6）。
 *
 * 横向边距为 0 的盒（`<mark>` 只有底色、`border-bottom` 只有下边框）**产出空表 ⇒ 零推进、像素与
 * 「只画」完全一致**：本机制只对显式设了行内横向内边距/边框/外边距的书生效。
 *
 * ⚠ **只认 `margin` + `padding` + `border` 宽度**：与绘制侧（`paintInlineBoxes`）同一组量，量画同源。
 * 只追加**要推进**的盒（[InlineBoxRun.advancesHorizontally]）。
 */
fun inlineBoxEdges(textLength: Int, runs: List<InlineBoxRun>): List<InlineBoxEdge> {
    if (runs.isEmpty()) return emptyList()
    val out = ArrayList<InlineBoxEdge>(runs.size * 2)
    for (r in runs) {
        if (!r.advancesHorizontally) continue
        val left = r.margin.left + r.padding.left + r.border.left
        if (left > 0f && r.start > 0 && r.start - 1 < textLength) out.add(InlineBoxEdge(r.start - 1, left))
        val right = r.margin.right + r.padding.right + r.border.right
        val last = r.endExclusive - 1
        if (right > 0f && last in 0 until textLength) out.add(InlineBoxEdge(last, right))
    }
    return out
}

/**
 * 行内盒横向外边距 + 内边距 + 边框的**总量**（px）：`shrink-to-fit` 首选宽度
 * （[ParagraphBreaker.preferredWidth]）的增量。与 [inlineBoxEdges] 逐字位之和 + [inlineBoxLeadInsetPx]
 * **恒等**（每条边各计一次），只是这里不需要位置、只要总量。
 */
fun inlineBoxInsetTotalPx(runs: List<InlineBoxRun>): Float {
    if (runs.isEmpty()) return 0f
    var sum = 0f
    for (r in runs) {
        if (!r.advancesHorizontally) continue
        sum += r.margin.left + r.padding.left + r.border.left +
            r.margin.right + r.padding.right + r.border.right
    }
    return sum
}

/**
 * [runs] 裁剪到 `[from, to)` 并平移到子串坐标系（[ParagraphBreaker.minContentWidth] 逐段度量用，
 * 与 `fontRunsWithin` 同口径）。
 *
 * 片段化的盒**照旧携带完整横向内边距**（不做「只有真起点才计左边」的细分）：min-content 是
 * 「不溢出所需的最小宽」⇒ 宁可**高估**也不低估，方向与既有「永不窄于实需」一致。
 */
fun sliceInlineBoxRuns(runs: List<InlineBoxRun>, from: Int, to: Int): List<InlineBoxRun> {
    if (runs.isEmpty()) return emptyList()
    val out = ArrayList<InlineBoxRun>(runs.size)
    for (r in runs) {
        val s = maxOf(r.start, from)
        val e = minOf(r.endExclusive, to)
        if (e <= s) continue
        out.add(r.copy(start = s - from, endExclusive = e - from))
    }
    return out
}

/**
 * **起始于叶首字符**（`start == 0`）的盒的左边外边距 + 内边距 + 边框之和（px）。
 *
 * 这一段几何上是「首行起始位移 + 首行可用宽减少」，正是 `text-indent` 的语义 ⇒ 折入首行缩进
 * （measure 侧 [breakLeafLines] / `breakWrappedLines`，落墨侧 `DrawLineBuilder` 的 `indent`，
 * **各一处、共用本函数**）。嵌套盒自然按「外+内」求和叠出正确层级。
 *
 * `margin.left` 与右边同理计入（见 [inlineBoxEdges]）：它把首字推开，却不画。
 */
fun inlineBoxLeadInsetPx(runs: List<InlineBoxRun>): Float {
    if (runs.isEmpty()) return 0f
    var sum = 0f
    for (r in runs) {
        if (r.advancesHorizontally && r.start == 0) sum += r.margin.left + r.padding.left + r.border.left
    }
    return sum
}

/**
 * 把叶坐标系的推进量切到 `[consumed, consumed + subLen)` 的子串坐标系（越界丢弃）。
 *
 * **切成 `slice` 语义是自动的**：盒的左推进量记在 `start - 1`，若盒从上一行/上一段延续过来，
 * 该字位落在切片之外 ⇒ 天然不施加 ⇒ 续片段没有左边框/内边距；右推进量同理只在末片段落在切片内时生效。
 */
fun sliceInlineBoxEdges(edges: List<InlineBoxEdge>, consumed: Int, subLen: Int): List<InlineBoxEdge> {
    if (edges.isEmpty()) return emptyList()
    val out = ArrayList<InlineBoxEdge>(edges.size)
    for (e in edges) {
        val i = e.index - consumed
        if (i in 0 until subLen) out.add(InlineBoxEdge(i, e.px))
    }
    return out
}
