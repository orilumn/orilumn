package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 修订文字里的段界（`U+E000`）工具：切块、计长、去段界取纯字符。 */
class RevisionBlocksTest {

    @Test
    fun textOfDropsSeparatorsOnly() {
        // 没有段界：原样返回（叶内 `\n` 是 `<br>`，算一个字，不能动）。
        assertEquals("甲乙\n丙", RevisionBlocks.textOf("甲乙\n丙"))
        // 有段界：段界不占字符，只把块边界去掉。
        assertEquals("甲乙丙", RevisionBlocks.textOf("甲乙${RevisionBlocks.SEPARATOR}丙"))
        assertEquals("甲乙丙", RevisionBlocks.textOf(
            "甲${RevisionBlocks.SEPARATOR}乙${RevisionBlocks.SEPARATOR}丙",
        ))
        // 空段（连续段界 / 首尾段界）不影响字符。
        assertEquals("甲乙", RevisionBlocks.textOf("${RevisionBlocks.SEPARATOR}甲乙"))
    }

    @Test
    fun renderedLengthIgnoresSeparators() {
        assertEquals(2, RevisionBlocks.renderedLength("甲${RevisionBlocks.SEPARATOR}乙"))
        assertEquals(3, RevisionBlocks.renderedLength("甲\n${RevisionBlocks.SEPARATOR}乙"))
    }
}
