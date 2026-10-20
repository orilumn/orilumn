package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.OverflowValue
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * E11：`overflow` 祖先裁剪的**共享几何**（排版层下，无画布依赖）。
 *
 * 浏览器语义（css-overflow-3）：`overflow: hidden/clip/scroll/auto` 的盒把**后代内容**裁到自己的
 * **padding box**（border box 内缩四边边框；`visible` 不裁）。裁剪是**后代链累积**的 —— 内容同时受
 * 自身与每个裁剪祖先约束，取交集。
 *
 * 坐标口径与 [LayoutBox] / [DrawRect] 一致（章节绝对 Y；轻路径是窗口局部 Y，与行流同一平移）。
 * 本文件只算「谁裁、裁成什么样」，落墨侧（canvas.clipRect / DrawLine.clip / PageImage.clip）负责施加。
 */
data class ClipRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /**
     * 沿 Y 平移。页切片/离屏换算与载体（行/盒/图）同步调用 —— **漏平移 = 裁剪区错位**，
     * 而 X 已含内容坐标偏移，不随 Y 变。
     */
    fun shifted(dy: Int): ClipRect = if (dy == 0) this else ClipRect(left, top + dy, right, bottom + dy)

    fun intersect(other: ClipRect): ClipRect = ClipRect(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    )
}

/** `overflow` 是否构成裁剪（`visible` 不裁；`hidden`/`clip`/`scroll`/`auto` 都裁）。 */
fun ComputedStyle.clipsOverflow(): Boolean = overflow != OverflowValue.VISIBLE

/** 本盒的裁剪区 = **padding box** = border box 内缩四边边框（CSS padding box，非 content box）。 */
fun LayoutBox.paddingBox(): ClipRect = ClipRect(
    left = contentLeft + relOffsetX + style.border.left.roundToInt(),
    top = contentTop + relOffsetY + style.border.top.roundToInt(),
    right = contentLeft + relOffsetX + contentWidth - style.border.right.roundToInt(),
    bottom = contentBottom + relOffsetY - style.border.bottom.roundToInt(),
)

/**
 * 盒**自身**底色/边框的裁剪 = 祖先链裁剪（**不含自身** padding box —— 自身不裁自己的边框/底色）。
 * 内容（文本行/插图/子盒）另用 [contentClipOf]，含自身。
 */
fun LayoutBox.inheritedClip(clipOf: (MarkupElement) -> ClipRect?): ClipRect? =
    contentClipOf(el?.parent, clipOf)

/**
 * 盒**内容**（文本行、插图、表格格内容）的裁剪 = 自身 padding box（若自身裁剪）与祖先链上每个裁剪盒
 * 的交集；全链无裁剪盒即 null（旧路径零回归）。
 *
 * [clipOf] 给出「某元素自身若有裁剪盒」的几何（重路径来自盒树、轻路径来自窗口聚合）；返回 null 表示
 * 该元素没有可裁的盒 ⇒ 不参与交集。
 *
 * 跳过合成文本节点（[MarkupElement.isText]）：匿名行叶的合成 `#text` 复用容器样式，若照单参与，容器
 * 的 padding box 会被当成它自己的盒（重复且更窄）。容器的裁剪在祖先链上照样遇到。
 */
fun contentClipOf(el: MarkupElement?, clipOf: (MarkupElement) -> ClipRect?): ClipRect? {
    var acc: ClipRect? = null
    var n = el
    while (n != null) {
        if (!n.isText) {
            clipOf(n)?.let { c -> acc = acc?.intersect(c) ?: c }
        }
        n = n.parent
    }
    return acc
}

/** 盒树里每个**裁剪盒**元素 → 其 padding box（非裁剪元素不入表；重复元素取首个）。 */
fun clipMapOf(
    boxes: List<LayoutBox>,
    out: MutableMap<MarkupElement, ClipRect> = HashMap(),
): Map<MarkupElement, ClipRect> {
    for (b in boxes) {
        val el = b.el
        if (el != null && !el.isText && b.style.clipsOverflow()) out.putIfAbsent(el, b.paddingBox())
        if (b.childBoxes.isNotEmpty()) clipMapOf(b.childBoxes, out)
    }
    return out
}

/**
 * 本盒**内容**（文本行/插图）的裁剪：盒内取值版的 [contentClipOf] —— 祖先链裁剪（[LayoutBox.clipRect]）
 * 再交上自身 padding box（自身裁剪时）。
 *
 * 重路径直接用（盒流后 [LayoutBox.clipRect] 已由 [applyInheritedClips] 填好，无需查表）；
 * 轻路径几何不同（窗口局部 Y），另用 [contentClipOf] 配窗口聚合表。
 */
fun LayoutBox.contentClip(): ClipRect? =
    if (style.clipsOverflow()) clipRect?.intersect(paddingBox()) ?: paddingBox() else clipRect

/**
 * 把「祖先链裁剪」写进每个盒的 [LayoutBox.clipRect]（重路径一次性预处理），并返回裁剪几何表
 * （供同行/图/表内容的 [contentClipOf] 复用）。
 *
 * 必须在**最终流完之后**调用：盒的 border-box 上下沿由流决定，流前算即错位。
 */
fun applyInheritedClips(rootBoxes: List<LayoutBox>): Map<MarkupElement, ClipRect> {
    val map = clipMapOf(rootBoxes)
    fun walk(bs: List<LayoutBox>) {
        for (b in bs) {
            b.clipRect = b.inheritedClip(map::get)
            if (b.childBoxes.isNotEmpty()) walk(b.childBoxes)
        }
    }
    walk(rootBoxes)
    return map
}
