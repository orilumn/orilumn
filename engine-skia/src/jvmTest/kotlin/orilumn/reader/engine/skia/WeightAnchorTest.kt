package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字重锚点机制已**整体退役**，本文件锁的是「渲染层不再私自改写字重」这条不变量。
 *
 * 旧实现在 `SkParagraphFactory.anchoredWeight`（渲染层·级联之后的 post-hoc mutation），
 * 违反 `Cascade` 决策 6「upper layers … without any post-hoc mutation」，并派生出三个缺陷：
 * 标题恒无效（靠 `italic`/非 400 启发式猜）、跨槽串扰（键只有族名）、装池被收窄成单面。
 * 现在用户字重是 UI 层声明（`ReaderUiSheet.fontRules` 发 `font-weight`，tier 44），级联里就赢。
 */
class WeightAnchorTest {

    @Test
    fun runFontStyle_isAPureMappingOfCascadeOutput() {
        // 同样的 (weight, italic) 必须恒给出同样的 FontStyle：渲染层无状态、无族名依赖。
        assertEquals(
            SkParagraphFactory.runFontStyle(400, false),
            SkParagraphFactory.runFontStyle(400, false),
        )
        assertEquals(FontSlantRef.UPRIGHT, SkParagraphFactory.runFontStyle(700, false).slant)
        // 书里的 font-style:italic 胜出时，斜体必须原样传下去（UI 层不声明 font-style）。
        assertEquals(FontSlantRef.ITALIC, SkParagraphFactory.runFontStyle(700, true).slant)
        // 斜体与字重是两个独立维度：斜体不吞字重，正体不吞斜体。
        assertEquals(300, SkParagraphFactory.runFontStyle(300, true).weight)
        assertEquals(900, SkParagraphFactory.runFontStyle(900, false).weight)
    }

    /** 退役断言：`anchoredWeight` 与两个 `@Volatile` 锚点表不得再回来（它们是 post-hoc mutation）。 */
    @Test
    fun anchorTablesAreGone() {
        val cls = SkParagraphFactory::class.java
        val gone = listOf("anchoredWeight", "weightAnchors", "weightAnchorsBySlot")
            .filter { cls.methods.any { m -> m.name == it } || cls.declaredFields.any { f -> f.name == it } }
        assertEquals("渲染层不得再有字重锚点（用户字重走 UI 层声明 + 级联）", emptyList<String>(), gone)
    }

    private object FontSlantRef {
        val UPRIGHT = org.jetbrains.skia.FontSlant.UPRIGHT
        val ITALIC = org.jetbrains.skia.FontSlant.ITALIC
    }
}