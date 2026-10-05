package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户字重 = UI 层（tier 44）**声明**，与 `font-family`/行距/段间距同一条路
 * （`Cascade` 决策 6：读者层恒盖书，且「without any post-hoc mutation」）。
 *
 * 钉住四件事：
 *  ① UI 声明的字重盖掉 UA `h1..h6{font-weight:bold}` 与作者 `.fm-head{bold}`；
 *  ② **UI 不声明 `font-style` ⇒ 原书的 `italic` 一字不丢**（用户只配了字重）；
 *  ③ 三槽按标签分域，正文槽的字重不外溢到标题/代码（反之亦然）；
 *  ④ 没选字重就不写 `font-weight`，原书 `font-weight`（含 bold）原样生效。
 *
 * ②③ 是旧渲染层锚点机制（`SkParagraphFactory.anchoredWeight`）做不到的：它在级联之后
 * 按族名+启发式（italic/非 400）偷偷换字重，于是标题恒不触发、跨槽串扰、还把原书
 * `font-style` 的语义搅浑；该机制已整体退役。
 */
class ReaderWeightSlotTest {

    private val titleFam = "阿里巴巴普惠体 3.0"

    /**
     * 书的 CSS 取自真书（Manning 排版）：`.fm-head{font-style:italic;font-weight:bold}`。
     * 标题**同时**斜体 + 粗，正是旧机制（`if (italic) return weight`）恒不触发的那个形状。
     */
    private val bookHeadingCss =
        ".fm-head{font-family:\"Franklin Gothic Medium\",sans-serif;font-style:italic;font-weight:bold}"

    private fun profile(
        body: String = "",
        title: String = titleFam,
        code: String = "",
        bySlot: Map<String, Int> = mapOf("fontTitle|$titleFam" to 300),
        legacy: Map<String, Int> = emptyMap(),
    ) = TypographicProfile(
        bodyPx = 18f,
        headingScale = 1.4f,
        quoteScale = 1f,
        codeScale = 0.92f,
        lineSpacing = 1.3f,
        lineSpacingMult = 1.3f,
                firstLineIndentEm = 0f,
        fgColor = 0xFF2B2B2B.toInt(),
        bgColor = 0xFFF4F2EC.toInt(),
        quoteColor = 0xFF2B2B2B.toInt(),
        marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
        fontBody = body, fontTitle = title, fontCode = code,
        fontWeightAnchors = legacy,
        fontWeightAnchorsBySlot = bySlot,
        useOriginalStyle = false,
        layoutTheme = "modern",
        coverStretch = false,
        paragraphGapScale = 1f,
        letterSpacingEm = 0f,
    )

    private val html =
        "<html><body>" +
            "<p>正文</p>" +
            "<h2 class=\"fm-head\">标题</h2>" +
            "<p>加粗<strong>重点</strong></p>" +
            "<pre><code>x</code></pre>" +
            "</body></html>"

    /** 真UA 层（`ReaderStylesheets.ua()`，含 `h1..h6{bold}` 与 `strong,b{bold}`）+ 作者层 + UI 层。 */
    private fun compute(p: TypographicProfile): Map<MarkupElement, ComputedStyle> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(
            rootFontPx = 18f,
            ua = LightCssParser().parse(ReaderStylesheets.ua()),
            authorSheets = listOf(LightCssParser().parse(bookHeadingCss)),
            theme = null,
            settings = null,
            ui = ReaderUiSheet.build(p),
        )
        return engine.compute(root)
    }

    private fun Map<MarkupElement, ComputedStyle>.byTag(tag: String, nth: Int = 0): ComputedStyle {
        var i = 0
        for ((el, st) in entries) {
            if (el.tag == tag) {
                if (i == nth) return st
                i++
            }
        }
        error("no <$tag> #$nth")
    }

    /** UI 层规则里带 `font-weight` 的那条（按选择器首名匹配）。 */
    private fun fontDecl(p: TypographicProfile, firstSelector: String): Map<String, String> =
        ReaderUiSheet.build(p).rules
            .filter { it.selectors.firstOrNull() == firstSelector }
            .flatMap { it.declarations }
            .associate { it.property to it.value }

    /** ① UI 声明的字重压过 UA `h1..h6{bold}` 与作者 `.fm-head{bold}`。 */
    @Test
    fun `UI weight beats UA bold and author bold on headings`() {
        assertEquals(300, compute(profile()).byTag("h2").fontWeight)
    }

    /** ② UI 不声明 font-style ⇒ 原书的 `font-style:italic` 原样胜出（用户明确要求的语义）。 */
    @Test
    fun `author italic survives because UI never declares font-style`() {
        assertFalse(
            "UI 层只发 font-family/font-weight，不得带上 font-style",
            ReaderUiSheet.build(profile()).rules.any { r -> r.declarations.any { it.property == "font-style" } },
        )
        assertTrue("原书标题的斜体必须还在", compute(profile()).byTag("h2").italic)
        assertFalse("正文没斜体，别被顺带染上", compute(profile()).byTag("p").italic)
    }

    /** 斜体与字重同时命中：两条声明各走各的 tier，互不吞（旧锚点机制做不到）。 */
    @Test
    fun `italic and weight coexist on the same heading`() {
        val st = compute(profile()).byTag("h2")
        assertTrue("斜体在", st.italic)
        assertEquals("字重也在", 300, st.fontWeight)
    }

    /** ④ 没选字重就不写 `font-weight` ⇒ 原书 `bold` 原样生效（用户没表达意图就不动）。 */
    @Test
    fun `no weight chosen leaves the book bold untouched`() {
        val p = profile(bySlot = emptyMap())
        assertFalse("没选字重就不发声明", fontDecl(p, "h1").containsKey("font-weight"))
        assertEquals(700, compute(p).byTag("h2").fontWeight)
    }

    /** ③ 三槽按标签分域：只配标题槽，正文/代码既不发 font-weight 也不受影响。 */
    @Test
    fun `slots are tag-scoped and never bleed`() {
        val p = profile(body = "PingFang SC")
        assertFalse("正文槽没配字重就不该发 font-weight", fontDecl(p, "body").containsKey("font-weight"))
        assertFalse(
            "代码槽没配字体 ⇒ 不得有字体/字重规则覆盖 pre/code",
            ReaderUiSheet.build(p).rules.any { r ->
                ("pre" in r.selectors) && r.declarations.any { it.property == "font-family" || it.property == "font-weight" }
            },
        )
        assertEquals("只配了标题槽 ⇒ 只有标题规则带 font-weight", 1,
            ReaderUiSheet.build(p).rules.count { r -> r.declarations.any { it.property == "font-weight" } })
        // 正文槽配了字体但没配字重 ⇒ 书里正文的 bold（strong）语义完整。
        val m = compute(p)
        assertEquals(700, m.byTag("strong").fontWeight)
    }

    /** 三槽各配各的：三条规则各自带自己的字重，互不串。 */
    @Test
    fun `three slots carry their own weights independently`() {
        val p = profile(
            body = "PingFang SC",
            code = "Fira Code",
            bySlot = mapOf(
                "fontBody|PingFang SC" to 500,
                "fontTitle|$titleFam" to 900,
                "fontCode|Fira Code" to 700,
            ),
        )
        assertEquals("500", fontDecl(p, "body")["font-weight"])
        assertEquals("900", fontDecl(p, "h1")["font-weight"])
        assertEquals("700", fontDecl(p, "pre")["font-weight"])
        val m = compute(p)
        assertEquals(900, m.byTag("h2").fontWeight)
        assertEquals(500, m.byTag("p").fontWeight)
        assertEquals(700, m.byTag("code").fontWeight)
    }

    /** 非法锚点值（不在 100..900）当作"没选"，不写声明。 */
    @Test
    fun `illegal weight values are dropped`() {
        val p = profile(bySlot = mapOf("fontTitle|$titleFam" to 50))
        assertFalse(fontDecl(p, "h1").containsKey("font-weight"))
        assertEquals(700, compute(p).byTag("h2").fontWeight)
    }

    /** 旧设置没有槽位键时，legacy 无槽位表仍兜住（向后兼容既有用户）。 */
    @Test
    fun `legacy anchor table still applies`() {
        val p = profile(bySlot = emptyMap(), legacy = mapOf(titleFam to 800))
        assertEquals("800", fontDecl(p, "h1")["font-weight"])
        assertEquals(800, compute(p).byTag("h2").fontWeight)
    }

    /** `<strong>` 的加粗必须保住：UI 正文规则作用在 `p`，`strong` 自身有 UA 声明（继承属性语义）。 */
    @Test
    fun `strong keeps its own bold under a body weight override`() {
        val p = profile(body = "PingFang SC", bySlot = mapOf("fontBody|PingFang SC" to 300))
        val m = compute(p)
        assertEquals("strong 自己的 UA bold 恒胜", 700, m.byTag("strong").fontWeight)
        assertEquals("p 走 UI 声明的 300", 300, m.byTag("p").fontWeight)
    }
}