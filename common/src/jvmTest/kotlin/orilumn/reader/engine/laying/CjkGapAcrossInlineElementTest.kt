package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「接缝上的空白一律删除」在**夹着一个行内元素**时同样成立（产品口径 2026-11-10）。
 *
 * 用户原话：「中英文边界（包括夹着一个html标签的情况），中间的所有空白字符
 * （不包括换行符）均删除，由混排字距统一来做隔离」。
 *
 * 这里锁的是**摊平**这一环：探测器（[CjkLatinSpacing.gaps]）只看叶文本里的字符，
 * 不认元素边界。所以 `中文 <span>Rust</span>` / `中文<span> Rust</span>` /
 * `中文<span>&nbsp;</span>Rust` 三种写法摊平后必须是**同一个字符串**，
 * 边界与 `spaceCount` 也必须一模一样 —— 否则「标签夹在中间」的那份空白就漏吃了。
 *
 * ⚠ 这条不是「探测器要认标签」：探测器永远不认标签（它拿不到 DOM），
 * 保证它成立的是 [NormalFlowLayout.leafText] 的行内吸收（`absorbStyled`）——
 * 节点级归一不 trim、叶级只 trim 首尾，行内边界上的空白保留一位并入同一个字符串。
 */
class CjkGapAcrossInlineElementTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, emptyMap(), children)

    private fun styles(root: MarkupElement) =
        StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse(""),
            authorSheets = listOf(LightCssParser().parse("")),
        ).compute(root)

    /** `<p>` 的叶文本 + 其上的混排间隙（`leftIndex` 相对叶文本）。 */
    private fun leafGaps(p: MarkupElement): Pair<String, List<CjkLatinGap>> {
        val root = node("body", listOf(p))
        val styleMap: Map<MarkupElement, ComputedStyle> = styles(root)
        val classify = BlockClassify { el ->
            NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true
        }
        val hidden = HiddenCheck { styleMap[it]?.displayNone == true }
        val t = NormalFlowLayout.leafText(p, styleMap, classify, hidden)
        return t to CjkLatinSpacing.gaps(t, 0.25f)
    }

    @Test
    fun `space before a span is the same as a space inside it`() {
        // 三种写法在探测器眼里必须是**同一个字符串**（这是本文件的立论基础）。
        val shapes = listOf(
            "空白在标签外（前置）" to node(
                "p", listOf(text("中文 "), node("span", listOf(text("Rust"))), text(" 的")),
            ),
            "空白在标签内（前导）" to node(
                "p", listOf(text("中文"), node("span", listOf(text(" Rust"))), text(" 的")),
            ),
            "空白在标签内（后随）" to node(
                "p", listOf(text("中文 "), node("span", listOf(text("Rust "))), text("的")),
            ),
        )
        for ((name, p) in shapes) {
            val (t, gaps) = leafGaps(p)
            assertEquals("$name：摊平后应是同一个叶文本", "中文 Rust 的", t)
            assertEquals(
                "$name：两侧接缝照吃，各留一条间隙",
                listOf(
                    CjkLatinGap(1, 0.25f, spaceCount = 1),
                    CjkLatinGap(6, 0.25f, spaceCount = 1),
                ),
                gaps,
            )
        }
    }

    @Test
    fun `whitespace-only inline element still separates and is eaten`() {
        // `<span>&nbsp;</span>` 这种「只有空白的行内元素」在 EPUB 转换产物里很常见。
        val p = node(
            "p",
            listOf(
                text("中文"),
                node("span", listOf(text("\u00A0"))),
                node("em", listOf(text("Rust"))),
            ),
        )
        val (t, gaps) = leafGaps(p)
        assertEquals("中文\u00A0Rust", t)
        assertEquals(listOf(CjkLatinGap(1, 0.25f, spaceCount = 1)), gaps)
    }

    @Test
    fun `nested inline elements do not hide the seam`() {
        val p = node(
            "p",
            listOf(
                text("使用 "),
                node("a", listOf(node("code", listOf(text("Rust"))))),
                text(" 的"),
            ),
        )
        val (t, gaps) = leafGaps(p)
        assertEquals("使用 Rust 的", t)
        assertEquals(
            listOf(
                CjkLatinGap(1, 0.25f, spaceCount = 1),
                CjkLatinGap(6, 0.25f, spaceCount = 1),
            ),
            gaps,
        )
    }

    @Test
    fun `latin-first seam across a tag is eaten too`() {
        val p = node(
            "p",
            listOf(node("span", listOf(text("Rust"))), text(" 中文")),
        )
        val (t, gaps) = leafGaps(p)
        assertEquals("Rust 中文", t)
        assertEquals(listOf(CjkLatinGap(3, 0.25f, spaceCount = 1)), gaps)
    }
}
