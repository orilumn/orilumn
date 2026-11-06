package orilumn.reader.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.io.Logger

/**
 * **当前页快照**：把"这一页排好之后的绘制结果"整份存到盘上，下次开书直接拿来画，
 * 不必再塑形、再拼行流。
 *
 * 与分页表（[ChapterPaginationTable]）的分工：
 *  - 分页表回答"**页在哪**"（每页从哪个字到哪个字、覆盖哪些块）——纯几何、极小、早就落盘；
 *  - 本快照回答"**这一页长什么样**"（每行画什么字、画在哪、什么字号/颜色/装饰、盒子背景、
 *    插图槽位）——即 [DrawLine] / [PageBackground] / [PageImage] 这份绘制模型。
 *
 * 只存**一页**、只存**一本一份**（[PageSnapshotStore.fileOf]）：读者翻页就覆盖，不留历史。
 * 一份快照 = 一页的行（通常 20–40 条）+ 行几何 + 本页的盒/图，几十 KB 量级。
 *
 * ## 为什么可以这么存
 *
 * 一本书关闭后，它的分页结果只会被四件事改变：排版参数（字号/行距/边距/字体/主题…）、
 * 版心尺寸、引擎版本、**字库环境**（装着的字体文件本身变了）。四者全在键里
 * （[paramHash] + [contentWidth]/[contentHeight] + [layoutVersion]/[appVersion] + [fontSig]）；
 * 书的内容本身不会变（bookId 换书即换 id）。任一不符即视为 miss（[matches]），
 * 退回正常排版路径——**绝不拿旧快照去画新参数**。
 *
 * ## 为什么是"绘制模型"而不是"位图"
 *
 * 位图只能看：长按选区、点按链接、行内图点击都要行文本与章内字符区间，位图给不出。
 * [DrawLine] 自带 `text` / `range` / `charBase`，快照因此同时是"能画"和"能选"的。
 */
@Serializable
data class PageSnapshot(
    /** 快照自身的字节布局版本（字段增删改语义时递增）。 */
    val schema: Int = SCHEMA,
    /** 产生这份几何的引擎版本（[PaginationCacheCodec.LAYOUT_VERSION]，源码指纹）。 */
    val layoutVersion: Int = 0,
    /** 产生这份几何的构建号（与分页表同口径，升级即整体作废）。 */
    val appVersion: Int = 0,
    val bookId: Long = -1L,
    val chapterIndex: Int = -1,
    /** 章内页号（0 起）。 */
    val pageIndex: Int = -1,
    val paramHash: Long = 0L,
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
    /** 本章作者 CSS 的哈希（书没换、样式没换才可能命中）。 */
    val cssHash: Long = 0L,
    /**
     * 产生这一页时**字库池的环境签名**（[orilumn.reader.engine.skia.FontPoolSync] 的 `sig`：
     * 本章需求命中的每个面的 `id:文件长度` + 书内字体 `族:字节数`）。
     *
     * **为什么必须进键**：行几何（每个字画在哪、够不够宽换行）是**具体字形度量**的产物。
     * 读者删掉一个导入字体、或读另一本书时本书内字体条目被逐出池，族名不变但实际取到的面
     * 变了——版式参数键（[paramHash]）里只有族名，认不出这种变化。凭旧快照画新字库
     * 就是"按旧字形算宽度、拿新字形去画"：溢出、错位、断行对不上。空串 = 宿主没提供
     * 签名（测试/未接线的宿主），此时不做这项校验。
     */
    val fontSig: String = "",

    // ---- 页切片（`PageSlice` 的持久化形状；与分页表同源）----
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val blockStart: Int = -1,
    val blockEndExclusive: Int = -1,
    val firstLine: Int = 0,
    val lastLineExclusive: Int = 0,

    // ---- 行几何：窗口内每一行一条（下标即窗口行序，与 `skiaLines` 的键同一坐标空间）----
    val lineTop: List<Int> = emptyList(),
    val lineBottom: List<Int> = emptyList(),
    val lineStart: List<Int> = emptyList(),
    val lineEnd: List<Int> = emptyList(),
    val lineParagraph: List<Boolean> = emptyList(),
    /** [orilumn.reader.engine.paging.BookLayout.getPageFrameTop] 的取值（按行预存，不再递归求）。 */
    val frameTop: List<Int> = emptyList(),
    /** [orilumn.reader.engine.paging.BookLayout.getPageFrameBottom] 的取值。 */
    val frameBottom: List<Int> = emptyList(),
    /** [orilumn.reader.engine.paging.BookLayout.getPageTopLead] 的取值。 */
    val topLead: List<Int> = emptyList(),

    // ---- 绘制模型 ----
    /** 与 [lines] 一一对应的窗口行下标（`DrawLine` 本身不带行号）。 */
    val lineIndex: List<Int> = emptyList(),
    /** 本页的文本行（[orilumn.reader.engine.skia.LayoutReadback.skiaLineWindow] 的切片）。 */
    val lines: List<DrawLine> = emptyList(),
    /** 表格单元格行（表行不产出行窗行，展开附在行下标上）。 */
    val tableCells: List<DrawLine> = emptyList(),
    /** 盒背景/边框（与 [lines] 同一章节绝对 Y）。 */
    val backgrounds: List<PageBackground> = emptyList(),
    /** `<img>` 插图几何。 */
    val images: List<PageImage> = emptyList(),
) {
    /**
     * 键校验：任何一项不符都视为 miss（调用方退回正常排版并覆盖本文件）。
     * 这里**只比键**，不试图"修补"——修补的一半对一半错比整体重排更贵也更难查。
     */
    fun matches(
        layoutVersion: Int,
        appVersion: Int,
        bookId: Long,
        chapterIndex: Int,
        pageIndex: Int,
        paramHash: Long,
        contentWidth: Int,
        contentHeight: Int,
        cssHash: Long,
        fontSig: String = "",
    ): Boolean =
        schema == SCHEMA &&
            this.layoutVersion == layoutVersion &&
            this.appVersion == appVersion &&
            this.bookId == bookId &&
            this.chapterIndex == chapterIndex &&
            this.pageIndex == pageIndex &&
            this.paramHash == paramHash &&
            this.contentWidth == contentWidth &&
            this.contentHeight == contentHeight &&
            this.cssHash == cssHash &&
            // 任一侧没提供签名（未接线/测试）就不做这项校验，绝不因缺签名而放过已知变化。
            (fontSig.isEmpty() || this.fontSig == fontSig) &&
            firstLine >= 0 &&
            lastLineExclusive > firstLine &&
            lineTop.size >= lastLineExclusive

    companion object {
        /** 字节布局版本：字段形状变化时递增（与 [PaginationCacheCodec.LAYOUT_VERSION] 各管一头）。 */
        const val SCHEMA = 2 // 2: 键里加了字库环境签名（fontSig）
    }
}

/**
 * 从一个**已经绑定的绘制布局**里抓出一页的快照（= 落盘侧唯一的取数口径）。
 *
 * 取数全部走公开回读缝（[orilumn.reader.engine.skia.LayoutReadback] 的四个方法与
 * [orilumn.reader.engine.paging.BookLayout] 的行几何），不碰塑形内部对象——因此这份快照
 * 与"重排一次得到同一页"在绘制上是同一份数据，也才能在测试里对着真实排版逐项比对。
 *
 * 行窗里的**空行是合法的**（整行都是插图/表格的占位行没有文本指令，真排版侧的
 * `pageLines` 也是直接跳过它们），所以这里按行下标挑有绘制指令的那几条存，不要求齐全——
 * 否则只含插图的页会永远存不下来。
 */
fun buildPageSnapshot(
    layout: orilumn.reader.engine.skia.PagedLayout,
    slice: PageSlice,
    pageIndex: Int,
    bookId: Long,
    chapterIndex: Int,
    paramHash: Long,
    appVersion: Int,
    contentWidth: Int,
    contentHeight: Int,
    cssHash: Long,
    fontSig: String = "",
): PageSnapshot? {
    if (slice.firstLine < 0 || slice.lastLineExclusive <= slice.firstLine) return null
    val window = layout.skiaLineWindow() ?: return null
    val n = layout.lineCount
    if (n <= 0 || slice.lastLineExclusive > n) return null

    val tops = IntArray(n)
    val bottoms = IntArray(n)
    val starts = IntArray(n)
    val ends = IntArray(n)
    val para = BooleanArray(n)
    val framesTop = IntArray(n)
    val framesBottom = IntArray(n)
    val leads = IntArray(n)
    for (i in 0 until n) {
        tops[i] = layout.getLineTop(i)
        bottoms[i] = layout.getLineBottom(i)
        starts[i] = layout.getLineStart(i)
        ends[i] = layout.getLineEnd(i)
        para[i] = layout.isParagraphBoundaryLine(i)
        framesTop[i] = layout.getPageFrameTop(i)
        framesBottom[i] = layout.getPageFrameBottom(i)
        leads[i] = layout.getPageTopLead(i)
    }

    // 只存本页那些行（"不要一次存很多页"）：窗口里其它行没有绘制指令，存了也没用。
    // 没有文本指令的行（纯插图/表格占位）本来就不画字，跳过即可。
    val lineIdx = ArrayList<Int>(slice.lastLineExclusive - slice.firstLine)
    val lines = ArrayList<DrawLine>(slice.lastLineExclusive - slice.firstLine)
    for (i in slice.firstLine until slice.lastLineExclusive) {
        val l = window[i] ?: continue
        lineIdx += i
        lines += l
    }
    return PageSnapshot(
        schema = PageSnapshot.SCHEMA,
        layoutVersion = PaginationCacheCodec.LAYOUT_VERSION,
        appVersion = appVersion,
        bookId = bookId,
        chapterIndex = chapterIndex,
        pageIndex = pageIndex,
        paramHash = paramHash,
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        cssHash = cssHash,
        fontSig = fontSig,
        charStart = slice.charStart,
        charEnd = slice.charEnd,
        blockStart = slice.blockStart,
        blockEndExclusive = slice.blockEndExclusive,
        firstLine = slice.firstLine,
        lastLineExclusive = slice.lastLineExclusive,
        lineTop = tops.toList(),
        lineBottom = bottoms.toList(),
        lineStart = starts.toList(),
        lineEnd = ends.toList(),
        lineParagraph = para.toList(),
        frameTop = framesTop.toList(),
        frameBottom = framesBottom.toList(),
        topLead = leads.toList(),
        lineIndex = lineIdx,
        lines = lines,
        tableCells = layout.tableCellLines(slice.firstLine, slice.lastLineExclusive),
        backgrounds = layout.pageBackgrounds(slice.firstLine, slice.lastLineExclusive),
        images = layout.pageImages(slice.firstLine, slice.lastLineExclusive),
    )
}

/**
 * [PageSnapshot] 的 JSON 编解码（与 `:common` 的 settings 落盘同一套 `kotlinx.serialization` 口径）。
 *
 * 为什么用 JSON 而不是手写二进制：绘制模型有几十个字段（一行 = 41 个属性，含颜色/字体/装饰/
 * 裁剪一大堆子结构），手写编解码器漏一个字段的后果是"画错一格"且编译器不会提醒；用序列化插件
 * 时**给数据类加字段就必须同时可序列化**，漏字段从"肉眼漏看"变成"编译期/运行期立刻暴露"。
 * 一页几十 KB 的文本体积与几毫秒的编解码在后台线程上都可以忽略。
 *
 * [ignoreUnknownKeys] 让"新版写、旧版读"（或回退装旧包）不会炸；键全量校验仍由
 * [PageSnapshot.matches] 负责，未知字段不参与语义。
 */
object PageSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: PageSnapshot): ByteArray =
        json.encodeToString(PageSnapshot.serializer(), snapshot).encodeToByteArray()

    /** 解析失败（截断/损坏/旧 schema）一律回 null，由调用方当作 miss 处理。 */
    fun decode(bytes: ByteArray): PageSnapshot? = runCatching {
        json.decodeFromString(PageSnapshot.serializer(), bytes.decodeToString())
    }.onFailure {
        Logger.w("Orilumn.DISK", "page snapshot decode FAIL ${it::class.simpleName}: ${it.message}")
    }.getOrNull()
}

/**
 * 单页快照的磁盘存储：**每本一份文件**（`<root>/page/book_<id>/current.json`）。
 *
 * 写入即覆盖 => 天然满足"同时删掉之前的页面、不要一次存很多页"：不存在第二份文件，
 * 也没有需要回收的旧条目。写入走"先写临时文件再原子改名"，避免读者在写到一半时被杀进程
 * 留下半份 JSON（半份 JSON 解码必然失败 => 退化为普通开书，不会画错）。
 *
 * 一切 IO 失败都只记 w 并当 miss 处理：快照是**加速手段**，永远不能成为开书的失败点。
 */
class PageSnapshotStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val root: Path,
) {
    fun fileOf(bookId: Long): Path = root.resolve("page/book_$bookId/current.json")

    fun read(bookId: Long): PageSnapshot? {
        if (bookId < 0) return null
        val f = fileOf(bookId)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }
            .onFailure { Logger.w("Orilumn.DISK", "page snapshot read IO FAIL $f ${it.message}") }
            .getOrNull() ?: return null
        return PageSnapshotCodec.decode(bytes)
    }

    fun write(bookId: Long, snapshot: PageSnapshot) {
        if (bookId < 0) return
        val f = fileOf(bookId)
        val dir = f.parent ?: return
        val bytes = PageSnapshotCodec.encode(snapshot)
        runCatching {
            fs.createDirectories(dir)
            val tmp = dir.resolve("current.tmp")
            fs.write(tmp) { write(bytes) }
            // okio 的 atomicMove 在个别文件系统上不保证可用 => 退一步"直接覆盖"，
            // 两种写法都只会产生"整份新文件"或"半份新文件"，后者解码失败即 miss。
            runCatching { fs.atomicMove(tmp, f) }
                .onFailure { fs.write(f) { write(bytes) }; runCatching { fs.delete(tmp) } }
        }.onFailure {
            Logger.w("Orilumn.DISK", "page snapshot write FAIL $f ${it::class.simpleName}: ${it.message}")
        }
    }

    /** 显式丢弃（例如版式被判定失效时）。失败静默：文件不存在本来就是常态。 */
    fun clear(bookId: Long) {
        if (bookId < 0) return
        runCatching { fs.delete(fileOf(bookId)) }
    }
}
