package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.data.epub.TocItem
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

    /**
     * **第 1 档「渲染」半边的抢占钩子**（`线程调度原则.md` §5「唯一钩子」）：
     * 页位图未命中、即将真正栅格化时由渲染器调用一次，宿主转接排版层的
     * `cancelLowerThan(PRIO_FLIP)`，让第 7 档后台预排让路。
     *
     * 栅格与翻页同属总则第 1 档（"立即同步排版**渲染**"），此前只有翻页/导航接了钩子，
     * 栅格这半边漏接 ⇒ 第 7 档整章全量能把绘制线程压住。默认实现 = 空实现（不抢占）。
     */
    fun onRasterMiss() {}

    /**
     * **预排出版式、像素尚缺**时回调一次（`线程调度原则.md` §3 阶梯要补的那一档）。
     *
     * 阶梯第 2/3/4 档全是**排版**（产出 `pageLines`），栅格一直不在体系内，于是像素只在
     * 页面被绘制时才生产 —— 而单页栅格实测 p50 143ms。用户手指按下后要先等这张位图。
     *
     * 预排出的邻页**用户下一步就要用**（§0 紧急度原则），所以像素该在预排完成那一刻就
     * 跟上。引擎跑在后台线程、**只抛信号**；执行在用户层的 UI 线程（`preraster` 与
     * `drawLines` 共用离屏 surface，不能跨线程）。
     *
     * 翻页只需准备**一页**（方向侧那一张），不是把窗口全截一遍。
     *
     * @param chapter 已出版式的那一章。
     * @param direction 上次翻页方向（`1` 前 / `-1` 后 / `0` 无记录，语义见 §3.2）。
     */
    fun onPrefillReady(chapter: Int, direction: Int) {}

    /**
     * 订阅「预排出邻页版式」事件（引擎 → 用户层的栅格信号通道）。
     *
     * 引擎跑在后台线程且不能碰 skia 画布，故只抛信号、在此注册执行侧；回调在
     * **UI 线程**的调用方协程上跑（`preraster` 与 `drawLines` 共用离屏 surface，
     * 跨线程会出事，见 [ReaderPageRenderer.preraster]）。
     *
     * 订阅随设置/视口变化重建（见 `Preraster.kt` 的 `LaunchedEffect` 键），
     * 重复订阅由宿主覆盖而非累加。
     */
    fun observePrefillReady(handler: suspend (chapter: Int, direction: Int) -> Unit) {}

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

    /**
     * 按 [PageImage] 异步解码成位图（阅读面按图缓存）；null = 解码失败。
     *
     * P0a：**返回 skia [DecodedImage] 而非 Compose `ImageBitmap`** —— 与 [loadBackgroundImage]
     * 统一成「skia 解码结果」单一类型。理由是插图已下沉进页位图（整页一张纹理），
     * 下游要的是能直接进 skia 画布的东西；Compose 位图在 Android 上还得再桥回 skia，
     * 白绕一圈（原先正文图在 Compose 层直画时不需要这一步）。
     */
    suspend fun loadPageImage(img: PageImage): DecodedImage? = null

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

    /**
     * 全书封面图（阅读器封面页用；无封面/失败回 null）。默认 null。
     * 封面页是用户层前置页，不进分页/存档（只读不存）。
     */
    suspend fun coverImage(): ImageBitmap? = null

    /**
     * 全书第一内容页（封面页的后一页判定 + 前进落位用；失败回 null）。
     *
     * **导航**（不是只读查询）：`openChapterStart(0)` 逐章排版、落位
     * 首内容章并 `evictFarChapters` 逐出 ±30 窗外的远章。旧 KDoc
     * 误写「只读查询，不碰临时表」——封面 effect 据此调用，续读位在
     * 深处时把正在渲染的远章逐出 → 白屏（见 [firstContentChapter] 与
     * FirstContentChapterProbeTest）。只用于真正需要落位的路径
     * （如封面页内前进翻页）；判定书首请用无副作用的 [firstContentChapter]。
     */
    suspend fun bookStart(): ReaderPos? = null

    /**
     * 全书第一个含正文章的下标（**纯查询，懒解析**：无落位、无排版、
     * 无逐出；仅 markup 结构解析。缓存未命中时从章 0 起找首个含正文
     * 章——在宿主后台线程执行）。封面层判定「当前是否书首」用——
     * 严禁用 [bookStart] 替代（那是导航，续读位在书首 ±30 章之外
     * 时会逐出正在渲染的远章 → 白屏）。
     */
    suspend fun firstContentChapter(): Int?
}

/**
 * 目录扁平化（Q1-2 收敛：原平板 `TabletReaderHost.flatToc` 与桌面
 * `DesktopReaderHost.flatten` 同义实现，合入 shared-ui）。
 * 焦点下标即扁平序，调用方用下标对章。
 */
fun flattenTocItems(toc: List<TocItem>): List<TocItem> {
    val out = ArrayList<TocItem>()
    fun walk(items: List<TocItem>) {
        for (t in items) {
            out.add(t)
            walk(t.children)
        }
    }
    walk(toc)
    return out
}