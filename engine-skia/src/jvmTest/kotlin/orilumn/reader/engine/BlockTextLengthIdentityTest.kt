package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R26 回归：`LightPrepare.block(i).textLength` 改走 `globalCharStarts` 差分后，
 * 必须与原先逐块现算的 `styledCharAdvance` **逐值相同**。
 *
 * 这条恒等式是纯文本、img 槽位、表格行、br、隐藏块、生成内容、嵌套容器都要成立的——
 * 任一分支不同源就会让轻路径行流的 `charStart`/`charEnd` 漂移，而漂移是静默的
 * （只表现为目录跳转错位 / 进度百分比偏几个字），不会有任何异常。
 *
 * 旧口径在这里现算一遍作对照，不是重复测同一个实现。
 */
class BlockTextLengthIdentityTest {

    private fun prepare(html: String, css: String = ""): LightPrepare {
        val root = HtmlTreeConverter().convert(html)!!
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        return BoxChapterLayouter().prepareLight(
            root, CssBundle(listOf(css)), profile, 800, ChapterStructureCache(), 1000,
        )
    }

    /** 旧口径：与改动前 `block(i)` 里的实参逐字相同。 */
    private fun directAdvance(p: LightPrepare, i: Int): Int =
        NormalFlowLayout.styledCharAdvance(
            p.block(i).el!!,
            { e -> p.resolveStyle(e) },
            p.lightClassify(),
            p.hidden,
            p.genOf,
        ).toInt()

    private fun assertIdentity(name: String, html: String, css: String = "") {
        val p = prepare(html, css)
        val n = p.totalBlocks
        assertEquals("$name: 叶集非空", true, n > 0)
        for (i in 0 until n) {
            assertEquals(
                "$name: 块 $i 的 textLength 漂移",
                directAdvance(p, i),
                p.block(i).textLength,
            )
        }
        // 差分的前缀和必须与 globalCharStarts 逐项吻合（末块靠 totalChars 兜）。
        var run = 0L
        for (i in 0 until n) {
            run += p.block(i).textLength
            val expect = if (i + 1 < n) p.globalCharStarts[i + 1] else p.totalChars.toLong()
            assertEquals("$name: 块 $i 处累计不一致", expect, run)
        }
    }

    @Test
    fun `plain paragraphs`() {
        assertIdentity(
            "纯段落",
            "<html><body><p>第一段文字。</p><p>第二段更长一些的内容。</p><p>三。</p></body></html>",
        )
    }

    @Test
    fun `image slots and br`() {
        assertIdentity(
            "img/br",
            "<html><body><p>前面<img src=\"a.png\" width=\"10\" height=\"10\"/>后面" +
                "<br/>换行继续</p><p><img src=\"b.png\" width=\"4\" height=\"4\"/></p></body></html>",
        )
    }

    @Test
    fun `table rows`() {
        assertIdentity(
            "表格",
            "<html><body><table><tr><td>甲</td><td>乙</td></tr>" +
                "<tr><td>丙</td><td>丁</td></tr></table><p>表后段落</p></body></html>",
        )
    }

    @Test
    fun `hidden and nested containers`() {
        assertIdentity(
            "隐藏/嵌套",
            "<html><body><div><div><div><p>深层段落</p></div></div></div>" +
                "<p style=\"display:none\">隐藏内容</p>" +
                "<blockquote><p>引文一</p><p>引文二</p></blockquote></body></html>",
        )
    }

    @Test
    fun `generated content`() {
        assertIdentity(
            "生成内容",
            "<html><head><style>p::before{content:\"前缀\"}li::after{content:\" · \"}</style></head>" +
                "<body><ul><li>甲</li><li>乙</li></ul><p>正文</p></body></html>",
        )
    }

    @Test
    fun `single block chapter`() {
        // 末块没有后继，只能靠 totalChars 兜底——这条是差分实现最容易写错的一处。
        assertIdentity("单块", "<html><body><p>只有一个段落。</p></body></html>")
    }
}
