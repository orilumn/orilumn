package orilumn.reader.ui.reader

import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4a 字体行模型单测：单扁平列表（开关 → 导入区 → 跟随原书 → 全部字体按展示名排序）+ 选择标记。
 * （排序口径由 [familyNameComparator] 保证，JVM 与 Android 同为拼音 Collator。）
 */
class FontPanelRowsTest {

    private fun imported(family: String, hidden: Boolean = false, subfamily: String = "") = FontEntry.Imported(
        FontFace(id = (family + subfamily).hashCode().toLong(), familyName = family, displayName = family, subfamily = subfamily, path = "/f.ttf", lang = "cjk", hidden = hidden),
    )

    private fun system(family: String, subfamily: String = "", hidden: Boolean = false, displayName: String = "") =
        FontEntry.System(family, family.hashCode().toLong() + subfamily.hashCode().toLong(), subfamily, hidden, displayName)

    @Test
    fun flatListImportFollowOriginalThenSortedEntries() {
        val rows = buildFontRows(
            listOf(imported("B"), system("A"), imported("C")),
            selectedFamily = "A",
            canImport = true,
        )
        // 导入区 + 跟随原书 + 3 字体行（无分区标题，按展示名排序；有导入区时开关并进导入行）。
        assertTrue(rows[0] is FontPanelRow.Import)
        assertTrue(rows[1] is FontPanelRow.FollowOriginal)
        assertEquals(false, (rows[1] as FontPanelRow.FollowOriginal).selected)
        val entries = rows.filterIsInstance<FontPanelRow.Entry>()
        assertEquals(listOf("A", "B", "C"), entries.map { it.family })
        val selected = entries.single { it.selected }
        assertEquals("A", selected.family)
    }

    @Test
    fun toggleStandaloneWithoutImportRow() {
        // 无导入区（桌面）时开关独立成首行。
        val rows = buildFontRows(
            listOf(system("A")),
            selectedFamily = "",
            canImport = false,
            canWifiImport = false,
        )
        assertTrue(rows[0] is FontPanelRow.Toggle)
        assertTrue(rows[1] is FontPanelRow.FollowOriginal)
    }

    @Test
    fun hiddenExcludedUnlessIncluded() {
        val all = listOf(imported("B", hidden = true), system("A"), system("H", hidden = true))
        // 关：隐藏字体不列出（无已隐藏区）。
        var rows = buildFontRows(all, selectedFamily = "", canImport = false, canWifiImport = false, includeHidden = false)
        assertEquals(listOf("A"), rows.filterIsInstance<FontPanelRow.Entry>().map { it.family })
        // 开：隐藏字体与其他行同列。
        rows = buildFontRows(all, selectedFamily = "", canImport = false, canWifiImport = false, includeHidden = true)
        assertEquals(listOf("A", "B", "H"), rows.filterIsInstance<FontPanelRow.Entry>().map { it.family })
        // 隐藏行永不标记选中。
        assertTrue(rows.filterIsInstance<FontPanelRow.Entry>().none { it.members.all { m -> m.hidden } && it.selected })
        assertTrue(rows.filterIsInstance<FontPanelRow.FollowOriginal>().single().selected)
    }

    @Test
    fun appleLegacyHeiStaysDistinctFromHeitiSc() {
        // Hei（Classic Mac 遗留，name 表无中文名 → 展示回退族名 "Hei"）与 Heiti SC（黑体-简）
        // 展示名不同，各占一行，不得按展示名合族成一行。
        val rows = buildFontRows(
            listOf(
                system("Hei", "Regular"),
                system("Heiti SC", "Light"),
                system("Heiti SC", "Medium"),
            ),
            selectedFamily = "Hei",
            canImport = false,
            canWifiImport = false,
        )
        val entries = rows.filterIsInstance<FontPanelRow.Entry>()
            .filter { it.members.any { m -> m.family == "Hei" || m.family == "Heiti SC" } }
        assertEquals(listOf("Hei", "Heiti SC"), entries.map { it.family }.sorted())
        val hei = entries.single { it.family == "Hei" }
        assertTrue(hei.selected) // 旧槽位 Hei 仍命中自己的行。
        assertEquals(listOf("Regular"), hei.members.map { (it as FontEntry.System).subfamily })
        val heiti = entries.single { it.family == "Heiti SC" }
        assertEquals(listOf("Light", "Medium"), heiti.members.map { (it as FontEntry.System).subfamily })
    }

    @Test
    fun appleLegacyKaiStaysDistinctFromKaitiSc() {
        // Kai（name 表无中文名 → 展示回退族名 "Kai"）≠ Kaiti SC（楷体-简）：同样各占一行，不合族。
        val rows = buildFontRows(
            listOf(
                system("Kai", "Regular"),
                system("Kaiti SC", "Regular"),
            ),
            selectedFamily = "Kai",
            canImport = false,
            canWifiImport = false,
        )
        val entries = rows.filterIsInstance<FontPanelRow.Entry>()
            .filter { it.members.any { m -> m.family == "Kai" || m.family == "Kaiti SC" } }
        assertEquals(listOf("Kai", "Kaiti SC"), entries.map { it.family }.sorted())
    }

    @Test
    fun sourceHanVfMergesIntoRegionalStaticRow() {
        // Source Han Sans VF 与 SC 经中文名链（name 表直读）都落成「思源黑体」：合并成一行；
        // 面数并列时规范族取族名字典序小者（SC 先于 VF）。宋体同理由 {Sans,Serif}×{VF,SC} 各自成行。
        val rows = buildFontRows(
            listOf(
                system("Source Han Sans VF", "Regular", displayName = "思源黑体"),
                system("Source Han Sans SC", "Regular", displayName = "思源黑体"),
                system("Source Han Serif VF", "Regular", displayName = "思源宋体"),
                system("Source Han Serif SC", "Regular", displayName = "思源宋体"),
            ),
            selectedFamily = "Source Han Sans SC",
            canImport = false,
            canWifiImport = false,
        )
        val sans = rows.filterIsInstance<FontPanelRow.Entry>().single {
            it.members.any { m -> m.family.startsWith("Source Han Sans") }
        }
        assertEquals("Source Han Sans SC", sans.family)
        assertTrue(sans.selected)
        val serif = rows.filterIsInstance<FontPanelRow.Entry>().single {
            it.members.any { m -> m.family.startsWith("Source Han Serif") }
        }
        assertEquals("Source Han Serif SC", serif.family)
        // 两族并成两行（不是四行）：思源黑体、思源宋体各一行。
        assertEquals(2, rows.filterIsInstance<FontPanelRow.Entry>().count {
            it.members.any { m -> m.family.startsWith("Source Han") }
        })
    }

    @Test
    fun crossSourceSameDisplayKeepsSeparateRows() {
        // 跨来源同名（导入 Sarasa vs 系统 Sarasa）各占一行：来源标记单一，删除/隐藏不串味。
        val rows = buildFontRows(
            listOf(
                imported("Sarasa Term SC", subfamily = "Bold"),
                system("Sarasa Term SC", "Bold"),
            ),
            selectedFamily = "",
            canImport = false,
            canWifiImport = false,
        )
        val entries = rows.filterIsInstance<FontPanelRow.Entry>()
            .filter { it.family == "Sarasa Term SC" }
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.members.all { m -> m is FontEntry.Imported } })
        assertTrue(entries.any { it.members.all { m -> m is FontEntry.System } })
    }

    @Test
    fun systemWeightsMergeIntoOneRow() {
        // 系统面枚举按族 + 字重落行：同族多字重合并为一行、member 全量保留
        // （副标题字重表在渲染层由 rowSubtitle 出）。
        val rows = buildFontRows(
            listOf(
                system("Songti SC", "Regular"),
                system("Songti SC", "Bold"),
                system("Songti SC", "Black"),
                system("PingFang SC", "Regular"),
                system("Heiti SC"),
            ),
            selectedFamily = "Songti SC",
            canImport = false,
            canWifiImport = false,
        )
        val songti = rows.filterIsInstance<FontPanelRow.Entry>().single { it.family == "Songti SC" }
        assertEquals(3, songti.members.size)
        assertTrue(songti.selected)
        // 无字重的系统族（subfamily 空）也照常单行展示。
        val heiti = rows.filterIsInstance<FontPanelRow.Entry>().single { it.family == "Heiti SC" }
        assertEquals(1, heiti.members.size)
    }

    @Test
    fun desktopHidesImportedSection() {
        // 桌面口径：仅系统字体，无导入入口/已导入行。
        val rows = buildFontRows(
            listOf(imported("B"), system("A"), imported("H", hidden = true), system("S", hidden = true)),
            selectedFamily = "",
            canImport = false,
            showImported = false,
            includeHidden = true,
        )
        assertTrue(rows.none { it is FontPanelRow.Import })
        val fams = rows.filterIsInstance<FontPanelRow.Entry>().map { it.family }.sorted()
        assertEquals(listOf("A", "S"), fams)
    }

    @Test
    fun chineseNamesBeforeLatin() {
        // 中文展示名在前（组内拼音序），英文沉后。
        val rows = buildFontRows(
            listOf(
                system("Helvetica"),
                system("Songti SC", displayName = "宋体-简"),
                imported("Arial"),
                imported("霞鹜文楷"),
            ),
            selectedFamily = "",
            canImport = true,
        )
        val names = rows.filterIsInstance<FontPanelRow.Entry>().map { it.members.first().display }
        assertEquals(listOf("宋体-简", "霞鹜文楷", "Arial", "Helvetica"), names)
    }

    @Test
    fun weightChoicesDedupeByNumericWeight() {
        // 同数值去重（首个保留）、按数值排序；空白字重名展示为默认。
        val members = listOf(
            imported("F", subfamily = "Bold"),
            imported("F", subfamily = "粗体"),
            imported("F", subfamily = "Regular"),
            imported("F", subfamily = ""),
        )
        assertEquals(
            listOf(400 to "Regular", 700 to "Bold"),
            members.weightChoices(),
        )
        assertEquals(listOf(400 to "默认"), listOf(imported("G")).weightChoices())
    }

    @Test
    fun previewMemberPrefersRegular() {
        // 同行多字重：有 Regular 用它（大小写不敏感 + 中文"常规"），无则用第一个。
        val members = listOf(
            imported("F", subfamily = "Bold"),
            imported("F", subfamily = "Regular"),
            imported("F", subfamily = "Italic"),
        )
        assertEquals("Regular", (members.previewMember() as FontEntry.Imported).face.subfamily)
        assertEquals(
            "Bold",
            (listOf(members[0], members[2]).previewMember() as FontEntry.Imported).face.subfamily,
        )
        val cn = listOf(imported("G", subfamily = "粗体"), imported("G", subfamily = "常规"))
        assertEquals("常规", (cn.previewMember() as FontEntry.Imported).face.subfamily)
        val lower = listOf(system("H", "bold"), system("H", "regular"))
        assertEquals("regular", (lower.previewMember() as FontEntry.System).subfamily)
    }

    @Test
    fun subtitleGapScalesWithGlyphHeightAndClamps() {
        // 行盒已是真墨迹高度（栅格真值）后名/重不会重叠，气口只微量按比例加（0.08），
        // 并钳 [min,max]（常规行 2dp 贴紧，高字形行封顶 5dp）。
        assertEquals(2f, subtitleGapPx(10, 2f, 5f))   // 10*0.08=0.8 → 兜底 2
        assertEquals(2.4f, subtitleGapPx(30, 2f, 5f), 0.001f) // 2.4（钳制区内）
        assertEquals(4f, subtitleGapPx(50, 2f, 5f), 0.001f) // 4.0（0.08 比率浮点折损）
        assertEquals(4.8f, subtitleGapPx(60, 2f, 5f), 0.001f) // 4.8（钳制区内）
        assertEquals(5f, subtitleGapPx(80, 2f, 5f))   // 6.4 → 封顶 5
        // 单调：字形越高间隙越大（钳制区内）。
        assertTrue(subtitleGapPx(53, 2f, 5f) > subtitleGapPx(41, 2f, 5f))
    }
}
