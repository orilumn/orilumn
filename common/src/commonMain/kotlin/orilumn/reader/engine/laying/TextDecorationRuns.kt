package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.DecorationLine
import orilumn.reader.engine.css.DecorationStyle
import orilumn.reader.engine.css.TextDecoration
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement

/**
 * 一层装饰的**外观**（渲染层要画线所需的一切），在**声明它的那个元素**上解析好：
 *
 * - 装饰是**非继承但传播**的（CSS Text Decoration 3 的 "decorating box"）：后代文本被祖先的装饰
 *   覆盖，但后代的 `color` / 字号**不改**这层装饰的外观 —— 故这些参数在这里**固定下来**，
 *   而不是在绘制时按叶的样式取。
 * - 同一段文本可以叠**多层**（祖先一层 + 自身一层，例如 `<a>` 里套 `<s>`）：见
 *   [TextDecorationRun.paints]。
 *
 * @property lines 线型集合（非空；空即不发这一层）。
 * @property colorHex **已解析**的装饰色（`#rrggbb`）；null = 落回叶墨色
 *   （声明元素的祖先链上一个 `color` 都没声明时的 `currentColor`）。
 * @property style 线型样式。
 * @property thicknessPx 声明值（px）；null = `auto` / `from-font`，绘制侧按 [fontSizePx] 那张面的
 *   字体度量取（`FontMetrics.underlineThickness`）。
 * @property skipInk `text-decoration-skip-ink`：装饰线遇到字形墨迹处断开。
 * @property fontSizePx 声明元素**自身字号**：em 口径的装饰线位置/粗细与 `auto` 粗细的度量基准。
 * @property families / weight / italic / monospace 声明元素自身字体（取度量用）。
 */
data class DecorationPaint(
    val lines: Set<DecorationLine>,
    val colorHex: String?,
    val style: DecorationStyle,
    val thicknessPx: Float?,
    val skipInk: Boolean,
    val fontSizePx: Float,
    val families: List<String>,
    val weight: Int,
    val italic: Boolean,
    val monospace: Boolean,
)

/**
 * 叶文本坐标系上的一段文本装饰（[文本] 同一归一化段构建，下标恒对齐）。
 *
 * 与旧的 `UnderlineRun`（只有 `start/endExclusive`）的差别：携带**整摞外观**（[paints]），
 * 从而三种线型 / 颜色 / 粗细 / 线型样式 / skip-ink 都能表达。`paints` 为空即不发本 run
 * （无装饰叶回空表，绘制零开销）。
 */
data class TextDecorationRun(
    val start: Int,
    val endExclusive: Int,
    val paints: List<DecorationPaint>,
)

/**
 * 文本装饰单源（pure JVM）：把已级联的 `text-decoration-*` 投影成叶文本坐标系的
 * [TextDecorationRun]，与叶文本同一归一化段构建（下标恒对齐）。
 *
 * ## 语义（对齐浏览器）
 *
 * - `text-decoration` **非继承**：每个元素只贡献**自己声明的**那份；
 * - 但装饰**向后代传播**：段上的最终外观 = **祖先链上各层的叠加**（从根到最近的祖先，顺序即绘制顺序）。
 *   子元素写 `text-decoration: none` **不能**取消祖先的装饰（规范如此）。
 * - `currentColor`（[TextDecoration.colorHex] == null）在**这里**解析成**声明元素**的有效颜色
 *   （[ComputedStyle.colorHex] 本身含继承，正是 currentColor 的定义）—— 注意不是叶自身的颜色。
 *
 * ## 骨架
 *
 * 与 [collectInlineBoxRuns] 的旧遍历**同构**（同一 `styledSegments`、
 * 同一 `isExcluded`/`isBlock` 跳过规则），差别只在「按节点叠装饰栈」而非「累积区间」。
 *
 * ## 叶块以上的祖先链（E18）
 *
 * 本函数只**向下**走 `root` 子树，而块级子元素有自己的叶路径（`isBlock` 跳过）⇒
 * **祖先块**上声明的装饰进不了栈。规范里装饰沿**盒树**传播到**在流的**全部后代
 * （含后代块与混合内容里的匿名文本叶），所以这里先沿 `root` 向上把祖先那几层叠成底栈
 * （见 [ancestorDecorationStack]）：`<div style="text-decoration:underline"><p>abc</p></div>`
 * 与 `<a href><p>abc</p></a>` 的文字都该有线。
 * **出流祖先**（`float`）自己那层仍传给它的后代，但**其上**的装饰不再越过它传入
 * （规范：装饰不传播进浮动/绝对定位等出流后代）。
 *
 * @param rootWs 叶块的 `white-space`（段归一化依据）。
 * @param genOf P3-c 生成内容查找（空即无，旧路径）。`::before`/`::after` 是原发元素的子盒 ⇒
 *   祖先装饰照叠，伪元素自身再叠一层。
 * @param precomputed 预计算段表（同参复用；null 即现算）。
 * @param styleOf 样式回退（默认读 [styles] 表；轻路径可喂懒级联）。
 */
fun collectTextDecorationRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    genOf: GenOf = EmptyGen,
    precomputed: List<StyledSegment>? = null,
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<TextDecorationRun> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = precomputed ?: styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve).segments
    if (segs.isEmpty()) return emptyList()

    /** 本元素声明的那一层（无声明即 [inherited] 原样）。 */
    fun stackOf(el: MarkupElement?, inherited: List<DecorationPaint>): List<DecorationPaint> {
        val st = el?.let(resolve) ?: return inherited
        return withLayer(inherited, st)
    }
    // 逐节点算一次「装饰栈」（祖先 + 自身），段折叠时直接取用。
    val stackByNode = HashMap<MarkupElement, List<DecorationPaint>>()
    fun fill(node: MarkupElement, inherited: List<DecorationPaint>) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                // 同 `styledSegments`：`isBlock` 排在 `c.isText` 之前（块有自己的叶路径，不越界）。
                isBlock(c) -> Unit
                else -> {
                    val st = stackOf(c, inherited)
                    stackByNode[c] = st
                    if (!c.isText) fill(c, st)
                }
            }
        }
    }
    // 叶块以上的祖先链（E18）：装饰沿盒树传播过块边界，叶块自身那层照旧由 stackOf 叠在最后。
    val rootStack = stackOf(root, ancestorDecorationStack(root, isExcluded, resolve))
    stackByNode[root] = rootStack
    fill(root, rootStack)

    val out = ArrayList<TextDecorationRun>()
    var pos = 0
    for (seg in segs) {
        val n = seg.text.length
        if (n <= 0) continue
        val node = seg.node
        val paints: List<DecorationPaint> = when {
            // 生成内容：原发元素的装饰照叠，伪元素自身再叠一层。
            seg.genStyle != null -> {
                val host = node
                withLayer(host?.let { stackByNode[it] } ?: rootStack, seg.genStyle)
            }
            node != null -> stackByNode[node] ?: rootStack
            else -> rootStack
        }
        pos += n
        if (paints.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.endExclusive == pos - n && last.paints == paints) {
            out[out.lastIndex] = last.copy(endExclusive = pos)
        } else {
            out.add(TextDecorationRun(pos - n, pos, paints))
        }
    }
    return out
}

/** 把 [st] 自己声明的装饰叠到 [inherited] 上（无声明原样返回，绝不分配新列表）。 */
private fun withLayer(inherited: List<DecorationPaint>, st: ComputedStyle): List<DecorationPaint> {
    val d = st.decoration
    if (!d.hasLines) return inherited
    val layer = DecorationPaint(
        lines = d.lines,
        // currentColor 在这里解析：`ComputedStyle.colorHex` 已含继承 ⇒ 正是声明元素的有效颜色。
        colorHex = d.colorHex ?: st.colorHex,
        style = d.style,
        thicknessPx = d.thicknessPx,
        skipInk = d.skipInk,
        fontSizePx = st.fontSizePx,
        families = st.fontFamilies,
        weight = st.fontWeight,
        italic = st.italic,
        monospace = st.monospace,
    )
    if (inherited.isEmpty()) return listOf(layer)
    return inherited + layer
}

/** 装饰线型集合的便捷判据（绘制/测试用）。 */
fun TextDecorationRun.hasLine(line: DecorationLine): Boolean = paints.any { line in it.lines }

/**
 * 取叶块 [root] 向上直到文档根的祖先链，按从根到离 [root] 最近的顺序叠装饰栈。
 *
 * 浏览器语义：`text-decoration` **非继承但传播**，且传播穿过**块**边界到在流后代（包括后代块）。
 * 这里要把祖先块上声明的装饰层也带进叶块的底栈。注意：传播**不**穿过 out-of-flow（float/absolute/fixed）
 * —— 当前计算值里没有显式标记 `position:absolute/fixed`，float 也暂未出现在这个口径里。
 */
private fun ancestorDecorationStack(
    root: MarkupElement,
    isExcluded: (MarkupElement) -> Boolean,
    resolve: (MarkupElement) -> ComputedStyle?,
): List<DecorationPaint> {
    val chain = ArrayList<MarkupElement>()
    var cur: MarkupElement? = root.parent
    while (cur != null && cur.tag != "#document" && cur.tag != "html" && cur.tag != "body") {
        chain.add(cur)
        cur = cur.parent
    }
    chain.reverse()
    var stack: List<DecorationPaint> = emptyList()
    for (el in chain) {
        if (isExcluded(el)) continue
        val st = resolve(el) ?: continue
        val d = st.decoration
        if (!d.hasLines) continue
        val layer = DecorationPaint(
            lines = d.lines,
            colorHex = d.colorHex ?: st.colorHex,
            style = d.style,
            thicknessPx = d.thicknessPx,
            skipInk = d.skipInk,
            fontSizePx = st.fontSizePx,
            families = st.fontFamilies,
            weight = st.fontWeight,
            italic = st.italic,
            monospace = st.monospace,
        )
        stack = if (stack.isEmpty()) listOf(layer) else stack + layer
    }
    return stack
}

