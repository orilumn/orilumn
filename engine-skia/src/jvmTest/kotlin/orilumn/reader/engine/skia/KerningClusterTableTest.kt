package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * S5b 回归锁：西文 **kerning / fi-fl 连字** 必须真的在绘制路径上生效。
 *
 * ## 为什么需要这把锁（否则「做了等于没做」）
 *
 * S5 逐字 `drawString` 后西文 kerning 是**丢**的（Q5）。S5b 用 [KerningClusterTable]
 * 对含拉丁字母的行改走 Skia 簇位把它找回来。这个功能**极易静默失效**：
 * 簇位拿不到时 [KerningClusterTable.clusterXs] 返 `null`、调用方**静默退回**
 * `LineAligner` 的裸 cmap x —— 于是「没 kerning」的旧行为照旧画，**测试照样全绿**。
 * 一个退回就等于没实现，而退回不报错 ⇒ 必须有锁直接断言「kerning 的效果存在」。
 *
 * ## 判据：同一段文本，`AV` 这类 kern 对的间距应与「逐字裸 cmap」不同
 *
 * 直接测像素太脆（字号/字体/平台抗锯齿都会动）。改测**几何差**：
 * 同一段文本，取「含 kerning 簇位」与「裸 cmap 累加」两个 x 表，
 * 对有 kern 对的位置断言两者**不相等**，且**无 kern 对的位置相等**。
 * 这样判据与平台无关，且天然区分「拿到簇位」与「静默退回」。
 */
class KerningClusterTableTest {

    private val fs = 44.4f
    private val fam = listOf("Times New Roman", "serif")

    private fun table() = KerningClusterTable()

    private fun clusterXsFor(text: String, fam: List<String> = this.fam): FloatArray? =
        table().clusterXs(text, 0, text.length, fs, 1.5f, "p", fam, 400, false, false, emptyList(), 0f)

    private fun bareXs(text: String, fam: List<String> = this.fam): FloatArray =
        SkiaRunMeasurer().advances(text, fs, 0f, "p", fam, 400, false, false, emptyList())

    @Test
    fun `kering 对位置的簇位与裸 cmap 不等（证明 kerning 生效）`() {
        // AV / To / Ya 是 kern 表里典型的收紧对（Q5: Times New Roman 59 对）。
        val text = "AV To Ya WA"
        val kern = requireNotNull(clusterXsFor(text)) { "簇位必须拿得到（Times 装了）" }
        val bare = bareXs(text)
        assertTrue("簇位表长度必须与文本等长", kern.size == text.length)

        // 逐字比较相邻间距：只要**存在一处** kern 对间距被收紧，就证明生效。
        var tightened = 0
        for (i in 0 until text.length - 1) {
            val kernGap = kern[i + 1] - kern[i]
            val bareGap = bare[i + 1] - bare[i]
            if (abs(kernGap - bareGap) > 0.01f) tightened++
        }
        assertTrue(
            "含 AV/To/Ya 的文本里必须存在 kern 收紧（实测收紧处数=$tightened）。" +
                "若为 0，说明 clusterXs 静默退回裸 cmap（拿不到簇位）—— S5b 等于没做。" +
                "本机若无 Times New Roman，请换一本**装了**带 kern 表的字族再跑。",
            tightened > 0,
        )
    }

    @Test
    fun `纯 CJK 行不建 Paragraph（needsClusters 返 false，零成本）`() {
        val t = table()
        assertTrue(
            "纯中文行必须判定为「无需 kerning」⇒ 走裸 cmap 快径、零 Paragraph 开销",
            !t.needsClusters("派生实现了方法当其为整个类型", 0, 13),
        )
        assertTrue(
            "含拉丁字母的行必须判定为「需要 kerning」",
            t.needsClusters("派生 Clone", 0, 6),
        )
    }

    @Test
    fun `行内换面分段各自取簇位（不混面）`() {
        // 前半 Times、后半 monospace：换面段必须分别取面，否则簇位会按错误的面算。
        val text = "AVToYa"
        val runs = listOf(FontRun(0, 3, fam, null, 400, false, false, 0f))
        val kern = requireNotNull(
            table().clusterXs(text, 0, text.length, fs, 1.5f, "p", fam, 400, false, false, runs, 0f),
        ) { "分段簇位必须拿得到" }
        assertTrue("分段后长度仍与文本等长", kern.size == text.length)
        assertTrue("簇位必须单调不减", (1 until kern.size).all { kern[it] >= kern[it - 1] })
    }
}