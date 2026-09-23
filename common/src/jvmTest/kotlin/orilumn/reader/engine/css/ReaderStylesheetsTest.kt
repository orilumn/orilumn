package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `ua keeps link color token for per-profile substitution`() {
        assertTrue(ReaderStylesheets.ua().contains(ReaderStylesheets.LINK_COLOR_TOKEN))
    }

    @Test
    fun `theme resolves known themes and null for original`() {
        val traditional = ReaderStylesheets.theme("traditional")
        assertTrue(traditional!!, traditional.contains("body { font-family: serif; }"))
        assertTrue(traditional.contains(".orilumn-fullwidth-image { width: 100%; }"))

        val modern = ReaderStylesheets.theme("modern")
        assertTrue(modern!!, modern.contains("body { font-family: sans-serif; }"))
        assertTrue(modern.contains("p { text-indent: 0; }"))

        assertNull(ReaderStylesheets.theme("original"))
        assertNull(ReaderStylesheets.theme("unknown"))
    }

    @Test
    fun `missing resource throws instead of inline fallback`() {
        assertNull(readReaderCss("no-such-file.css"))
    }
}