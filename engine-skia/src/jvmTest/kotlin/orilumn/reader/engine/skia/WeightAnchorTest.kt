package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字重锚点改写（渲染层 `SkParagraphFactory.anchoredWeight` 纯逻辑）：
 * 正体 400 命中锚点即改用，粗斜体/非 400/未命中原样。
 */
class WeightAnchorTest {

    @Test
    fun anchoredWeight_rewritesUprightNormalOnly() {
        SkParagraphFactory.weightAnchors = mapOf("Songti SC" to 700)
        try {
            assertEquals(700, SkParagraphFactory.anchoredWeight(listOf("Songti SC"), 400, false))
            // 粗体自然匹配，不劫持
            assertEquals(700, SkParagraphFactory.anchoredWeight(listOf("Songti SC"), 700, false))
            // 斜体自然匹配，不劫持
            assertEquals(400, SkParagraphFactory.anchoredWeight(listOf("Songti SC"), 400, true))
            // 未命中族原样
            assertEquals(400, SkParagraphFactory.anchoredWeight(listOf("PingFang SC"), 400, false))
            // 级联中后位命中也改写（槽位族不在首位时）
            assertEquals(700, SkParagraphFactory.anchoredWeight(listOf("Unknown", "Songti SC"), 400, false))
            // 非法锚点值丢弃
            SkParagraphFactory.weightAnchors = mapOf("Songti SC" to 50)
            assertEquals(400, SkParagraphFactory.anchoredWeight(listOf("Songti SC"), 400, false))
        } finally {
            SkParagraphFactory.weightAnchors = emptyMap()
        }
    }
}
