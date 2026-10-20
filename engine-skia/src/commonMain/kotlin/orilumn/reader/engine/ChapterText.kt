package orilumn.reader.engine

import orilumn.reader.engine.laying.NormalFlowLayout

/** 整章文本投影：文本本体 + 每个排版叶的累计块尾偏移（选区用来截断句子/英文词）。 */
internal class ChapterTextProjection(
    val text: String,
    val blockEnds: IntArray,
)

/**
 * 整章纯文本投影（D-18）：把排版叶按文档序拼成一条字符串。
 *
 * **与排版同源**：每个叶都走 [NormalFlowLayout.leafText]，并传入 prepare 阶段同一份
 * `styleMap` / `classify` / `hidden` / `genOf`——这正是绘制侧 `DrawLineBuilder` 取叶文本的入口，
 * 因此拼接后的字符偏移与 `PageSlice.charStart`、`DrawLine.charBase` 完全同尺。
 *
 * 返回值只覆盖**可排文字**：表格/替换元素不产字符（与 `leafText` 的空串一致），
 * 列表项目符号是装饰、不在字符流里。prepare 尚未就绪（未开章/已驱逐）时返回 null，
 * 调用方按「暂不可选」处理，不要拿空串冒充成功。
 *
 * 三条来源按「信息量」取：
 * 1. **重 prepare**（小章全排 / 磁盘命中路径）：整章 `styleMap`，与绘制逐字同源；
 * 2. **轻 prepare**（大章临时表在途）：`LightPrepare` 的懒级联与叶集，同样逐叶投影；
 * 3. 两者都没有 = 本章还没开过，返回 null。
 */
fun ChapterUnit.chapterText(): String? {
    chapterTextCache?.let { return it }
    val heavy = prepareResult
    if (heavy != null) {
        return heavy.chapterTextProjection().let { putTextProjection(it) }
    }
    val light = inProgress?.prepare ?: return null
    return light.chapterTextProjection().let { putTextProjection(it) }
}

/**
 * 整章文本的块尾偏移；与 [chapterText] 同时就绪。
 *
 * `[0, ends[0])` 是第一个排版叶，`[ends[0], ends[1])` 是第二个……选区只在本块内取整句/整词，
 * 不跨标题、列表项、段落（否则章节文本是连续拼接的，没有换行符也没有块分隔）。
 */
fun ChapterUnit.chapterTextBlockEnds(): IntArray? {
    chapterText()
    return chapterTextBlockEndsCache
}

/**
 * 大章临时表在途时的文本落盘：`buildLayout` 已经拿到 `LightPrepare`，顺手把整章文本算好，
 * 避免长按那一刻再扫整章；文本只与叶子/样式有关，参数变化由 `invalidateLayout` 统一清空。
 */
internal fun ChapterUnit.cacheChapterTextFrom(light: LightPrepare): String {
    chapterTextCache?.let { return it }
    return light.chapterTextProjection().let { putTextProjection(it) }
}

private fun ChapterUnit.putTextProjection(projection: ChapterTextProjection): String {
    chapterTextCache = projection.text
    chapterTextBlockEndsCache = projection.blockEnds
    return projection.text
}

private fun ChapterPrepareResult.chapterTextProjection(): ChapterTextProjection {
    val out = StringBuilder(totalChars.coerceAtLeast(0))
    val ends = IntArray(leaves.size)
    for ((i, leaf) in leaves.withIndex()) {
        out.append(
            NormalFlowLayout.leafText(
                el = leaf.el,
                styles = styleMap,
                classify = classify,
                hidden = hidden,
                genOf = genOf,
            ),
        )
        ends[i] = out.length
    }
    return ChapterTextProjection(out.toString(), ends)
}

/**
 * 轻 prepare 的文本投影：叶序与 [LightPrepare.globalCharStarts] 同一份结构；
 * 每个叶用 [LightPrepare.inlineStyles] 的懒级联（与塑形同入口），文本叶再补父节点样式
 * （重路径整章表里匿名文本叶会回查父容器，轻路径的 `inlineStyles` 对 `#text` 返回空表）。
 */
private fun LightPrepare.chapterTextProjection(): ChapterTextProjection {
    val out = StringBuilder(totalChars.coerceAtLeast(0))
    val ends = IntArray(totalBlocks)
    val classify = lightClassify()
    for (i in 0 until totalBlocks) {
        val el = leafElement(i)
        var styles = inlineStyles(i)
        if (el.isText) {
            val parent = el.parent
            if (parent != null && styles[parent] == null) {
                styles = styles + (parent to resolveStyle(parent))
            }
        }
        out.append(
            NormalFlowLayout.leafText(
                el = el,
                styles = styles,
                classify = classify,
                hidden = hidden,
                genOf = genOf,
            ),
        )
        ends[i] = out.length
    }
    return ChapterTextProjection(out.toString(), ends)
}
