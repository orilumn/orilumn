package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement

/**
 * 一个行内元素的「行内盒」在其叶文本上的字符区间 `[start, endExclusive)`
 * （叶文本坐标系，与 [UnderlineRun] / [orilumn.reader.engine.css.FontRun] 同源、同一次归一化段）。
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
 * **只对有可见物的元素发 run**（有背景色或有边框宽）⇒ `<em>` / `<a>` 这类无装饰行内元素**零开销**。
 * 不参与 CSS 级联、不依赖 `text-decoration`（装饰走专用通道，见标注方案 D-2）。
 *
 * @property bgHex 背景色（`#rrggbb` / `#aarrggbb`，与 [ComputedStyle.colorHex] 同形）；null = 无。
 *   **`background-image` 在行内不绘制** —— 记录在案的缺口（见引擎欠账 §4.3）。
 * @property padding 内边距（px，已解 em/%）。
 * @property border 四边边框宽度（px）；只画**宽 > 0 且 style 非 none** 的边。
 * @property borderColors 每边解析后的边框色（null 行值 = `currentColor`）；整体 null = 全未声明。
 * @property borderStyles 每边 `border-style`；null = 未声明（**按实线画**，与 [ComputedStyle.borderStyles]
 *   的「老书只有宽度也照样画」口径一致）。**v1 一律实线**：`dashed` / `dotted` 暂按实线画
 *   （与块盒的差距记录在案）。
 * @property colorHex 该元素自身的 `color`（`#rrggbb`）：边框 `currentColor` 的解析依据；
 *   null = 未声明 ⇒ 绘制侧回退到叶墨色（[orilumn.reader.engine.skia.DrawLine.inkColor]）。
 * @property fontSizePx / families / weight / italic / monospace 该元素**自身字体**：
 *   决定 em 盒高度（绘制侧取 font metrics）与合成斜体/粗体判定。
 */
data class InlineBoxRun(
    val start: Int,
    val endExclusive: Int,
    val bgHex: String? = null,
    val padding: Edges = Edges(),
    val border: Edges = Edges(),
    val borderColors: BorderColorEdges? = null,
    val borderStyles: BorderStyleEdges? = null,
    val colorHex: String? = null,
    val fontSizePx: Float = 0f,
    val families: List<String> = emptyList(),
    val weight: Int = 400,
    val italic: Boolean = false,
    val monospace: Boolean = false,
) {
    /** 有边框宽（不看 style）：style 的 none 判定留给绘制侧（与 [ComputedStyle.hasBorderEdges] 同口径）。 */
    val hasBorderWidth: Boolean
        get() = border.top > 0f || border.right > 0f || border.bottom > 0f || border.left > 0f

    /** 这个 run 有没有东西要画（背景色或任一非零边框宽）。 */
    val visible: Boolean get() = bgHex != null || hasBorderWidth
}

/**
 * 行内盒单源（pure JVM）：把叶内**有可见装饰的行内元素**投影成叶文本坐标系的 [InlineBoxRun]，
 * 与叶文本同一归一化段构建（下标恒对齐，重/轻两路同源）。
 *
 * 骨架与 [collectUnderlineRuns] **完全同构**（同一 `styledSegments`、同一 `isExcluded`/`isBlock`
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
): List<InlineBoxRun> {
    // 表/图根、文本根：无行内盒可言（与 UnderlineRuns 同口径早退）。
    if (root.tag == "table" || root.tag == "img") return emptyList()
    if (root.isText) return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = precomputed ?: styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve).segments
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
                    if (st != null && (st.backgroundColorHex != null || st.hasBorderEdges())) {
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
    for ((el, e) in extents) {
        val st = resolve(el) ?: continue
        out.add(
            InlineBoxRun(
                start = e[0],
                endExclusive = e[1],
                bgHex = st.backgroundColorHex,
                padding = st.padding,
                border = st.border,
                borderColors = st.borderColors,
                borderStyles = st.borderStyles,
                colorHex = st.colorHex,
                fontSizePx = st.fontSizePx,
                families = st.fontFamilies,
                weight = st.fontWeight,
                italic = st.italic,
                monospace = st.monospace,
            ),
        )
    }
    // 文档序；同起点时外层盒（end 大）在前 —— 绘制外层背景先落。
    out.sortWith(compareBy({ it.start }, { -it.endExclusive }))
    return out
}
