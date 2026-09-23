package orilumn.reader.engine.skia

import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder

/**
 * P4-c2 UI: 行内点按 → 字形命中（pure Skia, 可单测）。
 *
 * 用与 [LineWindowDrawer] 绘制同一行**完全相同**的字形输入重建单行段落
 * （对齐/字号/字族/字重/斜体/等宽/字距 + 行内 face 段 + 不换行无限宽 + JUSTIFY 尾换行），
 * 再经 `getGlyphPositionAtCoordinate` 反查点中的字形。颜色/阴影/着重号/透明/基线位移
 * 不改变横向 advances，命中时一律不重建（量画同源只取字形影响项）。
 *
 * 坐标：[xInParagraph] = 点按 x − 段落绘制原点（内容区左 + [DrawLine.xLeft] +
 * [DrawLine.firstLineIndentPx]，与绘制侧 `paintX` 同式）；[yInLine] = 点按 y − 行顶。
 * 返回 [DrawLine.text] 坐标系偏移（调用方 + [DrawLine.charBase] 即章内 char），
 *  miss（空行/行左外/行尾空区/超出段）回 null。Skia 异常永不外抛（点按路径绝不崩）。
 */
object LineHitTest {

    fun hit(
        line: DrawLine,
        xInParagraph: Float,
        yInLine: Float,
        collection: FontCollection = SkiaFontPool.current(),
    ): Int? {
        val start = line.range.first.coerceIn(0, line.text.length)
        val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)
        if (endExcl <= start) return null
        if (xInParagraph < 0f) return null
        return runCatching {
            val style = SkParagraphFactory.paragraphStyle(
                line.alignment,
                line.fontSizePx,
                line.lineHeightRatio,
                line.tag,
                line.families,
                line.weight,
                line.italic,
                line.monospace,
                line.letterSpacingEm,
                forceStrut = line.baselineShifts.isNotEmpty(),
            )
            val builder = ParagraphBuilder(style, collection)
            val subLen = endExcl - start
            if (line.fontRuns.isEmpty()) {
                builder.addText(line.text.substring(start, endExcl))
            } else {
                // 只按 face 段切分（唯一改变横向 advances 的 run；色/位移段跳过）。
                val edges = ArrayList<Int>(line.fontRuns.size * 2 + 2)
                edges.add(start)
                edges.add(endExcl)
                for (r in line.fontRuns) {
                    val s = r.start.coerceIn(start, endExcl)
                    val e = r.endExclusive.coerceIn(start, endExcl)
                    if (e > s) {
                        edges.add(s)
                        edges.add(e)
                    }
                }
                val cuts = edges.distinct().sorted()
                for (k in 0 until cuts.size - 1) {
                    val s = cuts[k]
                    val e = cuts[k + 1]
                    if (e <= s) continue
                    val run = line.fontRuns.firstOrNull { it.start < e && s < it.endExclusive }
                    if (run == null) {
                        builder.addText(line.text.substring(s, e))
                    } else {
                        builder.pushStyle(
                            SkParagraphFactory.runTextStyle(
                                line.alignment,
                                run.fontPxOr(line.fontSizePx),
                                line.lineHeightRatio,
                                run.tag ?: line.tag,
                                run.families,
                                run.weight,
                                run.italic,
                                run.monospace,
                                line.letterSpacingEm,
                            ),
                        )
                        builder.addText(line.text.substring(s, e))
                        builder.popStyle()
                    }
                }
            }
            if (line.alignment == orilumn.reader.engine.css.TextAlign.JUSTIFY) builder.addText("\n")
            val paragraph = builder.build()
            try {
                paragraph.layout(if (line.nowrap) Float.MAX_VALUE else line.lineWidthPx.coerceAtLeast(1).toFloat())
                // 行尾空区不算命中（短行右侧空白点按不该吸附到行尾字形）。
                if (xInParagraph > paragraph.longestLine + 1f) return@runCatching null
                val pos = paragraph.getGlyphPositionAtCoordinate(xInParagraph, yInLine).position
                if (pos < 0 || pos >= subLen) null else start + pos
            } finally {
                paragraph.close()
            }
        }.getOrNull()
    }
}
