package orilumn.reader.engine.skia

/**
 * 章节插图绘制指令（与 [DrawLine] 同源：盒流几何 + 章节绝对 Y）。
 *
 * [DrawLine] 只承载文本行（[DrawLineBuilder] 跳过替换块）；`<img>` 替换叶走这里，
 * 由阅读面异步解码贴图（与书架封面同一模式），不进 Skia 行窗口、不阻塞主线程栅格化。
 *
 * @property src `<img src>`（相对路径，相对 [chapterHref] 解析，沿用 ImageLoader 口径）。
 * @property chapterHref 本章 spine href（ImageLoader 解析基准）。
 * @property xLeft 内容区内水平起点（px，与 DrawLine.xLeft 同口径：contentLeft + 自身左边缘）。
 * @property yTop/yBottom 行几何内的绝对 Y（与 DrawLine 同一坐标系，画布侧同 shift 平移）。
 * @property widthPx/heightPx 解码目标尺寸（盒流已经算好的 used 尺寸，宿主按此解码即贴）。
 */
data class PageImage(
    val src: String,
    val chapterHref: String,
    val xLeft: Int,
    val yTop: Int,
    val yBottom: Int,
    val widthPx: Int,
    val heightPx: Int,
)
