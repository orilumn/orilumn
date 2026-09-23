package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorParseTest {

    // ---- transparent / null ----
    @Test fun transparent() { assertNull(parseCssColor("transparent")) }
    @Test fun rgba_zero_alpha() { assertNull(parseCssColor("rgba(255,0,128,0)")) }
    @Test fun css_hex8_zero_alpha() { assertNull(parseCssColor("#ff008000")) }

    // ---- named / hex (full alpha ff) ----
    @Test fun named_red() { assertEquals("#ffff0000", parseCssColor("red")) }
    @Test fun hex6() { assertEquals("#ffff0080", parseCssColor("#ff0080")) }
    @Test fun hex3() { assertEquals("#ffaabbcc", parseCssColor("#abc")) }

    // ---- rgb() / rgba() ----
    @Test fun rgba_half() { assertEquals("#80ff0080", parseCssColor("rgba(255,0,128,0.5)")) }
    @Test fun rgb_full() { assertEquals("#ffc8c8c8", parseCssColor("rgb(200,200,200)")) }
    @Test fun rgba_pct() { assertEquals("#ffcccccc", parseCssColor("rgb(80%,80%,80%)")) }

    // ---- CSS hex-8 format: RRGGBBAA → must flip AARRGGBB ----
    @Test fun css_hex8_alpha_flip() { assertEquals("#80ff0080", parseCssColor("#ff008080")) }

    // ---- hsl() / hsla() ----
    @Test fun hsl_red() { assertEquals("#ffff0000", parseCssColor("hsl(0,100%,50%)")) }
    @Test fun hsla_red_half() { assertEquals("#80ff0000", parseCssColor("hsla(0,100%,50%,0.5)")) }

    // ---- verify Android AARRGGBB layout byte-by-byte ----
    @Test fun output_is_8char_hex_AARRGGBB() {
        val out = parseCssColor("rgba(255,0,128,0.5)")!!
        assertEquals("#80ff0080", out)
        assertEquals("80", out.substring(1, 3))   // alpha
        assertEquals("ff", out.substring(3, 5))    // r
        assertEquals("00", out.substring(5, 7))     // g
        assertEquals("80", out.substring(7, 9))    // b
    }
}
