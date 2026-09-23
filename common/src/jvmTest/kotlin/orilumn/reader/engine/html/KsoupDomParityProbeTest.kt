package orilumn.reader.engine.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10 前置验证（fleeksoft/ksoup vs jsoup DOM 对等性探测）。
 *
 * 逐条镜像 [HtmlTreeConverter] 用到的 jsoup 访问模式，用 fleeksoft Ksoup 复现并断言语义等价：
 * parse / body / children / childNodes（Element 与 TextNode 交错）/ select / attr / hasAttr /
 * attributes 迭代 / attr("xml:lang") 等带冒号属性 / wholeText（空格保留）/ Element.data() /
 * 实体解码 / 未闭合标签修复。全部跑绿即证明 S11 仅需换 import + `Jsoup`→`Ksoup` + 少量方法名调整。
 *
 * 0.2.0 API 与 jsoup 的已知差异（本探测钉死）：
 * - `TextNode` 取全文本是函数 `getWholeText()`，不是属性 `wholeText`；`Element` 是方法 `wholeText()`。
 * - `Elements.first()` 为空时返回 null（同 jsoup），需显式处理。
 */
class KsoupDomParityProbeTest {

    @Test
    fun parseAndBodyRoot() {
        val doc = Ksoup.parse("<html><head><title>t</title></head><body><p>甲<br/>乙</p></body></html>")
        val body = doc.body()
        val ps = body.children()
        assertEquals(1, ps.size)
        val p = ps[0]
        assertEquals("p", p.tagName().lowercase())
        // 镜像 childNodesInOrder：Element 与 TextNode 交错保留
        val childNodes = p.childNodes()
        assertEquals(3, childNodes.size)
        assertTrue(childNodes[0] is TextNode)
        assertEquals("甲", (childNodes[0] as TextNode).getWholeText())
        assertTrue(childNodes[1] is Element)
        assertEquals("br", (childNodes[1] as Element).tagName().lowercase())
        assertTrue(childNodes[2] is TextNode)
        assertEquals("乙", (childNodes[2] as TextNode).getWholeText())
    }

    @Test
    fun headStyleExcludedFromBody() {
        val doc = Ksoup.parse("<head><style>@page{margin:0}</style><script>x</script></head><body><p>正文</p></body>")
        val bodyTexts = doc.body().wholeText().trim()
        assertFalse(bodyTexts.contains("@page"))
        assertFalse(bodyTexts.contains("x"))
        val p = doc.body().children().firstOrNull { it.tagName().lowercase() == "p" }
        assertEquals("正文", p?.wholeText()?.trim())
    }

    @Test
    fun selectStyleMirrorsCollectCssSources() {
        val doc = Ksoup.parse("<style>@page { margin: 0 }</style><style>   </style><body>t</body>")
        val blocks = doc.select("style").mapNotNull { it.data().trim().takeIf { s -> s.isNotEmpty() } }
        assertEquals(listOf("@page { margin: 0 }"), blocks)
    }

    @Test
    fun selectLinkRelStylesheetMirrorsCollectCssSources() {
        val doc = Ksoup.parse("<head><link rel='stylesheet' href='a.css'/><link rel='icon' href='f.ico'/><link rel='stylesheet' href='b.css'/></head>")
        val hrefs = doc.select("link[rel~=stylesheet]").mapNotNull { it.attr("href").trim().takeIf { h -> h.isNotEmpty() } }
        assertEquals(listOf("a.css", "b.css"), hrefs)
    }

    @Test
    fun entityDecodingAndBareAmpersandRepair() {
        val doc = Ksoup.parse("<p>Fish &amp; Chips & Chips</p>")
        val p = doc.body().childNodes()[0] as Element
        val runs = p.childNodes()
        val t = (runs[0] as TextNode).getWholeText()
        assertEquals("Fish & Chips & Chips", t)
    }

    @Test
    fun unclosedTagRepairedByHtml5TreeConstruction() {
        val doc = Ksoup.parse("<p>未闭合标签")
        val p = doc.body().children().firstOrNull { it.tagName().lowercase() == "p" }
        assertEquals("未闭合标签", p?.wholeText()?.trim())
    }

    @Test
    fun wholeTextPreservesWhitespaceVsTextFolds() {
        val doc = Ksoup.parse("<p>a <strong>b</strong>  c</p>")
        val childNodes = doc.body().childNodes()
        val p = childNodes[0] as Element
        val runs = p.childNodes()
        assertEquals("a ", (runs[0] as TextNode).getWholeText())
        assertEquals("  c", (runs[2] as TextNode).getWholeText())
        // Element.wholeText() 与 childNodes 交错游走得到相同的含空格全文
        var joined = ""
        for (n in p.childNodes()) {
            joined += when (n) {
                is TextNode -> n.getWholeText()
                is Element -> n.wholeText()
                else -> ""
            }
        }
        assertEquals(p.wholeText(), joined)
    }

    @Test
    fun booleanAttrHasAttrAndMissingAttrEmpty() {
        val doc = Ksoup.parse("<ol reversed start='3'></ol><p></p>")
        val ol = doc.body().children().first { it.tagName().lowercase() == "ol" }
        assertTrue(ol.hasAttr("reversed"))
        assertEquals("3", ol.attr("start"))
        assertEquals("", ol.attr("reversed"))
        val p = doc.body().children().first { it.tagName().lowercase() == "p" }
        assertFalse(p.hasAttr("reversed"))
        assertEquals("", p.attr("start"))
    }

    @Test
    fun attributesIterationCollectsAriaAndDataPrefixed() {
        val doc = Ksoup.parse("<span data-x='1' aria-label='y' class='c'>t</span>")
        val span = doc.body().children().firstOrNull()!!
        val out = HashMap<String, String>()
        for (a in span.attributes()) {
            val k = a.key
            if (k.startsWith("aria-") || k.startsWith("data-")) out[k] = a.value
        }
        assertEquals(mapOf("data-x" to "1", "aria-label" to "y"), out)
    }

    @Test
    fun colonAttributesXmlLangEpubTypeAndDir() {
        val doc = Ksoup.parse("<p xml:lang='zh' lang='en' dir='rtl' epub:type='chapter' role='doc-chapter'>t</p>")
        val p = doc.body().children().firstOrNull()!!
        assertEquals("zh", p.attr("xml:lang"))
        assertEquals("en", p.attr("lang"))
        assertEquals("rtl", p.attr("dir"))
        assertEquals("chapter", p.attr("epub:type"))
        assertEquals("doc-chapter", p.attr("role"))
    }

    @Test
    fun commentAndDataNodesAreSkippable() {
        val doc = Ksoup.parse("<p>a<!--c-->b</p>")
        val p = doc.body().childNodes()[0] as Element
        val runs = p.childNodes()
        assertEquals("a", (runs[0] as TextNode).getWholeText())
        assertEquals("b", (runs[runs.size - 1] as TextNode).getWholeText())
    }
}