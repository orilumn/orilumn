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
 * - 只改 CSS 通用关键字 `serif` / `sans-serif` / `monospace` 的含义（原书设置下书的具名字体
 *   一概不动；现代 / 传统主题下主题层接管字族，只有书标的等宽意图例外）；
 * - 原关键字留在栈尾，读者选的族缺字形时仍能逐字形回落到平台候选链；
 * - 没配（空槽）⇒ 完全维持原行为。
 *
 * 三个全局字体槽**只挑字族、不挑字重**：字重属于角色，由排版主题（原书设置下由书）决定，
 * 读者层不再通过通用族注入任何 `font-weight`。
 */
class GenericFontAliasTest {

    private fun profile(
        serif: String = "",
        sans: String = "",
        code: String = "",
    ) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(
            fontSerif = serif,
            fontSans = sans,
            fontCode = code,
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
    fun `theme generic family takes over the book's named font`() {
        // 主题层（tier 42）写裸 `sans-serif`，书（tier 20）给 p 点了具名字体：**主题接管**。
        // 读者选现代主题就是要全无衬线，书里点名的族（`p{font-family:STSong,serif}` 这类）
        // 不再排在栈首——否则「现代模式除等宽外全无衬线」永远做不到。
        val st = compute(
            profile(sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:\"BookFace\"}",
            themeCss = "p{font-family:sans-serif}",
        ).byTag("p")
        assertEquals(listOf("思源黑体", "sans-serif"), st.fontFamilies)
        assertEquals("思源黑体", st.fontFamily)
    }

    @Test
    fun `modern theme overrides a book-declared serif body`() {
        // 真书样式（《天才俱乐部》）：`p{font-family:STSong,serif}`。现代主题下正文必须是无衬线。
        val st = compute(
            profile(sans = "思源黑体"),
            "<html><body><p>x</p></body></html>",
            "p{font-family:STSong,serif}",
            themeCss = ReaderStylesheets.theme("modern"),
        ).byTag("p")
        assertEquals(listOf("思源黑体", "sans-serif"), st.fontFamilies)
    }

    @Test
    fun `traditional theme gives headings sans and body serif even when the book names fonts`() {
        // 传统主题口径：标题无衬线、正文衬线；书的具名字族同样让位。
        val p = profile(serif = "思源宋体", sans = "思源黑体")
        val m = compute(
            p,
            "<html><body><h2>标题</h2><p>正文</p></body></html>",
            "h2{font-family:\"STHeiti\",san-serif;font-weight:bold}p{font-family:STSong,serif}",
            themeCss = ReaderStylesheets.theme("traditional"),
        )
        assertEquals(listOf("思源黑体", "sans-serif"), m.byTag("h2").fontFamilies)
        assertEquals(listOf("思源宋体", "serif"), m.byTag("p").fontFamilies)
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
}
