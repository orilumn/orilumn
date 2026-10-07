package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 白屏回归锁（平板真机复现 2026-10-07，`docs/调参与分页路径不一致-问题记录.md`
 * 问题 6）：封面 effect 判定书首只需「首章正文号」一个数字，历史上却调
 * `bookStart()`——宿主实现是 `openChapterStart(0)` **导航**：逐章排版、
 * 落位首内容章并 `evictFarChapters(keep=首章, window=30)` 逐出窗外远章。
 * 续读位在书首 ±30 章之外（真机：长生界 ch=89 / 另一会话 ch=83）时，
 * **正在渲染的续读章被逐出**；而封面丢弃落位结果（只取 `.chapter`）、
 * 阅读面仍按续读位取页 → `pageLines` 的 `unit.layout` 为 null
 * （`non-Readback layout … blank content`）→ 整页白屏，且渲染路径
 * 不自愈（pageLines 只读不 ensure），点按翻页也无法恢复。
 *
 * 修复：控制器提供纯查询 [BookDocumentController.firstContentChapter]——
 * 懒解析（缓存未命中时从章 0 经 `ensureMarkup` 找首个含正文章，仅结构
 * 解析、无排版/无落位/无逐出，且不碰「开书不解析正文」契约）；
 * 封面层改读它。
 *
 * 锁定四条：
 *  1. `open()` 后 `firstContentChapter()` 给出正确下标（ch0 为无正文标题页）。
 *  2. 解析只走 markup 结构：open 后全书无任何章被排版（laidOut 恒 false）。
 *  3. 远章绑定版式后（小章走整章全量路径：直接 `bindFull`、无活会话——
 *     即开书落位态），纯查询反复调用**不逐出**远章——白屏回归锁。
 *  4. 对照：`openChapterStart(0)`（bookStart 的导航本体）仍逐出 ±30 窗外
 *     的远章——导航语义不变（TOC 跳转等真导航仍需要有界内存）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstContentChapterProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val viewW = 720
    private val viewH = 1280

    /** 远章下标：与首内容章 ch1 的距离 39 > 驱逐窗口 30。 */
    private val farChapter = 40

    private var cacheSeq = 0

    private val ownedControllers = mutableListOf<BookDocumentController>()

    @After
    fun tearDown() {
        // R13: reclaim background shaping so worker-JVM neighbors run clean.
        ownedControllers.forEach { runCatching { it.close() } }
        ownedControllers.clear()
    }

    private fun newController(): BookDocumentController {
        val c = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        c.cacheRoot = temp.newFolder("cache${cacheSeq++}").absolutePath.toPath()
        ownedControllers.add(c)
        return c
    }

    @Test
    fun `first content chapter resolves lazily without laying out or evicting`() = runBlocking {
        val c = newController()
        assertTrue(c.open(42L, saved = null))
        c.setViewport(viewW, viewH)

        // ⓪ 开书本身不解析任何正文（preflight 契约，OpenBookPreflightProbeTest
        //    同源锁定）——首章号是懒查询，封面首次查询才算。
        assertNull("open must not parse any body", c.unitAt(0)?.markup)

        // ① 纯查询给出正确首章号（ch0 是无文本标题页，首正文章是 ch1）。
        assertEquals("first content chapter is ch1 (ch0 is a blank titlepage)", 1, c.firstContentChapter())

        // ② 解析只走 markup 结构：全书没有任何章被排版。
        for (i in 0 until c.chapterCount) {
            assertTrue("resolve walk must not lay out ch$i", c.unitAt(i)?.laidOut != true)
        }

        // ③ 远章绑定版式（小章走整章全量路径：直接绑定、无活会话——即开书
        //    落位态），纯查询反复调用不逐出远章（白屏回归锁）。
        val unit = c.ensureChapterLayout(farChapter, 0) ?: error("no far chapter $farChapter")
        assertNotNull("small chapter binds a full layout", unit.layout)
        assertNull("small chapter holds no live temp session", unit.inProgress)
        repeat(3) { assertEquals(1, c.firstContentChapter()) }
        assertNotNull(
            "pure firstContentChapter() must not evict the rendered far chapter",
            c.unitAt(farChapter)?.layout,
        )

        // ④ 对照：bookStart() 的导航本体 openChapterStart(0) 落位 ch1 并逐出
        //    ±30 窗外的远章——导航语义不变（真导航仍需要有界内存）。
        val landing = c.openChapterStart(0)
        assertEquals("navigation lands on the first content chapter", 1, landing?.first)
        assertNull(
            "openChapterStart (the old cover path) still evicts far chapters",
            c.unitAt(farChapter)?.layout,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Fixtures：ch0 = 无文本标题页；ch1.. = 小章正文（< 120 块，
    // 首排走整章全量路径直接绑定版式、无活会话）
    // ─────────────────────────────────────────────────────────────

    private fun body(i: Int): String =
        (1..PARAS).joinToString("\n") { "<p>Chapter $i paragraph $it with filler text for measuring.</p>" }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val manifest = (0 until CHAPTERS).joinToString("\n") {
            "<item id=\"c$it\" href=\"Text/c$it.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val spine = (0 until CHAPTERS).joinToString("\n") { "<itemref idref=\"c$it\"/>" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>FCC</dc:title><dc:identifier id="bookid">urn:test:fcc</dc:identifier>
                </metadata>
                <manifest>$manifest</manifest>
                <spine>$spine</spine>
            </package>"""
        val files = mutableMapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            // 封面/标题页：无文本节点 → hasSignificantText() = false
            "OEBPS/Text/c0.xhtml" to "<html><body><div class=\"titlepage\"/></body></html>".toByteArray(),
        )
        for (i in 1 until CHAPTERS) {
            files["OEBPS/Text/c$i.xhtml"] =
                ("<html><body><h1>Chapter $i</h1>" + body(i) + "</body></html>").toByteArray()
        }
        return files
    }

    private companion object {
        const val PARAS = 12
        const val CHAPTERS = 45
    }
}
