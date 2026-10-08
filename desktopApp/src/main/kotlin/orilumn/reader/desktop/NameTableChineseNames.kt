package orilumn.reader.desktop

import orilumn.reader.data.font.FontParser
import orilumn.reader.engine.skia.systemFontFamilies
import orilumn.reader.engine.skia.systemFonts
import org.jetbrains.skia.FontMgr

/**
 * 方案B：系统字体中文族名扫描器 —— 不依赖 CoreText / 不依赖字典，直接读 OpenType
 * name 表（按拉丁族名尾缀匹配变体语言：TC→zh-TW、HK→zh-HK、MO→zh-MO、其余→zh-CN，
 * 均无回退任意含 CJK 记录；不筛简繁，name 表里是什么就显示什么），建立
 * 「拉丁族名（对齐平台枚举键）→ 中文族名」映射。
 *
 * 接入链（ReaderView）：name 表直读 **优先** → 方案A `MacFamilyNames`（CoreText）补缺
 * → `FontEntry.display` 无本地化名时回退族名本身。
 *
 * 字体发现走 skiko 枚举（[systemFontFamilies] + `matchFamily` + `getTypeface`
 * + `getTableData("name")`，与 engine-skia `styleNameZh` 同一套平台无关 API）：
 * 枚举出的正是阅读器能渲染的字体，macOS（CoreText）/Linux（fontconfig）/
 * Windows（GDI）同一段代码，不读平台字体目录。只读 name 表字节，
 * 进程内缓存一次。
 */
object NameTableChineseNames {

    private val parser = FontParser()

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
        val mgr = runCatching { systemFonts() }.getOrNull() ?: return@buildMap
        for (family in runCatching { systemFontFamilies() }.getOrDefault(emptyList())) {
            val chinese = chineseOf(mgr, family) ?: continue
            if (chinese.any { it.code in 0x4E00..0x9FFF } && family !in this) {
                put(family, chinese)
            }
        }
    }

    /**
     * 取一族的中文名：`matchFamily` 命中的首个 typeface 的
     * `name` 表字节，重组为最小 sfnt（sfnt 头 + 1 条目录项 + name 表本体）
     * 后交 [FontParser.familyNamesOf]（TTC 已由 skiko 按面枚举，无需再拆）。
     * 键用枚举族名（[namesFor] 的查询键）， latin 记录仅作可用性守卫。
     */
    private fun chineseOf(mgr: FontMgr, family: String): String? = runCatching {
        mgr.matchFamily(family)?.use { set ->
            if (set.count() <= 0) return@runCatching null
            set.getTypeface(0)?.use { tf ->
                tf.getTableData("name")?.use { data ->
                    val table = data.bytes
                    if (table.isEmpty()) return@use null
                    parser.familyNamesOf(minimalSfnt(table))?.chinese
                }
            }
        }
    }.getOrNull()

    /** 重组最小字模：sfnt 头(12) + 1 条目录项(16) + name 表本体。 */
    private fun minimalSfnt(table: ByteArray): ByteArray {
        val out = ByteArray(28 + table.size)
        out[0] = 0; out[1] = 1; out[2] = 0; out[3] = 0 // sfnt version 1.0
        out[4] = 0; out[5] = 1 // numTables = 1
        val rec = 12
        val tag = "name".toByteArray(Charsets.US_ASCII)
        for (i in 0 until 4) out[rec + i] = tag[i]
        putInt(out, rec + 8, 28)
        putInt(out, rec + 12, table.size)
        System.arraycopy(table, 0, out, 28, table.size)
        return out
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
