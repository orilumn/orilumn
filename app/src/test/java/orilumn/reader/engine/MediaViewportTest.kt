package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2 `@media` 全视口求值 guard：高/宽查询按内容区、分毫不差地在重轻两路生效；
 * 视口变化即结构缓存重解（旋转/尺寸变化不读脏表）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaViewportTest {

    private val converter = HtmlTreeConverter()
    private val layouter = BoxChapterLayouter()
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = """
        p { color: #000000 }
        @media screen and (min-height: 700px) { p { color: #123456 } }
        @media screen and (max-height: 699px) { p { color: #654321 } }
        @media screen and (min-width: 700px) { p { font-weight: bold } }
    """.trimIndent()

    private fun markup() = converter.convert("<html><body><p>正文</p></body></html>")!!

    private fun lightColor(contentW: Int, contentH: Int, structure: ChapterStructureCache = ChapterStructureCache()): Pair<String?, Int> {
        val light = layouter.prepareLight(markup(), CssBundle(listOf(css)), profile, contentW, structure, contentH)
        val leaf = light.block(0)
        return (leaf.style.colorHex to leaf.style.fontWeight)
    }

    @Test
    fun `高度查询按内容区高命中`() {
        val (tall, _) = lightColor(600, 800)
        assertEquals("#ff123456", tall)
        val (short, _) = lightColor(600, 600)
        assertEquals("#ff654321", short)
    }

    @Test
    fun `宽度查询按内容区宽命中`() {
        val (_, wideW) = lightColor(720, 800)
        assertEquals(700, wideW)
        val (_, narrowW) = lightColor(600, 800)
        assertEquals(400, narrowW)
    }

    @Test
    fun `重轻两路同视口同规则`() {
        val heavy = layouter.prepare(markup(), CssBundle(listOf(css)), profile, 600, 800)
        val heavyLeaf = heavy.leaves.single()
        val (lightColor, _) = lightColor(600, 800)
        assertEquals("#ff123456", heavyLeaf.style.colorHex)
        assertEquals(heavyLeaf.style.colorHex, lightColor)
    }

    @Test
    fun `视口变化即结构重解`() {
        val structure = ChapterStructureCache()
        val (tall, _) = lightColor(600, 800, structure)
        assertEquals("#ff123456", tall)
        // 同一缓存对象、仅高度变化：键含视口，必须重解而非读脏表。
        val (short, _) = lightColor(600, 600, structure)
        assertEquals("#ff654321", short)
    }
}
