package orilumn.reader.data.font

/**
 * Font binary parsing (pure Kotlin, no third-party dependency, unit-testable) — S21 moved this
 * into commonMain: `java.nio.ByteBuffer` reads were replaced with pure big-endian byte-array
 * helpers (same OOB→INVALID behavior via the [parse] wrapper), and the JVM-only `File`/`InputStream`
 * convenience overloads moved to `jvmMain` as extension functions.
 *
 * Parses the sfnt table directory of TTF / OTF / TTC, extracting:
 *  - **family name** ('name' table nameID=1), for WebView CSS `local()` referencing system fonts;
 *  - **cmap coverage weights**: counts code points mapped in the CJK (U+4E00–9FFF) and Latin
 *    (U+0000–02FF) blocks, feeding [FontClassifier] to judge Chinese/English/generic/symbol/invalid.
 *
 * Only reads the read-only header of the font bytes (table directory + cmap + name table), no full
 * glyph parsing, hence lightweight and reliable. Coverage is counted by interval overlap across cmap
 * segments (format 4) or groups (format 12), not exact glyph counts, but sufficient for deciding
 * whether a font has enough Chinese/English glyphs.
 */
class FontParser {

    /** Parsing result; any failing step returns with [valid]==false without affecting the caller. */
    data class Result(
        /** Family name (nameID=16 Typographic Family preferred, else nameID=1) — consistent across files of the same family, used as the grouping key. */
        val familyName: String?,
        /** Weight/style name (nameID=2 Subfamily, e.g. Regular/Bold/Italic), for distinguishing within a family. May be null. */
        val subfamily: String?,
        /** Number of code points in the CJK Unified Ideographs block (U+4E00–9FFF) mapped by cmap. */
        val cjkCoverage: Int,
        /** Number of code points in the Latin / basic-Latin block (U+0000–02FF) mapped by cmap. */
        val latinCoverage: Int,
        val valid: Boolean = true,
    ) {
        companion object {
            val INVALID = Result(familyName = null, subfamily = null, cjkCoverage = 0, latinCoverage = 0, valid = false)
        }
    }

    // The two blocks used for body classification (correspond to CSS unicode-range / semantics)
    private val cjkLo = 0x4E00
    private val cjkHi = 0x9FFF
    private val latinLo = 0x0000
    private val latinHi = 0x02FF

    /**
     * 方案B：读 name 表的「拉丁族名 + 中文族名」对（纯字节解析，不受系统语言/CoreText 影响）。
     *
     * 拉丁族名 = name 表里不含 CJK 的记录（如 "Songti SC"、"LXGW WenKai"），用于与平台
     * 枚举族名（skiko/CoreText 逻辑键）对齐；中文族名 = 按拉丁族名尾缀匹配变体语言——TC→zh-TW、
     * HK→zh-HK、MO→zh-MO 优先，其余（含 SC/无尾缀）→zh-CN 优先，回退任意含 CJK 的记录，
     * **不筛简繁**——name 表写什么就显示什么
     * （如「宋体-简」「黑體-繁」「霞鹜文楷」原样返回）。
     *
     * 平台无关（commonMain 纯字节），替代方案A（CoreText `CTFontCopyDisplayName`）
     * 的中文名链最前段：解析失败返回 null，调用方回落既有链路。
     */
    fun familyNamesOf(bytes: ByteArray): FamilyNames? = runCatching {
        require(bytes.size >= 4)
        val base = fontBase(bytes)
        val numTables = u16(bytes, base + 4)
        val nameRec = findTable(bytes, base, numTables, "name") ?: return@runCatching null
        val latin = readLatinName(bytes, nameRec)
        FamilyNames(
            latin = latin,
            chinese = readChinesePreferredName(bytes, nameRec, latin),
        )
    }.getOrNull()

    /** [familyNamesOf] 的解码结果：无对应记录的一侧为 null（多数西文族 chinese 为 null）。 */
    data class FamilyNames(
        /** 不含 CJK 的记录（nameID16 优先、1 回退，取 ASCII 可读性最高者）；无则 null。 */
        val latin: String?,
        /** 中文记录：按拉丁族名尾缀匹配变体语言（TC→zh-TW、HK→zh-HK、MO→zh-MO、其余→zh-CN），
         *  均无回退任意含 CJK 的记录（不筛语言）；无则 null。 */
        val chinese: String?,
    )

    /** Parses raw font bytes; any failure returns [Result.INVALID]. */
    fun parse(bytes: ByteArray): Result = runCatching { parseUnsafe(bytes) }
        .getOrElse { Result.INVALID }

    private fun parseUnsafe(bytes: ByteArray): Result {
        require(bytes.size >= 4)
        val base = fontBase(bytes)
        // Read the table directory; locate cmap and name
        val numTables = u16(bytes, base + 4)
        val cmapRec = findTable(bytes, base, numTables, "cmap")
        val nameRec = findTable(bytes, base, numTables, "name")
        if (cmapRec == null) return Result.INVALID // without cmap, even glyph coverage cannot be decided

        val familyName = nameRec?.let { readName(bytes, it, ID_FAMILY) }
        val subfamily = nameRec?.let { readName(bytes, it, ID_SUBFAMILY) }
        val cjk = CjkCollector("cjk", cjkLo, cjkHi)
        val latin = CjkCollector("latin", latinLo, latinHi)
        var anySubtable = false
        // Iterate cmap subtables, accumulate parsable format4/12 coverage (duplicate code points not deduped; interval addition is an approximation)
        val cmapNumTables = u16(bytes, cmapRec.offset + 2)
        for (i in 0 until cmapNumTables) {
            val recOff = cmapRec.offset + 4 + i * 8
            val subOff = i32(bytes, recOff + 4)
            if (subOff < 0) continue
            val abs = cmapRec.offset + subOff
            if (abs + 2 > bytes.size) continue
            val format = u16(bytes, abs)
            when (format) {
                4 -> { countFormat4(bytes, abs, cjk, latin); anySubtable = true }
                12 -> { countFormat12(bytes, abs, cjk, latin); anySubtable = true }
                else -> Unit // other formats do not affect classification
            }
        }
        if (!anySubtable) return Result.INVALID
        return Result(
            familyName = familyName,
            subfamily = subfamily,
            cjkCoverage = cjk.total,
            latinCoverage = latin.total,
            valid = true,
        )
    }

    private fun countFormat4(bytes: ByteArray, off: Int, cjk: CjkCollector, latin: CjkCollector) {
        val segCount = u16(bytes, off + 6) / 2
        if (segCount <= 0) return
        val endBase = off + 14
        val startBase = endBase + segCount * 2 + 2
        for (seg in 0 until segCount) {
            val start = u16(bytes, startBase + seg * 2)
            val end = u16(bytes, endBase + seg * 2)
            if (start > end) continue
            cjk.addIntersect(start, end)
            latin.addIntersect(start, end)
        }
    }

    private fun countFormat12(bytes: ByteArray, off: Int, cjk: CjkCollector, latin: CjkCollector) {
        val nGroups = i32(bytes, off + 12).toLong()
        if (nGroups < 0 || nGroups > 0x40000) return
        val groupsBase = off + 16
        for (g in 0 until nGroups.toInt()) {
            val recBase = groupsBase + g * 12
            val start = i32(bytes, recBase).toLong() and 0xffffffffL
            val end = i32(bytes, recBase + 4).toLong() and 0xffffffffL
            if (end < start) continue
            addCoverage(start, end, latinLo.toLong(), latinHi.toLong(), latin)
            addCoverage(start, end, cjkLo.toLong(), cjkHi.toLong(), cjk)
        }
    }

    private inline fun addCoverage(start: Long, end: Long, lo: Long, hi: Long, c: CjkCollector) {
        if (end < lo || start > hi) return
        val s = maxOf(start, lo)
        val e = minOf(end, hi)
        c.addRaw((e - s + 1).toInt())
    }

    companion object {
        // OpenType 'name' table standard nameIDs
        private const val ID_FAMILY = 1
        private const val ID_SUBFAMILY = 2
        private const val ID_TYPOGRAPHIC_FAMILY = 16
        private const val ID_TYPOGRAPHIC_SUBFAMILY = 17

        /** Windows platform languageID for Simplified Chinese (zh-CN)。 */
        private const val LANG_ZH_CN = 0x0804

        /** Windows platform languageID for Traditional Chinese (zh-TW)。 */
        private const val LANG_ZH_TW = 0x0404

        /** Windows platform languageID for Chinese HK (zh-HK)。 */
        private const val LANG_ZH_HK = 0x0C04

        /** Windows platform languageID for Chinese MO (zh-MO)。 */
        private const val LANG_ZH_MO = 0x1404

        private class CjkCollector(private val name: String, private val lo: Int, private val hi: Int) {
            var total = 0
            fun addIntersect(s: Int, e: Int) {
                if (e < lo || s > hi) return
                addRaw(minOf(e, hi) - maxOf(s, lo) + 1)
            }
            fun addRaw(n: Int) { total += n }
        }

        private fun isTag(bytes: ByteArray, off: Int, tag: String): Boolean {
            if (off + 4 > bytes.size) return false
            for (i in 0 until 4) if (bytes[off + i] != tag[i].code.toByte()) return false
            return true
        }

        /** sfnt 表目录基址：TTC 集合取第一个 font 的 offset table（既有 parse 只解析首面），否则 0。 */
        private fun fontBase(bytes: ByteArray): Int {
            if (isTag(bytes, 0, "ttcf")) {
                val numFonts = i32(bytes, 8) and 0xffff
                require(numFonts >= 1) { "TTC 无字体" }
                return i32(bytes, 12)
            }
            return 0
        }

        // ---- pure big-endian readers (replacing java.nio.ByteBuffer) ----
        private fun u16(bytes: ByteArray, off: Int): Int =
            ((bytes[off].toInt() and 0xff) shl 8) or (bytes[off + 1].toInt() and 0xff)

        private fun i16(bytes: ByteArray, off: Int): Int = u16(bytes, off).toShort().toInt()

        private fun i32(bytes: ByteArray, off: Int): Int =
            (u16(bytes, off) shl 16) or u16(bytes, off + 2)

        /** Find the record of a given tag in the sfnt table directory; returns the offset/length within the file header. */
        private fun findTable(bytes: ByteArray, base: Int, numTables: Int, tag: String): TableInfo? {
            for (i in 0 until numTables) {
                val rec = base + 12 + i * 16
                if (rec + 16 > bytes.size) return null
                if (isTag(bytes, rec, tag)) {
                    val offset = i32(bytes, rec + 8)
                    val length = i32(bytes, rec + 12)
                    if (offset < 0 || length < 0) return null
                    return TableInfo(offset, length)
                }
            }
            return null
        }

        /**
         * Read the name of a given nameID from the 'name' table. family prefers nameID=16 (Typographic,
         * no weight, consistent within a family), falling back to nameID=1 when missing; subfamily prefers
         * nameID=17 (Typographic Subfamily, holds the real weight), falling back to nameID=2 when missing —
         * because CJK fonts like Source Han often degrade to "Regular" in nameID=2 outside Regular/Bold,
         * while 17 stores the real weights like Light/Heavy. Among all candidate records **prefer the name
         * containing Chinese glyphs**, otherwise fall back to a readable Latin name — solving the issue of
         * erroneously picking English when a font has both Chinese and English names side by side
         * (e.g. 「霞鹜文楷 / LXGW WenKai」).
         */
        private fun readName(bytes: ByteArray, info: TableInfo, nameId: Int): String? {
            val preferred = when (nameId) {
                ID_FAMILY -> listOf(ID_TYPOGRAPHIC_FAMILY, ID_FAMILY)
                ID_SUBFAMILY -> listOf(ID_TYPOGRAPHIC_SUBFAMILY, ID_SUBFAMILY)
                else -> listOf(nameId)
            }
            for (pref in preferred) {
                collectNames(bytes, info, pref).let { c ->
                    if (c.isNotEmpty()) {
                        return c.maxWithOrNull(compareBy({ it.second }, { -asciiRatio(it.first) }))?.first
                    }
                }
            }
            return null
        }

        /**
         * 中文族名（方案B，变体感知）：按拉丁族名尾缀匹配变体语言——TC→zh-TW(0x0404)、
         * HK→zh-HK(0x0C04)、MO→zh-MO(0x1404) 优先，其余（含 SC、无尾缀）→zh-CN(0x0804) 优先。
         *
         * 与 [readName] 的关系：readName 对所有含 CJK 记录「取 CJK 字最多者」（不筛语言，可能命中
         * zh-TW/任意繁体记录）；此函数按族名尾缀**优先对应变体的语言**：繁体族（Kaiti TC 等）取
         * zh-TW「楷體-繁」而非 zh-CN「楷体-繁」，简体族（Kaiti SC 等）取 zh-CN「楷体-简」——
         * 简体/繁体按族名自报口径（尾缀即其变体），不硬掰成 zh-CN。目标语言查不到才回落 readName
         * （任意含 CJK 记录，日文假名照 name 表显示）；仍查不到返回 null。
         */
        private fun readChinesePreferredName(bytes: ByteArray, info: TableInfo, latin: String?): String? {
            for (lang in preferredChineseLangs(latin)) {
                for (pref in listOf(ID_TYPOGRAPHIC_FAMILY, ID_FAMILY)) {
                    collectNames(bytes, info, pref, lang).let { c ->
                        if (c.isNotEmpty()) {
                            return c.maxWithOrNull(compareBy({ it.second }, { -asciiRatio(it.first) }))?.first
                        }
                    }
                }
            }
            return readName(bytes, info, ID_FAMILY)
        }

        /** 变体 → 优先语言顺序：TC→zh-TW、HK→zh-HK、MO→zh-MO、其余→zh-CN。 */
        private fun preferredChineseLangs(latin: String?): List<Int> {
            val key = latin?.trim().orEmpty()
            return when {
                key.endsWith("TC") -> listOf(LANG_ZH_TW, LANG_ZH_CN)
                key.endsWith("HK") -> listOf(LANG_ZH_HK, LANG_ZH_TW, LANG_ZH_CN)
                key.endsWith("MO") -> listOf(LANG_ZH_MO, LANG_ZH_TW, LANG_ZH_CN)
                else -> listOf(LANG_ZH_CN, LANG_ZH_TW)
            }
        }

        /** 拉丁族名（方案B对齐键）：nameID16 优先、1 回退，取「无 CJK 且 ASCII 可读性最高」的记录；无则 null。 */
        private fun readLatinName(bytes: ByteArray, info: TableInfo): String? {
            for (pref in listOf(ID_TYPOGRAPHIC_FAMILY, ID_FAMILY)) {
                collectNames(bytes, info, pref).let { c ->
                    if (c.isEmpty()) continue
                    val latin = c.filter { it.second == 0 }
                        .maxWithOrNull(compareBy({ asciiRatio(it.first) }))
                    if (latin != null) return latin.first
                }
            }
            return null
        }
        private fun collectNames(bytes: ByteArray, info: TableInfo, nameId: Int, winLang: Int? = null): List<Pair<String, Int>> {
            val off = info.offset
            val count = u16(bytes, off + 2)
            val stringOff = u16(bytes, off + 4)
            if (count == 0 || count > 200) return emptyList()
            val out = mutableListOf<Pair<String, Int>>()
            for (i in 0 until count) {
                val rec = off + 6 + i * 12
                if (rec + 12 > bytes.size) continue
                val pf = u16(bytes, rec)
                val enc = u16(bytes, rec + 2)
                val lang = u16(bytes, rec + 4)
                val id = u16(bytes, rec + 6)
                val len = u16(bytes, rec + 8)
                val strOff = u16(bytes, rec + 10)
                if (id != nameId || len == 0) continue
                if (pf == 3 && enc != 1 && enc != 10) continue   // Windows: Unicode only
                if (winLang != null && !(pf == 3 && lang == winLang)) continue
                val start = off + stringOff + strOff
                val end = start + len
                if (start < 0 || end > bytes.size) continue
                // Decode by platform byte order (OpenType spec), guessing only when the spec is silent:
                // pf=1 MacRoman is single-byte; pf=3/enc=1 Windows Unicode and pf=0 Unicode are
                // ALWAYS UTF-16BE — decoding them by heuristic misreads mixed Latin+CJK names
                // ("TypeLand 康熙字典體" just under the ASCII threshold flips to LE garbage).
                // Residual encodings (old MBCS etc.) keep the adaptive fallback below.
                val name = when {
                    pf == 1 -> decodeMacRoman(bytes, start, end)
                    (pf == 3 && enc == 1) || pf == 0 -> decodeUtf16Be(bytes, start, end)
                    else -> decodeUtf16PreferCjk(bytes, start, end)
                }
                if (name != null && name.isNotBlank()) {
                    out.add(name to cjkCount(name))
                }
            }
            return out
        }

        /**
         * UTF-16 family-name decoding, byte-order adaptive, **preferring the Chinese record** (not dropped for lacking ASCII).
         *
         * Only reached for encodings the spec leaves ambiguous (see call site); Windows/Unicode
         * records take the spec byte order directly. Scoring is ASCII-readability PLUS CJK ratio
         * on each side — the old two-stage rule (ASCII ≥ 0.7, else CJK compare) flipped mixed
         * Latin+CJK names whose ASCII share just misses the threshold while the misdecoded side
         * accidentally lands mostly in CJK (ASCII 0x41–0x5A misread as LE fall in U+4100–5A00,
         * half of which is CJK). Ties go to big-endian (the spec default for Unicode records).
         */
        private fun decodeUtf16PreferCjk(bytes: ByteArray, start: Int, end: Int): String? {
            val len = end - start
            if (len < 2) return null
            val bs = bytes.copyOfRange(start, end)
            val be = decodeUtf16(bs, littleEndian = false)
            val le = decodeUtf16(bs, littleEndian = true)
            if (be.isBlank() && le.isBlank()) return null
            val scoreBe = asciiRatio(be) + cjkRatio(be)
            val scoreLe = asciiRatio(le) + cjkRatio(le)
            return if (scoreLe > scoreBe) le else be
        }

        /** Spec-order UTF-16BE decode (Windows Unicode / Unicode platform); null when blank. */
        private fun decodeUtf16Be(bytes: ByteArray, start: Int, end: Int): String? {
            if (end - start < 2) return null
            return decodeUtf16(bytes.copyOfRange(start, end), littleEndian = false)
                .takeIf { it.isNotBlank() }
        }

        /**
         * Pure UTF-16 decode (no java.nio.charset): big-endian (littleEndian=false) or little-endian
         * units are assembled from byte pairs into `Char`s; a dangling trailing byte decodes to U+FFFD
         * (mirroring Java's REPLACE-mode charset decoder), and NUL units are stripped like before.
         */
        private fun decodeUtf16(bs: ByteArray, littleEndian: Boolean): String {
            val sb = StringBuilder(bs.size / 2 + 1)
            var i = 0
            while (i + 1 < bs.size) {
                val hiByte = bs[i].toInt() and 0xff
                val loByte = bs[i + 1].toInt() and 0xff
                val unit = if (littleEndian) (loByte shl 8) or hiByte else (hiByte shl 8) or loByte
                sb.append(unit.toChar())
                i += 2
            }
            if (i < bs.size) sb.append('\uFFFD')
            return sb.toString().replace("\u0000", "")
        }

        /** Count of CJK Unified Ideographs (U+4E00–9FFF) in a string; used to judge whether it is a Chinese family name. */
        private fun cjkCount(s: String): Int {
            var n = 0
            for (c in s) if (c.code in 0x4E00..0x9FFF) n++
            return n
        }

        /** CJK-codepoint ratio; close to 1 for a correctly byte-ordered Chinese name, only about half falling in CJK for a wrong byte order. */
        private fun cjkRatio(s: String): Float {
            if (s.isEmpty()) return 0f
            return cjkCount(s).toFloat() / s.length
        }

        /** Ratio of printable ASCII / common Latin characters among valid code points (the higher, the more it looks like a correctly decoded Latin name). */
        private fun asciiRatio(s: String): Float {
            if (s.isEmpty()) return 0f
            var readable = 0
            for (c in s) {
                val v = c.code
                if (v in 0x20..0x7e || v in 0x00a0..0x00ff) readable++
            }
            return readable.toFloat() / s.length
        }

        private fun decodeMacRoman(bytes: ByteArray, start: Int, end: Int): String? {
            if (end <= start) return null
            val out = StringBuilder(end - start)
            for (i in start until end) {
                val b = bytes[i].toInt() and 0xff
                // Family names are usually pure ASCII; high bytes (MacRoman extensions) are dropped as non-printable symbols
                if (b in 0x20..0x7e) out.append(b.toChar()) else out.append(' ')
            }
            return out.toString().replace("\\s+".toRegex(), " ").trim().ifEmpty { null }
        }

        private data class TableInfo(val offset: Int, val length: Int)
    }
}