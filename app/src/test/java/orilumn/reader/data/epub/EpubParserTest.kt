package orilumn.reader.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private val parser = EpubParser()

    private fun str(s: String) = s.toByteArray()

    // Build a canonical EPUB3: two chapters + nav + cover, with the OPF under OEBPS/
    private fun epub3Files(): Map<String, ByteArray> {
        val container = "<?xml version=\"1.0\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"OEBPS/package.opf\" media-type=\"application/oebps-package+xml\"/>" +
            "</rootfiles></container>"
        val opf = "<?xml version=\"1.0\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:identifier id=\"bookid\">urn:uuid:1234</dc:identifier>" +
            "<dc:title>测试书名</dc:title><dc:creator>测试作者</dc:creator></metadata>" +
            "<manifest>" +
            "<item id=\"ch1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"ch2\" href=\"text/ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
            "<item id=\"cover\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/>" +
            "</manifest>" +
            "<spine><itemref idref=\"ch1\"/><itemref idref=\"ch2\"/></spine>" +
            "</package>"
        val nav = "<?xml version=\"1.0\"?>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>" +
            "<nav epub:type=\"toc\"><ol>" +
            "<li><a href=\"text/ch1.xhtml\">第一章</a></li>" +
            "<li><a href=\"text/ch2.xhtml#s2\">第二章</a>" +
            "<ol><li><a href=\"text/ch2.xhtml#s2-1\">2.1 小节</a></li></ol></li>" +
            "</ol></nav></body></html>"
        return mapOf(
            "META-INF/container.xml" to str(container),
            "OEBPS/package.opf" to str(opf),
            "OEBPS/nav.xhtml" to str(nav),
            "OEBPS/text/ch1.xhtml" to str("<!DOCTYPE html><html><body>第一章正文</body></html>"),
            "OEBPS/text/ch2.xhtml" to str("<!DOCTYPE html><html><body>第二章正文</body></html>"),
            "OEBPS/images/cover.jpg" to ByteArray(4),
        )
    }

    @Test
    fun `解析书名与作者`() {
        val book = parser.parse(FakeEpubResourceReader(epub3Files()))
        assertEquals("测试书名", book.title)
        assertEquals("测试作者", book.author)
    }

    @Test
    fun `解析出版标识与混淆字体`() {
        val files = epub3Files().toMutableMap()
        files["META-INF/encryption.xml"] = str(
            "<?xml version=\"1.0\"?>\n" +
                "<encryption xmlns=\"http://www.w3.org/2001/04/xmlenc#\">" +
                "<EncryptedData><EncryptionMethod Algorithm=\"http://www.idpf.org/2008/embedding\"/>" +
                "<CipherData><CipherReference URI=\"fonts/a.otf\"/></CipherData></EncryptedData>" +
                "<EncryptedData><EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/>" +
                "<CipherData><CipherReference URI=\"fonts/b.otf\"/></CipherData></EncryptedData>" +
                "</encryption>",
        )
        val book = parser.parse(FakeEpubResourceReader(files))
        // unique-identifier=bookid → dc:identifier 文本去空白。
        assertEquals("urn:uuid:1234", book.uid)
        // 仅 IDPF 嵌入算法条目计入（AES 条目是 DRM，不碰）。
        assertEquals(setOf("OEBPS/fonts/a.otf"), book.obfuscatedFonts)
    }

    @Test
    fun `无加密文件即空集`() {
        val book = parser.parse(FakeEpubResourceReader(epub3Files()))
        assertEquals("urn:uuid:1234", book.uid)
        assertTrue(book.obfuscatedFonts.isEmpty())
    }

    @Test
    fun `解析 spine 章节与 href`() {
        val book = parser.parse(FakeEpubResourceReader(epub3Files()))
        assertEquals(2, book.spine.size)
        assertEquals("OEBPS/text/ch1.xhtml", book.spine[0].href)
        assertEquals(0, book.spine[0].index)
        assertEquals("OEBPS/text/ch2.xhtml", book.spine[1].href)
        // href in the spine has no fragment
        assertNull(book.spine[0].fragment)
    }

    @Test
    fun `nav 目录关联到章节索引`() {
        val book = parser.parse(FakeEpubResourceReader(epub3Files()))
        assertEquals(2, book.toc.size)
        assertEquals("第一章", book.toc[0].label)
        assertEquals(0, book.toc[0].index)
        val second = book.toc[1]
        assertEquals("第二章", second.label)
        assertEquals(1, second.index)
        assertEquals("s2", second.fragment)
        // nested sub-directory
        assertEquals(1, second.children.size)
        assertEquals("2.1 小节", second.children[0].label)
        assertEquals(1, second.children[0].index)
    }

    @Test
    fun `脏书路径大小写漂移仍可解析`() {
        // container's full-path uses uppercase and entry names mix case too
        val files = epub3Files().toMutableMap().apply {
            remove("META-INF/container.xml")
            put("META-INF/Container.Xml", str("<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/PACKAGE.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"))
            putAll(mapOf(
                "OEBPS/TEXT/CH1.xhtml" to str("<!DOCTYPE html><html><body>x</body></html>"),
                "OEBPS/TEXT/CH2.xhtml" to str("<!DOCTYPE html><html><body>y</body></html>"),
            ))
        }
        files.remove("OEBPS/text/ch1.xhtml")
        files.remove("OEBPS/text/ch2.xhtml")
        val book = parser.parse(FakeEpubResourceReader(files))
        assertEquals("测试书名", book.title)
        assertEquals(2, book.spine.size)
        // spine.href preserves the manifest's original casing
        assertEquals("OEBPS/text/ch1.xhtml", book.spine[0].href)
    }

    @Test
    fun `EPUB2 NCX 兜底解析目录`() {
        val container = "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title></metadata>" +
            "<manifest>" +
            "<item id=\"c1\" href=\"c1.html\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"c2\" href=\"c2.html\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
            "</manifest>" +
            "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>"
        val ncx = "<?xml version=\"1.0\"?><!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\">" +
            "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><navMap>" +
            "<navPoint id=\"np1\" playOrder=\"1\"><navLabel><text>第一章</text></navLabel><content src=\"c1.html\"/></navPoint>" +
            "<navPoint id=\"np2\" playOrder=\"2\"><navLabel><text>第二章</text></navLabel><content src=\"c2.html#sec\"/></navPoint>" +
            "</navMap></ncx>"
        val files = mapOf(
            "META-INF/container.xml" to str(container),
            "package.opf" to str(opf),
            "toc.ncx" to str(ncx),
            "c1.html" to str("<html><body>x</body></html>"),
            "c2.html" to str("<html><body>y</body></html>"),
        )
        val book = parser.parse(FakeEpubResourceReader(files))
        assertEquals(2, book.toc.size)
        assertEquals("第一章", book.toc[0].label)
        assertEquals(0, book.toc[0].index)
        assertEquals(1, book.toc[1].index)
        assertEquals("sec", book.toc[1].fragment)
    }

    @Test
    fun `缺失 container 抛格式异常`() {
        try {
            parser.parse(FakeEpubResourceReader(emptyMap()))
            fail("应抛出 EpubFormatException")
        } catch (expected: EpubFormatException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `空 spine 抛格式异常`() {
        val container = "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title></metadata><manifest></manifest><spine></spine></package>"
        val files = mapOf(
            "META-INF/container.xml" to str(container),
            "package.opf" to str(opf),
        )
        try {
            parser.parse(FakeEpubResourceReader(files))
            fail("应抛出 EpubFormatException")
        } catch (expected: EpubFormatException) {
            assertTrue(expected.message.orEmpty().contains("spine"))
        }
    }

    @Test
    fun `真实 zip 的 META-INF 大写条目可解析`() = tempZipFile { zip ->
        val files = epub3Files() // keys are `META-INF/container.xml` (uppercase prefix)
        for ((name, bytes) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }.let { temp ->
        try {
            val book = parser.parse(ZipEpubResourceReader(temp.path))
            assertEquals("测试书名", book.title)
            assertEquals(2, book.spine.size)
            assertEquals("OEBPS/text/ch1.xhtml", book.spine[0].href)
        } finally {
            temp.delete()
        }
    }

    /** Creates a temporary zip file and returns it; the zip stream is filled via [build]. */
    private fun tempZipFile(build: (ZipOutputStream) -> Unit): File {
        val file = File.createTempFile("epub-test", ".epub")
        ZipOutputStream(file.outputStream()).use(build)
        return file
    }
}