package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S32 — [NormalFlowLayout.leafText] 单源漂移 guard：外部 DrawLine 桥（桌面壳）重算的叶子文本
 * 必须与塑形输入逐字一致，否则断行区间与绘制文本错位。
 */
class LeafTextTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + charsPerLine).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun styles(root: MarkupElement) =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    private fun layout(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>): BoxLayoutResult {
        val classify = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        return BoxLayouter(10f, FixedWidthBreaker(1000)).layoutBoxes(root, widthPx = 300, styleMap = styleMap, classify = classify)
    }

    private fun collectLeaves(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(collectLeaves(b.childBoxes)) else out.add(b)
        return out
    }

    @Test
    fun `叶子文本与塑形输入一致`() {
        val b = node("b", children = listOf(text("def")))
        val p1 = node("p", children = listOf(text("abc"), b, text("ghi")))
        val p2 = node("p", children = listOf(text("jk"), node("br"), text("lm")))
        val root = node("body", children = listOf(p1, p2))
        val styleMap = styles(root)
        val classify = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        val hidden = HiddenCheck { styleMap[it]?.displayNone == true }
        val leaves = collectLeaves(layout(root, styleMap).boxes)

        assertEquals(2, leaves.size)
        // 行内吸收：abc + def + ghi
        assertEquals("abcdefghi", NormalFlowLayout.leafText(leaves[0].el, styleMap, classify, hidden))
        // br 折成换行
        assertEquals("jk\nlm", NormalFlowLayout.leafText(leaves[1].el, styleMap, classify, hidden))
        for (leaf in leaves) {
            val t = NormalFlowLayout.leafText(leaf.el, styleMap, classify, hidden)
            assertEquals("leafText 长度必须等于塑形输入长度", leaf.textLength, t.length)
            for (r in leaf.ranges) {
                assertTrue("区间 $r 越界（文本长 ${t.length}）", r.first >= 0 && r.last < t.length)
            }
        }
    }

    @Test
    fun `匿名叶子与图片占位`() {
        val img = node("img", mapOf("src" to "a.png"))
        val p = node("p", children = listOf(text("ab"), img, text("cd")))
        // 容器杂散行内文本成为匿名叶子
        val div = node("div", children = listOf(text("前言"), p))
        val root = node("body", children = listOf(div))
        val styleMap = styles(root)
        val classify = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        val hidden = HiddenCheck { styleMap[it]?.displayNone == true }
        val leaves = collectLeaves(layout(root, styleMap).boxes)

        assertEquals(2, leaves.size)
        assertEquals("前言", NormalFlowLayout.leafText(leaves[0].el, styleMap, classify, hidden))
        // 行内 img 贡献 U+FFFC 占位
        assertEquals("ab" + "\uFFFC" + "cd", NormalFlowLayout.leafText(leaves[1].el, styleMap, classify, hidden))
        for (leaf in leaves) {
            assertEquals(leaf.textLength, NormalFlowLayout.leafText(leaf.el, styleMap, classify, hidden).length)
        }
    }

    @Test
    fun `空与非文本叶子`() {
        assertEquals("", NormalFlowLayout.leafText(null, emptyMap(), NormalFlowLayout.DEFAULT_CLASSIFY))
        val table = node("table")
        assertEquals("", NormalFlowLayout.leafText(table, emptyMap(), NormalFlowLayout.DEFAULT_CLASSIFY))
    }
}
