package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本章全量（B1）的**让位后能回来**（原则 `docs/线程调度原则.md` §3.6，先例见
 * `docs/字体发现与开书调度方案.md` §3.3「光可续跑不够，还得能回来」）。
 *
 * 现场（GIMP 3.0 用户手册，平板落盘日志 `日志_20261106.txt`）：
 * - 开书落点章走临时表，B1 以第 6 档（prio 40）派发；
 * - 锚页刚出画面就触发 `onForegroundRaster()` → `cancelForRaster(0)`，把 B1 整趟砍掉；
 * - 整书 B2 显式跳过锚页章（"B1 owns the active chapter"），于是没有补派；
 * - 磁盘目录里 28 章都在，独缺落点章 → 每次开书 `tableHit=false` → `LARGE-ANCHOR` 10.0s。
 *
 * 本测试钉住修法：B1 被抢占后，队列真空时看门狗把它补派回来并落盘。没有看门狗时这条会超时
 * （B1 只在 anchor 流出生时派发一次，之后无人补派）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class B1PreemptResumeProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        cacheDir = temp.newFolder("cache")
        controller.cacheRoot = cacheDir.absolutePath.toPath()
    }

    @After
    fun tearDown() {
        // R13: reclaim background shaping so worker-JVM neighbors run clean.
        if (::controller.isInitialized) controller.close()
    }

    private fun paramHash(settings: ReaderSettings): Long {
        val profile = TypographicProfile.build(settings)
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    }

    @Test
    fun `preempted chapter canonical returns on idle and persists`() = runBlocking {
        val bookId = 11L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        val h = paramHash(ReaderSettings.DEFAULT)

        // 大章（> SMALL_CHAPTER_BLOCKS）⇒ 走锚点临时表，B1 进后台池（第 6 档）。
        controller.ensureChapterLayout(0, 0)
        val unit = controller.unitAt(0) ?: error("no unit 0")
        assertNotNull("ch0 must own a temp session (large chapter)", unit.inProgress)

        // 反复抢占：现场的栅格钩子（`onForegroundRaster → cancelForRaster(PRIO_FLIP=0)`）就是这一刀。
        // 无论砍在当时是「还在排队」还是「已在跑」，磁盘表都必须靠续跑看门狗补回来。
        repeat(30) {
            controller.onForegroundRaster()
            delay(10)
        }

        val file = PaginationCacheStore(FileSystem.SYSTEM, cacheDir.absolutePath.toPath())
            .file("book_$bookId", 0, h)
        withTimeout(20_000) {
            while (!FileSystem.SYSTEM.exists(file)) delay(20)
        }
        assertEquals(
            "resumed canonical must bind the disk table for the running params",
            h,
            controller.unitAt(0)?.paginationTable?.paramHash,
        )
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>B1 resume</dc:title><dc:identifier id="bookid">urn:test:b1resume</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        // ~140 leaves（一段一叶），稳在 SMALL_CHAPTER_BLOCKS=120 之上。
        val body = (1..140).joinToString("\n") { p ->
            "<p>Paragraph $p. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        val chapter = ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray()
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to chapter,
        )
    }
}
