package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement

/**
 * 章字符流投影单源锁：**每个叶的投影文本长度必须等于它在 `globalCharStarts` 里占的格数**。
 *
 * ## 缺陷本体（真机「长按乱选」）
 *
 * 长按落点先在行流里换成章内字符偏移（`DrawLine.charBase` / `PageSlice.charStart` 口径），
 * 再拿这个偏移去整章文本里截整句/整词。整章文本若少算了某类叶的格子，偏移与文本就整体错位，
 * 落点和截出来的句子对不上 —— 表现为「选到别的段落」，且**错位量随手点位置变化**（"随机"）。
 *
 * 旧 [NormalFlowLayout.leafText] 只覆盖可排文字，漏了两类占格叶：
 * - 替换叶（`img` 单独成叶）占 1 格；
 * - 表格行叶（`tr`）占「格内各块归一文本之和」。
 *
 * ## 本锁锁什么
 *
 * 对每类叶分别断言 [NormalFlowLayout.leafStreamText] 长度 == [NormalFlowLayout.styledCharAdvance]
 * （重轻两路字符格数的唯一来源），并断言整章拼接总长 == 整章字符数。
 * 这两条一旦破了，选区偏移必然漂移，但漂移是静默的（不抛异常）。
 */
class LeafStreamTextTest {

    private data class Setup(
        val root: MarkupElement,
        val styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: BlockClassify,
        val hidden: HiddenCheck,
    )

    private fun setup(html: String): Setup {
        val root = HtmlTreeConverter().convert("<html><body>$html</body></html>")!!
        val sheet = LightCssParser().parse("")
        val engine = StyleComputer(16f, sheet, listOf(sheet))
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.DEFAULT_CLASSIFY
        val hidden = HiddenCheck { styles[it]?.displayNone == true }
        return Setup(root, styles, classify, hidden)
    }

    /** 逐叶核对投影长度，并核对整章拼接总长（与 [NormalFlowLayout.styledCharAdvance] 同尺）。 */
    private fun assertStreamAligned(name: String, html: String) {
        val s = setup(html)
        val leaves = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(s.root, leaves, s.classify, s.hidden)
        assertTrue("$name: 叶集非空", leaves.isNotEmpty())

        val stream = StringBuilder()
        var total = 0L
        for ((i, el) in leaves.withIndex()) {
            val advance = NormalFlowLayout.styledCharAdvance(el, { s.styles[it]!! }, s.classify, s.hidden)
            val text = NormalFlowLayout.leafStreamText(el, s.styles, s.classify, s.hidden)
            assertEquals(
                "$name: 叶 $i（${el.tag}）投影长度必须等于字符格数，否则其后每个字都漂移",
                advance.toInt(),
                text.length,
            )
            stream.append(text)
            total += advance
        }
        assertEquals("$name: 整章拼接总长必须等于整章字符数", total.toInt(), stream.length)
    }

    @Test
    fun `standalone image leaf occupies one slot`() {
        assertStreamAligned("独图叶", "<p>甲</p><img src=\"a.png\" width=\"10\" height=\"10\"/><p>乙</p>")

        val s = setup("<p>甲</p><img src=\"a.png\"/><p>乙</p>")
        val leaves = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(s.root, leaves, s.classify, s.hidden)
        assertEquals(listOf("p", "img", "p"), leaves.map { it.tag })
        // 旧 leafText 对 img 返回 ""（0 格），正是整章漂移的来源。
        assertEquals("", NormalFlowLayout.leafText(leaves[1], s.styles, s.classify, s.hidden))
        assertEquals("\uFFFC", NormalFlowLayout.leafStreamText(leaves[1], s.styles, s.classify, s.hidden))
    }

    @Test
    fun `inline image inside a paragraph keeps its slot`() {
        // 行内图本来就由 styledSegments 吸收成 U+FFFC —— 本锁防的是被上面的修复改坏。
        assertStreamAligned("行内图", "<p>前<img src=\"a.png\"/>后</p>")
    }

    @Test
    fun `table row leaf occupies its cell text`() {
        assertStreamAligned(
            "表格行",
            "<table><tr><td>甲</td><td>乙</td></tr>" +
                "<tr><td><p>丙</p></td><td>丁戊</td></tr></table><p>表后</p>",
        )

        val s = setup("<table><tr><td>甲</td><td>乙</td></tr><tr><td><p>丙</p></td><td>丁戊</td></tr></table>")
        val leaves = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(s.root, leaves, s.classify, s.hidden)
        assertEquals(listOf("tr", "tr"), leaves.map { it.tag })
        assertEquals("甲乙", NormalFlowLayout.leafStreamText(leaves[0], s.styles, s.classify, s.hidden))
        assertEquals("丙丁戊", NormalFlowLayout.leafStreamText(leaves[1], s.styles, s.classify, s.hidden))
    }

    @Test
    fun `block image inside a table cell gets one slot`() {
        assertStreamAligned(
            "格内独图",
            "<table><tr><td><img src=\"a.png\"/></td><td>字</td></tr></table>",
        )
    }

    @Test
    fun `caption br and hidden runs stay aligned`() {
        assertStreamAligned(
            "注脚/换行/隐藏",
            "<table><caption>表题</caption><tr><td>一<br/>二</td></tr></table>" +
                "<p>前<span style=\"display:none\">藏</span>后</p>",
        )
    }
}
