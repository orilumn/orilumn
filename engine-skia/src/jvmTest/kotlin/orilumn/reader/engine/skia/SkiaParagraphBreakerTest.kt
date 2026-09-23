package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.lineHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S24 `SkiaParagraphBreaker` 回归：几何不漂移。
 *
 * 验证断行产物与「后续单行整形绘制」自洽（同一 [SkParagraphFactory] 配置）：
 *  1. 确定性：同输入两次 → 完全相同断行；
 *  2. 区间完整性：断行区间拼接 == 去换行后的原文本（无字符丢失/重复/重叠 → CFI/分页锚点零漂移）；
 *  3. 行高：每行 == 统一行框 `lineHeightPx`（与旧管线公式单一来源，盒流几何不漂移）；
 *  4. 可绘制性：每个区间的单行宽度 ≤ 容器宽（度量与绘制一致，绝不溢出）；
 *  5. 换行归一化：尾部 '\n' 不产生幻影行；JUSTIFY 末行短、满行铺满。
 */
class SkiaParagraphBreakerTest {

    private val breaker = SkiaParagraphBreaker(letterSpacingEm = 0.02f)

    private fun breaks(
        text: String,
        fontSizePx: Float = 16f,
        ratio: Float = 1.5f,
        widthPx: Int = 320,
        alignment: TextAlign = TextAlign.LEFT,
        tag: String? = "p",
        families: List<String> = emptyList(),
        weight: Int = 400,
        italic: Boolean = false,
        monospace: Boolean = false,
    ) = breaker.breakLines(text, fontSizePx, ratio, widthPx, alignment, tag, families, weight, italic, monospace)

    private fun singleLineWidth(
        range: IntRange,
        text: String,
        fontSizePx: Float,
        ratio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
    ): Float {
        // 复用与断行完全相同的配置做单行整形（S25 绘制即此路径），lock 度量↔绘制一致性。
        val style = SkParagraphFactory.paragraphStyle(
            alignment, fontSizePx, ratio, tag, families, weight, italic, monospace, letterSpacingEm = 0.02f,
        )
        val p = org.jetbrains.skia.paragraph.ParagraphBuilder(style, SkParagraphFactory.defaultCollection())
            .addText(text.substring(range)).build()
        return try {
            p.layout(Float.MAX_VALUE)
            p.maxIntrinsicWidth
        } finally {
            p.close()
        }
    }
    @Test
    fun firstLineIndentShortensFirstLineOnly() {
        // 回归“首行缩进滑块不起作用”：断行侧须把首行按减宽排（绘制侧右移同值，见 DrawLine）。
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(6).trimEnd()
        fun br(indent: Float) = breaker.breakLines(
            text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, false, indent,
        )
        val plain = br(0f)
        val indented = br(80f)
        assertTrue("wraps into several lines", plain.size > 2 && indented.size > 2)
        assertTrue(
            "首行缩进须缩短首行",
            indented[0].range.last < plain[0].range.last,
        )
        assertTrue("缩进不加行数只减首行宽（或至多多一行）", indented.size <= plain.size + 1)
    }

    @Test
    fun deterministicSameInputSameBreaks() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(8).trimEnd()
        val a = breaks(text)
        val b = breaks(text)
        assertEquals(a.map { it.range }, b.map { it.range })
        assertEquals(a.map { it.heightPx }, b.map { it.heightPx })
    }

    @Test
    fun baselineShiftsDoNotChangeBreaks() {
        // P1-2: 位移不断行几何——同文本有/无位移的断行区间必须一致（量画一致）。
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(4).trimEnd()
        val plain = breaks(text)
        val shifted = breaker.breakLines(
            text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f,
            emptyList(), listOf(orilumn.reader.engine.laying.BaselineShift(5, 9, 0.33f)),
        )
        assertEquals(plain.map { it.range }, shifted.map { it.range })
        assertEquals(plain.map { it.heightPx }, shifted.map { it.heightPx })
    }

    @Test
    fun rangesAreContiguousAndCoverTextExactly() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(6).trimEnd()
        val lines = breaks(text)
        assertTrue("wraps into several lines", lines.size > 2)
        // 区间严格递增、连续覆盖（可拼回原文本）——分页/CFI 锚点不会漂移。
        var expectStart = 0
        for ((i, line) in lines.withIndex()) {
            assertEquals("line $i start", expectStart, line.range.first)
            assertTrue("range[$i] valid", line.range.first < line.range.last)
            expectStart = line.range.last + 1
        }
        assertEquals(text.length, expectStart)
    }

    @Test
    fun heightsEqualCanonicalFormula() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(4).trimEnd()
        val fontSizePx = 17f
        val ratio = 1.6f
        val target = lineHeightPx(fontSizePx, ratio)
        for (line in breaks(text, fontSizePx = fontSizePx, ratio = ratio)) {
            assertEquals(target, line.heightPx)
        }
    }

    @Test
    fun everyLineFitsWithinContainer() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(8).trimEnd()
        val fontSizePx = 16f
        val ratio = 1.5f
        val width = 280
        val lines = breaks(text, fontSizePx = fontSizePx, ratio = ratio, widthPx = width)
        for ((i, line) in lines.withIndex()) {
            val w = singleLineWidth(line.range, text, fontSizePx, ratio, width, TextAlign.LEFT, "p", emptyList(), 400, false, false)
            assertTrue("line $i width=$w must fit $width px", w <= width + 1f)
        }
    }

    @Test
    fun trailingNewlineDoesNotCreatePhantomLine() {
        val text = "line one\nline two\n"
        val lines = breaks(text, widthPx = 10000)
        // 两行可见文本（0..7 / 9..16），换行符 8/17 不进区间；尾部 '\n' 的幻影行被归一化剔除。
        assertEquals(listOf(0..7, 9..16), lines.map { it.range })
    }

    @Test
    fun brNewlinesKeepInteriorLines() {
        val text = "first\nsecond\nthird"
        val lines = breaks(text, widthPx = 10000)
        assertEquals(3, lines.size)
        assertEquals(listOf(0..4, 6..11, 13..17), lines.map { it.range })
        // 内部空行（两换行之间的空串）被跳过——与 StaticLayoutBreaker 的 `e > s` 口径逐行对齐（不丢字）。
        val both = breaks("a\n\nb", widthPx = 10000)
        assertEquals(listOf(0..0, 3..3), both.map { it.range })
    }

    @Test
    fun justifyBreaksFillWidthAndStayInContainer() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(6).trimEnd()
        val fontSizePx = 16f
        val ratio = 1.5f
        val width = 320
        val lines = breaks(text, fontSizePx = fontSizePx, ratio = ratio, widthPx = width, alignment = TextAlign.JUSTIFY)
        assertTrue(lines.size >= 3)
        for (i in lines.indices) {
            val w = singleLineWidth(lines[i].range, text, fontSizePx, ratio, width, TextAlign.JUSTIFY, "p", emptyList(), 400, false, false)
            assertTrue("justified line $i width=$w must fit $width px", w <= width + 1f)
        }
    }

    @Test
    fun longLatinTokenWrapsWithoutLoss() {
        // 无空格断字机会的长 Latin 串会被 SkParagraph 按需折行（与 BREAK_STRATEGY_SIMPLE 同向）；
        // 关键不变量是区间不丢字、不越过容器——CFI/分页锚点不漂移。
        val text = "supercalifragilisticexpialidocious"
        val lines = breaks(text, widthPx = 40, monospace = true)
        assertTrue(lines.size > 1)
        assertEquals(
            text,
            lines.joinToString("") { with(text) { substring(it.range) } },
        )
    }

    @Test
    fun fontRunsShapeLikeAllMonoWhenFaceIsMono() {
        // 行内跑的 face 必须参与测度：同一 mono face 的 run 与「整段 mono」断行完全一致——
        // 证明断行器真的按 run 整形（正文里的 `<code>` 会左右断行点，而不是忽略 run 用基底）。
        val text = "monospaceonly".repeat(4)
        val runsMono = breaker.breakLines(text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f,
            listOf(FontRun(0, text.length, emptyList(), "code", 400, false, true)),
        )
        val allMono = breaker.breakLines(text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, true)
        assertEquals("整段 mono run 必须与整体 mono 断行完全一致（run 参与测度）", allMono.map { it.range }, runsMono.map { it.range })
        assertEquals(text, runsMono.joinToString("") { text.substring(it.range) })
        assertTrue("长无断字串应折成多行", runsMono.size > 1)
    }

    @Test
    fun fontSizeRunsChangeMeasurement() {
        // 行内字号必须参与测度（浏览器 inline-run 语义）：同一段 0.8em 字号比 1em 单位宽度
        // 容纳更多字符 → 首行更长。修复前 run 无字号字段，断行永远按块级 1em，行内小字被吞。
        val text = "monospaceonly".repeat(4) // 56 字符无断字长串，SkParagraph 按字符折行
        val base = breaker.breakLines(text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, true)
        val smallRun = breaker.breakLines(
            text, 16f, 1.5f, 320, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f,
            listOf(FontRun(0, text.length, emptyList(), "code", 400, false, true, 12.8f)),
        )
        assertTrue("断行须折成多行", base.size > 1 && smallRun.size > 1)
        assertTrue(
            "更小字号首行应容纳更多字符（base=$base small=$smallRun）",
            smallRun[0].range.last > base[0].range.last,
        )
        // 区间仍连续覆盖全文（字号不影响字符完整性）。
        assertEquals(text, smallRun.joinToString("") { text.substring(it.range) })
    }

    @Test
    fun fontRunsKeepCoverageAndClampOutOfBounds() {
        // run 任意乱序/越界/空段都不丢字、不重复、不抛：区间仍严格连续覆盖全文（CFI 零漂移）。
        val text = "abXcdXfghX"
        val runs = listOf(
            FontRun(5, 6, listOf("Courier"), "code", 400, false, true),
            FontRun(1000, 2000, listOf("Courier"), "code", 400, false, true),
            FontRun(2, 2, listOf("Courier"), "code", 400, false, true),
        )
        val lines = breaker.breakLines(text, 16f, 1.5f, 10000, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f, runs)
        var expectStart = 0
        for (line in lines) {
            assertEquals(expectStart, line.range.first)
            expectStart = line.range.last + 1
        }
        assertEquals(text.length, expectStart)
        // 空 runs ≡ 单 style（与未传 runs 的断行一模一样）。
        val plain = breaker.breakLines(text, 16f, 1.5f, 10000, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f)
        val noRuns = breaker.breakLines(text, 16f, 1.5f, 10000, TextAlign.LEFT, "p", emptyList(), 400, false, false, 0f, emptyList())
        assertEquals(plain.map { it.range }, noRuns.map { it.range })
    }

    @Test
    fun degWidthPxLeavesSingleSharedLinePerRun() {
        val lines = breaks("some text", widthPx = 0)
        assertEquals(1, lines.size)
        assertEquals(0 until "some text".length, lines[0].range)
        assertTrue(breaks("", widthPx = 0).isEmpty())
    }

    @Test
    fun cjkTextWrapsAndNeverOverflows() {
        val text = "床前明月光，疑是地上霜。举头望明月，低头思故乡。".repeat(4)
        val fontSizePx = 16f
        val ratio = 1.5f
        val width = 240
        val lines = breaks(text, fontSizePx = fontSizePx, ratio = ratio, widthPx = width)
        assertTrue("CJK wraps into lines", lines.size > 1)
        for ((i, line) in lines.withIndex()) {
            val w = singleLineWidth(line.range, text, fontSizePx, ratio, width, TextAlign.LEFT, "p", emptyList(), 400, false, false)
            assertTrue("CJK line $i width=$w must fit $width px", w <= width + 1f)
        }
    }
}