package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 归一化 + 原文位置映射必须与渲染用的 [WhiteSpaceNormalize.normalizeNode] 逐字一致。 */
class WhiteSpaceNormalizeMapTest {

    private val samples = listOf(
        "",
        "abc",
        " a",
        "a ",
        "  a  b  ",
        "a\tb",
        "\t\ta",
        "a\nb",
        "a\r\nb",
        "a\u000Cb",
        "a  \t\n b",
        "   ",
        "中 文   排版",
        "a b c",
    )

    @Test
    fun mapMatchesRenderNormalization() {
        for (ws in WhiteSpace.entries) {
            for (s in samples) {
                val plain = WhiteSpaceNormalize.normalizeNode(s, ws)
                val mapped = WhiteSpaceNormalize.normalizeNodeWithMap(s, ws)
                assertEquals("ws=$ws s=«$s»", plain, mapped.text)
                assertEquals(mapped.text.length, mapped.rawStart.size)
                assertEquals(mapped.text.length, mapped.rawEnd.size)
            }
        }
    }

    @Test
    fun rawRangesCoverTheCollapsedRun() {
        val n = WhiteSpaceNormalize.normalizeNodeWithMap("a   b", WhiteSpace.NORMAL)
        assertEquals("a b", n.text)
        // 折叠出来的空格覆盖整段原文空白 [1, 4)。
        assertEquals(1, n.rawStart[1])
        assertEquals(4, n.rawEnd[1])
        // 普通字符是恒等映射。
        assertEquals(0, n.rawStart[0])
        assertEquals(1, n.rawEnd[0])
        assertEquals(4, n.rawStart[2])
        assertEquals(5, n.rawEnd[2])
    }

    @Test
    fun tabExpandsToEightSpaces() {
        val n = WhiteSpaceNormalize.normalizeNodeWithMap("\tx", WhiteSpace.PRE)
        assertEquals("        x", n.text)
        for (i in 0 until 8) {
            assertEquals(0, n.rawStart[i])
            assertEquals(1, n.rawEnd[i])
        }
        assertEquals(1, n.rawStart[8])
    }

    @Test
    fun preLineKeepsNewlines() {
        val n = WhiteSpaceNormalize.normalizeNodeWithMap("a  \n  b", WhiteSpace.PRE_LINE)
        assertEquals("a \n b", n.text)
        assertTrue(n.text.contains('\n'))
    }
}
