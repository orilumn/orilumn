package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile

/**
 * 批注字重的落地口径（面板「字重」页只给批注字体用）。
 *
 * 批注文字走自研断行引擎，引擎要的是**数值字重 + 字族名**。两件事分别成立才算
 * 读者选的那一档真的生效：设置里的档位原样交给引擎（[noteFontWeightIntOf]），
 * 且「没配」不会被悄悄变成「显式常规」（[noteFontWeightOf] 回 null）。
 */
class NoteFontWeightTest {

    @Test
    fun noteFontWeightFollowsSetting() {
        fun weightOf(w: Int): FontWeight? = ReaderSettings(noteFont = "Hannotate SC", noteFontWeight = w)
            .let { s -> noteFontWeightOf(s, TypographicProfile.build(s)) }

        assertEquals(FontWeight(400), weightOf(400))
        assertEquals(FontWeight(700), weightOf(700))
        // 0 = 没配（换字体时清零）；越界值同样当没配，不写死成 400。
        assertNull(weightOf(0))
        assertNull(weightOf(1000))
        // 批注自己挑过字体时不会去借正文的字重（只有「跟随正文」才照搬）。
        val ownFont = ReaderSettings(
            noteFont = "Hannotate SC",
            noteFontWeight = 0,
            fontBody = "BodyFace",
            fontWeightAnchorsBySlot = mapOf("fontBody|BodyFace" to 300),
        )
        assertNull(noteFontWeightOf(ownFont, TypographicProfile.build(ownFont)))
    }

    @Test
    fun blankNoteFontFollowsBodySlotWeight() {
        val settings = ReaderSettings(
            fontBody = "BodyFace",
            fontWeightAnchorsBySlot = mapOf("fontBody|BodyFace" to 300),
        )
        assertEquals(FontWeight(300), noteFontWeightOf(settings, TypographicProfile.build(settings)))
    }

    @Test
    fun blankNoteFontFollowsThemeBodyWeight() {
        val settings = ReaderSettings(
            layoutTheme = "traditional",
            fontSerif = "SerifFace",
        )
        assertEquals(FontWeight(400), noteFontWeightOf(settings, TypographicProfile.build(settings)))
    }

    /** 引擎要的是数值：没配时落到字体默认档 400，与旧 Compose 路径的 Normal 同值。 */
    @Test
    fun engineWeightFallsBackToNormal() {
        fun engineWeight(settings: ReaderSettings): Int =
            noteFontWeightIntOf(settings, TypographicProfile.build(settings))

        assertEquals(700, engineWeight(ReaderSettings(noteFont = "Hannotate SC", noteFontWeight = 700)))
        assertEquals(400, engineWeight(ReaderSettings(noteFont = "Hannotate SC", noteFontWeight = 0)))
    }

    @Test
    fun noteFontStackFollowsReaderThenBodyThenTheme() {
        // 1) 批注单独挑过族：就是它。
        assertEquals(
            listOf("NoteFace"),
            noteFontStackOf(ReaderSettings(noteFont = "NoteFace"), TypographicProfile.build(ReaderSettings(noteFont = "NoteFace"))),
        )
        // 2) 没挑：跟随正文槽的族。
        assertEquals(
            listOf("BodyFace"),
            noteFontStackOf(ReaderSettings(fontBody = "BodyFace"), TypographicProfile.build(ReaderSettings(fontBody = "BodyFace"))),
        )
        // 3) 正文也没挑：主题给正文的通用族。
        assertEquals(
            listOf("sans-serif"),
            noteFontStackOf(ReaderSettings(layoutTheme = "modern"), TypographicProfile.build(ReaderSettings(layoutTheme = "modern"))),
        )
        assertEquals(
            listOf("serif"),
            noteFontStackOf(ReaderSettings(layoutTheme = "traditional"), TypographicProfile.build(ReaderSettings(layoutTheme = "traditional"))),
        )
        // 4) 原书设置：正文由书的 CSS 决定 ⇒ 空栈（引擎用平台默认字体）。
        assertEquals(
            emptyList<String>(),
            noteFontStackOf(ReaderSettings(layoutTheme = "original"), TypographicProfile.build(ReaderSettings(layoutTheme = "original"))),
        )
    }
}
