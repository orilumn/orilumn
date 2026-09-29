package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R29 回归：`parseBorderColors` / `parseBorderStyles` 的**四槽顺序**必须是
 * top / right / bottom / left，且逐边回退链不变。
 *
 * ## 为什么锁这个
 *
 * R39 把这两个函数从 `listOf("top","right","bottom","left")` + `mapIndexed` +
 * `"border-$side-color"` 拼接查表，改成**显式四槽 + 预建 key 常量**
 * （`cEdge` 37.5→28ms，−25%）。
 *
 * 危险方向是**单向且无声**的：四槽顺序若被打乱（把 bottom 写成 left），
 * 编译通过、"整体非空"的断言也照样过，但**左/下边框会画到别的边上**——
 * 书里每条带框线都错位，且没有任何异常。
 *
 * 走真实链路（HTML → `computeOne` → `computeStyle`）而不是开测试后门：
 * `computeStyle` 是私有的，测试要打的是"内联声明 → 边框槽位"这条真链路；
 * 走链路还能顺带覆盖 UA 表的合并。
 *
 * 两种输入各有分工：
 *  - **只声明一边** → 抓住"哪个 key 配哪个槽"（配错则那槽为 null）；
 *  - **四边声明不同值** → 抓住"槽位顺序整体错位"。
 */
class BorderSideSlotsTest {

    private val converter = HtmlTreeConverter()

    /** 用一段内联 `style` 造出 `<p>` 的计算样式（走 `compute` 真链路）。 */
    private fun paraStyle(inline: String): ComputedStyle {
        val root = converter.convert("<html><body><p style=\"$inline\">正文</p></body></html>")!!
        val styles = StyleComputer(16f, StyleSheet(emptyList()), emptyList()).compute(root)
        val p = root.children.first { it.tag == "p" }
        return styles[p]!!
    }

    // ---- border-color ---------------------------------------------------

    @Test
    fun `border-color 一槽给四边`() {
        // 槽里存的是 `parseCssColor` **归一化后的 hex**（色名 `red` → `#ffff0000`），
        // 不是原始色名——断言必须按归一化值写，否则测的是字符串巧合。
        val c = paraStyle("border-color: red").borderColors
        assertNotNull("border-color:red 应产出四边颜色", c)
        assertEquals(listOf("#ffff0000", "#ffff0000", "#ffff0000", "#ffff0000"),
            listOf(c!!.top, c.right, c.bottom, c.left))
    }

    @Test
    fun `border-color 两槽映射 上下 与 左右`() {
        val c = paraStyle("border-color: #ff0000 #00ff00").borderColors
        assertNotNull(c)
        // 2 槽 = (上,右)+(下,左) → top=bottom=red, right=left=green
        assertEquals("#ffff0000", c!!.top)
        assertEquals("#ff00ff00", c.right)
        assertEquals("#ffff0000", c.bottom)
        assertEquals("#ff00ff00", c.left)
    }

    @Test
    fun `border-color 四槽保持 top right bottom left 顺序`() {
        val c = paraStyle("border-color: #ff0000 #00ff00 #0000ff #ffff00").borderColors
        assertNotNull(c)
        assertEquals("#ffff0000", c!!.top)
        assertEquals("#ff00ff00", c.right)
        assertEquals("#ff0000ff", c.bottom)
        assertEquals("#ffffff00", c.left)
    }

    @Test
    fun `逐边 color key 只落自己的槽`() {
        // 只声明 left：若 key 与槽位配错（如 border-left-color 落到 top），assertNull 立刻抓住。
        val c = paraStyle("border-left-color: red").borderColors
        assertNotNull(c)
        assertNull(c!!.top)
        assertNull(c.right)
        assertNull(c.bottom)
        assertEquals("#ffff0000", c.left)
    }

    @Test
    fun `逐边简写里的颜色按槽回退`() {
        val c = paraStyle("border-top: 1px solid red; border-bottom: 3px solid blue").borderColors
        assertNotNull("border-top/bottom 简写里的颜色应被取出", c)
        assertEquals("#ffff0000", c!!.top)
        assertNull(c.right)
        assertEquals("#ff0000ff", c.bottom)
        assertNull(c.left)
    }

    // ---- border-style ---------------------------------------------------

    @Test
    fun `border-style 四槽保持 top right bottom left 顺序`() {
        // 第 4 槽用 `none`：**必须**是 BORDER_STYLE_WORDS 里的词（none/hidden/solid/dashed/dotted），
        // 否则会被 `filter { it in BORDER_STYLE_WORDS }` 滤掉、整条声明退化成 3 槽，
        // 3 槽语义又把 dotted 复制到 left——那样测的就不再是"4 槽顺序"了（我第一版踩过）。
        // 四槽值两两可区分，槽位整体错位（bottom↔left）会立刻改掉断言。
        val st = paraStyle("border-style: solid dashed dotted none").borderStyles
        assertNotNull(st)
        assertEquals(BorderStyle.SOLID, st!!.top)
        assertEquals(BorderStyle.DASHED, st.right)
        assertEquals(BorderStyle.DOTTED, st.bottom)
        assertEquals(BorderStyle.NONE, st.left)
    }

    @Test
    fun `border-style 两槽映射 上下 与 左右`() {
        val st = paraStyle("border-style: solid dashed").borderStyles
        assertNotNull(st)
        assertEquals(BorderStyle.SOLID, st!!.top)
        assertEquals(BorderStyle.DASHED, st.right)
        assertEquals(BorderStyle.SOLID, st.bottom)
        assertEquals(BorderStyle.DASHED, st.left)
    }

    @Test
    fun `逐边 style key 只落自己的槽`() {
        val st = paraStyle("border-bottom-style: dashed").borderStyles
        assertNotNull(st)
        // 未声明的三边回退 NONE（`declared` 由 bottom 置位，故整体非 null）
        assertEquals(BorderStyle.NONE, st!!.top)
        assertEquals(BorderStyle.NONE, st.right)
        assertEquals(BorderStyle.DASHED, st.bottom)
        assertEquals(BorderStyle.NONE, st.left)
    }

    @Test
    fun `逐边简写里的 style 词按槽回退`() {
        val st = paraStyle("border-top: 1px dashed red").borderStyles
        assertNotNull(st)
        assertEquals(BorderStyle.DASHED, st!!.top)
        assertEquals(BorderStyle.NONE, st.right)
        assertEquals(BorderStyle.NONE, st.bottom)
        assertEquals(BorderStyle.NONE, st.left)
    }

    @Test
    fun `无 border 声明时两个家族都返回 null`() {
        val s = paraStyle("color: red")
        assertNull("无 border 声明时颜色家族应返回 null（不产出空对象）", s.borderColors)
        assertNull("无 border 声明时线型家族应返回 null", s.borderStyles)
    }
}
