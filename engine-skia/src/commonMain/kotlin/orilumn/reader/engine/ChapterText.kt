package orilumn.reader.engine

import orilumn.reader.engine.laying.NormalFlowLayout

/**
 * 整章纯文本投影（D-18）：把 [ChapterPrepareResult.leaves] 按文档序拼成一条字符串。
 *
 * **与排版同源**：每个叶都走 [NormalFlowLayout.leafText]，并传入 prepare 阶段同一份
 * `styleMap` / `classify` / `hidden` / `genOf`——这正是绘制侧 `DrawLineBuilder` 取叶文本的入口，
 * 因此拼接后的字符偏移与 `PageSlice.charStart`、`DrawLine.charBase` 完全同尺。
 *
 * 返回值只覆盖**可排文字**：表格/替换元素不产字符（与 `leafText` 的空串一致），
 * 列表项目符号是装饰、不在字符流里。`prepareResult` 尚未就绪（未开章/已驱逐）时返回 null，
 * 调用方按「暂不可选」处理，不要拿空串冒充成功。
 */
fun ChapterUnit.chapterText(): String? {
    chapterTextCache?.let { return it }
    val prepare = prepareResult ?: return null
    val out = StringBuilder(prepare.totalChars.coerceAtLeast(0))
    for (leaf in prepare.leaves) {
        val el = leaf.el ?: continue
        out.append(
            NormalFlowLayout.leafText(
                el = el,
                styles = prepare.styleMap,
                classify = prepare.classify,
                hidden = prepare.hidden,
                genOf = prepare.genOf,
            ),
        )
    }
    return out.toString().also { chapterTextCache = it }
}
