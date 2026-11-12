package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式痕迹：字一个没改、只改了标记时，把被删 / 新写的标记排进审阅空间（`## ` / `> ` …）。
 *
 * 红绿的字义是**标记**，不是正文：`## 你好世界` 删掉 `## ` 时留一个红 `## `，
 * 而不是把「你好世界」染成红色（那是「删掉了这些字」的意思，是假话）。
 */
class FormatTracesTest {

    private fun traces(
        oldText: String,
        newText: String,
        oldKind: BlockKind?,
        newKind: BlockKind?,
    ): List<Pair<String, Boolean>> =
        FormatTraces.block(oldText, newText, oldKind, newKind).map { it.text to it.isNew }

    @Test
    fun removedHeadingMarkerIsRed() {
        // 用户的原例：`## 你好世界` 删掉 `## ` 变回正文。
        assertEquals(
            listOf("## " to false),
            traces("你好世界", "你好世界", BlockKind.Heading2, BlockKind.Paragraph),
        )
    }

    @Test
    fun addedQuoteMarkerIsGreen() {
        assertEquals(
            listOf("> " to true),
            traces("你好世界", "你好世界", BlockKind.Paragraph, BlockKind.Quote),
        )
    }

    @Test
    fun changedMarkerShowsBothSides() {
        // 标题变引用：删掉的是 `## `（红），新写的是 `> `（绿），书写顺序与卡片一致。
        assertEquals(
            listOf("## " to false, "> " to true),
            traces("你好世界", "你好世界", BlockKind.Heading2, BlockKind.Quote),
        )
    }

    @Test
    fun unchangedKindLeavesNoTrace() {
        assertTrue(traces("你好世界", "你好世界", BlockKind.Paragraph, BlockKind.Paragraph).isEmpty())
        assertTrue(traces("你好世界", "你好世界", BlockKind.Heading2, BlockKind.Heading2).isEmpty())
    }

    @Test
    fun changedTextLeavesNoMarkerTrace() {
        // 改了字的修订不画标记痕迹：红绿已经标在字上了，再排标记只会更糊。
        assertTrue(traces("你好世界", "你好明天", BlockKind.Heading2, BlockKind.Paragraph).isEmpty())
    }

    @Test
    fun unknownKindLeavesNoTrace() {
        // 取不到原书形态（老宿主 / 结构未就绪）：宁可不画，也不猜一个标记出来。
        assertTrue(traces("你好世界", "你好世界", null, BlockKind.Paragraph).isEmpty())
        assertTrue(traces("你好世界", "你好世界", BlockKind.Heading2, null).isEmpty())
    }

    @Test
    fun tracesSitAtBlockStart() {
        val plan = ReviewOverlay.plan(
            "你好世界",
            "你好世界",
            IntArray(0),
            FormatTraces.block("你好世界", "你好世界", BlockKind.Heading2, BlockKind.Paragraph),
        )!!
        assertEquals("## 你好世界", plan.text)
        assertEquals(7, plan.length)
        // 红 `## ` 三个字都在删除区间里；正文一个字都不红。
        assertEquals(listOf(0 until 3), spans(plan.deleted))
        assertTrue(plan.inserted.isEmpty())
        // 三个痕迹字符各自登记一次（取文字时逐位跳过）。
        assertEquals(listOf(0, 1, 2), plan.traces.toList())
        assertTrue(plan.marks.isEmpty())
        // 痕迹是「就地插入」：排版把这三个字插在段首，正文各字原位不动。
        assertEquals(listOf(0), plan.insertions.map { it.at })
        assertEquals("## ", plan.insertions[0].text)
        assertEquals(0, plan.beforeOf(0))
        assertEquals(3, plan.afterOf(0))
    }

    @Test
    fun addedItalicShowsGreenStars() {
        // 用户书里那条：「函数是执行特定任务的一组指令。」一个字没改，只加上了斜体。
        val text = "函数是执行特定任务的一组指令。"
        val traces = FormatTraces.inline(
            text,
            oldSpans = emptyList(),
            newSpans = listOf(InlineMarkup.StyleSpan(0, text.length, InlineMarkup.StyleSig("em"))),
        )
        assertEquals(2, traces.size)
        // 开标记排在第一个字之前、闭标记排在最后一个字之后，都是绿字。
        assertEquals(0, traces[0].at)
        assertEquals("*", traces[0].text)
        assertTrue(traces[0].isNew)
        assertEquals(text.length, traces[1].at)
        assertEquals("*", traces[1].text)
        assertTrue(traces[1].isNew)
    }

    @Test
    fun removedBoldShowsRedDoubleStars() {
        val traces = FormatTraces.inline(
            "你好世界",
            oldSpans = listOf(InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("strong"))),
            newSpans = emptyList(),
        )
        assertEquals(listOf("**" to false, "**" to false), traces.map { it.text to it.isNew })
        assertEquals(listOf(0, 4), traces.map { it.at })
    }

    @Test
    fun removedLinkShowsBothSides() {
        // 地址不排进版面：闭标记只留 `](…)`（几百字的地址会把整段撑烂）。
        val traces = FormatTraces.inline(
            "点这里",
            oldSpans = listOf(
                InlineMarkup.StyleSpan(
                    0,
                    3,
                    InlineMarkup.StyleSig("a", mapOf("href" to "https://example.com")),
                ),
            ),
            newSpans = emptyList(),
        )
        assertEquals(listOf("[" to false, "](…)" to false), traces.map { it.text to it.isNew })
    }

    @Test
    fun unknownNewSpansLeaveNoTrace() {
        // 老修订没存过样式：不知道读者改了什么，一个字都不画。
        assertTrue(
            FormatTraces.inline(
                "你好世界",
                oldSpans = listOf(InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("strong"))),
                newSpans = null,
            ).isEmpty(),
        )
    }

    @Test
    fun attributeOnlyChangeLeavesNoTrace() {
        // 只换了属性写法（卡片归一过 class / style）：同区间同标签的一对红绿互相抵消。
        val traces = FormatTraces.inline(
            "你好世界",
            oldSpans = listOf(
                InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("span", mapOf("class" to "a"))),
            ),
            newSpans = listOf(
                InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("span", mapOf("class" to "b"))),
            ),
        )
        assertTrue(traces.isEmpty())
    }

    @Test
    fun unchangedStructureLeavesNoTrace() {
        val span = InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("em"))
        assertTrue(FormatTraces.inline("你好世界", listOf(span), listOf(span)).isEmpty())
    }

    @Test
    fun nestedRemovalKeepsOuterFirst() {
        // 外层 `**` 先开、内层 `*` 后开；收尾反过来（内层先闭）。
        val traces = FormatTraces.inline(
            "你好世界",
            oldSpans = listOf(
                InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("strong")),
                InlineMarkup.StyleSpan(1, 3, InlineMarkup.StyleSig("em")),
            ),
            newSpans = emptyList(),
        )
        assertEquals(
            listOf("**" to 0, "*" to 1, "*" to 3, "**" to 4),
            traces.map { it.text to it.at },
        )
    }

    @Test
    fun tracesCombineBlockAndInline() {
        val out = FormatTraces.traces(
            oldText = "你好世界",
            newText = "你好世界",
            oldKind = BlockKind.Heading2,
            newKind = BlockKind.Paragraph,
            oldSpans = emptyList(),
            newSpans = listOf(InlineMarkup.StyleSpan(0, 4, InlineMarkup.StyleSig("em"))),
        )
        // 段首先排块标记（红 `## `），再排行内开标记（绿 `*`），闭标记在段尾。
        assertEquals(listOf("## " to false, "*" to true, "*" to true), out.map { it.text to it.isNew })
        assertEquals(listOf(0, 0, 4), out.map { it.at })
    }

    private fun spans(spans: IntArray): List<IntRange> {
        val out = ArrayList<IntRange>(spans.size / 2)
        var i = 0
        while (i + 1 < spans.size) {
            out += spans[i] until spans[i + 1]
            i += 2
        }
        return out
    }
}
