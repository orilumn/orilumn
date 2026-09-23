package orilumn.reader.engine.skia

/**
 * C2-P1: 布局回读缝（`:common`/`:engine-skia` 可见；`:app` 的 Android Canvas 渲染器与
 * 桌面布局实现各接一次，控制器只认接口，不认实现）。
 *
 * 方法即控制器从绘制布局回读页面内容的四个口径（`BookDocumentController.pageLines /
 * pageImages / pageBackgrounds`），与行窗同一坐标空间（章节绝对 Y）。
 */
interface LayoutReadback {
    /** 行窗（行下标 → 行），绘制与回读同源；null = 该布局不支持回读。 */
    fun skiaLineWindow(): Map<Int, DrawLine>?

    /** 表格单元格行（表行不产出行窗行，展开附在行下标上；纯表页只有它们）。 */
    fun tableCellLines(firstLine: Int, lastLineExclusive: Int): List<DrawLine>

    /** 与行窗同一切片口径的盒背景/边框（章节绝对 Y）。 */
    fun pageBackgrounds(firstLine: Int, lastLineExclusive: Int): List<PageBackground>

    /** 与行窗同一切片口径的 `<img>` 插图几何（章节绝对 Y）。 */
    fun pageImages(firstLine: Int, lastLineExclusive: Int): List<PageImage>
}
