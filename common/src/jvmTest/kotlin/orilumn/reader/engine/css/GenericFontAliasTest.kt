package orilumn.reader.engine.css

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通用族替换（读者选的「衬线字体 / 无衬线字体」）：
 * - 只改 CSS 通用关键字 `serif` / `sans-serif` 的含义，书的具名字体一概不动；
 * - 原关键字留在栈尾，读者选的族缺字形时仍能逐字形回落到平台候选链；
 * - 没配（空槽）⇒ 完全维持原行为。
 *
 * 以及它带出的**基线字重**：读者在通用族槽里选过字重时，只补在「元素自己没写
 * font-weight、父级是常规 400」的正文上；显式 bold（`strong` / `h1..h6`）与作者字重不动。
 */
class GenericFontAliasTest {

    private fun profile(
        serif: String = "",
        sans: String = "",
        weights: Map<String, Int> = emptyMap(),
    ) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(
            fontSerif = serif,
            fontSans = sans,
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
