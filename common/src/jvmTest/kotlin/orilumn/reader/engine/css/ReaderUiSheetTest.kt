package orilumn.reader.engine.css

import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Z4 —— UI 层规则单源（[ReaderUiSheet]）：平板 `uiSheetFromProfile` 与桌面 `uiSheet` 共用同一
 * 生成器，输出契约在此一次断言。链接颜色单源（TypographicProfile.linkColorHex）同一文件覆盖。
 */
class ReaderUiSheetTest {

    private val textBlocks =
        "body,p,div,li,dd,dt,td,th,address,blockquote,pre,section,article,aside,header,footer,nav,figure,figcaption,h1,h2,h3,h4,h5,h6"

    private fun profile(
        lineSpacing: Float = 1.3f,
        paragraphSpacingPx: Int = 13,
        firstLineIndentEm: Float = 2f,
        bodyPx: Float = 18f,
        bgColor: Int = 0xFFF4F2EC.toInt(),
    ) = TypographicProfile(
        bodyPx = bodyPx,
        headingScale = 1.4f,
        quoteScale = 1f,
        codeScale = 0.92f,
        lineSpacing = lineSpacing,
        lineSpacingMult = lineSpacing,
        paragraphSpacingPx = paragraphSpacingPx,
        firstLineIndentEm = firstLineIndentEm,
        fgColor = 0xFF2B2B2B.toInt(),
        bgColor = bgColor,
        quoteColor = 0xFF2B2B2B.toInt(),
        marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
        fontBody = "", fontTitle = "", fontCode = "",
        useOriginalStyle = false,
        layoutTheme = "modern",
        coverProportional = true,
        paragraphGapScale = 1f,
        letterSpacingEm = 0f,
    )

    private fun declarations(profile: TypographicProfile, selector: String): Map<String, String> =
        ReaderUiSheet.build(profile).rules
            .single { it.selectors == listOf(selector) }
            .declarations.associate { it.property to it.value }

    @Test
    fun `line-height rule covers every text block with the profile value`() {
        val p = profile(lineSpacing = 1.35f)
        val sheet = ReaderUiSheet.build(p)
        val line = sheet.rules.single { it.selectors == textBlocks.split(",") }
        assertEquals(mapOf("line-height" to "1.35"), line.declarations.associate { it.property to it.value })
    }

    @Test
    fun `p rule carries per-side paragraph gap and first-line indent in em`() {
        // paragraphSpacingPx 13 / bodyPx 18 = 0.7222…em; firstLineIndentEm 2 → 整数去尾.
        val p = profile(paragraphSpacingPx = 13, firstLineIndentEm = 2f, bodyPx = 18f)
        val d = declarations(p, "p")
        assertEquals("0.72em", d["margin-top"])
        assertEquals("0.72em", d["margin-bottom"])
        assertEquals("2em", d["text-indent"])
    }

    @Test
    fun `li rule carries paragraph gap but never the first-line indent`() {
        val p = profile(firstLineIndentEm = 2f)
        val d = declarations(p, "li")
        assertEquals("0.72em", d["margin-top"])
        assertEquals("0.72em", d["margin-bottom"])
        assertFalse("li 绝不套用首行缩进", d.containsKey("text-indent"))
    }

    @Test
    fun `paraEm guards against zero body size`() {
        val p = profile(bodyPx = 0f, paragraphSpacingPx = 13)
        assertEquals("0em", declarations(p, "p")["margin-top"])
    }

    @Test
    fun `fmtEm strips trailing zeros and rounds to two decimals`() {
        assertEquals("2", ReaderUiSheet.fmtEm(2f))
        assertEquals("0.9", ReaderUiSheet.fmtEm(0.9f))
        assertEquals("1.35", ReaderUiSheet.fmtEm(1.35f))
        assertEquals("0.72", ReaderUiSheet.fmtEm(0.722f))
    }

    @Test
    fun `link color is theme aware by background luminance`() {
        assertEquals("#71B8FF", profile(bgColor = 0xFF121212.toInt()).linkColorHex)
        assertEquals("#1A66CC", profile(bgColor = 0xFFF4F2EC.toInt()).linkColorHex)
    }

    @Test
    fun `empty font slots emit no font rules and follow the book`() {
        val sheet = ReaderUiSheet.build(profile())
        assertFalse(
            "空槽 = 跟随原书，UI 层不得写 font-family",
            sheet.rules.any { r -> r.declarations.any { it.property == "font-family" } },
        )
    }

    @Test
    fun `font slots route body title and code to their own domains`() {
        // 回归"无法修改字体"：三槽各自进对应域（与 fontSlotFor 同一路由）。
        val p = profile().copy(fontBody = "霞鹜文楷", fontTitle = "serif", fontCode = "Menlo")
        val sheet = ReaderUiSheet.build(p)
        fun decl(selector: String): Map<String, String> =
            sheet.rules.filter { selector in it.selectors && it.declarations.any { d -> d.property == "font-family" } }
                .last().declarations.associate { it.property to it.value }
        assertEquals("\"霞鹜文楷\"", decl("body")["font-family"])
        assertEquals("\"霞鹜文楷\"", decl("p")["font-family"])
        assertEquals("\"霞鹜文楷\"", decl("blockquote")["font-family"])
        assertEquals("\"serif\"", decl("h1")["font-family"])
        assertEquals("\"serif\"", decl("h6")["font-family"])
        assertEquals("\"Menlo\"", decl("pre")["font-family"])
        assertEquals("\"Menlo\"", decl("code")["font-family"])
    }

    @Test
    fun `body slot never touches heading or code when their slots are empty`() {
        // 回归"改正文连带改标题"：fontTitle/fontCode 空 → 书自己的 h1/pre 字体存活；
        // 正文 p 仍被正文槽覆盖。
        val p = profile().copy(fontBody = "霞鹜文楷")
        val ui = ReaderUiSheet.build(p)
        val author = LightCssParser().parse(
            "h1{font-family:\"标题楷\"}pre{font-family:\"Courier New\"}",
        )
        val root = orilumn.reader.engine.html.HtmlTreeConverter()
            .convert("<html><body><h1>标题</h1><p>正文</p><pre>code</pre></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(author), null, null, ui)
        val styleMap = engine.compute(root)
        fun find(node: orilumn.reader.engine.html.MarkupElement, tag: String): orilumn.reader.engine.html.MarkupElement? {
            if (node.tag == tag) return node
            return node.children.firstNotNullOfOrNull { find(it, tag) }
        }
        assertEquals(listOf("标题楷"), styleMap[find(root, "h1")]?.fontFamilies)
        assertEquals(listOf("霞鹜文楷"), styleMap[find(root, "p")]?.fontFamilies)
        assertEquals(listOf("Courier New"), styleMap[find(root, "pre")]?.fontFamilies)
    }

    @Test
    fun `heading without its own font inherits the body slot`() {
        // 书没给 h1 声明字体 → 经 body 继承正文槽（空标题槽不写规则，但不切断继承）。
        val p = profile().copy(fontBody = "霞鹜文楷")
        val ui = ReaderUiSheet.build(p)
        val root = orilumn.reader.engine.html.HtmlTreeConverter()
            .convert("<html><body><h1>标题</h1></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList(), null, null, ui)
        val styleMap = engine.compute(root)
        fun find(node: orilumn.reader.engine.html.MarkupElement): orilumn.reader.engine.html.MarkupElement? {
            if (node.tag == "h1") return node
            return node.children.firstNotNullOfOrNull(::find)
        }
        assertEquals(listOf("霞鹜文楷"), styleMap[find(root) ?: error("no h1")]?.fontFamilies)
    }

    @Test
    fun `non-empty slot stomps direct book rules like the pre-migration pairing`() {
        // 旧语义：槽非空即覆盖全书（resolveBodyOrSlot 有槽不看书的栈）；UI tier 高于一切作者声明。
        val p = profile().copy(fontBody = "霞鹜文楷")
        val ui = ReaderUiSheet.build(p)
        val author = LightCssParser().parse("p{font-family:\"Times New Roman\"}")
        val root = orilumn.reader.engine.html.HtmlTreeConverter()
            .convert("<html><body><p>hi</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(author), null, null, ui)
        val styleMap = engine.compute(root)
        fun find(node: orilumn.reader.engine.html.MarkupElement): orilumn.reader.engine.html.MarkupElement? {
            if (node.tag == "p") return node
            return node.children.firstNotNullOfOrNull(::find)
        }
        val leaf = find(root) ?: error("no p leaf")
        assertEquals(listOf("霞鹜文楷"), styleMap[leaf]?.fontFamilies)
    }

    @Test
    fun `font slot flows through the cascade to leaf families`() {
        // 端到端：槽位 → UI 样式表 → 级联，叶子 fontFamilies 即用户所选。
        val p = profile().copy(fontBody = "霞鹜文楷")
        val ui = ReaderUiSheet.build(p)
        val root = orilumn.reader.engine.html.HtmlTreeConverter()
            .convert("<html><body><p>hi</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList(), null, null, ui)
        val styleMap = engine.compute(root)
        fun find(node: orilumn.reader.engine.html.MarkupElement): orilumn.reader.engine.html.MarkupElement? {
            if (node.tag == "p") return node
            return node.children.firstNotNullOfOrNull(::find)
        }
        val leaf = find(root) ?: error("no p leaf")
        assertEquals(listOf("霞鹜文楷"), styleMap[leaf]?.fontFamilies)
    }
}