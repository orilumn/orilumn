package orilumn.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置字段 → 是否进版式管线」的分类锁（用户层·跨端同规则的护栏）。
 *
 * 背景：共享面板把设置分成两类提交（排版类 `onCommitTypography` / 纯 UI `onCommitLight`，
 * 见 `ReaderSettingsPanel` KDoc 语义条约），平板按回调分流、桌面两条合流只能自己比对
 * ——比对规则即 [ReaderSettings.withoutNonLayout]。这族字段漏登记一个，症状就是
 * 「切一个纯 UI 开关触发一次本章全量重排 + 整书重排请求 + 两次落位推送」：
 * 无害但浪费（`LayoutParamKey.fromProfile` 不含这些字段 ⇒ 磁盘表不会被误删），
 * 且落位推送会撞锚页漏斗的 BUSY-DROP，看起来像「切了没生效」。
 *
 * 本锁三件事：
 *  1. 非版式族逐字段断言：单独改它，[withoutNonLayout] 必须**相等**（不触发重排）；
 *  2. 版式族逐字段断言：单独改它，[withoutNonLayout] 必须**不等**（要触发重排）；
 *  3. 完备性：从 `ReaderSettings.kt` 源码抽全部 `val` 字段名，断言它恰好被上述两族
 *     划分覆盖且无交集 —— **新增字段忘了分类即红**，这是本锁存在的唯一理由。
 *
 * ⚠ 完备性靠源码扫描（模块工作目录相对路径），字段改名时本测试与源码同改即可。
 */
class SettingsLayoutScopeTest {

    private val base = ReaderSettings()

    /** 不进版式管线（清中性值后比较相等）。 */
    private val nonLayoutFields = listOf(
        "brightness", "brightnessFollowSystem", "brightnessOffset", "eyeProtectionLevel",
        "brightnessGestureLeft", "brightnessGestureRight", "brightnessGestureTwo",
        "pageAnim", "pageAnimationMode", "autoContinue", "pageNum", "coverStretch",
        "showHiddenFonts",
    )

    /** 进版式管线（进 [orilumn.reader.engine.text.LayoutParamKey] 或经 profile 影响版面）。 */
    private val layoutFields = listOf(
        "lineSpacing", "firstLineIndent", "paragraphSpacing", "paragraphGap",
        "letterSpacing", "cjkLatinSpacing",
        "marginTop", "marginBottom", "marginLeft", "marginRight",
        "fontBody", "fontTitle", "fontCode", "useOriginalStyle", "useUserScripts",
        "fontScale", "layoutTheme", "fontSize",
        "fontWeightAnchors", "fontWeightAnchorsBySlot",
    )

    /**
     * 不影响分页、但**本次不登记**进 [ReaderSettings.withoutNonLayout] 的一族：颜色与持久化元数据。
     *
     * 它们确实只改像素不改版面（夜间/配色切换在两端都仍走排版提交 → 白跑一次本章重排，
     * 指纹不变故磁盘表不误删）。**这是刻意留下的现状**，不在本次修复范围内：
     * 摘出去要先确认阅读面配色链路（`ReaderLightMask` 盖章 + 主题探针）对无重排的假设成立。
     * 本测试锁住「它们当前仍在排版路径上」，将来要改必须连本族一起搬。
     */
    private val inertFields = listOf(
        "theme", "scheme", "bgOverride", "fgOverride", "schemaVersion",
    )

    /** 造一个「只把该字段改成明显不同的值」的实例。 */
    private fun mutate(field: String, s: ReaderSettings = base): ReaderSettings = when (field) {
        "brightness" -> s.copy(brightness = if (s.brightness == 100) 42 else 100)
        "brightnessFollowSystem" -> s.copy(brightnessFollowSystem = !s.brightnessFollowSystem)
        "brightnessOffset" -> s.copy(brightnessOffset = s.brightnessOffset + 13)
        "eyeProtectionLevel" -> s.copy(eyeProtectionLevel = if (s.eyeProtectionLevel == 0) 2 else 0)
        "brightnessGestureLeft" -> s.copy(brightnessGestureLeft = !s.brightnessGestureLeft)
        "brightnessGestureRight" -> s.copy(brightnessGestureRight = !s.brightnessGestureRight)
        "brightnessGestureTwo" -> s.copy(brightnessGestureTwo = !s.brightnessGestureTwo)
        "pageAnim" -> s.copy(pageAnim = !s.pageAnim)
        "pageAnimationMode" -> s.copy(pageAnimationMode = if (s.pageAnimationMode == "slide") "curl" else "slide")
        "autoContinue" -> s.copy(autoContinue = !s.autoContinue)
        "pageNum" -> s.copy(pageNum = !s.pageNum)
        "coverStretch" -> s.copy(coverStretch = !s.coverStretch)
        "showHiddenFonts" -> s.copy(showHiddenFonts = !s.showHiddenFonts)

        "lineSpacing" -> s.copy(lineSpacing = s.lineSpacing + 0.13)
        "firstLineIndent" -> s.copy(firstLineIndent = s.firstLineIndent + 0.11)
        "paragraphSpacing" -> s.copy(paragraphSpacing = s.paragraphSpacing + 0.17)
        "paragraphGap" -> s.copy(paragraphGap = s.paragraphGap + 0.19)
        "letterSpacing" -> s.copy(letterSpacing = s.letterSpacing + 0.23)
        "cjkLatinSpacing" -> s.copy(cjkLatinSpacing = s.cjkLatinSpacing + 1.0)
        "marginTop" -> s.copy(marginTop = s.marginTop + 7)
        "marginBottom" -> s.copy(marginBottom = s.marginBottom + 7)
        "marginLeft" -> s.copy(marginLeft = s.marginLeft + 7)
        "marginRight" -> s.copy(marginRight = s.marginRight + 7)
        "fontBody" -> s.copy(fontBody = if (s.fontBody.isEmpty()) "ZZProbe" else "")
        "fontTitle" -> s.copy(fontTitle = if (s.fontTitle.isEmpty()) "ZZProbe" else "")
        "fontCode" -> s.copy(fontCode = if (s.fontCode.isEmpty()) "ZZProbe" else "")
        "useOriginalStyle" -> s.copy(useOriginalStyle = !s.useOriginalStyle)
        "useUserScripts" -> s.copy(useUserScripts = !s.useUserScripts)
        "fontScale" -> s.copy(fontScale = s.fontScale + 3.0)
        "fontSize" -> s.copy(fontSize = s.fontSize + 3)
        "layoutTheme" -> s.copy(layoutTheme = if (s.layoutTheme == "original") "modern" else "original")
        "fontWeightAnchors" -> s.copy(fontWeightAnchors = s.fontWeightAnchors + ("p" to 700))
        "fontWeightAnchorsBySlot" -> s.copy(fontWeightAnchorsBySlot = s.fontWeightAnchorsBySlot + ("body" to 700))

        "theme" -> s.copy(theme = if (s.theme == "sepia") "light" else "sepia")
        "scheme" -> s.copy(scheme = if (s.scheme == "day") "night" else "day")
        "bgOverride" -> s.copy(bgOverride = if (s.bgOverride.isEmpty()) "#123456" else "")
        "fgOverride" -> s.copy(fgOverride = if (s.fgOverride == "#000000") "#fefefe" else "#000000")
        "schemaVersion" -> s.copy(schemaVersion = s.schemaVersion + 1)
        else -> error("未分类字段：$field")
    }

    @Test
    fun `非版式族逐字段不触发重排`() {
        for (f in nonLayoutFields) {
            val changed = mutate(f)
            assertTrue("探针无效：$f 改成后与基线相等", changed != base)
            assertEquals(
                "纯 UI/亮度字段 [$f] 变更不得被判定为版式变化（桌面据此跳过整书重排）",
                base.withoutNonLayout(), changed.withoutNonLayout(),
            )
        }
    }

    @Test
    fun `版式族逐字段必触发重排`() {
        for (f in layoutFields) {
            val changed = mutate(f)
            assertTrue("探针无效：$f 改成后与基线相等", changed != base)
            assertFalse(
                "版式字段 [$f] 变更必须被判定为版式变化",
                base.withoutNonLayout() == changed.withoutNonLayout(),
            )
        }
    }

    @Test
    fun `亮度族仍被 withoutLight 覆盖`() {
        for (f in nonLayoutFields.filter { it.startsWith("brightness") || it.startsWith("eyeProtection") }) {
            assertEquals(
                "withoutLight 窄口径仍应覆盖 [$f]",
                base.withoutLight(), mutate(f).withoutLight(),
            )
        }
    }

    @Test
    fun `颜色族当前仍在排版路径上`() {
        for (f in inertFields) {
            assertFalse(
                "[$f] 本次刻意不登记进 withoutNonLayout（要摘出去得连同阅读面配色链路一起改）",
                base.withoutNonLayout() == mutate(f).withoutNonLayout(),
            )
        }
    }

    @Test
    fun `完备性——新增字段必须分类`() {
        val declared = declaredValFields()
        val nonLayout = nonLayoutFields.toSet()
        val layout = layoutFields.toSet()
        val inert = inertFields.toSet()
        assertEquals("非版式族与版式族有交集", emptySet<String>(), nonLayout.intersect(layout))
        assertEquals("非版式族与惰性族有交集", emptySet<String>(), nonLayout.intersect(inert))
        assertEquals("版式族与惰性族有交集", emptySet<String>(), layout.intersect(inert))
        val unclassified = declared - nonLayout - layout - inert
        assertTrue(
            "ReaderSettings 新增字段未分类（忘记登记进 withoutNonLayout？）：$unclassified",
            unclassified.isEmpty(),
        )
        val stale = (nonLayout + layout + inert) - declared
        assertTrue("分类表里有已不存在的字段：$stale", stale.isEmpty())
    }

    /** 从 `ReaderSettings.kt` 源码抽 `val xxx:` 字段名（跳过 companion/局部声明）。 */
    private fun declaredValFields(): Set<String> {
        val file = File("src/commonMain/kotlin/orilumn/reader/data/settings/ReaderSettings.kt")
        assertTrue("找不到 ${file.absolutePath}（测试工作目录应为 :common 模块目录）", file.exists())
        val head = file.readText().substringBefore("companion object")
        return Regex("""^\s{4}val\s+([A-Za-z][A-Za-z0-9_]*)\s*:""", RegexOption.MULTILINE)
            .findAll(head).map { it.groupValues[1] }.toSet()
    }
}