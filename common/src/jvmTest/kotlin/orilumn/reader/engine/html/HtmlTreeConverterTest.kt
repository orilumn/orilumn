package orilumn.reader.engine.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTreeConverterTest {

    private val converter = HtmlTreeConverter()

    @Test
    fun `简单段落与行内格式`() {
        val root = converter.convert(
            "<html><body><p>第一段 <strong>加粗</strong> 文字</p></body></html>",
        )
        assertNotNull(root)
        val p = root!!.children.first()
        assertEquals("p", p.tag)
        // strong child node
        val strong = p.children.first { it.tag == "strong" }
        assertEquals("加粗", strong.children.first { it.isText }.text)
    }

    @Test
    fun `块级标签保留 style`() {
        val root = converter.convert("<p style='font-size:18px'>有样式段落</p>")
        val p = root!!.children.first()
        assertEquals("font-size:18px", p.attrs["style"])
    }

    @Test
    fun `注释和非白名单标签脱壳`() {
        val root = converter.convert(
            "<p>a<custom-tag style='color:red'>b</custom-tag>c</p>",
        )
        // custom-tag is "unwrapped" into an empty-tag container; its text passes through
        val text = root!!.children.first().children.joinToString("") { n ->
            if (n.isText) n.text else n.children.joinToString("") { it.text }
        }
        assertTrue(text.contains("a") && text.contains("b") && text.contains("c"))
    }

    @Test
    fun `br 与文本汇总 textLength`() {
        val root = converter.convert("<p>甲<br/>乙</p>")
        val p = root!!.children.first()
        // glyph jia(甲)=1 + br=1 + glyph yi(乙)=1 = 3
        assertEquals(3L, p.textLength)
    }

    @Test
    fun `表格保留嵌套表结构与 colspan-rowspan`() {
        // P2-C: tables are no longer flattened to <br>-joined text; they keep a real table>row>cell
        // tree so the box layer can build a 2D grid, and cells retain colspan/rowspan.
        val root = converter.convert(
            "<table><thead><tr><th>头A</th><th>头B</th></tr></thead>" +
                "<tbody><tr><td colspan='2'>合并</td></tr>" +
                "<tr><td rowspan='2'>跨行</td><td>格</td></tr></tbody></table>",
        )!!
        val table = root!!.children.first()
        assertEquals("table", table.tag)
        val thead = table.children.first { it.tag == "thead" }
        assertEquals(listOf("th", "th"), thead.children.first { it.tag == "tr" }.children.map { it.tag })
        val tbody = table.children.first { it.tag == "tbody" }
        val colspanTr = tbody.children[0]
        assertEquals("2", colspanTr.children.first { it.tag == "td" }.attrs["colspan"])
        val rowSpanTr = tbody.children[1]
        val rowspanCell = rowSpanTr.children.first { it.tag == "td" }
        assertEquals("2", rowspanCell.attrs["rowspan"])
        assertEquals("跨行", rowspanCell.children.first { it.isText }.text)
    }

    @Test
    fun `头部 style 与 script 内容被丢弃`() {
        val root = converter.convert(
            "<html><head><style>@page{padding:0}</style></head>" +
                "<body><p>正文段落</p></body></html>",
        )
        val bodyText = root!!.children.joinToString("") { n ->
            when {
                n.isText -> n.text
                n.tag == "p" -> n.children.joinToString("") { it.text }
                else -> ""
            }
        }
        // Only the body remains, with no CSS source
        assertEquals("正文段落", bodyText)
        assertTrue(!bodyText.contains("@page"))
    }

    @Test
    fun `畸形 XML 通过 jsoup 容错返回树`() {
        // jsoup HTML5 fault-tolerant parser repairs unclosed tags; we never return null now
        val out = converter.convert("<p>未闭合")
        assertNotNull(out)
        assertTrue(out!!.children.isNotEmpty())
        assertEquals("p", out.children.first().tag)
    }

    @Test
    fun `文本与行内标签保持原始交错顺序`() {
        // Regression: interleaved text and inline elements must keep their DOM order, otherwise
        // English/inline runs get pushed to wrong positions in mixed Chinese-English paragraphs.
        val root = converter.convert("<p>中文AAA <b>bold</b> 英文BBB <i>italic</i> 结尾</p>")
        val p = root!!.children.first()
        val joined = p.children.joinToString("|") { n ->
            if (n.isText) n.text else "<${n.tag}>${n.children.joinToString("") { it.text }}</${n.tag}>"
        }
        assertEquals("中文AAA |<b>bold</b>| 英文BBB |<i>italic</i>| 结尾", joined)
    }

    @Test
    fun `空串与纯文本`() {
        // jsoup produces an empty body for blank input — never null
        val blank = converter.convert("  ")
        assertNotNull(blank)
        assertTrue(blank!!.children.isEmpty())
        val root = converter.convert("<p>纯文本</p>")
        assertEquals("纯文本", root!!.children.first().children.first().text)
    }

    @Test
    fun `convertWithStyles 保留 style 与 stylesheet 链接`() {
        val chapter = converter.convertWithStyles(
            "<html><head>" +
                "<link rel='stylesheet' type='text/css' href='style/base.css'/>" +
                "<link href='icon.png' rel='icon'/>" + // non-stylesheet → ignored
                "<style>body { margin: 0 }</style>" +
                "</head>" +
                "<body><p>正文</p></body></html>",
        )
        assertNotNull(chapter)
        assertEquals(listOf("style/base.css"), chapter!!.linkHrefs)
        assertEquals(listOf("body { margin: 0 }"), chapter.styles)
        // body tree is unchanged from convert()'s output
        assertEquals("正文", chapter.tree.children.first().children.first().text)
    }

    @Test
    fun `convertWithStyles 无 css 源时为空列表`() {
        val chapter = converter.convertWithStyles("<p>纯正文</p>")
        assertNotNull(chapter)
        assertTrue(chapter!!.styles.isEmpty())
        assertTrue(chapter.linkHrefs.isEmpty())
    }

    @Test
    fun `convertWithStyles 畸形输入通过 jsoup 容错返回 ParsedChapter`() {
        val chapter = converter.convertWithStyles("<p>未闭合")
        assertNotNull(chapter)
        assertNotNull(chapter!!.tree)
    }

    @Test
    fun `P0-A box 块标签保留为语义节点而非脱壳`() {
        // Regression: article/aside/nav/dl/dt/dd/address/hr must survive parsing as block nodes so the
        // box layer sees them, instead of being de-shelled into bare text.
        val xhtml = "<article><p>art</p></article><aside><p>asd</p></aside><nav><p>nav</p></nav>" +
            "<dl><dt>术语</dt><dd>定义</dd></dl><address>地址</address><hr/>"
        val root = converter.convert(xhtml)!!
        val tags = root.children.map { it.tag }
        assertEquals("article", tags[0])
        assertEquals("aside", tags[1])
        assertEquals("nav", tags[2])
        assertEquals("dl", tags[3])
        assertEquals("address", tags[4])
        // A container block retains its semantic block children (dt/dd) instead of being de-shelled flat.
        assertEquals(listOf("dt", "dd"), root.children[3].children.map { it.tag })
    }

    @Test
    fun `P0-B a 保留 href`() {
        val root = converter.convert("<p>跳转<a href='chapter2.html#sec1'>这里</a>结束</p>")!!
        val a = root.children.first().children.first { it.tag == "a" }
        assertEquals("chapter2.html#sec1", a.attrs["href"])
    }

    @Test
    fun `P0-B img 保留 width height`() {
        val root = converter.convert("<img src='pic.png' width='120' height='80' alt='插图'/>")!!
        val img = root.children.first()
        assertEquals("120", img.attrs["width"])
        assertEquals("80", img.attrs["height"])
        assertEquals("pic.png", img.attrs["src"])
    }

    @Test
    fun `P0-B 全局语义属性保留 lang dir epub-type role aria data`() {
        val root = converter.convert(
            "<p lang='zh-CN' xml:lang='en' dir='rtl' epub:type='chapter' role='note' " +
                "aria-label='说明' data-index='7'>正文</p>",
        )!!
        val attrs = root.children.first().attrs
        // xml:lang wins over lang for the canonical key.
        assertEquals("en", attrs["lang"])
        assertEquals("rtl", attrs["dir"])
        assertEquals("chapter", attrs["epub:type"])
        assertEquals("note", attrs["role"])
        assertEquals("说明", attrs["aria-label"])
        assertEquals("7", attrs["data-index"])
    }

    @Test
    fun `P0-A EPUB2 附加行内标签保留语义节点`() {
        // P0 目标：任何 EPUB2/3 标签不得被 de-shell；s/del/ins/abbr/acronym/dfn/cite/var/mark/
        // time/data/bdi/bdo/wbr/ruby/rt/rp 全部以行内节点存续（q 属既有行内标签）。
        val xhtml = "<p>a<s>s</s><del>d</del><ins>i</ins><abbr title='x'>ab</abbr>" +
            "<acronym>ac</acronym><dfn>df</dfn><cite>ct</cite><var>v</var>" +
            "<mark>mk</mark><time>2024</time><data value='1'>dt</data>" +
            "<bdi>b</bdi><bdo>z</bdo><wbr/><ruby>漢<rp>(</rp><rt>kan</rt><rp>)</rp></ruby>" +
            "end</p>"
        val root = converter.convert(xhtml)!!
        val p = root.children.first { it.tag == "p" }
        val topLevel = p.children.filter { !it.isText }.map { it.tag }
        assertEquals(
            listOf("s", "del", "ins", "abbr", "acronym", "dfn", "cite", "var", "mark",
                "time", "data", "bdi", "bdo", "wbr", "ruby"),
            topLevel,
        )
        // ruby 的注音子节点（rp/rt）保留为 ruby 的语义后代。
        val ruby = p.children.first { it.tag == "ruby" }
        assertEquals(listOf("rp", "rt", "rp"), ruby.children.filter { !it.isText }.map { it.tag })
        // 语义属性跟随保留（abbr 的 title 在内联白名单）。
        assertEquals("x", p.children.first { it.tag == "abbr" }.attrs["title"])
    }

    @Test
    fun `P0-A EPUB3 块标签与表格列结构保留语义节点`() {
        val xhtml = "<main><hgroup><h1>组</h1><h2>副</h2></hgroup><details><summary>折</summary>" +
            "<p>内容</p></details></main>" +
            "<table><colgroup><col span='2' width='50'/></colgroup><tr><td>格</td></tr></table>"
        val root = converter.convert(xhtml)!!
        val main = root.children[0]
        assertEquals("main", main.tag)
        assertEquals(listOf("hgroup", "details"), main.children.map { it.tag })
        assertEquals(listOf("h1", "h2"), main.children[0].children.map { it.tag })
        val summary = main.children[1].children.first { it.tag == "summary" }
        assertEquals("summary", summary.tag)
        // colgroup/col 保留为列元数据（直接 tr 会被 Ksoup 收进 tbody）。
        val table = root.children[1]
        assertEquals(listOf("colgroup", "tbody"), table.children.map { it.tag })
        val col = table.children[0].children.first()
        assertEquals("col", col.tag)
        assertEquals("2", col.attrs["span"])
    }

    @Test
    fun `P0-B HTML4 表示型属性逐个保留`() {
        val blockquote = converter.convert("<blockquote cite='src.html'><p>引</p></blockquote>")!!.children[0]
        assertEquals("src.html", blockquote.attrs["cite"])
        val q = converter.convert("<p><q cite='q.html'>话</q></p>")!!.children[0].children.first { it.tag == "q" }
        assertEquals("q.html", q.attrs["cite"])
        val time = converter.convert("<p><time datetime='2024-01-01'>日</time></p>")!!.children[0].children.first { it.tag == "time" }
        assertEquals("2024-01-01", time.attrs["datetime"])
        val a = converter.convert("<a name='sec1' href='#sec1'>锚</a>")!!.children.first { it.tag == "a" }
        assertEquals("sec1", a.attrs["name"])
        assertEquals("#sec1", a.attrs["href"])
        val img = converter.convert("<img srcset='a.png 1x, b.png 2x' sizes='(min-width: 600px) 50vw, 100vw' border='2' src='a.png'/>")!!.children[0]
        assertEquals("a.png 1x, b.png 2x", img.attrs["srcset"])
        assertEquals("(min-width: 600px) 50vw, 100vw", img.attrs["sizes"])
        assertEquals("2", img.attrs["border"])
        val br = converter.convert("<p>x<br clear='all'/>y</p>")!!.children[0].children.first { it.tag == "br" }
        assertEquals("all", br.attrs["clear"])
    }

    @Test
    fun `P0-B 表格与单元格属性逐个保留`() {
        val xhtml = "<table border='1' summary='总表' cellpadding='4' cellspacing='2'>" +
            "<tbody><tr valign='top'><th scope='col' headers='h1'>头</th><td nowrap='true' valign='middle'>格</td></tr></tbody>" +
            "</table>"
        val root = converter.convert(xhtml)!!
        val table = root.children[0]
        assertEquals("1", table.attrs["border"])
        assertEquals("总表", table.attrs["summary"])
        assertEquals("4", table.attrs["cellpadding"])
        assertEquals("2", table.attrs["cellspacing"])
        val tr = table.children.first { it.tag == "tbody" }.children.first { it.tag == "tr" }
        assertEquals("top", tr.attrs["valign"])
        val th = tr.children.first { it.tag == "th" }
        assertEquals("col", th.attrs["scope"])
        assertEquals("h1", th.attrs["headers"])
        val td = tr.children.first { it.tag == "td" }
        assertEquals("true", td.attrs["nowrap"])
        assertEquals("middle", td.attrs["valign"])
    }

    @Test
    fun `根 body 的 class-id-bgcolor 保留进级联 style 不保留`() {
        // 浏览器标准行为：body.foo / body#id 选择器参与匹配，body id 为 fragment 目标。
        val root = converter.convert(
            "<html><body class='wrapper chapter level2' id='e.0001' bgcolor='#ffffff' " +
                "style='margin:0'><p>正文</p></body></html>",
        )!!
        assertEquals("body", root.tag)
        assertEquals("wrapper chapter level2", root.attrs["class"])
        assertEquals("e.0001", root.attrs["id"])
        assertEquals("#ffffff", root.attrs["bgcolor"])
        assertNull(root.attrs["style"])
    }

    @Test
    fun `P0-A li 保留 value 属性供 ListMarkers 改号`() {
        val ol = converter.convert("<ol><li>一</li><li value='5'>五</li><li>六</li></ol>")!!.children.first { it.tag == "ol" }
        val values = ol.children.filter { it.tag == "li" }.map { it.attrs["value"] }
        assertEquals(listOf(null, "5", null), values)
    }
}