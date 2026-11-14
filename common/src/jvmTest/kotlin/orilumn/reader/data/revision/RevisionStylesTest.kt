package orilumn.reader.data.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修订样式列的两份区间：`s` 相对最小修订文字（重放用）、`e` 相对输入区全文（修改卡回显用）。
 *
 * 新增 `e` 不升 [RevisionStyles.VERSION]：老版本只读 `s`，新数据回退过去照常重放。
 */
class RevisionStylesTest {

    private fun span(s: Int, e: Int, tag: String, attrs: Map<String, String> = emptyMap()) =
        RevisionStyleSpan(s, e, RevisionStyle(tag, attrs))

    @Test
    fun minimalAndEditedSpansRoundTrip() {
        val minimal = listOf(span(2, 5, "code", mapOf("class" to "code", "id" to "x")))
        val edited = listOf(span(0, 3, "code", mapOf("class" to "code", "id" to "x")))
        val raw = RevisionStyles.encode(minimal, edited)
        assertEquals(minimal, RevisionStyles.decode(raw))
        assertEquals(edited, RevisionStyles.decodeEdited(raw))
    }

    @Test
    fun oldPayloadWithoutEditedArrayStillDecodesMinimal() {
        // 老数据只有 `s`：最小修订照读，输入区结构读不到 ⇒ 打开旧修改按纯文本开卡。
        val raw = RevisionStyles.encode(listOf(span(1, 2, "em")))
        assertEquals(listOf(span(1, 2, "em")), RevisionStyles.decode(raw))
        assertNull(RevisionStyles.decodeEdited(raw))
    }

    @Test
    fun emptyEditedArrayMeansExplicitlyNoStructure() {
        // `e: []` = 读者明确声明「这段没有行内结构」，与老数据的「没存过」(null) 不是一回事。
        val raw = RevisionStyles.encode(listOf(span(0, 1, "em")), emptyList())
        assertEquals(emptyList<RevisionStyleSpan>(), RevisionStyles.decodeEdited(raw))
    }

    @Test
    fun nothingStoredEncodesAsBlank() {
        assertEquals("", RevisionStyles.encode(null, null))
        assertNull(RevisionStyles.decode(""))
        assertNull(RevisionStyles.decodeEdited(""))
    }

    @Test
    fun futureVersionIsIgnoredInsteadOfMisread() {
        val raw = """{"v":99,"s":[[0,1,"em",{}]],"e":[[0,1,"em",{}]]}"""
        assertNull(RevisionStyles.decode(raw))
        assertNull(RevisionStyles.decodeEdited(raw))
    }

    @Test
    fun brokenPayloadIsTreatedAsOldRow() {
        assertNull(RevisionStyles.decode("not json"))
        assertNull(RevisionStyles.decodeEdited("""{"v":1}"""))
        assertTrue(RevisionStyles.decode("""{"v":1,"s":[]}""").isNullOrEmpty())
    }

    @Test
    fun rowSourcesRoundTripAndOldPayloadHasNone() {
        // 整表修订的「新行来自旧表哪一行」随样式列一起落库；老数据没有这个键 ⇒ null（按内容对齐）。
        val raw = RevisionStyles.encode(
            spans = emptyList(),
            editedBlocks = listOf("Tm;2,1,1,1;2,0,1,1"),
            rowSources = listOf(2, -1, 0),
        )
        assertEquals(listOf(2, -1, 0), RevisionStyles.decodeRowSources(raw))
        assertNull(RevisionStyles.decodeRowSources(RevisionStyles.encode(emptyList())))
        assertNull("坏数据不能当成映射", RevisionStyles.decodeRowSources("""{"v":1,"rs":[1,"x"]}"""))
        assertNull("负数只允许 -1", RevisionStyles.decodeRowSources("""{"v":1,"rs":[-2]}"""))
    }
}
