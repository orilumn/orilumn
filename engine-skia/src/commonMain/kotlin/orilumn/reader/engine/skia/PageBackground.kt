package orilumn.reader.engine.skia

/**
 * 一页可视窗口内的盒背景/边框绘制指令（与 [DrawLine]/[PageImage] 同一章节绝对 Y 坐标系，
 * 阅读面做同一平移；顺序即绘制顺序，祖先后代子——父背景永远在子内容之下）。
 *
 * 由盒流 [orilumn.reader.engine.laying.BoxDrawer.drawOnPage] 按页切片产出：跨页撕裂的块已按页
 * 可视带裁剪，推挤到他页的块不再出现。边框已展开成细带矩形，与背景同一填充路径绘制。
 *
 * @property left/right 内容区内水平带（px，border-box 左右缘，与 DrawLine.xLeft 同口径）。
 * @property yTop/yBottom 绝对 Y 带（与 DrawLine 同一坐标系，画布侧同 shift 平移）。
 * @property argb 填充色（packed ARGB Int，hex 解析失败的已在构建侧丢弃）。
 * @property border true = 边框带，false = 背景填充（当前同为填充绘制，保留区分供调试）。
 * @property radii 圆角（px，全零即方形旧路径）。
 * @property alpha 祖先链 opacity 连乘（1 即旧路径）。
 * @property shadow 盒阴影（颜色已解；null 即无）。
 * @property strokeWidthPx 边框环描边宽（>0 即均匀边框圆角环描边；0 即填充带）。
 */
data class PageBackground(
    val left: Int,
    val yTop: Int,
    val yBottom: Int,
    val right: Int,
    val argb: Int,
    val border: Boolean = false,
    val radii: orilumn.reader.engine.css.CornerRadius = orilumn.reader.engine.css.CornerRadius(),
    val alpha: Float = 1f,
    val shadow: orilumn.reader.engine.css.BoxShadow? = null,
    val strokeWidthPx: Float = 0f,
    /** P3-b: 背景图（null＝无图旧路径；`bgSrc` 为章节相对 url，`bgChapterHref` 为解码基准）。 */
    val bgSrc: String? = null,
    val bgChapterHref: String = "",
    val bgRepeat: orilumn.reader.engine.css.BackgroundRepeat = orilumn.reader.engine.css.BackgroundRepeat.REPEAT,
    val bgPosition: orilumn.reader.engine.css.BackgroundPosition = orilumn.reader.engine.css.BackgroundPosition(),
    /** P3-b: 平铺锚盒（未裁剪的盒上下缘；跨页撕裂仍以整盒原点平铺）。 */
    val bgBoxTop: Int = 0,
    val bgBoxBottom: Int = 0,
) {
    /** 背景图解码键（`chapterHref|src`，与宿主图片缓存同口径）。 */
    fun bgKey(): String? = bgSrc?.takeIf { it.isNotBlank() }?.let { "$bgChapterHref|$it" }
}
