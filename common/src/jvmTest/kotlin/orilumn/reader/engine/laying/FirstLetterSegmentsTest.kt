package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `::first-letter` 伪元素的**分段与查找契约**（pure JVM，不碰 Skia）。
 *
 * ## 锁什么
 *
 * 1. **只切首字**：命中伪元素时首字（含其前的引号/开括号）单独成段并携带该伪元素样式
 *    （[StyledSegment.genStyle]），段落其余字符逐字原样；叶文本（`segments` 拼接）与
 *    **不带查找**时完全相同 —— 这不是重排版，只多一个段边界；
 * 2. **查找只喂叶根**：回调（[FirstLetterOf]）只收到原发元素，且一次分段只问一次；
 *    匿名叶/无规则时零调用；
 * 3. **未命中即 null**：章节没有 `::first-letter` 规则（[StyleComputer.hasFirstLetterRules]
 *    关）或选择器不匹配本元素时，查找回 null ⇒ 分段与旧行为逐字节一致（老书零开销）；
 * 4. **run 收集吃它**：[NormalFlowLayout.leafFontRuns] 按首字段拿到 2em 字号的 run
 *    `[0, 1)`，段落其余不再产生 run（整段字号与 face 逐值不变）。
 *
 * `<br>`/`<img>` 标记段与纯空白段不是「首字」，必须跳过后再切。
 */
class FirstLetterSegmentsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun styles(
        root: MarkupElement,
        ua: String = "body { font-family: serif } p { font-size: 10px }",
    ): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(ua), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    private fun classify(styles: Map<MarkupElement, ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styles[el]?.displayBlock == true }

    private fun hidden(styles: Map<MarkupElement, ComputedStyle>) =
        HiddenCheck { styles[it]?.displayNone == true }

    @Test
    fun `只切首字且文本逐字不变`() {
        val root = node("body", children = listOf(node("p", children = listOf(text("Hello world")))))
        val p = root.children.single()
        val sm = styles(root)
        var asked = 0
        val lookup: FirstLetterOf = { el ->
            asked++
            assertTrue("查找只喂原发元素（叶根）", el === p)
            sm.getValue(p).copy(fontSizePx = 20f)
        }
        val segs = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace, firstLetterOf = lookup,
        )
        assertEquals("一次分段只问一次", 1, asked)
        assertEquals("首字单列一段（其余为原段）", 2, segs.segments.size)
        assertEquals("H", segs.segments[0].text)
        assertEquals("首字段携带伪元素样式", 20f, segs.segments[0].genStyle!!.fontSizePx, 1e-4f)
        assertEquals("其余字符原样", "ello world", segs.segments[1].text)
        assertEquals("叶文本与不带查找时逐字相同", "Hello world", segs.text)
        // 无查找（老路径）：同一棵树分段必须逐段一致（不多不少）。
        val plain = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace,
        )
        assertEquals("不带查找时单段", 1, plain.segments.size)
        assertEquals(segs.text, plain.text)
    }

    @Test
    fun `首字前的引号与开括号一并纳入`() {
        val root = node("body", children = listOf(node("p", children = listOf(text("“引号”与(H)括号")))))
        val p = root.children.single()
        val sm = styles(root)
        val segs = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace,
            firstLetterOf = { sm.getValue(p).copy(fontSizePx = 20f) },
        )
        assertEquals("前导引号+首字合成一段", "“引", segs.segments[0].text)
        assertEquals("其余原样", "号”与(H)括号", segs.segments[1].text)
        assertEquals("叶文本逐字不变", "“引号”与(H)括号", segs.text)
    }

    @Test
    fun `br 与 img 标记段不是首字`() {
        val img = node("img", attrs = mapOf("src" to "x.png"))
        val root = node("body", children = listOf(node("p", children = listOf(node("br"), img, text("正文"), node("br"), text("后缀")))))
        val p = root.children.single()
        val sm = styles(root)
        val segs = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace,
            firstLetterOf = { sm.getValue(p).copy(fontSizePx = 20f) },
        )
        val styled = segs.segments.filter { it.genStyle != null }
        assertEquals("跳过 br/img 标记段后取正文本体首字", 1, styled.size)
        assertEquals("正", styled.single().text)
        assertEquals("叶文本逐字不变（标记字符也算）", "\n￼正文\n后缀", segs.text)
    }

    @Test
    fun `真级联未命中时查找回 null 且分段与旧行为一致`() {
        val root = node("body", children = listOf(node("p", children = listOf(text("Hello"))), node("div", children = listOf(text("World")))))
        val p = root.children[0]
        val sm = styles(
            root,
            ua = "body { font-family: serif }",
        )
        val eng = StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse("body { font-family: serif }"),
            authorSheets = listOf(LightCssParser().parse("p::first-letter { font-size: 2em }")),
        )
        assertTrue("本章确有 ::first-letter 规则", eng.hasFirstLetterRules())
        assertNotNull("选择器匹配 p ⇒ 有首字样式", eng.firstLetterStyle(p, ancestorsOf(p), sm.getValue(p)))
        val div = root.children[1]
        assertNull("选择器不匹配 div ⇒ 未命中即 null", eng.firstLetterStyle(div, ancestorsOf(div), sm.getValue(div)))

        val segs = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace,
            firstLetterOf = { eng.firstLetterStyle(it, ancestorsOf(it), sm.getValue(it)) },
        )
        assertEquals("命中即切段", 2, segs.segments.size)
        val none = StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse("body { font-family: serif }"),
            authorSheets = listOf(LightCssParser().parse("p { font-size: 10px }")),
        )
        assertFalse("章节无伪元素规则 ⇒ 开关关", none.hasFirstLetterRules())
        assertNull("开关关 ⇒ 查找恒 null", none.firstLetterStyle(p, ancestorsOf(p), sm.getValue(p)))
        val old = styledSegments(
            p, { sm[it]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL },
            { sm[it]?.displayNone == true }, { classify(sm).isBlock(it) },
            sm.getValue(p).whiteSpace,
            firstLetterOf = { none.firstLetterStyle(it, ancestorsOf(it), sm.getValue(it)) },
        )
        assertEquals("无规则时分段逐段一致（零开销）", 1, old.segments.size)
        assertEquals("Hello", old.segments[0].text)
        assertNull("作者文本段的 genStyle 恒 null", old.segments[0].genStyle)
    }

    @Test
    fun `字体 run 只给首字且字号是伪元素的 2em`() {
        val root = node("body", children = listOf(node("p", children = listOf(text("Hello world")))))
        val p = root.children.single()
        val sm = styles(root)
        val fl: FirstLetterOf = { sm.getValue(p).copy(fontSizePx = 20f) }
        val runs = NormalFlowLayout.leafFontRuns(
            p, sm, classify(sm), hidden(sm), EmptyGen, firstLetterOf = fl,
        )
        assertEquals("段落其余与基底同 face 同字号 ⇒ 只有首字一个 run", 1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(1, runs[0].endExclusive)
        assertEquals("2em × 10px", 20f, runs[0].fontSizePx, 1e-4f)
        assertEquals("整段基底字号不变", 10f, sm.getValue(p).fontSizePx, 1e-4f)
        // 不带查找：纯种叶零 run（与旧行为逐字节一致）。
        assertTrue(
            "无 ::first-letter 时零 run",
            NormalFlowLayout.leafFontRuns(p, sm, classify(sm), hidden(sm), EmptyGen).isEmpty(),
        )
    }
}
