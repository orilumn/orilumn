package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BorderColorEdges
import orilumn.reader.engine.css.BorderStyle
import orilumn.reader.engine.css.BorderStyleEdges
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 验收：[BoxDrawer] 按边框样式选绘制路径（solid 整带 / dashed·dotted 分段 /
 * none 跳过）+ 四边异色 + 无声明颜色时的 currentColor 回退。
 */
class BorderDrawTest {

    private fun box(
        border: Edges = Edges(2f, 2f, 2f, 2f),
        colors: BorderColorEdges? = BorderColorEdges("#ff111111"),
        styles: BorderStyleEdges? = null,
        textColor: String? = null,
    ): LayoutBox {
        val style = ComputedStyle(
            fontSizePx = 16f, lineHeightRatio = 1.5f,
            colorHex = textColor, border = border, borderColors = colors, borderStyles = styles,
        )
        return LayoutBox(null, style, contentLeft = 0, contentWidth = 100).also {
            it.contentTop = 0
            it.contentBottom = 40
        }
    }

    private fun borders(b: LayoutBox): List<DrawRect> =
        BoxDrawer.draw(listOf(b)).filter { it.kind == DrawKind.BORDER }

    @Test
    fun `solid 四边整带同色`() {
        val rs = borders(box())
        assertEquals(4, rs.size)
        assertTrue(rs.all { it.colorHex == "#ff111111" })
        // 顶带：y ∈ [0,2]，x 满宽。
        val top = rs.first { it.top == 0 }
        assertEquals(0, top.left)
        assertEquals(100, top.right)
        assertEquals(2, top.bottom)
    }

    @Test
    fun `style none 跳过绘制`() {
        val rs = borders(box(styles = BorderStyleEdges.uniform(BorderStyle.NONE)))
        assertTrue(rs.isEmpty())
    }

    @Test
    fun `dashed 顶边成多段`() {
        val rs = borders(box(border = Edges(top = 2f), styles = BorderStyleEdges(top = BorderStyle.DASHED)))
        assertTrue("dashed 应拆成多段，实际 ${rs.size}", rs.size > 1)
        assertTrue(rs.all { it.top == 0 && it.bottom == 2 })
        // 段之间有间隙（x 不连续）。
        val xs = rs.map { it.left }.sorted()
        assertTrue(xs.zipWithNext().any { (a, b) -> b - a > 3 })
    }

    @Test
    fun `dotted 左边成多段`() {
        val rs = borders(box(border = Edges(left = 2f), styles = BorderStyleEdges(left = BorderStyle.DOTTED)))
        assertTrue("dotted 应拆成多段，实际 ${rs.size}", rs.size > 1)
        assertTrue(rs.all { it.left == 0 })
    }

    @Test
    fun `未声明 style 回退 solid（旧行为）`() {
        // 只有宽度：styles 为 null → 整带绘制。
        val rs = borders(box(styles = null))
        assertEquals(4, rs.size)
    }

    @Test
    fun `四边异色`() {
        val rs = borders(
            box(colors = BorderColorEdges("#ffff0000", "#ff00ff00", "#ff0000ff", "#ff000000")),
        )
        assertEquals(4, rs.size)
        // 按几何判边（左右整带 top 同为 0，不能只看 top）。
        fun edge(r: DrawRect) = when {
            r.top == 0 && r.bottom == 2 -> "top"
            r.top == 38 && r.bottom == 40 -> "bottom"
            r.left == 0 && r.right == 2 -> "left"
            r.left == 98 && r.right == 100 -> "right"
            else -> error("未知边 $r")
        }
        val byPos = rs.associateBy(::edge)
        assertEquals("#ffff0000", byPos["top"]!!.colorHex)
        assertEquals("#ff0000ff", byPos["bottom"]!!.colorHex)
        assertEquals("#ff000000", byPos["left"]!!.colorHex)
        assertEquals("#ff00ff00", byPos["right"]!!.colorHex)
    }

    @Test
    fun `currentColor 回退文本色`() {
        val rs = borders(box(colors = null, textColor = "#ff445566"))
        assertEquals(4, rs.size)
        assertTrue(rs.all { it.colorHex == "#ff445566" })
    }

    @Test
    fun `无文本色时回退默认墨色`() {
        val rs = borders(box(colors = null, textColor = null))
        assertEquals(4, rs.size)
        assertTrue(rs.all { it.colorHex == BoxDrawer.FALLBACK_INK_HEX })
    }

    @Test
    fun `零宽边不绘制`() {
        val rs = borders(box(border = Edges()))
        assertTrue(rs.isEmpty())
    }
}
