package orilumn.reader.engine.html

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPreprocessorTest {

    private val converter = HtmlTreeConverter()

    private fun preprocess(xhtml: String, css: String = ""): MarkupElement {
        val root = converter.convert(xhtml)!!
        val sheets: List<StyleSheet> =
            if (css.isBlank()) emptyList() else listOf(LightCssParser().parse(css))
        return ChapterPreprocessor.preprocess(root, sheets)
    }

    private fun imgClasses(tree: MarkupElement): List<String> =
        collectImgs(tree).map { it.attrs["class"].orEmpty() }

    private fun collectImgs(el: MarkupElement): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        if (el.tag == "img") out.add(el)
        for (c in el.children) out.addAll(collectImgs(c))
        return out
    }

    private fun parentChainValid(el: MarkupElement): Boolean {
        var node: MarkupElement? = el
        var depth = 0
        while (node != null && depth < 100) {
            val parent = node.parent
            if (parent != null && !parent.children.any { it === node }) return false
            node = parent
            depth++
        }
        return node == null && depth > 0
    }

    @Test
    fun `p 独图段落获得类`() {
        val tree = preprocess("<html><body><p><img src='a.png'/></p></body></html>")
        assertEquals(listOf(ChapterPreprocessor.FULLWIDTH_CLASS), imgClasses(tree))
    }

    @Test
    fun `figure 独图获得类`() {
        val tree = preprocess("<html><body><figure><img src='a.png'/></figure></body></html>")
        assertTrue(imgClasses(tree).all { it == ChapterPreprocessor.FULLWIDTH_CLASS })
    }

    @Test
    fun `混合文字的行内图不打类`() {
        val tree = preprocess("<html><body><p>文字 <img src='a.png'/> 更多文字</p></body></html>")
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `带题注的 figure 不打类`() {
        val tree =
            preprocess("<html><body><figure><img src='a.png'/><figcaption>题注</figcaption></figure></body></html>")
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `容器内多张图都不打类`() {
        val tree = preprocess("<html><body><div><img src='a.png'/><img src='b.png'/></div></body></html>")
        assertEquals(listOf("", ""), imgClasses(tree))
    }

    @Test
    fun `仅空白环绕的独图仍打类`() {
        val tree = preprocess("<html><body><div>\n  <img src='a.png'/>\n</div></body></html>")
        assertEquals(listOf(ChapterPreprocessor.FULLWIDTH_CLASS), imgClasses(tree))
    }

    @Test
    fun `内联 width 声明跳过`() {
        val tree = preprocess("<html><body><p><img src='a.png' style='width:80%'/></p></body></html>")
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `内联 max-width 声明跳过`() {
        val tree = preprocess("<html><body><p><img src='a.png' style='max-width:80%'/></p></body></html>")
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `内联 max-width none 视为未声明`() {
        val tree = preprocess("<html><body><p><img src='a.png' style='max-width:none'/></p></body></html>")
        assertEquals(listOf(ChapterPreprocessor.FULLWIDTH_CLASS), imgClasses(tree))
    }

    @Test
    fun `html width 属性跳过`() {
        val tree = preprocess("<html><body><p><img src='a.png' width='200'/></p></body></html>")
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `作者 css img width 跳过`() {
        val tree = preprocess(
            "<html><body><p><img src='a.png'/></p></body></html>",
            "img { width: 80% }",
        )
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `作者 css 类选择器 width 跳过`() {
        val tree = preprocess(
            "<html><body><p><img class='fig' src='a.png'/></p></body></html>",
            ".fig { width: 80% }",
        )
        assertEquals(listOf("fig"), imgClasses(tree))
    }

    @Test
    fun `作者 css max-width 跳过`() {
        val tree = preprocess(
            "<html><body><p><img src='a.png'/></p></body></html>",
            "p img { max-width: 100% }",
        )
        assertEquals(listOf(""), imgClasses(tree))
    }

    @Test
    fun `作者 css max-width none 视为未声明`() {
        val tree = preprocess(
            "<html><body><p><img src='a.png'/></p></body></html>",
            "img { max-width: none }",
        )
        assertEquals(listOf(ChapterPreprocessor.FULLWIDTH_CLASS), imgClasses(tree))
    }

    @Test
    fun `保留书籍自身类并追加全宽类`() {
        val tree = preprocess("<html><body><p><img class='fig cover' src='a.png'/></p></body></html>")
        val classes = imgClasses(tree).single()
        assertTrue(classes.startsWith("fig cover") && classes.endsWith(ChapterPreprocessor.FULLWIDTH_CLASS))
    }

    @Test
    fun `原始树不被修改`() {
        val root = converter.convert("<html><body><p><img src='a.png'/></p></body></html>")
        val original = root!!
        val tree = ChapterPreprocessor.preprocess(original, emptyList())
        // 原树 img 无类, 新树 img 有类
        assertEquals(listOf(""), imgClasses(original))
        assertEquals(listOf(ChapterPreprocessor.FULLWIDTH_CLASS), imgClasses(tree))
        assertTrue(original !== tree)
    }

    @Test
    fun `全宽类互不串节点的其它子树`() {
        val tree = preprocess(
            "<html><body>"
                + "<p><img src='a.png'/></p>"
                + "<p>正文</p>"
                + "<p><img src='b.png' style='width:200px'/></p>"
                + "</body></html>",
        )
        val imgs = collectImgs(tree)
        assertEquals(2, imgs.size)
        assertEquals(ChapterPreprocessor.FULLWIDTH_CLASS, imgs[0].attrs["class"])
        assertEquals("", imgs[1].attrs["class"].orEmpty())
    }

    @Test
    fun `重建树的 parent 指针一致`() {
        val tree = preprocess(
            "<html><body>"
                + "<p><img src='a.png'/></p>"
                + "<div><p>文字<img src='b.png'/></p></div>"
                + "</body></html>",
        )
        val root = tree
        assertTrue(parentChainValid(root))
        // 每个节点向上走到根都成立
        var count = 0
        fun walk(el: MarkupElement) {
            for (c in el.children) {
                assertTrue(parentChainValid(c))
                count++
                walk(c)
            }
        }
        walk(root)
        assertTrue(count > 0)
    }

    @Test
    fun `isNoneOrAuto 识别无约束关键字`() {
        assertTrue(ChapterPreprocessor.isNoneOrAuto("none"))
        assertTrue(ChapterPreprocessor.isNoneOrAuto("auto"))
        assertTrue(ChapterPreprocessor.isNoneOrAuto("initial"))
        assertTrue(ChapterPreprocessor.isNoneOrAuto("inherit"))
        assertTrue(ChapterPreprocessor.isNoneOrAuto("  NONE  "))
    }

    @Test
    fun `作者 css 不设尺寸时不拦截`() {
        val tree = preprocess(
            "<html><body><p><img class='fig' src='a.png'/></p></body></html>",
            ".fig { border: 1px solid; margin: 0 auto }",
        )
        assertTrue(imgClasses(tree).single().contains(ChapterPreprocessor.FULLWIDTH_CLASS))
    }

    // ---- Kotlin in Action（图 2.1 及其它图）的真实结构：`<p class="fm-figure"><img/><br/></p>`
    // `<br>` 是排版产生的占位换行，不是有内容的兄弟节点，不应阻止图片被认定为独图。

    @Test
    fun `独图段落内 img 后跟 br 仍打类`() {
        val tree = preprocess("<html><body><p class='fm-figure'><img class='calibre3' src='a.png'/><br/></p></body></html>")
        assertTrue(imgClasses(tree).single().contains(ChapterPreprocessor.FULLWIDTH_CLASS))
    }

    @Test
    fun `作者 css width auto 视为未声明`() {
        val tree = preprocess(
            "<html><body><p><img class='calibre3' src='a.png'/></p></body></html>",
            ".calibre3 { height: auto; width: auto; }",
        )
        assertTrue(imgClasses(tree).single().contains(ChapterPreprocessor.FULLWIDTH_CLASS))
    }

    @Test
    fun `kotlin in action 图二一 真实结构获得全宽类`() {
        val tree = preprocess(
            "<html><body><p class='fm-figure'><img alt='CH02_F01_Isakova' class='calibre3' src='../Images/CH02_F01_Isakova.png'/><br class='calibre6'/></p></body></html>",
            ".calibre3 { height: auto; width: auto; } .calibre6 { display: block; } .fm-figure { display: block; margin: 1.2em 0 0.8em; }",
        )
        assertTrue(imgClasses(tree).single().contains(ChapterPreprocessor.FULLWIDTH_CLASS))
    }
}