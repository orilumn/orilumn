package orilumn.reader.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Linux fontconfig 枚举桥的**折叠口径**（纯逻辑）：别名族折回基族、字重名只留本族字形。
 *
 * 这张表的现实来源是 fontconfig 的 `family` 多值：基族名会出现在该族每个字重上，
 * 而「基名 + 字重」这类别名只挂在对应那个面。真实机器的 fc-list 形如：
 * `SourceHanSerifCN-Medium-6.otf: 思源宋体 CN,Source Han Serif CN,Source Han Serif CN Medium,思源宋体 CN Medium:style=Medium`
 */
class FontconfigFontsTest {

    private fun face(style: String, vararg families: String) =
        FontconfigFonts.Face(families.toList(), style)

    @Test
    fun `weight suffixed alias families fold into the base family`() {
        val folded = FontconfigFonts.fold(
            listOf(
                face("Regular", "思源宋体 CN", "Source Han Serif CN"),
                face("Medium", "思源宋体 CN", "Source Han Serif CN", "Source Han Serif CN Medium", "思源宋体 CN Medium"),
                face("Bold", "思源宋体 CN", "Source Han Serif CN"),
                face("Heavy", "思源宋体 CN", "Source Han Serif CN", "Source Han Serif CN Heavy", "思源宋体 CN Heavy"),
            ),
        )
        assertEquals("别名族必须消失，只留基族", listOf("思源宋体 CN"), folded.families)
        assertEquals(
            "四个字重都要落在基族上",
            listOf("Regular", "Medium", "Bold", "Heavy"),
            folded.faces.getValue("思源宋体 CN").map { it.subfamily },
        )
        assertEquals("基名已是中文名，不再另给展示名", emptyMap<String, String>(), folded.display)
    }

    @Test
    fun `english locale keeps the localized name as display only`() {
        // 英文系统上 fontconfig 把拉丁名排前面：基名 = 拉丁名，中文名只作展示。
        val folded = FontconfigFonts.fold(
            listOf(
                face("Regular", "Source Han Serif CN", "思源宋体 CN"),
                face("Bold", "Source Han Serif CN", "思源宋体 CN"),
            ),
        )
        assertEquals(listOf("Source Han Serif CN"), folded.families)
        assertEquals("思源宋体 CN", folded.display["Source Han Serif CN"])
    }

    @Test
    fun `vendor numeric weight codes merge into one family`() {
        val weights = listOf("503L", "504L", "505L", "506L", "507R", "508R", "509R")
        val folded = FontconfigFonts.fold(
            weights.map { w ->
                face(w, "方正悠宋+ GBK", "FZYouSongJ GBK", "FZYouSongJ GBK $w", "方正悠宋+ GBK $w")
            },
        )
        assertEquals("七个厂商字重档必须只占一行", listOf("方正悠宋+ GBK"), folded.families)
        assertEquals(weights, folded.faces.getValue("方正悠宋+ GBK").map { it.subfamily })
    }

    @Test
    fun `prefix sharing families stay separate`() {
        // DejaVu Sans Mono 的族名里含 DejaVu Sans，但它不是别名族（各自面数相同也各留一行）。
        val folded = FontconfigFonts.fold(
            listOf(
                face("Book", "DejaVu Sans"),
                face("Bold", "DejaVu Sans"),
                face("Book", "DejaVu Sans Mono"),
                face("Bold", "DejaVu Sans Mono"),
            ),
        )
        assertEquals(listOf("DejaVu Sans", "DejaVu Sans Mono"), folded.families)
    }

    @Test
    fun `face without style name falls back to an empty subfamily`() {
        val folded = FontconfigFonts.fold(listOf(face("", "僅有一檔")))
        assertEquals(listOf(""), folded.faces.getValue("僅有一檔").map { it.subfamily })
    }

    @Test
    fun `empty enumeration folds to nothing`() {
        val folded = FontconfigFonts.fold(emptyList())
        assertEquals(emptyList<String>(), folded.families)
        assertTrue(folded.faces.isEmpty())
        assertNull(folded.display["x"])
    }

    /** 本机（Linux）真枚举冒烟：能取到族，且不再出现「族名 + 字重」的别名族、每族字重名不超过十几个。 */
    @Test
    fun `real enumeration merges alias families`() {
        assumeTrue("fontconfig 桥只在 Linux 生效", FontconfigFonts.available())
        val folded = FontconfigFonts.folded()
        assertTrue("应能枚举到系统字体族", (folded?.families?.size ?: 0) > 0)
        val cjk = folded!!.families.filter { fam -> fam.any { it.code in 0x4E00..0x9FFF } }
        assertTrue("中文系统上应有中文族名", cjk.isNotEmpty())
        // 别名族判据：列表里不应同时存在「X」与「X <字重词>」这种成对项（思源宋体 CN / 思源宋体 CN Medium）。
        // 注意只认字重/风格词：`DejaVu Sans Mono`、`Noto Sans CJK JP` 这类是**独立族**，不是别名。
        val weightTokens = setOf(
            "regular", "bold", "italic", "oblique", "light", "thin", "medium", "black", "heavy",
            "book", "demi", "semibold", "extralight", "ultralight", "demilight", "hairline",
            "regularitalic", "bolditalic",
        )
        val pairs = folded.families.filter { fam ->
            val words = fam.split(' ')
            if (words.size < 2) return@filter false
            val tail = words.last().lowercase()
            tail in weightTokens && (words.dropLast(1).joinToString(" ")) in folded.families
        }
        assertEquals("不许留下「基族 + 字重后缀」的重复族：$pairs", emptyList<String>(), pairs)
        assertTrue(
            "单族字重名不该膨胀到全库量级：${folded.faces.entries.sortedByDescending { it.value.size }.take(3).map { it.key to it.value.size }}",
            folded.faces.values.all { it.size <= 64 },
        )
    }
}
