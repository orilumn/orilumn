package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统栏**图标明暗**判据（`TypographicProfile.wantsLightBarIcons` / `isDarkBackground`）。
 *
 * 修的 bug：判据原先在 `ReaderActivity` 写成 `effective.scheme != "night"`，只看 `scheme` 标签。
 * 但 `day` 分支的底色就是 `bgOverride` **原值**（用户能用滑块调深、能存深色自定义预设），
 * 那时底色是暗的而 `scheme` 仍是 `day` ⇒ 判「深色图标」画在暗底上，**看不见**。
 *
 * 判据按**实际底色**算，单源在 [TypographicProfile]（排版层背景色派生），
 * 平台侧（Android `WindowInsetsControllerCompat`）只消费结果。
 *
 * 判据本身**不看 scheme**（只吃最终的 ARGB），所以这些测试直接钉 ARGB，不经设置层 ——
 * 设置层那一条另有 `wantsLightBarIcons` 的用例覆盖。
 */
class SystemBarIconToneTest {

    private fun argb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

    // ---------- 判据本身：暗/亮分界 ----------

    @Test
    fun `dark backgrounds are recognised as dark`() {
        // HSL 明度 < 0.5 为暗。阈值来自 nightBackgroundOf 的「已暗则原样保留」，共用同一判据。
        for (hex in listOf("#000000", "#121212", "#1a1a1a", "#222222", "#2b2b2b", "#101820", "#1b2631")) {
            assertTrue("$hex 应判为暗底", TypographicProfile.isDarkBackground(argb(hex)))
        }
    }

    @Test
    fun `light backgrounds are recognised as light`() {
        for (hex in listOf("#ffffff", "#f4f2ec", "#fffbf0", "#eaecee", "#f2ecde", "#e0eee8", "#d6ecf0", "#fbeff2", "#e8e0f0")) {
            assertFalse("$hex 应判为亮底", TypographicProfile.isDarkBackground(argb(hex)))
        }
    }

    /**
     * **单源牙齿**：`isDarkBackground` 与 `nightBackgroundOf` 的「已暗则原样保留」必须是同一个判定。
     *
     * 两者一旦各写一份阈值，改了其中一个就会分叉 —— 表现为「夜间背景已按新阈值变亮/变暗，
     * 状态栏图标却按旧阈值判」，极难定位。故直接断言两条路径对同一批颜色结论一致。
     */
    @Test
    fun `dark judgement agrees with the night-background rule it shares a threshold with`() {
        val probe = listOf(
            0xFF000000.toInt(), 0xFF080808.toInt(), 0xFF121212.toInt(), 0xFF202020.toInt(),
            0xFF2b2b2b.toInt(), 0xFF404040.toInt(), 0xFF606060.toInt(), 0xFF7f7f7f.toInt(),
            0xFF808080.toInt(), 0xFF9e9e9e.toInt(), 0xFFB0B0B0.toInt(), 0xFFD6ECF0.toInt(),
            0xFFE0EEE8.toInt(), 0xFFF4F2EC.toInt(), 0xFFFFFFFF.toInt(),
        )
        for (c in probe) {
            val dark = TypographicProfile.isDarkBackground(c)
            val nightKeptAsIs = TypographicProfile.nightBackgroundOf(c) == c
            assertEquals(
                "颜色 #%08x 上两个判据分叉了：isDarkBackground=%s 而 nightBackgroundOf 原样保留=%s".format(c, dark, nightKeptAsIs),
                dark,
                nightKeptAsIs,
            )
        }
    }

    /** 反转等价：亮色进夜间必被反转、暗色原样，两个判据同源的结果。 */
    @Test
    fun `nightBackgroundOf still inverts light and keeps dark unchanged`() {
        // 行为回归：本次把 HSL 计算抽成共用函数，若重写时手滑就会在这里露馅。
        assertEquals("亮色应被夜间反转（结果不等于自身）", false, TypographicProfile.nightBackgroundOf(0xFFF4F2EC.toInt()) == 0xFFF4F2EC.toInt())
        assertTrue("夜间反白色应仍是暗色", TypographicProfile.isDarkBackground(TypographicProfile.nightBackgroundOf(0xFFFFFFFF.toInt())))
        assertEquals("暗色进夜间应原样", 0xFF121212.toInt(), TypographicProfile.nightBackgroundOf(0xFF121212.toInt()))
        assertTrue("夜间反米色应仍是暗色", TypographicProfile.isDarkBackground(TypographicProfile.nightBackgroundOf(0xFFF4F2EC.toInt())))
    }

    // ---------- 设置层入口：这就是修的那个 bug ----------

    /**
     * **本轮修复的核心用例**：深色背景 + `scheme=day`。
     *
     * 旧判据 `scheme != "night"` 在这里给 `true`（深色图标）⇒ 画在暗底上看不见；
     * 新判据按实际底色给 `false`（浅色图标）⇒ 看得见。
     */
    @Test
    fun `dark background under day scheme asks for light icons`() {
        val s = ReaderSettings.DEFAULT.copy(scheme = "day", bgOverride = "#111111")
        assertFalse("深底必须用浅色图标（旧判据在这里错给 true）", TypographicProfile.wantsLightBarIcons(s))
    }

    /** 对称情形：夜间 + 浅色 bgOverride。夜间底色会被 nightBackgroundOf 反转成暗色，故仍是浅色图标。 */
    @Test
    fun `night scheme keeps light icons even when a light background override is stored`() {
        val s = ReaderSettings.DEFAULT.copy(scheme = "night", bgOverride = "#ffffff")
        assertFalse("夜间底色必暗（nightBackgroundOf 会反转），故用浅色图标", TypographicProfile.wantsLightBarIcons(s))
    }

    /**
     * **不改现有用户体验**：既有浅色底色下，新判据必须与旧判据（`scheme != "night"`）结论一致。
     *
     * 若这条红，说明修复让某个既有浅色主题翻成了浅色图标 —— 那是回归而非修复。
     * 「内置 8 个主题全浅」那条放在 `shared-ui` 的 [orilumn.reader.ui.reader.ReaderThemeMathTest]
     * —— 内置主题清单是 UI 层的东西，`common` 不依赖 `shared-ui`（依赖方向是反的），够不着。
     */
    @Test
    fun `every light day-scheme background keeps the pre-existing behaviour`() {
        for (hex in listOf("#f4f2ec", "#ffffff", "#fffbf0", "#eaecee", "#f2ecde", "#e0eee8", "#d6ecf0", "#fbeff2", "#e8e0f0")) {
            val s = ReaderSettings.DEFAULT.copy(scheme = "day", bgOverride = hex)
            assertTrue(
                "$hex 是浅底，必须仍要深色图标（与旧判据 scheme!=night 一致）",
                TypographicProfile.wantsLightBarIcons(s),
            )
        }
    }

    @Test
    fun `default background without override stays light`() {
        assertTrue(TypographicProfile.wantsLightBarIcons(ReaderSettings.DEFAULT))
        assertTrue(TypographicProfile.wantsLightBarIcons(ReaderSettings.DEFAULT.copy(bgOverride = "")))
        assertFalse(TypographicProfile.wantsLightBarIcons(ReaderSettings.DEFAULT.copy(scheme = "night")))
    }

    // ---------- 阈值边界 ----------

    /**
     * 阈值两侧：`#7f7f7f`（明度 0.498）判暗、`#808080`（明度 0.502）判亮。
     *
     * 钉住 `l < 0.5` 是**严格**小于：若日后有人写成 `<=` 或 `<= 0.5` 这里会红，
     * 而这种偏移在肉眼上几乎无感、却会让中灰主题的图标色莫名翻面。
     */
    @Test
    fun `threshold boundary is strict and documented`() {
        assertTrue("#7f7f7f 明度 0.498 < 0.5 ⇒ 暗", TypographicProfile.isDarkBackground(argb("#7f7f7f")))
        assertFalse("#808080 明度 0.502 > 0.5 ⇒ 亮", TypographicProfile.isDarkBackground(argb("#808080")))
    }

    /**
     * 透明通道无关：底色恒为不透明（ARGB 的 alpha 恒 `FF`），但若哪天传进来带 alpha 的色，
     * 判据不应被 alpha 带偏（HSL 明度只看 RGB）。这里钉住。
     */
    @Test
    fun `alpha does not shift the judgement`() {
        val opaqueLight = TypographicProfile.isDarkBackground(argb("#f4f2ec"))
        val semiLight = TypographicProfile.isDarkBackground(0x80F4F2EC.toInt())
        assertEquals("alpha 不该影响明暗判定", opaqueLight, semiLight)
    }
}
