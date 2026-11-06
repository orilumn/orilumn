package orilumn.reader.engine

import okio.Path.Companion.toPath
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.skia.PagedLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当前页快照的**取数—落盘—回读**闭环（JVM 真排版，不是假数据）。
 *
 * 锁三件事：
 * 1. **编解码不丢字段**：快照是数据类，`assertEquals(原, 解码后)` 一条断言就覆盖了
 *    `DrawLine` 的四十来个属性（颜色/字体/装饰/注音/裁剪/行内盒…）——只要漏序列化一个字段，
 *    真排版出来的那一页就必然对不上。
 * 2. **回读同口径**：`PersistedPageLayout` 对同一页给出的行、盒背景、插图、表格行与行几何，
 *    必须与真排版布局逐项相同（绘制/选区/点按都靠这几项，差一项就是画错）。
 * 3. **键不符即 miss**：参数、页号、版心、样式任一不同都不得命中——绝不拿旧页冒充新页。
 */
class PageSnapshotTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 700
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0; }
            p { margin: 16px 0; }
            p.fig { margin: 44px 0 30px 0; }
            h2 { font-size: 22px; margin: 34px 0 14px 0; }
            pre { margin: 20px 0; padding: 12px 0 18px 0; white-space: pre; font-family: monospace; font-size: 14px; }
            div.box { padding: 14px 8px 22px 8px; border-top: 5px solid #000; border-bottom: 7px solid #000; }
            .em { color: #c00; font-weight: 700; text-decoration: underline; }
            """.trimIndent(),
        ),
    )

    private fun para(text: String) = "<p class=\"body\">\n$text\n</p>"

    private fun chapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>快照</h2>
          ${para("开头段落。".repeat(24))}
          <p class="body">含<b>粗体</b>与<span class="em">红色下划线</span>的一行，再补一点字把这一行排满看清楚。</p>
          <div class="box">
            ${para("盒内第一段。".repeat(14))}
            ${para("盒内第二段。".repeat(14))}
          </div>
          <p class="fig"><img src="fig.png" width="200" height="160"/></p>
          ${para("图后段落。".repeat(18))}
          ${para("结尾段落。".repeat(20))}
        </body></html>
        """.trimIndent(),
    )!!

    private fun canonical(): ChapterLayouter.ChapterLayoutProduct {
        val heavy = layouter.prepare(chapter(), css, profile, contentW, contentH)
        return layouter.fullLayout(heavy, profile, contentW, contentH)
    }

    private fun snapshotOf(
        layout: PagedLayout,
        slice: orilumn.reader.engine.paging.PageSlice,
        pageIndex: Int,
        paramHash: Long = 4242L,
    ): PageSnapshot = requireNotNull(
        buildPageSnapshot(
            layout = layout,
            slice = slice,
            pageIndex = pageIndex,
            bookId = 40L,
            chapterIndex = 4,
            paramHash = paramHash,
            appVersion = 7,
            contentWidth = contentW,
            contentHeight = contentH,
            cssHash = 0x5EEDL,
        ),
    ) { "真实排版的一页必须能抓成快照" }

    @Test
    fun `codec round trips a real page`() {
        val product = canonical()
        val layout = product.layout as PagedLayout
        val slice = product.slices[1]
        val snap = snapshotOf(layout, slice, 1)

        val bytes = PageSnapshotCodec.encode(snap)
        val back = PageSnapshotCodec.decode(bytes)

        assertNotNull("快照必须能被解码", back)
        // 数据类全等 = 四十来个绘制属性逐个相等（漏序列化一个字段这里必红）。
        assertEquals(snap, back)
        assertTrue("快照应含本页文本行", snap.lines.isNotEmpty())
        assertEquals(
            "只存本页：行数与页切片一致",
            slice.lastLineExclusive - slice.firstLine,
            snap.lines.size,
        )
    }

    @Test
    fun `restored layout serves the same page readback`() {
        val product = canonical()
        val layout = product.layout as PagedLayout
        val slice = product.slices[1]
        val snap = snapshotOf(layout, slice, 1)
        val restored = PersistedPageLayout(
            requireNotNull(PageSnapshotCodec.decode(PageSnapshotCodec.encode(snap))),
        )

        // 行几何：整段窗口逐行比（页首缺口的帧上下沿也在内）。
        assertEquals(layout.lineCount, restored.lineCount)
        for (i in 0 until layout.lineCount) {
            assertEquals("lineTop[$i]", layout.getLineTop(i), restored.getLineTop(i))
            assertEquals("lineBottom[$i]", layout.getLineBottom(i), restored.getLineBottom(i))
            assertEquals("lineStart[$i]", layout.getLineStart(i), restored.getLineStart(i))
            assertEquals("lineEnd[$i]", layout.getLineEnd(i), restored.getLineEnd(i))
            assertEquals("para[$i]", layout.isParagraphBoundaryLine(i), restored.isParagraphBoundaryLine(i))
            assertEquals("frameTop[$i]", layout.getPageFrameTop(i), restored.getPageFrameTop(i))
            assertEquals("frameBottom[$i]", layout.getPageFrameBottom(i), restored.getPageFrameBottom(i))
            assertEquals("topLead[$i]", layout.getPageTopLead(i), restored.getPageTopLead(i))
        }

        // 绘制模型：本页每一行 / 盒背景 / 插图 / 表格行。
        val window = requireNotNull(layout.skiaLineWindow())
        val restoredWindow = requireNotNull(restored.skiaLineWindow())
        for (i in slice.firstLine until slice.lastLineExclusive) {
            assertEquals("line[$i]", window[i], restoredWindow[i])
        }
        assertEquals(
            layout.pageBackgrounds(slice.firstLine, slice.lastLineExclusive),
            restored.pageBackgrounds(slice.firstLine, slice.lastLineExclusive),
        )
        assertEquals(
            layout.pageImages(slice.firstLine, slice.lastLineExclusive),
            restored.pageImages(slice.firstLine, slice.lastLineExclusive),
        )
        assertEquals(
            layout.tableCellLines(slice.firstLine, slice.lastLineExclusive),
            restored.tableCellLines(slice.firstLine, slice.lastLineExclusive),
        )
    }

    @Test
    fun `key mismatch is a miss and never a repair`() {
        val product = canonical()
        val layout = product.layout as PagedLayout
        val slice = product.slices[1]
        val snap = snapshotOf(layout, slice, 1)

        fun ok(
            paramHash: Long = 4242L,
            pageIndex: Int = 1,
            contentWidth: Int = contentW,
            contentHeight: Int = contentH,
            cssHash: Long = 0x5EEDL,
            layoutVersion: Int = PaginationCacheCodec.LAYOUT_VERSION,
            appVersion: Int = 7,
        ) = snap.matches(
            layoutVersion = layoutVersion,
            appVersion = appVersion,
            bookId = 40L,
            chapterIndex = 4,
            pageIndex = pageIndex,
            paramHash = paramHash,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            cssHash = cssHash,
        )

        assertTrue("同键必须命中", ok())
        assertTrue("参数变了必须 miss", !ok(paramHash = 4243L))
        assertTrue("页号变了必须 miss", !ok(pageIndex = 2))
        assertTrue("版心变了必须 miss", !ok(contentWidth = contentW - 1))
        assertTrue("版心高了必须 miss", !ok(contentHeight = contentH + 1))
        assertTrue("样式变了必须 miss", !ok(cssHash = 0x5EEDL + 1))
        assertTrue("引擎版本变了必须 miss", !ok(layoutVersion = PaginationCacheCodec.LAYOUT_VERSION + 1))
        assertTrue("构建号变了必须 miss", !ok(appVersion = 8))
    }

    @Test
    fun `store keeps exactly one file per book and clears it`() {
        val dir = java.nio.file.Files.createTempDirectory("page-snapshot").toFile()
        try {
            val store = PageSnapshotStore(okio.FileSystem.SYSTEM, dir.absolutePath.toPath())
            val product = canonical()
            val layout = product.layout as PagedLayout
            val first = snapshotOf(layout, product.slices[0], 0)
            val second = snapshotOf(layout, product.slices[1], 1)

            store.write(40L, first)
            store.write(40L, second)
            assertEquals("后写覆盖前写（同一份文件）", second, store.read(40L))

            val bookDir = dir.resolve("page/book_40").listFiles().orEmpty()
            assertEquals("一本书只留一份当前页", 1, bookDir.count { it.isFile })
            assertNull("别的书互不影响", store.read(41L))

            store.clear(40L)
            assertNull("清掉后读不到", store.read(40L))
        } finally {
            dir.deleteRecursively()
        }
    }
}
