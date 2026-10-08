package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.skia.PageImage
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * P0b [PageRasterStore] 的端到端行为锁（**真实离屏栅格**，不依赖任何 GUI）。
 *
 * 这是刻意做成可直测的：命中判据错 ⇒ 翻页露出旧像素 / 邻页没就绪 / 池无限涨。
 * 三者都不会崩，只会慢慢坏，所以逐条锁死，且每条都跑真实的 skia 栅格路径。
 */
class PageRasterStoreTest {

    private var encodeCalls = 0

    /** 恒等 encode（桌面语义）：直接持 skia Image，零拷贝。 */
    private fun store(maxBytes: Long = 8_000_000, w: Int = 200, h: Int = 120) =
        PageRasterStore<org.jetbrains.skia.Image>(
            maxBytes = maxBytes,
            sizeOf = { it.width * it.height * 4L },
            encode = { encodeCalls++; it },
        )

    private fun spec(
        images: List<PageImageSlot> = emptyList(),
        revision: Int = 0,
        right: Float = 200f,
        bottom: Float = 120f,
    ) = PageRasterSpec(
        lines = emptyList(),
        backgrounds = emptyList(),
        bgImages = emptyMap(),
        images = images,
        pageBg = 0xFF204060.toInt(),
        contentLeft = 0f,
        contentRectLeft = 0f,
        contentRectTop = 0f,
        contentRight = right,
        contentBottom = bottom,
        contentRevision = revision,
    )

    private fun key(charStart: Int = 0, w: Int = 200, h: Int = 120, rev: Int = 0) =
        PageRasterKey(1, charStart, charStart + 100, w, h, rev, 0xFF204060.toInt(), 0xFF000000.toInt())

    private fun slot(src: String) = PageImageSlot(
        PageImage(src = src, chapterHref = "ch.xhtml", xLeft = 10, yTop = 10, yBottom = 30, widthPx = 20, heightPx = 20),
        decoded = null,
    )

    @Test
    fun `首次栅格入池且标记为未命中`() {
        encodeCalls = 0
        val s = store()
        val r = s.obtain(key(), spec())
        assertNotNull(r)
        assertFalse("首次必须真的画", r!!.cacheHit)
        assertEquals(1, encodeCalls)
        assertEquals(1, s.stats().first)
        r.value.close()
    }

    @Test
    fun `同键同规格二次命中——不重栅格（这是翻页不卡的前提）`() {
        encodeCalls = 0
        val s = store()
        val a = s.obtain(key(), spec())!!
        val b = s.obtain(key(), spec())!!
        assertTrue("第二次应命中", b.cacheHit)
        assertSame("命中必须返回同一实例，不能重画", a.value, b.value)
        assertEquals("命中不得再走 encode", 1, encodeCalls)
        a.value.close()
    }

    @Test
    fun `换页各占一槽——多页留存`() {
        val s = store()
        val a = s.obtain(key(charStart = 0), spec())!!
        val b = s.obtain(key(charStart = 100), spec())!!
        assertEquals(2, s.stats().first)
        // 回翻到第一页应命中（1 槽位时代这里必然 miss）
        assertTrue(s.obtain(key(charStart = 0), spec())!!.cacheHit)
        assertEquals(2, s.stats().first)
        a.value.close(); b.value.close()
    }

    @Test
    fun `插图补解码后重画——否则灰块留一辈子`() {
        encodeCalls = 0
        val s = store()
        val k = key()
        val first = s.obtain(k, spec())!!          // 首帧：图未解出（灰块）
        val second = s.obtain(k, spec(images = listOf(slot("a.png"))))!!
        assertFalse("补解码必须重画", second.cacheHit)
        assertEquals(2, encodeCalls)
        assertEquals("同键重写仍是 1 页", 1, s.stats().first)
        first.value.close(); second.value.close()
    }

    @Test
    fun `视口变化清空池`() {
        val s = store()
        s.obtain(key(), spec())!!.value.close()
        s.obtain(key(charStart = 100), spec())!!.value.close()
        assertEquals(2, s.stats().first)
        // 视口变高 ⇒ 尺寸不对，旧页必须全清
        val bigger = s.obtain(key(w = 200, h = 240), spec(bottom = 240f))!!
        assertEquals("池应只剩新页", 1, s.stats().first)
        bigger.value.close()
    }

    @Test
    fun `无键不入池——封面等无页身份的调用不占预算`() {
        val s = store()
        assertNotNull(s.obtain(null, spec()))
        assertEquals(0, s.stats().first)
    }

    @Test
    fun `编码失败返回 null 而不是崩`() {
        val failing = PageRasterStore<org.jetbrains.skia.Image>(
            maxBytes = 1_000_000,
            sizeOf = { 4L },
            encode = { null },
        )
        assertNull(failing.obtain(key(), spec()))
    }

    @Test
    fun `像素确定性——同规格两次栅格像素全等`() {
        val s1 = store(); val s2 = store()
        val img = s1.obtain(key(), spec(images = listOf(slot("a.png"))))!!.value
        val ref = s2.obtain(key(), spec(images = listOf(slot("a.png"))))!!.value
        assertTrue("同输入必须像素全等（否则内容变了却看不出来）", pixelsEqual(img, ref))
        img.close(); ref.close()
    }

    private fun pixelsEqual(a: org.jetbrains.skia.Image, b: org.jetbrains.skia.Image): Boolean {
        // peekPixels 给的是 Pixmap，尺寸挂在 info 上（Pixmap 本身没有 width/height）。
        val pa = a.peekPixels() ?: return false
        val pb = b.peekPixels() ?: return false
        if (pa.info.width != pb.info.width || pa.info.height != pb.info.height) return false
        for (y in 0 until pa.info.height) {
            for (x in 0 until pa.info.width) {
                if (pa.getColor(x, y) != pb.getColor(x, y)) return false
            }
        }
        return true
    }
}