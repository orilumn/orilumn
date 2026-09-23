package orilumn.reader.desktop

import orilumn.reader.data.epub.EpubParser
import orilumn.reader.data.epub.ZipEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.LinkTargets
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-c 真书离屏回归（桌面 JVM 全管线：解析→级联→盒子→Skia 断行→分页→DrawLine→落 PNG）。
 *
 * `books/` 语料（IDPF×8＋Gutenberg×3）逐本打开＋翻页＋渲染不断言崩；内嵌字体三对照
 * （明文/混淆/woff）文本与像素双锁；内部链接端到端导航。PNG 落 `build/p5c/` 供人眼复核。
 */
class P5cOffscreenCorpusTest {

    private val viewportW = 760
    private val viewportH = 1000
    private val marginX = 30
    private val marginY = 40
    private val pageW = viewportW + marginX * 2
    private val pageH = viewportH + marginY * 2

    private fun books(): List<File> {
        val all = File("../books").listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        org.junit.Assume.assumeTrue("无语料跳过真书测试", all.isNotEmpty())
        return all
    }

    private fun outDir(): File = File("build/p5c").apply { mkdirs() }

    private fun openHost(book: File, root: File): DesktopReaderHost = runBlocking {
        val store = DesktopShelfStore(root)
        store.load()
        val entry = store.addBook(book.nameWithoutExtension, null, book.absolutePath, null)
        DesktopReaderHost(
            bookFile = entry.filePath,
            bookId = entry.id,
            store = store,
            settings = ReaderSettings.DEFAULT,
            density = 1f,
            viewportW = viewportW,
            viewportH = viewportH,
            fontLibrary = store.fontLibrary(),
        )
    }

    /** 首屏起沿 adjacent 走 [maxPages] 页，返回访问过的 (chapter, 页行, 页背景) 序列。 */
    private fun walkPages(host: DesktopReaderHost, maxPages: Int): List<Triple<Int, List<DrawLine>, List<orilumn.reader.engine.skia.PageBackground>>> {
        val out = ArrayList<Triple<Int, List<DrawLine>, List<orilumn.reader.engine.skia.PageBackground>>>()
        var pos = runBlocking { host.open() } ?: return out
        var hops = 0
        while (hops < maxPages) {
            val lines = host.pageLines(pos) ?: break
            out.add(Triple(pos.chapter, lines, host.pageBackgrounds(pos) ?: emptyList()))
            pos = runBlocking { host.adjacent(pos, 1) } ?: break
            hops++
        }
        return out
    }

    private fun render(lines: List<DrawLine>, backgrounds: List<orilumn.reader.engine.skia.PageBackground> = emptyList()): Bitmap {
        val bmp = Bitmap()
        bmp.allocN32Pixels(pageW, pageH, true)
        val canvas = Canvas(bmp)
        canvas.clear(Color.WHITE)
        // 章内绝对坐标归一到页原点 + 边距。
        val base = lines.firstOrNull()?.yTop ?: backgrounds.firstOrNull()?.yTop ?: 0
        val paint = Paint()
        for (bg in backgrounds) {
            paint.color = bg.argb
            canvas.drawRect(
                org.jetbrains.skia.Rect.makeLTRB(
                    (bg.left + marginX).toFloat(), (bg.yTop - base + marginY).toFloat(),
                    (bg.right + marginX).toFloat(), (bg.yBottom - base + marginY).toFloat(),
                ),
                paint,
            )
        }
        // 文本行（墨色即字体证明；含 marker/基线位移/阴影/着重号同式）。
        LineWindowDrawer().drawLines(canvas, marginX.toFloat(), lines.map { it.copy(yTop = it.yTop - base + marginY, yBottom = it.yBottom - base + marginY) }, null)
        return bmp
    }

    private fun savePng(bmp: Bitmap, file: File) {
        val px = requireNotNull(bmp.peekPixels())
        val img = BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pageH) for (x in 0 until pageW) img.setRGB(x, y, px.getColor(x, y))
        ImageIO.write(img, "png", file)
    }

    /** 两位图差异像素占比（尺寸必须一致）。 */
    private fun pixDiff(a: Bitmap, b: Bitmap): Double {
        val pa = requireNotNull(a.peekPixels())
        val pb = requireNotNull(b.peekPixels())
        var diff = 0L
        var n = 0L
        for (y in 0 until pageH) for (x in 0 until pageW) {
            n++
            if (pa.getColor(x, y) != pb.getColor(x, y)) diff++
        }
        return diff.toDouble() / n
    }

    private fun checkPageLines(book: String, lines: List<DrawLine>) {
        // 空页合法：纯空白/封面章零行仍给一空页（Paginator.paginateFrom），只验单调与页高。
        if (lines.isEmpty()) return
        // yTop 是章内绝对坐标：单调 + 行高为正恒成立；页内高度用相对首行 top 断言
        // （== 版心高 = viewportH - 上下边距，break-inside:avoid 整块扩展时可超，见 Paginator.applyBreakInsideAvoid）。
        val firstTop = lines.first().yTop
        for (l in lines) {
            assertTrue("$book 行高为正：$l", l.yBottom > l.yTop)
            assertTrue("$book 行带不越界：$l", l.yTop >= 0)
        }
        assertEquals("$book 行带严格递增", lines.map { it.yTop }, lines.map { it.yTop }.sorted())
        val pageH = lines.last().yBottom - firstTop
        // 版心高 = 视口 - profile 上下边距（与控制器 `viewH - marginTop - marginBottom` 同式）。
        val contentH = viewportH - orilumn.reader.engine.text.TypographicProfile.build(
            orilumn.reader.data.settings.ReaderSettings.DEFAULT, 1f,
        ).let { it.marginTop + it.marginBottom }
        assertTrue("$book 页高 $pageH 超限（contentH=$contentH ＋ avoid 扩展 400）", pageH <= contentH + 400)
    }

    @Test
    fun `corpus opens paginates and renders`() {
        val dir = outDir()
        for (book in books()) {
            val root = java.nio.file.Files.createTempDirectory("orilumn-p5c").toFile()
            val host = openHost(book, root)
            try {
                val pages = walkPages(host, 12)
                assertTrue("${book.name} 应有页", pages.isNotEmpty())
                assertTrue("${book.name} 标题非空", host.title().isNotBlank())
                assertTrue("${book.name} 应有实内容页", pages.any { it.second.isNotEmpty() })
                // 首 3 页几何断言 + 落 PNG。
                for ((i, p) in pages.take(3).withIndex()) {
                    checkPageLines(book.name, p.second)
                    savePng(render(p.second, p.third), File(dir, "${book.nameWithoutExtension}-p$i.png"))
                }
                // 进度口径不断。
                val frac = host.pageProgress(runBlocking { host.open() }!!)
                assertTrue("${book.name} 进度 0..1：$frac", frac in 0.0..1.0)
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `obfuscated font matches plain`() {
        // P2-b：混淆字体解码后与明文字体同版（同文本＋像素级一致）。
        val dir = outDir()
        fun pageShots(name: String): List<Pair<String, Bitmap>> {
            val book = books().first { it.nameWithoutExtension == name }
            val root = java.nio.file.Files.createTempDirectory("orilumn-p5c").toFile()
            val host = openHost(book, root)
            try {
                val pages = walkPages(host, 4)
                assertTrue("$name 应有页", pages.isNotEmpty())
                return pages.take(3).mapIndexed { i, p ->
                    val bmp = render(p.second, p.third)
                    savePng(bmp, File(dir, "$name-p$i.png"))
                    p.second.joinToString("\n") { it.text } to bmp
                }
            } finally {
                host.close()
            }
        }
        val plain = pageShots("wasteland-otf")
        val obf = pageShots("wasteland-otf-obf")
        assertEquals("章节页数一致", plain.size, obf.size)
        for (i in plain.indices) {
            assertEquals("第 $i 页文本一致（混淆已解）", plain[i].first, obf[i].first)
            val d = pixDiff(plain[i].second, obf[i].second)
            assertTrue("第 $i 页像素差异 $d 过大（字体回退嫌疑）", d < 0.002)
        }
    }

    @Test
    fun `woff font loads`() {
        val book = books().first { it.nameWithoutExtension == "wasteland-woff" }
        val root = java.nio.file.Files.createTempDirectory("orilumn-p5c").toFile()
        val host = openHost(book, root)
        try {
            val pages = walkPages(host, 4)
            assertTrue("woff 书应有页", pages.isNotEmpty())
            assertTrue("首页应有墨", pages.first().second.any { it.text.isNotBlank() })
            savePng(render(pages.first().second, pages.first().third), File(outDir(), "wasteland-woff-p0.png"))
        } finally {
            host.close()
        }
    }

    @Test
    fun `internal links navigate end to end`() {
        // P4-c2：真书 href 解析→跨章/页内导航闭环。
        val book = books().first { it.nameWithoutExtension == "internallinks" }
        val parsed = ZipEpubResourceReader(book.absolutePath).use { EpubParser().parse(it) }
        val indexByHref = parsed.spine.associate {
            LinkTargets.normalizePath(LinkTargets.splitFragment(it.href).first) to it.index
        }
        val converter = HtmlTreeConverter()
        var landed = 0
        val root = java.nio.file.Files.createTempDirectory("orilumn-p5c").toFile()
        val host = openHost(book, root)
        try {
            runBlocking { host.open() }
            outer@ for (sp in parsed.spine.take(6)) {
                val xhtml = ZipEpubResourceReader(book.absolutePath).use { it.readText(sp.href) } ?: continue
                val tree = converter.convert(xhtml) ?: continue
                val hrefs = ArrayList<String>()
                fun walk(el: MarkupElement) {
                    if (el.tag == "a") el.attrs["href"]?.let { hrefs.add(it) }
                    for (c in el.children) walk(c)
                }
                walk(tree)
                for (h in hrefs) {
                    val t = LinkTargets.resolveLinkTarget(h, sp.index, sp.href, indexByHref) ?: continue
                    val pos = runBlocking { host.openLink(t) }
                    assertNotNull("链接 $h 应可导航", pos)
                    assertEquals("跨章落位", t.chapterIndex, pos!!.chapter)
                    if (t.fragment != null) {
                        val lines = host.pageLines(pos) ?: continue
                        savePng(render(lines, host.pageBackgrounds(pos) ?: emptyList()), File(outDir(), "internallinks-target-$landed.png"))
                    }
                    if (++landed >= 3) break@outer
                }
            }
            assertTrue("应至少导航 3 条真书链接", landed >= 3)
        } finally {
            host.close()
        }
    }
}
