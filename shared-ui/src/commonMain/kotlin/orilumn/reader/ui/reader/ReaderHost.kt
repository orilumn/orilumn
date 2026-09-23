package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.engine.LinkTarget
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 阅读面当前定位：章节下标 + 该章内的一页（[PageSlice] 是 common 的纯定位模型）。
 *
 * 三件套（chapter + slice 的 char/line 区间）足够宿主完成翻页/跳读/进度计算，与 Android 旧管线
 * `BookDocumentController.findAdjacentPage(startCh, s, dir)` 的入参同构。
 */
data class ReaderPos(val chapter: Int, val slice: PageSlice)

/**
 * S28 阅读面宿主抽象：shared-ui 只依赖本接口，把字节取章/分页/持久化等平台能力收敛到实现方
 * （Android 侧由 BookDocumentController + Room 实现，S31 接入；与 S27 的 [orilumn.reader.ui.shelf.ShelfRepository]
 * /[orilumn.reader.ui.shelf.ShelfHost] 同一分层口径）。
 *
 * 约定：
 *  - **同步**函数（title/unitTitle/pageCount/pageProgress/pageLines/onSaveProgress）必须是廉价、可立即
 *    返回的；重型运算（开书/翻页/跳读）为 **suspend**（内部可切 IO 线程），返回新定位或 null（到边界/失败）。
 *  - [pageLines] 返回当前页"可视窗口"的绘制指令：行集合为**章节绝对 Y**（见 LineWindowDrawer 分页/滚动
 *    共用绝对坐标的设计），[ReaderMath.shiftToPageFrame] 负责平移到本页坐标系；null/空 = 该页暂不可绘制。
 *  - [onSaveProgress] 由阅读面在翻页/跳转后 debounce 调用（保存定位），不要求立即写盘。
 */
interface ReaderHost {

    /** 书名（顶部栏）。 */
    fun title(): String

    /** 章节标题（顶部栏，chapter 见 [ReaderPos.chapter]）。 */
    fun unitTitle(chapter: Int): String

    /** 章节页数（可写"n/m"页标；暂未在 S28 展示，保留给 S29 页码开关）。 */
    fun pageCount(chapter: Int): Int

    /** 章节在目录中的扁平下标（目录当前项高亮；S28 未挂 TOC 面板，S29 使用）。 */
    fun tocIndex(chapter: Int): Int

    /** 打开书籍并定位起始页（自动续读/首页），失败返回 null。 */
    suspend fun open(): ReaderPos?

    /** 相邻页翻页：跨章并跳过空白短章；到边界返回 null。direction 语义同 [ReaderMath.flipDirection]。 */
    suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos?

    /** 章节切换（上/下一章）到相邻有内容章节的第一页；边界返回 null。 */
    suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos?

    /** 进度条拉动（0..1 等权 → 某章第一页）；失败返回 null。 */
    suspend fun pageAtFraction(fraction: Double): ReaderPos?

    /** 目录跳转到指定章的第一内容页（XHTML 下标）；失败返回 null。 */
    suspend fun chapterStart(index: Int): ReaderPos?

    /** 当前定位的整书比例 0..1（进度条/百分比）。 */
    fun pageProgress(pos: ReaderPos): Double

    /** 当前页可视窗口的行绘制指令（章节绝对 Y，见上），null/空 = 暂不可画。 */
    fun pageLines(pos: ReaderPos): List<DrawLine>?

    /**
     * P4-c2u: 章内字符处的 `<a href>` 目标（点按命中的查表口；同步廉价，默认 null = 宿主未实现，
     * 阅读面点按退回三区行为）。
     */
    fun linkTargetAt(chapter: Int, charOffset: Int): LinkTarget? = null

    /**
     * P4-c2u: 跟随链接目标（页内锚重锚 / 跨章跳转；失败回 null）。默认 null。
     */
    suspend fun openLink(target: LinkTarget): ReaderPos? = null

    /**
     * 当前页可视窗口的 `<img>` 插图几何（章节绝对 Y，与 pageLines 同一切片）。
     * 默认 null（宿主未实现时阅读面只画文本）；[loadPageImage] 同理。
     */
    fun pageImages(pos: ReaderPos): List<PageImage>? = null

    /** 按 [PageImage] 异步解码成位图（阅读面 produceState 按图缓存）；null = 跳过该图。 */
    suspend fun loadPageImage(img: PageImage): ImageBitmap? = null

    /**
     * 当前页可视窗口的盒背景/边框（章节绝对 Y，与 pageLines 同一切片同坐标系），
     * 阅读面画在文字之下。默认 null（无背景块时）。
     */
    fun pageBackgrounds(pos: ReaderPos): List<PageBackground>? = null

    /**
     * P3-b: 按章节相对 url 异步解码背景图（阅读面按 `PageBackground.bgKey()` 去重缓存；
     * null = 缺失/失败，該幅跳过只留底色）。默认 null（宿主未实现时无背景图）。
     */
    suspend fun loadBackgroundImage(chapterHref: String, src: String): DecodedImage? = null

    /** 定位已变化，保存阅读进度（防抖由阅读面负责）。 */
    fun onSaveProgress(pos: ReaderPos)
}