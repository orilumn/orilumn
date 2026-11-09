package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.After
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
 * 章内跳转**优先磁盘表**（总则 1）：临时表只是磁盘表就绪前的占位，磁盘表一就绪，
 * 跳转就必须落到磁盘表上——哪怕目标页正落在临时表的窗口里（那种情况以前会走 [fastRelocate]
 * 的临时表分支，搬一下指针就返回，读者继续看那张随时会被抛弃的分页）。
 *
 * 复现路径：大章（> 120 块）开书 ⇒ 锚点临时表会话 + 后台 B1；等 B1 把正典算完并绑到本章；
 * 跳到**临时表窗口内**的某一页 ⇒ 必须改由磁盘表落位，会话被收掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JumpUsesCanonicalTableProbeTest {

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
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `in-chapter jump lands on the canonical table even inside the temp window`() = runBlocking {
        assertTrue(controller.open(21L, saved = null))
        controller.setViewport(viewW, viewH)

        // 大章：开书落位走锚点临时表，B1 在后台算正典。
        controller.ensureChapterLayout(0, 0)
        val unit = controller.unitAt(0) ?: error("no unit 0")
        val ip = unit.inProgress ?: error("large chapter must own a temp session")
        assertNull("temp session must start without a canonical table", unit.paginationTable)

        // 等 B1 把正典算完并绑到本章（canonicalSlices / unit 上的表任一到手即算就绪）。
        withTimeout(30_000) {
            while (unit.paginationTable == null && ip.canonicalSlices.isEmpty()) delay(20)
        }
        val table = unit.paginationTable ?: error("canonical table must be bound")

        // 锚点取临时表**窗口内**的一页：旧实现会在这里走 fastRelocate 的临时表分支。
        val window = ip.forwardPages + ip.backwardPages
        val inside = window.firstOrNull { it.slice.firstLine >= 0 }?.slice
            ?: error("no shaped temp page in window")
        val anchor = inside.charStart
        assertTrue(
            "test premise: the anchor must be inside a live temp page",
            inside.charStart <= anchor && anchor < inside.charEnd,
        )

        val landed = controller.relayoutTo(0, anchor) ?: error("jump must land")

        // 落到磁盘表上：落位页必须是正典表里含锚点的那一页，且会话已收掉。
        val canonical = table.pages.firstOrNull { it.charStart <= anchor && anchor < it.charEnd }
            ?: error("anchor must fall in a canonical page")
        assertEquals("落位页首必须是正典页首", canonical.charStart, landed.second.charStart)
        assertEquals("落位页界必须是正典页界", canonical.charEnd, landed.second.charEnd)
        assertNull("跳转命中磁盘表后不得再留在临时会话里", unit.inProgress)
        assertTrue("落位页必须来自正典（有行号）", landed.second.firstLine >= 0)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>JUMP</dc:title><dc:identifier id="bookid">urn:test:jump-canonical</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // 大章：~150 段（> 120 块）⇒ 走临时表；每段够长，保证一页装不下。
        val body = (1..150).joinToString("\n") { p ->
            "<p>Paragraph $p. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray(),
            "OEBPS/Text/c2.xhtml" to ("<html><body><h1>Chapter 2</h1>" + body + "</body></html>").toByteArray(),
        )
    }
}
