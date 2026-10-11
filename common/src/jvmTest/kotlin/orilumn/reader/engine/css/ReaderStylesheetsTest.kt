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
        // P3a：全宽拉伸规则已退役 —— 主题层不得再给独图注入 width。断言跑在**剥注释**后的文本上
        // （编者注里会引用被删的类名与 UA 兜底规则 `img { max-width: 100% }`，不剥会误伤）；
        // `(?<![-\w])width` 排除 max-/min- 前缀。
        val traditionalBody = stripComments(traditional)
        assertFalse(traditional, traditionalBody.contains("orilumn-fullwidth-image"))
        assertFalse(traditional, NO_IMG_WIDTH.containsMatchIn(traditionalBody))

        val modern = ReaderStylesheets.theme("modern")
        assertTrue(modern!!, modern.contains("body { font-family: sans-serif; }"))
        assertTrue(modern.contains("p { text-indent: 0; }"))
        val modernBody = stripComments(modern)
        assertFalse(modern, modernBody.contains("orilumn-fullwidth-image"))
        assertFalse(modern, NO_IMG_WIDTH.containsMatchIn(modernBody))

        assertNull(ReaderStylesheets.theme("original"))
        assertNull(ReaderStylesheets.theme("unknown"))
    }

    @Test
    fun `missing resource throws instead of inline fallback`() {
        assertNull(readReaderCss("no-such-file.css"))
    }

    private companion object {
        /** The rule P3a retired: an `img` selector declaring a plain `width` (not max-/min-). */
        val NO_IMG_WIDTH = Regex("""img[^{}]*\{[^}]*(?<![-\w])width\s*:""")

        fun stripComments(css: String) = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    }
}