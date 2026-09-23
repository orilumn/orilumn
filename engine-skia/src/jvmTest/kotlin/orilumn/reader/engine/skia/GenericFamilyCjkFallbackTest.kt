package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传统模式 = 衬线正文（中文宋体）：浏览器内核把 `serif` 映射成纯候选链（Times… 拉丁 →
 * Noto Serif CJK / SimSun / Songti… 中文宋体），不把平台裸 `serif` 置首——skiko-Android 的
 * FontMgr 认 `serif` 却无 Minikin `serif→NotoSerifCJK` 链，置首会让中文被其内部回退的黑体吃掉。
 * 系统有宋体（Android `/system/fonts/NotoSerifCJK-Regular.ttc`）时宿主以候选里的真名补装进池，
 * 映射即命中。
 *
 * 真机 FontMgr 不可注入，这里用 [SkParagraphFactory.resolveFamiliesFor] 的匹配谓词复现
 * Android 的行为；[resolveFamilies] 只是把 [org.jetbrains.skia.FontMgr.matchFamilyStyle] 投影成
 * 同一谓词，逻辑与之一一对应。
 */
class GenericFamilyCjkFallbackTest {

    /** 模拟 Android skiko FontMgr：认通用别名（serif/sans-serif/monospace）+ 自带 Noto CJK 实族。 */
    private val androidLike: (String) -> Boolean = { name ->
        name in setOf(
            "serif", "sans-serif", "monospace",
            "Times New Roman", "Times", "Georgia",
            "Noto Serif CJK TC", "Noto Serif CJK SC", "Noto Serif CJK JP", "Noto Serif CJK KR",
            "Noto Sans CJK SC", "Noto Sans CJK TC", "Noto Sans CJK JP", "Noto Sans CJK KR",
            "Roboto", "Helvetica", "Arial",
        )
    }

    @Test
    fun serifExpandsPureCandidatesWithoutBarePlatformGeneric() {
        // 传统模式正文 fontFamilies = ["serif"]（UI 层槽=读者层额外 CSS）。浏览器内核的 serif 映射
        // 是纯候选链（单源）：skiko-Android 认裸 serif 但该族内部回退是黑体（vivo 实测），置首会让
        // 中文永远够不到候选里的宋体——故不得把平台裸 serif 放进结果。系统有宋体时宿主以
        // "Noto Serif CJK SC" 真名补装进池，候选链即命中宋体。
        val resolved = SkParagraphFactory.resolveFamiliesFor("p", listOf("serif"), false, androidLike)
        assertTrue("serif 不得带平台裸通用族（否则黑体内部回退抢在宋体前）", !resolved.contains("serif"))
        assertEquals("候选链首族（拉丁衬线）", "Times New Roman", resolved[0])
        assertTrue(
            "serif 须含 Noto CJK 衬线候选（宿主以真名补装即宋体落地）",
            resolved.any { it == "Noto Serif CJK SC" || it == "Noto Serif CJK TC" },
        )
        assertTrue(
            "serif 须含 Songti/STSong 衬线候选",
            resolved.any { it.contains("Songti", ignoreCase = true) || it.contains("STSong", ignoreCase = true) || it.contains("SimSun", ignoreCase = true) },
        )
    }

    @Test
    fun sansSerifKeepsBarePlatformGeneric() {
        // 现代模式 fontFamilies = ["sans-serif"]：平台无衬线族内部回退即黑体（现代模式期望），
        // 保留平台通用族置首不动；serif 才纯走候选链。
        val resolved = SkParagraphFactory.resolveFamiliesFor("p", listOf("sans-serif"), false, androidLike)
        assertTrue("sans-serif 仍留平台通用族", resolved.contains("sans-serif"))
        assertTrue("sans-serif 仍含 CJK 无衬线候选", resolved.any { it.contains("Noto Sans CJK") })
    }

    @Test
    fun serifTailStillsFallsToCjkSerifOnTopOfBookStack() {
        // 书栈 + 传统模式垫底 serif（StyleComputer 兜底合并后结尾是 serif）：
        // 中途的 Noto Sans CJK 不得把中文截断成黑体，尾部 serif 仍应把 CJK 引到衬线。
        val stack = listOf("思源宋体 VF", "Noto Sans CJK SC", "serif")
        val resolved = SkParagraphFactory.resolveFamiliesFor("p", stack, false, androidLike)
        assertTrue("尾部 serif 须展开出 CJK 衬线候选", resolved.any { it.startsWith("Noto Serif CJK") })
    }

    @Test
    fun platformWithoutGenericMatchStillExpandsPureCandidates() {
        // macOS CoreText 类平台：不认通用名 → 候选链即为全部（既有行为，回归原语义）。
        val resolved = SkParagraphFactory.resolveFamiliesFor("p", listOf("serif"), false) {
            it in setOf("Times New Roman", "Songti SC", "Noto Serif CJK SC")
        }
        assertEquals("候选链首族", "Times New Roman", resolved[0])
        assertTrue("纯候选展开须含 CJK 衬线", resolved.any { it.contains("Songti") || it.startsWith("Noto Serif CJK") })
    }
}