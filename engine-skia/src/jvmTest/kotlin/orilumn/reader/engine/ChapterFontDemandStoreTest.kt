package orilumn.reader.engine

import okio.Path.Companion.toPath
import orilumn.reader.engine.css.FontDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字体需求清单的落盘闭环：**写进去的族/字重/斜体读回来必须一模一样**，且任何一项判据不符
 * （格式版本 / 构建号 / 坏文件）都必须回空表——清单只用来"省一次解析"，判据松了就会拿旧需求
 * 去装池，开屏后再追装必见字体跳变。
 */
class ChapterFontDemandStoreTest {

    private fun tempRoot(): java.io.File =
        java.nio.file.Files.createTempDirectory("font-demand").toFile()

    private val chapter4 = ChapterFontDemandEntry.of(
        cssHash = 0xC55L,
        demand = FontDemand(
            families = setOf("Source Serif", "sans-serif"),
            weights = setOf(400, 700),
            italic = true,
        ),
    )

    private val chapter5 = ChapterFontDemandEntry.of(
        cssHash = 0xD00DL,
        demand = FontDemand(families = setOf("monospace"), weights = setOf(400), italic = false),
    )

    @Test
    fun `round trips the whole book list`() {
        val dir = tempRoot()
        try {
            val store = ChapterFontDemandStore(okio.FileSystem.SYSTEM, dir.absolutePath.toPath())
            store.write(40L, 1234, mapOf(4 to chapter4, 5 to chapter5))

            val back = store.read(40L, 1234)
            assertEquals("章号必须原样回来", setOf(4, 5), back.keys)
            assertEquals(chapter4, back[4])
            assertEquals(chapter5, back[5])
            // 条目 → FontDemand 的往返：集合顺序无关，内容必须相等。
            assertEquals(
                FontDemand(setOf("Source Serif", "sans-serif"), setOf(400, 700), true),
                back.getValue(4).toDemand(),
            )
            assertEquals("别的书互不影响", emptyMap<Int, ChapterFontDemandEntry>(), store.read(41L, 1234))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `build number change is a full miss`() {
        val dir = tempRoot()
        try {
            val store = ChapterFontDemandStore(okio.FileSystem.SYSTEM, dir.absolutePath.toPath())
            store.write(40L, 1234, mapOf(4 to chapter4))
            assertTrue("构建号变了必须整体作废（新的解析口径可能不同）", store.read(40L, 1235).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt or truncated file is a miss not a crash`() {
        val dir = tempRoot()
        try {
            val store = ChapterFontDemandStore(okio.FileSystem.SYSTEM, dir.absolutePath.toPath())
            val f = store.file(40L)
            f.parent?.let { java.io.File(it.toString()).mkdirs() }
            java.io.File(f.toString()).writeText("""{"version":1,"appVers""")

            assertTrue("半份文件读回空表（退化为现算）", store.read(40L, 1234).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cssHash travels with the entry so the caller can reject one chapter`() {
        val dir = tempRoot()
        try {
            val store = ChapterFontDemandStore(okio.FileSystem.SYSTEM, dir.absolutePath.toPath())
            store.write(40L, 1234, mapOf(4 to chapter4))
            val back = store.read(40L, 1234).getValue(4)
            assertEquals("逐章判据必须落盘（作者 CSS 换了只作废这一章）", 0xC55L, back.cssHash)
        } finally {
            dir.deleteRecursively()
        }
    }
}
