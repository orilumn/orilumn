package orilumn.reader.engine.skia

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.NormalFlowLayout

/**
 * 行内图（叶文本里 U+FFFC 槽位）的绘制判据与占位隐藏——**量画同源的单点**。
 *
 * 背景：行内 `<img>`（`<p>字<img>字</p>`，如书里的 GUI 图标）在盒流里只留下一个
 * U+FFFC 对象替换字符（断行/行高都按它算），但没有任何像素管线接管 ⇒ 字体画出
 * 「虚线 obj 方框」。这里给出两个消费方共用的判据：
 *  - [orilumn.reader.engine.skia.WindowedBookLayout.pageImages]：按槽位贴真图；
 *  - [orilumn.reader.engine.skia.DrawLineBuilder] / 增量行窗：把占位字形隐掉。
 *
 * 只有**真能贴出来**的槽位才隐占位（尺寸算得出、未被 `visibility:hidden` 藏掉）：
 * 反过来的错更隐蔽——隐了占位却没图可贴，读者只看到一片空白。
 */

/** 叶文本里 U+FFFC 的绝对下标（升序；无则空表）。 */
internal fun inlineImagePositions(text: String): IntArray {
    var p = text.indexOf('\uFFFC')
    if (p < 0) return IntArray(0)
    var n = 0
    var q = p
    while (q >= 0) {
        n++
        q = text.indexOf('\uFFFC', q + 1)
    }
    val out = IntArray(n)
    var i = 0
    while (p >= 0) {
        out[i++] = p
        p = text.indexOf('\uFFFC', p + 1)
    }
    return out
}

/**
 * 该槽位贴图用的 used 尺寸（宽, 高）；null = **贴不出来**（无 loader/href/src，或被
 * `visibility:hidden` 藏掉）。
 *
 * 与断行/行高同一个函数 [NormalFlowLayout.replacedUsedSize]：槽位宽高沿 CSS 替换元素口径
 * （`auto/auto` 且资源缺失时按规范的 300px 兜底，与块级插图同一条路径），
 * 图片按它绘制才是量画同源。
 */
internal fun inlineImageUsedSize(
    img: MarkupElement,
    style: ComputedStyle,
    breakW: Int,
    imageLoader: ImageBoundsReader?,
    chapterHref: String,
    density: Float,
): Pair<Int, Int>? {
    if (imageLoader == null || chapterHref.isBlank()) return null
    if (img.attrs["src"] == null) return null
    if (style.visibilityHidden) return null
    return NormalFlowLayout.replacedUsedSize(
        img, style, breakW, imageLoader, chapterHref, density = density,
    )
}

/**
 * 本行要隐掉的占位字形区间（[range] 为叶文本坐标，inclusive）。
 *
 * [positions] 与 [images] 同序（U+FFFC 出现序 = `inlineImageEls` 文档序）；
 * 缺样式时退回叶样式（行内元素不在盒表里，重路径的全量级联表正常能命中）。
 */
internal fun inlineImageHiddenRanges(
    positions: IntArray,
    range: IntRange,
    images: List<MarkupElement>,
    styleOf: (MarkupElement) -> ComputedStyle?,
    leafStyle: ComputedStyle,
    breakW: Int,
    imageLoader: ImageBoundsReader?,
    chapterHref: String,
    density: Float,
): List<IntRange> {
    if (positions.isEmpty() || images.isEmpty()) return emptyList()
    var out: MutableList<IntRange>? = null
    for (i in positions.indices) {
        val p = positions[i]
        if (p < range.first || p > range.last) continue
        val img = images.getOrNull(i) ?: break
        val st = styleOf(img) ?: leafStyle
        if (inlineImageUsedSize(img, st, breakW, imageLoader, chapterHref, density) == null) continue
        val list = out ?: ArrayList<IntRange>(1).also { out = it }
        list.add(p..p)
    }
    return out ?: emptyList()
}
