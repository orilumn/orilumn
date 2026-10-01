package orilumn.reader.engine.skia

import kotlin.math.abs

/**
 * P4-c2 UI: 行内点按 → 字形命中（**与绘制同一份几何**，pure Kotlin、可单测）。
 *
 * ## 为什么不再重建 Paragraph（S5b 起）
 *
 * 原实现用 `ParagraphBuilder` + `getGlyphPositionAtCoordinate` 反查。S5 把绘制改成
 * [LineAligner] + 逐字 `drawString` 之后，这两者**几何来源不同**：
 *
 * | | 拉伸规则 | 行末空白 | 字距模型 |
 * |---|---|---|---|
 * | 绘制（现状） | Aligner：slack 均摊到可见间隙 | 不计入可见宽度 | 裸 cmap |
 * | 旧命中 | Skia `kJustify` + `appendTrailingNewline` 假首行 | 挂起不拉伸 | HarfBuzz（有 kerning） |
 *
 * 两条规则在 JUSTIFY 行上**必然给出不同的字形 x**（教训 29b/29c 实测：行末空白一出现
 * Skia 就整行放弃拉伸）⇒ **点中的字与看到的字会错位**。这不是理论风险，是那次真机缺陷
 * 在命中路径上的同一个成因。
 *
 * 改为直接读绘制用的同一份 [LineAligner.Placement.xs] 做二分查找：
 * **命中与绘制共用一份几何**，错位在结构上不可能发生。
 *
 * ## 坐标系（与绘制侧同式，未改）
 *
 * [xInParagraph] = 点按 x − 内容区左 − [DrawLine.xLeft] − [DrawLine.firstLineIndentPx]，
 * 由 [orilumn.reader.engine.ui.reader.ReaderMath.tapLineAt] 算好（`ReaderModels.kt:147`），
 * 与绘制侧 `paintX` 同一原点。命中在 `placement.xs` 上二分，`xs` 与 range 同坐标系。
 *
 * ## 语义保持不变
 *
 * - 行尾空区不算命中（x 超过**可见右边界**）—— 短行右侧空白点按不吸附到行尾字形。
 * - 返回 [DrawLine.text] 坐标系偏移；miss 回 null。
 * - **Skia 异常永不外抛**（点按路径绝不崩）—— 现在不调 Skia，天然满足。
 */
object LineHitTest {

    fun hit(
        line: DrawLine,
        xInParagraph: Float,
        yInLine: Float,
        collection: org.jetbrains.skia.paragraph.FontCollection = SkiaFontPool.current(),
    ): Int? {
        val start = line.range.first.coerceIn(0, line.text.length)
        val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)
        if (endExcl <= start) return null
        if (xInParagraph < 0f) return null
        if (yInLine < 0f) return null
        // y 不参与判定：调用方已保证 yInLine 落在 [yTop, yBottom) 内（tapLineAt 的行窗过滤），
        // 纵向没有「同一行内多个位置」的歧义（旧实现把它传给 Skia 只是形式需要）。
        @Suppress("UNUSED_EXPRESSION") collection

        val placement = LineAligner().align(
            text = line.text,
            range = start until endExcl,
            fontSizePx = line.fontSizePx,
            lineWidthPx = if (line.nowrap) Float.MAX_VALUE else line.lineWidthPx.coerceAtLeast(1).toFloat(),
            letterSpacingEm = line.letterSpacingEm,
            tag = line.tag,
            families = line.families,
            weight = line.weight,
            italic = line.italic,
            monospace = line.monospace,
            fontRuns = line.fontRuns,
            align = line.alignment,
            firstLineIndentPx = 0f,
            isLastLine = line.range.last + 1 >= line.text.length,
        )
        // 行尾空区不算命中：超过**可见右边界**即 miss（可见右边界已排除行末空白）。
        if (xInParagraph > placement.visibleRight + 1f) return null

        val xs = placement.xs
        if (xs.isEmpty()) return null
        // 二分找最后一个 x <= 点击位置的那个字 —— 即「点在哪个字的左半边」。
        var lo = 0
        var hi = xs.size - 1
        if (xInParagraph < xs[0]) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (xs[mid] <= xInParagraph) lo = mid else hi = mid - 1
        }
        return if (lo in 0 until endExcl - start) start + lo else null
    }

    /** 供自检：命中位置与该字可见中心的偏差（锁「点哪就是哪」用）。 */
    fun deviation(line: DrawLine, xInParagraph: Float): Float {
        val start = line.range.first.coerceIn(0, line.text.length)
        val placement = LineAligner().align(
            line.text, start until (line.range.last + 1), line.fontSizePx, line.lineWidthPx.toFloat(),
            line.letterSpacingEm, line.tag, line.families, line.weight, line.italic, line.monospace,
            line.fontRuns, line.alignment, 0f, line.range.last + 1 >= line.text.length,
        )
        val hit = hit(line, xInParagraph, 0f) ?: return Float.NaN
        val k = hit - start
        val cx = placement.xs[k] + placement.advs[k] / 2f
        return abs(cx - xInParagraph)
    }
}