package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rust 书 `blockquote{padding:0.5rem;padding-bottom:0.2rem}` 的上下不对称是书自己写的，
 * 引擎必须忠实还原（单边声明覆盖简写对应槽位，其余槽位保留简写值）。
 */
class PaddingOverrideTest {

    @Test
    fun `简写加单边覆盖上下按书算`() {
        val bq = MarkupElement("blockquote")
        val root = MarkupElement("body", children = listOf(bq))
        val styles = StyleComputer(
            18f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse("blockquote{margin:0.5rem 0;padding:0.5rem;padding-bottom:0.2rem}")),
        ).compute(root)
        val st = styles[bq]!!
        assertEquals(0.5f * 18f, st.padding.top, 0.001f)
        assertEquals(0.5f * 18f, st.padding.left, 0.001f)
        assertEquals(0.2f * 18f, st.padding.bottom, 0.001f)
        assertEquals(0.5f * 18f, st.margin.top, 0.001f)
    }
}
