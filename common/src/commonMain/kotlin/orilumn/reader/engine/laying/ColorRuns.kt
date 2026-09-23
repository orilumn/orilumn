package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.html.MarkupElement

/**
 * 该子树节点的有效 `white-space`：样式表命中即用；合成匿名叶等表外节点回父级；
 * 根块回退 [rootWs]（调用方喂叶块样式，无则 NORMAL）。
 */
fun wsOfNode(
    node: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    root: MarkupElement,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
): WhiteSpace =
    styles[node]?.whiteSpace
        ?: node.parent?.let { styles[it]?.whiteSpace }
        ?: styles[root]?.whiteSpace
        ?: rootWs

/**
 * 引擎层行内着色唯一核心：把一级联已有 `colorHex` 投影成叶文本坐标系的 [ColorRun]。
 *
 * 两条塑形路径（common 盒流 `absorbedText` / Android `ParagraphShapes.emitPlainText`）各有
 * 自己的文本构建（跳过规则不完全相同，不可合并），但着色遍历必须与**各自**的文本构建逐分支
 * 同构——否则 run 下标与字符错位。本函数收敛公共 mechanics（区间合并、颜色解析、
 * 合成匿名叶的父级回退），调用方只注入三处差异：
 *
 * @param root 叶子块元素（文本叶；合成匿名 `#text` 亦可）。
 * @param styles 该叶文本构建所用的同一份样式表（键须覆盖构建走过的节点；合成匿名叶不在
 *   表中时走父级回退，见下）。
 * @param isExcluded 该构建的“无内容”判定（common：`HiddenCheck`；Android：`displayNone`）。
 * @param isBlock 该构建的块判定（common：display 感知 `classify`；Android：`BLOCK_TAGS`）。
 *   `<img>` 判定两边一致（`tag == "img"`，占一位 U+FFFC），由本函数内联。
 * @param baseArgb 根块自身解析色（普通叶即其样式色，无影响；合成匿名叶的表为空时靠它兜底，
 *   Android 侧传 `rootStyle` 色）。
 */
fun collectColorRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    baseArgb: Int?,
    /** 根块回退 `white-space`（匿名叶等表外节点的归一化依据；默认 NORMAL）。 */
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    /** P3-c 生成内容查找（空即无，旧路径）。 */
    genOf: GenOf = EmptyGen,
    /** P3-c 样式回退（变换/引号依据；默认读 [styles] 表）。 */
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<ColorRun> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    fun argbOf(node: MarkupElement, fallback: Int?): Int? =
        styles[node]?.colorHex?.let(::cssHexToArgb) ?: fallback
    // P1-2: 与叶文本同一归一化段构建（下标恒对齐）；纯基底叶回空表。
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    if (root.isText) {
        // 合成匿名叶（排版中途新建，不在级联表里）取父块色，次选 baseArgb；
        // 归一化按容器（父级）white-space，整段收尾（与塑形侧同式）。
        val argb = argbOf(root, root.parent?.let(styles::get)?.colorHex?.let(::cssHexToArgb) ?: baseArgb)
            ?: return emptyList()
        val t = normalizeAnonymousRun(root.text, leafWs)
        return if (t.isEmpty()) emptyList() else listOf(ColorRun(0, t.length, argb))
    }
    val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, styleOf ?: { styles[it] }).segments
    val out = ArrayList<ColorRun>()
    var pos = 0
    fun emit(seg: StyledSegment, argb: Int?) {
        val n = seg.text.length
        if (n <= 0) return
        if (argb != null) {
            val last = out.lastOrNull()
            if (last != null && last.argb == argb && last.endExclusive == pos) {
                out[out.lastIndex] = last.copy(endExclusive = pos + n)
            } else {
                out.add(ColorRun(pos, pos + n, argb))
            }
        }
        pos += n
    }
    // 根块自身颜色即整段底色（普通叶与 styles[root] 一致，无影响；匿名叶靠 baseArgb）。
    val rootArgb = argbOf(root, null) ?: baseArgb
    // 逐节点继承色一次算好（与段构建同一跳过规则），段折叠时直接取用。
    val argbByNode = HashMap<MarkupElement, Int?>()
    fun fill(node: MarkupElement, inherited: Int?) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                c.isText -> argbByNode[c] = argbOf(c, inherited)
                c.tag == "br" -> Unit
                isBlock(c) -> Unit
                c.tag == "img" -> argbByNode[c] = argbOf(c, inherited)
                else -> {
                    val a = argbOf(c, inherited)
                    argbByNode[c] = a
                    fill(c, a)
                }
            }
        }
    }
    argbByNode[root] = rootArgb
    fill(root, rootArgb)
    // P3-c: 生成/引号段优先伪元素色（`::before` 继承原发元素，浏览器同式）。
    for (seg in segs) emit(seg, seg.genStyle?.colorHex?.let(::cssHexToArgb) ?: seg.node?.let { argbByNode[it] })
    return out
}

/**
 * 行内**字体** face 的唯一核心：把一级联已有字体字段投影成叶文本坐标系的 [FontRun]（浏览器
 * inline-run 语义，`<code>/<kbd>/<samp>/<tt>` 正文混排时按 UA 等宽、`<strong>/<em>` 加粗/斜体）。
 *
 * 与 [collectColorRuns] 同一套遍历骨架 —— 跳转规则（isExcluded / isBlock / `<img>` 占一位）与
 * 两条文本构建（common `absorbedText` / Android `emitPlainText`）逐分支同构，run 下标与字符永远
 * 对齐。只发「与叶子基底 face 不同」的段：纯种叶子不产生任何 run（绘制/减速零开销、与整叶
 * 单 face 路径逐字节一致）；匿名 `#text` 叶不在样式表时回父级基底。
 *
 * @param root 叶子块元素（文本叶；合成匿名 `#text` 亦可）。
 * @param styles 该叶文本构建所用的同一份样式表（[ComputedStyle] 已含继承，直接取字段即有效值）。
 * @param isExcluded / @param isBlock 同 [collectColorRuns]。
 * @param base 叶子的基底 face（= [root] 自身计算字体，等价 DrawLine 的 families/weight/italic/mono）。
 */
fun collectFontRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    base: FontRun,
    /** 根块回退 `white-space`（匿名叶等表外节点的归一化依据；默认 NORMAL）。 */
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    /** P3-c 生成内容查找（空即无，旧路径）。 */
    genOf: GenOf = EmptyGen,
    /** P3-c 样式回退（变换/引号依据；默认读 [styles] 表）。 */
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<FontRun> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    fun fontOf(node: MarkupElement, fallback: FontRun): FontRun =
        styles[node]?.let {
            FontRun(0, 0, it.fontFamilies, if (node.isText) node.parent?.tag else node.tag, it.fontWeight, it.italic, it.monospace, it.fontSizePx)
        } ?: fallback
    // P1-2: 与叶文本同一归一化段构建（下标恒对齐）。
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    if (root.isText) {
        // 合成匿名叶（排版中途新建，不在级联表里）回父基底；归一化按容器 white-space 整段收尾。
        val eff = fontOf(root, base)
        val t = normalizeAnonymousRun(root.text, leafWs)
        return if (t.isEmpty() || eff.fontEquals(base)) emptyList()
        else listOf(FontRun(0, t.length, eff.families, eff.tag, eff.weight, eff.italic, eff.monospace, eff.fontSizePx))
    }
    val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, styleOf ?: { styles[it] }).segments
    // 逐节点 face 一次算好（与段构建同一跳过规则），段折叠时直接取用。
    val fontByNode = HashMap<MarkupElement, FontRun>()
    fun fill(node: MarkupElement, inherited: FontRun) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                c.isText -> fontByNode[c] = fontOf(c, inherited)
                c.tag == "br" -> Unit
                isBlock(c) -> Unit
                c.tag == "img" -> Unit
                else -> {
                    val f = fontOf(c, inherited)
                    fontByNode[c] = f
                    fill(c, f)
                }
            }
        }
    }
    val rootFont = fontOf(root, base)
    fontByNode[root] = rootFont
    fill(root, rootFont)
    val out = ArrayList<FontRun>()
    var pos = 0
    fun emit(seg: StyledSegment, font: FontRun?) {
        val n = seg.text.length
        if (n <= 0) return
        if (font != null) {
            val last = out.lastOrNull()
            if (last != null && last.fontEquals(font) && last.endExclusive == pos) {
                out[out.lastIndex] = last.copy(endExclusive = pos + n)
            } else {
                out.add(FontRun(pos, pos + n, font.families, font.tag, font.weight, font.italic, font.monospace, font.fontSizePx))
            }
        }
        pos += n
    }
    for (seg in segs) {
        val node = seg.node
        // 标记段（br/img）永不分 face（断行用），与旧 walk 一致留空。
        // P3-c: 生成/引号段走伪元素 face（`q{font}` 引号同字体）；small-caps 段缩字号单列。
        val font = if (node == null || !node.isText) {
            seg.genStyle?.let { gs ->
                FontRun(0, 0, gs.fontFamilies, node?.tag, gs.fontWeight, gs.italic, gs.monospace, gs.fontSizePx)
                    .takeIf { !it.fontEquals(base) }
            }
        } else {
            fontByNode[node]?.let { f ->
                (if (seg.smallCaps) f.copy(fontSizePx = f.fontSizePx * GeneratedContent.SMALL_CAPS_SCALE) else f)
                    .takeIf { !it.fontEquals(base) }
            }
        }
        emit(seg, font)
    }
    return out
}

/** [FontRun] 的 face 相等（忽略 [FontRun.start]/[FontRun.endExclusive] 与 [FontRun.tag]）：run 与
     *  基底「渲染是否相同」只由解析 face（families/weight/italic/mono）与字号（fontSizePx）决定；
     *  tag 仅为解析提示（如 CODE_TAGS 的 mono 兜底），face 相同则该提示已无影响（落到同一绘制）。
     *  字号纳入比较：`span{font-size:1.2em}` 同 face 也必须单列成段，否则行内字号又被吞掉。 */
    private fun FontRun.fontEquals(o: FontRun): Boolean =
        families == o.families && weight == o.weight && italic == o.italic &&
            monospace == o.monospace && fontSizePx == o.fontSizePx
