package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块级标记单测：行首标记识别 / 硬断行 / 代码块围栏 / 转义 / 与行内标记的往返。
 * 口径见 `docs/正文修改-块级标记.md`。
 */
class BlockMarkupTest {

    private fun sig(tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSig(tag, attrs.toMap())

    private fun span(s: Int, e: Int, tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSpan(s, e, sig(tag, *attrs))

    private fun blocks(markup: String) =
        BlockMarkup.split(markup).blocks.map { it.kind to it.content }

    // ---------------------------------------------------------------- 行首标记

    @Test
    fun markersAreRecognised() {
        assertEquals(BlockKind.Heading2 to 3, BlockMarkup.markerAt("## 标题"))
        assertEquals(BlockKind.Heading6 to 7, BlockMarkup.markerAt("###### 六级"))
        assertEquals(BlockKind.Quote to 2, BlockMarkup.markerAt("> 引用"))
        assertEquals(BlockKind.Quote to 1, BlockMarkup.markerAt(">引用"))
        assertEquals(BlockKind.Bullet to 2, BlockMarkup.markerAt("- 项"))
        assertEquals(BlockKind.Bullet to 2, BlockMarkup.markerAt("* 项"))
        assertEquals(BlockKind.Bullet to 2, BlockMarkup.markerAt("+ 项"))
        assertEquals(BlockKind.Ordered to 3, BlockMarkup.markerAt("1. 项"))
        assertEquals(BlockKind.Ordered to 4, BlockMarkup.markerAt("12) 项"))
    }

    @Test
    fun nonMarkersAreLeftAlone() {
        assertNull(BlockMarkup.markerAt(""))
        assertNull(BlockMarkup.markerAt("#没有空格"))
        assertNull(BlockMarkup.markerAt("####### 七级"))
        assertNull(BlockMarkup.markerAt("-没有空格"))
        assertNull(BlockMarkup.markerAt("1.没有空格"))
        assertNull(BlockMarkup.markerAt("1 项"))
        // 转义开头的行按普通文字（读者写的 `\# 不是标题`）。
        assertNull(BlockMarkup.markerAt("\\# 不是标题"))
        assertNull(BlockMarkup.markerAt("\\- 不是列表"))
    }

    @Test
    fun oneLineIsOneBlockAndHardBreakJoinsLines() {
        assertEquals(
            listOf<Pair<BlockKind?, String>>(
                BlockKind.Heading1 to "第一章",
                null to "上句\n下句",
                BlockKind.Bullet to "项",
            ),
            blocks("# 第一章\n上句\\\n下句\n- 项"),
        )
    }

    @Test
    fun trailingBackslashAtEndKeepsTheBreak() {
        assertEquals(listOf<Pair<BlockKind?, String>>(null to "a\n"), blocks("a\\\n"))
        assertEquals(listOf<Pair<BlockKind?, String>>(null to "a\n\nb"), blocks("a\\\n\\\nb"))
    }

    @Test
    fun evenBackslashRunIsLiteral() {
        // 行尾两个反斜杠 = 转义后的一个字面反斜杠，不是硬断行：两行各成一块。
        assertEquals(
            listOf<Pair<BlockKind?, String>>(null to "a\\\\", null to "b"),
            blocks("a\\\\\nb"),
        )
    }

    // ---------------------------------------------------------------- 代码块

    @Test
    fun fenceHoldsMultiLineContent() {
        assertEquals(
            listOf<Pair<BlockKind?, String>>(
                null to "前",
                BlockKind.Pre to "val a = 1\nval b = 2",
                null to "后",
            ),
            blocks("前\n```\nval a = 1\nval b = 2\n```\n后"),
        )
    }

    @Test
    fun fenceContentIsNotBlockParsed() {
        assertEquals(
            listOf<Pair<BlockKind?, String>>(BlockKind.Pre to "# 不是标题\n- 不是列表"),
            blocks("```\n# 不是标题\n- 不是列表\n```"),
        )
    }

    @Test
    fun unterminatedFenceIsReported() {
        val split = BlockMarkup.split("```\nval a = 1")
        assertTrue(split.unterminatedFence)
        assertEquals(BlockKind.Pre to "val a = 1", split.blocks.single().kind to split.blocks.single().content)
    }

    @Test
    fun markerRangesCoverPrefixes() {
        val ranges = BlockMarkup.split("# 标题\n正文\n```\nx\n```").markerRanges
        assertEquals(listOf(0..1, 8..10, 14..16), ranges)
    }

    // ---------------------------------------------------------------- 往返

    private fun show(vararg segments: InlineMarkup.Segment) =
        InlineMarkup.show(segments.toList(), emptyList())

    @Test
    fun blockMarkersRoundTrip() {
        val segs = listOf(
            InlineMarkup.Segment("标题", BlockKind.Heading2),
            InlineMarkup.Segment("引用", BlockKind.Quote),
            InlineMarkup.Segment("项一", BlockKind.Bullet),
            InlineMarkup.Segment("项二", BlockKind.Ordered),
            InlineMarkup.Segment("正文", null),
        )
        val markup = InlineMarkup.show(segs, emptyList())
        assertEquals("## 标题\n> 引用\n- 项一\n1. 项二\n正文", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals(segs, parsed.segments)
        assertEquals("标题\n引用\n项一\n项二\n正文", parsed.text)
    }

    @Test
    fun preBlockRoundTrips() {
        val segs = listOf(InlineMarkup.Segment("fun main() {\n    println(1)\n}", BlockKind.Pre))
        val markup = InlineMarkup.show(segs, emptyList())
        assertEquals("```\nfun main() {\n    println(1)\n}\n```", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals(segs, parsed.segments)
        assertEquals("fun main() {\n    println(1)\n}", parsed.text)
    }

    @Test
    fun preBlockContentWithFenceLineGetsLongerFence() {
        val segs = listOf(InlineMarkup.Segment("```\ncode\n```", BlockKind.Pre))
        val markup = InlineMarkup.show(segs, emptyList())
        assertTrue(markup.startsWith("````\n"))
        assertEquals(segs, InlineMarkup.parse(markup).segments)
    }

    @Test
    fun paragraphStartingWithMarkerIsEscaped() {
        assertEquals("\\- 这不是列表", show(InlineMarkup.Segment("- 这不是列表")))
        assertEquals("\\# 这不是标题", show(InlineMarkup.Segment("# 这不是标题")))
        assertEquals("\\> 这不是引用", show(InlineMarkup.Segment("> 这不是引用")))
        assertEquals("\\1. 这不是列表", show(InlineMarkup.Segment("1. 这不是列表")))
        for (t in listOf("- 这不是列表", "# 这不是标题", "> 这不是引用", "1. 这不是列表")) {
            val parsed = InlineMarkup.parse(InlineMarkup.show(listOf(InlineMarkup.Segment(t)), emptyList()))
            assertEquals(t, parsed.text)
            assertEquals(listOf<BlockKind?>(null), parsed.segments.map { it.block })
        }
    }

    @Test
    fun paragraphStartingWithFenceIsEscaped() {
        val text = "```\n有没有收尾"
        val markup = InlineMarkup.show(listOf(InlineMarkup.Segment(text)), emptyList())
        val parsed = InlineMarkup.parse(markup)
        assertEquals(text, parsed.text)
        assertEquals(listOf<BlockKind?>(null), parsed.segments.map { it.block })
    }

    @Test
    fun brInsideBlockIsHardBreakAndInlineMarkupStillWorks() {
        val segs = listOf(InlineMarkup.Segment("上句\n下句", BlockKind.Quote))
        val markup = InlineMarkup.show(segs, listOf(span(1, 2, "em")))
        assertEquals("> 上*句*<br>下句", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals(segs, parsed.segments)
        assertEquals(1 to 2, parsed.spans.single().start to parsed.spans.single().endExclusive)
    }

    @Test
    fun codeSpanIsNotFormedInsidePre() {
        val segs = listOf(InlineMarkup.Segment("a *b* `c`", BlockKind.Pre))
        val parsed = InlineMarkup.parse(InlineMarkup.show(segs, emptyList()))
        assertEquals(segs, parsed.segments)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun emptyParagraphSurvives() {
        val segs = listOf(InlineMarkup.Segment("", null), InlineMarkup.Segment("", BlockKind.Quote))
        val markup = InlineMarkup.show(segs, emptyList())
        val parsed = InlineMarkup.parse(markup)
        assertEquals(segs, parsed.segments)
        assertFalse(parsed.errors.isNotEmpty())
    }
}
