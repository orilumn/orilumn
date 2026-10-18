package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderStyle

/**
 * 一条**边框边**的铺带方向（E16 单源几何的轴向标记）：
 *  - [HORIZONTAL] = 上/下边框：带沿 x 轴铺满 `[x0, x1)`，在 y 上占 `[band0, band0 + width)`；
 *  - [VERTICAL] = 左/右边框：带沿 y 轴铺满 `[x0, x1)`，在 x 上占 `[band0, band0 + width)`。
 */
enum class BorderEdgeAxis { HORIZONTAL, VERTICAL }

/**
 * 一条边框边切出的一段矩形（**Float 坐标**；实线恒 1 段，虚线/点线多段）。
 * 命名按屏幕方向：[left]..[right] × [top]..[bottom]。
 */
data class BorderEdgeRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 边框边几何**单源**（pure JVM）：把一条边（轴向区间 `[x0, x1)`、带起点 [band0]、
 * 厚 [width]）按 [style] 切成若干段矩形。
 *
 * 消费方两处，**规则只此一份**（E16 的要害：虚线/点线规则不得在块盒与行内盒各写一套，
 * 否则同一页里两种盒的虚线长相悄悄分叉）：
 *  - 块盒 [BoxDrawer]（绘制层把段圆整成整像素 `DrawRect`）；
 *  - 行内盒 `LineWindowDrawer.paintInlineBoxes`（渲染层直接落 Float 段）。
 *
 * 规则（CSS 2.1 §11.1 的近似，与本仓块盒历史口径逐值相同）：
 *  - `SOLID`：整带一段；
 *  - `DASHED`：段长 `3×厚`（下限 2px）、隙 `2×厚`（下限 1px）；
 *  - `DOTTED`：段长 = `厚` 的方块、步距 `2×厚`（下限 2px）；
 *  - `NONE` / 零厚 / 空区间：空表（不画）。
 *
 * ⚠ 两边的**入参口径**不同，但对结果等价：[BoxDrawer] 传入**已取整**的厚（Int 口径，
 * 段坐标因此全为整数 ⇒ 落到 `DrawRect` 与本函数落地前的历史实现逐字节相同）；
 * 行内盒传入真浮点厚（虚线不必先量化到整像素）。
 */
fun borderEdgeRects(
    axis: BorderEdgeAxis,
    x0: Float,
    x1: Float,
    band0: Float,
    width: Float,
    style: BorderStyle,
): List<BorderEdgeRect> {
    if (width <= 0f || x1 <= x0) return emptyList()
    if (style == BorderStyle.NONE) return emptyList()
    fun seg(a0: Float, a1: Float): BorderEdgeRect =
        if (axis == BorderEdgeAxis.HORIZONTAL) {
            BorderEdgeRect(left = a0, top = band0, right = a1, bottom = band0 + width)
        } else {
            BorderEdgeRect(left = band0, top = a0, right = band0 + width, bottom = a1)
        }
    return when (style) {
        BorderStyle.SOLID -> listOf(seg(x0, x1))
        BorderStyle.DASHED -> {
            val dash = (3f * width).coerceAtLeast(2f)
            val gap = (2f * width).coerceAtLeast(1f)
            val out = ArrayList<BorderEdgeRect>()
            var p = x0
            while (p < x1) {
                out.add(seg(p, minOf(p + dash, x1)))
                p += dash + gap
            }
            out
        }
        BorderStyle.DOTTED -> {
            val step = (2f * width).coerceAtLeast(2f)
            val out = ArrayList<BorderEdgeRect>()
            var p = x0
            while (p < x1) {
                out.add(seg(p, minOf(p + width, x1)))
                p += step
            }
            out
        }
        BorderStyle.NONE -> emptyList()
    }
}
