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
 * E23：块盒**指定高**（`height`）的消费口径。
 *
 * 三条纪律逐条上锁：
 *  1. `height` 非 `auto` 时**内容盒高** = max(在流内容高, `height`)（CSS 2.1 §10.6.3）；
 *  2. **内容更高时以内容为准**（溢出照排，不裁 —— 裁剪是 `overflow` 的事）；
 *  3. 浮动盒不推进流游标：指定高**只**进自身盒高（绘制 / 环绕跨度），**不改**后续内容的 Y。
 */
class BlockHeightConsumptionTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun node(
        tag: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<MarkupElement> = emptyList(),
    ): MarkupElement = MarkupElement(tag, attrs, children).also { e -> children.forEach { it.parent = e } }

    /** 每行固定 20 字的假断行器（行高 = 字号 × 行高比）。 */
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

    private fun boxes(root: MarkupElement, widthPx: Int, author: String = ""): List<LayoutBox> {
        val sm: Map<MarkupElement, ComputedStyle> = StyleComputer(
            rootFontPx = 16f,
            ua = LightCssParser().parse(""),
            authorSheets = listOf(LightCssParser().parse(author)),
        ).compute(root)
        val classify = NormalFlowLayout.heavyClassify(sm, author.isNotBlank())
        return NormalFlowLayout.layout(root, sm, Breaker(), widthPx, classify).boxes
    }

    private fun List<LayoutBox>.find(tag: String): LayoutBox {
        fun rec(b: LayoutBox): LayoutBox? =
            if (b.el?.tag == tag) b else b.childBoxes.firstNotNullOfOrNull { rec(it) }
        return firstNotNullOfOrNull { rec(it) } ?: error("树里没有 <$tag> 的盒")
    }

    private fun LayoutBox.heightPx(): Int = contentBottom - contentTop

    @Test
    fun `叶盒指定高生效并按自身边取 border-box`() {
        // `.fl{height:30px;border:1px solid}`：正文叶（零行）边框盒高 = 30 + 1 + 1。
        val fl = node("div", mapOf("style" to "height:30px;border:1px solid #52c41a"), emptyList())
        val root = node("body", children = listOf(fl))
        assertEquals(32, boxes(root, 600).find("div").heightPx())
    }

    @Test
    fun `内容更高时以内容为准`() {
        // 两行 × 24 = 48 > height:10 ⇒ 盒高 = 48（溢出照排，不裁）。
        val p = node(
            "p",
            mapOf("style" to "height:10px;line-height:1.5;font-size:16px"),
            listOf(text("一二三四五六七八九十一二三四五六七八九十一二三四五六")),
        )
        val root = node("body", children = listOf(p))
        assertEquals(48, boxes(root, 600).find("p").heightPx())
    }

    @Test
    fun `浮动盒指定高只进自身盒高不改后续内容 Y`() {
        // 带内容的浮动方块（height:40 + 边框）：自身盒高 42；后随段落仍在原处（浮动不推进流游标）。
        val fl = node(
            "div",
            mapOf("style" to "float:left;width:40px;height:40px;border:1px solid"),
            listOf(text("x")),
        )
        val p = node("p", children = listOf(text("x")))
        val root = node("body", children = listOf(fl, p))
        val out = boxes(root, 600)
        val floatBox = out.find("div")
        val pBox = out.find("p")
        assertEquals(42, floatBox.heightPx())
        // 浮动盒的边框盒顶即段落顶（浮动零高推进、异侧并存不占 Y）。
        assertEquals(floatBox.contentTop, pBox.contentTop)
    }

    @Test
    fun `auto 高逐值不变`() {
        // 反空跑：没有 `height` 声明时，盒高恒为「行高和 + 自身边」。
        val p = node("p", mapOf("style" to "line-height:1.5;font-size:16px"), listOf(text("x")))
        val root = node("body", children = listOf(p))
        assertEquals(24, boxes(root, 600).find("p").heightPx())
    }

    @Test
    fun `指定高单源口径`() {
        val sm = StyleComputer(
            rootFontPx = 16f,
            ua = LightCssParser().parse(""),
            authorSheets = listOf(LightCssParser().parse("div{height:50px}")),
        ).compute(node("body", children = listOf(node("div"))))
        val div = sm.keys.first { it.tag == "div" }
        assertEquals(50, NormalFlowLayout.specifiedContentHeight(sm.getValue(div), 10))
        // 内容更高 ⇒ 取内容；`height: auto` ⇒ 原样返回。
        assertEquals(80, NormalFlowLayout.specifiedContentHeight(sm.getValue(div), 80))
        assertEquals(80, NormalFlowLayout.specifiedContentHeight(null, 80))
    }
}
