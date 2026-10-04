package orilumn.reader.engine.skia

import org.jetbrains.skia.Font
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取面缓存的池代次失效（渲染层）：
 *
 * [SkiaRunMeasurer] 的面表/保底表是长驻实例字段（绘制侧 `LineWindowDrawer` 的 painter、
 * 断行侧 breaker 跨越多次排版），键里没有池身份。池一变（换字体/换字重锚点导致不同面
 * 入池）旧条目即错面，真机症状是“换字体/点字重要退出重进才生效”（标题槽普惠体只动正文、
 * 更纱字重多档无反应、思源黑体后几次点字重无变化，三案同源）。
 */
class FontPoolGenerationTest {

    private val tmp = "/private/var/folders/83/8q_h_hh50bv3yx3lh2nzvg4m0000gn/T"
    private val fam = "GenTestFam"
    private val zh = '中'.code
    private val fs = 24f

    private fun covers(f: Font?): Boolean =
        f != null && runCatching { f.getUTF32Glyph(zh).toInt() }.getOrDefault(0) != 0

    @Test
    fun `same measurer sees new pool faces without reopen`() {
        val prevAnchors = SkParagraphFactory.weightAnchors
        try {
            SkParagraphFactory.weightAnchors = emptyMap()
            val reg = java.io.File("$tmp/puhuiti-regular.ttf").readBytes()
            val blk = java.io.File("$tmp/puhuiti-black.ttf").readBytes()
            SkiaFontPool.setEmbedded(
                listOf(SkiaFontPool.EmbeddedFont.forFace(fam, fam, reg)),
            )
            val m = SkiaRunMeasurer()
            val stack = listOf(fam)
            val before = m.faceForCp(zh, "p", stack, 400, false, false, fs)
            assertTrue("Regular 面必须覆盖 中", covers(before))
            // 同族名换 Black 面入池（字节量不同 ⇒ 池签名变 ⇒ 代次 +1）。
            SkiaFontPool.setEmbedded(
                listOf(SkiaFontPool.EmbeddedFont.forFace(fam, fam, blk)),
            )
            val after = m.faceForCp(zh, "p", stack, 400, false, false, fs)
            assertTrue("Black 面必须覆盖 中", covers(after))
            assertNotSame("池变后同一实例必须给新面（旧实现返同一张，即退出重进才生效）", before, after)
            assertNotNull(after)
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
            SkParagraphFactory.weightAnchors = prevAnchors
        }
    }
}
