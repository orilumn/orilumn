package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 接线锁：**正文断行变体必须三处同源**（重路径 / 轻路径浮动 / 叶级 `breakWrappedLines`）。
 *
 * ## 为什么这条锁不可省
 *
 * 接线点是三处分散的 `if (开关) 自建 else Skia`。任何一侧漏改就是**两侧分家**：
 * 量宽按自建断点、绘制按 Skia 断点（或反之），症状是「同一段文字的行数/高度随翻页方式变化」
 * 这类极难归因的漂移 —— 而且在小样本回归里往往**全绿**（T2d/T2e 就是这么骗过人的，
 * 见 `docs/自建断行引擎-测试计划.md` 教训 25 / §T2d-线上）。
 *
 * 本锁不试图穷举接线点（那是文本检索、脆），而是**锁住单源工厂本身的行为契约**：
 * 同一开关状态下必须给出同一实现类。这把「三处分家」从「靠人记得」降级为「靠一条测试」。
 *
 * ## 判据为什么用类名而不是输出
 *
 * 输出等价由 `InhouseParagraphBreakerTest` 的四层 parity 锁负责（那一侧测的是算法）。
 * 本锁只测**接线**：`factory` 在两侧必须分别返回两个具体类，且不接受第三个实现。
 */
class BodyParagraphBreakerWiringTest {

    @Test
    fun `factory returns Skia by default`() {
        AbSwitch.resetForTest()
        try {
            assertEquals(
                "默认（未传 ab extra）必须是 Skia —— 这是生产行为，也是回退阀的静止位",
                SkiaParagraphBreaker::class.java,
                bodyParagraphBreaker(0f)::class.java,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    @Test
    fun `factory returns inhouse when the switch is on`() {
        AbSwitch.resetForTest()
        try {
            AbSwitch.apply("inhouseBreak=1")
            assertTrue(AbSwitch.inhouseBreak())
            assertEquals(
                "开关打开后必须换成自建断行器，否则真机 A/B 量的是同一个实现",
                InhouseParagraphBreaker::class.java,
                bodyParagraphBreaker(0f)::class.java,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    @Test
    fun `both sides implement the full breaker contract`() {
        // 接线能编译不代表两侧能力对等：自建侧若漏实现某个重载，运行时才炸。
        // 这里断言两侧都能吃下「最宽」的那组参数（12 参版，含 fontRuns/baselineShifts）。
        AbSwitch.resetForTest()
        try {
            for ((name, b) in listOf(
                "skia" to bodyParagraphBreaker(0f),
                "inhouse" to InhouseParagraphBreaker(0f),
            )) {
                val lines = b.breakLines(
                    "中文 English 混排 test", 20f, 1.5f, 300,
                    orilumn.reader.engine.css.TextAlign.LEFT, "p", listOf("serif"), 400, false, false,
                )
                assertTrue("$name 侧对普通文本应产出至少一行", lines.isNotEmpty())
            }
        } finally {
            AbSwitch.resetForTest()
        }
    }
}
