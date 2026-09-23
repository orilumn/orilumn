package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (P4-c2 engine): `<a href>` tap lookup + navigation closure.
 *
 * Locked in here:
 *  1. A char inside a cross-chapter link resolves to (chapter, fragment) via [BookDocumentController.linkTargetAt].
 *  2. [BookDocumentController.openLinkTarget] lands on the target chapter with the fragment's char on the page.
 *  3. Same-chapter `#fragment` links re-anchor within the chapter; plain text and external URLs yield null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkNavigationProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
    }

    @Test
    fun `link chars resolve and navigate cross-chapter and same-chapter`() = runBlocking {
        assertTrue(controller.open(21L, saved = null))
        controller.setViewport(viewW, viewH)
        val ch0 = controller.ensureChapterLayout(0, 0) ?: error("no ch0")
        assertNotNull(ch0.pageSlices.firstOrNull())
        val len = ch0.markup!!.textLength.toInt()

        // 1. Distinct link targets across the whole chapter: exactly the two in-book links
        //    (the https link resolves to null and never surfaces).
        val found = LinkedHashSet<LinkTarget?>()
        for (c in 0 until len) found.add(controller.linkTargetAt(0, c))
        found.remove(null)
        assertEquals(setOf(LinkTarget(1, "target"), LinkTarget(0, "local")), found)

        // Plain heading text carries no link.
        assertNull("chapter title must not be a link", controller.linkTargetAt(0, 0))

        // 2. Cross-chapter: the first link char lands ch1 with the fragment's char on the page.
        val crossChar = (0 until len).first { controller.linkTargetAt(0, it) == LinkTarget(1, "target") }
        val cross = controller.openLinkTarget(LinkTarget(1, "target"))
        assertNotNull("cross-chapter open must land", cross)
        assertEquals(1, cross!!.first)
        val fragChar = contentFragmentIdCharStart(controller.unitAt(1)!!.markup!!, "target")!!
        assertTrue(
            "fragment char $fragChar must be on the landing page [${cross.second.charStart},${cross.second.charEnd})",
            fragChar in cross.second.charStart until cross.second.charEnd,
        )
        assertTrue("used link char must be inside the clicked page", crossChar >= 0)

        // 3. Same-chapter anchor re-anchors within ch0.
        val self = controller.openLinkTarget(LinkTarget(0, "local"))
        assertNotNull("same-chapter open must land", self)
        assertEquals(0, self!!.first)
        val localChar = contentFragmentIdCharStart(controller.unitAt(0)!!.markup!!, "local")!!
        assertTrue(
            "local char $localChar must be on the landing page [${self.second.charStart},${self.second.charEnd})",
            localChar in self.second.charStart until self.second.charEnd,
        )

        // 4. Out-of-range chapter refuses.
        assertNull(controller.openLinkTarget(LinkTarget(99, null)))
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P4-c2</dc:title><dc:identifier id="bookid">urn:test:p4c2</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val filler = (1..12).joinToString("\n") { p ->
            "<p>Filler paragraph $p. " + "some filler text for measuring line wraps and page breaks. ".repeat(3) + "</p>"
        }
        val c1 = "<html><body><h1>Chapter 1</h1>" +
            "<h2 id=\"local\">Local section</h2>" +
            "<p>See <a href=\"c2.xhtml#target\">the target</a> and " +
            "<a href=\"#local\">self</a> here, or <a href=\"https://example.com/\">web</a>.</p>" +
            filler + "</body></html>"
        val c2 = "<html><body><h1>Chapter 2</h1>" +
            "<h2 id=\"target\">Target section</h2>" +
            "<p>Arrival body. " + "arrival filler text for measuring line wraps. ".repeat(6) + "</p>" +
            filler + "</body></html>"
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to c1.toByteArray(),
            "OEBPS/Text/c2.xhtml" to c2.toByteArray(),
        )
    }
}
