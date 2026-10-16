package orilumn.reader.engine.skia

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ChapterPaginationTable
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· 图片**密度换算与宽高比一致性**回归（density=2.4，平板实测倍率）。
 *
 * 两代现场（2026-10-16）：
 * 1. 资源固有像素不乘 density ⇒ 915px 宽的图在 1590px 画布上只画 915px（比 calibre/浏览器小
 *    一圈）——尺寸 bug；
 * 2. 只在部分调用点乘 density（增量窗 `PartialDrawableLayout` 漏传，取默认 1f）⇒
 *    `pageImages` 的**宽**×1 而 shape 的**高**×2.4 ⇒ 图被竖向拉伸、宽宽比坏——量画漂移。
 *
 * 本测试同时锁住两条不变量（三条生产路径全覆盖）：
 * - **尺寸**：density≠1 时 `pageImages.widthPx` 必须等于「固有 px × density」按 §10.4 约束后的值
 *   （拿掉 ×density ⇒ 宽回退到原图 px，宽度断言红）；
 * - **宽高比**：`widthPx / (yBottom − yTop)` 必须贴近原图比例（任一路径漏 density ⇒ 高宽比
 *   直接掉到 1/2.4 倍量级，比例断言红）。
 *
 * 路径：canonical（`fullLayout`）、增量（`incrementalLayoutForPage`，曾漏 density 的那条）、
 * 临时（`shapeTempPageForward`）。图片无 width/height 属性 ⇒ 必走资源 bounds 分支。
 */
class ImageDensityAspectConsistencyTest {

    private val intrinsicW = 1122
    private val intrinsicH = 735
    private val density = 2.4f
    private val chapterHref = "OEBPS/Text/Ch01.htm"

    /** 版心宽 > 固有×density（2693），保证不被 `max-width:100%` 截断，能直接读出尺寸换算。 */
    private val contentW = 3000
    private val contentH = 2560

    private val html = """
        <html><body>
        <p>图前的正文段落，让图落在块序靠前的位置（增量窗 4 页内必含它）。</p>
        <p><img src="CH01_F02_Isakova.png" /></p>
        <p>图后的正文段落。</p>
        </body></html>
    """.trimIndent()

    private val authorCss = "img { display: block; margin: 1em auto; }"

    private val png: ByteArray by lazy {
        val surface = Surface.makeRasterN32Premul(intrinsicW, intrinsicH)
        surface.canvas.drawRect(
            Rect.makeWH(intrinsicW.toFloat(), intrinsicH.toFloat()),
            Paint().apply { color = Color.makeRGB(200, 30, 30) },
        )
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)!!.bytes
    }

    private class StubReader(private val bytes: ByteArray) : EpubResourceReader {
        override fun entries(): Sequence<String> = emptySequence()
        override fun readText(path: String): String? = null
        override fun readBytes(path: String): ByteArray? = bytes
    }

    private val profile by lazy {
        TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"), density)
    }

    private val layouter: BoxChapterLayouter by lazy {
        BoxChapterLayouter(
            themeSheet = themeSheetFromProfile(profile),
            imageLoader = ImageLoader(StubReader(png)),
        ).also { it.bindChapterContext(chapterHref) }
    }

    private fun root() = HtmlTreeConverter().convert(html)!!
    private fun bundle() = CssBundle(listOf(authorCss))

    /** §10.4 后的期望 used 宽：round(1122×2.4)=2693，不触任何约束。 */
    private val expectedW = Math.round(intrinsicW * density)
    private val expectedRatio = intrinsicW.toFloat() / intrinsicH

    /** 宽必须等于密度换算值（尺寸不变量）；高宽比必须贴近原图（比例不变量）。 */
    private fun assertImageGeometry(label: String, img: PageImage) {
        val h = img.yBottom - img.yTop
        assertEquals("$label 宽未按 density 换算（尺寸 bug 回归）", expectedW, img.widthPx)
        val ratio = img.widthPx.toFloat() / h
        val dev = kotlin.math.abs(ratio - expectedRatio) / expectedRatio
        assertTrue(
            "$label 宽高比坏了：w=${img.widthPx} h=$h ratio=$ratio 期望≈$expectedRatio（量画 density 漂移）",
            dev < 0.01f,
        )
    }

    private fun firstImage(images: List<PageImage>): PageImage =
        images.firstOrNull() ?: error("窗口内没有任何页图")

    @Test
    fun `intrinsic 资源分支按 density 换算且比例不变`() {
        val img = walk(root().children).first { it.tag == "img" }
        val raw = NormalFlowLayout.intrinsicSizeOf(img, ImageLoader(StubReader(png)), chapterHref)
        assertEquals("density=1 保持原值（默认既有测试不变）", intrinsicW to intrinsicH, raw)
        val scaled = NormalFlowLayout.intrinsicSizeOf(img, ImageLoader(StubReader(png)), chapterHref, density)
        assertEquals(
            "density=2.4：固有 px 必须整宽整高同乘（比例不变）",
            Math.round(1122 * density) to Math.round(735 * density),
            scaled,
        )
    }

    @Test
    fun `canonical 页图：density=2·4 尺寸放大且宽高比不变`() {
        val prep = layouter.prepare(root(), bundle(), profile, contentW, contentH)
        val canonical = layouter.fullLayout(prep, profile, contentW, contentH)
        val rb = canonical.layout as LayoutReadback
        val img = firstImage(rb.pageImages(0, canonical.layout.lineCount))
        assertImageGeometry("canonical", img)
    }

    @Test
    fun `增量页图：density=2·4 尺寸放大且宽高比不变（曾漏 density 的路径）`() {
        val prep = layouter.prepare(root(), bundle(), profile, contentW, contentH)
        val canonical = layouter.fullLayout(prep, profile, contentW, contentH)
        val table = ChapterPaginationTable.fromSlices(
            chapterIndex = 0, paramHash = 0L,
            slices = canonical.slices, totalBlocks = prep.totalBlocks, totalChars = prep.totalChars,
        )
        val light = layouter.prepareLight(root(), bundle(), profile, contentW, ChapterStructureCache(), contentH)
        val product = layouter.incrementalLayoutForPage(
            prepare = light, profile = profile, contentW = contentW, contentH = contentH,
            table = table, targetPage = 0, pagesToShape = 4,
        )
        val rb = product.layout as LayoutReadback
        val img = firstImage(rb.pageImages(0, product.layout.lineCount))
        assertImageGeometry("incremental", img)
    }

    @Test
    fun `临时页图：density=2·4 尺寸放大且宽高比不变`() {
        val light = layouter.prepareLight(root(), bundle(), profile, contentW, ChapterStructureCache(), contentH)
        val imgBlock = (0 until light.totalBlocks).first { hasImgDescendant(light.block(it).el) }
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, imgBlock, null, startLine = 0)!!
        val slice = fwd.page.slice
        val rb = fwd.page.layout as LayoutReadback
        val img = firstImage(rb.pageImages(slice.firstLine, slice.lastLineExclusive))
        assertImageGeometry("temp", img)
    }

    private fun walk(els: List<MarkupElement>): Sequence<MarkupElement> = sequence {
        for (el in els) {
            yield(el)
            yieldAll(walk(el.children))
        }
    }

    private fun hasImgDescendant(el: MarkupElement?): Boolean {
        if (el == null) return false
        if (el.tag == "img") return true
        return el.children.any { hasImgDescendant(it) }
    }
}
