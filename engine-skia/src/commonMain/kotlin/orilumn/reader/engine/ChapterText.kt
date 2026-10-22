package orilumn.reader.engine

import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.io.Logger

/** 整章文本投影：文本本体 + 每个排版叶的累计块尾偏移（选区用来截断句子/英文词）。 */
internal class ChapterTextProjection(
    val text: String,
    val blockEnds: IntArray,
)

/**
 * 整章纯文本投影（D-18）：把排版叶按文档序拼成一条字符串。
 *
 * **与排版同尺**：每个叶都走 [NormalFlowLayout.leafStreamText]（[NormalFlowLayout.leafText] +
 * 替换叶/表格行叶的字符槽），并传入 prepare 阶段同一份 `styleMap` / `classify` / `hidden` /
 * `genOf`。因此拼接后的字符偏移与 `PageSlice.charStart`、`DrawLine.charBase` 完全同尺——
 * 长按点按拿到的偏移能直接在这条串上取整句 / 整词。
 *
 * 漏掉「替换叶占 1 格」「表格行占格内文本」会让其后每个字都错位（真机「长按乱选」的根因）；
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
    if (heavy != null) return putTextProjection(heavy.chapterTextProjection())
    val light = inProgress?.prepare ?: return null
    return putTextProjection(light.chapterTextProjection())
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
    return putTextProjection(light.chapterTextProjection())
}

private fun ChapterUnit.putTextProjection(projection: ChapterTextProjection): String {
    chapterTextCache = projection.text
    chapterTextBlockEndsCache = projection.blockEnds
    return projection.text
}

internal fun ChapterPrepareResult.chapterTextProjection(): ChapterTextProjection {
    val out = StringBuilder(totalChars.coerceAtLeast(0))
    val ends = IntArray(leaves.size)
    val drift = StreamDrift()
    for ((i, leaf) in leaves.withIndex()) {
        val leafStr = NormalFlowLayout.leafStreamText(
            el = leaf.el,
            styles = styleMap,
            classify = classify,
            hidden = hidden,
            genOf = genOf,
        )
        ends[i] = appendLeaf(out, i, leafStr, globalCharStarts, totalChars.toLong(), drift)
    }
    drift.report("heavy", out.length, totalChars)
    return ChapterTextProjection(out.toString(), ends)
}

/**
 * 轻 prepare 的文本投影：叶序与 [LightPrepare.globalCharStarts] 同一份结构；
 * 每个叶用 [LightPrepare.inlineStyles] 的懒级联（与塑形同入口），文本叶再补父节点样式
 * （重路径整章表里匿名文本叶会回查父容器，轻路径的 `inlineStyles` 对 `#text` 返回空表）。
 */
internal fun LightPrepare.chapterTextProjection(): ChapterTextProjection {
    val out = StringBuilder(totalChars.coerceAtLeast(0))
    val ends = IntArray(totalBlocks)
    val classify = lightClassify()
    val drift = StreamDrift()
    for (i in 0 until totalBlocks) {
        val el = leafElement(i)
        var styles = inlineStyles(i)
        if (el.isText) {
            val parent = el.parent
            if (parent != null && styles[parent] == null) {
                styles = styles + (parent to resolveStyle(parent))
            }
        }
        val leafStr = NormalFlowLayout.leafStreamText(
            el = el,
            styles = styles,
            classify = classify,
            hidden = hidden,
            genOf = genOf,
            resolveStyle = ::resolveStyle,
        )
        ends[i] = appendLeaf(out, i, leafStr, globalCharStarts, totalChars.toLong(), drift)
    }
    drift.report("light", out.length, totalChars)
    return ChapterTextProjection(out.toString(), ends)
}

/** 投影构建中的「叶文本长度 ≠ 该叶字符格数」计数（每章构建一次，只记首例，不刷屏）。 */
private class StreamDrift {
    private var count = 0
    private var first = ""

    fun check(index: Int, expected: Int, actual: Int, start: Long) {
        if (expected == actual) return
        count++
        if (count == 1) first = "i=$index expected=$expected got=$actual start=$start"
    }

    fun report(kind: String, built: Int, total: Int) {
        if (count == 0 && built == total) return
        Logger.w(
            "Orilumn.TXT",
            "text-drift build=$kind leaves=$count first=$first built=$built totalChars=$total",
        )
    }
}

/**
 * 追加一个叶的文本并回写块尾；顺带核对「本叶文本长度 == 本叶在字符流里占的格数」。
 *
 * 两者一旦不等，该叶之后的全部偏移都会与 `charBase` 漂移（长按/点按落到别的段落），
 * 因此在**整章文本构建时**（每章一次，不在手势路径上）留一条警告，便于今后定位。
 */
private fun appendLeaf(
    out: StringBuilder,
    index: Int,
    leafText: String,
    starts: LongArray,
    totalChars: Long,
    drift: StreamDrift,
): Int {
    val start = starts.getOrElse(index) { totalChars }
    val end = starts.getOrElse(index + 1) { totalChars }
    drift.check(index, (end - start).toInt(), leafText.length, start)
    out.append(leafText)
    return out.length
}
