package orilumn.reader.desktop

import orilumn.reader.data.font.FontParser
import java.io.File
import java.io.RandomAccessFile

/**
 * 方案B：系统字体中文族名扫描器 —— 不依赖 CoreText / 不依赖字典，直接读 OpenType
 * name 表（按拉丁族名尾缀匹配变体语言：TC→zh-TW、HK→zh-HK、MO→zh-MO、其余→zh-CN，
 * 均无回退任意含 CJK 记录；不筛简繁，name 表里是什么就显示什么），建立
 * 「拉丁族名（对齐平台枚举键）→ 中文族名」映射。
 *
 * 接入链（ReaderView）：name 表直读 **优先** → 方案A `MacFamilyNames`（CoreText）补缺
 * → `FontEntry.display` 无本地化名时回退族名本身。
 *
 * 读法：只读取每个字体文件的 sfnt 表目录 + name 表区间（RandomAccessFile 定位区间），
 * 不整读大文件（STHeiti Light.ttc 55MB 只读头几十 KB），进程内缓存一次。
 */
object NameTableChineseNames {

    private val parser = FontParser()

    private val fontDirs = listOf(
        "/System/Library/Fonts",
        "/System/Library/Fonts/Supplemental",
        "/System/Library/AssetsV2/com_apple_MobileAsset_Font7",
        "/Library/Fonts",
        "${System.getProperty("user.home")}/Library/Fonts",
        "${System.getProperty("user.home")}/.fonts",
    )

    @Volatile private var cache: Map<String, String>? = null

    /** 全量映射：拉丁族名 → 中文族名（仅收录 chinese 含 CJK 的族）。扫描一次进程内缓存。 */
    fun chineseNames(): Map<String, String> = cache ?: synchronized(this) {
        cache ?: scanAll().also { cache = it }
    }

    /** 只取给定族的映射（供 syncSystemFaces 落 displayName）。 */
    fun namesFor(families: Collection<String>): Map<String, String> {
        val all = chineseNames()
        return families.mapNotNull { fam -> all[fam]?.let { fam to it } }.toMap()
    }

    private fun scanAll(): Map<String, String> = buildMap {
        for (dir in fontDirs) {
            val d = File(dir)
            if (!d.isDirectory()) continue
            d.walkTopDown().filter { f ->
                val n = f.name.lowercase()
                n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc") || n.endsWith(".otc")
            }.forEach { f ->
                for (names in faceNamesOf(f)) {
                    val latin = names.latin ?: continue
                    val chinese = names.chinese ?: continue
                    if (chinese.any { it.code in 0x4E00..0x9FFF } && latin !in this) {
                        put(latin, chinese)
                    }
                }
            }
        }
    }

    /**
     * 列出文件内每个面（TTC 每面独立 name 表，逐个解析；TTF/OTF 单面）的 latin/中文名对。
     * 只读各区间的表目录 + name 表，不整读大文件；任一面失败不影响其他面。
     */
    private fun faceNamesOf(file: File): List<FontParser.FamilyNames> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return@use emptyList()
            val head = ByteArray(16)
            raf.readFully(head)
            val isTtc = String(head, 0, 4, Charsets.US_ASCII) == "ttcf"
            val numFonts = if (isTtc) {
                val n = intAt(head, 8)
                if (n < 1 || n > 128) return@use emptyList()
                n
            } else 1
            val bases = if (isTtc) {
                raf.seek(12)
                val offs = ByteArray(numFonts * 4)
                raf.readFully(offs)
                List(numFonts) { intAt(offs, it * 4) }
            } else listOf(0)
            bases.mapNotNull { base ->
                readFaceNameBytes(raf, base)?.let {
                    runCatching { parser.familyNamesOf(it) }.getOrNull()
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 只读单个面的 sfnt 表目录 + 定位 'name' 表区间，重组为最小可解析字节
     * （sfnt 头 + 单个 name 表目录项 + name 表本体），供 [FontParser.familyNamesOf]。
     */
    private fun readFaceNameBytes(raf: RandomAccessFile, base: Int): ByteArray? = runCatching {
        raf.seek((base + 4).toLong())
        val numTables = raf.readUnsignedShort()
        if (numTables <= 0 || numTables > 4096) return@runCatching null

        raf.seek((base + 12).toLong())
        val dir = ByteArray(numTables * 16)
        raf.readFully(dir)

        var nameOff = -1
        var nameLen = -1
        for (i in 0 until numTables) {
            val rec = i * 16
            if (String(dir, rec, 4, Charsets.US_ASCII) == "name") {
                nameOff = intAt(dir, rec + 8)
                nameLen = intAt(dir, rec + 12)
                break
            }
        }
        if (nameOff < 0 || nameLen <= 0 || nameLen > 1 shl 20) return@runCatching null

        raf.seek(nameOff.toLong())
        val table = ByteArray(nameLen)
        raf.readFully(table)

        // 重组最小字模：sfnt 头(12) + 1 条目录项(16) + name 表本体。
        val out = ByteArray(28 + table.size)
        out[0] = 0; out[1] = 1; out[2] = 0; out[3] = 0 // sfnt version 1.0
        out[4] = 0; out[5] = 1 // numTables = 1
        val rec = 12
        val tag = "name".toByteArray(Charsets.US_ASCII)
        for (i in 0 until 4) out[rec + i] = tag[i]
        putInt(out, rec + 8, 28)
        putInt(out, rec + 12, table.size)
        System.arraycopy(table, 0, out, 28, table.size)
        out
    }.getOrNull()

    private fun intAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff shl 24) or (b[off + 1].toInt() and 0xff shl 16) or
            (b[off + 2].toInt() and 0xff shl 8) or (b[off + 3].toInt() and 0xff)

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}