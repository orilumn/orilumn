package orilumn.reader.engine.laying

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement

/**
 * 行内图行高规则（渲染层/内核：CSS 2.1 §10.8 line-box rule）。
 *
 * 原属 `NormalFlowLayout` 对象成员（盒流 emission 的家），名义易误读为中间层逻辑；
 * 实为浏览器内核函数（分行控制/几何测量），单源至此，盒流与塑形层共调：
 *  - 盒流 `breakLeafLines` 系（流式 Y 记账等不及塑形结果）；
 *  - 塑形层 `ShapeGeometry`（shape 自带终高，行窗/分页/表格格高同源）。
 */
internal fun inlineImageEls(
    root: MarkupElement,
    classify: BlockClassify,
    hidden: HiddenCheck = HIDDEN_NONE,
): List<MarkupElement> {
    val out = ArrayList<MarkupElement>()
    fun walk(node: MarkupElement) {
        for (c in node.children) {
            when {
                hidden.isHidden(c) -> Unit
                c.isText || c.tag == "br" -> Unit
                classify.isBlock(c) -> Unit
                NormalFlowLayout.isReplaceable(c) -> out.add(c)
                else -> walk(c)
            }
        }
    }
    walk(root)
    return out
}

/**
 * Raises breaker line heights for lines holding inline `<img>` slots (U+FFFC) to the images'
 * used heights (CSS 2.1 §10.8 line-box rule). Returns per-line heights aligned with [broken].
 *
 * The [BrokenLine.range]s index into [text]; U+FFFC occurrence order matches
 * [inlineImageEls] order, so images are consumed in line order. A line with several images
 * takes the tallest. Image-free lines keep the breaker height untouched.
 */
/** P6-a2: 行内图抬升行高（渲染内核单源；pure，盒流与塑形层共调）。 */
fun adjustLineHeightsForInlineImages(
    text: String,
    broken: List<BrokenLine>,
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    classify: BlockClassify,
    hidden: HiddenCheck,
    breakW: Int,
    imageLoader: ImageBoundsReader?,
    chapterHref: String,
): List<Int> {
    if (broken.isEmpty() || text.indexOf('\uFFFC') < 0) return broken.map { it.heightPx }
    // Queue of images in U+FFFC order; consumed as their slots appear line by line.
    val imgs = ArrayDeque(inlineImageEls(root, classify, hidden))
    if (imgs.isEmpty()) return broken.map { it.heightPx }
    // Index of the k-th U+FFFC in [text] → image lookup without re-scanning per line.
    val fffcAt = ArrayList<Int>()
    var p = text.indexOf('\uFFFC')
    while (p >= 0) { fffcAt.add(p); p = text.indexOf('\uFFFC', p + 1) }
    var cursor = 0 // consumed count into fffcAt/imgs
    return broken.map { line ->
        var tallest = 0
        // BrokenLine.range is an inclusive IntRange (see emit(): r.first..r.last).
        val lo = line.range.first.coerceAtLeast(0)
        val hi = line.range.last.coerceAtMost(text.length - 1)
        while (cursor < fffcAt.size && fffcAt[cursor] < lo) cursor++
        var scan = cursor
        while (scan < fffcAt.size && fffcAt[scan] <= hi) {
            val img = imgs.getOrNull(scan) ?: break
            val imgStyle = styles[img] ?: styles[root] ?: NormalFlowLayout.DEFAULT_STYLE
            val usedH = NormalFlowLayout.replacedUsedSize(img, imgStyle, breakW, imageLoader, chapterHref).second
            if (usedH > tallest) tallest = usedH
            scan++
        }
        cursor = scan
        if (tallest > 0) maxOf(line.heightPx, tallest) else line.heightPx
    }
}
