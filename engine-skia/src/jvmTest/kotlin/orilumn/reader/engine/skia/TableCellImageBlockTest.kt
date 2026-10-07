package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Test
import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.ShapedGeometry
import orilumn.reader.engine.laying.TableCellBlock
import orilumn.reader.engine.laying.TableCellLayout
import orilumn.reader.engine.laying.TableRowLayout

/**
 * Rust 简介 Ferris 表回归：块级独图格（`img{display:block}` 书的 `<td><img></td>`）必须落图。
 *
 * 格块是 replaceable 形（`shapeGeometry` 对 img 根早退，`lineCount` 恒 1、高度现成），
 * 但 shape 文本为空 —— [TableCellLines] 若只走 U+FFFC 占位配对，整块被跳过、图静默丢失。
 * 本锁直接喂行展开单源，断言 PageImage 落位与尺寸（与 `replacedUsedSize` 同口径）。
 */
class TableCellImageBlockTest {

    @Test
    fun `block level img cell emits its image`() {
        val img = MarkupElement("img", mapOf("src" to "a.png"))
        val td = MarkupElement("td", children = listOf(img))
        img.parent = td
        val imgStyle = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f, widthPx = 60f)
        val tdStyle = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f)
        val block = TableCellBlock(el = img, style = imgStyle, text = "\uFFFC", runs = emptyList(), imageRoot = img)
        block.shape = ShapedGeometry(isReplaceable = true, replaceableBottom = 40)
        block.top = 0
        block.height = 40
        val cell = TableCellLayout(
            el = td, col = 0, colSpan = 1, x = 10, width = 200, height = 40,
            isHeader = false, blocks = listOf(block),
        )
        val table = TableRowLayout(columnXs = intArrayOf(10), columnWidths = intArrayOf(200), cells = listOf(cell))
        val loader = ImageBoundsReader { _, _ -> 120 to 80 }
        val win = TableCellLines.expand(
            table, rowTop = 100, rowHeight = 40, rowCharBase = 0,
            styleOf = { if (it === img) imgStyle else tdStyle }, fallback = tdStyle,
            letterSpacingEm = 0f, imageLoader = loader, chapterHref = "OEBPS/Text/00_3.xhtml",
        )
        assertEquals(0, win.lines.size)
        assertEquals(1, win.images.size)
        val im = win.images[0]
        assertEquals("a.png", im.src)
        // width=60 指定宽 + 120x80 内在比 ⇒ 60x40；独占即块左，y 取块顶。
        assertEquals(60, im.widthPx)
        assertEquals(40, im.heightPx)
        assertEquals(10, im.xLeft)
        assertEquals(100, im.yTop)
        assertEquals(140, im.yBottom)
    }
}
