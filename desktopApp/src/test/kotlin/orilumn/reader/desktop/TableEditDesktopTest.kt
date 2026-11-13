package orilumn.reader.desktop

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.TextEditBlock
import orilumn.reader.engine.revision.tableRowCardOf
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.ui.reader.TextEditOutcome
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面全管线（开书 → 卡片文本 → 整表修改 → 落库重排 → 再读正文）验证表格结构修改。
 *
 * 走的是与真卡片**同一套入口**：`textEditBlocks` 拿块/格结构 → `InlineMarkup.show` 生成卡片文字
 * （管道行）→ `InlineMarkup.parse` 解析回分段 → `commitTextEdit` 落库并重排。
 * 覆盖：删整行、加整行、每行加一列、删整表，以及网格不自洽时拒绝；合并单元格单独一条。
 */
private const val DEFAULT_BODY = """
<h1>表格测试</h1>
<p>表格前面的段落。</p>
<table>
  <tr><th>甲一</th><th>乙一</th><th>丙一</th></tr>
  <tr><td>甲二</td><td>乙二</td><td>丙二</td></tr>
  <tr><td>甲三</td><td>乙三</td><td>丙三</td></tr>
  <tr><td>甲四</td><td>乙四</td><td>丙四</td></tr>
</table>
<p>表格后面的段落。</p>
"""

private const val MERGED_BODY = """
<h1>合并表格</h1>
<p>合并表格前面的段落。</p>
<table>
  <tr><td colspan="2">合并甲</td><td>合并乙</td></tr>
  <tr><td rowspan="2">跨行甲</td><td>乙二</td><td>丙二</td></tr>
  <tr><td>乙三</td><td>丙三</td></tr>
</table>
<p>合并表格后面的段落。</p>
"""

private const val MARKUP_BODY = """
<h1>表格标记</h1>
<p>表格前面的段落。</p>
<table>
  <tr><th>功能</th><th>通过访问</th></tr>
  <tr><td><code>let</code></td><td><code>it</code></td></tr>
  <tr><td><code>run</code></td><td>初始化<em>AND</em>计算结果</td></tr>
</table>
<p>表格后面的段落。</p>
"""

class TableEditDesktopTest {

    private val viewportW = 760
    private val viewportH = 1000

    // ---- 测试书 ----

    private fun writeBook(root: File, bodyHtml: String = DEFAULT_BODY): File {
        val file = File(root, "table-test.epub")
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
                    <dc:title>表格测试</dc:title><dc:identifier id="id">table-test</dc:identifier>
                    <dc:language>zh-CN</dc:language>
                  </metadata>
                  <manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="ch"/></spine>
                </package>""",
            )
            entry(
                "OEBPS/ch.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>表格</title></head>
                <body>
                $bodyHtml
                </body></html>""",
            )
        }
        return file
    }

    private fun openHost(bookFile: File, root: File, reviewMode: Boolean = false): DesktopReaderHost = runBlocking {
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
            initialReviewMode = reviewMode,
            fontLibrary = store.fontLibrary(),
        )
    }

    // ---- 卡片口径 ----

    private fun tableBlocks(
        host: DesktopReaderHost,
        chapter: Int,
        text: String,
        expectedRows: Int = 4,
        probe: String = "乙四",
    ): Pair<IntRange, List<TextEditBlock>> {
        val hit = text.indexOf(probe)
        assertTrue("章节里应找到表格内容", hit >= 0)
        val first = host.textEditBlocks(chapter, hit, hit + 1)?.firstOrNull { it.table != null }
        assertNotNull("选区应命中表格行", first)
        val range = first!!.tableRange
        assertNotNull("表格行应带整表区间", range)
        val blocks = host.textEditBlocks(chapter, range!!.first, range.last + 1)
        assertNotNull(blocks)
        assertEquals("整表区间应覆盖所有行", expectedRows, blocks!!.size)
        assertTrue("整表区间内全是表格行", blocks.all { it.table != null })
        return range to blocks
    }

    private fun cardText(text: String, blocks: List<TextEditBlock>): String {
        val segments = blocks.map { b ->
            InlineMarkup.Segment(text.substring(b.start, b.endExclusive), null, b.table)
        }
        return InlineMarkup.show(segments, emptyList())
    }

    /**
     * 带行内结构的卡片文字：与 `ReaderScreen.setTextEditTarget` 同一条换算（选区上的 span
     * 相对选区起点，块之间要多算一个 `\n`）。
     */
    private fun cardText(
        text: String,
        blocks: List<TextEditBlock>,
        from: Int,
        spans: List<InlineMarkup.StyleSpan>,
    ): String {
        val segments = blocks.map { b ->
            InlineMarkup.Segment(text.substring(b.start, b.endExclusive), null, b.table)
        }
        val innerEnds = blocks.dropLast(1).map { it.endExclusive }
        fun shift(off: Int, atEnd: Boolean): Int {
            var n = 0
            for (end in innerEnds) if (if (atEnd) end < off else end <= off) n++
            return off - from + n
        }
        val mapped = spans.map {
            InlineMarkup.StyleSpan(
                shift(from + it.start, atEnd = false),
                shift(from + it.endExclusive, atEnd = true),
                it.sig,
            )
        }
        return InlineMarkup.show(segments, mapped)
    }

    private fun commit(
        host: DesktopReaderHost,
        chapter: Int,
        range: IntRange,
        card: String,
        currentPage: orilumn.reader.ui.reader.ReaderPos?,
    ): TextEditOutcome {
        val parsed = InlineMarkup.parse(card)
        assertTrue("卡片文字应能解析：${parsed.errors}", parsed.ok)
        return runBlocking {
            host.commitTextEdit(
                chapter = chapter,
                start = range.first,
                endExclusive = range.last + 1,
                text = parsed.text,
                styles = null,
                currentPage = currentPage,
                blocks = parsed.segments,
            )
        }
    }

    private fun body(host: DesktopReaderHost, chapter: Int): String =
        host.textOf(chapter, 0, Int.MAX_VALUE) ?: ""

    private fun lines(card: String): List<String> = card.split('\n')

    /** 把一页的行画成 PNG（桌面同一条 Skia 画线路径），供人眼复核结构修改后的版面。 */
    private fun savePage(lines: List<DrawLine>, file: File) {
        file.parentFile.mkdirs()
        val bmp = Bitmap()
        bmp.allocN32Pixels(viewportW, viewportH, true)
        val canvas = Canvas(bmp)
        canvas.clear(Color.WHITE)
        val base = lines.firstOrNull()?.yTop ?: 0
        orilumn.reader.engine.skia.LineWindowDrawer().drawLines(
            canvas,
            40f,
            lines.map { it.copy(yTop = it.yTop - base + 40, yBottom = it.yBottom - base + 40) },
            null,
        )
        val img = BufferedImage(viewportW, viewportH, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until viewportH) for (x in 0 until viewportW) img.setRGB(x, y, bmp.getColor(x, y))
        ImageIO.write(img, "png", file)
    }

    @Test
    fun wholeTableEditsThroughCard() {
        val root = Files.createTempDirectory("orilumn-table").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }
            assertNotNull("开书应落位", pos)
            val chapter = pos!!.chapter
            val before = body(host, chapter)
            assertTrue(before.contains("甲一") && before.contains("丙四"))
            val shots = File("build/table-edit")
            host.pageLines(pos)?.let { savePage(it, File(shots, "1-before.png")) }

            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks)
            assertTrue("普通表应显示成管道行：$card", card.startsWith("| 甲一 | 乙一 | 丙一 |"))
            assertEquals(4, lines(card).size)

            // 1) 删整行（第二行）。
            val deleted = lines(card).filterNot { it.contains("乙二") }.joinToString("\n")
            val outcome = commit(host, chapter, range, deleted, pos)
            assertTrue("删行应成功：$outcome", outcome is TextEditOutcome.Success)
            val afterDelete = body(host, chapter)
            assertFalse("被删的行应从正文消失", afterDelete.contains("甲二"))
            assertTrue("其余行仍在", afterDelete.contains("甲三") && afterDelete.contains("甲四"))
            assertTrue("表外段落不受影响", afterDelete.contains("表格前面的段落"))
            // 落到页面上：重排后的行里不再有被删那一行（不是只有内存文字变了）。
            val landed = (outcome as TextEditOutcome.Success).pos
            val pageText = landed?.let { host.pageLines(it)?.joinToString("") { l -> l.text } }
            assertNotNull("删行后应拿到重排页", pageText)
            assertFalse("页面上也不应再画出被删的行", pageText!!.contains("甲二"))
            assertTrue("页面仍画着其余行", pageText.contains("甲三"))
            host.pageLines(landed)?.let { savePage(it, File(shots, "2-after-delete-row.png")) }

            // 2) 加整行（补在末尾）。
            val (range2, blocks2) = tableBlocks(host, chapter, afterDelete, expectedRows = 3)
            val card2 = cardText(afterDelete, blocks2)
            val added = card2 + "\n| 新甲 | 新乙 | 新丙 |"
            val outcome2 = commit(host, chapter, range2, added, pos)
            assertTrue("加行应成功：$outcome2", outcome2 is TextEditOutcome.Success)
            val afterAdd = body(host, chapter)
            assertTrue("新行应写进正文", afterAdd.contains("新甲") && afterAdd.contains("新丙"))
            val pageAfterAdd = (outcome2 as TextEditOutcome.Success).pos
                ?.let { host.pageLines(it)?.joinToString("") { l -> l.text } }
            assertTrue("新行应画到页面上", pageAfterAdd?.contains("新甲") == true)

            // 3) 每行加一列（4 行 × 4 格，网格仍自洽）。
            val (range3, blocks3) = tableBlocks(host, chapter, afterAdd)
            val card3 = cardText(afterAdd, blocks3)
            val wide = lines(card3).joinToString("\n") { it.dropLast(1) + "| 新列 |" }
            assertTrue(commit(host, chapter, range3, wide, pos) is TextEditOutcome.Success)
            val afterWide = body(host, chapter)
            assertEquals("每个新格都应落进正文", 4, Regex("新列").findAll(afterWide).count())

            // 4) 网格不自洽（只给某一行删格）→ 规划层拒绝。
            val (range4, blocks4) = tableBlocks(host, chapter, afterWide)
            val card4 = cardText(afterWide, blocks4)
            val broken = lines(card4).mapIndexed { i, l ->
                if (i == 1) "| 甲三 | 乙三 | 丙三 |" else l
            }.joinToString("\n")
            val rejected = commit(host, chapter, range4, broken, pos)
            assertTrue("列数对不齐应拒绝：$rejected", rejected is TextEditOutcome.Rejected)
            assertEquals("拒绝后正文不应变化", afterWide, body(host, chapter))

            // 5) 删整表（卡片清空）。
            val (range5, _) = tableBlocks(host, chapter, afterWide)
            assertTrue(commit(host, chapter, range5, "", pos) is TextEditOutcome.Success)
            val afterRemove = body(host, chapter)
            assertFalse("整张表的字都应消失", afterRemove.contains("甲一") || afterRemove.contains("新甲"))
            assertTrue("表前表后的段落仍在", afterRemove.contains("表格前面的段落") &&
                afterRemove.contains("表格后面的段落"))
        } finally {
            host.close()
        }
    }

    /**
     * 只选中**一行**、把这一行删掉：卡片里只有这一行，提交时把未选中的行一起拼回整张表
     * 再校验 / 重建。这是真机复报「只选一行删不掉」的回归。
     */
    @Test
    fun deletingOneSelectedRowThroughCard() {
        val root = Files.createTempDirectory("orilumn-table-one-row").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            assertTrue(before.contains("甲二"))
            val hit = before.indexOf("甲二")
            val row = host.textEditBlocks(chapter, hit, hit + 1)?.firstOrNull { it.table != null }
            assertNotNull("选区应命中表格行", row)
            // 只给这一行的区间（不是整张表）：卡片只列这一行。
            val rowRange = row!!.start until row.endExclusive
            val card = cardText(before, listOf(row))
            assertEquals("卡片只应有一行", 1, lines(card).size)

            val outcome = commit(host, chapter, rowRange, "", pos)
            assertTrue("只选一行删行应成功：$outcome", outcome is TextEditOutcome.Success)
            val after = body(host, chapter)
            assertFalse("被删的行应从正文消失", after.contains("甲二"))
            assertTrue("其余行仍在", after.contains("甲一") && after.contains("甲三") && after.contains("甲四"))
            assertTrue("表外段落不受影响", after.contains("表格前面的段落"))
            val landed = (outcome as TextEditOutcome.Success).pos
            val page = landed?.let { host.pageLines(it)?.joinToString("") { l -> l.text } }
            assertFalse("页面上也不应再画出被删的行", page?.contains("甲二") == true)
        } finally {
            host.close()
        }
    }

    /** 合并单元格：卡片走基本 HTML 行，改字照常；删行时 rowspan 越过末行必须拒绝。 */
    @Test
    fun mergedCellsRoundTripAndRowspanGuard() {        val root = Files.createTempDirectory("orilumn-table-merged").toFile()
        val host = openHost(writeBook(root, MERGED_BODY), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            assertTrue(before.contains("跨行甲"))
            val (range, blocks) = tableBlocks(host, chapter, before, expectedRows = 3, probe = "乙二")
            val card = cardText(before, blocks)
            assertTrue(
                "合并格应显示成基本 HTML：$card",
                card.contains("<tr>") && card.contains("rowspan=\"2\"") && card.contains("colspan=\"2\""),
            )
            assertTrue("格内文字照旧看得见", card.contains("合并甲") && card.contains("跨行甲"))

            // 改一个格里的字：结构不变，照样走整表通道。
            val edited = card.replace("丙三", "丙三改")
            val editOutcome = commit(host, chapter, range, edited, pos)
            assertTrue("改格内文字应成功：$editOutcome", editOutcome is TextEditOutcome.Success)
            val afterEdit = body(host, chapter)
            assertTrue(afterEdit.contains("丙三改"))
            assertTrue("合并格本身不动", afterEdit.contains("跨行甲") && afterEdit.contains("合并甲"))

            // 删末行但不改 rowspan=2：rowspan 越过末行 ⇒ 拒绝。
            val (range2, blocks2) = tableBlocks(host, chapter, afterEdit, expectedRows = 3, probe = "乙二")
            val card2 = cardText(afterEdit, blocks2)
            val orphan = lines(card2).dropLast(1).joinToString("\n")
            val rejected = commit(host, chapter, range2, orphan, pos)
            assertTrue("rowspan 越过末行应拒绝：$rejected", rejected is TextEditOutcome.Rejected)
            assertEquals("拒绝后正文不变", afterEdit, body(host, chapter))

            // 把 rowspan 收成 1 再删末行：网格自洽，放行。
            val fixed = lines(card2).dropLast(1).joinToString("\n")
                .replace("rowspan=\"2\"", "rowspan=\"1\"")
            val outcome = commit(host, chapter, range2, fixed, pos)
            assertTrue("收掉 rowspan 后应放行：$outcome", outcome is TextEditOutcome.Success)
            val afterDelete = body(host, chapter)
            assertFalse("末行应消失", afterDelete.contains("乙三"))
            assertTrue("其余格仍在", afterDelete.contains("跨行甲") && afterDelete.contains("乙二"))
            val page = (outcome as TextEditOutcome.Success).pos
                ?.let { host.pageLines(it)?.joinToString("") { l -> l.text } }
            assertFalse("页面上也不应再有末行", page?.contains("乙三") == true)
        } finally {
            host.close()
        }
    }

    /**
     * 表头 + 格内行内标记：卡片走管道行时要把 `<code>` / `<em>` 摊成 markdown 语法糖，
     * 表头用 markdown 分隔行往返（提交后仍是 `<th>`），整表区间一直盖到最后一格。
     */
    @Test
    fun tableHeaderAndInlineMarkupRoundTripThroughCard() {
        val root = Files.createTempDirectory("orilumn-table-markup").toFile()
        val host = openHost(writeBook(root, MARKUP_BODY), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before, expectedRows = 3, probe = "计算结果")
            assertEquals("整表区间应盖到最后一格的最后一个字", range.last + 1, blocks.last().endExclusive)
            assertTrue("最后一格应完整落在整表区间里", blocks.last().endExclusive - blocks.last().start >= "初始化AND计算结果".length)

            val spans = host.textEditSpans(chapter, range.first, range.last + 1)
            assertNotNull("表格行的行内结构应拿得到", spans)
            val card = cardText(before, blocks, range.first, spans!!)
            val cardLines = lines(card)
            assertEquals("表头应是管道行", "| 功能 | 通过访问 |", cardLines[0])
            assertEquals("格内代码应摊成反引号", "| `let` | `it` |", cardLines[1])
            assertTrue("格内斜体应摊成星号：$card", card.contains("初始化*AND*计算结果"))
            assertFalse("不应退回标签形式：$card", card.contains("<code>") || card.contains("<em>"))
            assertTrue("表头格应带 `<th>` 信息", blocks.first().table!!.cells.all { it.header })

            // 改一格的字并提交：表头在管道行里看不见，但提交时按原位补回，`<th>` 不会悄悄变成普通格。
            val edited = card.replace("计算结果", "计算结果改")
            val outcome = commit(host, chapter, range, edited, pos)
            assertTrue("改字应成功：$outcome", outcome is TextEditOutcome.Success)
            val after = body(host, chapter)
            assertTrue("新写的字应落进正文", after.contains("计算结果改"))

            val (range2, blocks2) = tableBlocks(host, chapter, after, expectedRows = 3, probe = "计算结果改")
            val reopened = cardText(after, blocks2)
            assertTrue("改过的字应回显", reopened.contains("计算结果改"))
            assertTrue("表头文字仍在", reopened.startsWith("| 功能 | 通过访问 |"))
            assertTrue("重开卡片仍是管道行（没有多余的分隔行）", lines(reopened).size == 3)
            assertTrue("提交后表头仍是 `<th>`", blocks2.first().table!!.cells.all { it.header })
            assertEquals("整表区间应仍盖到最后一格", range2.last + 1, blocks2.last().endExclusive)
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图（默认阅读视图在平板）里，整表结构修订必须**显示被删掉的内容**。
     *
     * 删掉一行后切到审阅空间：被删的那一行仍留在表里（红字 + 删除线），没改的行照旧，
     * 整表在审阅空间里比正常视图多出被删文字——与其他文字的审阅显示完全同一套口径。
     */
    @Test
    fun reviewModeKeepsDeletedTableContent() {
        val root = Files.createTempDirectory("orilumn-table-review").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks)
            val deleted = lines(card).filterNot { it.contains("乙二") }.joinToString("\n")
            assertTrue(commit(host, chapter, range, deleted, pos) is TextEditOutcome.Success)
            val normalText = body(host, chapter)
            assertFalse("正常视图里被删的行已经不在", normalText.contains("乙二"))

            val landed = runBlocking { host.switchReviewMode(true, chapter, 0) }
            assertNotNull("应能切到审阅空间", landed)
            assertTrue(host.isReviewMode())
            val reviewText = body(host, chapter)
            assertTrue("审阅空间里被删的行要留在原处：$reviewText", reviewText.contains("乙二"))
            assertTrue("其余行照旧在", reviewText.contains("甲二") && reviewText.contains("甲四"))
            assertTrue("审阅空间比正常视图长（原文一字不删）", reviewText.length > normalText.length)
            val marks = host.reviewMarks(chapter)
            assertTrue("被删的表内容应标红（红字 + 删除线）", marks.any { it.deleted })
            assertFalse("这次只删了内容，没有新增", marks.any { !it.deleted })
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图里「点按只认改过的那一行」的前提：改动痕迹只落在真正改到的行里，
     * 一行都没改过的行不在任何痕迹的范围内。
     *
     * 点按命中用它分「修改区域」与「表格其他区域」：前者进编辑状态，后者不响应。
     */
    @Test
    fun reviewMarksCoverOnlyChangedTableRow() {
        val root = Files.createTempDirectory("orilumn-table-review-marks").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks).replace("乙二", "乙二改")
            assertTrue(commit(host, chapter, range, card, pos) is TextEditOutcome.Success)

            assertNotNull(runBlocking { host.switchReviewMode(true, chapter, 0) })
            val reviewText = body(host, chapter)
            val marks = host.reviewMarks(chapter)
            assertTrue("改过的格应带痕迹：$marks", marks.isNotEmpty())

            val changed = reviewText.indexOf("乙二改")
            assertTrue("审阅空间里改过的格是「原文 + 改后」：$reviewText", changed >= 0)
            assertTrue(
                "新写的字要落在痕迹里（绿字）：$marks",
                marks.any { !it.deleted && changed < it.endExclusive && it.start < changed + 3 },
            )
            assertEquals("这次只添了一个字", 1, marks.size)

            // 行级判据（UI 点按命中同一条）：改过的那一行与痕迹相交，别行不相交。
            // 同一行里没改过的格（甲二 / 丙二）也算修改区域——改一格常常只改一两个字，
            // 读者点这一行里其他字同样要进编辑；没改过的**整行**才不响应。
            fun rowAt(at: Int) = host.textEditBlocks(chapter, at, at + 1)!!.first()
            for (text in listOf("甲一", "丙一", "乙二改", "甲二", "丙二", "甲三", "丙四")) {
                val at = reviewText.indexOf(text)
                assertTrue("审阅空间里应仍有 $text：$reviewText", at >= 0)
                val row = rowAt(at)
                val touched = marks.any { it.start < row.endExclusive && row.start < it.endExclusive }
                if (text in setOf("乙二改", "甲二", "丙二")) {
                    assertTrue("改过的行里 $text 也算修改区域：${row.start}-${row.endExclusive} $marks", touched)
                } else {
                    assertFalse("没改过的行 $text 不该算修改区域：${row.start}-${row.endExclusive} $marks", touched)
                }
            }
        } finally {
            host.close()
        }
    }

    /**
     * 点按「表格里的修改区域」要进编辑状态、带出之前的改动（真机复报：点了没反应）。
     *
     * 这里把 UI 点按决策的三步照原样走一遍（`tryOpenReviewEditAt` 用的就是它们）：
     * 1. 点到的字落在本章某条修订的整段里 ⇒ 命中这条修订；
     * 2. 审阅视图里按点到的行开行卡片（`tableRowScope`），卡片文字是**改完的那一行**；
     * 3. 一行都没改过的格：痕迹碰不到它 ⇒ 不进编辑（连同不翻页由 UI 吞掉这次点按）。
     */
    @Test
    fun reviewTapOnChangedTableRowOpensRowCard() {
        val root = Files.createTempDirectory("orilumn-table-review-tap").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks).replace("乙二", "乙二改")
            assertTrue(commit(host, chapter, range, card, pos) is TextEditOutcome.Success)

            assertNotNull(runBlocking { host.switchReviewMode(true, chapter, 0) })
            val reviewText = body(host, chapter)
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .single { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            val marks = host.reviewMarks(chapter)

            /** 点按命中的第一步：这个字在不在某条修订的整段里。 */
            fun hit(at: Int) = at >= revision.charStart && at < revision.charEnd

            fun rowOf(text: String): Pair<Int, TextEditBlock> {
                val at = reviewText.indexOf(text)
                assertTrue("审阅空间里应仍有 $text：$reviewText", at >= 0)
                return at to host.textEditBlocks(chapter, at, at + 1)!!.first()
            }

            // 改过的格：命中修订，按点到的行开卡片，卡片带出改后的内容。
            val (changedAt, _) = rowOf("乙二改")
            assertTrue("点改过的格应命中修订：$changedAt in [${revision.charStart},${revision.charEnd})", hit(changedAt))
            val scope = host.tableRowScope(chapter, revision.id, changedAt, changedAt + 1)
            assertNotNull("改过的那一行应能开行卡片", scope)
            val rowCard = tableRowCardOf(revision, scope!!)
            assertNotNull("行卡片应能拼出来", rowCard)
            assertTrue("卡片带出之前的改动：${rowCard!!.text}", rowCard.text.contains("乙二改"))
            assertFalse("行卡片不是整张表：${rowCard.text}", rowCard.text.contains("甲三"))

            // 没改过的行：也在整条修订的整段里（整张表是一条修订），但痕迹碰不到它 ⇒ 不进编辑。
            val (plainAt, plainRow) = rowOf("甲三")
            assertTrue("整张表都是同一条修订：$plainAt in [${revision.charStart},${revision.charEnd})", hit(plainAt))
            assertFalse(
                "一行都没改过 ⇒ 不算修改区域：${plainRow.start}-${plainRow.endExclusive} $marks",
                marks.any { it.start < plainRow.endExclusive && plainRow.start < it.endExclusive },
            )
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图里重开整表结构修订：卡片带出**改完的那张表**，再改一次仍按原书那张表提交。
     *
     * 真机复报：先删过一行（表上压着一条整表结构修订），进审阅视图再清空一行，
     * 整张表连同其余行都没了。根因是审阅分支把选区扩到整条修订的原书区间（整张原始表），
     * 卡片里却只有一行——规划层把「一行清空」当成了「整表删空」。现在重开卡片走的是
     * 改后的整张表，清空一行就是删那一行，其余行原样留下。
     */
    @Test
    fun reviewModeReeditingStructurallyChangedTableKeepsOtherRows() {
        val root = Files.createTempDirectory("orilumn-table-review-row").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks)
            val deleted = lines(card).filterNot { it.contains("乙二") }.joinToString("\n")
            assertTrue(commit(host, chapter, range, deleted, pos) is TextEditOutcome.Success)

            val landedReview = runBlocking { host.switchReviewMode(true, chapter, 0) }
            assertNotNull("应能切到审阅空间", landedReview)
            assertTrue(host.isReviewMode())
            val reviewText = body(host, chapter)
            assertTrue("其余行还在", reviewText.contains("甲三") && reviewText.contains("甲四"))

            // 点按表上改动痕迹 = 重开这条整表修订：卡片带出改后的整张表（乙二已不在）。
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            val segments = orilumn.reader.engine.revision.RevisionBlocks
                .cardSegments(revision.editedText, revision.editedBlocks)
            assertNotNull("整表修订应能还原成表格行", segments)
            val reopened = InlineMarkup.show(segments!!, emptyList())
            assertTrue("重开卡片应显示改后的表：$reopened", reopened.contains("甲一"))
            assertTrue(reopened.contains("甲三") && reopened.contains("甲四"))
            assertFalse("被删的旧行不应出现在改后表里", reopened.contains("乙二"))

            // 再清空一行（甲三）：只删这一行，其余行（甲一 / 甲四）必须还在。
            val cleared = lines(reopened).filterNot { it.contains("甲三") }.joinToString("\n")
            val outcome = commit(host, chapter, revision.charStart until revision.charEnd, cleared, landedReview)
            assertTrue("审阅视图里重改整表应成功：$outcome", outcome is TextEditOutcome.Success)

            val after = body(host, chapter)
            assertTrue("整张表不能消失", after.contains("甲一"))
            assertTrue("其他行仍在", after.contains("甲四"))
            assertTrue("表外段落不受影响", after.contains("表格前面的段落"))
            val back = runBlocking { host.switchReviewMode(false, chapter, 0) }
            assertNotNull("应能切回正常视图", back)
            host.pageLines(back!!)
            // 文本投影在后台回填，刚切完可能还没就绪（与上面普通改字那条一样等一拍）。
            var normalText = body(host, chapter)
            var waited = 0
            while (normalText.isEmpty() && waited < 2_000) {
                Thread.sleep(20)
                waited += 20
                host.pageLines(back)
                normalText = body(host, chapter)
            }
            assertTrue("正常视图里表还在", normalText.contains("甲一") && normalText.contains("甲四"))
            assertFalse("两次删掉的行都不在", normalText.contains("乙二") || normalText.contains("甲三"))
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图里再改一次整表：留下的行仍指向原书那一行（行 / 格样式、格内行内元素都不丢）。
     *
     * 真机复报的另一半：第一次改过的行对不上原书文字，第二次重开时会被当成「读者新加的行」，
     * 重放按新行处理，`<code>` / `<em>` 这些格内元素就没了。`rowSources` 必须顺着上一条整表修订
     * 折回原书行号。
     */
    @Test
    fun reviewModeReeditingTableKeepsRemainingRowSources() {
        val root = Files.createTempDirectory("orilumn-table-review-source").toFile()
        val host = openHost(writeBook(root, MARKUP_BODY), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before, expectedRows = 3, probe = "run")
            val card = cardText(before, blocks)
            // 第一次：删掉表头行，留下 let / run 两行。
            val first = lines(card).filterNot { it.contains("功能") }.joinToString("\n")
            assertTrue(commit(host, chapter, range, first, pos) is TextEditOutcome.Success)

            val reviewPos = runBlocking { host.switchReviewMode(true, chapter, 0) }
            assertNotNull("应能切到审阅空间", reviewPos)
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            val segments = orilumn.reader.engine.revision.RevisionBlocks
                .cardSegments(revision.editedText, revision.editedBlocks)!!
            val reopened = InlineMarkup.show(segments, emptyList())
            // 第二次：再删掉 run 行，只留 let 行（原书第 2 行，0 起下标 1）。
            val second = lines(reopened).filterNot { it.contains("run") }.joinToString("\n")
            val outcome = commit(host, chapter, revision.charStart until revision.charEnd, second, reviewPos)
            assertTrue("重改整表应成功：$outcome", outcome is TextEditOutcome.Success)

            val fresh = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            assertEquals("留下的 let 行应仍来自原书第 2 行", listOf(1), fresh.rowSources)

            val back = runBlocking { host.switchReviewMode(false, chapter, 0) }!!
            host.pageLines(back)
            var normalText = body(host, chapter)
            var waited = 0
            while (normalText.isEmpty() && waited < 2_000) {
                Thread.sleep(20)
                waited += 20
                host.pageLines(back)
                normalText = body(host, chapter)
            }
            assertTrue("let 行还在", normalText.contains("let"))
            assertFalse("run 行已删", normalText.contains("run"))
            // 格内行内元素仍在：`let` 应仍带 `<code>` 结构（否则重放把它当新行重建了）。
            val hit = normalText.indexOf("let")
            val spans = host.textEditSpans(chapter, hit, hit + 3).orEmpty()
            assertTrue("let 的 code 结构应保留：$spans", spans.any { it.sig.tag == "code" })
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图里重开一条普通文字修订：卡片带出改后的整段，再改一次是**改同一条记录**
     * （旧记录被吸收，最终只剩一条），不是把改后的字当成新底稿再叠一层。
     */
    @Test
    fun reviewModeReeditingTextReplacesTheSameRecord() {
        val root = Files.createTempDirectory("orilumn-review-text-reedit").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val old = "表格后面的段落"
            val start = before.indexOf(old)
            assertTrue(start >= 0)
            val first = "别的文字"
            val outcome = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = start,
                    endExclusive = start + old.length,
                    text = first,
                    styles = null,
                    currentPage = pos,
                    blocks = listOf(InlineMarkup.Segment(first, null)),
                )
            }
            assertTrue("第一次改字应成功：$outcome", outcome is TextEditOutcome.Success)

            val reviewPos = runBlocking { host.switchReviewMode(true, chapter, 0) }
            assertNotNull("应能切到审阅空间", reviewPos)
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { r -> r.editedText.contains(first) }
            assertTrue("重开卡片应带出改后的整段：${revision.editedText}", revision.editedText.contains(first))

            val second = "第三次"
            val again = commit(host, chapter, revision.charStart until revision.charEnd, second, reviewPos)
            assertTrue("再改同一条应成功：$again", again is TextEditOutcome.Success)

            val fresh = runBlocking { host.textRevisionsForChapter(chapter) }
            assertEquals("同一处修改只应留一条记录", 1, fresh.size)
            assertEquals(second, fresh.single().editedText)

            val back = runBlocking { host.switchReviewMode(false, chapter, 0) }!!
            host.pageLines(back)
            var normalText = body(host, chapter)
            var waited = 0
            while (normalText.isEmpty() && waited < 2_000) {
                Thread.sleep(20)
                waited += 20
                host.pageLines(back)
                normalText = body(host, chapter)
            }
            assertTrue("最终正文应是最后一次改的字", normalText.contains(second))
            assertFalse("上一次改的字不应残留", normalText.contains(first))
        } finally {
            host.close()
        }
    }

    /** 开书即审阅（桌面默认走这里）：首屏就已经在审阅空间，不用先排正常视图再切。 */
    @Test
    fun opensDirectlyInReviewMode() {        val root = Files.createTempDirectory("orilumn-review-open").toFile()
        val host = openHost(writeBook(root), root, reviewMode = true)
        try {
            val pos = runBlocking { host.open() }
            assertNotNull("开书应落位", pos)
            assertTrue("开书就是审阅视图", host.isReviewMode())
        } finally {
            host.close()
        }
    }

    @Test
    fun reviewModeOneRowSelectionMapsToThatRowOnly() {
        val root = Files.createTempDirectory("orilumn-table-review-one-row").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks)
            val deleted = lines(card).filterNot { it.contains("乙二") }.joinToString("\n")
            assertTrue(commit(host, chapter, range, deleted, pos) is TextEditOutcome.Success)

            val landedReview = runBlocking { host.switchReviewMode(true, chapter, 0) }!!
            val reviewText = body(host, chapter)
            val hit = reviewText.indexOf("甲三")
            assertTrue("审阅空间应仍有甲三：$reviewText", hit >= 0)
            val one = host.textEditBlocks(chapter, hit, hit + 1)
            assertNotNull("审阅空间里点一行应拿得到块", one)
            assertEquals("审阅空间里点一行只应拿到那一行", 1, one!!.size)
            assertTrue("拿到的应是表格行", one.single().table != null)
        } finally {
            host.close()
        }
    }

    /**
     * 审阅视图里拖选改过的表格中的**一行**：卡片只列那一行，确认后只改这一行，
     * 其余行（连同上一次改过的行）原样保留，仍是一条整表记录。
     */
    @Test
    fun reviewModeRowCardEditsOnlyThatRow() {
        val root = Files.createTempDirectory("orilumn-table-review-row-card").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks)
            val firstEdit = card.replace("乙二", "乙二改")
            assertTrue(commit(host, chapter, range, firstEdit, pos) is TextEditOutcome.Success)

            val reviewPos = runBlocking { host.switchReviewMode(true, chapter, 0) }!!
            assertNotNull("应能切到审阅空间", reviewPos)
            val reviewText = body(host, chapter)
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            val hit = reviewText.indexOf("丙四")
            assertTrue("审阅空间应仍有丙四：$reviewText", hit >= 0)
            val row = host.textEditBlocks(chapter, hit, hit + 1)!!.single()
            val scope = host.tableRowScope(chapter, revision.id, row.start, row.endExclusive)
            assertNotNull("拖选改过的表格里的一行应得到行范围", scope)
            assertEquals("应是改完那张表的最后一行", 3, scope!!.firstRow)
            assertEquals("只应有一行", 3, scope.lastRow)

            val rowCard = tableRowCardOf(revision, scope)!!
            val shown = rowCardText(rowCard)
            assertEquals("卡片只应列出拖到的那一行", "| 甲四 | 乙四 | 丙四 |", shown)

            // 只给这一行加一格：整张表铺不成矩形 ⇒ 拒绝（校验仍按整张表）。
            val wide = InlineMarkup.parse(shown.replace("丙四", "丙四 | 多一"))
            val rejected = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = revision.charStart,
                    endExclusive = revision.charEnd,
                    text = wide.text,
                    styles = null,
                    currentPage = reviewPos,
                    blocks = wide.segments,
                    rowScope = scope,
                )
            }
            assertTrue("整表铺不成矩形应拒绝：$rejected", rejected is TextEditOutcome.Rejected)
            assertEquals("拒绝后审阅空间不变", reviewText, body(host, chapter))

            val parsed = InlineMarkup.parse(shown.replace("乙四", "乙四改"))
            assertTrue("卡片文字应能解析：${parsed.errors}", parsed.ok)
            val outcome = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = revision.charStart,
                    endExclusive = revision.charEnd,
                    text = parsed.text,
                    styles = null,
                    currentPage = reviewPos,
                    blocks = parsed.segments,
                    rowScope = scope,
                )
            }
            assertTrue("只改一行应成功：$outcome", outcome is TextEditOutcome.Success)

            val fresh = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            assertTrue("上一次改的字应保留：${fresh.editedText}", fresh.editedText.contains("乙二改"))
            assertTrue("这次改的字应落库：${fresh.editedText}", fresh.editedText.contains("乙四改"))
            assertTrue(
                "其余行应原样：${fresh.editedText}",
                fresh.editedText.contains("甲三") && fresh.editedText.contains("丙一"),
            )
            assertEquals("仍应是四行", 4, fresh.editedBlocks.size)
            assertEquals("行来源应仍是原书四行", listOf(0, 1, 2, 3), fresh.rowSources)

            val back = runBlocking { host.switchReviewMode(false, chapter, 0) }!!
            host.pageLines(back)
            var normalText = body(host, chapter)
            var waited = 0
            while (normalText.isEmpty() && waited < 2_000) {
                Thread.sleep(20)
                waited += 20
                host.pageLines(back)
                normalText = body(host, chapter)
            }
            assertTrue("两次改动都应在正文里", normalText.contains("乙二改") && normalText.contains("乙四改"))
            assertTrue(
                "四行都在",
                normalText.contains("甲二") && normalText.contains("甲三") && normalText.contains("甲四"),
            )
        } finally {
            host.close()
        }
    }

    /** 审阅视图里把行范围卡片删空 = 删掉这一行，不是删掉整张表。 */
    @Test
    fun reviewModeRowCardClearingDeletesOnlyThatRow() {
        val root = Files.createTempDirectory("orilumn-table-review-row-clear").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val (range, blocks) = tableBlocks(host, chapter, before)
            val card = cardText(before, blocks).replace("乙二", "乙二改")
            assertTrue(commit(host, chapter, range, card, pos) is TextEditOutcome.Success)

            val reviewPos = runBlocking { host.switchReviewMode(true, chapter, 0) }!!
            val reviewText = body(host, chapter)
            val revision = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            val hit = reviewText.indexOf("丙三")
            val row = host.textEditBlocks(chapter, hit, hit + 1)!!.single()
            val scope = host.tableRowScope(chapter, revision.id, row.start, row.endExclusive)!!

            val outcome = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = revision.charStart,
                    endExclusive = revision.charEnd,
                    text = "",
                    styles = null,
                    currentPage = reviewPos,
                    blocks = listOf(InlineMarkup.Segment("", null)),
                    rowScope = scope,
                )
            }
            assertTrue("把这一行删空应成功：$outcome", outcome is TextEditOutcome.Success)
            val fresh = runBlocking { host.textRevisionsForChapter(chapter) }
                .first { it.editedBlocks.firstOrNull()?.startsWith("T") == true }
            assertFalse("被删的那一行应消失：${fresh.editedText}", fresh.editedText.contains("甲三"))
            assertEquals("表还在（少一行）", 3, fresh.editedBlocks.size)
            assertTrue(
                "其余行应原样：${fresh.editedText}",
                fresh.editedText.contains("甲一") && fresh.editedText.contains("甲二") &&
                    fresh.editedText.contains("甲四"),
            )
        } finally {
            host.close()
        }
    }

    /** 行范围卡片在 UI 里的显示文字（与 `editCardOf` 同一条换算）。 */
    private fun rowCardText(card: orilumn.reader.engine.revision.TableRowCard): String =
        InlineMarkup.show(
            orilumn.reader.engine.revision.RevisionBlocks.cardSegments(card.text, card.blocks)!!,
            InlineMarkup.fromStored(card.styles.orEmpty()),
        )

    /** 普通改字在审阅空间里看得见红字与绿字，切回正常视图逐字复原。 */
    @Test
    fun reviewModeShowsTextMarksAndSwitchesBack() {
        val root = Files.createTempDirectory("orilumn-review-text").toFile()
        val host = openHost(writeBook(root), root)
        try {
            val pos = runBlocking { host.open() }!!
            val chapter = pos.chapter
            val before = body(host, chapter)
            val start = before.indexOf("表格后面的段落")
            assertTrue(start >= 0)
            val old = "表格后面的段落"
            val next = "别的文字"
            val outcome = runBlocking {
                host.commitTextEdit(
                    chapter = chapter,
                    start = start,
                    endExclusive = start + old.length,
                    text = next,
                    styles = null,
                    currentPage = pos,
                    blocks = listOf(InlineMarkup.Segment(next, null)),
                )
            }
            assertTrue("改字应成功：$outcome", outcome is TextEditOutcome.Success)
            val normalText = body(host, chapter)
            assertTrue(normalText.contains(next))
            assertFalse(host.isReviewMode())

            val landed = runBlocking { host.switchReviewMode(true, chapter, 0) }
            assertNotNull("应能切到审阅空间", landed)
            assertTrue(host.isReviewMode())
            val reviewText = body(host, chapter)
            assertTrue("原文的字还留在审阅空间", reviewText.contains("段落"))
            assertTrue("新写的字也在审阅空间", reviewText.contains("别") && reviewText.contains("文"))
            assertTrue("审阅空间比正常视图长（原文一字不删）", reviewText.length > normalText.length)
            val marks = host.reviewMarks(chapter)
            assertTrue("应同时有红色删除与绿色新增", marks.any { it.deleted } && marks.any { !it.deleted })
            assertTrue("痕迹区间有效", marks.all { it.endExclusive > it.start })

            val back = runBlocking { host.switchReviewMode(false, chapter, 0) }
            assertNotNull("应能切回正常视图", back)
            assertFalse(host.isReviewMode())
            host.pageLines(back!!)   // 落位后按宿主真实读取路径触发本章重新准备
            // 文本投影按设计在后台回填（选区/复制用），刚切完可能还没就绪：像真实界面那样等它一拍。
            var restored = body(host, chapter)
            var waited = 0
            while (restored.isEmpty() && waited < 2_000) {
                Thread.sleep(20)
                waited += 20
                host.pageLines(back)
                restored = body(host, chapter)
            }
            assertEquals("切回后正文复原", normalText, restored)
            assertTrue("正常视图没有审阅痕迹", host.reviewMarks(chapter).isEmpty())
        } finally {
            host.close()
        }
    }
}
