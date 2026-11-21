package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z1 —— 单源样式加载器的内容契约：common `resources/css/` 为唯一事实源；UA 关键基线、
 * 主题键值（traditional/modern 有、原书设置 null）就位；token 保留待替换。
 */
class ReaderStylesheetsTest {

    @Test
    fun `ua contains browser baseline keystones`() {
        val css = ReaderStylesheets.ua()
        val flat = css.replace(Regex("\\s+"), " ")
        assertTrue(flat, flat.contains("ul, ol { padding-left: 2em; }"))
        assertTrue(flat, flat.contains("strong, b { font-weight: bold; }"))
        assertTrue(flat, flat.contains("em, i { font-style: italic; }"))
        assertTrue(flat, flat.contains("code, kbd, samp, tt, pre { font-family: monospace; }"))
        assertTrue(flat, flat.contains("blockquote { margin: 1em 2.5em; }"))
        // P3a 的兜底仍在：超宽 intrinsic 由 UA 层按 §10.4 收敛（主题不再注入 width:100%）。
        assertTrue(flat, flat.contains("img { max-width: 100%; }"))
    }

    @Test
    fun `ua keeps link color token for per-profile substitution`() {
        assertTrue(ReaderStylesheets.ua().contains(ReaderStylesheets.LINK_COLOR_TOKEN))
    }

    @Test
    fun `theme resolves known themes and null for original`() {
        val traditional = ReaderStylesheets.theme("traditional")
        assertTrue(traditional!!, traditional.contains("body { font-family: serif; }"))
        // 标题单独一条：书常给 h1..h6 写字体，只靠 body 继承够不着（用户报「切主题标题不跟着变」）。
        // 传统主题的标题是**无衬线**（正文宋体 + 标题黑体的纸书惯例），且写通用关键字：
        // 读者配了「无衬线字体（全局）」时，标题跟着那个族走。
        assertTrue(traditional, traditional.contains("h1, h2, h3, h4, h5, h6 { font-family: sans-serif; }"))
        // P3a：全宽拉伸规则已退役 —— 主题层不得再给独图注入 width。断言跑在**剥注释**后的文本上
        // （编者注里会引用被删的类名与 UA 兜底规则 `img { max-width: 100% }`，不剥会误伤）；
        // `(?<![-\w])width` 排除 max-/min- 前缀。
        val traditionalBody = stripComments(traditional)
        assertFalse(traditional, traditionalBody.contains("orilumn-fullwidth-image"))
        assertFalse(traditional, NO_IMG_WIDTH.containsMatchIn(traditionalBody))

        val modern = ReaderStylesheets.theme("modern")
        // 现代主题：**除等宽（pre/code/kbd/samp/tt）之外的全部文字块**一律无衬线。
        // 按标签逐个断言，防止日后有人把某类标签从选择器表里删掉（书给那个标签点了字体时
        // 就会漏出正文体）。等宽标签一律不在此表内。
        val modernFam = fontFamilyBySelector(modern!!)
        for (tag in listOf(
            "body", "p", "div", "li", "ul", "ol", "dl", "dt", "dd", "blockquote",
            "table", "td", "th", "caption", "figure", "figcaption", "address",
            "section", "article", "aside", "header", "footer", "nav", "main",
            "h1", "h2", "h3", "h4", "h5", "h6",
        )) {
            assertEquals("现代主题必须把 <$tag> 声明成无衬线", "sans-serif", modernFam[tag])
        }
        for (codeTag in listOf("pre", "code", "kbd", "samp", "tt")) {
            assertNull("现代主题不碰等宽域 <$codeTag>", modernFam[codeTag])
        }
        assertTrue(modern.contains("p { text-indent: 0; }"))
        val modernBody = stripComments(modern)
        assertFalse(modern, modernBody.contains("orilumn-fullwidth-image"))
        assertFalse(modern, NO_IMG_WIDTH.containsMatchIn(modernBody))

        assertNull(ReaderStylesheets.theme("original"))
        assertNull(ReaderStylesheets.theme("unknown"))
    }

    /**
     * 覆盖层用的「主题正文字族」镜像必须等于主题 CSS 自己声明的那个词。
     *
     * 旁注不进书的级联，跟随正文只能问主题表；镜像一旦与 CSS 漂了，批注就会悄悄换字体，
     * 所以这里直接拿解析器读 CSS 对账，而不是相信两处手抄。
     */
    @Test
    fun `theme body family mirror matches theme css`() {
        for (theme in listOf("traditional", "modern")) {
            val sheet = LightCssParser().parse(ReaderStylesheets.theme(theme)!!)
            val bodyRule = sheet.rules.first { rule -> rule.selectors.any { it.trim() == "body" } }
            val declared = bodyRule.declarations
                .first { it.property.equals("font-family", ignoreCase = true) }
                .value.substringBefore(',').trim().lowercase()
            assertEquals("$theme 的正文字族镜像与 CSS 不一致", declared, ReaderStylesheets.themeBodyFamily(theme))
        }
        assertNull(ReaderStylesheets.themeBodyFamily("original"))
        assertNull(ReaderStylesheets.themeBodyFamily("unknown"))
    }

    @Test
    fun `missing resource throws instead of inline fallback`() {
        assertNull(readReaderCss("no-such-file.css"))
    }

    private companion object {
        /** 主题 CSS 里 selector → 该规则声明的 `font-family` 原始值（仅收声明了字族的规则）。 */
        fun fontFamilyBySelector(css: String): Map<String, String> {
            val sheet = LightCssParser().parse(css)
            val out = HashMap<String, String>()
            for (rule in sheet.rules) {
                val fam = rule.declarations
                    .firstOrNull { it.property.equals("font-family", ignoreCase = true) }?.value
                    ?: continue
                for (sel in rule.selectors) out[sel.trim()] = fam.substringBefore(',').trim().lowercase()
            }
            return out
        }

        /** The rule P3a retired: an `img` selector declaring a plain `width` (not max-/min-). */
        val NO_IMG_WIDTH = Regex("""img[^{}]*\{[^}]*(?<![-\w])width\s*:""")

        fun stripComments(css: String) = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    }
}