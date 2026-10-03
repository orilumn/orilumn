package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7第二步回归锁：K-L 断词必须与 **pyphen** 逐值一致。
 *
 * ## 为什么必须逐值（而不是「看起来像音节」）
 *
 * K-L 实现有三处「错了不报错、只表现为断点偏多/偏少」的坑（全部踩过）：
 * ① 模式里的数字当字符比⇒ 一条都匹配不上、恒返回空；
 * ② 权重挂在字符上而非**间隙**上（`hy3` 的权重落在 `y` 之后）⇒ 整体错一位；
 * ③ 裁剪右端写成排他 `until` ⇒ 词尾少一个断点。
 * 三者都**不会抛异常**，所以只能用「与权威实现逐值相同」来钉。
 *
 * ## 金标准从哪来
 *
 * 本轮在 host 上用 **pyphen 的 `HyphDict.positions` 逻辑逐行复刻**（正则 `(\d?)(\D?)`、
 * `chop zeros`、`map(max, …)`、循环边界 `range(n-1)` / `min(i+maxlen, n)+1`）生成，
 * 逐词对照后才收敛。**判据即那批逐值结果**，不另拍。
 */
class HyphenatorTest {

    /** 与 pyphen 逐值相同的样本（覆盖短词/长词/连缀/元音串/词尾边界）。 */
    private val cases = listOf(
        "hyphenation" to listOf(2, 6),
        "computer" to listOf(3, 6),
        "algorithm" to listOf(2, 4),
        "typography" to listOf(2, 5, 7),
        "representation" to listOf(3, 5, 8, 10),
        "internationalization" to listOf(2, 5, 7, 11, 13, 16),
        "extraordinary" to listOf(2, 5, 7, 9),
        "difficult" to listOf(3, 5),
        "project" to listOf(3),
        "associate" to listOf(2, 4, 6),
        "present" to listOf(3),
        "university" to listOf(3, 6, 8),
        "engineering" to listOf(2, 4, 8),
        "document" to listOf(3, 4),
        "table" to emptyList(),
        "paragraph" to listOf(4),
        "identifier" to listOf(4, 6, 8),
        "implementation" to listOf(2, 5, 8, 10),
        "variable" to listOf(4),
    )

    @Test
    fun `与 pyphen 逐值一致`() {
        for ((w, expected) in cases) {
            assertEquals(
                "「$w」的断点必须与 pyphen 逐值相同",
                expected, Hyphenator.hyphenate(w),
            )
        }
    }

    @Test
    fun `大小写不敏感且首尾不越界`() {
        assertEquals("首字母大写应与全小写同断点", Hyphenator.hyphenate("hyphenation"), Hyphenator.hyphenate("Hyphenation"))
        assertEquals("全大写同断点", Hyphenator.hyphenate("hyphenation"), Hyphenator.hyphenate("HYPHENATION"))
        for ((w, pts) in cases) {
            for (p in pts) {
                assertTrue("「$w」断点 $p 必须在词内", p in 1 until w.length)
                assertTrue("「$w」断点 $p 必须留够左侧 (left=2)", p >= 2)
                assertTrue("「$w」断点 $p 必须留够右侧 (right=2)", p <= w.length - 2)
            }
        }
    }

    @Test
    fun `非拉丁词与未加载语言退回空（由 R1 兜底）`() {
        assertEquals("纯数字不适用", emptyList<Int>(), Hyphenator.hyphenate("abc123def"))
        assertEquals("CJK 不适用", emptyList<Int>(), Hyphenator.hyphenate("中文测试文本"))
        assertEquals("未加载语种退回空", emptyList<Int>(), Hyphenator.hyphenate("hyphenation", lang = "xx"))
        assertEquals("过短词不断", emptyList<Int>(), Hyphenator.hyphenate("the"))
    }

    @Test
    fun `表非空且语种已注册（防止表被生成脚本写坏）`() {
        assertTrue("模式表不应为空", HyphEnUsPatterns.TABLE.size > 4000)
        assertTrue("en 必须已注册", "en" in Hyphenator.loadedLanguages())
    }
}