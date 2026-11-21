package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纠错词典的匹配口径：整词命中才给候选，不猜上下文。 */
class CorrectionDictionaryTest {

    @Test
    fun deGroupGivesDiThenDei() {
        // 「的」最常被写错，候选按词典里的顺序先「地」后「得」。
        assertEquals(listOf("地", "得"), CorrectionDictionary.candidatesFor("的"))
        assertEquals(listOf("的", "得"), CorrectionDictionary.candidatesFor("地"))
        assertEquals(listOf("的", "地"), CorrectionDictionary.candidatesFor("得"))
    }

    @Test
    fun twoCharWordGivesItsPartner() {
        assertEquals(listOf("其他"), CorrectionDictionary.candidatesFor("其它"))
        assertEquals(listOf("截至"), CorrectionDictionary.candidatesFor("截止"))
    }

    @Test
    fun notAWordGivesNoCandidate() {
        assertTrue(CorrectionDictionary.candidatesFor("").isEmpty())
        assertTrue(CorrectionDictionary.candidatesFor("的的").isEmpty())
        assertTrue(CorrectionDictionary.candidatesFor("的。").isEmpty())
        assertTrue(CorrectionDictionary.candidatesFor("地 得").isEmpty())
    }

    @Test
    fun candidateNeverContainsItself() {
        for (word in listOf("的", "在", "其它", "化妆")) {
            assertTrue(word, word !in CorrectionDictionary.candidatesFor(word))
        }
    }
}
