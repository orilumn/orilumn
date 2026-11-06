package orilumn.reader.engine

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LayoutReadback
import orilumn.reader.engine.skia.LineAligner
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.engine.skia.linePlacement
import orilumn.reader.engine.text.TypographicProfile
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 排版层（上）· **行内图**（`<p>字<img>字</p>`）的落图与占位隐藏。
 *
 * 缺陷现场（GIMP 手册）：正文里的 GUI 图标全部显示成字体画的「虚线 obj 方框」——
 * 文本抽取只留一个 U+FFFC 槽位，行高按图抬了，但没有任何像素管线按槽位贴真图，
 * 也没有人把占位字形隐掉。本测试锁两条不变量：
 *
 * 1. **落图**：页面图几何在 U+FFFC 槽位上产出 [PageImage]，宽高与
 *    `replacedUsedSize` 同口径、x 与落墨同源（[linePlacement]，量画同源）；
 * 2. **占位隐藏**：同一槽位的 U+FFFC 进 `imgHidden`（占宽不落墨），且**只在真能贴图时**
 *    才隐（缺 loader/href/src ⇒ 保持旧行为，占位字形留着，不能出现「字没了图也没有」）。
 *
 * 重路径（`fullLayout`）与轻路径（`prepareLight` + 临时页）各验一次：两路共用
 * [orilumn.reader.engine.skia.WindowedBookLayout.pageImages]，但行窗来源不同。
 */
class InlineImageFlowTest {

    private val contentW = 600
    private val contentH = 800
    private val chapterHref = "OEBPS/Text/ch01.xhtml"

    /** 内联图标：属性宽高 16×16 ⇒ 不依赖资源解码，也不受版心约束。 */
    private val html = """
        <html><body>
        <p>点击<img src="icon.png" width="16" height="16" />按钮继续。</p>
        </body></html>
    """.trimIndent()

    /** 同一段两张图（不同尺寸）：文档序 = U+FFFC 序 = 落图序，锁「图贴到别的字上」的错位。 */
    private val twoImagesHtml = """
        <html><body>
        <p>前<img src="a.png" width="10" height="10" />中<img src="b.png" width="20" height="20" />后</p>
        </body></html>
    """.trimIndent()

    private val png: ByteArray by lazy {
        val surface = Surface.makeRasterN32Premul(16, 16)
        surface.canvas.drawRect(Rect.makeWH(16f, 16f), Paint().apply { color = Color.makeRGB(30, 120, 200) })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)!!.bytes
    }

    private class StubReader(private val bytes: ByteArray) : EpubResourceReader {
        override fun entries(): Sequence<String> = emptySequence()
        override fun readText(path: String): String? = null
        override fun readBytes(path: String): ByteArray? = bytes
    }

    private val profile by lazy {
        TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"))
    }

    private fun layouter(withImages: Boolean = true): BoxChapterLayouter =
        BoxChapterLayouter(
            themeSheet = themeSheetFromProfile(profile),
            imageLoader = if (withImages) ImageLoader(StubReader(png)) else null,
        ).also { it.bindChapterContext(chapterHref) }

    private fun heavy(markup: String, withImages: Boolean = true): LayoutReadback {
        val l = layouter(withImages)
        val prep = l.prepare(HtmlTreeConverter().convert(markup)!!, CssBundle(emptyList()), profile, contentW, contentH)
        val product = l.fullLayout(prep, profile, contentW, contentH)
        return product.layout as LayoutReadback
    }

    private fun linesWithSlot(rb: LayoutReadback): List<DrawLine> =
        rb.skiaLineWindow()!!.values.filter { it.text.contains('\uFFFC') }

    private fun firstLineWithSlot(rb: LayoutReadback): DrawLine = linesWithSlot(rb).firstOrNull()
        ?: error("行窗里没有 U+FFFC 槽位")

    /** 槽位在叶文本里的下标（与行下标无关，页面图几何按槽位定位）。 */
    private fun slotOf(line: DrawLine): Int = line.text.indexOf('\uFFFC')

    private fun imagesOnSlotPage(rb: LayoutReadback): List<PageImage> =
        rb.pageImages(0, rb.skiaLineWindow()!!.keys.max() + 1)

    @Test
    fun `行内图进页面图_尺寸与属性同口径`() {
        val rb = heavy(html)
        val imgs = imagesOnSlotPage(rb)
        assertEquals("含行内图的段落必须产出恰好一张页面图", 1, imgs.size)
        val im = imgs[0]
        assertEquals("icon.png", im.src)
        assertEquals("章内相对路径不许丢", chapterHref, im.chapterHref)
        assertEquals(16, im.widthPx)
        assertEquals(16, im.heightPx)
    }

    @Test
    fun `行内图 x 与槽位落墨同源`() {
        val rb = heavy(html)
        val line = firstLineWithSlot(rb)
        val im = imagesOnSlotPage(rb).single()
        // 与绘制同一入口（linePlacement）求槽位 x：任何只改一侧的实现都会在这里错位。
        val placed = linePlacement(LineAligner(), line, line.range.first, line.range.last + 1)
        val slot = slotOf(line) - line.range.first
        assertTrue("槽位必须落在本行 x 表内：slot=$slot xs=${placed.xs.size}", slot in placed.xs.indices)
        assertEquals(
            "页面图的 x 必须取槽位落墨 x（量画同源）",
            line.xLeft + placed.xs[slot].roundToInt(),
            im.xLeft,
        )
        assertTrue("图必须落在本行纵向范围附近：img=${im.yTop}..${im.yBottom} line=${line.yTop}..${line.yBottom}",
            im.yTop >= line.yTop && im.yBottom <= line.yBottom)
    }

    @Test
    fun `占位字形被隐掉_但占宽保留`() {
        val rb = heavy(html)
        val line = firstLineWithSlot(rb)
        val slot = slotOf(line)
        assertTrue(
            "U+FFFC 槽位必须在 imgHidden 里（否则字体画出虚线 obj 方框）：$slot / ${line.imgHidden}",
            line.imgHidden.any { slot in it },
        )
        // 槽位字符仍在文本里（断行/点按坐标不变），只是不落墨。
        assertTrue("槽位字符不得从文本里删掉（占宽保留）", line.text.length > 1)
    }

    @Test
    fun `两张行内图按文档序落位`() {
        val rb = heavy(twoImagesHtml)
        val imgs = imagesOnSlotPage(rb)
        assertEquals("两张行内图都要落图", 2, imgs.size)
        assertEquals("先文档序的 a.png", "a.png", imgs[0].src)
        assertEquals("后文档序的 b.png", "b.png", imgs[1].src)
        assertEquals(10, imgs[0].widthPx)
        assertEquals(20, imgs[1].widthPx)
        assertTrue("图必须按文档序从左到右：${imgs.map { it.xLeft }}", imgs[0].xLeft < imgs[1].xLeft)
        val line = firstLineWithSlot(rb)
        assertEquals("两个槽位都要隐占位", 2, line.imgHidden.size)
    }

    @Test
    fun `缺 loader 时保留占位字形且不落图`() {
        val rb = heavy(html, withImages = false)
        assertTrue("没有 loader 就没有页面图", imagesOnSlotPage(rb).isEmpty())
        val line = firstLineWithSlot(rb)
        assertTrue(
            "不能隐掉占位却没图可贴（读者会看到一片空白）：${line.imgHidden}",
            line.imgHidden.isEmpty(),
        )
    }

    /** 没有 `src`（空图/坏标记）：贴不出图，占位字形必须留着（不能变成空白）。 */
    @Test
    fun `缺 src 时保留占位字形且不落图`() {
        val markup = "<html><body><p>点击<img width=\"16\" height=\"16\" />按钮。</p></body></html>"
        val rb = heavy(markup)
        assertTrue("没有 src 就不落图", imagesOnSlotPage(rb).isEmpty())
        assertTrue("没有 src 就不能隐占位", firstLineWithSlot(rb).imgHidden.isEmpty())
    }

    /** CSS 宽高可以替代属性宽高（尺寸不来自资源）⇒ 照样落图并隐占位。 */
    @Test
    fun `CSS 宽高决定行内图尺寸`() {
        val markup = """
            <html><body>
            <p>前<img src="bar.png" style="width:30px;height:12px" />后</p>
            </body></html>
        """.trimIndent()
        val rb = heavy(markup)
        val im = imagesOnSlotPage(rb).single()
        assertEquals(30, im.widthPx)
        assertEquals("高度取 yBottom − yTop", 12, im.yBottom - im.yTop)
        assertTrue("CSS 尺寸够用 ⇒ 隐占位", firstLineWithSlot(rb).imgHidden.isNotEmpty())
    }

    @Test
    fun `临时页同样落图并隐占位`() {
        val l = layouter()
        val prep = l.prepare(
            HtmlTreeConverter().convert(html)!!, CssBundle(emptyList()), profile, contentW, contentH,
        )
        val light = l.prepareLight(
            HtmlTreeConverter().convert(html)!!, CssBundle(emptyList()), profile, contentW,
            ChapterStructureCache(), contentH,
        )
        // 锚页就是含图的那一块（塑形文本里带 U+FFFC）；前向临时页从它起排。
        val imgBlock = (0 until light.totalBlocks).first { b ->
            l.tempShape(null, light, b, profile).shapeText.contains('\uFFFC')
        }
        val page = l.shapeTempPageForward(light, profile, contentW, contentH, imgBlock, null, 0)!!
        val rb = page.page.layout as LayoutReadback
        val slice = page.page.slice
        val imgs = rb.pageImages(slice.firstLine, slice.lastLineExclusive)
        assertFalse("临时页必须落行内图（与磁盘表同一像素管线）", imgs.isEmpty())
        assertEquals("icon.png", imgs.single().src)
        val line = rb.skiaLineWindow()!!.values.first { it.text.contains('\uFFFC') }
        assertTrue("临时页的占位字形同样要隐", line.imgHidden.any { slotOf(line) in it })
    }
}
