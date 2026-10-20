package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E22：块盒**指定宽**（`width`）的消费口径。
 *
 * 三条纪律逐条上锁：
 *  1. **单向消费**：`width` 非 `auto` 时 border-box 宽 = 指定内容宽 + 自身横向 `padding`/`border`
 *     （CSS 2.1 §10.3.3），父给的可用宽不再决定宽度；
 *  2. **`%` 按包含块内容宽**解（不是父的 border-box 宽）；
 *  3. **表格 / 格 / 替换元素 / 浮动不在本层消费**（各自另有归属：表格网格、替换尺寸、浮动排位模型）；
 *  4. **重（`buildBoxTree`）/ 轻（`descendContentWidth`）两路同一判据**，逐值相等。
 */
class BlockWidthConsumptionTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun node(
        tag: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<MarkupElement> = emptyList(),
    ): MarkupElement = MarkupElement(tag, attrs, children).also { e -> children.forEach { it.parent = e } }

    /** 每行固定 20 字的假断行器（只求能排出真实行，宽度断言与它无关）。 */
    private class Breaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence,
            fontSizePx: Float,
            lineHeightRatio: Float,
            widthPx: Int,
            alignment: TextAlign,
            tag: String?,
            families: List<String>,
            weight: Int,
            italic: Boolean,
            monospace: Boolean,
        ): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + 20).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun styles(root: MarkupElement, author: String): Map<MarkupElement, ComputedStyle> =
        StyleComputer(
            rootFontPx = 16f,
            ua = LightCssParser().parse(""),
            authorSheets = listOf(LightCssParser().parse(author)),
        ).compute(root)

    private class Tree(val boxes: List<LayoutBox>, val styles: Map<MarkupElement, ComputedStyle>) {
        fun find(tag: String): LayoutBox = findOrNull(tag) ?: error("树里没有 <$tag> 的盒")

        fun findOrNull(tag: String): LayoutBox? {
            fun rec(b: LayoutBox): LayoutBox? =
                if (b.el?.tag == tag) b else b.childBoxes.firstNotNullOfOrNull { rec(it) }
            return boxes.firstNotNullOfOrNull { rec(it) }
        }

        fun edgesOf(el: MarkupElement): Int {
            val s = styles.getValue(el)
            return (s.padding.horizontal + s.border.horizontal).roundToInt()
        }

        fun marginsOf(el: MarkupElement): Int {
            val s = styles.getValue(el)
            return (s.margin.left + s.margin.right).roundToInt()
        }

        /** 轻路径口径的 border-box 可用宽（见 [NormalFlowLayout.descendContentWidth]）。 */
        fun lightAvail(el: MarkupElement, rootW: Int): Int =
            NormalFlowLayout.descendContentWidth(el, rootW, ::edgesOf, ::marginsOf) { styles[it] }
    }

    private fun tree(root: MarkupElement, widthPx: Int, author: String): Tree {
        val sm = styles(root, author)
        val classify = NormalFlowLayout.heavyClassify(sm, author.isNotBlank())
        val r = NormalFlowLayout.layout(root, sm, Breaker(), widthPx, classify)
        return Tree(r.boxes, sm)
    }

    @Test
    fun `指定宽单向消费并按自身边取 border-box`() {
        // `.ovh{width:100px;border:1px;padding:4px}` 里装一个 `.wide{width:300px}`。
        val wide = node("div", mapOf("style" to "width:300px"), listOf(text("超宽超宽超宽超宽超宽超宽")))
        val ovh = node(
            "div",
            mapOf("style" to "width:100px;border:1px solid #333;padding:4px"),
            listOf(wide),
        )
        val root = node("body", children = listOf(ovh))
        val t = tree(root, widthPx = 600, author = "")
        // 100（指定内容宽）+ 1+1（边框）+ 4+4（内边距）= 110。
        assertEquals(110, t.find("div").contentWidth)
        assertEquals(300, t.findOrNull("div")!!.childBoxes.single().contentWidth)
        // 轻路径同值（同一闸门）。
        assertEquals(110, t.lightAvail(ovh, 600))
        assertEquals(300, t.lightAvail(wide, 600))
    }

    @Test
    fun `百分比按包含块内容宽解`() {
        val half = node("div", mapOf("style" to "width:50%"), listOf(text("x")))
        val box = node("div", mapOf("style" to "width:200px;padding:10px"), listOf(half))
        val root = node("body", children = listOf(box))
        val t = tree(root, widthPx = 600, author = "")
        // 外层 = 200 + 20（左右各 10 内边距）= 220；内层 = 50% × 外层**内容宽 200** = 100。
        assertEquals(220, t.find("div").contentWidth)
        assertEquals(100, t.findOrNull("div")!!.childBoxes.single().contentWidth)
        assertEquals(100, t.lightAvail(half, 600))
    }

    @Test
    fun `auto 宽仍沿用父给的可用宽`() {
        val plain = node("div", children = listOf(text("x")))
        val ovh = node("div", mapOf("style" to "width:100px;padding:4px"), listOf(plain))
        val root = node("body", children = listOf(ovh))
        val t = tree(root, widthPx = 600, author = "")
        // 无指定宽 ⇒ 包含块（110）内容宽减去自身外边距（0）= 100。
        assertEquals(100, t.findOrNull("div")!!.childBoxes.single().contentWidth)
        assertEquals(100, t.lightAvail(plain, 600))
    }

    @Test
    fun `浮动元素不在本层消费指定宽`() {
        // 浮动盒宽归浮动排位模型（`floatTextWidth`）；本层若再消费一次就是双算。
        val fl = node("div", mapOf("style" to "float:left;width:30px;height:30px"), emptyList())
        val root = node("body", children = listOf(fl))
        val t = tree(root, widthPx = 600, author = "")
        assertEquals(30, t.find("div").contentWidth)
    }

    @Test
    fun `非相对定位的块不受宽度以外的量影响`() {
        // 反空跑：没有 `width` 声明时，盒宽与「重路径本来就会给的值」逐值相同（父内容宽）。
        val p = node("p", children = listOf(text("abcdef")))
        val root = node("body", children = listOf(p))
        val t = tree(root, widthPx = 600, author = "")
        assertEquals(600, t.find("p").contentWidth)
        assertEquals(600, t.lightAvail(p, 600))
    }
}
