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
}
