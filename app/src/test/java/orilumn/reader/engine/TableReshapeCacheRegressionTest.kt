package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 表格翻页空白回归：格 shape 只活在叶实例上（[BoxChapterLayouter.fillTableRowCells]
 * 回填），而塑形缓存按块下标跨 prepare 复用行 shape。若缓存命中不回填当前实例，
 * 重建页（翻页返回）的展开阶段会拿到全空 shape，整表静默消失（有占位、无像素）。
 *
 * 复现序列（与线上翻页一致）：同一章节 prepareLight 两次（第二次产出全新叶实例），
 * 两次塑形共享同一块缓存；第二次命中缓存后，表行叶的格 shape 仍须齐全。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TableReshapeCacheRegressionTest {

    private lateinit var bc: BoxChapterLayouter
    private lateinit var profile: TypographicProfile
    private val contentW = 600
    private val contentH = 800

    @Before
    fun setUp() {
        bc = BoxChapterLayouter()
        profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    }

    private fun chapter() = HtmlTreeConverter().convert(
        "<html><body><p>引言段落。</p>" +
            "<div><table><thead><tr><th>甲</th><th>乙</th></tr></thead>" +
            "<tbody><tr><td>一</td><td>二</td></tr></tbody></table></div>" +
            "<p>结尾段落。</p></body></html>",
    )!!

    private fun cellShapesOf(prepare: LightPrepare): List<Boolean> {
        val out = ArrayList<Boolean>()
        for (i in 0 until prepare.totalBlocks) {
            val leaf = prepare.block(i)
            if (leaf.el?.tag != "tr") continue
            val t = leaf.table
            assertNotNull("tr block must carry a row layout", t)
            assertTrue("row must have cells", t!!.cells.isNotEmpty())
            for (c in t.cells) out.add(c.shape != null)
        }
        return out
    }

    @Test
    fun `缓存命中重建页仍回填格shape`() {
        val markup = chapter()
        val bundle = CssBundle(listOf(""))
        // 线上结构缓存跨 prepare 常驻，叶实例每次重建——此处同式。
        val structure = ChapterStructureCache()
        val cache = HashMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>()
        val p1 = bc.prepareLight(markup, bundle, profile, contentW, structure, contentH)
        bc.shapeTempPageForward(p1, profile, contentW, contentH, 0, cache)
        val first = cellShapesOf(p1)
        assertTrue("chapter must contain table cells", first.isNotEmpty())
        assertTrue("first shaping fills every cell shape", first.all { it })

        // 翻页返回：全新叶实例＋缓存命中。
        val p2 = bc.prepareLight(markup, bundle, profile, contentW, structure, contentH)
        bc.shapeTempPageForward(p2, profile, contentW, contentH, 0, cache)
        val second = cellShapesOf(p2)
        assertTrue("chapter must contain table cells", second.isNotEmpty())
        assertTrue("cache-hit reshaping must refill every cell shape", second.all { it })
    }
}
