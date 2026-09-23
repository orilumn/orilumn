package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase A — numeric font-weight / font-family parsing for the browser-core pairing. */
class FontWeightParseTest {

    @Test
    fun `numeric weight parses keywords and numbers`() {
        assertEquals(400, parseFontWeight("normal"))
        assertEquals(700, parseFontWeight("bold"))
        assertEquals(300, parseFontWeight("lighter"))
        assertEquals(700, parseFontWeight("bolder"))
        assertEquals(700, parseFontWeight("700"))
        assertEquals(550, parseFontWeight("550"))
        assertEquals(100, parseFontWeight("100"))
        assertEquals(900, parseFontWeight("900"))
        assertNull(parseFontWeight("abc"))
    }

    @Test
    fun `font-family extracts the leading family`() {
        assertEquals("Times New Roman", parseFontFamily("\"Times New Roman\", serif"))
        assertEquals("Source Han Sans", parseFontFamily("'Source Han Sans', sans-serif"))
        // Generic-only values pass the generic keyword through so theme sheets / books resolve to a
        // real platform face (Android Typeface.create and the Skia factory accept these names).
        assertEquals("serif", parseFontFamily("serif"))
        assertEquals("sans-serif", parseFontFamily("sans-serif"))
        assertEquals("monospace", parseFontFamily("monospace"))
        assertEquals("serif", parseFontFamily("serif, sans-serif"))
        assertNull(parseFontFamily(""))
    }

    @Test
    fun `generic family detection recognizes css keywords in any case`() {
        assertTrue(isGenericFontFamily("sans-serif"))
        assertTrue(isGenericFontFamily("serif"))
        assertTrue(isGenericFontFamily("MONOSPACE"))
        assertFalse(isGenericFontFamily("屏显臻宋"))
        assertFalse(isGenericFontFamily(""))
        assertFalse(isGenericFontFamily(" "))
    }
}