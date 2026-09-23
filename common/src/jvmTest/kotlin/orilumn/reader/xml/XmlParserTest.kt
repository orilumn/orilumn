package orilumn.reader.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlParserTest {

    private val parser = XmlParser()

    // ---- basic structure ----

    @Test
    fun `根元素与 nodeName`() {
        val doc = parser.parse("<root/>")
        assertEquals("root", doc.documentElement.nodeName)
    }

    @Test
    fun `嵌套子元素`() {
        val doc = parser.parse("<root><child/></root>")
        val child = doc.documentElement.firstChild
        assertNotNull(child)
        assertTrue(child is XmlElement)
        assertEquals("child", (child as XmlElement).nodeName)
    }

    @Test
    fun `自关闭标签`() {
        val doc = parser.parse("<root><leaf/></root>")
        val leaf = doc.documentElement.firstChild as XmlElement
        assertTrue(leaf.children.isEmpty())
    }

    @Test
    fun `text 节点作为子节点`() {
        val doc = parser.parse("<root>hello</root>")
        val text = doc.documentElement.firstChild
        assertTrue(text is XmlTextNode)
        assertEquals("hello", (text as XmlTextNode).data)
    }

    // ---- attributes ----

    @Test
    fun `属性读取`() {
        val doc = parser.parse("""<root name="foo" id='bar'/>""")
        assertEquals("foo", doc.documentElement.getAttribute("name"))
        assertEquals("bar", doc.documentElement.getAttribute("id"))
    }

    @Test
    fun `缺失属性返回空字符串`() {
        val doc = parser.parse("<root/>")
        assertEquals("", doc.documentElement.getAttribute("missing"))
    }

    @Test
    fun `带命名空间前缀的属性名`() {
        val doc = parser.parse("""<root xmlns:dc="http://example.com" dc:title="T"/>""")
        assertEquals("T", doc.documentElement.getAttribute("dc:title"))
    }

    // ---- textContent ----

    @Test
    fun `textContent 拼接所有后代文本`() {
        val doc = parser.parse("<root><p>a<b>b</b>c</p></root>")
        val p = doc.documentElement.firstDescendant("p")!!
        assertEquals("abc", p.textContent)
    }

    @Test
    fun `textContent 拼接多层嵌套`() {
        val doc = parser.parse("<root><a>1<b>2</b></a><a>3</a></root>")
        assertEquals("123", doc.documentElement.textContent)
        assertEquals("12", doc.documentElement.elementChildren("a")[0].textContent)
        assertEquals("3", doc.documentElement.elementChildren("a")[1].textContent)
    }

    // ---- firstChild / nextSibling ----

    @Test
    fun `firstChild 与 nextSibling 链`() {
        val doc = parser.parse("<root><a/>text<b/></root>")
        val first = doc.documentElement.firstChild
        assertTrue(first is XmlElement)
        assertEquals("a", (first as XmlElement).nodeName)

        val second = first.nextSibling
        assertTrue(second is XmlTextNode)
        assertEquals("text", (second as XmlTextNode).data)

        val third = second!!.nextSibling
        assertTrue(third is XmlElement)
        assertEquals("b", (third as XmlElement).nodeName)

        assertNull(third.nextSibling)
    }

    // ---- entity resolution ----

    @Test
    fun `内置 XML 实体解析`() {
        val doc = parser.parse("""<root>&amp;&lt;&gt;&apos;&quot;</root>""")
        assertEquals("&<>'\"", doc.documentElement.textContent)
    }

    @Test
    fun `十进制数字字符引用`() {
        val doc = parser.parse("<root>&#65;&#66;</root>")
        assertEquals("AB", doc.documentElement.textContent)
    }

    @Test
    fun `十六进制数字字符引用`() {
        val doc = parser.parse("<root>&#x41;&#x42;</root>")
        assertEquals("AB", doc.documentElement.textContent)
    }

    @Test
    fun `属性中的实体解析`() {
        val doc = parser.parse("""<root val="a&amp;b"/>""")
        assertEquals("a&b", doc.documentElement.getAttribute("val"))
    }

    @Test
    fun `未知实体保留原文`() {
        val doc = parser.parse("<root>&unknown;</root>")
        assertEquals("&unknown;", doc.documentElement.textContent)
    }

    // ---- getElementsByTagNameNS ----

    @Test
    fun `通配符命名空间搜索`() {
        val xml = """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val doc = parser.parse(xml)
        val results = doc.getElementsByTagNameNS("*", "rootfile")
        assertEquals(1, results.size)
        assertEquals("OEBPS/content.opf", results[0].getAttribute("full-path"))
    }

    @Test
    fun `elementChildren 与 firstDescendant`() {
        val xml = """
            <package>
              <metadata>
                <title>Book</title>
                <creator>Author</creator>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.xhtml"/>
              </manifest>
            </package>
        """.trimIndent()
        val doc = parser.parse(xml)
        val pkg = doc.documentElement
        // elementChildren
        val metadata = pkg.elementChildren("metadata")
        assertEquals(1, metadata.size)
        val title = metadata[0].elementChildren("title")
        assertEquals("Book", title[0].textContent)
        // firstDescendant
        val creator = pkg.firstDescendant("creator")!!
        assertEquals("Author", creator.textContent)
    }

    // ---- comments / CDATA / PI / DOCTYPE ----

    @Test
    fun `注释被忽略`() {
        val doc = parser.parse("<root><!-- comment --><child/></root>")
        assertEquals("child", (doc.documentElement.firstChild as XmlElement).nodeName)
    }

    @Test
    fun `CDATA 内容作为文本节点`() {
        val doc = parser.parse("<root><![CDATA[hello world]]></root>")
        assertEquals("hello world", doc.documentElement.textContent)
    }

    @Test
    fun `XML 声明与处理指令被忽略`() {
        val doc = parser.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?target data?>\n<root/>")
        assertEquals("root", doc.documentElement.nodeName)
    }

    @Test
    fun `DOCTYPE 被忽略`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://example.com/dtd"><root/>"""
        val doc = parser.parse(xml)
        assertEquals("root", doc.documentElement.nodeName)
    }

    @Test
    fun `DOCTYPE 带内部子集被忽略`() {
        val xml = """<!DOCTYPE root [<!ENTITY foo "bar">]><root>&foo;</root>"""
        val doc = parser.parse(xml)
        // Entity declared in DTD should NOT be resolved (XXE protection)
        assertEquals("&foo;", doc.documentElement.textContent)
    }

    // ---- BOM ----

    @Test
    fun `BOM 被跳过`() {
        val doc = parser.parse("\uFEFF<root/>")
        assertEquals("root", doc.documentElement.nodeName)
    }

    // ---- EPUB integration: container.xml ----

    @Test
    fun `container xml 结构解析`() {
        val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        val doc = parser.parse(container)
        val rootfile = doc.getElementsByTagNameNS("*", "rootfile").first()
        assertEquals("OEBPS/content.opf", rootfile.getAttribute("full-path"))
        assertEquals("application/oebps-package+xml", rootfile.getAttribute("media-type"))
    }

    @Test
    fun `OPF metadata 结构`() {
        val opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:1234</dc:identifier>
    <dc:title>测试书名</dc:title>
    <dc:creator>测试作者</dc:creator>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>"""
        val doc = parser.parse(opf)
        val metadata = doc.documentElement.elementChildren("metadata").first()
        assertEquals("测试书名", metadata.firstDescendant("title")?.textContent)
        assertEquals("测试作者", metadata.firstDescendant("creator")?.textContent)
    }

    @Test
    fun `NCX navMap 结构`() {
        val ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="np1">
      <navLabel><text>第一章</text></navLabel>
      <content src="ch1.html"/>
    </navPoint>
    <navPoint id="np2">
      <navLabel><text>第二章</text></navLabel>
      <content src="ch2.html#sec"/>
    </navPoint>
  </navMap>
</ncx>"""
        val doc = parser.parse(ncx)
        val navPoints = doc.documentElement
            .firstDescendant("navMap")!!
            .elementChildren("navPoint")
        assertEquals(2, navPoints.size)
        assertEquals("第一章", navPoints[0].firstDescendant("text")?.textContent)
        assertEquals("ch1.html", navPoints[0].firstDescendant("content")?.getAttribute("src"))
        assertEquals("ch2.html#sec", navPoints[1].firstDescendant("content")?.getAttribute("src"))
    }

    // ---- error cases ----

    @Test(expected = XmlFormatException::class)
    fun `空文档抛异常`() {
        parser.parse("")
    }

    @Test(expected = XmlFormatException::class)
    fun `未闭合标签抛异常`() {
        parser.parse("<root>")
    }

    @Test(expected = XmlFormatException::class)
    fun `不匹配闭合标签抛异常`() {
        parser.parse("<root></foo>")
    }
}
