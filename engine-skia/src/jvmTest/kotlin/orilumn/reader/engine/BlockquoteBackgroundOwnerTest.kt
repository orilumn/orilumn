package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 内核层回归：`blockquote > h2 + p`（Rust 程序设计语言 3.1，`h2` 带
 * `border-bottom:1px`、`blockquote` 带半透明背景）——轻量路径的背景属主必须是
 * `blockquote`，只带边框的 `h2` 不得劫持属主，否则 `h2` 行区没有引用块底色。
 */
class BlockquoteBackgroundOwnerTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()

    private fun chapterHtml(): String = """
        <!DOCTYPE html><html><head></head><body>
        <blockquote><h2>3.1 关键字</h2><p>正文段落。</p></blockquote>
        <h2>3.2 下一节</h2>
        </body></html>
    """.trimIndent()

    private fun chapterCss(): String =
        "blockquote{margin:0.5rem 0;padding:0.5rem;background:rgba(235,255,255,0.3);}" +
            "h2{margin-top:2rem;margin-bottom:0.5rem;padding-bottom:0.25rem;border-bottom:1px solid #ccc;}" +
            "p{margin-top:0;margin-bottom:0.3rem;}"

    private fun find(root: MarkupElement, tag: String, nth: Int = 0): MarkupElement {
        val acc = ArrayList<MarkupElement>()
        fun walk(n: MarkupElement) {
            if (n.tag == tag) acc.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        return acc[nth]
    }

    @Test
    fun `border-only h2 inside blockquote is owned by the blockquote background`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(useOriginalStyle = true))
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))
        val structure = ChapterStructureCache()
        layouter.prepareLight(root, bundle, profile, 720, structure, 1280)

        val bq = find(root, "blockquote")
        val h2in = find(root, "h2", 0)
        val pin = find(root, "p", 0)
        assertSame("h2 背景属主必须是 blockquote", bq, structure.leafToBackgroundOwner[h2in])
        assertSame("p 背景属主必须是 blockquote", bq, structure.leafToBackgroundOwner[pin])
    }
}
