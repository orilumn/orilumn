package orilumn.reader.engine.skia

import org.jetbrains.skia.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D1 池装载探测字的族宽度对照集（都是本机确定存在的排版字体）。 */
private val otherFamilies = listOf("Times New Roman", "Verdana", "Courier New", "Georgia")

/**
 * D2 — 系统字体集合接缝 + D1 — 字体池装载。
 *
 * 母文档 §3「系统字体集合」边界的第一处显式接缝：所有 FontCollection/ParagraphStyle 构造公平经
 * [systemFonts]，字体内嵌装载经 [SkParagraphFactory.embeddedFontCollection]。平台度量差异是能力边界，
 * 这里只断言接缝存在、池装载可解析。
 */
class SystemFontsAndFontPoolTest {

    @Test
    fun systemFontsResolvesAFamily() {
        val mgr = systemFonts()
        assertNotNull(mgr)
        // macOS CoreText 不认 CSS 通用名（serif/sans-serif），resolveFamilies 才会展开成实族；
        // 这里断言接缝能落实具体系统族即可。
        assertTrue(
            "系统字体集必须含 macOS 实族 Helvetica",
            mgr.matchFamilyStyle("Helvetica", FontStyle.NORMAL) != null,
        )
    }

    @Test
    fun systemFontFamiliesListsRealFamilies() {
        val fams = systemFontFamilies()
        assertTrue("系统族枚举非空（macOS CoreText 上百族）", fams.isNotEmpty())
        assertTrue("应含 Helvetica", fams.contains("Helvetica"))
        assertEquals("去重+排序", fams.sorted(), fams)
        assertTrue("无空名", fams.none { it.isBlank() })
    }

    @Test
    fun systemFontFacesListsWeights() {
        val faces = systemFontFaces()
        assertTrue("系统字形枚举非空", faces.isNotEmpty())
        assertTrue("无空族名", faces.all { it.family.isNotBlank() })
        val byFamily = faces.groupBy { it.family }
        // 常见族在列（Helvetica：CoreText 必带一族）。
        assertTrue("应含 Helvetica", byFamily.containsKey("Helvetica"))
        // Windows/ClassicMac 默认族之一应带真实字重名（≠ 推到单行空字重的情况过半）。
        val weighted = faces.count { it.subfamily.isNotBlank() }
        assertTrue("多数面应带字重名（实际 $weighted/${faces.size}）", weighted >= faces.size / 2)
        // 至少一个族展开出 ≥2 字重（如 Helvetica Regular/Bold）。
        assertTrue(
            "应存在多字重家族（样例: " + helveticaSample(byFamily) + ")",
            byFamily.values.any { it.map { f -> f.subfamily }.filter { s -> s.isNotBlank() }.distinct().size >= 2 },
        )
    }

    private fun helveticaSample(byFamily: Map<String, List<orilumn.reader.data.font.SystemFontFace>>): String =
        byFamily["Helvetica"]?.joinToString { it.subfamily } ?: "(Helvetica 未枚举)"

    @Test
    fun skiaFontPoolServesEmbeddedAliasesAndResets() {
        // 共用池：别名（展示名）与真实族名同二进制同宽；置空回退默认且幂等无重建。
        val file = systemTtfCandidates.firstOrNull { it.second.isFile } ?: return
        val realName = file.first
        val bytes = file.second.readBytes()
        val alias = "OrilumnPoolAlias"
        try {
            assertTrue(SkiaFontPool.setEmbedded(listOf(SkiaFontPool.EmbeddedFont(realName, bytes, listOf(alias)))))
            assertTrue(
                "同签名二次设置不得重建",
                !SkiaFontPool.setEmbedded(listOf(SkiaFontPool.EmbeddedFont(realName, bytes, listOf(alias)))),
            )
            val collection = SkiaFontPool.current()
            val widths: (String) -> Float = { fam ->
                val style = SkParagraphFactory.paragraphStyle(
                    alignment = orilumn.reader.engine.css.TextAlign.LEFT, fontSizePx = 20f, lineHeightRatio = 1f,
                    tag = "p", families = listOf(fam), weight = 400, italic = false, monospace = false,
                    letterSpacingEm = 0f, fontManager = systemFonts(),
                )
                val paragraph = org.jetbrains.skia.paragraph.ParagraphBuilder(style, collection).addText("AB")
                    .build()
                try {
                    paragraph.layout(1000f)
                    paragraph.maxIntrinsicWidth
                } finally {
                    paragraph.close()
                }
            }
            val viaAlias = widths(alias)
            val viaReal = widths(realName)
            assertTrue("别名必须命中同一内嵌二进制", viaAlias in (viaReal - 0.5f)..(viaReal + 0.5f))
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }

    @Test
    fun embeddedFontCollectionLoadsBinaryFamily() {
        val file = systemTtfCandidates.firstOrNull { it.second.isFile } ?: return
        val realName = file.first
        val bytes = file.second.readBytes()
        // 同一二进制按「真实族名」+「探针族名」两次进池：若池装载成功两者宽度一致；
        // 若探针族被回退到系统默认族，宽度≠真实族名宽度 —— 一票否决。
        val probeName = "OrilumnPoolProbe"
        val collection = SkParagraphFactory.embeddedFontCollection(
            listOf(
                SkiaFontPool.EmbeddedFont(realName, bytes),
                SkiaFontPool.EmbeddedFont(probeName, bytes),
            ),
        )

        // resolveFamilies 的 pool 语义：具名族原样保留（不被展开/丢弃）。
        val kept = SkParagraphFactory.resolveFamilies("p", listOf(probeName), false, systemFonts())
        assertTrue("具名族必须保留在 Family 栈中", kept.contains(probeName))

        val widths: (String) -> Float = { fam ->
            val style = SkParagraphFactory.paragraphStyle(
                alignment = orilumn.reader.engine.css.TextAlign.LEFT, fontSizePx = 20f, lineHeightRatio = 1f,
                tag = "p", families = listOf(fam), weight = 400, italic = false, monospace = false,
                letterSpacingEm = 0f, fontManager = systemFonts(),
            )
            val paragraph = org.jetbrains.skia.paragraph.ParagraphBuilder(style, collection).addText("AB")
                .build()
            try {
                paragraph.layout(1000f)
                paragraph.maxIntrinsicWidth
            } finally {
                paragraph.close()
            }
        }
        val probe = widths(probeName)
        val original = widths(realName)
        val other = widths(otherFamilies.first { it != realName })
        assertTrue("内嵌二进制家族必须可输出字形（探针=原厂族宽度）", probe in (original - 0.5f)..(original + 0.5f))
        assertTrue("对照族与探针族必须不同宽", probe !in (other - 0.5f)..(other + 0.5f))
    }

    private companion object {
        // 测试机上的确定性 TTF（非符号字体），不存在时跳过 —— 与既有 jvmTest 的 macOS CoreText 前提一致。
        // (真实族名, 文件) —— 族名须与字体文件的 name 表一致，供探针对照。
        val systemTtfCandidates: List<Pair<String, File>> = listOf(
            "Arial" to File("/System/Library/Fonts/Supplemental/Arial.ttf"),
            "Times New Roman" to File("/System/Library/Fonts/Supplemental/Times New Roman.ttf"),
            "Verdana" to File("/System/Library/Fonts/Supplemental/Verdana.ttf"),
            "Courier New" to File("/System/Library/Fonts/Supplemental/Courier New.ttf"),
        ).filter { it.second.isFile }
    }
}