package orilumn.reader.engine.skia

import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * 断行器夹具：**从单源工厂取实例**，测试不自己 `new`，保证与生产接线不可能分家。
 *
 * 生产只剩一套断行实现（[bodyParagraphBreaker] → [InhouseParagraphBreaker]）。
 * 这里保留 `label` 形参只是为了让断言消息仍写明「哪一侧」，不再代表任何变体。
 */
internal fun variantBreaker(letterSpacingEm: Float = 0f): ParagraphBreaker =
    bodyParagraphBreaker(letterSpacingEm)

internal fun withBodyBreaker(
    cjkLatinSpacingEm: () -> Float = { 0f },
    block: (ParagraphBreaker, String) -> Unit,
) {
    block(bodyParagraphBreaker(letterSpacingEm = 0f, cjkLatinSpacingEm = cjkLatinSpacingEm()), "inhouse")
}
