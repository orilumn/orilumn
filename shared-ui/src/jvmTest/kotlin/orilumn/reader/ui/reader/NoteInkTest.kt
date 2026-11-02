package orilumn.reader.ui.reader

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旁注「使用标注同色字体」的明度调节：**只动明度**，色相与饱和度必须原样，
 * 且调完要真的看得清（对比度 ≥ 4.5）。
 */
class NoteInkTest {

    private val dayBg = 0xFFF4F2EC.toInt() // 象牙白
    private val nightBg = 0xFF121212.toInt()

    @Test
    fun `浅底上的浅色高亮压暗到达标`() {
        val palePink = 0x73FFB3B3 // 高亮槽：45% 透明度的浅粉
        val ink = noteInkColor(palePink, dayBg)
        assertTrue("日间浅底必须够深", contrastRatio(ink, dayBg) >= NOTE_INK_MIN_CONTRAST - 1e-6)
        assertTrue(
            "应该更暗",
            relativeLuminance(ink) < relativeLuminance(palePink or 0xFF000000.toInt()),
        )
        assertHueSaturationKept(palePink, ink)
        assertEquals("文字必须不透明", 255, (ink ushr 24) and 0xFF)
    }

    @Test
    fun `深底上的深色标注提亮到达标`() {
        val navy = 0xFF1A237E.toInt()
        val ink = noteInkColor(navy, nightBg)
        assertTrue("夜间深底必须够亮", contrastRatio(ink, nightBg) >= NOTE_INK_MIN_CONTRAST - 1e-6)
        assertTrue("应该更亮", relativeLuminance(ink) > relativeLuminance(navy))
        assertHueSaturationKept(navy, ink)
    }

    @Test
    fun `本来就够清楚的颜色不动`() {
        val blue = 0xFF1565C0.toInt() // 在白底上对比度足够
        assertEquals(blue, noteInkColor(blue, dayBg))
    }

    @Test
    fun `中途明度只调够用的那一档`() {
        // 浅灰字在象牙白上不够，压暗后仍应是灰（h/s 不变），且比纯黑浅。
        val gray = 0xFF9E9E9E.toInt()
        val ink = noteInkColor(gray, dayBg)
        assertTrue("必须达标", contrastRatio(ink, dayBg) >= NOTE_INK_MIN_CONTRAST - 1e-6)
        assertTrue("不该一路压成纯黑", relativeLuminance(ink) > 0.0)
        assertEquals("灰调不该染色", (ink ushr 16) and 0xFF, ink and 0xFF)
    }

    @Test
    fun `纯灰在深底上提亮`() {
        val darkGray = 0xFF3A3A3A.toInt()
        val ink = noteInkColor(darkGray, nightBg)
        assertTrue("必须达标", contrastRatio(ink, nightBg) >= NOTE_INK_MIN_CONTRAST - 1e-6)
        assertTrue("应该更亮", relativeLuminance(ink) > relativeLuminance(darkGray))
    }

    @Test
    fun `开了批注底色时按混色后的底算`() {
        // 浅粉标底色铺在象牙白上：混完的底比纸面更亮更粉，浅粉字压上去更糊。
        val palePink = 0x7381C784 // 45% 透明度的浅绿高亮
        val backdrop = blendOver(dayBg, palePink or 0xFF000000.toInt(), NOTE_BUBBLE_BG_ALPHA)
        val ink = noteInkColor(palePink, backdrop)
        assertTrue("压在铺底色上也要达标", contrastRatio(ink, backdrop) >= NOTE_INK_MIN_CONTRAST - 1e-6)
        assertHueSaturationKept(palePink, ink)
        // 混色方向：标底色比纸面暗时，混出来的底必须变暗。
        val darkTint = 0xFF1565C0.toInt()
        val darkerBackdrop = blendOver(dayBg, darkTint, NOTE_BUBBLE_BG_ALPHA)
        assertTrue("深色标底会把纸面压暗", relativeLuminance(darkerBackdrop) < relativeLuminance(dayBg))
        // 全透明 / 全不透明是端点。
        assertEquals(dayBg, blendOver(dayBg, darkTint, 0f))
        assertEquals(0xFF1565C0.toInt(), blendOver(dayBg, darkTint, 1f))
    }

    private fun assertHueSaturationKept(before: Int, after: Int) {
        val (h0, s0, _) = rgbToHsl(before or 0xFF000000.toInt())
        val (h1, s1, _) = rgbToHsl(after)
        if (s0 < 0.02f) return
        val dh = abs(h0 - h1).let { if (it > 0.5f) 1f - it else it }
        assertTrue("色相被改了：$h0 → $h1", dh < 0.01f)
        assertEquals("饱和度被改了：$s0 → $s1", s0.toDouble(), s1.toDouble(), 0.01)
    }
}
