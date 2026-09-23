package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-b 验收（内嵌/混淆字体例）：含 `@font-face` 章节经控制器装载出可入池字节
 * （相对源 href 解析 → zip 取字节 → IDPF 去混淆 → 魔数校验）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookFontsPipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fontBytes(): ByteArray =
        javaClass.getResourceAsStream("/fonts/roboto-latin.woff2")!!.readBytes()

    private fun files(obfuscate: Boolean): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Fonts</dc:title><dc:identifier id="bookid">urn:uuid:font-book</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="f1" href="Fonts/r.woff2" media-type="font/woff2"/>
                </manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        val css = "@font-face { font-family: \"Roboto\"; src: url(\"../Fonts/r.woff2\") format(\"woff2\"); }" +
            " p { font-family: \"Roboto\", sans-serif; }"
        val ch = "<html><body><style>$css</style><p>Hello world</p></body></html>"
        val out = mutableMapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ch.toByteArray(),
        )
        val raw = fontBytes()
        if (obfuscate) {
            out["META-INF/encryption.xml"] = (
                "<?xml version=\"1.0\"?>" +
                    "<encryption xmlns=\"http://www.w3.org/2001/04/xmlenc#\">" +
                    "<EncryptedData><EncryptionMethod Algorithm=\"http://www.idpf.org/2008/embedding\"/>" +
                    "<CipherData><CipherReference URI=\"Fonts/r.woff2\"/></CipherData></EncryptedData>" +
                    "</encryption>"
                ).toByteArray()
            // 混淆即异或（对称）：用去混淆函数反向加密。
            out["OEBPS/Fonts/r.woff2"] = orilumn.reader.engine.css.deobfuscateIdpf(raw, "urn:uuid:font-book")
        } else {
            out["OEBPS/Fonts/r.woff2"] = raw
        }
        return out
    }

    private fun controller(obfuscate: Boolean): BookDocumentController {
        val c = BookDocumentController(
            reader = FakeEpubResourceReader(files(obfuscate)),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        c.cacheRoot = temp.newFolder().absolutePath.toPath()
        return c
    }

    @Test
    fun `内嵌字体装载出可入池字节`() = runBlocking {
        val c = controller(false)
        assertTrue(c.open(42L, saved = null))
        c.setViewport(720, 1280)
        c.ensureChapterLayout(0, 0) ?: error("no ch0")
        val fonts = c.bookFontsFor(0)
        assertEquals(1, fonts.size)
        assertEquals("Roboto", fonts[0].family)
        assertEquals("woff2", orilumn.reader.engine.css.sniffFontFormat(fonts[0].bytes))
        assertTrue(fonts[0].bytes.contentEquals(fontBytes()))
    }

    @Test
    fun `混淆字体去混淆后可用`() = runBlocking {
        val c = controller(true)
        assertTrue(c.open(43L, saved = null))
        c.setViewport(720, 1280)
        c.ensureChapterLayout(0, 0) ?: error("no ch0")
        // 解析器看到混淆条目（UID 对上）。
        assertEquals(setOf("OEBPS/Fonts/r.woff2"), c.book?.obfuscatedFonts)
        val fonts = c.bookFontsFor(0)
        assertEquals(1, fonts.size)
        assertEquals("woff2", orilumn.reader.engine.css.sniffFontFormat(fonts[0].bytes))
        assertTrue("deobfuscated bytes must equal original", fonts[0].bytes.contentEquals(fontBytes()))
    }
}
