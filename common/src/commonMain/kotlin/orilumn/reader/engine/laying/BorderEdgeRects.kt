package orilumn.reader.engine.laying

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
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
 *  - `DASHED`：按 Chromium `styled_stroke_data.cc` 的虚线口径——
 *    **设备像素厚** `t = round(厚 × deviceScale)`，`t ≥ 3` 时段 `2t`、隙 `t`，
 *    `t < 3` 时段 `3t`、隙 `2t`（细线用更长的段/隙才看得出虚线）；
 *    隙再按 Chromium `SelectBestDashGap` 微调，让整数段虚线正好铺满整条边；
 *  - `DOTTED`：段长 = `厚` 的方块、步距 `2×厚`（下限 2px）；
 *  - `NONE` / 零厚 / 空区间：空表（不画）。
 *
 * ⚠ 两边的**入参口径**不同，但对结果等价：[BoxDrawer] 传入**已取整**的厚（Int 口径，
 * 段坐标因此全为整数 ⇒ 落到 `DrawRect` 与本函数落地前的历史实现逐字节相同）；
 * 行内盒传入真浮点厚（虚线不必先量化到整像素）。
 *
 * @param deviceScale 显示密度（1 CSS px 落几个物理像素）。Chromium 的虚线比例是在**设备像素**
 *   空间里定的（`t` 过 3 换档），本仓绘制坐标是 CSS px ⇒ 必须把密度带进来才能同档；
 *   缺省 1 = 只在无密度信息的纯几何测试里成立。
 */
fun borderEdgeRects(
    axis: BorderEdgeAxis,
    x0: Float,
    x1: Float,
    band0: Float,
    width: Float,
    style: BorderStyle,
    deviceScale: Float = 1f,
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
            val d = if (deviceScale > 0f) deviceScale else 1f
            // 设备像素厚（Chromium 用整像素厚定档）；长度同样换到设备像素再回算。
            val t = (width * d).roundToInt().coerceAtLeast(1).toFloat()
            val dashDev = t * if (t >= 3f) 2f else 3f
            val gapDev0 = t * if (t >= 3f) 1f else 2f
            val lenDev = (x1 - x0) * d
            // Chromium：放不下两段就不画（`path_length <= dash_length * 2`）。
            if (lenDev <= dashDev * 2f) return emptyList()
            val two = dashDev * 2f + gapDev0
            val dashD: Float
            val gapD: Float
            if (lenDev <= two) {
                // 只够两段：段/隙等比缩到正好铺满。
                val m = lenDev / two
                dashD = dashDev * m
                gapD = gapDev0 * m
            } else {
                dashD = dashDev
                gapD = bestDashGap(lenDev, dashDev, gapDev0)
            }
            val dash = dashD / d
            val gap = gapD / d
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

/**
 * Chromium `SelectBestDashGap`：在「多一段」与「少一段」两个整数段数里挑一个，
 * 让隙尽量接近 [gap0]（段长不动）。`minGaps <= 0` 时无从微调，原样返回。
 */
private fun bestDashGap(length: Float, dash: Float, gap0: Float): Float {
    val available = length + gap0
    val minNum = floor(available / (dash + gap0))
    val maxNum = minNum + 1f
    val minGaps = minNum - 1f
    val maxGaps = maxNum - 1f
    if (minGaps <= 0f || maxGaps <= 0f) return gap0
    val minGap = (length - minNum * dash) / minGaps
    val maxGap = (length - maxNum * dash) / maxGaps
    return if (maxGap <= 0f || abs(minGap - gap0) < abs(maxGap - gap0)) minGap else maxGap
}
