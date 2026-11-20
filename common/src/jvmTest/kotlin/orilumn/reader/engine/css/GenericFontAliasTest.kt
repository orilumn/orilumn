package orilumn.reader.engine.css

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用族替换（读者选的「衬线字体 / 无衬线字体 / 代码字体」）：
 * - 只改 CSS 通用关键字 `serif` / `sans-serif` / `monospace` 的含义，书的具名字体一概不动；
 * - 原关键字留在栈尾，读者选的族缺字形时仍能逐字形回落到平台候选链；
 * - 没配（空槽）⇒ 完全维持原行为。
 *
 * 以及它带出的**基线字重**：读者在通用族槽里选过字重时，补在「父级是常规 400、且元素自己
 * 没写 font-weight」的正文上；元素自己写了字族（主题的裸通用名）而字重只来自 UA 基线时，
 * 标题也跟读者挑的那一档。书 / 主题 / UI 显式写的字重与 `<strong>`（没有自己的字族）不动。
 */
class GenericFontAliasTest {

    private fun profile(
        serif: String = "",
        sans: String = "",
        code: String = "",
        weights: Map<String, Int> = emptyMap(),
    ) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(
            fontSerif = serif,
            fontSans = sans,
            fontCode = code,
            fontWeightAnchorsGlobal = weights,
        ),
    )

    private fun compute(
        p: TypographicProfile,
        html: String,
        css: String,
        themeCss: String? = null,
    ): Map<MarkupElement, ComputedStyle> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(
            rootFontPx = 16f,
            ua = LightCssParser().parse(ReaderStylesheets.ua()),
            authorSheets = listOf(LightCssParser().parse(css)),
            theme = themeCss?.let { LightCssParser().parse(it) },
            custom = null,
            ui = null,
            fontFamilyAliases = p.genericFontAliases,
            genericFontWeights = p.genericFontWeights,
        )
        return engine.compute(root)
    }

    private fun Map<MarkupElement, ComputedStyle>.byTag(tag: String): ComputedStyle =
        entries.firstOrNull { it.key.tag == tag }?.value ?: error("no <$tag>")

    @Test
    fun `serif keyword resolves to the reader family and keeps the keyword as fallback`() {
        val st = compute(
            profile(serif = "思源宋体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"Times New Roman\",serif}",
        ).byTag("p")
        assertEquals(listOf("Times New Roman", "思源宋体", "serif"), st.fontFamilies)
        // 书的具名字体仍排第一（`fontFamily` 取栈里第一个非通用族），读者只改了 `serif` 的含义。
        assertEquals("Times New Roman", st.fontFamily)
    }

    @Test
    fun `named book families are untouched`() {
        val st = compute(
            profile(serif = "思源宋体", sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"BookFace\"}",
        ).byTag("p")
        assertEquals(listOf("BookFace"), st.fontFamilies)
    }

    @Test
    fun `empty slots leave the original stack alone`() {
        val st = compute(
            profile(),
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"Times New Roman\",serif}",
        ).byTag("p")
        assertEquals(listOf("Times New Roman", "serif"), st.fontFamilies)
    }

    @Test
    fun `theme generic family also follows the reader alias`() {
        // 主题层（traditional/modern）写的就是裸 `serif` / `sans-serif`，替换必须对它同样生效。
        val st = compute(
            profile(sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:sans-serif}",
        ).byTag("p")
        assertEquals(listOf("思源黑体", "sans-serif"), st.fontFamilies)
    }

    @Test
    fun `book named font still wins over the theme generic after expansion`() {
        // 主题层（tier 42）写裸 `sans-serif`，书（tier 20）给 p 点了具名字体：既有语义是
        // 书的具名字体优先、主题通用名只做最终回退。配了无衬线替换后这条必须原样成立
        // ——不能因为裸通用名先被展开成两项就丢掉「书具名字体前置」的兜底合并。
        val st = compute(
            profile(sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"BookFace\"}",
            themeCss = "p{font-family:sans-serif}",
        ).byTag("p")
        assertEquals(listOf("BookFace", "思源黑体", "sans-serif"), st.fontFamilies)
        assertEquals("BookFace", st.fontFamily)
    }

    @Test
    fun `traditional theme heading resolves through the reader sans slot`() {
        // 传统主题：正文衬线、标题无衬线（纸书惯例）。标题那条写的是通用关键字，所以读者在
        // 「无衬线字体（全局）」里选了什么，标题就用什么；正文那条 serif 同理走衬线槽。
        val st = compute(
            profile(serif = "思源宋体", sans = "思源黑体"),
            "<html><body><h2>标题</h2><p>正文</p></body></html>",
            "",
            themeCss = ReaderStylesheets.theme("traditional"),
        )
        assertEquals(listOf("思源黑体", "sans-serif"), st.byTag("h2").fontFamilies)
        assertEquals(listOf("思源宋体", "serif"), st.byTag("p").fontFamilies)
    }

    @Test
    fun `alias expansion is not repeated along inheritance`() {
        // 父级展开一次后，子级继承的是已展开的栈；若每层再展开，读者族会被重复缀入。
        val m = compute(
            profile(serif = "思源宋体"),
            "<html><body><div><p><span>x</span></p></div></body></html>",
            "body{font-family:serif}",
        )
        assertEquals(listOf("思源宋体", "serif"), m.byTag("div").fontFamilies)
        assertEquals(listOf("思源宋体", "serif"), m.byTag("p").fontFamilies)
        assertEquals(listOf("思源宋体", "serif"), m.byTag("span").fontFamilies)
    }

    @Test
    fun `monospace keyword resolves to the reader code family and keeps the keyword as fallback`() {
        // UA 的 `code,kbd,samp,tt,pre{font-family:monospace}` 与书里手写的通用名走同一条通道。
        val st = compute(
            profile(code = "Fira Code"),
            "<html><body><pre><code>x</code></pre></body></html>",
            "",
        ).byTag("pre")
        assertEquals(listOf("Fira Code", "monospace"), st.fontFamilies)
        assertTrue(st.monospace)
        assertEquals("Fira Code", st.fontFamily)
    }

    @Test
    fun `monospace outside code tags also follows the code slot`() {
        // 书的等宽声明不只在 pre/code 标签上：也有用 div/span 标代码的写法。
        val st = compute(
            profile(code = "Fira Code"),
            "<html><body><p><span class=\"k\">x</span></p></body></html>",
            "span.k{font-family:monospace}",
        ).byTag("span")
        assertEquals(listOf("Fira Code", "monospace"), st.fontFamilies)
        assertTrue(st.monospace)
    }

    @Test
    fun `theme body font does not wipe the book's monospace intent`() {
        // 主题层（tier 42）给 div 写了裸 `sans-serif`，书（tier 20）把同一元素声明成等宽：
        // 两根轴——主题决定「默认正文长什么样」，书说的是「这段是代码」。合并后等宽在前、
        // 主题通用名留栈尾，读者的代码字体因此仍然落到这段上。
        val st = compute(
            profile(code = "Fira Code"),
            "<html><body><div class=\"code\">x</div></body></html>",
            "div.code{font-family:monospace}",
            themeCss = "body{font-family:sans-serif}div{font-family:sans-serif}",
        ).byTag("div")
        assertEquals(listOf("Fira Code", "monospace", "sans-serif"), st.fontFamilies)
        assertTrue(st.monospace)
    }

    @Test
    fun `theme still wins over the book's serif-sans preference`() {
        // 与上一条对照：作者写的也是衬线/无衬线通用名时，仍以主题（读者全局选择）为先。
        val st = compute(
            profile(sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:serif}",
            themeCss = "body{font-family:sans-serif}p{font-family:sans-serif}",
        ).byTag("p")
        assertEquals(listOf("思源黑体", "sans-serif"), st.fontFamilies)
    }

    @Test
    fun `named book code families are untouched`() {
        // 书点名了具体等宽族：不替换（与「书的具名字体不动」同一条纪律），等宽语义照旧。
        val st = compute(
            profile(code = "Fira Code"),
            "<html><body><pre>x</pre></body></html>",
            "pre{font-family:Consolas}",
        ).byTag("pre")
        assertEquals(listOf("Consolas"), st.fontFamilies)
        assertTrue(st.monospace)
    }

    @Test
    fun `empty code slot leaves monospace alone`() {
        val st = compute(
            profile(),
            "<html><body><pre>x</pre></body></html>",
            "pre{font-family:monospace}",
        ).byTag("pre")
        assertEquals(listOf("monospace"), st.fontFamilies)
    }

    @Test
    fun `code slot weight lands on generic monospace elements`() {
        val p = profile(code = "Fira Code", weights = mapOf("fontCode|Fira Code" to 700))
        val plain = compute(
            p,
            "<html><body><p class=\"c\">x</p></body></html>",
            "p.c{font-family:monospace}",
        ).byTag("p")
        assertEquals("读者在代码槽选的字重落到纯通用等宽上", 700, plain.fontWeight)
        val authored = compute(
            p,
            "<html><body><p class=\"c\">x</p></body></html>",
            "p.c{font-family:monospace;font-weight:500}",
        ).byTag("p")
        assertEquals("作者字重照旧优先", 500, authored.fontWeight)
    }

    @Test
    fun `generic slot weight lands on body text but never overrides explicit weights`() {
        val p = profile(serif = "思源宋体", weights = mapOf("fontSerif|思源宋体" to 300))
        val m = compute(
            p,
            "<html><body><p>正文</p><p>加粗<strong>重点</strong></p><h2>标题</h2></body></html>",
            "p{font-family:serif}",
        )
        assertEquals("读者在衬线槽选的字重落到正文", 300, m.byTag("p").fontWeight)
        assertEquals("strong 自己的 UA bold 恒胜", 700, m.byTag("strong").fontWeight)
        assertEquals("标题的 UA bold 不受正文基线影响", 700, m.byTag("h2").fontWeight)
    }

    @Test
    fun `theme heading takes the reader's generic slot weight`() {
        // 标题「采用主题设置」时，字族来自主题写的裸 `sans-serif`、被读者替换成自己那族；
        // 读者给这族挑的字重是**这套字体脸**的一部分，必须压过 UA 自带的 `h1..h6{bold}`，
        // 否则正文是细体、标题凭空变粗（用户报「无衬线字体的字重不对」）。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 300))
        val m = compute(
            p,
            "<html><body><h2>标题</h2><p>正文</p></body></html>",
            "",
            themeCss = ReaderStylesheets.theme("traditional"),
        )
        assertEquals("标题跟着读者挑的无衬线字重", 300, m.byTag("h2").fontWeight)
        assertEquals("正文走衬线槽，不串无衬线的字重", 400, m.byTag("p").fontWeight)
        // 同一份设置下 `strong` 没有自己的字族（继承父级），语义加粗照旧。
        val strong = compute(
            p,
            "<html><body><p>加<strong>粗</strong></p></body></html>",
            "",
            themeCss = ReaderStylesheets.theme("modern"),
        ).byTag("strong")
        assertEquals("strong 的 UA bold 不受通用族字重影响", 700, strong.fontWeight)
    }

    @Test
    fun `themed heading follows the reader weight even when the book wrote one`() {
        // 标题槽「采用主题设置」⇒ 标题的字族来自读者层（主题的 h1..h6 裸通用名），读者给这一族
        // 挑的字重属于同一张读者层字体脸，书的标题字重也要让位；否则「标题采用主题设置 + 无衬线
        // 挑 regular」在写了标题粗体的书里完全看不出来（用户报「这个字重不起作用」）。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 400))
        val st = compute(
            p,
            "<html><body><h2>标题</h2></body></html>",
            "h2{font-weight:bold}",
            themeCss = ReaderStylesheets.theme("modern"),
        ).byTag("h2")
        assertEquals(400, st.fontWeight)
    }

    @Test
    fun `themed heading parent already carrying the reader weight does not block it`() {
        // 现代主题把无衬线字重也补在 body 上（父级 300）；标题不能因为「父级不是 400」就被
        // 打回 UA bold——标题自己的字族仍来自主题，读者的那一档照样生效。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 300))
        val m = compute(
            p,
            "<html><body><h2>标题</h2><p>正文</p></body></html>",
            "",
            themeCss = ReaderStylesheets.theme("modern"),
        )
        assertEquals("父级已是 300，标题仍跟读者挑的那一档", 300, m.byTag("h2").fontWeight)
        assertEquals(300, m.byTag("p").fontWeight)
    }

    @Test
    fun `book weight on a themed paragraph still wins`() {
        // 主题也给 p/div 写了裸通用名，但那只是「默认正文长什么样」：书在段落上显式写的
        // 加粗/字重仍是书说了算（只有标题标签跟着读者那一档走）。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 400))
        val st = compute(
            p,
            "<html><body><p class=\"b\">正文</p></body></html>",
            "p.b{font-weight:600}",
            themeCss = ReaderStylesheets.theme("modern"),
        ).byTag("p")
        assertEquals(600, st.fontWeight)
    }

    @Test
    fun `themed heading keeps the reader weight when the book names its own heading font`() {
        // 书给标题同时写了具名字族与粗体（现实 EPUB 极常见：`h2{font-family:STHeiti;font-weight:bold}`）。
        // 主题的裸通用名压过书的字族后，引擎会把书的具名字族并到栈首做字形回退——这一步不能
        // 顺手把读者挑的字重丢掉（用户报「标题采用主题设置时调无衬线字重完全没影响」）。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 400))
        val st = compute(
            p,
            "<html><body><h2>标题</h2></body></html>",
            "h2{font-family:BookFont;font-weight:bold}",
            themeCss = ReaderStylesheets.theme("modern"),
        ).byTag("h2")
        assertEquals("读者挑的 regular 不被书的具名标题字族顶掉", 400, st.fontWeight)
        assertEquals(
            "书的具名字族仍留在栈首做字形回退，读者的无衬线族紧随其后",
            listOf("BookFont", "思源黑体", "sans-serif"),
            st.fontFamilies,
        )
    }

    @Test
    fun `book heading weight wins when the theme is off`() {
        // 原书设置：主题整张不加载，标题「采用主题设置」= 随书 ⇒ 书的标题字重原样生效。
        val p = profile(sans = "思源黑体", weights = mapOf("fontSans|思源黑体" to 400))
        val st = compute(
            p,
            "<html><body><h2>标题</h2></body></html>",
            "h2{font-family:sans-serif;font-weight:bold}",
            themeCss = null,
        ).byTag("h2")
        assertEquals(700, st.fontWeight)
    }

    @Test
    fun `generic weight never leaks onto the book's named font`() {
        // 书的栈是「具名字体 + serif 兜底」：实际主字体是具名的那个，读者的 serif 字重
        // 不能把它改成 300（那等于偷偷改了书的具名字体）。
        val p = profile(serif = "思源宋体", weights = mapOf("fontSerif|思源宋体" to 300))
        val st = compute(
            p,
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"BookFace\",serif}",
        ).byTag("p")
        assertEquals(400, st.fontWeight)
        assertEquals(listOf("BookFace", "思源宋体", "serif"), st.fontFamilies)
    }

    @Test
    fun `author weight wins over the generic baseline`() {
        val p = profile(serif = "思源宋体", weights = mapOf("fontSerif|思源宋体" to 300))
        val st = compute(
            p,
            "<html><body><p>x</p></body></html>",
            "p{font-family:serif;font-weight:500}",
        ).byTag("p")
        assertEquals(500, st.fontWeight)
    }

    @Test
    fun `generic weights are derived only from the global anchor table`() {
        // 正文/标题那两张按书私有的表不得串到通用族槽（反之亦然）。
        val p = profile(serif = "思源宋体")
            .copy(fontWeightAnchorsBySlot = mapOf("fontSerif|思源宋体" to 300))
        assertEquals("私有表不参与通用族字重", emptyMap<String, Int>(), p.genericFontWeights)
    }
}
