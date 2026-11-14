package orilumn.reader.desktop

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.TextEditBlock
import orilumn.reader.ui.reader.TextEditOutcome
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书里用 `display:block` 把行内元素做成独立一行（`<h2><span class="sec_num">第四十八章</span> 夜话</h2>`）时，
 * 修改卡要**照实写出那一层标签**，而不是让它在输入区里凭空消失。
 *
 * 这条口径与正文段落**同一套**：`<p>段落前<span class="blk">块中间</span>段落后</p>` 里的那一截同样是
 * 单独一叶、同样带 `ownTag`——不是标题的特殊分支（读者复问「为什么到标题就得另搞一套」）。
 */
private const val OWN_TAG_BODY = """
<h2><span class="sec_num">第四十八章</span> 夜话</h2>
<p>段落前<span class="blk">块中间</span>段落后</p>
"""

private const val OWN_TAG_CSS = """
span.sec_num { display: block; font-size: 0.4em; }
span.blk { display: block; }
"""

class OwnTagDesktopTest {

    private val viewportW = 760
    private val viewportH = 1000

    private fun writeBook(root: File): File {
        val file = File(root, "own-tag.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles>
                </container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>行内元素独立成行</dc:title><dc:identifier id="id">own-tag</dc:identifier>
                    <dc:language>zh-CN</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="s.css" media-type="text/css"/>
                  </manifest>
                  <spine><itemref idref="ch"/></spine>
                </package>""",
            )
            entry("OEBPS/s.css", OWN_TAG_CSS)
            entry(
                "OEBPS/ch.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>标题</title>
                <link rel="stylesheet" type="text/css" href="s.css"/></head>
                <body>
                $OWN_TAG_BODY
                </body></html>""",
            )
        }
        return file
    }

    private fun openHost(bookFile: File, root: File): DesktopReaderHost = runBlocking {
        val store = DesktopShelfStore(root)
        store.load()
        val entry = store.addBook(bookFile.nameWithoutExtension, null, bookFile.absolutePath, null)
        DesktopReaderHost(
            bookFile = entry.filePath,
            bookId = entry.id,
            store = store,
            settings = ReaderSettings.DEFAULT,
            density = 1f,
            viewportW = viewportW,
            viewportH = viewportH,
            cacheRoot = File(root, "cache"),
            initialReviewMode = false,
            fontLibrary = store.fontLibrary(),
        )
    }

    private fun body(host: DesktopReaderHost, chapter: Int): String =
        host.textOf(chapter, 0, Int.MAX_VALUE) ?: ""

    /**
     * 与 `ReaderScreen.openTextEditAt` 同一条换算：选区上的行内结构 + 「这一行本身就是那个元素」
     * 的整行包装（`ownTag`），块之间多算一个 `\n`。
     */
    private fun cardOf(
        host: DesktopReaderHost,
        chapter: Int,
        blocks: List<TextEditBlock>,
        text: String,
    ): String {
        val segments = blocks.map { b ->
            InlineMarkup.Segment(text.substring(b.start, b.endExclusive), b.kind, b.table)
        }
        val raw = host.textEditSpans(chapter, 0, text.length).orEmpty()
        val innerEnds = blocks.dropLast(1).map { it.endExclusive }
        fun shift(off: Int, atEnd: Boolean): Int {
            var n = 0
            for (end in innerEnds) if (if (atEnd) end < off else end <= off) n++
            return off + n
        }
        val mapped = raw.map {
            InlineMarkup.StyleSpan(shift(it.start, false), shift(it.endExclusive, true), it.sig)
        }
        // `ownTag` 那层整行包装：只给显示，提交时由卡片侧摘掉。
        val own = ArrayList<InlineMarkup.StyleSpan>()
        var at = 0
        for ((i, seg) in segments.withIndex()) {
            val tag = blocks[i].ownTag
            if (tag != null && seg.text.isNotEmpty()) {
                own += InlineMarkup.StyleSpan(at, at + seg.text.length, InlineMarkup.StyleSig(tag))
            }
            at += seg.text.length + 1
        }
        return InlineMarkup.show(segments, mapped + own)
    }

    private fun blockAt(blocks: List<TextEditBlock>, text: String, probe: String): TextEditBlock {
        val hit = text.indexOf(probe)
        assertTrue("正文里应找到「$probe」：$text", hit >= 0)
        return blocks.first { hit >= it.start && hit < it.endExclusive }
    }

    @Test
    fun headingAndBodyShareTheSameOwnTag() {
        val root = Files.createTempDirectory("orilumn-own-tag").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val text = body(host, chapter)
            val blocks = host.textEditBlocks(chapter, 0, text.length)
            assertNotNull("整章应给得出块信息", blocks)

            // 标题里那个 display:block 的 span：排版把它切成单独一叶，卡片带上标签。
            assertEquals("span", blockAt(blocks!!, text, "第四十八章").ownTag)
            // 标题剩下的字是零散文字（`#text` 叶），不带标签。
            assertNull(blockAt(blocks, text, "夜话").ownTag)
            // 正文段落里同一套：块中间也是单独一叶、也带标签（不是标题专属）。
            assertEquals("span", blockAt(blocks, text, "块中间").ownTag)
            assertNull(blockAt(blocks, text, "段落前").ownTag)

            val card = cardOf(host, chapter, blocks, text)
            assertTrue("卡片应写出标题里的 <span>：$card", card.contains("<span>第四十八章</span>"))
            assertTrue("卡片应写出段落里的 <span>：$card", card.contains("<span>块中间</span>"))
            val parsed = InlineMarkup.parse(card)
            assertTrue("卡片应能解析回来：${parsed.errors}", parsed.ok)
        } finally {
            host.close()
        }
    }

    @Test
    fun editingTheSpanKeepsTheElementWithoutNesting() {
        val root = Files.createTempDirectory("orilumn-own-tag-commit").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val head = blockAt(host.textEditBlocks(chapter, 0, before.length)!!, before, "第四十八章")
            assertEquals("span", head.ownTag)

            // 跟真卡片一样：只改这一叶的字，结构交给重放（卡片那层整行包装提交前已摘掉）。
            val outcome = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = head.start,
                    endExclusive = head.endExclusive,
                    text = "第四十九章宣",
                    styles = null,
                    currentPage = pos,
                    blocks = null,
                    rowScope = null,
                )
            }
            assertTrue("提交应成功：$outcome", outcome is TextEditOutcome.Success)
            val after = body(host, chapter)
            assertTrue("改的字要落进正文：$after", after.contains("第四十九章宣"))

            // 叶元素还是那一个，且里面**没有多套一层 span**（嵌套会让元素层级越改越深）。
            val afterBlocks = host.textEditBlocks(chapter, 0, after.length)!!
            val headAfter = blockAt(afterBlocks, after, "第四十九章宣")
            assertEquals("叶元素还是那一个", "span", headAfter.ownTag)
            assertTrue(
                "叶里不该多出一层 span：" +
                    host.textEditSpans(chapter, headAfter.start, headAfter.endExclusive),
                host.textEditSpans(chapter, headAfter.start, headAfter.endExclusive).orEmpty().isEmpty(),
            )
        } finally {
            host.close()
        }
    }
}
