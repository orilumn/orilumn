package orilumn.reader.engine.css

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-b 管线纯逻辑 guard：字体引用收集、`@import` 内联、IDPF 去混淆、魔数嗅探。
 */
class BookFontsTest {

    private fun sheetOf(css: String) = LightCssParser().parse(css)

    /** 测试用相对解析（读取壳语义简化版：`..` 折叠，空目录即相对本身）。 */
    private fun join(base: String, rel: String): String {
        val dir = base.substringBeforeLast('/', "")
        if (dir.isEmpty()) return rel
        var r = rel
        var d = dir
        while (r.startsWith("../")) {
            r = r.removePrefix("../")
            d = d.substringBeforeLast('/', "")
        }
        return if (d.isEmpty()) r else "$d/$r"
    }

    @Test
    fun `collectBookFonts 相对源解析并去重`() {
        val sheets = listOf(
            sheetOf("@font-face { font-family: F; src: url(fonts/a.ttf); } p { color: red }"),
            sheetOf("@font-face { font-family: F; src: url(../fonts/a.ttf); }"),
        )
        // 两份源不同基准但归一化到同一文件 → 去重剩一。
        fun resolve(base: String, rel: String): String {
            var d = base.substringBeforeLast('/', "")
            var r = rel
            while (r.startsWith("../")) {
                r = r.removePrefix("../")
                d = d.substringBeforeLast('/', "")
            }
            return if (d.isEmpty()) r else "$d/$r"
        }
        val refs = collectBookFonts(
            sheets,
            listOf("OEBPS/Text/ch.xhtml", "OEBPS/Text/sub/s.css"),
            "OEBPS/Text/ch.xhtml",
            ::resolve,
        )
        assertEquals(1, refs.size)
        assertEquals("F", refs[0].family)
        assertEquals("OEBPS/Text/fonts/a.ttf", refs[0].href)
    }

    @Test
    fun `外部与数据 URL 跳过`() {
        val sheets = listOf(
            sheetOf("@font-face { font-family: A; src: url(https://x/a.ttf); }"),
            sheetOf("@font-face { font-family: B; src: url(data:font/ttf;base64,AAA); }"),
            sheetOf("@font-face { font-family: C; src: url(c.exe); }"),
        )
        val refs = collectBookFonts(sheets, emptyList(), "ch.xhtml", ::join)
        assertTrue("外部/数据/未知扩展名全部跳过：$refs", refs.isEmpty())
    }

    @Test
    fun `resolveCssImports 递归内联防环限深`() {
        val files = mapOf(
            "s/main.css" to "@import \"a.css\"; @import \"b.css\"; p { color: red }",
            "s/a.css" to "@import \"b.css\"; .a { color: a }",
            "s/b.css" to ".b { color: b }",
            "s/self.css" to "@import \"self.css\"; .s { color: s }",
        )
        val out = resolveCssImports(
            listOf(files["s/main.css"]!!, files["s/self.css"]!!),
            listOf("s/main.css", "s/self.css"),
            "ch.xhtml",
            { b, r -> join(b, r) },
            { h -> files[h] },
            null,
        )
        val texts = out.map { it.second }
        // b 只内联一次（a 后的 b 去重），self 自环一次，导入恒在导入者之前
        //（输出文本保留 @import 原文，下游解析时跳过——此处不断言剥离）。
        assertEquals(
            listOf(files["s/b.css"], files["s/a.css"], files["s/main.css"], files["s/self.css"]),
            texts,
        )
        assertEquals(listOf("s/b.css", "s/a.css", "s/main.css", "s/self.css"), out.map { it.first })
    }

    @Test
    fun `resolveCssImports 媒体条件按视口`() {
        val files = mapOf(
            "m.css" to "@import \"w.css\" screen and (min-width: 500px); @import \"n.css\" screen and (min-width: 900px); p{}",
            "w.css" to ".w{}",
            "n.css" to ".n{}",
        )
        val wide = resolveCssImports(listOf(files["m.css"]!!), listOf("m.css"), "c", { b, r -> join(b, r) }, { h -> files[h] }, CssViewport(600, 800))
        // 命中 w，n 条件不满足；输出文本保留 @import 原文（下游跳过）。
        assertEquals(listOf("w.css", "m.css"), wide.map { it.first })
        assertEquals(files["w.css"], wide[0].second)
        val none = resolveCssImports(listOf(files["m.css"]!!), listOf("m.css"), "c", { b, r -> join(b, r) }, { h -> files[h] }, null)
        assertEquals(listOf("m.css"), none.map { it.first })
    }

    @Test
    fun `deobfuscateIdpf 往返且只动前1040`() {
        val uid = "urn:uuid:test-book-1"
        val raw = ByteArray(2000) { (it % 251).toByte() }
        val key = Buffer().write(uid.encodeToByteArray()).sha1().toByteArray()
        val obf = raw.copyOf()
        for (i in 0 until 1040) obf[i] = (obf[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
        val back = deobfuscateIdpf(obf, uid)
        assertTrue("round-trip restores bytes", back.contentEquals(raw))
        assertEquals("empty uid returns input", obf, deobfuscateIdpf(obf, ""))
    }

    @Test
    fun `sniffFontFormat 魔数`() {
        assertEquals("ttf", sniffFontFormat(byteArrayOf(0, 1, 0, 0, 0)))
        assertEquals("otf", sniffFontFormat("OTTO".encodeToByteArray()))
        assertEquals("woff", sniffFontFormat("wOFF".encodeToByteArray()))
        assertEquals("woff2", sniffFontFormat("wOF2".encodeToByteArray()))
        assertEquals("ttc", sniffFontFormat("ttcf".encodeToByteArray()))
        assertEquals("", sniffFontFormat("PK\u0003\u0004".encodeToByteArray()))
        assertEquals("", sniffFontFormat(byteArrayOf(1, 2)))
    }

    @Test
    fun `真字体往返去混淆后魔数 intact`() {
        // P2 验收（混淆例）：真实 woff2 按 UID 混淆再去混淆，魔数与字节全还原。
        val woff2 = javaClass.getResourceAsStream("/fonts/roboto-latin.woff2")!!.readBytes()
        assertEquals("woff2", sniffFontFormat(woff2))
        val uid = "urn:uuid:fixture-book"
        val key = Buffer().write(uid.encodeToByteArray()).sha1().toByteArray()
        val obf = woff2.copyOf()
        for (i in 0 until minOf(1040, obf.size)) {
            obf[i] = (obf[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        assertEquals("混淆后魔数被破坏", "", sniffFontFormat(obf))
        val back = deobfuscateIdpf(obf, uid)
        assertEquals("woff2", sniffFontFormat(back))
        assertTrue(back.contentEquals(woff2))
    }

    @Test
    fun `真字体全链路收集`() {
        // css → 解析 → 收集引用（相对基准），扩展名/格式门全开。
        val css = "@font-face { font-family: \"Roboto\"; src: url(\"../fonts/roboto-latin.woff2\") format(\"woff2\"); }"
        val sheets = listOf(LightCssParser().parse(css))
        val refs = collectBookFonts(sheets, listOf("OEBPS/Text/ch.xhtml"), "OEBPS/Text/ch.xhtml") { b, r ->
            b.substringBeforeLast('/', "") + "/" + r.removePrefix("../")
        }
        assertEquals(1, refs.size)
        assertEquals("Roboto", refs[0].family)
        assertTrue(refs[0].href.endsWith("roboto-latin.woff2"))
        assertEquals("woff2", refs[0].format)
    }
}
