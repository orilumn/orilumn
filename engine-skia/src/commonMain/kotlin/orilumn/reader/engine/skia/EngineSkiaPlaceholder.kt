package orilumn.reader.engine.skia

/**
 * engine-skia —— Skiko / SkParagraph 排版与绘制实现模块（G 阶段起）。
 *
 * 职责（见 `docs/KMP迁移-目标结构.md` §1.2）：
 *  - [orilumn.reader.engine.laying.ParagraphBreaker] 的 SkParagraph 实现（`SkiaParagraphBreaker`，S24）
 *  - 按行窗口绝对 Y 坐标绘制（`LineWindowDrawer`，S25）
 *  - 跨平台字体集合与图片解码（后续阶段）
 *
 * 依赖方向：engine-skia → common；不反向引用任何平台 UI。placeholder 仅用于 S22 骨架编译冒烟，
 * 后续步骤实现即取代。
 */
object EngineSkia {
    val moduleReady: Boolean = true
}