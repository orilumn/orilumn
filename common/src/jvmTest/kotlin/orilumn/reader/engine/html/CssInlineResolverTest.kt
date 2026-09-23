package orilumn.reader.engine.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssInlineResolverTest {

    private val resolver = CssInlineResolver(baseBodyPx = 16f)

    @Test
    fun `px 与 em 与百分比长度`() {
        val s = resolver.resolve("font-size:18px")
        assertEquals(18f, s.fontSizePx!!, 1e-3f)
        val em = resolver.resolve("font-size:1.5em")
        assertEquals(24f, em.fontSizePx!!, 1e-3f)
        val pct = resolver.resolve("font-size:80%")
        assertEquals(12.8f, pct.fontSizePx!!, 1e-3f)
    }

    @Test
    fun `line-height 无单位倍数与 px`() {
        assertEquals(1.5f, resolver.resolve("line-height:1.5").lineHeightRatio!!, 1e-3f)
        // 24px relative to the 16px base = 1.5x
        assertEquals(1.5f, resolver.resolve("line-height:24px").lineHeightRatio!!, 1e-3f)
    }

    @Test
    fun `颜色解析 rgb 简写与英文名`() {
        assertEquals("#ffffff", resolver.resolve("color:#fff").colorHex)
        assertEquals("#ff0000", resolver.resolve("color:red").colorHex)
        assertNull(resolver.resolve("color:notacolor").colorHex)
    }

    @Test
    fun `加粗斜体下划线`() {
        assertTrue(resolver.resolve("font-weight:bold").bold == true)
        assertTrue(resolver.resolve("font-style:italic").italic == true)
        assertTrue(resolver.resolve("text-decoration:underline").underline == true)
        assertTrue(resolver.resolve("font-weight:400").bold == false)
    }

    @Test
    fun `空串与未知属性忽略`() {
        val s = resolver.resolve("")
        assertNull(s.fontSizePx)
        assertNull(s.colorHex)
        assertNull(resolver.resolve("foo:bar;").fontSizePx)
    }

    @Test
    fun `text-indent`() {
        assertEquals(32f, resolver.resolve("text-indent:2em").textIndentPx!!, 1e-3f)
    }
}