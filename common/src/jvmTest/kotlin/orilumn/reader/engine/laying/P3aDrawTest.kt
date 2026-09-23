package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyle
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.BoxShadow
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.CornerRadius
import orilumn.reader.engine.css.Edges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-a 绘制模型 guard：圆角（解算＋钳制＋均匀描边环/非均匀回退）、阴影附着
 * （currentColor 消解）、alpha 连乘；方形旧路径逐字节一致。
 */
class P3aDrawTest {

    private fun box(
        style: ComputedStyle,
        width: Int = 100,
        top: Int = 0,
        bottom: Int = 40,
    ): LayoutBox = LayoutBox(null, style, contentLeft = 0, contentWidth = width).also {
        it.contentTop = top
        it.contentBottom = bottom
    }

    private fun style(
        bg: String? = "#ffffffff",
        border: Edges = Edges(),
        radius: CornerRadius = CornerRadius(),
        radiusPct: CornerRadius = CornerRadius(),
        shadow: BoxShadow? = null,
        opacity: Float = 1f,
        colorHex: String? = null,
        colors: BorderColorEdges? = null,
        styles: BorderStyleEdges? = null,
    ) = ComputedStyle(
        fontSizePx = 16f, lineHeightRatio = 1.5f,
        colorHex = colorHex, backgroundColorHex = bg,
        border = border, borderColors = colors, borderStyles = styles,
        borderRadius = radius, borderRadiusPct = radiusPct,
        boxShadow = shadow, opacity = opacity,
    )

    @Test
    fun `方形旧路径零新字段`() {
        val rs = BoxDrawer.draw(listOf(box(style()))).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertTrue(rs[0].radii.isSquare())
        assertEquals(1f, rs[0].alpha, 0f)
        assertNull(rs[0].shadow)
        assertEquals(0f, rs[0].strokeWidthPx, 0f)
    }

    @Test
    fun `圆角背景解算钳制`() {
        // 200% 半径：先按 min(100,40)=40 得 80，再逐边折叠（顶 160→100 得 50，
        // 侧 100→40 得 20），终值 20。
        val rs = BoxDrawer.draw(
            listOf(box(style(radiusPct = CornerRadius(2f, 2f, 2f, 2f)))),
        ).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(20f, rs[0].radii.topLeft, 1e-4f)
        assertEquals(20f, rs[0].radii.bottomRight, 1e-4f)
    }

    @Test
    fun `均匀边框加圆角出描边环`() {
        val rs = BoxDrawer.draw(
            listOf(box(style(bg = null, border = Edges(2f, 2f, 2f, 2f), radius = CornerRadius(6f, 6f, 6f, 6f), colors = BorderColorEdges("#ff000000")))),
        ).filter { it.kind == DrawKind.BORDER }
        assertEquals("均匀边框只出一环", 1, rs.size)
        assertEquals(2f, rs[0].strokeWidthPx, 1e-4f)
        assertEquals(6f, rs[0].radii.topLeft, 1e-4f)
        assertEquals("#ff000000", rs[0].colorHex)
    }

    @Test
    fun `非均匀边框加圆角回方形带`() {
        val rs = BoxDrawer.draw(
            listOf(
                box(
                    style(
                        bg = null, border = Edges(2f, 4f, 2f, 4f), radius = CornerRadius(6f, 6f, 6f, 6f),
                        colors = BorderColorEdges("#ff000000"),
                    ),
                ),
            ),
        ).filter { it.kind == DrawKind.BORDER }
        assertTrue("非均匀回方形带", rs.size > 1)
        assertTrue(rs.all { it.strokeWidthPx == 0f && it.radii.isSquare() })
    }

    @Test
    fun `阴影附着且 currentColor 消解`() {
        val rs = BoxDrawer.draw(
            listOf(box(style(bg = null, shadow = BoxShadow(2f, 2f, 4f, null), colorHex = "#ff112233"))),
        ).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals("#00000000", rs[0].colorHex)
        assertEquals("#ff112233", rs[0].shadow!!.colorHex)
    }

    @Test
    fun `alpha 祖先连乘`() {
        val parentStyle = style(bg = null, opacity = 0.5f)
        val child = box(style(bg = "#ffffffff", opacity = 0.5f))
        val tree = LayoutBox(null, parentStyle, 0, 100, childBoxes = listOf(child))
        tree.contentTop = 0
        tree.contentBottom = 40
        val rs = BoxDrawer.draw(listOf(tree)).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals(0.25f, rs[0].alpha, 1e-6f)
    }
}
