package orilumn.reader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.LoadedFontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.data.settings.ReaderSettings

/**
 * 批注字重的落地口径（面板「字重」页只给批注字体用）。
 *
 * 两件事分别成立才算读者选的那一档真的生效：
 * 1. 设置里的档位原样交给绘制（`noteFontWeightOf`，没配就不发声明）；
 * 2. 桌面系统族**必须按族名**交给 Compose，字重随 `TextStyle` 走——把字重烤进单个字形面
 *    （`FontFamily(Typeface(skia, family))`）在桌面不起作用：单面族没有第二档，Compose 仍按
 *    族名去系统字库取面，烤好的那一档被丢掉，400 / 700 量出来的宽度一模一样。
 */
class NoteFontWeightTest {

    @Test
    fun noteFontWeightFollowsSetting() {
        assertEquals(FontWeight(400), noteFontWeightOf(ReaderSettings(noteFontWeight = 400)))
        assertEquals(FontWeight(700), noteFontWeightOf(ReaderSettings(noteFontWeight = 700)))
        // 0 = 没配（换字体时清零）；越界值同样当没配，不写死成 400。
        assertNull(noteFontWeightOf(ReaderSettings(noteFontWeight = 0)))
        assertNull(noteFontWeightOf(ReaderSettings(noteFontWeight = 1000)))
    }

    @Test
    fun desktopSystemFamilyResolvesWeightByName() {
        // 只在装了这个族（macOS 自带手札体）的机器上验真；别的机器跳过，不拿缺字当失败。
        val family = "Hannotate SC"
        val resolved = composeFontFamily(family, null, 700)
            ?: return // 本机没有这个族：跳过量宽比对。
        assertTrue(
            "桌面系统族不能烤成单个字形面（那样字重会被丢掉）",
            resolved !is LoadedFontFamily,
        )

        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val text = AnnotatedString("abcdefghijklmnop")
        fun widthAt(weight: Int): Int = measurer.measure(
            text = text,
            style = TextStyle(fontFamily = resolved, fontSize = 20.sp, fontWeight = FontWeight(weight)),
        ).size.width

        assertTrue(
            "常规档与粗档量出来的宽度应当不同（同宽说明字重没落到字形面上）",
            widthAt(400) < widthAt(700),
        )
    }

    /** 字体列表里没有这个族时 [composeFontFamily] 回 null（调用方回退界面默认字体）。 */
    @Test
    fun unknownFamilyFallsBack() {
        assertNull(composeFontFamily("Orilumn No Such Family", null, null))
    }
}
