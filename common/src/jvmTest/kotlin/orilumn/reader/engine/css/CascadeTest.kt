package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lightweight tree builders + assertions for the cascade/compute layer. */
private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
    MarkupElement(tag, attrs, children)

private fun sheet(css: String) = LightCssParser().parse(css)

private fun styleMap(ua: String, author: String, rootFontPx: Float = 16f, root: MarkupElement): Map<MarkupElement, ComputedStyle> =
    StyleComputer(rootFontPx, sheet(ua), listOf(sheet(author))).compute(root)

class CascadeTest {

    @Test
    fun `作者来源优先于 UA`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "p { color: red }", author = "p { color: blue }", root = root)
        assertEquals("#ff0000ff", out[p]?.colorHex)
    }

    @Test
    fun `特异性 class 优先于 type`() {
        val p = node("p", mapOf("class" to "note"))
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "p { color: red }", author = "p { color: blue } .note { color: green }", root = root)
        assertEquals("#ff008000", out[p]?.colorHex)
    }

    @Test
    fun `important 反转来源优先级`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        // UA important beats author normal
        val a = styleMap(ua = "p { color: red !important }", author = "p { color: blue }", root = root)
        assertEquals("#ffff0000", a[p]?.colorHex)
        // author important beats UA normal
        val b = styleMap(ua = "p { color: red }", author = "p { color: blue !important }", root = root)
        assertEquals("#ff0000ff", b[p]?.colorHex)
    }

    @Test
    fun `同特异性后定义者胜`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "p { color: red } p { color: blue }", root = root)
        assertEquals("#ff0000ff", out[p]?.colorHex)
    }

    @Test
    fun `内联样式优先于作者`() {
        val p = node("p", mapOf("style" to "color: blue"))
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "p { color: red }", root = root)
        assertEquals("#ff0000ff", out[p]?.colorHex)
    }

    @Test
    fun `内联 important 胜于作者 important`() {
        val p = node("p", mapOf("style" to "color: blue !important"))
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "p { color: red !important }", root = root)
        assertEquals("#ff0000ff", out[p]?.colorHex)
    }

    @Test
    fun `颜色与字重继承`() {
        val em = node("em")
        val p = node("p", children = listOf(em))
        val root = node("body", mapOf("style" to "color: red; font-weight: bold"), children = listOf(p))
        val out = styleMap(ua = "", author = "", root = root)
        assertEquals("#ffff0000", out[em]?.colorHex)
        assertTrue(out[em]!!.bold)
    }

    @Test
    fun `font-size 百分比基于父级且内联胜出`() {
        val plain = node("p") // no font-size → matches author p{font-size:50%}
        val inner = node("span")
        val styled = node("p", mapOf("style" to "font-size: 20px"), children = listOf(inner))
        val root = node("body", children = listOf(plain, styled))
        val out = styleMap(ua = "", author = "p { font-size: 50% }", root = root)
        // body font = rootFontPx 16; plain p{50%} → 8px
        assertEquals(8f, out[plain]?.fontSizePx!!, 1e-3f)
        // styled p: inline 20px overrides author 50% → 20px; span inherits 20
        assertEquals(20f, out[styled]?.fontSizePx!!, 1e-3f)
        assertEquals(20f, out[inner]?.fontSizePx!!, 1e-3f)
    }

    @Test
    fun `rem 基于根字号并继承`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "p { font-size: 1.5rem }", rootFontPx = 16f, root = root)
        assertEquals(24f, out[p]?.fontSizePx!!, 1e-3f)
    }

    @Test
    fun `行高与行距解析`() {
        val p1 = node("p", mapOf("style" to "line-height: 1.5"))
        val p2 = node("p", mapOf("style" to "line-height: 24px"))
        val root = node("body", children = listOf(p1, p2))
        val out = styleMap(ua = "", author = "", root = root)
        assertEquals(1.5f, out[p1]?.lineHeightRatio!!, 1e-3f)
        // 24px with root font 16 → 1.5x
        assertEquals(1.5f, out[p2]?.lineHeightRatio!!, 1e-3f)
    }

    @Test
    fun `不可继承属性初始化`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "", root = root)
        assertEquals(0f, out[p]?.textIndentPx!!, 1e-3f)
        assertFalse(out[p]!!.underline)
    }

    @Test
    fun `后代选择器匹配`() {
        val em = node("em")
        val p = node("p", children = listOf(em))
        val note = node("p", mapOf("class" to "note"), children = listOf(p))
        val root = node("body", children = listOf(note))
        val out = styleMap(ua = "", author = ".note p { color: red }", root = root)
        assertEquals("#ffff0000", out[p]?.colorHex)
        assertEquals("#ffff0000", out[em]?.colorHex)
    }

    @Test
    fun `颜色未声明时继承初始为 null`() {
        val root = node("body")
        val out = styleMap(ua = "", author = "", root = root)
        assertNull(out[root]?.colorHex)
    }

    @Test
    fun `text-indent em 基于元素字号`() {
        val p = node("p", mapOf("style" to "font-size: 10px; text-indent: 2em"))
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "", root = root)
        assertEquals(20f, out[p]?.textIndentPx!!, 1e-3f)
    }

    @Test
    fun `id 特异性高于 class`() {
        val p = node("p", mapOf("id" to "head", "class" to "note"))
        val root = node("body", children = listOf(p))
        val out = styleMap(ua = "", author = "#head { color: red } .note { color: blue }", root = root)
        assertEquals("#ffff0000", out[p]?.colorHex)
    }

    @Test
    fun `属性选择器匹配`() {
        val a = node("a", mapOf("href" to "x.html"))
        val root = node("body", children = listOf(a))
        val out = styleMap(ua = "", author = "a[href] { color: red }", root = root)
        assertEquals("#ffff0000", out[a]?.colorHex)
    }

    @Test
    fun `读者层裸通用名缀在书栈后而非替换`() {
        // 传统模式：UI serif 不能替换书栈（书里点名的导入字体池中有），只能垫底做最终回退。
        val p = node("p")
        val root = node("body", children = listOf(p))
        val engine = StyleComputer(
            16f, sheet(""),
            listOf(sheet("body{font-family:\"霞鹜文楷\", serif}")),
            null, null, sheet("body{font-family:serif}"),
        )
        val out = engine.compute(root)
        assertEquals(listOf("霞鹜文楷", "serif"), out[root]?.fontFamilies)
        assertEquals(listOf("霞鹜文楷", "serif"), out[p]?.fontFamilies)
    }

    @Test
    fun `具名槽照旧全覆盖书栈`() {
        // 用户显式选择：UI 具名族替换一切（旧 resolveBodyOrSlot 语义）。
        val p = node("p")
        val root = node("body", children = listOf(p))
        val engine = StyleComputer(
            16f, sheet(""),
            listOf(sheet("body{font-family:\"霞鹜文楷\", serif}")),
            null, null, sheet("body{font-family:\"屏显臻宋\"}"),
        )
        val out = engine.compute(root)
        assertEquals(listOf("屏显臻宋"), out[root]?.fontFamilies)
        assertEquals(listOf("屏显臻宋"), out[p]?.fontFamilies)
    }

    @Test
    fun `无书栈时裸通用名保持不变`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val engine = StyleComputer(
            16f, sheet(""), emptyList(),
            null, null, sheet("body{font-family:serif}"),
        )
        val out = engine.compute(root)
        assertEquals(listOf("serif"), out[root]?.fontFamilies)
    }

    @Test
    fun `pre 块兜底为等宽且书内 code 字体仍胜过`() {
        // UA 把 pre 一并纳入 monospace：叶子只用自身族名绘制，`<pre><code>` 里 code 的
        // UA 规则被吸收吞掉，pre 自身声明才能让代码块恒等宽（书显式写字体的按书走）。
        val pre = node("pre")
        val body = node("body", children = listOf(pre))
        val mono = StyleComputer(16f, sheet("code,kbd,samp,tt,pre{font-family:monospace}"), emptyList()).compute(body)
        assertEquals(listOf("monospace"), mono[pre]?.fontFamilies)
        assertTrue(mono[pre]!!.monospace)

        // 书里对 pre 点名实体字体：UA 兜底让位（浏览器语义）。
        val pre2 = node("pre")
        val authorPre = StyleComputer(
            16f, sheet("code,kbd,samp,tt,pre{font-family:monospace}"),
            listOf(sheet("pre{font-family:\"Fira Code\", monospace}")),
        ).compute(node("body", children = listOf(pre2)))
        val bookPre = authorPre[pre2]!!
        assertEquals(listOf("Fira Code", "monospace"), bookPre.fontFamilies)
        assertTrue(bookPre.monospace)
    }
}