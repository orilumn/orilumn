package orilumn.reader.engine.skia

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode
import org.jetbrains.skia.paragraph.StrutStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 回归：`TextStyle.setBaselineShift` 的单位/方向/几何影响（`vertical-align`
 * 绘制走原生单段落方案的前提）。
 *
 * 实测语义（Skia 坐标 y 向下，单位 px）：
 *  - 正值下移（SUB 方向），无 strut 时行盒顶不动、被移 run 下沉；
 *  - 负值上移（SUPER 方向），无 strut 时行盒向上扩张、其余 run 下推；
 *  - strut 强制行高（[StrutStyle.isHeightForced]）固定基线：两方向都只动被移 run，
 *    与 CSS 行盒语义一致；
 *  - 位移不改变 intrinsic 宽度与断行位置（量画一致）。
 */
class BaselineShiftProbeTest {

    private val collection = FontCollection().setDefaultFontManager(FontMgr.default)

    private fun baseStyle(fontSize: Float = 16f, height: Float? = null, strut: Boolean = false) = ParagraphStyle().apply {
        textStyle = TextStyle().setFontSize(fontSize).apply { if (height != null) setHeight(height) }
        if (strut) {
            strutStyle = StrutStyle()
                .setFontFamilies(arrayOf("sans-serif"))
                .setFontSize(fontSize)
                .setHeight(height ?: 1f)
                .setLeading(0f)
                .setEnabled(true)
                .setHeightForced(true)
                .setHeightOverridden(false)
        }
    }

    private fun topOf(text: String, shiftAt: IntRange?, shiftPx: Float, range: IntRange, height: Float? = null, strut: Boolean = false): Float {
        val p = ParagraphBuilder(baseStyle(height = height, strut = strut), collection).apply {
            if (shiftAt == null) {
                addText(text)
            } else {
                addText(text.substring(0, shiftAt.first))
                pushStyle(TextStyle().setFontSize(16f).apply {
                    if (height != null) setHeight(height)
                    setBaselineShift(shiftPx)
                })
                addText(text.substring(shiftAt))
                popStyle()
            }
        }.build()
        try {
            p.layout(Float.MAX_VALUE)
            val boxes = p.getRectsForRange(range.first, range.last + 1, RectHeightMode.TIGHT, RectWidthMode.TIGHT)
            assertTrue("expected boxes for $range", boxes.isNotEmpty())
            return boxes.minOf { it.rect.top }
        } finally {
            p.close()
        }
    }

    @Test
    fun positiveShiftMovesRunDown() {
        val shiftedTop = topOf("AB", 1..1, 8f, 1..1)
        val anchorTop = topOf("AB", 1..1, 8f, 0..0)
        assertEquals("unshifted run stays at line top", 0f, anchorTop, 0.5f)
        assertEquals("positive shift moves down by px", 8f, shiftedTop, 0.5f)
    }

    @Test
    fun negativeShiftGrowsLineBoxUpward() {
        val shiftedTop = topOf("AB", 1..1, -8f, 1..1)
        val anchorTop = topOf("AB", 1..1, -8f, 0..0)
        assertEquals("shifted run sits at grown line top", 0f, shiftedTop, 0.5f)
        assertEquals("other runs are pushed down", 8f, anchorTop, 0.5f)
    }

    @Test
    fun forcedStrutFixesBaselineBothDirections() {
        for (s in listOf(8f, -8f)) {
            val tB = topOf("AB", 1..1, s, 1..1, height = 1.5f, strut = true)
            val tA = topOf("AB", 1..1, s, 0..0, height = 1.5f, strut = true)
            val pure = topOf("AB", null, 0f, 0..0, height = 1.5f, strut = true)
            assertEquals("shift=$s base run must stay at pure baseline", pure, tA, 0.5f)
            assertEquals("shift=$s run must move by px", s, tB - tA, 0.5f)
        }
    }

    @Test
    fun shiftDoesNotChangeLineGeometry() {
        fun width(shiftPx: Float): Float {
            val p = ParagraphBuilder(baseStyle(), collection).apply {
                addText("Hello world, this is a long line for measurement. ")
                pushStyle(TextStyle().setFontSize(16f).setBaselineShift(shiftPx))
                addText("super")
                popStyle()
            }.build()
            try {
                p.layout(200f)
                return p.maxIntrinsicWidth
            } finally {
                p.close()
            }
        }
        assertEquals("shift must not change intrinsic width", width(0f), width(8f), 0.01f)
        assertEquals("shift must not change intrinsic width", width(0f), width(-8f), 0.01f)
    }
}
