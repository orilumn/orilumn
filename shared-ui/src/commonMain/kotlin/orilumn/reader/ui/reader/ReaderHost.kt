package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.bookmark.Bookmark
import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.LinkTarget
import orilumn.reader.engine.paging.BookPageNumber
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.ReviewOverlay
import orilumn.reader.engine.revision.TableRowScope
import orilumn.reader.engine.revision.TextEditBlock
import orilumn.reader.engine.revision.TextEditPart
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 阅读面当前定位：章节下标 + 该章内的一页（[PageSlice] 是 common 的纯定位模型）。
 *
 * 三件套（chapter + slice 的 char/line 区间）足够宿主完成翻页/跳读/进度计算，与 Android 旧管线
 * `BookDocumentController.findAdjacentPage(startCh, s, dir)` 的入参同构。
 *
 * [atCover] 是定位链上的「封面位」（用户层前置页，不在排版层页表里）：封面盖在全书
 * 第一内容页上时 openPos 仍指向那页，单看 (chapter, charStart) 无法把「停在封面」
 * 与「停在正文首页」分开 —— 存档读回后易重弹封面（反复出现：停在正文首页退出、重开在封面）。
 * 故把封面态挂进定位本身（仅用于存档/续读的状态区分），页面链与排版层页表不受影响。
 */
data class ReaderPos(val chapter: Int, val slice: PageSlice, val atCover: Boolean = false)

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
     * 翻页只需准备**一页**（方向侧那一张），不是把窗口全截一遍：窗口 {反侧, 当前, 方向侧}
     * 里的反侧就是上一刻的当前页、必然还在池中，当前页由绘制同步栅格（见 `Preraster.kt`）。
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

    /**
     * [adjacent] 的**只读**版本：不推进引擎指针，只回答「那一页是哪个页」。
     *
     * ## 为什么必须有这个（预栅格不能调 [adjacent]）
     *
     * `ReaderHost.adjacent` 不是查询而是**有副作用的真导航**——引擎侧 `tempNav` 会
     * `ip.curIndex = next`，真把临时表指针挪到下一页。故预栅格（`Preraster.kt`）调它
     * 会在**没有用户手势**的情况下把指针多推一格。
     *
     * 真机症状（19:56:25）：预排在落位之后仍持续出版式并触发预栅格，每次预栅格都
     * 调 `adjacent(anchor, dir)` ⇒ 指针被多推 ⇒「翻页完成后又跳了一页」；同时预栅格
     * 算出的是另一页（`raster n=36` vs `preraster n=37`），既没备好用户要的那页，
     * 又把目标页白栅一次。
     *
     * 预栅格是投机活，**绝不能改数据**（§0：它只为「让将来的目标页变成命中」）。
     */
    suspend fun peekAdjacent(pos: ReaderPos, direction: Int): ReaderPos? = null

    /** 章节切换（上/下一章）到相邻有内容章节的第一页；边界返回 null。 */
    suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos?

    /** 进度条拉动（0..1 等权 → 某章第一页）；失败返回 null。 */
    suspend fun pageAtFraction(fraction: Double): ReaderPos?

    /** 目录跳转到指定章的第一内容页（XHTML 下标）；失败返回 null。 */
    suspend fun chapterStart(index: Int): ReaderPos?

    /** 当前定位的整书比例 0..1（进度条/百分比）。 */
    fun pageProgress(pos: ReaderPos): Double

    /**
     * 进度条某个位置（0..1）对应的全书页码（1 起）；页数不全返回 null（底栏退回百分比）。
     *
     * 与 [pageAtFraction] 的按页落位**同一换算**：拖动时标签上写的页，就是松手后落到的页。
     * **同步、廉价**（引擎侧是 O(章数) 加和），拖动中每帧调用。
     *
     * 默认 null = 宿主尚未实现（桌面等壳按可缺省处理），底栏就一直显示百分比。
     */
    fun bookPageNumberAtFraction(fraction: Double): Int? = null

    /**
     * 全书累计页码（用户层「显示页码」开关的消费者）：本页在全书中的页码（1 起）+ 全书总页数。
     *
     * **同步、廉价**（引擎侧是 O(章数) 加和）：阅读面在组合期直接调。null = 这一页暂时给不出
     * 页码（本章还没有当前参数下的正典分页表 / 全书章页数还没补齐），此时页脚留白。
     *
     * 默认 null = 宿主尚未实现（桌面等壳按可缺省处理），阅读面就当没有页码。
     */
    fun bookPageNumber(pos: ReaderPos): BookPageNumber? = null

    /**
     * 各章首页在全书的页码（章号下标 → 1 起的页码；0 = 暂不可算），目录面板用。
     *
     * **同步、廉价**；与 [bookPageNumber] 同一份快照，同一口径：能算的都给，遇到第一个未知章就停
     * （后面的章依赖它，不估）。换版式参数后整份作废，这里自然从空表重新长出来。
     *
     * 默认空数组 = 宿主尚未实现（目录不显示页码）。
     */
    fun chapterStartPages(): IntArray = IntArray(0)

    /**
     * 全书页码数据的代际。宿主用 **Compose 快照状态**实现它，阅读面在组合期读——后台把各章页数
     * 补齐（或本章正典表刚落）时它一变，页码才能在**读者静置不翻页**时也自己出现/更新。
     *
     * 默认 0 = 不变：页码只在别的由头触发重组时重取（不实现也不算错，只是更新慢一拍）。
     */
    fun pageNumberRevision(): Int = 0

    /** 当前页可视窗口的行绘制指令（章节绝对 Y，见上），null/空 = 暂不可画。 */
    fun pageLines(pos: ReaderPos): List<DrawLine>?

    /**
     * 整章纯文本的切片（D-18）：`[start, endExclusive)`，坐标与 [pageLines] / 标注区间同尺。
     *
     * **同步、廉价**：开章时已随排版缓存；未就绪（未开章 / 远章已驱逐）返回 null，
     * 空串是合法的「本章无可排文字」。复制、选词、长按断句都读这一口，
     * 不要在 `shared-ui` 里拼 `DrawLine.text`（那只是视口内的行，跨页/跨行会缺字）。
     *
     * 默认 null = 宿主尚未实现，阅读面按「暂不可选」处理。
     */
    fun textOf(chapter: Int, start: Int, endExclusive: Int): String? = null

    /**
     * 与 [textOf] 同尺的块尾偏移（每个排版叶一段，累积 exclusive）：长按选区在本块内断句/断词，
     * 不跨标题、列表项、段落。未就绪返回 null，选区退回「整章扫描」（旧行为）。
     */
    fun textBlockEndsOf(chapter: Int): IntArray? = null

    /**
     * 审阅视图里**痕迹字符**在 [textOf] 同尺空间里的字符偏移（升序；没有则空表）。
     *
     * 痕迹是审阅空间专有的排版字符，不是书里的字，有两类：
     * - 段界痕迹 ¶（被删掉的段界 / 新插的段界）；
     * - 格式痕迹（`## ` / `**` …：字一个没改、只改了标记时，把被删 / 新写的标记排出来）。
     *
     * 取文字给读者看（修改卡初值、复制、摘录）时要跳过这些位。
     */
    fun reviewBreakOffsets(chapter: Int): IntArray = IntArray(0)

    /**
     * 本章「中西接缝上被画成零宽的空白字位」（升序，与 [textOf] 同尺）。
     *
     * 这些字**在文本模型里还在**：排版把它们画成 0 宽、由混排字距取代，标注、进度、选区下标
     * 照常按含空白的原文算（用户口径：原文一个字都不动，只在显示与选区里处理）。
     * 所以凡是「读者要看 / 要带走」的文字（复制、摘录）都得按本表把它们摘掉。
     */
    fun seamSpaceOffsets(chapter: Int): IntArray = IntArray(0)

    /**
     * 本章全部标注（D-19）：按起点、id 升序；读取失败返回空表。
     *
     * `shared-ui` 进章时读一次，之后增删改先改内存并递增装饰修订号，再异步落库；
     * 投影/长按命中/覆盖层都读内存列表，**不每帧查库**。
     */
    suspend fun annotationsForChapter(chapter: Int): List<Annotation> = emptyList()

    /**
     * 全书标注（标注清单用）：按章序、章内起点升序；读取失败返回空表。
     *
     * 清单只读库，不要求任何章的排版缓存就绪（原文摘录已随记录存快照，见 D-24）。
     * 默认空表 = 宿主尚未实现，面板走空态，不崩。
     */
    suspend fun annotationsForBook(): List<Annotation> = emptyList()

    /** 新增标注并返回带数据库 id 的记录；失败返回 null。实现方负责补 `bookId` 与时间戳。 */
    suspend fun addAnnotation(annotation: Annotation): Annotation? = null

    /** 更新标注类型/颜色/批注文本（锚点不变）；失败返回 false。 */
    suspend fun updateAnnotation(annotation: Annotation): Boolean = false

    /** 按 id 删除标注；失败返回 false。 */
    suspend fun deleteAnnotation(id: Long): Boolean = false

    /**
     * 全书正文修订（修订清单用）：按章序、章内起点升序。**读取失败返回 null**（不是空表）。
     *
     * 和 [annotationsForBook] 一样只读库，不要求任何章就绪。坐标是原书空间，
     * 跳转时由宿主换算成渲染空间再落位（与标注清单同一口径）。
     *
     * 失败与「真没有修改」必须分开：修订清单要按这个区分显示「读取失败，点此重试」还是空态。
     */
    suspend fun textRevisionsForBook(): List<TextRevision>? = null

    // ---- 书签 ----

    /**
     * 全书书签（书签清单用）：按章序、章内页首升序。**读取失败返回 null**（不是空表）。
     *
     * 只读库，不要求任何章就绪——摘录已随记录存快照。锚点是**原书空间**的页首字符，
     * 跳转时由宿主换算成渲染空间（与标注清单同一口径）。
     */
    suspend fun bookmarksForBook(): List<Bookmark>? = null

    /**
     * 新增一条书签（[bookmark] 的 bookId 由实现方补，锚点已是原书空间）。
     *
     * 同一页已有书签时返回已有那条，不重复插入。失败返回 null。
     * 从当前页取锚点请用 [addBookmarkAt]。
     */
    suspend fun addBookmark(bookmark: Bookmark): Bookmark? = null

    /** 按 id 删除书签；失败返回 false。 */
    suspend fun deleteBookmark(id: Long): Boolean = false

    /**
     * 本章正文修订，**渲染坐标**（与选区 / `textOf` / `PageSlice` 同尺）：按起点升序。
     *
     * 阅读面判「这段字改过没有 / 删除该删哪条」要用它——[textRevisionsForBook] 是原书坐标，
     * 只适合清单与跳转。默认空表 = 宿主尚未实现，正文修改卡的「删除」保持虚掉。
     */
    suspend fun textRevisionsForChapter(chapter: Int): List<TextRevision> = emptyList()

    /**
     * 删除一条正文修订（修订清单行左滑删除）：宿主落库 + 重读修订 + 只作废该章。
     *
     * [currentPage] 是阅读面**正在显示的那一页**（调用方传自己手里的 `openPos` / `currentPos`）。
     * 宿主只能用它判断「删的是不是当前章」并取改前页首——不能拿开书锚点（`locateStart`）顶替：
     * 读者翻走以后开书锚点仍停在旧章，会把当前章的锚点判成 null，于是只作废不重排，
     * 眼前这一章既没版式也没人补排，整页空白。
     *
     * [TextRevisionDeleteOutcome.Success.pos] 非空表示删的是**当前阅读章**：该章版式已作废，
     * 阅读面必须按这个新落位重新取页（否则页面空白）；null 表示删的是别的章，当前页不动。
     * 默认失败 = 宿主尚未支持。
     */
    suspend fun deleteTextRevision(
        revision: TextRevision,
        currentPage: ReaderPos? = null,
    ): TextRevisionDeleteOutcome = TextRevisionDeleteOutcome.Failed

    /**
     * 修订清单的「全部恢复原文」：把全书修订一次删光，返回结果（口径同 [deleteTextRevision]）。
     *
     * 删的是当前阅读章时，[TextRevisionDeleteOutcome.Success.pos] 给出按「删除前页首」重新落位的
     * 新位置；别的章只作废版式，当前页不动（那时 pos 为 null）。
     * 默认失败 = 宿主尚未支持。
     */
    suspend fun restoreAllTextRevisions(currentPage: ReaderPos? = null): TextRevisionDeleteOutcome =
        TextRevisionDeleteOutcome.Failed

    /**
     * 审阅视图开关：打开后本章按「原文全保留 + 新文字插在后面」重排，改动的痕迹看得见
     * （删掉的字变红划红线、新添的字变绿）。返回 true 表示真的变了，调用方必须重排并重新落位。
     *
     * 审阅是排版前的另一份预处理结果：有修订的章照常重排、照常落分页表与页数（页码照常算），
     * 没有修订的章两种视图文字相同、不必重排。默认 false = 宿主尚未实现。
     */
    fun setReviewMode(on: Boolean): Boolean = false

    /** 当前是否处于审阅视图（栏上「审阅」按钮的高亮状态）。 */
    fun isReviewMode(): Boolean = false

    /**
     * 本章在审阅视图里的改动痕迹（审阅空间、章内偏移，按起点升序）。
     *
     * 只有审阅视图打开、且本章有修订时才非空；正常视图返回空表。
     */
    fun reviewMarks(chapter: Int): List<ReviewMark> = emptyList()

    /**
     * 本页**页首缺口**：页首行行顶之下到本页绘制帧顶的距离（px，≥0，见
     * [orilumn.reader.engine.paging.BookLayout.getPageFrameTop]）。
     *
     * 分页预算把页首元素的 `margin-top` 与页首块的 `border/padding` 记在**本页**帧内，绘制侧
     * 就必须把整页从**帧顶**（= 首行行顶 − 本值）对齐，那一段间距才会画在页顶而不是漏在页底：
     * 否则页首块的装饰在栅格上沿被裁、且预留的缺口留成页底空白（「明明有空间，最后一行却被推走」）。
     *
     * **坐标无关**：返回值是相对首行行顶的差值，重路径（章节绝对 Y）与临时页（页内归一化 Y）
     * 同值——阅读面只需 `anchorY = min(行, 图) - lead`。
     *
     * 默认 0 = 旧口径（锚点即行/图最小者）；宿主未实现时不影响既有像素。
     */
    fun pageFrameLead(pos: ReaderPos): Int = 0

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

    // ---- 正文修改 v3（方案 §8.8） ----

    /**
     * 提交一次正文修改：引擎算修订 + 宿主落库 + 该章重排。返回新落位与替换后的选区。
     *
     * [start] / [endExclusive] 是**渲染空间**（与 [textOf] / 选区同尺）；[text] 是输入区文字，
     * 跨段时用 `\n` 分隔，**结果段数 = 输入里的换行符数 + 1**（删光中间的字就把几段并成一段）。
     * [currentPage] 同 [deleteTextRevision]：阅读面正在显示的那一页，宿主据此取改前页首；
     * null 时退回开书锚点（兼容旧调用方）。
     * 默认失败 = 宿主尚未支持，阅读面就地提示。
     */
    suspend fun commitTextEdit(
        chapter: Int,
        start: Int,
        endExclusive: Int,
        text: String,
        styles: List<InlineMarkup.StyleSpan>? = null,
        currentPage: ReaderPos? = null,
        blocks: List<InlineMarkup.Segment>? = null,
        rowScope: TableRowScope? = null,
    ): TextEditOutcome = TextEditOutcome.Failed("当前版本不支持正文修改")

    /**
     * 一次提交里有多段（审阅视图里表格和别的文字一起选中）：逐段规划、**一次落库、一次重排**。
     *
     * 各段区间互不重叠，坐标同为当前显示空间（见 [TextEditPart]）；任何一段被拒就整单拒。
     * 默认实现逐段调 [commitTextEdit]（老宿主 / 测试桩），真宿主覆盖成单事务。
     */
    suspend fun commitTextEdits(
        chapter: Int,
        parts: List<TextEditPart>,
        currentPage: ReaderPos? = null,
    ): TextEditOutcome {
        var last: TextEditOutcome = TextEditOutcome.Failed("请先选择要修改的文字")
        for (part in parts) {
            last = commitTextEdit(
                chapter,
                part.start,
                part.endExclusive,
                part.text,
                part.styles,
                currentPage,
                part.blocks,
                part.rowScope,
            )
            if (last !is TextEditOutcome.Success) return last
        }
        return last
    }

    /**
     * 审阅视图里「读者拖选的是整表修订中的哪几行」：卡片据此只列那几行
     * （见 [TableRowScope]）；null = 不是这种选法，调用方按整表卡片打开。
     *
     * 默认 null = 宿主没实现（老宿主 / 非盒式排版）。
     */
    fun tableRowScope(chapter: Int, revisionId: Long, start: Int, endExclusive: Int): TableRowScope? = null

    /**
     * 选区上的行内结构（渲染空间，**相对选区起点**）：正文修改卡用它回显标记文字
     * （`*斜体*`、`` `代码` ``、`[文字](地址)`、`<span class="x">`）。
     *
     * 默认 null = 宿主没实现（或这一处没有显式结构）：卡片按纯文本显示，行为与引入标记之前一致。
     * 提交时同一份结构用于补回元素属性（`class` / `id` 之类读者没写的部分）。
     */
    fun textEditSpans(chapter: Int, start: Int, endExclusive: Int): List<InlineMarkup.StyleSpan>? = null

    /**
     * 一条修订**原来**盖着的行内结构（原书空间，**相对 [revisionId] 那条修订的区间起点**）。
     *
     * 正文修改卡重开旧修订时用它区分「读者这一次新写的标记」与「书本来就有的元素」：
     * 新写的整段覆盖要摊开显示、本来就有的整段覆盖保持隐形（见 `splitAmbientSpans`），
     * 与审阅视图的格式痕迹同一份来源（`ChapterRevisionMap.originalSpans`），两处不会各说各话。
     *
     * 默认 null = 宿主没实现 / 这一条取不到原书投影：卡片按纯文本开卡，不猜。
     */
    fun textRevisionOriginalSpans(chapter: Int, revisionId: Long): List<InlineMarkup.StyleSpan>? = null

    /**
     * 一条修订在**审阅空间**里的样子（修订清单的行摘要用）：文字 + 红 / 绿区间（局部偏移）。
     *
     * 清单的一行要画得与正文审阅视图**完全一致**（绿 `**`、划掉的红 `## `、¶ 都在），所以两边
     * 取同一份数据（`ChapterRevisionMap.reviewExcerpt`），清单不另算一套差异：只改了标记的修订
     * 两端文字相同，按快照做字符差异一条颜色都出不来，行里就只剩普通文字。
     *
     * 默认 null = 宿主没实现 / 这一条取不到（本章结构未载入、老数据没有原文投影、快照对不上）：
     * 清单退回「原文 / 改文」快照做字符级对齐，行为与引入这一口之前一致。
     */
    fun textRevisionReviewExcerpt(chapter: Int, revisionId: Long): ReviewOverlay.Excerpt? = null

    /**
     * 选区涉及的各**块**（渲染空间 [start, endExclusive)）：正文修改卡按它给每一行加块标记
     * （`# ` / `> ` / `- ` / `1. ` / 围栏），读者也靠删改标记来换块形态。
     *
     * 顺序与 [commitTextEdit] 的 `\n` 分段一一对应；第一项带「选区起点是否在块首」。
     * 默认 null = 宿主没实现（或这一处拿不到排版叶）：卡片不显示块标记，行为与引入块标记之前一致。
     */
    fun textEditBlocks(chapter: Int, start: Int, endExclusive: Int): List<TextEditBlock>? = null

    /**
     * 渲染区间 → 原书区间（建标注 / 存进度用）。无修订时原样返回；默认 null = 宿主未实现，
     * 调用方按「同一坐标」处理。
     */
    fun originalRangeOf(chapter: Int, start: Int, endExclusive: Int): IntRange? = null

    /**
     * 原书区间 → 渲染区间（画标注 / 标注清单跳转用）。无修订时原样返回；默认 null = 宿主未实现。
     */
    fun renderedRangeOf(chapter: Int, start: Int, endExclusive: Int): IntRange? = null
}

/**
 * 一次正文修改的结果（方案 §8.8）。
 *
 * [Success.pos] 为 null 表示新文字与旧文字相同（无需重排，保持当前页）；
 * [Success.selStart] / [selEnd] 是替换后文字在**渲染空间**的区间，供调用方需要时定位改后的文字
 * （阅读面确认后不再据此保留选区）。
 */
sealed interface TextEditOutcome {
    data class Success(
        val pos: ReaderPos?,
        val selStart: Int,
        val selEnd: Int,
    ) : TextEditOutcome

    /** 校验失败（选区跨到图片 / 表格等不能合并的块）：卡片不关、正文不动。 */
    data class Rejected(val reason: String) : TextEditOutcome

    /** 落库 / 重排失败：卡片不关、正文不动。 */
    data class Failed(val message: String) : TextEditOutcome
}

/**
 * 删除一条正文修订的结果（修订清单行左滑删除）。
 *
 * [Success.pos] 非空 = 删的是当前阅读章，阅读面落到这个新位置；null = 删的是别的章，当前页不动。
 */
sealed interface TextRevisionDeleteOutcome {
    data class Success(val pos: ReaderPos?) : TextRevisionDeleteOutcome
    data object Failed : TextRevisionDeleteOutcome
}

/**
 * 审阅视图里的一处改动痕迹（章内偏移，**审阅空间**）。
 *
 * @property deleted true = 被删掉的原文（红字 + 红色删除线）；false = 新增的文字（绿字）。
 */
data class ReviewMark(
    val start: Int,
    val endExclusive: Int,
    val deleted: Boolean,
)

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