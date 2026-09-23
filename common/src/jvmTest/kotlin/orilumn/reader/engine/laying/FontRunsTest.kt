package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S32 — [NormalFlowLayout.leafFontRuns] 行内 face 段的单源 guard：与 [NormalFlowLayout.leafText]
 * 同一遍历产出，索引恒对齐；纯种叶必须零 run（断行/绘制零开销、与整叶单 face 逐字节一致）。
 */
class FontRunsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el // 与 HtmlTreeConverter 一致：写出 parent 链
        return el
    }

    private fun styles(
        root: MarkupElement,
        ua: String = "body { font-family: serif } code,kbd,samp,tt,pre { font-family: monospace } strong { font-weight: bold }",
    ): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(ua), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    private fun fonts(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>): List<FontRun> =
        NormalFlowLayout.leafFontRuns(
            root,
            styleMap,
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true },
            HiddenCheck { styleMap[it]?.displayNone == true },
        )

    private fun face(run: FontRun, expect: FontRun) {
        assertEquals("families", expect.families, run.families)
        assertEquals("tag", expect.tag, run.tag)
        assertEquals("weight", expect.weight, run.weight)
        assertEquals("italic", expect.italic, run.italic)
        assertEquals("monospace", expect.monospace, run.monospace)
        assertEquals("fontSizePx", expect.fontSizePx, run.fontSizePx, 1e-4f)
    }

    @Test
    fun `行内 code 产生等宽段且下标与外层文本对齐`() {
        val p = node("p", children = listOf(text("ab"), node("code", children = listOf(text("cd"))), text("ef")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        val runs = fonts(p, styleMap)

        assertEquals(1, runs.size)
        face(runs[0], FontRun(0, 0, listOf("monospace"), "code", 400, false, true, 10f))
        assertEquals(2, runs[0].start)
        assertEquals(4, runs[0].endExclusive)
        assertEquals("cd", leafTextOf(p, styleMap).substring(runs[0].start, runs[0].endExclusive))
    }

    private fun leafTextOf(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>): String {
        val classify = BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }
        val hidden = HiddenCheck { styleMap[it]?.displayNone == true }
        return NormalFlowLayout.leafText(root, styleMap, classify, hidden)
    }

    @Test
    fun `纯种叶子零 run`() {
        val p = node("p", children = listOf(text("abcdef"), node("br"), text("gh")))
        val body = node("body", children = listOf(p))
        val styleMap = styles(body)
        // 同时验证 leafText 仍把 br 折成换行（这两条文本构建并行存在）。
        assertEquals("abcdef\ngh", leafTextOf(p, styleMap))
        assertTrue("纯种叶不得产出 run：${fonts(p, styleMap)}", fonts(p, styleMap).isEmpty())
    }

    @Test
    fun `strong 产生加粗段并覆盖正确下标`() {
        val p = node("p", children = listOf(text("a"), node("strong", children = listOf(text("b"))), text("c")))
        val body = node("body", children = listOf(p))
        val styleMap = styles(body)
        val runs = fonts(p, styleMap)
        assertEquals(1, runs.size)
        face(runs[0], FontRun(0, 0, listOf("serif"), "strong", 700, false, false, 10f))
        assertEquals(1, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }

    @Test
    fun `pre 基底已等宽则同 face 子树零 run`() {
        val pre = node("pre", children = listOf(text("mono face")))
        // UA 里 pre 也是等宽：基底 mono=true，子树同 face → 零 delta run。
        val styleMap = styles(pre)
        assertTrue("pre 基底等宽时不得产出 run：${fonts(pre, styleMap)}", fonts(pre, styleMap).isEmpty())
    }

    @Test
    fun `同一 face 的相邻段合并、img 与 br 占位不产生段`() {
        val p = node(
            "p",
            children = listOf(
                text("a"),
                node("code", children = listOf(text("x"))),
                node("code", children = listOf(text("y"))),
                node("img", mapOf("src" to "a.png")),
                node("br"),
                text("b"),
            ),
        )
        val body = node("body", children = listOf(p))
        val styleMap = styles(body)
        val runs = fonts(p, styleMap)
        val text = leafTextOf(p, styleMap)
        // img 占 U+FFFC、br 占 '\n'；code 段 [1,3) 相邻合并成单个 run。
        assertEquals("axy\uFFFC\nb", text)
        assertEquals(1, runs.size)
        face(runs[0], FontRun(0, 0, listOf("monospace"), "code", 400, false, true, 10f))
        assertEquals(1, runs[0].start)
        assertEquals(3, runs[0].endExclusive)
    }

    @Test
    fun `display none 子树不参与 run 文本`() {
        val p = node(
            "p",
            children = listOf(
                text("a"),
                node("span", mapOf("style" to "display:none"), children = listOf(node("code", children = listOf(text("zz"))))),
                text("b"),
            ),
        )
        val body = node("body", children = listOf(p))
        val styleMap = styles(body)
        val runs = fonts(p, styleMap)
        val text = leafTextOf(p, styleMap)
        assertEquals("ab", text)
        assertTrue("被裁剪的 code 不得产出 run：$runs", runs.isEmpty())
    }

    @Test
    fun `UA 上下标字号换算进 run 且与文本对齐`() {
        // 浏览器 UA `sub,sup/small/big{font-size:.7em/.8em/1.2em}` 的行内字号必须进 run：
        // sub 段算出 0.7×叶子基底的 px，段只盖 sub 的子串；其余位置不放段。
        val p = node("p", children = listOf(text("a"), node("sub", children = listOf(text("b"))), text("c")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root, ua = "body { font-family: serif } sub { font-size: 0.7em }")
        val runs = fonts(p, styleMap)
        val text = leafTextOf(p, styleMap)
        assertEquals("abc", text)
        assertEquals(1, runs.size)
        face(runs[0], FontRun(0, 0, listOf("serif"), "sub", 400, false, false, 7f))
        assertEquals(1, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }

    @Test
    fun `同 face 异字号 span 也产生段`() {
        // 回归"行内字号整条丢失"：只字号不同（families/weight/italic/mono 全同）的 span 也必须单列成段，
        // 否则断行/绘制看不到 1.2em 的字号，肉眼等同正文。
        val p = node(
            "p",
            children = listOf(text("a"), node("span", mapOf("style" to "font-size: 1.2em"), children = listOf(text("b"))), text("c")),
        )
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        val runs = fonts(p, styleMap)
        assertEquals(1, runs.size)
        face(runs[0], FontRun(0, 0, listOf("serif"), "span", 400, false, false, 12f))
        assertEquals(1, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }
}