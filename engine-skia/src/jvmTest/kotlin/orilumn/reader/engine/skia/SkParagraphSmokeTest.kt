package orilumn.reader.engine.skia

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S23 SkParagraph 单行整形冒烟。
 *
 * 验证两条 G 阶段铁律（见 `docs/KMP+CMP迁移方案.md` §5 阶段1）：
 *  1. `layout(Float.MAX_VALUE)` 强制 single-line 整形：SkParagraph 只做单行整形，禁止内部二次折行；
 *  2. `Alignment.JUSTIFY` 两端对齐：满行铺满约束宽（最后一个不满行除外）。
 *
 * 度量与后续绘制（S25 LineWindowDrawer）必须复用**完全相同的** FontCollection / FontStyle 配置，
 * 此处冒烟即锁定这一契约的最小形态。
 */
class SkParagraphSmokeTest {

    private val collection = FontCollection().setDefaultFontManager(FontMgr.default)

    private fun paragraph(
        text: String,
        width: Float,
        alignment: Alignment = Alignment.LEFT,
        fontSize: Float = 16f,
    ): org.jetbrains.skia.paragraph.Paragraph {
        val style = ParagraphStyle().apply {
            this.alignment = alignment
            textStyle = TextStyle().setFontSize(fontSize)
        }
        return ParagraphBuilder(style, collection)
            .addText(text)
            .build()
            .layout(width)
    }

    /**
     * Width (#px) that a plain latin glyph occupies at [fontSize] — the widest the paragraph can get.
     */

    @Test
    fun layoutMaxValueKeepsASingleLine() {
        val text = "The quick brown fox jumps over the lazy dog. " +
            "The quick brown fox jumps over the lazy dog. " +
            "The quick brown fox jumps over the lazy dog. " +
            "The quick brown fox jumps over the lazy dog."
        val p = paragraph(text, Float.MAX_VALUE)
        // 单行整形：全段必须仍是一行；断行范围覆盖全文本，绝无第二次包裹。
        assertEquals("long text stays on one line", 1, p.lineNumber)
        p.close()
    }

    @Test
    fun layoutMaxValueNeverWrapsInsideALine() {
        // 即便把约束宽设成一个极小值，MAX_VALUE 整形也不产生换行（这就是"禁二次折行"）。
        val text = "abcdefghijklmnopqrstuvwxyz"
        val p = paragraph(text, Float.MAX_VALUE)
        assertEquals(1, p.lineNumber)
        val metrics = p.lineMetrics
        assertEquals("single line covers the whole text", metrics[0].startIndex, 0)
        assertEquals("single line covers the whole text", metrics[0].endIndex, text.length)
        p.close()
    }

    @Test
    fun justifyFillsEveryFullLine() {
        // 无空格断字的 ASCII 文本在某宽度下自然折成多行；JUSTIFY 下除末行外每行都应铺满约束宽。
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(4).trimEnd()
        val width = 300f
        val p = paragraph(text, width, alignment = Alignment.JUSTIFY)
        assertTrue("paragraph wraps into several lines", p.lineNumber >= 3)
        val lines = p.lineMetrics
        for (i in 0 until lines.size - 1) {
            // 满行（非末行）必须铺满 layout 约束宽（两端对齐），误差容忍一个字形步进。
            assertTrue(
                "line $i must be justified (right=${lines[i].right}, width=$width)",
                lines[i].right in (width - width / 200f)..(width + width / 200f),
            )
        }
        assertEquals("unfilled last line is shorter", true, lines.last().right <= width)
        p.close()
    }

    @Test
    fun leftAlignmentKeepsRaggedRight() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(4).trimEnd()
        val width = 300f
        val p = paragraph(text, width, alignment = Alignment.LEFT)
        assertTrue(p.lineNumber >= 3)
        val lines = p.lineMetrics
        val rights = lines.joinToString { "${it.right.toInt()}" }
        // LEFT 对齐下大多数行右缘是"字形结束"而非铺满宽 → 明显短于约束宽。
        assertTrue(
            "left-aligned lines must be shorter than the constraint (rights=$rights, width=$width)",
            lines.dropLast(1).any { it.right < width - width / 30f },
        )
        p.close()
    }
}