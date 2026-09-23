package orilumn.reader.desktop

import orilumn.reader.engine.skia.systemFontFaces
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案B 实机回归：生产扫描器 [NameTableChineseNames.namesFor] 与 skiko 枚举对齐——
 * 用户点名的族必须从 name 表直出（不依赖 CoreText/字典）：SimSong(简宋)、Yu 系列(游ゴシック体/
 * 游教科書体/游明朝体)、Heiti SC/TC、Kaiti SC/TC、Songti SC/TC、LXGW 等。全量结果写 /tmp/family-align.txt。
 */
class NameTableFamilyAlignTest {

    @Test
    fun alignSkikoFamiliesWithNameTableScan() {
        val sys = runCatching { systemFontFaces() }.getOrDefault(emptyList())
        val families = sys.map { it.family }.distinct().sorted()

        val mapped = NameTableChineseNames.namesFor(families)
        val hits = families.filter { mapped.containsKey(it) }
        val misses = families.filter { !mapped.containsKey(it) }

        val sb = StringBuilder()
        sb.appendLine("=== skiko families: ${families.size}, name-table hits: ${hits.size} ===")
        sb.appendLine("--- hits ---")
        hits.forEach { sb.appendLine("$it -> ${mapped[it]}") }
        sb.appendLine("--- misses ---")
        misses.forEach { sb.appendLine(it) }
        File("/tmp/family-align.txt").writeText(sb.toString())
        System.err.println(sb.toString())

        // 命中族中文名必须含 CJK（日文假名照 name 表显示也 OK——Hiragino/Toppan 就是假名+汉字）。
        hits.forEach { hit ->
            val v = mapped.getValue(hit)
            assertTrue("命中族 $hit 中文名应含 CJK，实际「$v」", v.any { it.code in 0x4E00..0x9FFF })
        }
        // 用户点名族必须命中（本机已装）：name 表直读出中文/日文名。
        // 注意 TC 族走变体语言（zh-TW）——「黑體-繁」「楷體-繁」「宋體-繁」是繁体字面。
        val must = mapOf(
            "SimSong" to "简宋",
            "YuGothic" to "游ゴシック体",
            "YuKyokasho" to "游教科書体",
            "YuMincho" to "游明朝体",
            "Heiti SC" to "黑体-简",
            "Heiti TC" to "黑體-繁",
            "Kaiti TC" to "楷體-繁",
            "Kaiti SC" to "楷体-简",
            "Songti TC" to "宋體-繁",
            "Songti SC" to "宋体-简",
            "LXGW WenKai" to "霞鹜文楷",
            "LXGW WenKai Mono" to "霞鹜文楷等宽",
        )
        for ((family, expect) in must) {
            assertTrue(
                "name 表应直读 $family（现 ${mapped[family]}）",
                mapped[family]?.contains(expect) == true,
            )
        }
        // 扫描器解决的核心族必须够多；其余靠 CoreText 补缺（架构允许部分 miss）。
        assertTrue("命中率过低: ${hits.size}/${families.size}", hits.size >= 40)
    }
}