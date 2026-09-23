package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D1 行为对账（过渡断言，退役后删除）：平板旧解析器（FontPairing.SYSTEM 的 matchClass 令牌分类）
 * 与 engine-skia [SkParagraphFactory.resolveFamilies] 在相同 `font-spec` 下的字体族命中一致。
 *
 * 旧解析器按「码类令牌」跳到 SERIF/SANS/MONO（无令牌 = 默认）；engine-skia 把整栈交给
 * FontCollection 按字形逐族回退，最终首次命中的实族也可用同一令牌表归到码类。两者一致 =
 * 解析器迁移不改书排版的结果类。FontPairing 退役后本测试删除。
 */
class ResolveFamiliesReconcileTest {

    @Test
    fun engineAndLegacyAgreeOnFamilyClassHits() {
        val mgr = systemFonts()
        for ((tag, families, mono) in MATRIX) {
            val engineHits = SkParagraphFactory.resolveFamilies(tag, families.map { it }, mono, mgr)
            val engineClass = engineHits
                .firstNotNullOfOrNull { LegacySystemPairing.matchClass(it) }
            val legacyClass = LegacySystemPairing.hitClass(tag, families.map { it }, mono)
            assertEquals("spec tag=$tag families=$families mono=$mono", legacyClass, engineClass)
        }
    }

    @Test
    fun codeStackHonorsAuthorStackOverLegacyMono() {
        // 曾经是上面 MATRIX 的一部分：code-like 恒取 monospace。用户代码槽与书内 code 字体
        // 要求 honor 非空栈（浏览器语义：`code{font-family:Georgia}` 即按 Georgia 排），
        // 与 legacy 故意分叉；空栈仍回 monospace（与 legacy 一致）。
        val mgr = systemFonts()
        fun cls(tag: String?, families: List<String>, mono: Boolean): FontClass? =
            SkParagraphFactory.resolveFamilies(tag, families, mono, mgr)
                .firstNotNullOfOrNull { LegacySystemPairing.matchClass(it) }
        assertEquals(FontClass.SERIF, cls("code", listOf("serif"), false))
        assertEquals(FontClass.SANS, cls("pre", listOf("sans-serif"), false))
        assertEquals(FontClass.MONO, cls("code", emptyList(), false))
    }

    private data class Spec(val tag: String?, val families: List<String>, val mono: Boolean = false)

    private val MATRIX = listOf(
        Spec("p", emptyList()),
        Spec("p", listOf("serif")),
        Spec("p", listOf("sans-serif")),
        Spec("p", listOf("monospace")),
        Spec("p", listOf("思源宋体 VF", "serif")),
        Spec("p", listOf("Noto Sans CJK SC", "sans-serif")),
        Spec("p", listOf("Source Han Sans", "sans-serif")),
        Spec("p", listOf("Menlo", "Courier New", "monospace"), mono = false),
        Spec("code", emptyList()),
        Spec("code", listOf("Fira Code", "monospace"), mono = true),
        Spec("h1", listOf("Georgia", "serif")),
        Spec("h1", listOf("思源宋体 VF")),
        Spec("blockquote", listOf("宋体")),
        Spec("p", listOf("PingFang SC", "sans-serif")),
        Spec("code", listOf("Fira Code", "monospace"), mono = true),
    )
}

/** 平板旧解析器 FontPairing.SYSTEM 的回溯快照（D1 过渡对账期使用，退役后删除）。 */
private object LegacySystemPairing {
    private val CODE_TAGS = setOf("pre", "code", "kbd", "samp")

    private val MONO_TOKENS = listOf("mono", "courier", "consolas", "menlo", "code")
    private val SERIF_TOKENS = listOf(
        "serif", "宋体", "宋", "simsun", "nsimsun", "stsong", "stzhongsong", "zhongsong", "songti",
        "source han serif", "noto serif", "han serif", "dk-songti", "dk songti",
        "times", "georgia", "tinos",
        "楷体", "kaiti", "stkaiti", "仿宋", "fangsong", "stfangsong",
        "mingliu", "mincho", "明",
    )
    private val SANS_TOKENS = listOf(
        "sans", "黑体", "黑", "hei", "yahei", "pingfang", "hiragino", "冬青",
        "source han sans", "noto sans", "han sans", "stheiti",
        "microsoft", "droidsans", "roboto",
    )

    /** 旧 SYSTEM.hitClass：code-like→MONO；否则按作者序走 matchClass，无命中→null（默认）。 */
    fun hitClass(tag: String?, families: List<String>, monospace: Boolean): FontClass? {
        if (monospace || tag in CODE_TAGS) return FontClass.MONO
        for (raw in families) matchClass(raw)?.let { return it }
        return null
    }

    fun matchClass(raw: String): FontClass? {
        val n = raw.lowercase()
        when (n.trim()) {
            "serif" -> return FontClass.SERIF
            "sans-serif" -> return FontClass.SANS
            "monospace" -> return FontClass.MONO
        }
        if (MONO_TOKENS.any { n.contains(it) }) return FontClass.MONO
        if (SERIF_TOKENS.any { n.contains(it) }) return FontClass.SERIF
        if (SANS_TOKENS.any { n.contains(it) }) return FontClass.SANS
        return null
    }
}

/** 旧解析器命中码类（过渡对账用）。 */
private enum class FontClass { SERIF, SANS, MONO }