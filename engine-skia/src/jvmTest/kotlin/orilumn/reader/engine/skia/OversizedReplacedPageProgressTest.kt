package orilumn.reader.engine.skia

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.LightPrepare
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 临时表「游标永不停滞」回归：**整宽插图高过版心时，临时表必须照样前进，不得产出零前进退化页**。
 *
 * ## 现场（2026-10-11，桌面全屏，真书《Kotlin in Action》ch7 §1.4 图 1.2）
 *
 * 版心 4920x2560（设备 px；全屏横向 1.92:1），`modern.css` 的
 * `.orilumn-fullwidth-image{width:100%}` 把内在 1122x735 的 `CH01_F02_Isakova.png` 拉到 4920 宽
 * ⇒ 盒高 4920×735/1122 = **3222 > 2560**（超页 26%）。该 img 块字符区间为空，于是
 * `shapeTempPageForward` 在**页首行**（k0=0）就撞上 `acc + h > contentH`，切点落在
 * `(块, 行 0)` = 入参原点 ⇒ `charStart == charEnd` 的退化页，`next` 与 origin 相同 ⇒
 * 前向预填水位永不推进（窗口塞满同页副本），翻页原地不动。真机日志：
 * `FLIP … slice[0,1)char[17667,17667)blk[201,202) … fwd=[b201[17667,17667),b201[17667,17667)]`
 * 然后 `tap-flip result dir=1 landed ch=7 char=17667`（原地）。
 *
 * 重路径（磁盘表）不会停滞：`Paginator.fillWholeLines` 末行明写
 * 「`if (end == startLine) end = startLine + 1` —— 单行高过整页时至少推进一行（cursor never stalls）」。
 * 两代口径分叉就是本 bug；这里把同一判据锁进临时表（前向 / 后向两侧）。
 *
 * 撕裂（页底裁掉超页部分）是**有意**口径，与重路径 `applyBreakInsideAvoid`
 * 「oversized blocks are allowed to tear」一致——本测试只锁「必须有字符前进」，不锁可见像素。
 */
@RunWith(Parameterized::class)
class OversizedReplacedPageProgressTest(private val variant: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "breaker={0}")
        fun variants() = breakerVariantParams()
    }

    @Before fun applyVariant() = applyBreakerVariant(variant)

    @After fun resetVariant() = resetBreakerVariant()

    /** 全屏横向版心（设备 px）：图被拉到 4920 宽 ⇒ 高 3222 > 版心高 2560。 */
    private val contentW = 4920
    private val contentH = 2560

    /** `CH01_F02_Isakova.png` 真实二进制尺寸。 */
    private val intrinsicW = 1122
    private val intrinsicH = 735

    private val chapterHref = "OEBPS/Text/Ch01.htm"

    /** 版心 4920 下拉满宽后的盒高（= round(4920 × 735/1122) = 3223；真机 FLIP 日志记 ~3222）。 */
    private val fullWidthBoxH = 3223

    private val html = """
        <html><body>
        <p>Kotlin 标准库里的扩展函数（图前的正文，用来把锚点放在图之前）。</p>
        <p><img src="CH01_F02_Isakova.png" /></p>
        <p>图 1.2 在 IDE 里查看 Kotlin 源码。</p>
        <p>图后的正文段落，用于确认图页之后的页仍有内容。</p>
        </body></html>
    """.trimIndent()

    /** 原书未给该 img width/max-width（CSS）⇒ `ChapterPreprocessor` 打 `orilumn-fullwidth-image`。 */
    private val authorCss = "img { display: block; margin: 1em auto; }"

    /** 生成一张 `intrinsicW x intrinsicH` 纯色 PNG（不依赖仓库静态资源）。 */
    private val png: ByteArray by lazy {
        val surface = Surface.makeRasterN32Premul(intrinsicW, intrinsicH)
        surface.canvas.drawRect(
            Rect.makeWH(intrinsicW.toFloat(), intrinsicH.toFloat()),
            Paint().apply { color = Color.makeRGB(200, 30, 30) },
        )
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)!!.bytes
    }

    /** 任意路径都返回同一张 PNG 的最小 reader（`ImageLoader` 只经 `readBytes` 探尺寸）。 */
    private class StubReader(private val bytes: ByteArray) : EpubResourceReader {
        override fun entries(): Sequence<String> = emptySequence()
        override fun readText(path: String): String? = null
        override fun readBytes(path: String): ByteArray? = bytes
    }

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"))

    private fun layouter(): BoxChapterLayouter = BoxChapterLayouter(
        themeSheet = themeSheetFromProfile(profile()),
        imageLoader = ImageLoader(StubReader(png)),
    ).also { it.bindChapterContext(chapterHref) }

    private fun prepare(): LightPrepare {
        val root = HtmlTreeConverter().convert(html)!!
        val preprocessed = ChapterPreprocessor.preprocess(root, listOf(LightCssParser().parse(authorCss)))
        return layouter().prepareLight(
            preprocessed, CssBundle(listOf(authorCss)), profile(), contentW,
            orilumn.reader.engine.ChapterStructureCache(), contentH,
        )
    }

    /** 块序里第一个「含 img」的叶（真书里 `<p><img/></p>` 归并成独图块）。 */
    private fun imageBlock(p: LightPrepare): Int =
        (0 until p.totalBlocks).first { hasImgDescendant(p.block(it).el) }

    private fun hasImgDescendant(el: MarkupElement?): Boolean {
        if (el == null) return false
        if (el.tag == "img") return true
        return el.children.any { hasImgDescendant(it) }
    }

    @Test
    fun `触发条件成立：整宽图盒高超过版心高`() {
        val leaf = prepare().let { p -> p.block(imageBlock(p)) }
        assertEquals("整宽盒高应为 round(4920×735/1122)", fullWidthBoxH, leaf.replaceableHeight)
        assertTrue(
            "触发条件是「单行(替换块)高过整页」：replaceableHeight=${leaf.replaceableHeight} contentH=$contentH",
            leaf.replaceableHeight > contentH,
        )
    }

    @Test
    fun `前向塑形超高图块：必须前进且图在该页上`() {
        val p = prepare()
        val b = imageBlock(p)
        val fwd = layouter().shapeTempPageForward(p, profile(), contentW, contentH, b, null, startLine = 0)!!
        val s = fwd.page.slice
        // 关键不变式：塑形点严格越过入参原点 (b, 0)（修复前 next == (b, 0)，退化页）。
        assertTrue(
            "零前进退化页：origin=($b,0) next=(${fwd.nextBlock},${fwd.nextLine}) char[${s.charStart},${s.charEnd})",
            fwd.nextBlock > b || (fwd.nextBlock == b && fwd.nextLine > 0),
        )
        assertTrue("退化页无行：line[${s.firstLine},${s.lastLineExclusive})", s.lastLineExclusive > s.firstLine)
        // 用户可见断言：该页的图片窗口非空（真机症状是「图不在页上」）。
        val rb = fwd.page.layout as orilumn.reader.engine.skia.LayoutReadback
        assertTrue(
            "图不在该页的绘制窗口内：firstLine=${s.firstLine} pageEnd=${s.lastLineExclusive} lineCount=${fwd.page.layout.lineCount}",
            rb.pageImages(s.firstLine, s.lastLineExclusive).isNotEmpty(),
        )
    }

    @Test
    fun `前向连续塑形不卡：水位单调前进并越过图块`() {
        val p = prepare()
        val l = layouter()
        val start = imageBlock(p)
        var block = start
        var line = 0
        val visited = ArrayList<Pair<Int, Int>>()
        repeat(6) {
            if (block >= p.totalBlocks) return@repeat
            val fwd = l.shapeTempPageForward(p, profile(), contentW, contentH, block, null, line)!!
            visited.add(block to line)
            val (nb, nl) = fwd.nextBlock to fwd.nextLine
            assertTrue(
                "第 ${visited.size} 步未前进：($block,$line) → ($nb,$nl) 已访问=$visited",
                nb > block || (nb == block && nl > line),
            )
            block = nb
            line = nl
        }
        assertTrue("水位没越过图块：visited=$visited", visited.size >= 2)
        assertTrue("水位没越过图块：visited=$visited end=($block,$line)", block > start)
    }

    @Test
    fun `后向塑形：页底就是超高图时不丢块、图在该页上`() {
        val p = prepare()
        val b = imageBlock(p)
        val back = layouter().shapeTempPageBackward(p, profile(), contentW, contentH, b + 1, -1, null)!!
        val s = back.slice
        // 修复前 overflowLine == maxLine-1 ⇒ startLine == maxLine ⇒ pageFirstLine 越出本页行窗
        //（pageFirstLine=1 而行窗只有 1 行）⇒ 回读 pageImages 空 ⇒ 图被静默跳过。
        assertTrue(
            "页首行索引越出本页行窗：firstLine=${s.firstLine} lineCount=${back.layout.lineCount}",
            s.firstLine < back.layout.lineCount,
        )
        assertTrue("后向页无行：line[${s.firstLine},${s.lastLineExclusive})", s.lastLineExclusive > s.firstLine)
        assertTrue("页首块区间不含图块：blk=[${s.blockStart},${s.blockEndExclusive}) 图块=$b", b in s.blockStart until s.blockEndExclusive)
        val rb = back.layout as orilumn.reader.engine.skia.LayoutReadback
        assertTrue(
            "图不在该页的绘制窗口内：firstLine=${s.firstLine} pageEnd=${s.lastLineExclusive} lineCount=${back.layout.lineCount}",
            rb.pageImages(s.firstLine, s.lastLineExclusive).isNotEmpty(),
        )
    }
}
