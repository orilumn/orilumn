package orilumn.reader.ui.reader

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 用户层跨页位图缓存 [PageImageCache] 的行为锁（纯逻辑，无 GUI）。
 *
 * 对着真机症状写：全屏整宽插图解码后是 4880×2101 ≈ 39MiB，而缓存默认预算 32MiB。
 * 旧 `evict()` 只看 `bytes > maxBytes` 就一路淘汰，**把刚 put 的那条也扫掉了**
 * （单条即唯一一条时必然如此），于是「解码 → 放入 → 取不到」死循环：
 * 整页插图永远备不齐，`Preraster.warmImagesForPreraster` 恒返回 false
 * （真机日志 `preraster skipped: images not ready`），界面只剩灰占位。
 *
 * 《Kotlin in Action, 2nd ed》§2.1.3 两张图「只显示第二张」正是此 bug：第二张
 * 4880×1661 ≈ 31MiB 刚好塞得下，第一张 39MiB 塞不下 ⇒ 被自己淘汰。
 */
class PageImageCacheTest {

    private class Blob(val bytes: Long)

    private val mib = 1024L * 1024L

    private fun cache(maxMib: Long) =
        PageImageCache<Blob>(maxBytes = maxMib * mib, sizeOf = { it.bytes })

    private fun put(c: PageImageCache<Blob>, key: String, mibSize: Long) =
        c.put(key, Blob(mibSize * mib))

    /** 单条大于预算的条目必须仍取得到——否则该图**永远**显示不出来（只留灰占位）。 */
    @Test
    fun oversizedEntrySurvivesEviction() {
        val c = cache(32)
        put(c, "big", 39)
        assertNotNull("39MiB 条目在 32MiB 预算下被自己淘汰了", c.get("big"))
    }

    /** 淘汰仍按 LRU：预算内的多条目照旧只留最新的若干条。 */
    @Test
    fun lruEvictionStillAppliesToSiblings() {
        val c = cache(8)
        put(c, "a", 4)
        put(c, "b", 4)
        assertNotNull(c.get("a"))
        assertNotNull(c.get("b"))
        put(c, "c", 4)
        assertNull("最久未用的 a 应被淘汰", c.get("a"))
        assertNotNull(c.get("b"))
        assertNotNull(c.get("c"))
    }

    /** 超大条目入池时旧条目让位给它，而不是反过来把它挤掉。 */
    @Test
    fun oversizedEntryDisplacesStaleSiblings() {
        val c = cache(8)
        put(c, "a", 4)
        put(c, "big", 12)
        assertNotNull(c.get("big"))
        assertNull("旧条目应给超大条目让位", c.get("a"))
    }

    /** 同一批 put 的多条（一页多图）必须**整批**留下，否则同页只画得出一张。 */
    @Test
    fun batchPutKeepsWholePageWorkingSet() {
        val c = cache(32)
        c.putAll(listOf("i1" to Blob(30 * mib), "i2" to Blob(17 * mib)))
        assertNotNull(c.get("i1"))
        assertNotNull(c.get("i2"))
    }

    /** `get` 给条目续命（LRU 访问序语义不变）。 */
    @Test
    fun getRefreshesRecency() {
        val c = cache(8)
        put(c, "a", 4)
        put(c, "b", 4)
        assertNotNull(c.get("a"))
        put(c, "c", 4)
        assertNotNull(c.get("a"))
        assertNull("b 已是最久未用，应让位", c.get("b"))
        assertNotNull(c.get("c"))
    }

    /** 覆盖 put（同键覆盖）不重复计费。 */
    @Test
    fun overwriteDoesNotDoubleCount() {
        val c = cache(8)
        put(c, "a", 6)
        put(c, "a", 6)
        put(c, "b", 2)
        assertNotNull(c.get("a"))
        assertNotNull(c.get("b"))
    }
}
