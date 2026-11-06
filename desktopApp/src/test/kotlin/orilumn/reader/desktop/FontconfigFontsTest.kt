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

    /** 带 fontconfig 字重刻度的面（`Regular`=80、`SemiBold`=180、`Bold`=200、`Black`=210）。 */
    private fun weightFace(weight: Int, style: String, vararg families: String) =
        FontconfigFonts.Face(families.toList(), style, weight)

    /** 带字重 + 宽度刻度的面（宽度：100 正常、87 半窄、75 窄、63 特窄）。 */
    private fun sizedFace(width: Int, weight: Int, style: String, vararg families: String) =
        FontconfigFonts.Face(families.toList(), style, weight, width)

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

    /**
     * 上游打包 bug：字重被并进族名、nameID16/17 丢失、OS/2 字重又没跟着改，
     * 于是这张面上**根本没有基族名**，按众数折不出来，只能靠「名字说的字重 ≠ 自己声明的字重」认出它。
     * 真实现场：Noto Sans Gunjala Gondi / New Tai Lue 的 Semibold，Noto Sans Sora Sompeng 的「Semi」。
     */
    @Test
    fun `self-contradicting weight suffix folds into the base family`() {
        val folded = FontconfigFonts.fold(
            listOf(
                weightFace(80, "Regular", "Noto Sans Gunjala Gondi"),
                // 名字说 Semibold（180），自己说 400（80）——名字是打包残留。
                weightFace(80, "Regular", "Noto Sans Gunjala Gondi Semibold"),
                weightFace(80, "Regular", "Noto Sans Sora Sompeng"),
                weightFace(200, "Bold", "Noto Sans Sora Sompeng"),
                // 名字被截成「Semi」，自己说 Bold（200）——同样是残的。
                weightFace(200, "Bold", "Noto Sans Sora Sompeng Semi"),
            ),
        )
        assertEquals(
            listOf("Noto Sans Gunjala Gondi", "Noto Sans Sora Sompeng"),
            folded.families,
        )
        assertEquals(
            "折回后必须改用名字里的字重名，否则与真 Regular 撞成一档",
            listOf("Regular", "SemiBold"),
            folded.faces.getValue("Noto Sans Gunjala Gondi").map { it.subfamily },
        )
        assertEquals(
            listOf("Regular", "Bold", "SemiBold"),
            folded.faces.getValue("Noto Sans Sora Sompeng").map { it.subfamily },
        )
        assertEquals(
            "折叠后各面的族名必须是基族名",
            listOf("Noto Sans Gunjala Gondi", "Noto Sans Gunjala Gondi"),
            folded.faces.getValue("Noto Sans Gunjala Gondi").map { it.family },
        )
    }

    /** 守卫：族名带字重词、**自己就是那个字重**的正经独立族不许被吞（Arial Black 是 900）。 */
    @Test
    fun `self-consistent weight suffixed families stay independent`() {
        val folded = FontconfigFonts.fold(
            listOf(
                weightFace(80, "Regular", "Arial"),
                weightFace(200, "Bold", "Arial"),
                weightFace(210, "Regular", "Arial Black"),
                // 宽度词是族名的一部分（Condensed 是独立族），绝不剥。
                weightFace(80, "Regular", "Noto Sans Georgian"),
                weightFace(80, "Condensed", "Noto Sans Georgian Condensed"),
                // 宽度词 + 字重词：剥掉的只能是 Black，剩余基名仍是带 Condensed 的那一族。
                weightFace(210, "Condensed Black", "Noto Sans Georgian Condensed", "Noto Sans Georgian Condensed Black"),
            ),
        )
        assertEquals(
            listOf("Arial", "Arial Black", "Noto Sans Georgian", "Noto Sans Georgian Condensed"),
            folded.families,
        )
        assertEquals(listOf("Regular"), folded.faces.getValue("Arial Black").map { it.subfamily })
        assertEquals(
            "Condensed Black 必须落在带 Condensed 的那一族上，且族名已说明宽度、字重名里那个词是冗余的",
            listOf("Regular", "Black"),
            folded.faces.getValue("Noto Sans Georgian Condensed").map { it.subfamily },
        )
        assertEquals(
            "不得因带 Condensed 而被并进未压缩的族",
            listOf("Regular"),
            folded.faces.getValue("Noto Sans Georgian").map { it.subfamily },
        )
    }

    /** 基族名不在本次枚举里 / 字重读不到 ⇒ 不敢猜，原样保留。 */
    @Test
    fun `weight suffix is kept when the base family or own weight is unknown`() {
        val folded = FontconfigFonts.fold(
            listOf(
                // 基族不存在（枚举不完整）：不凭空造族。
                weightFace(80, "Regular", "Mystery Sans Semibold"),
                // 读不到字重（平台不给）：无从判断名字是否自相矛盾。
                FontconfigFonts.Face(listOf("Another Sans Light"), "Regular"),
            ),
        )
        assertEquals(listOf("Another Sans Light", "Mystery Sans Semibold"), folded.families)
    }

    /**
     * 宽度半拉子的上游 bug（Noto Sans Sinhala Condensed 现场）：同一套窄体的 9 个面里，
     * 3 个（Regular/Bold/Black）的 nameID16 还写着基族名、fontconfig 于是把它们报成「基族 + 别名」，
     * 按众数会漏进基族，结果同一套 Condensed 被劈成两处。
     *
     * 注意这里刻意用宽度刻度 63（按 OS/2 规范是 ExtraCondensed）——真实文件就是这样写的，
     * 而名字都叫 Condensed。若要按刻度拼族名，会拼出 `… ExtraCondensed` 把同一套字体再劈一次。
     */
    @Test
    fun `semi-condensed faces join the width family that exists`() {
        val folded = FontconfigFonts.fold(
            listOf(
                sizedFace(100, 80, "Regular", "Noto Sans Sinhala"),
                sizedFace(100, 700, "Bold", "Noto Sans Sinhala"),
                // 这三个的族名候选里混着基族（nameID16 = 基族名），按众数原本会漏进基族。
                sizedFace(63, 80, "Condensed", "Noto Sans Sinhala", "Noto Sans Sinhala Condensed"),
                sizedFace(63, 210, "Black Condensed", "Noto Sans Sinhala", "Noto Sans Sinhala Black Condensed"),
                sizedFace(63, 100, "Thin Condensed", "Noto Sans Sinhala", "Noto Sans Sinhala Thin Condensed"),
                // 其余 6 个只报窄体族名。
                sizedFace(63, 200, "Bold", "Noto Sans Sinhala Condensed"),
            ),
        )
        assertEquals(
            "宽度词只认名字：不得按刻度造出 ExtraCondensed 一族",
            listOf("Noto Sans Sinhala", "Noto Sans Sinhala Condensed"),
            folded.families,
        )
        assertEquals(
            "正常宽度那一族只剩两个字重，窄体不许再漏进来",
            listOf("Regular", "Bold"),
            folded.faces.getValue("Noto Sans Sinhala").map { it.subfamily },
        )
        assertEquals(
            "窄体一族必须齐档（族名已说明宽度，字重名里的宽度词要被剥掉，只剩 9 档）",
            listOf("Regular", "Black", "Thin", "Bold"),
            folded.faces.getValue("Noto Sans Sinhala Condensed").map { it.subfamily },
        )
    }

    /** 守卫：宽度刻度或许不可信，但它说「正常宽度」时绝不把面往宽度族里搬。 */
    @Test
    fun `normal width face is never moved into a width family`() {
        val folded = FontconfigFonts.fold(
            listOf(
                sizedFace(100, 80, "Regular", "Demo Sans"),
                sizedFace(100, 700, "Bold", "Demo Sans"),
                // style 里带 Condensed，自己却声明正常宽度 ⇒ 名字与字形对不上，保持原样。
                sizedFace(100, 400, "Condensed", "Demo Sans", "Demo Sans Condensed"),
            ),
        )
        assertEquals(listOf("Demo Sans"), folded.families)
        assertEquals(
            listOf("Regular", "Bold", "Condensed"),
            folded.faces.getValue("Demo Sans").map { it.subfamily },
        )
    }

    /**
     * 拼出来的族名不存在时**绝不**归族：`Noto Sans Arabic Condensed` 在 fontconfig 里没有
     * （上游只报缩写别名 `… Cond`），而名字不存在的族会被静默匹配成别的字体。
     */
    @Test
    fun `width family is never invented when fontconfig does not report it`() {
        val folded = FontconfigFonts.fold(
            listOf(
                sizedFace(100, 80, "Regular", "Noto Sans Arabic"),
                sizedFace(75, 200, "Condensed Bold", "Noto Sans Arabic", "Noto Sans Arabic Cond"),
            ),
        )
        assertEquals(listOf("Noto Sans Arabic"), folded.families)
        assertEquals(
            listOf("Regular", "Condensed Bold"),
            folded.faces.getValue("Noto Sans Arabic").map { it.subfamily },
        )
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
            "regularitalic", "bolditalic", "semi",
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
            // 上限对着「每族挂全库两百多个假字重」的老 bug，留出真·大家庭的余量：fontconfig 把
            // Condensed/ExtraCondensed/SemiCondensed 与正斜体都算同一族，Noto Serif / Noto Sans 各 72 档
            // （9 字重 × 4 宽度 × 2 斜体）是正常的。
            folded.faces.values.all { it.size <= 128 },
        )
    }
}
