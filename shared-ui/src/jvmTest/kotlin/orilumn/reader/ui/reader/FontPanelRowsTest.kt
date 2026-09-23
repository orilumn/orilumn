package orilumn.reader.ui.reader

import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4a 字体行模型单测：分区（跟随原书/已导入/系统字体/已隐藏）+ 隐藏下沉 + 选择标记。
 * （排序口径由 [familyNameComparator] 保证，JVM 与 Android 同为拼音 Collator。）
 */
class FontPanelRowsTest {

    private fun imported(family: String, hidden: Boolean = false) = FontEntry.Imported(
        FontFace(id = family.hashCode().toLong(), familyName = family, displayName = family, path = "/f.ttf", lang = "cjk", hidden = hidden),
    )

    private fun system(family: String, subfamily: String = "", hidden: Boolean = false, displayName: String = "") =
        FontEntry.System(family, family.hashCode().toLong() + subfamily.hashCode().toLong(), subfamily, hidden, displayName)

    @Test
    fun sectionsAndSelection() {
        val rows = buildFontRows(
            listOf(imported("B"), system("A"), imported("C")),
            selectedFamily = "A",
            canImport = true,
        )
        // 跟随原书 + 导入区（一行双按钮） + 2 分区头 + 3 行。
        assertTrue(rows[0] is FontPanelRow.FollowOriginal)
        assertEquals(false, (rows[0] as FontPanelRow.FollowOriginal).selected)
        assertTrue(rows[1] is FontPanelRow.Import)
        val headers = rows.filterIsInstance<FontPanelRow.Header>().map { it.title }
        assertEquals(listOf("已导入", "系统字体"), headers)
        val selected = rows.filterIsInstance<FontPanelRow.Entry>().single { it.selected }
        assertEquals("A", selected.family)
    }

    @Test
    fun subtitleGapScalesWithGlyphHeightAndClamps() {
        // 行盒已是真墨迹高度（栅格真值）后名/重不会重叠，气口只微量按比例加（0.15），
        // 并钳 [min,max]（常规行恒 6dp 兜底，高字形行封顶 8dp——不再有 12dp 的离身感）。
        assertEquals(6f, subtitleGapPx(10, 6f, 8f))   // 10*0.15=1.5 → 兜底 6
        assertEquals(6f, subtitleGapPx(30, 6f, 8f))   // 4.5 → 兜底 6
        assertEquals(6f, subtitleGapPx(40, 6f, 8f))   // 6（整好 == 下限）
        assertEquals(7.5f, subtitleGapPx(50, 6f, 8f), 0.001f) // 7.5（0.15 比率浮点折损）
        assertEquals(8f, subtitleGapPx(60, 6f, 8f))   // 9 → 封顶 8
        assertEquals(8f, subtitleGapPx(80, 6f, 8f))   // 12 → 封顶 8
        // 单调：字形越高间隙越大（钳制区内）。
        assertTrue(subtitleGapPx(53, 6f, 8f) > subtitleGapPx(41, 6f, 8f))
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
    fun importRowNeedsAnyCapability() {
        // 一行双按钮：任一能力位开即出行（具体按钮显隐在渲染层 showLocal/showWifi），双关不出行。
        fun has(canImport: Boolean, canWifi: Boolean) = buildFontRows(
            listOf(system("A")), selectedFamily = "",
            canImport = canImport, canWifiImport = canWifi,
        ).any { it is FontPanelRow.Import }
        assertTrue(has(canImport = true, canWifi = false))
        assertTrue(has(canImport = false, canWifi = true))
        assertEquals(false, has(canImport = false, canWifi = false))
    }

    @Test
    fun hiddenSinksToHiddenSection() {
        val rows = buildFontRows(
            listOf(imported("B", hidden = true), system("A"), system("H", hidden = true)),
            selectedFamily = "",
            canImport = false,
            canWifiImport = false,
        )
        // 无导入入口（双能力位皆关）；隐藏区只在有隐藏项时出现。
        assertTrue(rows.none { it is FontPanelRow.Import })
        val headers = rows.filterIsInstance<FontPanelRow.Header>().map { it.title }
        assertEquals(listOf("已导入", "系统字体", "已隐藏"), headers)
        val hiddenRows = rows.filterIsInstance<FontPanelRow.Entry>()
            .filter { it.members.all { m -> m.hidden } }.map { it.family }.sorted()
        assertEquals(listOf("B", "H"), hiddenRows)
        // 隐藏行永不标记选中。
        assertTrue(rows.filterIsInstance<FontPanelRow.Entry>().none { it.members.all { m -> m.hidden } && it.selected })
        assertTrue((rows[0] as FontPanelRow.FollowOriginal).selected)
    }

    @Test
    fun emptyImportedHints() {
        val rows = buildFontRows(listOf(system("A")), selectedFamily = "", canImport = false)
        val hint = rows.filterIsInstance<FontPanelRow.EmptyHint>().single()
        assertEquals("暂无导入字体", hint.text)
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
        assertTrue(rows.none { it is FontPanelRow.Import })
        val songti = rows.filterIsInstance<FontPanelRow.Entry>().single { it.family == "Songti SC" }
        assertEquals(3, songti.members.size)
        assertTrue(songti.selected)
        // 无字重的系统族（subfamily 空）也照常单行展示。
        val heiti = rows.filterIsInstance<FontPanelRow.Entry>().single { it.family == "Heiti SC" }
        assertEquals(1, heiti.members.size)
    }

    @Test
    fun desktopHidesImportedSection() {
        // 桌面口径：仅系统字体，无导入入口/已导入区，隐藏区也只收系统行。
        val rows = buildFontRows(
            listOf(imported("B"), system("A"), imported("H", hidden = true), system("S", hidden = true)),
            selectedFamily = "",
            canImport = false,
            showImported = false,
        )
        assertTrue(rows.none { it is FontPanelRow.Import })
        val headers = rows.filterIsInstance<FontPanelRow.Header>().map { it.title }
        assertEquals(listOf("系统字体", "已隐藏"), headers)
        val fams = rows.filterIsInstance<FontPanelRow.Entry>().map { it.family }.sorted()
        assertEquals(listOf("A", "S"), fams)
    }
}
