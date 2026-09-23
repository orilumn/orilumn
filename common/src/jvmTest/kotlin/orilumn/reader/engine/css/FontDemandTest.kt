package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章字体需求收集：作者 CSS 文本 → (家族, 字重, 斜体)，供整形前追装导入字库。
 * 开屏后追装必见字体跳变，故需求先行；本函数是纯文本扫描，不碰级联。
 */
class FontDemandTest {

    @Test
    fun `collects families weights and italic`() {
        val d = collectFontDemand(
            listOf(
                "p{font-family:\"霞鹜文楷\", serif}code{font-weight:200}em{font-style:italic}",
                "h1{font-weight:bold}",
            ),
        )
        assertEquals(setOf("霞鹜文楷", "serif"), d.families)
        assertEquals(setOf(200, 700), d.weights)
        assertTrue(d.italic)
    }

    @Test
    fun `empty css yields empty demand`() {
        assertEquals(FontDemand.EMPTY, collectFontDemand(emptyList()))
        assertEquals(FontDemand.EMPTY, collectFontDemand(listOf("p{color:red}")))
    }

    @Test
    fun `malformed sheets are skipped`() {
        val d = collectFontDemand(listOf("p{font-family:"))
        assertEquals(FontDemand.EMPTY, d)
    }
}
