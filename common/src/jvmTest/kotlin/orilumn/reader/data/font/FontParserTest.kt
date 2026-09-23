package orilumn.reader.data.font

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FontParser] parsing and [FontClassifier] classification.
 *
 * Verifies the parse logic with programmatically constructed minimal sfnt bytes (only the cmap + name
 * tables), avoiding reliance on real font files in the environment (device fonts are left to on-device testing).
 */
class FontParserTest {

    private val parser = FontParser()

    @Test
    fun `format4 classified as generic when both cjk and latin covered`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0020, 0x007A), intArrayOf(0x4E00, 0x9FFF))), 3, 1),
            nameTable("Source Han Serif"),
        )
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals("Source Han Serif", res.familyName)
        assertEquals(20992, res.cjkCoverage) // U+4E00..U+9FFF
        assertEquals(91, res.latinCoverage)   // U+0020..U+007A within U+0000-02FF
        assertEquals(FontClassifier.LANG_GENERIC, FontClassifier.classify(res))
    }

    @Test
    fun `cjk only classified as cjk`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable("ChineseSong"),
        )
        val res = parser.parse(font)
        assertEquals(FontClassifier.LANG_CJK, FontClassifier.classify(res))
    }

    @Test
    fun `latin only classified as latin`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0041, 0x005A))), 3, 1),
            nameTable("LatinOnly"),
        )
        val res = parser.parse(font)
        assertEquals(26, res.latinCoverage)
        assertEquals(FontClassifier.LANG_LATIN, FontClassifier.classify(res))
    }

    @Test
    fun `tiny coverage not usable, symbol or below threshold`() {
        // Covers only a few characters, below the body-text threshold
        val font = sfntFont(cmapTable(fmt4(arrayOf(intArrayOf(0x2000, 0x2003))), 3, 1), nameTable("Symbols"))
        val res = parser.parse(font)
        assertFalse(FontClassifier.isUsable(FontClassifier.classify(res)))
    }

    @Test
    fun `format12 unicode range parsed`() {
        val fmt12 = fmt12(
            listOf(
                Triple(0x4E00, 0x4E10, 0),   // CJK part
                Triple(0x0020, 0x007E, 0),   // Latin part
            ),
        )
        val font = sfntFont(cmapTable(fmt12, 3, 10), nameTable("UnicodeFont"))
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals(17, res.cjkCoverage) // 0x4E00..0x4E10
        assertEquals(95, res.latinCoverage) // 0x0020..0x007E
        assertTrue(res.familyName == "UnicodeFont")
    }

    @Test
    fun `garbage is invalid`() {
        val res = parser.parse(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFalse(res.valid)
        assertEquals(FontClassifier.LANG_INVALID, FontClassifier.classify(res))
    }

    @Test
    fun `mixed latin+CJK windows-unicode name keeps byte order`() {
        // 真机回归：TypeLand 康熙字典體（platform 3 / encoding 1 = UTF-16BE）。
        // 旧启发式里 ASCII 占比 0.69 差一格过 0.7 线，误判成 LE 乱码。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0020, 0x007A), intArrayOf(0x4E00, 0x9FFF))), 3, 1),
            nameTable("TypeLand 康熙字典體"),
        )
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals("TypeLand 康熙字典體", res.familyName)
    }

    @Test
    fun `ambiguous encoding still adapts by scoring`() {
        // 规范未定的记录走兜底打分：LE 序英文仍能认回（ASCII+CJK 联合分，持平归 BE）。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0041, 0x005A))), 3, 1),
            nameTable("TestLE".toByteArray(Charsets.UTF_16LE), platform = 2, encoding = 1),
        )
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals("TestLE", res.familyName)
    }

    // ── 方案B：familyNamesOf 的拉丁/中文对 ──

    @Test
    fun `familyNamesOf separates latin and zh-cn records`() {
        // 同 nameID 多语言记录：latin 取无 CJK 记录，chinese 取 zh-CN(0x0804) 记录。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0409, text = "Songti SC"),
                NameRec(lang = 0x0804, text = "宋体-简"),
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("Songti SC", names?.latin)
        assertEquals("宋体-简", names?.chinese)
    }

    @Test
    fun `familyNamesOf falls back to any cjk record when no zh-cn record`() {
        // 无 0x0804 时回退任意含 CJK 记录——不筛简繁（zh-TW 繁体也算中文名）。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0409, text = "Songti SC"),
                NameRec(lang = 0x0404, text = "宋體-簡"), // zh-TW
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("Songti SC", names?.latin)
        assertEquals("宋體-簡", names?.chinese)
    }

    @Test
    fun `familyNamesOf latin null for cjk-only name`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(NameRec(lang = 0x0804, text = "屏阅初夏明朝体")),
        )
        val names = parser.familyNamesOf(font)
        assertNull(names?.latin)
        assertEquals("屏阅初夏明朝体", names?.chinese)
    }

    @Test
    fun `familyNamesOf prefers typographic family name id 16`() {
        // zh-CN 记录在 nameID16 时优先（对齐族名键）；latin 无 CJK 记录才顺延到 nameID1。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0804, nameId = 16, text = "霞鹜文楷"),
                NameRec(lang = 0x0409, nameId = 16, text = "LXGW WenKai"),
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("LXGW WenKai", names?.latin)
        assertEquals("霞鹜文楷", names?.chinese)
    }

    @Test
    fun `familyNamesOf latin picks most ascii-readable non-cjk record`() {
        // 回归：STHeiti 类 TTC 的 name 表 lantin 候选含韩文记录（Heiti-번체）时，必须选
        // ASCII 可读性最高的「Heiti TC」，而不是最低（旧实现 -asciiRatio 取 max 反了）。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0409, text = "Heiti TC"),
                NameRec(lang = 0x0412, text = "Heiti-번체"), // ko-KR，cjkCount=0 混入 latin 候选
                NameRec(lang = 0x0804, text = "黑体-繁"),
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("Heiti TC", names?.latin)
        assertEquals("黑体-繁", names?.chinese)
    }

    @Test
    fun `familyNamesOf tc prefer zh-tw over zh-cn`() {
        // 回归（TC 族简体名问题）：Kaiti TC 的 name 表 zh-CN 记录是「楷体-繁」（简体字面），
        // zh-TW 记录是「楷體-繁」（繁体字面）。变体感知后 latin 尾缀 TC → 优先 zh-TW，
        // 中文名必须出繁体「楷體-繁」，而不是被 zh-CN 抢成「楷体-繁」。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0409, text = "Kaiti TC"),
                NameRec(lang = 0x0804, text = "楷体-繁"), // zh-CN
                NameRec(lang = 0x0404, text = "楷體-繁"), // zh-TW
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("Kaiti TC", names?.latin)
        assertEquals("楷體-繁", names?.chinese)
    }

    @Test
    fun `familyNamesOf sc still prefer zh-cn`() {
        // 简体族保持 zh-CN 优先：Kaiti SC 的 zh-CN「楷体-简」优先于 zh-TW「楷體-簡」。
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable(
                NameRec(lang = 0x0409, text = "Kaiti SC"),
                NameRec(lang = 0x0404, text = "楷體-簡"), // zh-TW，不应命中
                NameRec(lang = 0x0804, text = "楷体-简"), // zh-CN
            ),
        )
        val names = parser.familyNamesOf(font)
        assertEquals("Kaiti SC", names?.latin)
        assertEquals("楷体-简", names?.chinese)
    }

    @Test
    fun `familyNamesOf null for garbage`() {
        assertNull(parser.familyNamesOf(byteArrayOf(1, 2, 3)))
    }

    // ── Synthetic font construction ------------------------------------------------------------------

    /** sfnt header + table directory: holds one cmap and one name table. */
    private fun sfntFont(cmap: ByteArray, name: ByteArray): ByteArray {
        val numTables = 2
        val dataStart = (12 + numTables * 16 + 3) and -4
        val cmapOff = dataStart
        val nameOff = cmapOff + cmap.size
        val total = nameOff + name.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000.toInt()) // sfnt version
        buf.putShort(numTables.toShort())
        buf.putShort(0) // searchRange
        buf.putShort(0) // entrySelector
        buf.putShort(0) // rangeShift
        putRec(buf, 12, "cmap", cmapOff, cmap.size)
        putRec(buf, 28, "name", nameOff, name.size)
        // putRec writes the directory at absolute indices while the cursor is still at 12; the cursor must be
        // positioned at the corresponding offset before the table content is written, otherwise the relative
        // put(cmap)/put(name) overwrites the directory records.
        buf.position(cmapOff)
        buf.put(cmap)
        buf.position(nameOff)
        buf.put(name)
        return buf.array()
    }

    private fun putRec(buf: ByteBuffer, at: Int, tag: String, off: Int, len: Int) {
        val intTag = byteArrayOf(tag[0].code.toByte(), tag[1].code.toByte(), tag[2].code.toByte(), tag[3].code.toByte())
            .let { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).int }
        buf.putInt(at, intTag)
        buf.putInt(at + 4, 0) // checksum (the resolver does not verify it)
        buf.putInt(at + 8, off)
        buf.putInt(at + 12, len)
    }

    /** cmap table: a single encoding sub-table. */
    private fun cmapTable(subtable: ByteArray, platform: Int, encoding: Int): ByteArray {
        val out = ByteBuffer.allocate(12 + subtable.size).order(ByteOrder.BIG_ENDIAN)
        out.putShort(0) // version
        out.putShort(1) // numTables
        out.putShort(platform.toShort())
        out.putShort(encoding.toShort())
        out.putInt(12) // offset of the sub-table relative to cmap
        out.put(subtable)
        return out.array()
    }

    private fun fmt4(segs: Array<IntArray>): ByteArray {
        val sc = segs.size
        val len = 14 + 2 * sc + 2 + (2 * sc) * 3
        val b = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN)
        b.putShort(4)
        b.putShort(len.toShort())
        b.putShort(0) // language
        b.putShort((2 * sc).toShort()) // segCountX2
        b.putShort(0); b.putShort(0); b.putShort(0)
        segs.forEach { b.putShort(it[1].toShort()) } // endCode
        b.putShort(0) // reservedPad
        segs.forEach { b.putShort(it[0].toShort()) } // startCode
        segs.forEach { b.putShort(0) } // idDelta
        segs.forEach { b.putShort(0) } // idRangeOffset
        return b.array()
    }

    private fun fmt12(groups: List<Triple<Int, Int, Int>>): ByteArray {
        val len = 16 + groups.size * 12
        val b = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN)
        b.putShort(12)
        b.putShort(0) // reserved
        b.putInt(len)
        b.putInt(0) // language
        b.putInt(groups.size)
        groups.forEach { (s, e, _) ->
            b.putInt(s); b.putInt(e); b.putInt(0) // start/end/startGlyph
        }
        return b.array()
    }

    @Suppress("unused")
    private fun nameTable(name: String): ByteArray =
        nameTable(name.toByteArray(Charsets.UTF_16BE))

    /** name 记录的 platform/encoding 可配（兜底打分路径用非 Unicode 编码触发）。 */
    private fun nameTable(str: ByteArray, platform: Int = 3, encoding: Int = 1): ByteArray {
        val stringOffset = 6 + 12
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0); putShort(1); putShort(stringOffset.toShort())
        }.array())
        out.write(ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(platform.toShort()); putShort(encoding.toShort()); putShort(0x0409); putShort(1) // nameID=1
            putShort(str.size.toShort()); putShort(0.toShort())
        }.array())
        out.write(str)
        return out.toByteArray()
    }

    /** 方案B：多记录 name 表（同 nameID 多语言记录，验证 latin/中文分离与 zh-CN 优先）。 */
    private fun nameTable(vararg recs: NameRec): ByteArray {
        val strings = recs.map { it.bytes }
        val stringOffset = 6 + recs.size * 12
        var strPos = 0
        val offsets = strings.map { bytes -> strPos.also { strPos += bytes.size } }
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0); putShort(recs.size.toShort()); putShort(stringOffset.toShort())
        }.array())
        recs.forEachIndexed { i, r ->
            out.write(ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(r.platform.toShort()); putShort(r.encoding.toShort())
                putShort(r.lang.toShort()); putShort(r.nameId.toShort())
                putShort(strings[i].size.toShort()); putShort(offsets[i].toShort())
            }.array())
        }
        strings.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** 单条 name 记录：默认 pf=3(Windows)/enc=1(Unicode)/lang en-US/nameID=1。 */
    private data class NameRec(
        val platform: Int = 3,
        val encoding: Int = 1,
        val lang: Int = 0x0409,
        val nameId: Int = 1,
        val text: String,
    ) {
        val bytes: ByteArray get() = text.toByteArray(Charsets.UTF_16BE)
    }
}