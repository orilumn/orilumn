package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Test
import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement

/**
 * Kotlin 版权页开书闪退回归：轻路径窗口化 style 表只注册段落内联子树，块级子树后代
 * （如表格格内图）不在表中。`styles.getValue(img)` 直接抛 NoSuchElementException，
 * 从 `shapeGeometry → fillTableRowCells → incrementalLayoutForPage` 一路炸到开书失败。
 *
 * 修法：缺键时经级联 resolver 拿真样式（轻路径传 `prepare::resolveStyle`），
 * 穷尽才回退旧 16px 行为 —— 不再抛。
 */
class ImageLineHeightsPartialMapTest {

    private val loader = ImageBoundsReader { _, _ -> 120 to 80 }
    private val broken = listOf(BrokenLine(0..0, 20))
    private val classify = BlockClassify { false }
    private val imgStyle = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f, widthPx = 60f)
    private val tdStyle = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f)

    private fun cell(): Pair<MarkupElement, MarkupElement> {
        val img = MarkupElement("img", mapOf("src" to "a.png"))
        val td = MarkupElement("td", children = listOf(img))
        img.parent = td
        return td to img
    }

    @Test
    fun `missing img entry resolves through resolver instead of throwing`() {
        val (td, img) = cell()
        // 表里故意没有 img 项（轻路径窗口化表的真实形状），只有块根。
        val styles = mapOf(td to tdStyle)
        val hs = adjustLineHeightsForInlineImages(
            "\uFFFC", broken, td, styles, classify, HIDDEN_NONE, 800, loader, "ch.xhtml",
            resolveStyle = { if (it === img) imgStyle else null },
        )
        // 真样式宽 60 + 内在 120x80 ⇒ 行高抬到 40（回退 16px 样式无宽 ⇒ 80，见下条）。
        assertEquals(listOf(40), hs)
    }

    @Test
    fun `no resolver falls back without throwing`() {
        val (td, _) = cell()
        val styles = mapOf(td to tdStyle)
        val hs = adjustLineHeightsForInlineImages(
            "\uFFFC", broken, td, styles, classify, HIDDEN_NONE, 800, loader, "ch.xhtml",
        )
        assertEquals(listOf(80), hs)
    }
}
