package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageImage

/**
 * P0b [PageRasterCache] 的行为锁。
 *
 * 这缓存的失效判据是整套动画地基：命中判据错 ⇒ 翻页露出上一页/旧墨色/旧几何；
 * 淘汰不回收 ⇒ Android native 像素一路涨到 OOM。两类错都不会崩，只会慢慢坏，
 * 所以逐条锁死。
 */
class PageRasterCacheTest {

    private fun key(
        chapter: Int = 1,
        charStart: Int = 0,
        charEnd: Int = 100,
        w: Int = 800,
        h: Int = 1200,
        rev: Int = 0,
        bg: Int = 0xFF204060.toInt(),
        ink: Int = 0xFF000000.toInt(),
    ) = PageRasterKey(chapter, charStart, charEnd, w, h, rev, bg, ink)

    private fun fp(rev: Int = 0, lines: List<DrawLine> = emptyList(), imgTag: String? = null) =
        PageRasterFingerprint(
            lines = lines,
            backgrounds = emptyList(),
            bgImageKeys = emptySet(),
            images = imgTag?.let { listOf(PageImageSlot(img(it), null)) } ?: emptyList(),
            pageBg = 0xFF204060.toInt(),
            contentRevision = rev,
        )

    private fun img(src: String) =
        PageImage(src = src, chapterHref = "ch.xhtml", xLeft = 0, yTop = 0, yBottom = 10, widthPx = 10, heightPx = 10)

    /** 假位图：只带尺寸，sizeOf 按尺寸算，够验 LRU。 */
    private fun bmp(w: Int, h: Int) = Fake(w, h)

    private class Fake(val w: Int, val h: Int)

    private fun cache(maxBytes: Long = 1000, evicted: MutableList<Fake> = mutableListOf()) =
        PageRasterCache<Fake>(maxBytes, { it.w * it.h * 4L }, { evicted.add(it) })

    @Test
    fun `同键同指纹命中`() {
        val c = cache()
        val k = key()
        c.put(k, fp(), bmp(10, 10))
        assertNotNull(c.get(k, fp()))
    }

    @Test
    fun `同键不同指纹不命中——插图补解码后必须重画`() {
        val c = cache()
        val k = key()
        c.put(k, fp(imgTag = null), bmp(10, 10))
        assertNull("首帧灰块补解码后若还命中，灰块会一直留着", c.get(k, fp(imgTag = "a.png")))
    }

    @Test
    fun `多页同时留存——这正是 P0b 的目的`() {
        val c = cache(maxBytes = 10_000_000)
        val pages = (0 until 5).map { i -> key(charStart = i * 100, charEnd = i * 100 + 100) }
        pages.forEachIndexed { i, k -> c.put(k, fp(), bmp(10, 10)) }
        // 逐页回查都必须命中（1 槽位时代只有最后一页能命中）
        pages.forEach { k -> assertNotNull("页 ${k.charStart} 被踢掉了", c.get(k, fp())) }
        assertEquals(5, c.stats().first)
    }

    @Test
    fun `键随视口与修订号与颜色变化`() {
        val base = key()
        val same = key()
        assertEquals("同身份同参数应同键", base.id, same.id)
        assertTrue("换视口必须换键", base.id != key(w = 900).id)
        assertTrue("换高度必须换键", base.id != key(h = 1300).id)
        assertTrue("换修订号必须换键", base.id != key(rev = 1).id)
        assertTrue("换底色必须换键", base.id != key(bg = 0xFFFFFFFF.toInt()).id)
        assertTrue("换墨色必须换键", base.id != key(ink = 0xFF111111.toInt()).id)
        assertTrue("换页必须换键", base.id != key(charStart = 100, charEnd = 200).id)
    }

    @Test
    fun `换视口后旧页不串味`() {
        val c = cache(maxBytes = 10_000_000)
        c.put(key(w = 800), fp(), bmp(10, 10))
        assertNull("换视口后不得命中旧键", c.get(key(w = 900), fp()))
    }

    @Test
    fun `超预算淘汰最久未用并回调回收`() {
        val evicted = mutableListOf<Fake>()
        // 每张 10*10*4 = 400 字节，预算 1000 ⇒ 装 2 张，第 3 张挤掉最久未用
        val c = cache(maxBytes = 1000, evicted = evicted)
        val a = bmp(10, 10); val b = bmp(10, 10); val d = bmp(10, 10)
        val ka = key(charStart = 0, charEnd = 100)
        val kb = key(charStart = 100, charEnd = 200)
        val kd = key(charStart = 200, charEnd = 300)
        c.put(ka, fp(), a)
        c.put(kb, fp(), b)
        // 碰一下 a，让它变「最近用过」，则淘汰应打 b
        assertNotNull(c.get(ka, fp()))
        c.put(kd, fp(), d)
        assertEquals("只应回收被淘汰的那张", listOf(b), evicted)
        assertNotNull("最近用过的 a 应还在", c.get(ka, fp()))
        assertNull("b 应已被淘汰", c.get(kb, fp()))
        assertNotNull(c.get(kd, fp()))
        assertEquals(2, c.stats().first)
    }

    @Test
    fun `同键重写不回收自己`() {
        val evicted = mutableListOf<Fake>()
        val c = cache(maxBytes = 10_000_000, evicted = evicted)
        val k = key()
        val first = bmp(10, 10)
        c.put(k, fp(), first)
        val second = bmp(10, 10)
        c.put(k, fp(), second)
        assertEquals("覆盖同一键不该把旧值当淘汰回收集（页面自己会丢引用）", 1, evicted.size)
        assertEquals(first, evicted[0])
        assertTrue(c.stats().second <= 10_000_000L)
    }

    @Test
    fun `clear 逐页回调回收`() {
        val evicted = mutableListOf<Fake>()
        val c = cache(maxBytes = 10_000_000, evicted = evicted)
        (0 until 3).forEach { i -> c.put(key(charStart = i * 100, charEnd = i * 100 + 100), fp(), bmp(10, 10)) }
        c.clear()
        assertEquals("clear 必须把每一页都交回平台回收", 3, evicted.size)
        assertEquals(0, c.stats().first)
        assertEquals(0L, c.stats().second)
    }

    @Test
    fun `指纹含修订号——字重变更即使行相等也重画`() {
        val c = cache()
        val k = key(rev = 7)
        c.put(k, fp(rev = 7), bmp(10, 10))
        assertNull(c.get(k, fp(rev = 8)))
    }
}