package orilumn.reader.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旁注字体进池口径：只有读者单独挑过族才要装池，跟随正文时由正文槽保证有面。
 *
 * 字重越界 / 0（面板换字体时清零的「没配」）按字体自身默认档 400，
 * 与 `noteFontWeightIntOf` 同口径——不能把 0 当成一个真实字重去选面。
 */
class NoteFontPoolExtraTest {

    @Test
    fun blankNoteFontNeedsNoExtraFace() {
        assertTrue(noteFontPoolExtra("", 500).isEmpty())
        assertTrue(noteFontPoolExtra("   ", 500).isEmpty())
    }

    @Test
    fun explicitNoteFontCarriesItsWeight() {
        assertEquals(mapOf("霞鹜文楷" to 500), noteFontPoolExtra("霞鹜文楷", 500))
    }

    @Test
    fun unsetOrOutOfRangeWeightFallsBackToRegular() {
        assertEquals(mapOf("霞鹜文楷" to 400), noteFontPoolExtra("霞鹜文楷", 0))
        assertEquals(mapOf("霞鹜文楷" to 400), noteFontPoolExtra("霞鹜文楷", 50))
        assertEquals(mapOf("霞鹜文楷" to 400), noteFontPoolExtra("霞鹜文楷", 1000))
    }

    @Test
    fun familyNameIsTrimmed() {
        assertEquals(mapOf("霞鹜文楷" to 700), noteFontPoolExtra("  霞鹜文楷 ", 700))
    }
}
