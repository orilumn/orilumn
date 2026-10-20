package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E16 单源几何锁：[borderEdgeRects] 的切段规则（实线整带、虚线 3×/2×、点线方块步距）与轴向映射。
 *
 * 这是「规则只此一份」的**规则本身**——块盒 [BoxDrawer] 传已取整宽度（Int 口径）、
 * 行内盒渲染侧传真浮点宽度，两边都只能从这里拿段表。块盒落地后输出**逐字节不变**由
 * 既有 [BorderDrawTest] 守（它锁的正是 BoxDrawer 的几何），本文件再钉死规则值，
 * 防止「改虚线规则」这种语义性变异在两边同时静默通过。
 */
class BorderEdgeRectsTest {

    @Test
    fun `实线整带一段`() {
        val r = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 10f, 60f, 3f, 4f, BorderStyle.SOLID)
        assertEquals(listOf(BorderEdgeRect(10f, 3f, 60f, 7f)), r)
    }

    @Test
    fun `点线是厚见方的方块按 2 倍厚步距铺`() {
        // 厚 4：方块 [p, p+4)，步距 8。
        val r = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 20f, 0f, 4f, BorderStyle.DOTTED)
        assertEquals(
            listOf(
                BorderEdgeRect(0f, 0f, 4f, 4f),
                BorderEdgeRect(8f, 0f, 12f, 4f),
                BorderEdgeRect(16f, 0f, 20f, 4f), // 末段被 x1 截断
            ),
            r,
        )
    }

    @Test
    fun `虚线段长按 Chromium 设备像素口径、隙微调铺满`() {
        // 厚 4（设备厚 4 ≥ 3）：dash 2×4=8、目标隙 1×4=4；
        // 长 36 时 SelectBestDashGap 取 3 段、隙 6 ⇒ 三段正好铺满 [0,36)。
        val r = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 36f, 2f, 4f, BorderStyle.DASHED)
        assertEquals(
            listOf(
                BorderEdgeRect(0f, 2f, 8f, 6f),
                BorderEdgeRect(14f, 2f, 22f, 6f),
                BorderEdgeRect(28f, 2f, 36f, 6f),
            ),
            r,
        )
    }

    @Test
    fun `虚线换档看设备像素厚——2px 在 2x 屏按 2 倍厚算`() {
        // 2px 边框 @2x = 设备厚 4（≥3）⇒ 段 4px、隙 2px（CSS 口径）；
        // 同一条边若按 1x 算（设备厚 2 < 3）则是段 6px、隙 4px。
        val at2x = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 100f, 0f, 2f, BorderStyle.DASHED, 2f)
        assertEquals(4f, at2x[0].right - at2x[0].left, 1e-4f)
        assertEquals(2f, at2x[1].left - at2x[0].right, 1e-4f)
        val at1x = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 100f, 0f, 2f, BorderStyle.DASHED, 1f)
        assertEquals(6f, at1x[0].right - at1x[0].left, 1e-4f)
    }

    @Test
    fun `虚线放不下两段就不画`() {
        // 设备厚 1（0.5px @1x 向上取整）⇒ 段 3px；长 4px 连两段都放不下 ⇒ 空表（Chromium 同）。
        assertTrue(
            borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 4f, 0f, 0.5f, BorderStyle.DASHED).isEmpty(),
        )
    }

    @Test
    fun `点线厚度下限`() {
        // 厚 0.5：步距下限 2px、方块仍为厚本身 [p, p+0.5)
        val dot = borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 4f, 0f, 0.5f, BorderStyle.DOTTED)
        assertEquals(
            listOf(
                BorderEdgeRect(0f, 0f, 0.5f, 0.5f),
                BorderEdgeRect(2f, 0f, 2.5f, 0.5f),
            ),
            dot,
        )
    }

    @Test
    fun `轴向映射竖边把带落在 x 上`() {
        val v = borderEdgeRects(BorderEdgeAxis.VERTICAL, 10f, 60f, 3f, 4f, BorderStyle.SOLID)
        assertEquals(listOf(BorderEdgeRect(3f, 10f, 7f, 60f)), v)
    }

    @Test
    fun `零厚 none 与空区间不画`() {
        assertTrue(borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 10f, 0f, 0f, BorderStyle.SOLID).isEmpty())
        assertTrue(borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 0f, 10f, 0f, 4f, BorderStyle.NONE).isEmpty())
        assertTrue(borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 5f, 5f, 0f, 4f, BorderStyle.SOLID).isEmpty())
        assertTrue(borderEdgeRects(BorderEdgeAxis.HORIZONTAL, 10f, 4f, 0f, 4f, BorderStyle.DASHED).isEmpty())
    }
}
