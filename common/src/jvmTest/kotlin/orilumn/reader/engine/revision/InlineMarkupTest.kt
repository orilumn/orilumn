package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内标记语法单测：序列化 / 解析 / 往返幂等 / 未闭合 / 转义 / 校验 / 属性补回。
 * 口径见 `docs/正文修改-行内标记语法.md`。
 */
class InlineMarkupTest {

    private fun sig(tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSig(tag, attrs.toMap())

    private fun span(s: Int, e: Int, tag: String, vararg attrs: Pair<String, String>) =
        InlineMarkup.StyleSpan(s, e, sig(tag, *attrs))

    private fun show(text: String, vararg spans: InlineMarkup.StyleSpan) =
        InlineMarkup.show(text, spans.toList())

    // ------------------------------------------------------------------ 往返

    @Test
    fun sugarRoundTrip() {
        assertEquals("他*慢慢*地走", show("他慢慢地走", span(1, 3, "em")))
        assertEquals("**粗**", show("粗", span(0, 1, "strong")))
        // 内容含反引号：退回标签形式（反引号代码里没法表示自己）。
        assertEquals("<code>`</code>", show("`", span(0, 1, "code")))
        assertEquals("H~2~O", show("H2O", span(1, 2, "sub")))
        assertEquals("x^2^", show("x2", span(1, 2, "sup")))
        assertEquals("~~删~~", show("删", span(0, 1, "s")))
        assertEquals("[文字](https://a.b)", show("文字", span(0, 2, "a", "href" to "https://a.b")))
    }

    @Test
    fun sugarDoesNotShowAttributesButRestoreBringsThemBack() {
        val original = listOf(span(0, 5, "code", "class" to "test", "id" to "c1"))
        val markup = InlineMarkup.show("hello", original)
        assertEquals("`hello`", markup)

        val parsed = InlineMarkup.parse(markup)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals("hello", parsed.text)
        assertEquals(listOf(sig("code")), parsed.spans.map { it.sig })

        val restored = InlineMarkup.restore(original, parsed.spans)
        assertEquals(listOf(sig("code", "class" to "test", "id" to "c1")), restored.map { it.sig })
    }

    @Test
    fun tagFormForElementsWithoutSugar() {
        val spans = listOf(span(0, 1, "span", "style" to "color:#c00"))
        val markup = show("红", *spans.toTypedArray())
        assertEquals("<span style=\"color:#c00\">红</span>", markup)
        val parsed = InlineMarkup.parse(markup)
        assertEquals("红", parsed.text)
        assertEquals(spans.map { it.sig }, parsed.spans.map { it.sig })
    }

    @Test
    fun nestedSameMarker() {
        val parsed = InlineMarkup.parse("*a *b* c*")
        assertEquals("a b c", parsed.text)
        assertEquals(listOf(0 to 5, 2 to 3), parsed.spans.map { it.start to it.endExclusive })
        assertTrue(parsed.spans.all { it.sig.tag == "em" })
    }

    @Test
    fun tripleMarkerIsStrongPlusEm() {
        val parsed = InlineMarkup.parse("***x***")
        assertEquals("x", parsed.text)
        assertEquals(setOf("strong", "em"), parsed.spans.map { it.sig.tag }.toSet())
    }

    // ------------------------------------------------------------------ 未闭合

    @Test
    fun unclosedTagExtendsToEnd() {
        val parsed = InlineMarkup.parse("<em>abc")
        assertTrue(parsed.ok)
        assertEquals("abc", parsed.text)
        assertEquals(listOf(0 to 3), parsed.spans.map { it.start to it.endExclusive })
    }

    @Test
    fun orphanCloseTagStartsAtStart() {
        val parsed = InlineMarkup.parse("abc</code>")
        assertTrue(parsed.ok)
        assertEquals("abc", parsed.text)
        assertEquals(listOf(0 to 3), parsed.spans.map { it.start to it.endExclusive })
        assertEquals("code", parsed.spans.single().sig.tag)
    }

    @Test
    fun markdownMarkersRequirePairing() {
        // 「1~2」「3 * 4 * 5」不能被吃成标记（规格 §2.5）。
        assertEquals("1~2", InlineMarkup.parse("1~2").text)
        assertTrue(InlineMarkup.parse("1~2").spans.isEmpty())
        assertEquals("3 * 4 * 5", InlineMarkup.parse("3 * 4 * 5").text)
        assertTrue(InlineMarkup.parse("3 * 4 * 5").spans.isEmpty())
        // 未闭合的 * 按字面。
        assertEquals("*abc", InlineMarkup.parse("*abc").text)
        assertTrue(InlineMarkup.parse("*abc").spans.isEmpty())
    }

    // ------------------------------------------------------------------ 转义

    @Test
    fun literalMarkerCharsRoundTrip() {
        val cases = listOf("3 * 4 * 5", "1~2", "见[注1]", "a < b & c", "x^y", "路径 C:\\tmp")
        for (t in cases) {
            val markup = InlineMarkup.show(t, emptyList())
            val back = InlineMarkup.parse(markup)
            assertEquals("原文：$t / 标记：$markup / 错误：${back.errors}", t, back.text)
            assertTrue("不应多出样式：$t → $markup", back.spans.isEmpty())
        }
    }

    @Test
    fun bookLiteralMarkersAreEscapedAndComeBackVerbatim() {
        // EPUB 原文不是 markdown：书里字面写着的 `**加粗**`、`~~删~~` 都是普通文字。
        // 进输入区必须转义，否则一确认就被解析成样式，原文被悄悄改掉。
        val needEscape = listOf("**加粗**", "*斜体*", "~~删~~", "x^2^", "H~2~O", "[注1](p1)")
        for (t in needEscape) {
            val markup = InlineMarkup.show(t, emptyList())
            assertTrue("标记字符应转义：$t → $markup", markup != t && markup.contains('\\'))
            val back = InlineMarkup.parse(markup)
            assertEquals("标记：$markup", t, back.text)
            assertTrue("不应多出样式：$markup", back.spans.isEmpty())
        }
        // 单个、成不了对的标记本来就不会被吃，显示时不必加反斜杠。
        for (t in listOf("1990~2000", "3 * 4 * 5", "AT&T", "<b>字</b>", "函数(a)")) {
            val markup = InlineMarkup.show(t, emptyList())
            val back = InlineMarkup.parse(markup)
            assertEquals("标记：$markup", t, back.text)
            assertTrue("不应多出样式：$markup", back.spans.isEmpty())
        }
        // `]` `(` `)` 自身不构成语法：同一段里有真标记时也不该被加反斜杠。
        val mixed = InlineMarkup.show("**重点**(a)", emptyList())
        assertFalse(mixed, mixed.contains("\\("))
        assertEquals("**重点**(a)", InlineMarkup.parse(mixed).text)
    }

    @Test
    fun linkNameMayContainBrackets() {
        // 链接名里有 `]`：整体转义时必须把它转掉，否则配对断在第一处、链接被还原成字面文字。
        val text = "见 a]b"
        val spans = listOf(span(2, 5, "a", "href" to "x.html"))
        val markup = show(text, *spans.toTypedArray())
        val back = InlineMarkup.parse(markup)
        assertEquals("标记：$markup", text, back.text)
        assertEquals(listOf(2 to 5), back.spans.map { it.start to it.endExclusive })
        assertEquals("a", back.spans.single().sig.tag)
    }

    @Test
    fun showIsIdempotentOnMixedText() {
        val text = "他说：*好*，用 `code` 和 [注1]，1~2 之间 <b> 没有闭合 & 3 * 4"
        val emAt = text.indexOf("好")
        val codeAt = text.indexOf("code")
        val spans = listOf(
            span(emAt, emAt + 1, "em", "class" to "a"),
            span(codeAt, codeAt + 4, "code"),
        )
        val markup = show(text, *spans.toTypedArray())
        val parsed = InlineMarkup.parse(markup)
        assertEquals(text, parsed.text)
        val restored = InlineMarkup.restore(spans, parsed.spans)
        assertEquals(
            listOf(emAt to emAt + 1 to "em", codeAt to codeAt + 4 to "code"),
            restored.map { it.start to it.endExclusive to it.sig.tag },
        )
        // 同一份结构再序列化一次，结果必须一致（幂等）。
        assertEquals(markup, InlineMarkup.show(text, restored))
    }

    // ------------------------------------------------------------------ 校验

    @Test
    fun rejectsTagsOutsideWhitelist() {
        assertTrue(InlineMarkup.parse("<img src=x>").errors.isNotEmpty())
        assertTrue(InlineMarkup.parse("<p>x</p>").errors.isNotEmpty())
        assertTrue(InlineMarkup.parse("<script>alert(1)</script>").errors.isNotEmpty())
        // 正文不动：纯文本照样解析出来，交给卡片提示、不落库。
        assertEquals("x", InlineMarkup.parse("<p>x</p>").text)
    }

    @Test
    fun dropsDisallowedAttributesButKeepsAllowed() {
        val parsed = InlineMarkup.parse("<span onclick=\"x()\" class=\"a\" data-k=\"v\">红</span>")
        assertTrue(parsed.ok)
        assertEquals(mapOf("class" to "a"), parsed.spans.single().sig.attrs)
    }

    @Test
    fun filtersStyleToDecorationOnly() {
        val parsed = InlineMarkup.parse("<span style=\"color:red;position:fixed;display:none\">a</span>")
        assertTrue(parsed.ok)
        assertEquals("color:red", parsed.spans.single().sig.attrs["style"])
    }

    @Test
    fun rejectsJavascriptHref() {
        val parsed = InlineMarkup.parse("<a href=\"javascript:alert(1)\">x</a>")
        assertFalse(parsed.ok)
        assertTrue(InlineMarkup.parse("<a href=\"https://a.b\">x</a>").ok)
        assertTrue(InlineMarkup.parse("<a href=\"../x.html\">x</a>").ok)
    }

    // ------------------------------------------------------------------ 属性补回

    @Test
    fun restoreKeepsOriginalIdInPlaceAndDropsItForNewSpans() {
        val original = listOf(span(0, 2, "a", "href" to "x.html", "id" to "fn1"))
        val parsed = InlineMarkup.parse("[ab](x.html)").spans
        val restored = InlineMarkup.restore(original, parsed)
        assertEquals(mapOf("href" to "x.html", "id" to "fn1"), restored.single().sig.attrs)

        // 读者新写的一处强调：没有原元素可对，id 一律不要。
        val fresh = InlineMarkup.restore(emptyList(), InlineMarkup.parse("*新*").spans)
        assertFalse(fresh.single().sig.attrs.containsKey("id"))
    }

    @Test
    fun readerWrittenAttributesWin() {
        val original = listOf(span(0, 2, "a", "href" to "old.html", "class" to "x"))
        val parsed = InlineMarkup.parse("[ab](new.html)").spans
        val restored = InlineMarkup.restore(original, parsed)
        assertEquals(mapOf("href" to "new.html", "class" to "x"), restored.single().sig.attrs)
    }

    @Test
    fun noOpWhenNothingChanged() {
        val text = "他<code>x</code>"
        val spans = listOf(span(1, 2, "code", "class" to "c"))
        val markup = show(text, *spans.toTypedArray())
        val parsed = InlineMarkup.parse(markup)
        val restored = InlineMarkup.restore(spans, parsed.spans)
        assertEquals(text, parsed.text)
        assertEquals(spans.map { it.start to it.endExclusive to it.sig.tag },
            restored.map { it.start to it.endExclusive to it.sig.tag })
    }

    // ------------------------------------------------- 兜底：显示出来的一定解析得回去

    /**
     * 规格 §6 的硬约束：`解析(显示(x)) == x`。以前只验「少转义」那一版，全转义版不再复验，
     * 于是下面这两种形状确认后会悄悄改掉正文（实测：多出一个 `)`、星号变成三个）。
     */
    @Test
    fun shownMarkupAlwaysParsesBackToTheSameTextAndShape() {
        val cases = listOf(
            // 链接地址自带右括号（维基百科常见）：语法糖 `](url)` 会在第一个 `)` 截断。
            "维基" to listOf(span(0, 2, "a", "href" to "https://zh.wikipedia.org/wiki/Foo_(bar)")),
            // 斜体里只有一个星号：`*\**` 会被解析成字面 `***`。
            "*" to listOf(span(0, 1, "em")),
            "**" to listOf(span(0, 2, "strong")),
            "`" to listOf(span(0, 1, "em")),
            "[a]" to listOf(span(0, 3, "em")),
            "a&b" to listOf(span(0, 3, "span", "title" to "a&amp;b")),
        )
        for ((text, spans) in cases) {
            val markup = InlineMarkup.show(text, spans)
            val parsed = InlineMarkup.parse(markup)
            assertTrue("markup=<$markup> errors=${parsed.errors}", parsed.ok)
            assertEquals("markup=<$markup>", text, parsed.text)
            assertEquals(
                "markup=<$markup>",
                spans.map { it.start to it.endExclusive to it.sig.shapeKey },
                parsed.spans.map { it.start to it.endExclusive to it.sig.shapeKey },
            )
        }
    }

    @Test
    fun linkWithClosingParenFallsBackToTagForm() {
        val markup = show("维基", span(0, 2, "a", "href" to "https://x/Foo_(bar)"))
        assertEquals("<a href=\"https://x/Foo_(bar)\">维基</a>", markup)
    }

    @Test
    fun attributeValuesRoundTripWithEntities() {
        val original = listOf(span(0, 1, "span", "title" to "a&amp;b", "class" to "x"))
        val markup = show("红", *original.toTypedArray())
        val parsed = InlineMarkup.parse(markup)
        assertEquals("红", parsed.text)
        // 原文里的 `&amp;` 是「已编码」的字面值：写出去再读回来必须还是它，不能被解一层。
        assertEquals("a&amp;b", parsed.spans.single().sig.attrs["title"])
    }

    // ------------------------------------------------- 小 bug 回归

    @Test
    fun strayClosingBrIsRejectedInsteadOfSilentlyEaten() {
        // `br` 是空元素、没有结束标签（HTML 规范）：`</br>` 既不产生换行，也不能悄悄消失，
        // 直接判错让读者改——凭空多一个换行与凭空丢掉几个字都是「正文被偷偷改掉」。
        val withClosing = InlineMarkup.parse("ab</br>cd")
        assertEquals("abcd", withClosing.text)
        assertFalse(withClosing.ok)
        assertTrue(withClosing.errors.any { it.contains("</br>") })
        // 三种正确写法都等于一个换行。
        assertEquals("ab\ncd", InlineMarkup.parse("ab<br>cd").text)
        assertEquals("ab\ncd", InlineMarkup.parse("ab<br/>cd").text)
        assertEquals("ab\ncd", InlineMarkup.parse("ab<br />cd").text)
        assertTrue(InlineMarkup.parse("ab<br />cd").ok)
        // `wbr` 同样是空元素。
        assertFalse(InlineMarkup.parse("ab</wbr>cd").ok)
        assertEquals("abcd", InlineMarkup.parse("ab</wbr>cd").text)
    }

    @Test
    fun markdownMarkersDoNotPairAcrossLines() {
        val parsed = InlineMarkup.parse("*a\nb*")
        assertTrue(parsed.ok)
        assertEquals("*a\nb*", parsed.text)
        assertTrue(parsed.spans.isEmpty())
        // 同一行里照常配对。
        assertEquals(listOf(span(0, 2, "em")), InlineMarkup.parse("*ab*").spans)
    }

    @Test
    fun styleFilterKeepsQuotedSemicolonsAndDropsLayoutDeclarations() {
        assertEquals("font-family:\"a;b\"", InlineMarkup.filterStyle("font-family:\"a;b\""))
        assertEquals("color:#c00", InlineMarkup.filterStyle("color:#c00;position:fixed"))
        assertEquals("", InlineMarkup.filterStyle("display:none"))
        // `url(...)` 里的分号也不能当声明分隔。
        assertEquals(
            "background-color:red",
            InlineMarkup.filterStyle("background-color:red;background-image:url(a;b)"),
        )
    }

    @Test
    fun readerWrittenIdIsAlwaysDropped() {
        val parsed = InlineMarkup.parse("<span id=\"dup\" class=\"x\">红</span>")
        assertTrue(parsed.ok)
        assertFalse(parsed.spans.single().sig.attrs.containsKey("id"))
        assertEquals("x", parsed.spans.single().sig.attrs["class"])
    }

    // ------------------------------------------------- 新写标记继承同段样式 / 标记生效与否的颜色

    @Test
    fun newMarkerInheritsSameTagTemplateAttributesButNeverItsId() {
        // 同段有一个 `<em class="a" id="x">`：读者写 `*新*` 要长得跟它一样（规格 §4.3），
        // 但 id 不能带过去——新元素带原 id 会撞号。
        val template = InlineMarkup.StyleSpan(
            0, 2, InlineMarkup.StyleSig("em", mapOf("class" to "a", "id" to "x")),
        )
        val got = InlineMarkup.inheritTemplateAttrs(InlineMarkup.parse("*新*").spans, listOf(template))
        assertEquals(mapOf("class" to "a"), got.single().sig.attrs)
    }

    @Test
    fun explicitAttributesAreNeverOverwrittenByTheTemplate() {
        // 读者写了属性就是显式意图，模板只补空位。
        val template = InlineMarkup.StyleSpan(0, 2, InlineMarkup.StyleSig("span", mapOf("class" to "a")))
        val parsed = InlineMarkup.parse("<span class=\"b\">字</span>").spans
        assertEquals("b", InlineMarkup.inheritTemplateAttrs(parsed, listOf(template)).single().sig.attrs["class"])
    }

    @Test
    fun restoreUsesTemplatesOnlyForNewlyWrittenMarkers() {
        // 原位保留的那份以原文为准（原文的 em 没有 class，就不该凭空长出一个）；
        // 只有新写出来的第二个 em 才按模板补。
        val original = listOf(InlineMarkup.StyleSpan(0, 1, InlineMarkup.StyleSig("em")))
        val templates = listOf(
            InlineMarkup.StyleSpan(0, 1, InlineMarkup.StyleSig("em")),
            InlineMarkup.StyleSpan(5, 7, InlineMarkup.StyleSig("em", mapOf("class" to "a"))),
        )
        val parsed = InlineMarkup.parse("*甲* *乙*").spans
        val restored = InlineMarkup.restore(original, parsed, templates)
        assertEquals(2, restored.size)
        assertEquals(emptyMap<String, String>(), restored[0].sig.attrs)
        assertEquals(mapOf("class" to "a"), restored[1].sig.attrs)
    }

    @Test
    fun unpairedMarkersAreReportedSeparatelyFromPairedOnes() {
        // 真标记（成对）与「写了但按字面处理」的标记必须分得开（规格 §3.6），卡片用两种颜色画。
        assertEquals(listOf(0..0), InlineMarkup.unpairedMarkerRanges("*a"))
        assertTrue(InlineMarkup.unpairedMarkerRanges("*a*").isEmpty())
        // 跨行的星号不配对（§2.3），两头都算字面。
        assertEquals(listOf(0..0, 4..4), InlineMarkup.unpairedMarkerRanges("*a\nb*"))
        assertEquals(listOf(0..0, 2..2, 4..4, 6..6), InlineMarkup.markerRanges("*a* *b*"))
    }

    // ------------------------------------------------- 注音（ruby）往返

    @Test
    fun rubyRoundTripsThroughTagForm() {
        // 注音是「基字 + 读音」两层元素，没有语法糖，只能走标签形式；往返必须逐字逐元素等价。
        val text = "漢かん"
        val spans = listOf(span(0, 3, "ruby"), span(1, 3, "rt"))
        val markup = InlineMarkup.show(text, spans)
        assertEquals("<ruby>漢<rt>かん</rt></ruby>", markup)
        val parsed = InlineMarkup.parse(markup)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(text, parsed.text)
        assertEquals(
            spans.map { it.start to it.endExclusive to it.sig.shapeKey },
            parsed.spans.map { it.start to it.endExclusive to it.sig.shapeKey },
        )
    }

    // ------------------------------------------------ 多块卡片的行内标记

    /**
     * 多块卡片里，**后面的块**也要写成语法糖形式。
     *
     * 回归：`show` 的分块夹取一度返回「块内坐标」，而回验（`parse`）与 `renderSegments` 用的是
     * 拼接后的全文坐标，两边错位 ⇒ 回验永远失败，只要有第二块就走「整篇退化成标签形式」的兜底，
     * 读者在卡片里看到的是 `<code>` 而不是反引号。
     */
    @Test
    fun multiBlockShowKeepsSpansOnLaterBlocks() {
        val segments = listOf(
            InlineMarkup.Segment("fun 用于声明函数", BlockKind.Bullet),
            InlineMarkup.Segment("main()函数是你的程序开始的地方", BlockKind.Bullet),
            InlineMarkup.Segment("{}内", BlockKind.Bullet),
        )
        val starts = InlineMarkup.segmentStarts(segments)
        val spans = listOf(
            span(starts[0], starts[0] + 3, "code"),
            span(starts[1], starts[1] + 6, "code"),
            span(starts[2], starts[2] + 2, "code"),
        )
        val markup = InlineMarkup.show(segments, spans)
        assertEquals(
            "- `fun` 用于声明函数\n" +
                "- `main()`函数是你的程序开始的地方\n" +
                "- `{}`内",
            markup,
        )
        // 显示 → 解析：文字、块形态、区间三者都要原样回来（规格 §6）。
        val parsed = InlineMarkup.parse(markup)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(InlineMarkup.joinSegments(segments), parsed.text)
        assertEquals(segments.map { it.block }, parsed.segments.map { it.block })
        assertEquals(
            spans.map { it.start to it.endExclusive to it.sig.shapeKey },
            parsed.spans.map { it.start to it.endExclusive to it.sig.shapeKey },
        )
    }

    @Test
    fun rubyInsideSelectionKeepsItsAttributes() {
        // 书里的注音元素常带 class（样式表按它给读音缩小字号），补回时不能丢。
        val parsed = InlineMarkup.parse("<ruby class=\"r\">漢<rt>かん</rt></ruby>")
        assertTrue(parsed.ok)
        assertEquals("ruby", parsed.spans.first().sig.tag)
        assertEquals("r", parsed.spans.first().sig.attrs["class"])
    }
}
