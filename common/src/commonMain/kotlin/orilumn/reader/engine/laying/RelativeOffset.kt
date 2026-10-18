package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * E12：`position: relative` 的**绘制偏移**（排版层下，纯几何）。
 *
 * 相对定位**不改排版**：占位、兄弟位置、分页切点全不动，只把落墨的几何整体平移 ——
 * 盒底色/边框、文本行、插图、表格格内容。偏移沿**祖先链累积**：一个相对定位的盒连同它的整棵
 * 子树一起平移（CSS 2.1 §9.4.3，相对定位的盒为其后代建立新的「原位置」）。
 *
 * 与 [ClipRect] 同款：偏移是**常量增量**，与坐标系无关（章节绝对 Y 与窗口局部 Y 都适用），
 * 故重/轻/临时三路共用同一份值，不会因切片而漂移。
 *
 * 未做（见账本 E12「残留」）：层叠顺序（定位盒仍按文档序绘制，`z-index` 不生效）、
 * `absolute`/`fixed`、`%` 偏移。
 */
data class BoxOffset(val x: Int, val y: Int) {
    val isZero: Boolean get() = x == 0 && y == 0

    operator fun plus(other: BoxOffset): BoxOffset =
        if (other.isZero) this else if (isZero) other else BoxOffset(x + other.x, y + other.y)

    companion object {
        val ZERO = BoxOffset(0, 0)
    }
}

/**
 * 元素**自身**（不含祖先）的相对定位偏移；`position` 非 `relative` 时恒 [BoxOffset.ZERO]。
 *
 * CSS 2.1 §9.4.3：`left` 与 `right` 同时非 `auto` 时（ltr）**`left` 赢**（`top`/`bottom` 同理）；
 * 只有一个非 `auto` 时用它的反方向（`right: 8px` ⇒ 左移 8px）。
 */
fun relativeSelfOffset(style: ComputedStyle?): BoxOffset {
    if (style == null || !style.positionRelative) return BoxOffset.ZERO
    val ins = style.relativeInset
    val dx = ins.left?.roundToInt() ?: ins.right?.let { -it.roundToInt() } ?: 0
    val dy = ins.top?.roundToInt() ?: ins.bottom?.let { -it.roundToInt() } ?: 0
    return if (dx == 0 && dy == 0) BoxOffset.ZERO else BoxOffset(dx, dy)
}

/**
 * 元素内容的**累积**相对定位偏移 = 自身与每个相对定位祖先的偏移之和。
 * `styleOf` 给出元素的**计算样式**（重路径来自级联表、轻路径来自 `prepare`）；缺项视为不偏移。
 *
 * 只走**元素链**（不是盒链）：合成 `#text` 节点的匿名叶复用容器样式，若按盒取会把容器的偏移
 * 重复算一次；按元素链算时容器的偏移只经容器元素计一次。
 */
fun relativeOffsetOf(el: MarkupElement?, styleOf: (MarkupElement) -> ComputedStyle?): BoxOffset {
    var acc = BoxOffset.ZERO
    var n = el
    while (n != null) {
        acc += relativeSelfOffset(styleOf(n))
        n = n.parent
    }
    return acc
}

/**
 * 把**累积**偏移写进每个盒的 [LayoutBox.relOffsetX]/[LayoutBox.relOffsetY]（绘制侧直接读）。
 *
 * 幂等（每次从元素链重算，不累加既有值），可在盒树流完之后重复调用；
 * 与 [relativeOffsetOf] 同源，故轻路径（按元素链现算）与重路径（盒树预处理）取值必然一致。
 */
fun applyRelativeOffsets(boxes: List<LayoutBox>, styleOf: (MarkupElement) -> ComputedStyle?) {
    for (b in boxes) {
        val off = relativeOffsetOf(b.el, styleOf)
        b.relOffsetX = off.x
        b.relOffsetY = off.y
        if (b.childBoxes.isNotEmpty()) applyRelativeOffsets(b.childBoxes, styleOf)
    }
}
