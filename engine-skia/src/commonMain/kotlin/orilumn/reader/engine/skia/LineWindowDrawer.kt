package orilumn.reader.engine.skia

import orilumn.reader.engine.css.BorderStyle
import orilumn.reader.engine.css.CornerRadius
import orilumn.reader.engine.css.DecorationLine
import orilumn.reader.engine.css.DecorationStyle
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.BorderEdgeAxis
import orilumn.reader.engine.laying.HYPHEN_GLYPH
import orilumn.reader.engine.laying.InlineBoxRun
import orilumn.reader.engine.laying.SOFT_HYPHEN
import orilumn.reader.engine.laying.TextDecorationRun
import orilumn.reader.engine.laying.borderEdgeRects
import orilumn.reader.engine.laying.extraHeightPx
import orilumn.reader.engine.laying.inlineBoxAdvanceEnabled
import orilumn.reader.engine.laying.isDocumentSpace
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Point
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.TextBlobBuilder
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.Shadow
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.roundToInt

/**
 * 单行文本绘制指令。
 *
 * 由测度引擎（[SkiaParagraphBreaker]）产出的每一 `range` 对应一张绘制指令，
 * [yTop]..[yBottom] 为该框在所属页/滚动容器内的**绝对 Y**几何（统一行高，
 * `lineHeightPx = round(fontSizePx × lineHeightRatio)`），
 * [lineWidthPx] 为该行的整形宽（所在内容区宽度）。分页与连续滚动对绘制层
 * 只差这一组绝对坐标，其余完全共用（见 [LineWindowDrawer]）。
 *
 * @property text 所属整段文本；[range] 指向其中可见子区间（不含换行符）。
 * @property families 级联结果里该行的整条 CSS `font-family` 栈（作者顺序；空 = 默认族），
 *   与测度（[SkiaParagraphBreaker]）交给 [SkParagraphFactory] 的栈完全一致，绘制即量得。
 * @property xLeft 文本相对内容区的水平起点（px）；表叶 = `contentLeft + border.left + padding.left`，
 *   普通带头叶 = 0（对齐 [orilumn.reader.engine.layout] 各 BoxPageRenderer 的 xOff，P 系列 B 行）。
 * @property listMarker 行首列表 marker（OUTSIDE 悬垂沟槽 / INSIDE 行首内嵌）；null = 无 marker。
 * @property inkColor 文本墨色（ARGB Int，与 TypographicProfile.fgColor 同一值）：主题换色即换墨，
 *   底色由调用方打底（Android 离屏 surface / 桌面 Compose 背景），不在此持有。
 * @property colorRuns 书内显色区间（[orilumn.reader.engine.css.ColorRun]，[text] 全文本坐标系）：
 *   有区间的字按书色画，无区间仍走 [inkColor]；绘制时墨色盖章只改 [inkColor]，不动 runs。
 * @property fontRuns 行内 face 段（[orilumn.reader.engine.css.FontRun]，[text] 全文本坐标系，空 = 全行
 *   用 [families]/[weight]/[italic]/[monospace] 基底）——浏览器 inline-run 语义：有段的字按段的
 *   face 画（正文里的 `<code>`/`<kbd>` 等宽、`<strong>`/`<em>` 加粗/斜体），无段仍走基底；与测度
 *   （[SkiaParagraphBreaker]）同样源的 runs，量画一致。
 * @property firstLineIndentPx 本行是段首行时的 CSS `text-indent` 偏移（px；非首行恒 0）：
 *   断行侧已把首行按减宽排过，绘制侧把本行整体右移该值（与旧 LeadingMarginSpan 同位）。
 * @property nowrap 本行来自 `white-space: pre/nowrap` 不换行段：整形不限宽（单行溢出绘制），
 *   与断行侧 `breakLeafLines` 不换行语义一致（P1-2）。
 */
data class DrawLine(
    val text: String,
    val range: IntRange,
    val yTop: Int,
    val yBottom: Int,
    val alignment: TextAlign,
    val fontSizePx: Float,
    val lineHeightRatio: Float,
    val tag: String?,
    val families: List<String>,
    val weight: Int,
    val italic: Boolean,
    val monospace: Boolean,
    val letterSpacingEm: Float,
    val lineWidthPx: Int,
    val xLeft: Int = 0,
    val listMarker: ListMarkers.ListMarker? = null,
    val inkColor: Int = 0xFF000000.toInt(),
    val colorRuns: List<orilumn.reader.engine.css.ColorRun> = emptyList(),
    val fontRuns: List<orilumn.reader.engine.css.FontRun> = emptyList(),
    val firstLineIndentPx: Float = 0f,
    val nowrap: Boolean = false,
    /**
     * 本行是否**断词收尾** —— 行尾要补一个 [orilumn.reader.engine.laying.HYPHEN_GLYPH]。
     *
     * 断行侧已为它**预留了版心**（`InhouseParagraphBreaker.reserveHyphenWidth`），
     * [LineAligner] 也把它算进 JUSTIFY 拉伸基数与可见右缘，故本字段只管「落墨」。
     * 漏传的表现是连字符没画（版心被白白让出一截），反过来画了而没预留就会溢出被裁。
     */
    val hyphenAtEnd: Boolean = false,
    /**
     * 本行的**标点挤压比例**（`[0,1]`，0 = 不挤），来自
     * [orilumn.reader.engine.laying.BrokenLine.squeezeRatio]。
     *
     * 断行侧是「挤着算宽度」才敢把词完整留在同一行的，[LineAligner] 把它乘进 `adv`
     * ⇒ 字位变窄、行少断一次。漏传 = 按 0 挤 ⇒ **画比量宽 ⇒ 右溢被裁**（分页阅读器硬错误）；
     * 反过来「绘制侧自己挤一个比例」也不行：那就是两个宽度，说不清哪个才是断点决策的依据。
     */
    val squeezeRatio: Float = 0f,
    /**
     * P1-2 行内基线位移（[orilumn.reader.engine.laying.BaselineShift]，[text] 全文本坐标系，
     * 空 = 纯基线）：随段整形（`SkParagraphFactory.runTextStyle` 位移＋行盒 strut 固定基线），
     * 不断行几何。
     */
    val baselineShifts: List<orilumn.reader.engine.laying.BaselineShift> = emptyList(),
    /**
     * P3-a 行阴影（颜色已解 currentColor；null 即无）：随段整形（Skia TextStyle
     * 原生阴影，量画同源；位移/模糊不改变 advances，不断行几何）。
     */
    val textShadow: orilumn.reader.engine.css.TextShadow? = null,
    /** P3-a 着重号样式（NONE 即无；圆点/圆圈画在行上/下方，见 emphasisUnder）。 */
    val emphasis: orilumn.reader.engine.css.EmphasisStyle = orilumn.reader.engine.css.EmphasisStyle.NONE,
    /** P3-a 着重号位置（false＝行上方，true＝行下方）。 */
    val emphasisUnder: Boolean = false,
    /** P3-a 祖先链 opacity 连乘（1 即旧路径；墨色/段色统一乘）。 */
    val alpha: Float = 1f,
    /**
     * 显示密度（1 CSS px 落几个物理像素）。
     *
     * 只服务**虚线/点线边框**：Chromium 的段长/隙长比例是在设备像素空间里定的
     * （`styled_stroke_data.cc`：设备厚 < 3 用 3/2，≥ 3 用 2/1），本仓绘制坐标是 CSS px，
     * 不带上密度就会在 2x/2.75x 屏上把段画长一档。缺省 1 = 纯几何测试口径。
     */
    val deviceScale: Float = 1f,
    /**
     * P4-c2: `text[0]` 的章内全局字符偏移（= 叶全局起点；`text[k]` ↔ `charBase + k`）。
     * 点按命中（行内 glyph → 叶内偏移）经它换算成控制器 `linkTargetAt` 要的章内 char。
     * 默认 0（旧构造点行为不变）。
     */
    val charBase: Int = 0,
    /**
     * P6-b 叠排注音 runs（[text] 全文本坐标系，空 = 无注音旧路径）：
     * 由 [DrawLineBuilder] 从与 [text] 同构的遍历产出；绘制把 rt 居中画在基字上方，
     * 内联 rt 源文以透明墨隐藏（占宽保守，字符流/断行不变）。
     */
    val rubyRuns: List<orilumn.reader.engine.laying.RubyRun> = emptyList(),
    /**
     * 下划线区间（[text] 全文本坐标系，空 = 无下划线旧路径）：
     * 由 [DrawLineBuilder] 从与 [text] 同构的遍历产出（声明元素整段传播）；
     * Skia 端随段挂原生下划线装饰（量画同源，不改 advances）。
     */
    val decorationRuns: List<orilumn.reader.engine.laying.TextDecorationRun> = emptyList(),
    /**
     * 标注装饰区间（**章内全局坐标**，空 = 无高亮/划线）：
     * 由 [projectDecor] 从本章标注列表投影到行上；绘制侧按 [charBase] 换回本行文本坐标。
     *
     * 与 [decorationRuns]（书内 `text-decoration`，[text] 坐标系）是两条通道：本字段只服务
     * 读者标注，颜色是创建时的 ARGB 快照，不受书内样式/调色板变化影响。`data class` 加字段
     * ⇒ 栅格指纹按值比较，装饰一变即重烤。
     */
    val decorRuns: List<DecorRange> = emptyList(),
    /**
     * 行内盒 spans（[text] 全文本坐标系，空 = 无行内背景/边框）：
     * 由 [DrawLineBuilder] 从与 [text] 同构的遍历产出。
     *
     * 绘制侧按本行裁剪成片段，画**背景**（border-box）与**四边边框带**；`border-left` 只在元素
     * 首个片段、`border-right` 只在末片段（`box-decoration-break: slice`）。**必须画在文字之下**。
     *
     * 横向 `padding`/`border` 的**推进量**在断行侧注入（1b，与测度同源）；本字段是同一份数据的
     * 几何视图，只负责落墨。`data class` ⇒ 加字段/改值即自动让栅格指纹失效重烤。
     */
    val inlineBoxRuns: List<orilumn.reader.engine.laying.InlineBoxRun> = emptyList(),
    /**
     * `::first-letter{float:left}` 首字下沉盒（null = 无）：本行的 x 偏移/收窄已由断行侧决定
     * （`xLeft`/`lineWidthPx`），这里只带**首字自己的基线**信息。
     *
     * 首字留在行文本里（第 0 行）：它按下沉盒自己的基线落墨（盒子顶 = 首行行框顶，
     * 基线在盒内按 `字号 × 行高比例` 居中），而不是跟正文基线走 —— 只在本行**含首字**时生效
     * （`range.first == 0`）。
     */
    val capFloat: orilumn.reader.engine.laying.CapFloat? = null,
    /**
     * 表格图占位隐藏区间（[text] 全文本坐标系，空 = 无表图旧路径）：
     * [TableCellLines] 为已进 [PageImage] 的 U+FFFC 占位记录本区间；绘制以透明墨隐藏
     *（占宽保留，与 P6-b 注音源文同法），字符流/断行/点按坐标不变。
     */
    val imgHidden: List<IntRange> = emptyList(),
    /**
     * E10：`visibility: hidden` 覆盖区间（[text] 全文本坐标系，空 = 全叶可见）。
     *
     * 隐藏字符**不落墨、但保留占位**（与 `display:none` 的去盒语义相反）：字形/装饰/着重号/注音
     * 一并跳过，advances 与断行不变（与 [imgHidden] 同一份「占宽不画」机制）。
     * 由 [DrawLineBuilder] / 塑形 shape 从与 [text] 同构的遍历产出。
     */
    val visibilityHiddenRuns: List<IntRange> = emptyList(),
    /**
     * 混排字距（em，0 = 关，[orilumn.reader.engine.text.TypographicProfile.cjkLatinSpacingEmApplied]
     * 的值）：行内 CJK↔西文边界的注入间隙。
     *
     * 断行侧已为它**预留了版心**（[InhouseParagraphBreaker] 经 [SkiaRunMeasurer.advances] 计入
     * 判定宽），本字段只管**落墨侧的 x 位**（[LineAligner.align] + [KerningClusterTable.clusterXs]
     * 与量宽侧同源）。漏传 = 版心白白让出一截（行内看着挤），反过来传了而断行侧没预留就会右溢被裁。
     */
    val cjkLatinSpacingEm: Float = 0f,
    /**
     * E11：本行内容所受的**祖先链 `overflow` 裁剪**（null = 不裁），与 [yTop] 同一坐标系。
     *
     * 渲染层按行 `save/clipRect/restore`（X 相对内容区原点，与 [xLeft] 同口径）。行切片/离屏换算
     * 平移 Y 时**必须同时平移它**（[shiftedBy]），否则裁剪区错位。
     */
    val clip: orilumn.reader.engine.laying.ClipRect? = null,
) {
    /** 沿 Y 平移（页切片/离屏换算；裁剪区同步平移，漏了即错位）。 */
    fun shiftedBy(dy: Int): DrawLine =
        if (dy == 0) this else copy(yTop = yTop + dy, yBottom = yBottom + dy, clip = clip?.shifted(dy))
}

/**
 * 行窗口绘制器：按**行**的绝对 Y 坐标用 SkParagraph 单行整形绘制。
 *
 * 与测度共用 [SkParagraphFactory] 的同一套 ParagraphStyle 构造（kJustify / 行高 /
 * letterSpacing 恒一），保证"画出来的"就是"量出来的"。每一行只用其 [DrawLine.range]
 * 子串、以 [DrawLine.lineWidthPx] 整形——区间由测度保证 ≤ 行宽，故 `layout` 不二次折行；
 * 对齐（含两端对齐/居中/右对齐）在行宽内生效，与 Android 排版对齐语义一致。
 *
 * 分页与连续滚动共用此接口：两者均产出若干 [DrawLine]（绝对 Y）→ `drawLines`。
 * [clip] 用于行窗口裁剪（可选）：给定可视内容区后，窗口外行由裁剪丢弃。
 *
 * ## 视觉模拟（合成粗体 / 合成斜体）的落墨边界
 *
 * 判据与阈值全在 [SkParagraphFactory.synthesisFor]（渲染层·字体能力），本类只负责**施加**：
 *
 * - 合成粗体落在 [org.jetbrains.skia.Font] 上（`isEmboldened`），取面时随面走，本类无需感知；
 * - 合成斜体是**画布剪切**（Skia 的 `Font` 没有斜体属性），施加在**逐字落墨**三处：
 *   [drawGlyphPass] 的正字/阴影两趟、行尾连字符的正文/阴影两笔。阴影趟的剪切中心取 `y + dy`，
 *   否则影子斜、字不斜（或反之）。
 *
 * **划在边界外的两处**：[paintText]（list marker）与 `drawRuby` 的注音文本走 SkParagraph 的
 * `Paragraph.paint`，而**同一个 paragraph 对象也用于它们的测量**；在 `paint` 外面套剪切会让
 * 「量出来的形状」与「画出来的形状」不一致（量画失配，本仓头号禁忌）。这两处都是短小附属文本
 * （marker 形如 `1.`/`•`，注音字数 ≈ 底字数），合成与否肉眼不可辨，故明确不合成 ——
 * 这是**记录在案的取舍**，不是遗漏。
 */
class LineWindowDrawer(
    /**
     * 字体集合按次解析（默认 [SkiaFontPool.current]），而非构造时快照：绘制器是常驻实例
     *（Compose remember / 引擎复用），用户开书后导入字体会换池；快照即永远看不见新字库
     *（断行器按次新建不受影响，两边还会量画不一致）。
     */
    private val collections: () -> FontCollection = SkiaFontPool::current,
) {

    /**
     * S4/S5 协作件：几何（[LineAligner]）与落墨（[GlyphPainter]）。
     *
     * **两侧同源**：二者都用 [SkiaRunMeasurer] 取宽，与断行侧算断点同一出口 ——
     * 所以「量到的宽」与「画出来的宽」逐值一致（量画一致生死线）。
     * 按次构造而非快照：本类与绘制器同为常驻实例，字体池会因导入新字库而换，
     * 快照即永远看不见新字库（与 [collections] 同一理由）。
     */
    private val aligner = LineAligner()
    private val glyphPainter = GlyphPainter()
    private val kerningTable = KerningClusterTable()

    /**
     * **诊断/测试钩子**：逐字落墨时回调每一个**真正画出去**的码本与其占用的码元数。
     * 生产恒为 `null`（一次 `?.let` 的代价），测试用来钉住「落墨侧取码本的契约」。
     *
     * ## 为什么需要它（像素判据抓不到这个缺陷）
     *
     * 代理对被拆开落墨时，画面上**并不是两个豆腐块** —— skiko 会把孤立代理编码成 `?` 画出来，
     * 墨迹存在、左右还不对称 ⇒ 「豆腐块是左右对称的」这个像素判据**恒为假**（实测 `sym=false`），
     * 一把**假绿锁**。教训见 `自建断行引擎-测试计划.md` ⑫。
     *
     * 而这里要钉的契约本来就只是个**取值问题**（画出去的码本是不是合并后的那个），
     * 不该绕到像素上去猜。回调把契约直接暴露出来：断言「画出去的码本里没有孤立代理、
     * 且含合并后的 U+1F642」—— 这对**任何宿主**都成立，不依赖宿主装了什么字体。
     */
    internal var onGlyphCp: ((cp: Int, units: Int) -> Unit)? = null

    /** 该行是否有**带位移的已定位行内盒**（`position: relative` 且 `relDx/relDy` 非零）。 */
    private fun hasPositionedRuns(line: DrawLine): Boolean = line.inlineBoxRuns.any {
        it.visible && !it.visibilityHidden && (it.relDx != 0 || it.relDy != 0)
    }

    /** 延后到「整块在流内容画完之后」再补画的已定位层（见 [drawLines] 的两趟）。 */
    private class DeferredLine(val line: DrawLine, val textX: Float, val style: ParagraphStyle)

    fun drawLines(canvas: Canvas, contentLeft: Float, lines: List<DrawLine>, clip: Rect? = null) {
        if (lines.isEmpty()) return
        val collection = collections()
        val saved = if (clip != null) {
            canvas.save()
            canvas.clipRect(clip)
            true
        } else {
            false
        }
        try {
            // 附录 E 第 8 步的**已定位行内盒**必须排在**整块**的在流内容之后 —— 不是「逐行之后」：
            // 盒下移 6px 会压到**下一行**的文字上，逐行画的话下一行的字又把盒盖回去（用户实测：
            // 第一行对、下一行又错）。故第一趟只画在流，第二趟再按行补已定位盒（盒 → 自己的字）。
            val deferred = ArrayList<DeferredLine>()
            for (line in lines) {
                // E11：逐行 `overflow` 祖先裁剪（行自带的裁剪区，X 相对内容区原点 = contentLeft）。
                // 与整窗 clip 叠加（save 嵌套），行间互不影响。
                val lc = line.clip
                val lineSaved = if (lc != null) {
                    canvas.save()
                    canvas.clipRect(
                        Rect.makeLTRB(
                            contentLeft + lc.left, lc.top.toFloat(),
                            contentLeft + lc.right, lc.bottom.toFloat(),
                        ),
                    )
                    true
                } else {
                    false
                }
                try {
                    val style = SkParagraphFactory.paragraphStyle(
                        line.alignment,
                        line.fontSizePx,
                        line.lineHeightRatio,
                        line.tag,
                        line.families,
                        line.weight,
                        line.italic,
                        line.monospace,
                        line.letterSpacingEm,
                        // P3-a: 祖先 opacity 统一乘墨色（段色在 paintText 内乘）。
                        inkColor = withAlpha(line.inkColor, line.alpha),
                        // P1-2: 有基线位移的行固定行盒基线（与度量侧同开，基线不浮动）。
                        forceStrut = line.baselineShifts.isNotEmpty(),
                    )
                    var textX = contentLeft + line.xLeft
                    val marker = line.listMarker
                    if (marker != null) {
                        val gap = ListMarkers.markerGapPx(line.fontSizePx)
                        if (marker.isShapeKind) {
                            // 矢量圆点/圈/方块：不随字体字形走（老 StaticLayout 管线 drawShapeMarker 同口径，
                            // C1-0 重构搬运；字形 "•/○" 在 CJK 字库下一大一小即此回归）。
                            val markerW = ListMarkers.shapeMarkerWidthPx(line.fontSizePx)
                            val x = if (marker.position == ListMarkers.Position.INSIDE) textX
                            else textX - markerW - gap
                            drawShapeMarker(
                                canvas, marker.kind, x, (line.yTop + line.yBottom) / 2f,
                                line.fontSizePx * ListMarkers.SHAPE_MARKER_EM,
                                withAlpha(line.inkColor, line.alpha),
                            )
                            if (marker.position == ListMarkers.Position.INSIDE) textX += markerW + gap
                        } else {
                            val markerString = ListMarkers.markerText(marker.kind, marker.order)
                            val markerW = measureMarkerWidth(style, markerString, collection)
                            when (marker.position) {
                                // OUTSIDE：marker 在沟槽悬垂（文本左缘左侧 markerW+gap），文本位置不变.
                                ListMarkers.Position.OUTSIDE -> {
                                    paintText(canvas, style, markerString, textX - markerW - gap, line, collection)
                                }
                                // INSIDE：marker 行首内嵌，文本向右让 markerW+gap（近似平板 LeadingMarginSpan）.
                                ListMarkers.Position.INSIDE -> {
                                    paintText(canvas, style, markerString, textX, line, collection)
                                    textX += markerW + gap
                                }
                            }
                        }
                    }
                    if (hasPositionedRuns(line)) deferred.add(DeferredLine(line, textX, style))
                    paintText(canvas, style, line, textX, collection)
                } finally {
                    if (lineSaved) canvas.restore()
                }
            }
            for (d in deferred) {
                val line = d.line
                val textX = d.textX
                val style = d.style
                val lc = line.clip
                val lineSaved = if (lc != null) {
                    canvas.save()
                    canvas.clipRect(
                        Rect.makeLTRB(
                            contentLeft + lc.left, lc.top.toFloat(),
                            contentLeft + lc.right, lc.bottom.toFloat(),
                        ),
                    )
                    true
                } else {
                    false
                }
                try {
                    paintText(canvas, style, line, textX, collection, positionedLayer = true)
                } finally {
                    if (lineSaved) canvas.restore()
                }
            }
        } finally {
            if (saved) canvas.restore()
        }
    }

    /**
     * 算一段章内区间在当前页各行上的**几何矩形**（只算不画），供选区覆盖层 / 手柄 / 放大镜定位。
     *
     * 与 [drawLines] → `paintText` 共用 [placementFor] 的逐字 x，保证「命中 / 绘制 / 装饰」同一份
     * 几何。返回的 `Rect`：
     * - `x` 是**相对内容区左缘**的坐标（已含 [DrawLine.xLeft]；调用方再加页面内容的绘制原点）；
     * - `y` 与 [DrawLine.yTop] / [DrawLine.yBottom] 同一坐标系（章节绝对 Y）。
     *
     * 区间按 `[range.first, range.last + 1)` 解释；与行不相交的行不产出矩形。
     * [chapter] 只用于调用方表达作用域，函数不跨章。
     */
    @Suppress("UNUSED_PARAMETER")
    fun decorRects(lines: List<DrawLine>, chapter: Int, range: IntRange): List<Rect> {
        if (range.isEmpty()) return emptyList()
        val queryStart = range.first
        val queryEnd = range.last + 1
        val out = ArrayList<Rect>(lines.size)
        for (line in lines) {
            val start = line.range.first.coerceIn(0, line.text.length)
            val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)
            if (endExcl <= start) continue
            val lineStart = line.charBase + start
            val lineEnd = line.charBase + endExcl
            val s = maxOf(queryStart, lineStart)
            val e = minOf(queryEnd, lineEnd)
            if (e <= s) continue
            val placement = placementFor(line, start, endExcl)
            val ps = (s - line.charBase - start).coerceIn(0, endExcl - start)
            val pe = (e - line.charBase - start).coerceIn(ps, endExcl - start)
            val (left, right) = aligner.visibleSpan(placement, endExcl - start, ps, pe) ?: continue
            if (right <= left) continue
            out.add(
                Rect.makeLTRB(
                    line.xLeft + left,
                    line.yTop.toFloat(),
                    line.xLeft + right,
                    line.yBottom.toFloat(),
                ),
            )
        }
        return out
    }

    /**
     * 本行的逐字几何（[LineAligner.Placement] 的完整入参集中在这里）。
     *
     * `paintText` 与 [decorRects] 必须走同一个入口：任何只改一侧的「修法」都会让选区/装饰与
     * 文字错位（本仓头号禁忌「量画同源」）。
     */
    private fun placementFor(line: DrawLine, start: Int, endExcl: Int): LineAligner.Placement =
        aligner.align(
            text = line.text,
            range = start until endExcl,
            fontSizePx = line.fontSizePx,
            lineWidthPx = if (line.nowrap) Float.MAX_VALUE else line.lineWidthPx.coerceAtLeast(1).toFloat(),
            letterSpacingEm = line.letterSpacingEm,
            tag = line.tag,
            families = line.families,
            weight = line.weight,
            italic = line.italic,
            monospace = line.monospace,
            fontRuns = line.fontRuns,
            align = line.alignment,
            // 首行缩进**必须交给 Aligner**，不能画完再整体右移（用户报缺陷①的修法）。
            //
            // Aligner 的 `natural` 本来就以 `x0 = firstLineIndentPx` 起算（`natural = x0 + Σadv`），
            // 所以 `slack = lineWidthPx − natural` 已经把缩进扣掉了，拉伸后右缘正好落在版心。
            // 旧写法传 `0f` 再 `paintX += indent`：拉伸基数**少了缩进** ⇒ JUSTIFY 首行被拉到
            // 版心宽之后又整体右移 indent ⇒ **右溢出一个缩进**。实测（Rust 书 22 章）：
            // `p { text-align: justify; text-indent: 2em }` 的**每一个**正文段首行都如此，
            // 溢出量恒等于缩进（`2em` = 84.36px @fs=42.18），22 章里 555 行，末两个字被页面裁掉。
            firstLineIndentPx = line.firstLineIndentPx,
            // 末行不拉伸：两端对齐的定义本身。
            isLastLine = isLastLineOf(line, endExcl),
            // 行尾连字符：进拉伸基数与可见右缘（连字符占版心，否则 JUSTIFY 会少算一个字位）。
            hyphenAtEnd = line.hyphenAtEnd,
            // 标点挤压：断行侧定断点时用的比例（量画同源，教训 ⑩）。
            squeezeRatio = line.squeezeRatio,
            // 混排字距：与断行侧同一个 em（间隙进 `adv` ⇒ 逐字 x、JUSTIFY 基数、可见右缘同源）。
            cjkLatinSpacingEm = line.cjkLatinSpacingEm,
            // 行内盒横向推进：与断行侧同一份（同 `cjkLatinSpacingEm` 的理由 —— 量画同源）。
            inlineBoxRuns = line.inlineBoxRuns,
        )

    /** 主文本行绘制（S5 逐字路径）：几何由 [LineAligner] 给、落墨由 [GlyphPainter] 逐字做。 */
    /**
     * 主文本行绘制 —— **S5 逐字路径，本函数内不出现 `Paragraph`**。
     *
     * 几何（逐字 x / 可见右边界 / 拉伸）由 [LineAligner] 给，落墨由 [GlyphPainter] 逐字
     * `drawString` 做；两者与断行侧共用 [SkiaRunMeasurer] 取宽，**量画同源**。
     *
     * ## 为什么这里一句 `Paragraph` 都没有（S5 清理）
     *
     * S5 落地时为了少改，`paragraph.layout()` 被留着只用来取 `lineMetrics.height` 算基线，
     * 于是**两套基线计算并存**、60 行 `ParagraphBuilder` 分段喂文本成了死代码。
     * 「换断行器是为了真正实现 JUSTIFY」这个主要目的会被这种残骸掩盖 ——
     * 读代码的人看到 `ParagraphBuilder` + `Alignment.JUSTIFY` 会以为对齐还是 Skia 在做。
     *
     * 现在基线由 [LineAligner.baselineOffset] 单独算（半行距居中 + ascent，CSS 2.2 §10.8.1），
     * **Android 的 `setHeight`/`setHalfLeading` 平台差异一并消失**（旧路径靠
     * `deviceBaselineShift` 补，现在两侧同式）。故整段 Paragraph 装配可以删净。
     *
     * [style] 形参仍在：ruby rt 与 marker 两处仍需自建 `ParagraphStyle`（注音独立居中整形、
     * marker 单行短文本），它们与本行的对齐无关。
     */
    private fun paintText(
        canvas: Canvas,
        style: ParagraphStyle,
        line: DrawLine,
        textX: Float,
        collection: FontCollection,
        /** `true` = 只补画已定位行内盒那一层（[drawLines] 的第二趟）；见函数内注释。 */
        positionedLayer: Boolean = false,
    ) {
        // P3-a: 祖先 opacity 统一乘墨色/段色。
        val ink = withAlpha(line.inkColor, line.alpha)
        // 钳位：range 越界只跳过该行，绝不在绘制线程崩 activity。
        val start = line.range.first.coerceIn(0, line.text.length)
        val endExcl = (line.range.last + 1).coerceIn(start, line.text.length)

        // P6-b 本行相交的叠排 runs；内联 rt 源文以透明墨隐藏（占宽保守，字符流/断行不变）。
        val rubyHits = line.rubyRuns.filter { it.start < endExcl && it.endExclusive > start }
        // E10：`visibility: hidden` 覆盖区间（本行相交部分）。隐藏字符**占宽照旧、只不落墨**；
        // 装饰线 / 着重号 / 注音 rt 也随基字一同隐去（字形之外的三处消费点都读本表）。
        val visHidden = line.visibilityHiddenRuns.mapNotNull { r ->
            val s = r.first.coerceIn(start, endExcl)
            val e = (r.last + 1).coerceIn(start, endExcl)
            if (e > s) s until e else null
        }
        // 表格图占位隐藏（与注音源文同法：占宽保留，字符流/断行不变）。
        val hiddenRuns = buildList {
            for (run in rubyHits) {
                val s = run.rtStart.coerceIn(start, endExcl)
                val e = run.rtEndExclusive.coerceIn(start, endExcl)
                if (e > s && run.rtStart >= 0) add(s until e)
            }
            for (r in line.imgHidden) {
                val s = r.first.coerceIn(start, endExcl)
                val e = (r.last + 1).coerceIn(start, endExcl)
                if (e > s) add(s until e)
            }
            addAll(visHidden)
        }
        // 子段 [from,to) 被隐藏 ⟺ 其末字符 to-1 落在区间内（区间按 `until` 存，last = 排外末端-1）。
        fun isHidden(from: Int, to: Int): Boolean {
            for (r in hiddenRuns) if (from >= r.first && to <= r.last + 1) return true
            return false
        }
        val lineExtra = rubyHits.maxOfOrNull { it.extraHeightPx() } ?: 0
        // 文本装饰：行区间相交段（叶坐标）。CSS 标准是整段传播，故按区间并集画，不逐字跳。
        // E10：隐藏字符的装饰线与字形同隐 —— 从装饰段里挖掉 visHidden 区间（无隐藏时空转零开销）。
        val decRuns = line.decorationRuns.mapNotNull { r ->
            val s = r.start.coerceIn(start, endExcl)
            val e = r.endExclusive.coerceIn(start, endExcl)
            if (e > s) r.copy(start = s, endExclusive = e) else null
        }.let { runs -> if (visHidden.isEmpty()) runs else runs.flatMap { subtractHidden(it, visHidden) } }
        // 读者标注装饰：章内全局区间 → 本行文本坐标，并按隐藏区间挖空（隐藏字符不落墨）。
        val decorRuns = decorRunsFor(line, start, endExcl, visHidden)

        // ── 几何：Aligner 给逐字 x / 拉伸 / 可见右边界（P1-2 不换行段以无限宽排，单行溢出）──
        val placement = placementFor(line, start, endExcl)
        // 缩进已在 placement 内（x0），落墨起点即文本区左缘（marker 另行绘制，不动）。
        val paintX = textX
        // 基线：半行距居中 + ascent（CSS 2.2 §10.8.1）。**自算**，不再借道 Paragraph。
        val baseY = baselineY(line, lineExtra, start, endExcl)
        // paintX 是纯内容区偏移；placement 的 x 已含缩进与对齐偏移，逐字落墨时统一加上。
        // **在流行内盒（背景/边框）先落，垫在文字之下**（CSS：背景 → 边框 → 文字）。
        val capDy = capFloatDy(line, baseY, lineExtra)
        // 绘制层级（CSS 2.1 附录 E）：在流内容在第 7 步、**已定位后代**（`position: relative`，
        // `z-index: auto`）在第 8 步 —— 后者**盖在**同行在流文字之上（CSS Position 3 §2.2：
        // 相对定位盒「painted as if it generated a new stacking context」）。故已定位的行内盒
        // 不能跟其它盒一起垫在文字下，要单独走第二趟：底色/边框压住在流文字，自己的字形再压盒上。
        //
        // 判据用 run 的累积相对位移（`relDx/relDy`）：零位移的已定位盒画在哪层都看不出差别，
        // 不必为它多开一趟（真书里绝大多数 `position: relative` 没有位移）。
        val positionedRuns = line.inlineBoxRuns.filter {
            it.visible && !it.visibilityHidden && (it.relDx != 0 || it.relDy != 0)
        }
        if (positionedLayer) {
            // 第二趟（[drawLines] 在**整块**在流内容之后调用）：树序逐盒 —— 盒（底色/边框）→ 它自己的字。
            // 嵌套的已定位盒按 run 顺序自然压在外层之上；本层之外的一切（注音/着重号/连字符/装饰）
            // 已在第一趟画过，这里一概不碰，避免叠深。
            for (r in positionedRuns) {
                paintInlineBoxes(canvas, line, placement, paintX, baseY, start, endExcl, only = listOf(r))
                val s = r.start.coerceIn(start, endExcl)
                val e = r.endExclusive.coerceIn(start, endExcl)
                if (e > s) {
                    paintGlyphs(
                        canvas, line, placement, paintX, baseY, s, e,
                        { f, t -> isHidden(f, t) }, decRuns, placementStart = start,
                    )
                }
            }
            return
        }
        paintInlineBoxes(
            canvas, line, placement, paintX, baseY, start, endExcl,
            only = if (positionedRuns.isEmpty()) null else line.inlineBoxRuns - positionedRuns.toSet(),
        )
        // 标注底色垫在文字之下、书内行内盒之上：读者高亮不会被书的代码块/行内底色吞掉。
        paintDecorBackgrounds(canvas, line, placement, paintX, start, endExcl, decorRuns)
        if (positionedRuns.isEmpty()) {
            paintGlyphs(canvas, line, placement, paintX, baseY, start, endExcl, { f, t -> isHidden(f, t) }, decRuns, capDy = capDy)
        } else {
            // 在流这趟只画**在流**字形与装饰：已定位区间整段跳过（含下划线等装饰，避免第二趟叠深）。
            var cur = start
            for (r in positionedRuns.sortedBy { it.start }) {
                val s = r.start.coerceIn(start, endExcl)
                val e = r.endExclusive.coerceIn(start, endExcl)
                if (e <= cur) continue
                if (s > cur) {
                    paintGlyphs(
                        canvas, line, placement, paintX, baseY, cur, s,
                        { f, t -> isHidden(f, t) }, decRuns, capDy = capDy, placementStart = start,
                    )
                }
                cur = e
            }
            if (cur < endExcl) {
                paintGlyphs(
                    canvas, line, placement, paintX, baseY, cur, endExcl,
                    { f, t -> isHidden(f, t) }, decRuns, capDy = capDy, placementStart = start,
                )
            }
        }
        // 标注划线压在文字与书内装饰之上：读者线更粗、更靠下，不能被书的下划线糊住。
        paintDecorLines(canvas, line, placement, paintX, baseY, start, endExcl, decorRuns)

        // 注音/着重号与文字同盒，随 half-leading 一并下移，保持与基字的相对位置。
        // E10：基字被隐藏的注音整条不画（rt 源文与基字同属被隐藏子树）；行高增量照旧计入基线。
        val rubyPainted =
            if (visHidden.isEmpty()) rubyHits
            else rubyHits.filterNot { fullyHidden(it.start, it.endExclusive, visHidden) }
        if (rubyPainted.isNotEmpty() && endExcl > start) {
            drawRuby(canvas, line, placement, paintX, start, endExcl, rubyPainted, ink, collection, 0f)
        }
        if (line.emphasis != orilumn.reader.engine.css.EmphasisStyle.NONE && endExcl > start) {
            drawEmphasis(canvas, line, placement, paintX, ink, start, endExcl, 0f, visHidden)
        }
    }

    /** 子段 [from,to) 是否被 [hidden] 完全覆盖（E10 注音整条跳过的判据）。 */
    private fun fullyHidden(from: Int, to: Int, hidden: List<IntRange>): Boolean {
        if (to <= from) return true
        for (r in hidden) if (from >= r.first && to <= r.last + 1) return true
        return false
    }

    /**
     * 从装饰段里挖掉隐藏区间（E10）：隐藏字符的 `text-decoration` 与字形同隐，其余片段照画
     * （同段样式原样复制）。无隐藏区间时调用方已短路，不会走到这里。
     */
    private fun subtractHidden(run: TextDecorationRun, hidden: List<IntRange>): List<TextDecorationRun> {
        var segs: List<IntRange> = listOf(run.start until run.endExclusive)
        for (h in hidden) {
            val next = ArrayList<IntRange>(segs.size)
            for (s in segs) {
                if (h.last < s.first || h.first > s.last) {
                    next.add(s)
                    continue
                }
                if (h.first > s.first) next.add(s.first until h.first)
                if (h.last < s.last) next.add((h.last + 1)..s.last)
            }
            segs = next
            if (segs.isEmpty()) return emptyList()
        }
        return segs.map { run.copy(start = it.first, endExclusive = it.last + 1) }
    }

    /**
     * 本行相交的标注装饰：章内全局坐标 → [line] 文本坐标，并挖掉隐藏区间。
     *
     * 与 [decRuns]（书内 `text-decoration`）同法，但颜色 / 粗细 / 位置来自 [DecorRange]，
     * 不参与 CSS 级联。裁剪后仍在文本坐标系，绘制时再减 [start] 换成 placement 局部下标。
     */
    private fun decorRunsFor(
        line: DrawLine,
        start: Int,
        endExcl: Int,
        hidden: List<IntRange>,
    ): List<DecorRange> {
        if (line.decorRuns.isEmpty() || endExcl <= start) return emptyList()
        val lineStart = line.charBase + start
        val lineEnd = line.charBase + endExcl
        val out = ArrayList<DecorRange>(line.decorRuns.size)
        for (r in line.decorRuns) {
            val s = maxOf(r.start, lineStart)
            val e = minOf(r.endExclusive, lineEnd)
            if (e <= s) continue
            val local = r.copy(start = s - line.charBase, endExclusive = e - line.charBase)
            if (hidden.isEmpty()) {
                out.add(local)
            } else {
                out.addAll(subtractHiddenDecor(local, hidden))
            }
        }
        return out
    }

    /** 从一条标注装饰里挖掉隐藏区间（隐藏字符的高亮/划线也不落墨）。 */
    private fun subtractHiddenDecor(run: DecorRange, hidden: List<IntRange>): List<DecorRange> {
        var segs: List<IntRange> = listOf(run.start until run.endExclusive)
        for (h in hidden) {
            val next = ArrayList<IntRange>(segs.size)
            for (s in segs) {
                if (h.last < s.first || h.first > s.last) {
                    next.add(s)
                    continue
                }
                if (h.first > s.first) next.add(s.first until h.first)
                if (h.last < s.last) next.add((h.last + 1)..s.last)
            }
            segs = next
            if (segs.isEmpty()) return emptyList()
        }
        return segs.map { run.copy(start = it.first, endExclusive = it.last + 1) }
    }

    /**
     * 标注高亮底色：画在文字与书内行内盒之上、字形之下（读者意图压过书的背景）。
     *
     * 纵向取整行框 [DrawLine.yTop]..[DrawLine.yBottom]（荧光笔感，跨行连续）；横向复用
     * [LineAligner.visibleSpan]，与落墨/命中同一份几何。
     */
    private fun paintDecorBackgrounds(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        start: Int,
        endExcl: Int,
        decorRuns: List<DecorRange>,
    ) {
        if (decorRuns.isEmpty() || endExcl <= start) return
        val n = endExcl - start
        for (r in decorRuns) {
            if (r.kind != DecorKind.BACKGROUND) continue
            val lo = (r.start - start).coerceIn(0, n)
            val hi = (r.endExclusive - start).coerceIn(lo, n)
            if (hi <= lo) continue
            val (left, right) = aligner.visibleSpan(placement, n, lo, hi) ?: continue
            if (right <= left) continue
            val paint = Paint().apply { color = withAlpha(r.argb, line.alpha) }
            canvas.drawRect(
                Rect.makeLTRB(
                    paintX + left,
                    line.yTop.toFloat(),
                    paintX + right,
                    line.yBottom.toFloat(),
                ),
                paint,
            )
        }
    }

    /**
     * 标注划线 / 删除线：画在文字与书内装饰之后，默认比书内下划线更粗、更靠下，避免两线糊在一起。
     *
     * - 下划线中心 = 基线下 `0.20em`；删除线中心 = 基线上方 `0.28em`（穿字身）；
     * - 线型：实线填矩形，虚线走 dash 描边，波浪线走正弦路径描边；
     * - 默认粗细 = `0.08em`；[DecorRange.thicknessPx] / [DecorRange.offsetPx] 可覆盖
     *   （offset 是中心相对基线的距离，向下为正）。
     */
    private fun paintDecorLines(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        decorRuns: List<DecorRange>,
    ) {
        if (decorRuns.isEmpty() || endExcl <= start) return
        val n = endExcl - start
        for (r in decorRuns) {
            val defaultOffsetEm = when (r.kind) {
                DecorKind.UNDERLINE -> ANNOTATION_UNDERLINE_OFFSET_EM
                DecorKind.STRIKETHROUGH -> -ANNOTATION_STRIKE_OFFSET_EM
                else -> continue
            }
            val lo = (r.start - start).coerceIn(0, n)
            val hi = (r.endExclusive - start).coerceIn(lo, n)
            if (hi <= lo) continue
            val (left, right) = aligner.visibleSpan(placement, n, lo, hi) ?: continue
            if (right <= left) continue
            val thickness = (r.thicknessPx ?: (line.fontSizePx * ANNOTATION_UNDERLINE_THICKNESS_EM)).coerceAtLeast(1f)
            val centerY = baseY + (r.offsetPx ?: (line.fontSizePx * defaultOffsetEm))
            val x0 = paintX + left
            val x1 = paintX + right
            when (r.stroke) {
                DecorStroke.Solid -> {
                    val paint = Paint().apply { color = withAlpha(r.argb, line.alpha) }
                    canvas.drawRect(
                        Rect.makeLTRB(x0, centerY - thickness / 2f, x1, centerY + thickness / 2f),
                        paint,
                    )
                }
                DecorStroke.Dashed -> {
                    val paint = Paint().apply {
                        color = withAlpha(r.argb, line.alpha)
                        mode = PaintMode.STROKE
                        strokeWidth = thickness
                        strokeCap = PaintStrokeCap.ROUND
                        pathEffect = PathEffect.makeDash(
                            floatArrayOf(
                                thickness * ANNOTATION_DASH_ON_RATIO,
                                thickness * ANNOTATION_DASH_OFF_RATIO,
                            ),
                            0f,
                        )
                    }
                    canvas.drawLine(x0, centerY, x1, centerY, paint)
                }
                DecorStroke.Wavy -> {
                    // 与书内波浪装饰同一套波形几何（Chromium 口径），换成本标注的颜色与粗细。
                    drawDecorationWave(canvas, x0, x1, centerY, thickness, withAlpha(r.argb, line.alpha))
                }
            }
        }
    }

    /**
     * 本行基线 y（绝对坐标）。
     *
     * **替换掉旧路径的两套基线计算**：旧代码先按 `Paragraph.paint` 的内部语义定位，再按平台
     * （`paragraphPaintHonorsLineHeight`：jvm true / android false）用 `deviceBaselineShift`
     * 补差；现在两侧同式 —— [LineAligner.boxBaselineOffset] 一次算完，平台差异不再需要分支。
     *
     * ## E9：行内大字号必须一并把基线下移
     *
     * 行框高已按 CSS 2.1 §10.8 取本行各**行内盒** leading 的最大值（[growLineHeightsForInlineFontSize]，
     * 见 `breakLeafLines` 出口）。基线若仍按叶自身字号算，大字顶部就越出行框压到上一行 ——
     * 行框撑了、基线不同步 = 等于没修。故此处对同一批盒取 §10.8.1 的
     * `ascent + 半行距` **最大**（行框顶 = 基线 − 该值）。
     *
     * 只算**字号严格更大**的 run（E9 的范围，也是唯一会改变本行高度的情形）：同号异族的度量差在
     * 亚像素级，拿它去动全量基线是另一件事，不在本刀。
     */
    private fun baselineY(line: DrawLine, lineExtra: Int, start: Int, endExcl: Int): Float {
        val font = glyphPainter.baseGlyphStyle(
            line.fontSizePx, line.families, line.weight, line.italic, line.monospace,
            0xFF000000.toInt(), 'H'.code, line.tag,
        ).font
        val fm = font.metrics
        var offset = aligner.boxBaselineOffset(line.fontSizePx, line.lineHeightRatio, -fm.ascent, fm.descent)
        // 首字下沉（E21 残留修复）：首字出流，**不抬行框基线** —— 它按下沉盒自己的基线落墨
        // （见 [capFloatDy]）。只在本行含首字（`range.first == 0`）时剔。
        val capEnd = line.capFloat?.takeIf { line.range.first == 0 }?.endExclusive ?: 0
        for (r in line.fontRuns) {
            // run 段表是**整叶**的（未按行切）：只认与本行有交集的段，否则同段别行的大字会抬起本行。
            if (r.endExclusive <= start || r.start >= endExcl) continue
            if (capEnd > 0 && r.start == 0 && r.endExclusive <= capEnd) continue
            val px = r.fontPxOr(line.fontSizePx)
            if (px <= line.fontSizePx) continue
            val runFont = glyphPainter.baseGlyphStyle(
                px, r.families, r.weight, r.italic, r.monospace,
                0xFF000000.toInt(), 'H'.code, r.tag ?: line.tag,
            ).font
            val rf = runFont.metrics
            val o = aligner.boxBaselineOffset(px, line.lineHeightRatio, -rf.ascent, rf.descent)
            if (o > offset) offset = o
        }
        return line.yTop + lineExtra + offset
    }

    /**
     * 首字下沉盒的**基线位移**（px，相对本行正文基线 [baseY]）。
     *
     * 浏览器把 `::first-letter{float:left}` 的首字浮出成**独立行盒**：盒顶 = 首行行框顶
     * （`yTop`，注音抬升那截也算在盒外），盒高 = 首字自己的行高（[CapFloat.heightPx] =
     * `字号 × 行高比例`，比例继承），盒内基线按半行距居中 —— 与正文基线无关。
     * 实测（Chromium，18px / 1.5 / 首字 2em）：首字基线比正文基线低 14 CSS px，与下式同值。
     *
     * 只在**含首字那一行**（`range.first == 0`）返回非 0；其余行不受影响。
     */
    private fun capFloatDy(line: DrawLine, baseY: Float, lineExtra: Int): Float {
        val cap = line.capFloat ?: return 0f
        if (line.range.first != 0) return 0f
        val run = line.fontRuns.firstOrNull { it.start == 0 && it.endExclusive <= cap.endExclusive }
        val size = run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx
        val font = glyphPainter.baseGlyphStyle(
            size, run?.families ?: line.families, run?.weight ?: line.weight,
            run?.italic ?: line.italic, run?.monospace ?: line.monospace,
            0xFF000000.toInt(), line.text.getOrNull(0)?.code ?: 'H'.code, run?.tag ?: line.tag,
        ).font
        val fm = font.metrics
        val emH = -fm.ascent + fm.descent
        val boxTop = line.yTop + lineExtra
        val capBaseline = boxTop + (cap.heightPx - emH) / 2f - fm.ascent
        return capBaseline - baseY
    }


    /**
     * P6-b 叠排注音绘制（Skia 端）：每个相交 run 的 rt 文本以注音字号居中画在基字上方。
     * 基字中心经基文段落的 `getRectsForRange` 并集求（量画同源）；rt 段落独立整形居中落位。
     * 内联 rt 源文已透明（占宽保守），字符流/断行不变；水平溢出允许（与浏览器同式）。
     */
    private fun drawRuby(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        start: Int,
        endExcl: Int,
        hits: List<orilumn.reader.engine.laying.RubyRun>,
        ink: Int,
        collection: FontCollection,
        halfLeading: Float,
    ) {
        val n = endExcl - start
        for (run in hits) {
            val bs = maxOf(run.start, start)
            val be = minOf(run.endExclusive, endExcl)
            if (be <= bs || run.rtText.isEmpty()) continue
            // S5：基字区间改读 Aligner 的 `xs`（T0 落点 2）。不再用 `getRectsForRange(TIGHT)`：
            // 那是 Skia **整形后**的墨盒，与本仓「裸 cmap 量宽」不同源（教训 20）；
            // `xs` 才是落墨真用的坐标，注音居中因此与基字严格对齐。
            val ps = (bs - start).coerceIn(0, n)
            val pe = (be - start).coerceIn(ps, n)
            if (pe <= ps) continue
            val span = aligner.visibleSpan(placement, n, ps, pe) ?: continue
            val (left, right) = span
            if (right <= left) continue
            val baseCenter = paintX + (left + right) / 2f
            val rtSize = run.rtFontSizePx.coerceAtLeast(1f)
            val rtStyle = SkParagraphFactory.paragraphStyle(
                orilumn.reader.engine.css.TextAlign.CENTER,
                rtSize,
                1f,
                "rt",
                line.families,
                line.weight,
                line.italic,
                false,
                0f,
                inkColor = ink,
            )
            val rtBuilder = ParagraphBuilder(rtStyle, collection)
            rtBuilder.addText(run.rtText)
            val rtPara = rtBuilder.build()
            try {
                rtPara.layout(Float.MAX_VALUE)
                val rtRects = runCatching {
                    rtPara.getRectsForRange(
                        0, run.rtText.length,
                        org.jetbrains.skia.paragraph.RectHeightMode.TIGHT,
                        org.jetbrains.skia.paragraph.RectWidthMode.TIGHT,
                    )
                }.getOrNull()
                var rtW = 0f
                if (rtRects != null && rtRects.isNotEmpty()) {
                    var rl = Float.MAX_VALUE
                    var rr = -Float.MAX_VALUE
                    for (b in rtRects) {
                        rl = minOf(rl, b.rect.left)
                        rr = maxOf(rr, b.rect.right)
                    }
                    if (rr > rl) rtW = rr - rl
                }
                if (rtW <= 0f) rtW = run.rtText.length * rtSize * 0.6f
                rtPara.paint(canvas, baseCenter - rtW / 2f, line.yTop + halfLeading)
            } finally {
                rtPara.close()
            }
        }
    }

    /** P3-a alpha 合成：ARGB 高字节乘系数（1 即原文）。 */
    private fun withAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        if (alpha <= 0f) return argb and 0x00FFFFFF
        val a = ((argb ushr 24) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * P3-a 着重号绘制：逐字取墨盒中心置点/圈（空格亦置点，与浏览器一致）。
     * 位置：行上 = 行顶内，行下 = 行底内；半径相对行字号。
     */
    private fun drawEmphasis(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        ink: Int,
        start: Int,
        endExcl: Int,
        halfLeading: Float,
        hidden: List<IntRange> = emptyList(),
    ) {
        val filled = line.emphasis == orilumn.reader.engine.css.EmphasisStyle.DOT
        val r = line.fontSizePx * (if (filled) 0.09f else 0.11f)
        if (r <= 0f) return
        val paint = Paint().apply {
            color = ink
            if (!filled) {
                mode = org.jetbrains.skia.PaintMode.STROKE
                strokeWidth = (r * 0.3f).coerceAtLeast(1f)
            }
        }
        val y = if (line.emphasisUnder) line.yBottom + halfLeading - r - 1f else line.yTop + halfLeading + r + 1f
        val n = endExcl - start
        for (i in start until endExcl) {
            // S5：逐字墨盒中心改读 Aligner 的 `xs`（T0 落点 3），不再 `getRectsForRange(TIGHT)`。
            val local = i - start
            if (local >= n || local < 0) continue
            // E10：隐藏字符不着重号（与字形同隐；`hidden` 为空时零开销）。
            if (hidden.isNotEmpty() && hidden.any { i >= it.first && i <= it.last }) continue
            // 「空格亦置点，与浏览器一致」：尾随空白照样占一个点。
            aligner.visibleSpan(placement, n, local, local + 1)?.let { (lx, rx) ->
                val cx = paintX + (lx + rx) / 2f
                canvas.drawCircle(cx, y.toFloat(), r, paint)
            }
        }
    }

    /**
     * 行内盒绘制（背景 + 四边边框带）——**必须画在文字之下**（CSS：背景 → 边框 → 文字）。
     *
     * ## 几何（浏览器语义）
     *
     * 用 [LineAligner.visibleSpan] 取该片段 `[lo, hi)` 的可见左右界（**与落墨/命中/下划线同一份
     * 几何**，量画同源）；内容区高度取该元素**自身字体**的 `ascent + descent`（**不是行高**）；
     * `padding` 向外扩，`border` 再向外一圈。纵向 padding/border **不撑高行框**（与邻行重叠，
     * 浏览器如此），故这里直接画到行框之外。
     *
     * 「该元素自身字体」的取面口径：以**盒首字**为探针字命中族栈里的面（`'H'` 会误落 Latin 面，
     * 中文盒因此少 0.4em；Chromium 同栈实测见 [paintInlineBoxes] 内注释）。
     *
     * ## `box-decoration-break: slice`（默认）
     *
     * `border-left` 只在元素**首个片段**、`border-right` 只在**末片段**；上/下边框每片段都有。
     * 「首/末片段」= 本行裁剪区间是否触到 run 的头/尾（跨行时自然只在头尾行成立）。
     *
     * @param baseY 基线绝对 y（与 [baselineY] 同口径）。
     */
    private fun paintInlineBoxes(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        /** 只画这些 run（null = 全部）。两趟绘制用：在流这一趟与已定位那一趟各传各的子集。 */
        only: Collection<orilumn.reader.engine.laying.InlineBoxRun>? = null,
    ) {
        val runs = if (only == null) line.inlineBoxRuns else line.inlineBoxRuns.filter { it in only }
        if (runs.isEmpty() || endExcl <= start) return
        val n = endExcl - start
        for (r in runs) {
            if (!r.visible) continue
            // E10：`visibility: hidden` 的行内盒不落墨（底色/边框一并隐去；推进量照旧，见 [InlineBoxRun]）。
            if (r.visibilityHidden) continue
            val s = r.start.coerceIn(start, endExcl)
            val e = r.endExclusive.coerceIn(start, endExcl)
            if (e <= s) continue
            val (lx, rx) = aligner.visibleSpan(placement, n, s - start, e - start) ?: continue
            // slice 语义：左边框只首片段、右边框只末片段。
            val firstFrag = s == r.start
            val lastFrag = e == r.endExclusive
            // 内容区（em 盒）高度：该元素自身字体（字号未设则回退行字号/族栈）。
            val sizePx = if (r.fontSizePx > 0f) r.fontSizePx else line.fontSizePx
            val famList = r.families.ifEmpty { line.families }
            // 探针字必须是**盒自己的首字**，不是固定的 `'H'`：`'H'` 会落到族栈里第一个
            // **含拉丁字形**的面（空栈/`sans-serif` 展开后都是 Helvetica，em 盒 = 1.0em），
            // 而盒里的字形是汉面画的（苹方/宋体，em 盒 = 1.4em）⇒ 中文盒矮掉约 8px
            // （实测 18.5 vs 25.9：样例1.2 实测盒高 24，Chromium 同栈为 32，看起来「上下
            // padding 没了」——padding 一直在画，矮的是 em 盒）。取**盒首字**（不是片段首字）
            // ⇒ 跨行各片段同一面，与浏览器「一个盒一个字体」一致。
            val probeCp = line.text.getOrNull(r.start)?.code ?: 'H'.code
            val fm = glyphPainter.baseGlyphStyle(
                sizePx, famList, r.weight, r.italic, r.monospace,
                0xFF000000.toInt(), probeCp, line.tag,
            ).font.metrics
            val ascent = -fm.ascent
            val descent = fm.descent
            val pad = r.padding
            val bd = r.border
            // E12：行内相对定位偏移（本元素 + 行内祖先累积）整体平移盒与其中文字；
            // 块级祖先的偏移已在 `LayoutBox.relOffset*` 里平移整行，不在此重复。
            val top = baseY - ascent - pad.top - bd.top + r.relDy
            val bottom = baseY + descent + pad.bottom + bd.bottom + r.relDy
            val left = paintX + lx - pad.left - bd.left + r.relDx
            // 右缘：**末片段**的 `rx` 已经含 `padding.right + border.right`（[inlineBoxEdges] 把右边记在
            // 末字符位上，断行/落位/簇位轨同吃，见 `inlineBoxAdvanceEnabled`）。再补一遍就是
            // 「画比量宽」：盒边（以及它带的底色）往右压出 5px 盖住后文 —— 验收样书 V1 样例1.2
            // 「方框与后文的『后』部分重叠」即此（Chromium 实测：盒右缘 = 后文行首，其后 1px 空档）。
            // 闸关时 advance 里不含这段（回退纪律），照旧补上。
            val right = paintX + rx +
                (if (lastFrag && !inlineBoxAdvanceEnabled()) pad.right + bd.right else 0f) + r.relDx
            // E17：圆角按**本片段** border-box 尺寸解（`%` ⇒ `min(w,h)` 基准，与块盒
            // [CornerRadius.resolved] 同式钳制）。slice：切开端不是元素真角 ⇒ 只在真角保留半径
            // （首片段保左两角、末片段保右两角；整盒单片段即原样）。
            val radFull = r.borderRadius.resolved(
                (right - left).roundToInt(), (bottom - top).roundToInt(), r.borderRadiusPct,
            )
            val radii = if (firstFrag && lastFrag) radFull else slicedRadii(radFull, firstFrag, lastFrag)
            // 背景：铺满 border-box（默认 background-clip: border-box），圆角随形。
            r.bgHex?.let(::cssHexToArgb)?.let { argb ->
                val bgPaint = Paint().apply { color = withAlpha(argb, line.alpha) }
                if (radii.isSquare()) {
                    canvas.drawRect(Rect.makeLTRB(left, top, right, bottom), bgPaint)
                } else {
                    canvas.drawRRect(RRect.makeComplexLTRB(left, top, right, bottom, radii.skiaArray()), bgPaint)
                }
            }
            // 边框色：每边解析 → currentColor（该元素自身色）→ 叶墨色。
            fun edgeColor(edge: String?): Int =
                edge?.let(::cssHexToArgb) ?: r.colorHex?.let(::cssHexToArgb) ?: line.inkColor
            fun styleDraws(st: BorderStyle?): Boolean = st != BorderStyle.NONE
            val bs = r.borderStyles
            // E16/E17：边框两条路 ——
            //  a) 整盒（首末片段合一 ⇒ 四边都在）四边同宽、同色、全实线 + 圆角 ⇒ **描边环**：
            //     描边内缩半宽，环整体落在 border-box 内 —— 与方形时「边带画在 border-box 内」
            //     同位（块盒的环是居中描边、半宽越界，行内盒不跟着越）；
            //  b) 其余（异色/异宽/虚线点线/切片段）⇒ 逐边分段（E16 单源 [borderEdgeRects]）；
            //     圆角时先裁到圆角外形再落带，切口端与边带一律不出圆角轮廓。
            val ring = if (!radii.isSquare() && firstFrag && lastFrag) {
                uniformRing(r, ::edgeColor, radii, right - left, bottom - top)
            } else null
            if (ring != null) {
                val inset = ring.width / 2f
                val bp = Paint().apply {
                    color = withAlpha(ring.colorArgb, line.alpha)
                    mode = PaintMode.STROKE
                    strokeWidth = ring.width
                }
                canvas.drawRRect(
                    RRect.makeComplexLTRB(left + inset, top + inset, right - inset, bottom - inset, ring.radii),
                    bp,
                )
            } else {
                val rounded = !radii.isSquare()
                val saved = if (rounded) {
                    val s = canvas.save()
                    canvas.clipRRect(RRect.makeComplexLTRB(left, top, right, bottom, radii.skiaArray()), true)
                    s
                } else 0
                try {
                    val bp = Paint()
                    fun edge(
                        style: BorderStyle?,
                        width: Float,
                        colorEdge: String?,
                        axis: BorderEdgeAxis,
                        x0: Float,
                        x1: Float,
                        band0: Float,
                    ) {
                        if (width <= 0f || !styleDraws(style)) return
                        bp.color = withAlpha(edgeColor(colorEdge), line.alpha)
                        for (rc in borderEdgeRects(axis, x0, x1, band0, width, style ?: BorderStyle.SOLID, line.deviceScale)) {
                            canvas.drawRect(Rect.makeLTRB(rc.left, rc.top, rc.right, rc.bottom), bp)
                        }
                    }
                    edge(bs?.top, bd.top, r.borderColors?.top, BorderEdgeAxis.HORIZONTAL, left, right, top)
                    edge(bs?.bottom, bd.bottom, r.borderColors?.bottom, BorderEdgeAxis.HORIZONTAL, left, right, bottom - bd.bottom)
                    if (firstFrag) edge(bs?.left, bd.left, r.borderColors?.left, BorderEdgeAxis.VERTICAL, top, bottom, left)
                    if (lastFrag) edge(bs?.right, bd.right, r.borderColors?.right, BorderEdgeAxis.VERTICAL, top, bottom, right - bd.right)
                } finally {
                    if (rounded) canvas.restoreToCount(saved)
                }
            }
        }
    }

    /**
     * E17：圆角 + **整盒四边同宽同色实线** ⇒ 可画一条闭合描边环时的参数；
     * 不满足（异色/异宽/虚线/盒太窄）回 `null`，调用方回落逐边分段。
     * 判据与块盒 [orilumn.reader.engine.laying.BoxDrawer] 的同款判据同式（只把颜色解析口径
     * 换成「已折叠 currentColor 的 ARGB」，块盒侧比较的是色串，此处比较解析值等价）。
     *
     * @param boxW / boxH 本片段 border-box 尺寸：环要求四边都放得下，否则内缩后矩形反向 ⇒ 弃环。
     */
    private fun uniformRing(
        r: InlineBoxRun,
        colorOf: (String?) -> Int,
        radii: CornerRadius,
        boxW: Float,
        boxH: Float,
    ): InlineRing? {
        val w = r.border.top
        if (w <= 0f || w != r.border.right || w != r.border.bottom || w != r.border.left) return null
        if (boxW <= w || boxH <= w) return null
        val c = colorOf(r.borderColors?.top)
        if (c != colorOf(r.borderColors?.right) || c != colorOf(r.borderColors?.bottom) || c != colorOf(r.borderColors?.left)) return null
        val st = r.borderStyles
        val solid = { v: BorderStyle? -> (v ?: BorderStyle.SOLID) == BorderStyle.SOLID }
        if (!solid(st?.top) || !solid(st?.right) || !solid(st?.bottom) || !solid(st?.left)) return null
        // 描边路径的半径 = 外半径 − 半宽（环带 [r−w, r] 落在 border-box 内），钳 ≥0。
        return InlineRing(w, c, radii.grown(-w / 2f))
    }

    /** [uniformRing] 的产出：描边宽、色（已解析 currentColor）、内缩后的 8 元半径。 */
    private class InlineRing(val width: Float, val colorArgb: Int, val radii: FloatArray)

    /**
     * E17 的 slice 切角：`box-decoration-break: slice` 下片段只有元素**真角**带半径 ——
     * 切开端不是角（首片段保 tl/bl、末片段保 tr/br；两者齐备即原样，调用方已先行短路）。
     */
    private fun slicedRadii(r: CornerRadius, firstFrag: Boolean, lastFrag: Boolean): CornerRadius = CornerRadius(
        if (firstFrag) r.topLeft else 0f,
        if (lastFrag) r.topRight else 0f,
        if (lastFrag) r.bottomRight else 0f,
        if (firstFrag) r.bottomLeft else 0f,
    )

    /**
     * S5 逐字落墨：按 [placement] 的 x 逐字 `drawString`，段内样式由 `mergeBands` 供（不重写切段）。
     *
     * **下划线改为自绘**（原挂在 `TextStyle` 的 `DecorationStyle` 上）：
     * 按 CSS，`text-decoration` 覆盖整个行区间、与是否 JUSTIFY 无关，故用 [ulHits] 的区间并集
     * 画一条线 —— 与 `TextStyle` 版在字距/换面上更稳（装饰线不随字形盒跳变）。
     *
     * **字阴影**：原挂 `TextStyle.addShadow`（随段整形）。逐字路径下 `Paint` 不带阴影，
     * 故先按阴影参数画一遍偏移的同色字作底，再画正字 —— CSS `text-shadow` 的常规实现
     * （blur 用多层近似，量级为 `blur/2`）。
     */
    private fun paintGlyphs(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        isHidden: (Int, Int) -> Boolean,
        decRuns: List<TextDecorationRun>,
        /** 首字下沉的基线位移（px；0 = 无）。判区间在本函数内做（[DrawLine.capFloat] + `range.first == 0`）。 */
        capDy: Float = 0f,
        /**
         * [placement] 的起点（创建它时的区间起点）。
         *
         * 一般 = [start]（整段一次画完）。**同一行分两趟画**时（已定位行内盒压层，见调用处）
         * 本趟的 [start] 大于整体起点 ⇒ 槽位按 `start − placementStart` 偏移索引。
         */
        placementStart: Int = start,
    ) {
        if (endExcl <= start) return
        val n = endExcl - start
        val slotOff = start - placementStart
        // S5b：含拉丁字母的行改用 Skia 簇位（保 kerning + fi/fl 连字），纯 CJK 行零成本走 Aligner 的 x。
        // **逐行判据、不混用**：一行要么全用簇位、要么全用 Aligner —— 两套 x 混在一行里就是错位。
        //
        // 簇位轨是「未拉伸的自然轨」，拉伸 / 对齐偏移必须由 Aligner 出、[graftKerningOnto] 嫁接。
        // 全部推导、实测数据、不变式与两个禁忌都写在那里（真书实测：修复前 965 行右缘缺口 >1px、
        // 最大 17.29px；18 行右溢最大 6.116px；修复后两者归零）。
        val graft: ClusterTrackGraft? =
            if (!kerningTable.needsClusters(line.text, start, endExcl)) null else {
                // originX 必须 0：本轨要与 Placement.advs 同坐标系，才能逐字相减出 kerning 增量。
                kerningTable.clusterXs(
                    line.text, start, endExcl, line.fontSizePx, line.lineHeightRatio,
                    line.tag, line.families, line.weight, line.italic, line.monospace,
                    line.fontRuns, originX = 0f, letterSpacingEm = line.letterSpacingEm,
                    // 混排字距：必须传与量宽侧同一个 em，否则 [graftKerningOnto] 会把注入的间隙
                    // 当成「整形收紧量」吃掉（该方法的 KDoc 有完整推导）。
                    cjkLatinSpacingEm = line.cjkLatinSpacingEm,
                    // 行内盒横向推进：同理必须与量宽侧同一份，否则被当收紧量抹掉。
                    inlineBoxRuns = line.inlineBoxRuns,
                )?.let { cnat ->
                    graftKerningOnto(
                        placement, cnat, line.text, start,
                        line.fontSizePx, line.letterSpacingEm, line.fontRuns,
                        // 只有 JUSTIFY 且非末行才把收紧量补回右缘；其余传 NaN = 「不补」。
                        if (line.alignment == TextAlign.JUSTIFY && endExcl < line.text.length) {
                            line.lineWidthPx.toFloat()
                        } else {
                            Float.NaN
                        },
                        // 槽位分类要与主拉伸同源（同 em）；不传则级 2（中英注入间隙）恒为空。
                        line.cjkLatinSpacingEm,
                        // 本趟只画整行里的一段（同一行分两趟）⇒ 槽位按段落起点偏移索引。
                        off = slotOff, count = n,
                    )
                }
            }
        val xs: FloatArray = graft?.xs ?: if (slotOff == 0) placement.xs else FloatArray(n) { placement.xs[slotOff + it] }
        val kernEndDelta = graft?.endDelta ?: 0f
        // E12：行内相对定位的**逐字**位移表（按全文本绝对下标；无行内偏移叶回 null 零开销）。
        // 每个字符的位移 = 覆盖它的所有行内盒 run 的 relDx/relDy 之和（嵌套盒自然叠加）。
        val relRuns = line.inlineBoxRuns
        val relDx: FloatArray?
        val relDy: FloatArray?
        if (relRuns.none { it.relDx != 0 || it.relDy != 0 }) {
            relDx = null
            relDy = null
        } else {
            val n2 = line.text.length
            val ax = FloatArray(n2)
            val ay = FloatArray(n2)
            for (r in relRuns) {
                if (r.relDx == 0 && r.relDy == 0) continue
                for (k in r.start.coerceAtLeast(0) until r.endExclusive.coerceAtMost(n2)) {
                    ax[k] += r.relDx
                    ay[k] += r.relDy
                }
            }
            relDx = ax
            relDy = ay
        }
        // 段样式缓存：同一 (色, 面, 位移) 只解析一次 Font（matchFamilyStyle 是 native 调用）。
        val fontCache = HashMap<Any, GlyphPainter.GlyphStyle>()
        val fonts = object : FontResolver {
            override fun resolve(
                cp: Int, key: Any, sizePx: Float, fam: List<String>, wt: Int, ital: Boolean,
                mono: Boolean, tag: String?,
            ): GlyphPainter.GlyphStyle = fontCache.getOrPut(key) {
                glyphPainter.baseGlyphStyle(sizePx, fam, wt, ital, mono, 0xFF000000.toInt(), cp, tag)
            }
        }

        // 首字下沉：本行含首字时，首字那几字按**下沉盒自己的基线**落墨（不跟正文基线）。
        val capRange: IntRange? = line.capFloat
            ?.takeIf { line.range.first == 0 }
            ?.let { 0 until it.endExclusive.coerceAtMost(endExcl) }
            ?.takeIf { it.first < it.last + 1 }
        val shadow = line.textShadow
        // **切段每行算一次**（不是每字一次，见 [drawGlyphPass] 的性能注记）；连字符也要用末字那段。
        val bands = mergeBands(line, start, endExcl)
        // 先画阴影层（偏移同色），再画正字 —— CSS text-shadow 的常规两层近似。
        if (shadow != null) {
            val sc = shadow.colorHex?.let(::cssHexToArgb) ?: 0xFF000000.toInt()
            drawGlyphPass(canvas, line, placement, paintX, baseY, start, endExcl, isHidden, shadow.dx, shadow.dy, sc, true, fonts, xs, bands, relDx, relDy, capRange, capDy)
        }
        drawGlyphPass(canvas, line, placement, paintX, baseY, start, endExcl, isHidden, 0f, 0f, 0, false, fonts, xs, bands, relDx, relDy, capRange, capDy)

        // **行尾连字符**（`hyphens: auto` 的音节断点 / `&shy;` 断行显形）。
        //
        // `&shy;` 的槽位是它自己那个字位 ⇒ `adv[shySlot]` 已含连字符宽，但逐字循环画的是
        // `drawString("\u00AD")`（零宽，**什么也画不出来**）⇒ 这时要补画一个真 `-`。
        // K-L 断词没有槽位 ⇒ 连字符在行末之外，`hyphenX = trailStartX − hyphenWidth`。
        // 两种情况同一个式子，不必分支（[LineAligner.hyphenXOf]）。
        if (placement.hyphenWidth > 0f) {
            val hx = aligner.hyphenXOf(placement)
            val lastIdx = endExcl - 1
            val band = bands.lastOrNull { it.start <= lastIdx && lastIdx < it.end }
            if (hx >= 0f && band != null && lastIdx >= start) {
                // face/墨色/基线位移取**行末字所属的那段**：`<code>` 里的断词要按等宽面画连字符。
                val run = band.font
                val sizePx = run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx
                val famList = run?.families ?: line.families
                val wt = run?.weight ?: line.weight
                val ital = run?.italic ?: line.italic
                val key = glyphPainter.cacheKey(famList, wt, ital, sizePx, HYPHEN_GLYPH.code)
                val gs = fonts.resolve(
                    HYPHEN_GLYPH.code, key, sizePx, famList, wt, ital,
                    run?.monospace ?: line.monospace, run?.tag ?: line.tag,
                )
                val font = gs.font
                val y = baseY - (band.shiftEm ?: 0f) * line.fontSizePx
                // 与逐字轨同一个 kerning 增量：簇位行末比 Aligner 略紧，连字符跟着一起挪，
                // 否则 JUSTIFY 行会出现「末字与连字符之间凭空一个空当」（实测 ≤ 几 px）。
                val hxK = hx + kernEndDelta
                val paint = org.jetbrains.skia.Paint().apply { color = withAlpha(band.argb ?: line.inkColor, line.alpha) }
                // 合成斜体（`font-style: italic` 但无斜体面）：连字符同属该 run，必须一起斜切。
                drawOblique(canvas, y, gs.oblique) {
                    canvas.drawString(HYPHEN_GLYPH.toString(), paintX + hxK, y, font, paint)
                }
                // 阴影层同样要补一个，否则带 text-shadow 的行连字符没有影。
                if (shadow != null) {
                    val sp = org.jetbrains.skia.Paint().apply {
                        color = withAlpha(shadow.colorHex?.let(::cssHexToArgb) ?: 0xFF000000.toInt(), line.alpha)
                    }
                    drawOblique(canvas, y + shadow.dy, gs.oblique) {
                        canvas.drawString(HYPHEN_GLYPH.toString(), paintX + hxK + shadow.dx, y + shadow.dy, font, sp)
                    }
                }
            }
        }

        // 文本装饰（第三刀）：三种线型各画各的，颜色/粗细/线型样式/skip-ink 见 [paintDecorations]。
        paintDecorations(canvas, line, placement, paintX, baseY, start, endExcl, decRuns, fonts, bands, xs)
    }

    /**
     * 文本装饰落墨（渲染层）：`underline` / `overline` / `line-through` 三种线型**各画各的**，
     * 颜色 / 粗细 / 线型样式（实线·双线·点线·虚线·波浪）/ `text-decoration-skip-ink` 全参数。
     *
     * ## 位置与粗细（对齐浏览器：取字体度量）
     *
     * 装饰线的位置与 `auto` 粗细取**声明元素那张面**的度量（`FontMetrics`）：
     * - `underline`：`underlinePosition`（Skia 约定为「线顶距基线」的正值）与 `underlineThickness`；
     * - `line-through`：**中心 = `(ascent+descent)/2`**（Chromium 同式；实测以 `H` 底边定基线，
     *   线顶 = 基线 − 中心 − 半线宽）。不是字体的 `strikeoutPosition`——CoreText 给 Chrome 的
     *   口径与 Skia 的字段值不同（本字体 −8.0 vs −5.7px）；
     * - `overline`：无对应度量，取 `ascent` 上缘（浏览器同处：升部之上）。
     *
     * **度量面 = 被装饰文字的实际面**：取装饰区间首字的码本，走与逐字落墨同一条解析
     * （同 band、同 key）。固定码本 `'H'` 会解析到拉丁面，与正文 CJK 面度量差 0.1em 以上。
     *
     * 度量缺失（字体没给）时才回退 em 常量。**不读行墨色命名**：装饰色 = 声明元素解析后的颜色
     * （`currentColor` 已在收集期解析），无颜色声明才落回叶墨色。
     *
     * ## skip-ink（initial `auto`）
     *
     * 与浏览器**同一套原语**：逐字取**字形轮廓**与装饰线所在横带求交，在交点区间处断开
     * （不是整字跳过）—— 下划线遇到降部（`g` / `p` / `y`）断开、上划线遇到升部断开。
     * 「不许切墨」的字（汉字/假名、`/ \ _`、Hangul）根本不进取样集，故整段穿过、不断线。
     * `line-through` 按规范**不**做 skip-ink。细节见 [inkCuts]。
     */
    private fun paintDecorations(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        decRuns: List<TextDecorationRun>,
        fontFor: FontResolver,
        bands: List<Band>,
        xs: FloatArray,
    ) {
        if (decRuns.isEmpty() || endExcl <= start) return
        val n = endExcl - start
        for (r in decRuns) {
            val lo = (r.start - start).coerceIn(0, n)
            val hi = (r.endExclusive - start).coerceIn(lo, n)
            if (hi <= lo) continue
            for (paint in r.paints) {
                if (paint.lines.isEmpty()) continue
                val fs = if (paint.fontSizePx > 0f) paint.fontSizePx else line.fontSizePx
                val fams = paint.families.ifEmpty { line.families }
                // **度量面 = 被装饰文字真正用的那张面**（不是固定码本 `'H'` 解析出的面）。
                //
                // 旧写法固定用 `DECORATION_METRIC_CP = 'H'`：族栈里拉丁面先命中 ⇒ 拿到的
                // 是**拉丁面**的 ascent/descent/strikeout/underline，而正文是 CJK 面
                // （族栈回退到宋体/思源）。两套度量可以差整整 0.1em：
                // 实测默认栈 `'H'` 面 a≈-14.7/d≈5.2，正文面 a≈-21.3/d≈+5.3（18.5px），
                // 删除线因此比 Chromium 低 ~2px（用户实测「偏下」）。
                //
                // 取 `lo` 那个字的**码本**走与逐字落墨完全同一条解析（同 key、同 band 字段），
                // 保证「量画同源」：面相同 ⇒ 度量相同。
                val metricBand = bands.lastOrNull { it.start <= lo && lo < it.end }
                val mRun = metricBand?.font
                val mSize = mRun?.fontPxOr(fs) ?: fs
                val mFams = mRun?.families ?: fams
                val mWt = mRun?.weight ?: paint.weight
                val mItal = mRun?.italic ?: paint.italic
                val mMono = mRun?.monospace ?: paint.monospace
                val mTag = mRun?.tag ?: line.tag
                val cp = codePointAt(line.text, lo)?.cp ?: DECORATION_METRIC_CP
                val key = glyphPainter.cacheKey(mFams, mWt, mItal, mSize, cp)
                val gs = fontFor.resolve(cp, key, mSize, mFams, mWt, mItal, mMono, mTag)
                val fm = gs.font.metrics
                val color = withAlpha(paint.colorHex?.let(::cssHexToArgb) ?: line.inkColor, line.alpha)
                val left = xs.getOrNull(lo) ?: continue
                val right = (xs.getOrNull(hi - 1) ?: continue) + (placement.advs.getOrNull(hi - 1) ?: 0f)
                if (right <= left) continue
                for (kind in DECORATION_PAINT_ORDER) {
                    if (kind !in paint.lines) continue
                    val t = when {
                        paint.thicknessPx != null -> maxOf(paint.thicknessPx!!, DECORATION_MIN_THICKNESS_PX)
                        else -> maxOf(fm.underlineThickness ?: (fs * DECORATION_AUTO_THICKNESS_EM), DECORATION_MIN_THICKNESS_PX)
                    }
                    // 线中心相对基线的偏移（画布 y 向下为正）。度量按「线顶」给，故加半个线宽取中心。
                    val centerRel = when (kind) {
                        DecorationLine.UNDERLINE ->
                            (fm.underlinePosition?.takeIf { it > 0f } ?: (fs * DECORATION_UNDERLINE_FALLBACK_EM)) + t / 2f
                        DecorationLine.LINE_THROUGH ->
                            // **中心 = (ascent+descent)/2，与 Chromium 同式**（不是字体的 strikeout 度量）。
                            // 实测（/tmp/gt strike-offsets，Chromium @font-face 加载同一字体文件
                            // 屏显臻宋 18.5px/LH1.5，以 `H` 底边定基线）：线顶 = 基线 −8.5px
                            // = 中心 −8.0px − t/2(0.5) ⇒ 中心正是 (ascent+descent)/2。
                            // Skia 的 `strikeoutPosition`（−5.7px）与 CoreText 给 Chrome 的口径不同，不用它。
                            (fm.ascent + fm.descent) / 2f
                        DecorationLine.OVERLINE -> fm.ascent - t / 2f
                    }
                    val cuts = if (paint.skipInk && kind != DecorationLine.LINE_THROUGH) {
                        inkCuts(line, xs, start, lo, hi, fontFor, bands, centerRel, t)
                    } else {
                        emptyList()
                    }
                    drawDecoration(canvas, paintX, left, right, baseY + centerRel, t, paint.style, color, cuts, kind)
                }
            }
        }
    }

    /**
     * 一条装饰线的落墨（含线型样式与 skip-ink 切口）。切口与 [left]/[right] 同为 placement 坐标系。
     *
     * [y] 是**线中心**（相对基线已含在内）。双线与波浪在 Chromium 里按线型各自偏移
     * （`TextDecorationInfo::SetLineData`：偏移 = 线宽 + 1px，删除线取整，波浪线同偏移但
     * 删除线不偏移），故需要 [kind] 参与。
     */
    private fun drawDecoration(
        canvas: Canvas,
        paintX: Float,
        left: Float,
        right: Float,
        y: Float,
        t: Float,
        style: DecorationStyle,
        color: Int,
        cuts: List<ClosedFloatingPointRange<Float>>,
        kind: DecorationLine,
    ) {
        // 双线第二条线、波浪线相对首线的纵向偏移（Chromium `DoubleOffset()`）。
        val doubleDy = when (kind) {
            DecorationLine.UNDERLINE -> t + 1f
            DecorationLine.OVERLINE -> -(t + 1f)
            DecorationLine.LINE_THROUGH -> kotlin.math.floor(t + 1f)
        }
        for ((a, b) in subtractCuts(left, right, cuts)) {
            val x0 = paintX + a
            val x1 = paintX + b
            when (style) {
                DecorationStyle.SOLID -> drawDecorationRect(canvas, x0, x1, y, t, color)
                DecorationStyle.DOUBLE -> {
                    // Chromium 画法：两条**与实线同宽**的填充矩形（各按 SnapYAxis 整像素化），
                    // 第二条整体偏移「线宽 + 1px」⇒ 两条线之间恰好留 1px 空隙。
                    drawDecorationRect(canvas, x0, x1, y, t, color)
                    drawDecorationRect(canvas, x0, x1, y + doubleDy, t, color)
                }
                DecorationStyle.DOTTED -> {
                    val tt = kotlin.math.round(t).coerceAtLeast(1f)
                    if (tt >= 4f) {
                        // 粗点线（Chromium StrokeIsDashed 为假的分支）：圆点直径 = 线宽、
                        // 两端各内缩半个线宽，虚线段长 0 + 圆端帽即圆点。
                        drawDecorationStroke(
                            canvas, x0 + tt / 2f, x1 - tt / 2f, y, tt, color,
                            floatArrayOf(0f, 2f * tt - 0.01f), PaintStrokeCap.ROUND,
                        )
                    } else {
                        // 细点线（线宽 ≤ 3）：Chromium 与虚线同一路径，矩形短划 + 平头端帽。
                        drawDecorationStroke(canvas, x0, x1, y, tt, color, floatArrayOf(tt, tt))
                    }
                }
                DecorationStyle.DASHED -> {
                    val tt = roundHalfUp(t).coerceAtLeast(1f)
                    // Chromium DashLengthRatio/DashGapRatio：线宽 ≥ 3 取 2:1，否则 3:2。
                    val on = if (tt >= 3f) 2f else 3f
                    val off = if (tt >= 3f) 1f else 2f
                    drawDecorationStroke(canvas, x0, x1, y, tt, color, floatArrayOf(tt * on, tt * off))
                }
                DecorationStyle.WAVY -> {
                    // Chromium：波浪中线 = 线顶 + 0.5 + 偏移（删除线不偏移），即相对线中心
                    // 再移「线宽/2 + 1.5」（删除线为「-线宽/2 + 0.5」）。
                    val shift = if (kind == DecorationLine.LINE_THROUGH) 0f else doubleDy
                    drawDecorationWave(canvas, x0, x1, y - t / 2f + 0.5f + shift, t, color)
                }
            }
        }
    }

    /**
     * 一条水平线笔画（点线/虚线：Skia 的 dash 相位 0）。
     *
     * 中线按 Chromium `GetSnappedPointsForTextLine` 吸附：先 `floor(线中心)` 落到整像素，
     * 取整后**奇数宽**再下移半像素让描边骑在像素边界上（偶数宽居中即恰好占整数行）。
     */
    private fun drawDecorationStroke(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        t: Float,
        color: Int,
        dash: FloatArray?,
        cap: PaintStrokeCap = PaintStrokeCap.BUTT,
    ) {
        if (x1 <= x0 || t <= 0f) return
        val yDraw = kotlin.math.floor(y) + if (t.toLong() % 2 == 1L) 0.5f else 0f
        val paint = Paint().apply {
            this.color = color
            mode = PaintMode.STROKE
            strokeWidth = t
            strokeCap = cap
            if (dash != null) pathEffect = PathEffect.makeDash(dash, 0f)
        }
        canvas.drawLine(x0, yDraw, x1, yDraw, paint)
        paint.close()
    }

    /** 实线/双线用填充矩形绘制（避免亚像素描边，贴近 Chromium SnapYAxis 行为）。 */
    private fun drawDecorationRect(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        yCenter: Float,
        t: Float,
        color: Int,
    ) {
        if (x1 <= x0 || t <= 0f) return
        // Chromium SnapYAxis：从**未取整的线顶**取 `floor(线顶 + 0.5)`，高取整且不小于 1。
        val h = maxOf(kotlin.math.floor(t), 1f)
        val ySnapped = kotlin.math.floor(yCenter - t / 2f + 0.5f)
        val paint = Paint().apply {
            this.color = color
            mode = PaintMode.FILL
        }
        canvas.drawRect(x0, ySnapped, x1, ySnapped + h, paint)
        paint.close()
    }

    /**
     * 波浪线（`text-decoration-style: wavy`）：Chromium `MakeWave` 形状 ——
     * 线宽钳到 ≥1 后 `波长 = 1 + 2·round(2t + 0.5)`、`控制点距 = 0.5 + round(3t + 0.5)`，
     * 每个波长一段三次贝塞尔，路径起点相位 -1 个波长、多画 1 个波长收尾，
     * 再**横向裁剪**到本片段（[cy] 即波浪中线，调用方已含 Chromium 的纵向偏移与半像素）。
     */
    private fun drawDecorationWave(canvas: Canvas, x0: Float, x1: Float, cy: Float, t: Float, color: Int) {
        if (x1 <= x0 || t <= 0f) return
        val clamped = maxOf(1f, t)
        val waveLen = 1f + 2f * roundHalfUp(2f * clamped + 0.5f)
        val cp = 0.5f + roundHalfUp(3f * clamped + 0.5f)
        val builder = PathBuilder()
        val startX = x0 - waveLen
        builder.moveTo(startX, cy)
        var x = startX
        val endX = x1 + waveLen
        while (x < endX) {
            val xe = x + waveLen
            val cx = x + waveLen * 0.5f
            builder.cubicTo(cx, cy + cp, cx, cy - cp, xe, cy)
            x = xe
        }
        val path = builder.snapshot()
        val paint = Paint().apply {
            this.color = color
            mode = PaintMode.STROKE
            strokeWidth = t.coerceAtLeast(DECORATION_MIN_THICKNESS_PX)
            strokeCap = PaintStrokeCap.BUTT
        }
        // 横向裁到片段（两端各多画 1 个波长正是 Chromium 的 overhang 再裁剪）；纵向留足振幅。
        val ext = 0.289f * cp + t + 2f
        canvas.save()
        canvas.clipRect(Rect.makeXYWH(x0, cy - ext, x1 - x0, 2f * ext))
        canvas.drawPath(path, paint)
        canvas.restore()
        path.close()
        builder.close()
        paint.close()
    }

    /** 从 [left, right] 里挖掉 [cuts]（已排序或未排序都行），返回剩下的片段。 */
    private fun subtractCuts(left: Float, right: Float, cuts: List<ClosedFloatingPointRange<Float>>): List<Pair<Float, Float>> {
        if (cuts.isEmpty()) return listOf(left to right)
        val out = ArrayList<Pair<Float, Float>>(cuts.size + 1)
        var cursor = left
        for (c in cuts) {
            val a = c.start.coerceIn(left, right)
            val b = c.endInclusive.coerceIn(left, right)
            if (b <= a) continue
            if (a > cursor) out.add(cursor to a)
            if (b > cursor) cursor = b
        }
        if (right > cursor) out.add(cursor to right)
        return out
    }

    /**
     * skip-ink 切口：与浏览器同一套原语 —— **字形轮廓**（不是墨迹盒）与装饰横带求交，
     * 回报每个交点区间。三处逐条对齐 Chromium（M134：
     * `third_party/blink/renderer/core/paint/text_painter.cc` 的 `ClipDecorationsStripe`
     * 与 `PaintDecorationLine`）：
     *
     * 1. 横带 = 装饰线矩形**纵向内缩 0.5px**（源码注释 `inflate by -0.5`：0.5px 以内的相交
     *    忽略），即 `[center − t/2 + 0.5, center + t/2 − 0.5]`；`t = 1px` 时带退化成一条线。
     * 2. **不许切墨**的字直接从取样集里剔掉（[canSkipInk]）—— 这就是「下划线穿过汉字
     *    不断线」的全部原因，与墨迹几何无关。
     * 3. 每个交点区间左右各扩 `min(t, 13px)`（`clip_rect.Outset(VH(1.0, dilation))` +
     *    `kDecorationClipMaxDilation = 13`）；竖向不再扩，带本身已内缩。
     *
     * 相交判据走 [org.jetbrains.skia.TextBlob.getIntercepts]（Skia `SkTextBlob::getIntercepts`，
     * 亦即 Blink `Font::GetTextIntercepts`）：同面的一串字合成一个 blob 一次取回，
     * 位置与断行/落墨同源（[xs] 是 placement 坐标），切口与 [left]/[right] 同坐标系。
     */
    private fun inkCuts(
        line: DrawLine,
        xs: FloatArray,
        start: Int,
        lo: Int,
        hi: Int,
        fontFor: FontResolver,
        bands: List<Band>,
        centerRel: Float,
        t: Float,
    ): List<ClosedFloatingPointRange<Float>> {
        val bandTop = centerRel - t / 2f + DECORATION_INK_BAND_INSET_PX
        val bandBot = centerRel + t / 2f - DECORATION_INK_BAND_INSET_PX
        // t 已由调用侧保底 1px，带非负；真退化成空带时按「无相交」处理。
        if (bandBot < bandTop) return emptyList()
        val dilation = minOf(t, DECORATION_CLIP_MAX_DILATION_PX)
        val out = ArrayList<ClosedFloatingPointRange<Float>>(4)
        var k = lo
        // 同面的一串字攒一个 blob（Skia 的拦截按 blob 内的 run 取），换面就结算一次。
        // 不许切墨的字不入 blob（位置是显式的，缺一个不影响其余字的 x）。
        var curFont: Font? = null
        var glyphs = ShortArray(0)
        var posX = FloatArray(0)
        var n = 0
        fun flush() {
            if (n == 0) return
            val blob = TextBlobBuilder()
                .appendRunPosH(curFont!!, glyphs.copyOf(n), posX.copyOf(n), 0f, null)
                .build()
            n = 0
            if (blob == null) return
            try {
                val iv = blob.getIntercepts(bandTop, bandBot) ?: return
                var i = 0
                while (i + 1 < iv.size) {
                    val a = iv[i] - dilation
                    val b = iv[i + 1] + dilation
                    if (b > a) out.add(a..b)
                    i += 2
                }
            } finally {
                blob.close()
            }
        }
        while (k < hi) {
            val abs = start + k
            if (abs >= line.text.length) break
            val at = codePointAt(line.text, abs)
            val xHere = xs.getOrNull(k)
            if (at.isLowSurrogateTail || xHere == null) {
                k += at.units
                continue
            }
            if (canSkipInk(at.cp)) {
                val run = bands.lastOrNull { it.start <= abs && abs < it.end }?.font
                val sizePx = run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx
                val famList = run?.families ?: line.families
                val wt = run?.weight ?: line.weight
                val ital = run?.italic ?: line.italic
                val key = glyphPainter.cacheKey(famList, wt, ital, sizePx, at.cp)
                val gs = fontFor.resolve(at.cp, key, sizePx, famList, wt, ital, run?.monospace ?: line.monospace, run?.tag ?: line.tag)
                if (gs.font !== curFont) {
                    flush()
                    curFont = gs.font
                }
                if (n == glyphs.size) {
                    val cap = maxOf(8, n * 2)
                    glyphs = glyphs.copyOf(cap)
                    posX = posX.copyOf(cap)
                }
                glyphs[n] = gs.font.getUTF32Glyph(at.cp)
                posX[n] = xHere
                n++
            }
            k += at.units
        }
        flush()
        return out
    }

    /** 段内面解析（抽出成接口：inline 函数不能收函数类型参数）。 */
    /**
 * 逐字落墨的**段内样式解析**（渲染层）。
 *
 * 返回 [GlyphPainter.GlyphStyle] 而非裸 [org.jetbrains.skia.Font]：合成斜体是**画布变换**
 * （Skia 的 `Font` 无斜体属性），必须与面**一起**交给绘制侧，逐字轨才能对同一段施加剪切。
 */
private interface FontResolver {
        /**
         * **按码本**解析面（`cp`）：逐字绘制时不同码本可能落到族栈里不同的面
         * （CJK 落宋体、Latin 落 Times），故缓存键**必须含码本**——
         * 少这一点就是「一个字用错面」，肉眼可见且不报错。
         */
        fun resolve(
            cp: Int, key: Any, sizePx: Float, fam: List<String>, wt: Int, ital: Boolean,
            mono: Boolean, tag: String?,
        ): GlyphPainter.GlyphStyle
    }

    /** 单趟逐字绘制（阴影层与正字层各一趟）。 */
    private fun drawGlyphPass(
        canvas: Canvas,
        line: DrawLine,
        placement: LineAligner.Placement,
        paintX: Float,
        baseY: Float,
        start: Int,
        endExcl: Int,
        isHidden: (Int, Int) -> Boolean,
        dx: Float,
        dy: Float,
        overrideInk: Int,
        isShadowPass: Boolean,
        fontFor: FontResolver,
        xs: FloatArray = placement.xs,
        preBands: List<Band>? = null,
        relDx: FloatArray? = null,
        relDy: FloatArray? = null,
        /** 首字下沉：首字区间（叶文本坐标，null = 本行无首字）。 */
        capRange: IntRange? = null,
        /** 首字下沉：首字基线相对正文基线的位移（px，已含下沉盒自己的半行距）。 */
        capDy: Float = 0f,
    ) {
        if (endExcl <= start) return
        // **切段每行算一次**（不是每字一次）。第一版写成 `mergeBands(line, i, i+1).firstOrNull()`
        // 在逐字循环里，等于每字重建整行的 band 列表 —— JUSTIFY 行实测 13.00ms vs
        // LEFT 行 4.73ms（同文本、同字号），**拉伸不该让绘制慢 2.7 倍**，那全是这份重复计算。
        // 走游标：band 列表已按区间排序且无缝，顺序扫即可。
        // （[preBands] 由 [paintGlyphs] 一次算好传进来：行尾连字符也要用末字那段。）
        val bands = preBands ?: mergeBands(line, start, endExcl)
        var bandIdx = 0
        var i = start
        // ---- 批处理（渲染层·落墨）--------------------------------------------
        // 逐字 `drawString` + 逐字新建 `Paint` 是「一屏 1000 字 = 2000 次分配/JNI」的来源
        // （Linux 实测单屏栅格 2.2s；平板约 0.2s）。改为：**连续「同面 + 同色 + 同基线 +
        // 同斜切」的字符合成一个 TextBlob 一次 `drawTextBlob`**。
        //
        // 像素不变性：glyph 取自同一张面（`font.getUTF32Glyphs`，与 drawString 内部同源），
        // 位置取自同一份 `xs` + 同一个 `y`，Paint 只改 color 不改其它状态 ⇒ 同一 glyph
        // 落在同一像素位置。验收走 P5cOffscreenCorpusTest 落 PNG 逐像素 A/B。
        //
        // 批的切分键：**面 / 墨色 / 基线 y / 斜切标志**四者任一不同即换批
        // （换面与换色换 band，换基线换 band 的 shiftEm，换斜切换 canvas 变换）。
        val batchPaint = Paint()
        var batchFont: Font? = null
        var batchInk = 0
        var batchOblique = false
        var batchY = 0f
        var batchCps: ArrayList<Int>? = null
        var batchXs: ArrayList<Float>? = null

        fun flushBatch() {
            val f = batchFont
            val cps = batchCps
            val xArr = batchXs
            batchFont = null
            batchCps = null
            batchXs = null
            if (f == null || cps == null || xArr == null || cps.isEmpty()) return
            // **一组的字形一次 native 取回**（`getUTF32Glyphs(IntArray)`），不是逐字一次：
            // 逐字 JNI + 逐字 IntArray 分配是批量 drawTextBlob 之后剩下的最大单项开销。
            val gids = f.getUTF32Glyphs(cps.toIntArray())
            val pos = Array(cps.size) { k -> Point(xArr[k], batchY) }
            val blob = TextBlobBuilder().appendRunPos(f, gids, pos, null).build() ?: return
            batchPaint.color = batchInk
            // 合成斜体（`font-style: italic` 但族里没有斜体面）：基线处剪切。
            // 阴影层 `dy` 也进剪切中心，否则影子是斜的而字是正的（反之亦然）。
            drawOblique(canvas, batchY, batchOblique) {
                // blob 里的点已是绝对坐标（appendRunPos 收的就是 paintX+x+dx / y），
                // 故偏移传 0,0（skiko 的签名是 drawTextBlob(blob, x, y, paint)，没有两参重载）。
                canvas.drawTextBlob(blob, 0f, 0f, batchPaint)
            }
            blob.close()
        }

        while (i < endExcl) {
            // **码本单源**（见 [codePointAt]）：代理对必须整对取面、整对绘制。
            // 按 `text[i].code` 取 ⇒ emoji 被拆成两个**孤立代理** ⇒ 没有任何字体有它们的字形
            // ⇒ 画出来是**两个豆腐块**（真机报「代码块里特殊字符乱码」的根因之一）。
            val at = codePointAt(line.text, i)
            val units = at.units
            val local = i - start
            val x = xs.getOrNull(local)
            // 游标推进到覆盖 i 的那一段（band 之间有「无 run 覆盖」的间隙，band 为 null）。
            while (bandIdx < bands.size && bands[bandIdx].end <= i) bandIdx++
            val band = bands.getOrNull(bandIdx)?.takeIf { i >= it.start && i < it.end }
            val hidden = isHidden(i, i + units)
            // 代理对的低位代理尾**不落墨**：它已随高位代理整对画过（见上方 units）。
            // 软连字符同样永不落墨：`&shy;` 是「不换行时看不见、换行时才显形」的占位符。
            // 实测它在 STSong 里有真字形（glyph 271），不跳过就会在**没断行**的行里画出一道杠。
            // 断行显形的那一个由 [paintGlyphs] 在行尾补画真 `-`（含 SHY 槽位复用的情况）。
            if (x != null && !hidden && !at.isLowSurrogateTail && line.text[i] != SOFT_HYPHEN) {
                val run = band?.font
                val sizePx = run?.fontPxOr(line.fontSizePx) ?: line.fontSizePx
                val cp = at.cp
                val famList = run?.families ?: line.families
                val wt = run?.weight ?: line.weight
                val ital = run?.italic ?: line.italic
                val mono = run?.monospace ?: line.monospace
                val rtag = run?.tag ?: line.tag
                // 缓存键含码本：CJK 与 Latin 会落到族栈里不同的面（少这一点 = 一个字用错面）。
                val key = glyphPainter.cacheKey(famList, wt, ital, sizePx, cp)
                val gs = fontFor.resolve(cp, key, sizePx, famList, wt, ital, mono, rtag)
                val argb = if (isShadowPass) overrideInk else withAlpha(band?.argb ?: line.inkColor, line.alpha)
                // 行内基线位移（Skia 正值下移，故原点上移）。
                val shiftEm = band?.shiftEm ?: 0f
                // E12：行内相对定位的逐字位移（不进退格化/断行，只平移落墨）。
                val rdx = relDx?.getOrNull(i) ?: 0f
                val rdy = relDy?.getOrNull(i) ?: 0f
                val capShift = if (capRange != null && i in capRange) capDy else 0f
                val y = baseY - shiftEm * line.fontSizePx + dy + rdy + capShift
                // 代理对整对入批（units=2）；普通字符 units=1，行为与原来逐字版完全一致。
                onGlyphCp?.invoke(cp, units)
                // 换批判定：四要素任一不同即先落地上批。
                if (batchFont !== gs.font || batchInk != argb || batchY != y || batchOblique != gs.oblique) {
                    flushBatch()
                    batchFont = gs.font
                    batchInk = argb
                    batchOblique = gs.oblique
                    batchY = y
                    batchCps = ArrayList()
                    batchXs = ArrayList()
                }
                // 字形留到 flush 时按整批一次取（见 flushBatch 的注释）。
                batchCps!!.add(cp)
                batchXs!!.add(paintX + x + dx + rdx)
            }
            i += units
        }
        flushBatch()
    }

    /**
     * 该行是否为段落末行（末行不拉伸，两端对齐的定义本身）。
     *
     * 绘制侧只看本 [DrawLine] 无法知道段末，故取 `DrawLine` 已携带的判据：
     * [DrawLine.listMarker] 只挂首行 ⇒ 不能用；`range.last` 覆盖到叶文本末即末行。
     * 叶文本末由 [orilumn.reader.engine.skia.DrawLineBuilder] 传入的 range 全文本坐标决定，
     * 这里用「行区间右端 == 该行所属叶的文本长度」近似 —— 不精确时会退化为多拉伸末行，
     * 故判据保守：仅当整行右端 == 全文本长度时才算末行。
     */
    private fun isLastLineOf(line: DrawLine, endExcl: Int): Boolean = endExcl >= line.text.length

    /** 行区间内三套 runs（色 + face + 基线位移）合并后的 [lo, hi) 段序列：每段的肤色/face/位移恒定，供逐段渲染。 */
    private data class Band(val start: Int, val end: Int, val argb: Int?, val font: orilumn.reader.engine.css.FontRun?, val shiftEm: Float)

    private fun mergeBands(line: DrawLine, lo: Int, hi: Int): List<Band> {
        val colorBounds = line.colorRuns.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) ColorBand(s, e, r.argb) else null
        }
        val fontBounds = line.fontRuns.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) FontBand(s, e, r) else null
        }
        val shiftBounds = line.baselineShifts.mapNotNull { r ->
            val s = r.start.coerceIn(lo, hi)
            val e = r.endExclusive.coerceIn(lo, hi)
            if (e > s) ShiftBand(s, e, r.shiftEm) else null
        }
        if (colorBounds.isEmpty() && fontBounds.isEmpty() && shiftBounds.isEmpty()) return emptyList()
        val edges = (colorBounds.flatMap { listOf(it.start, it.end) } +
            fontBounds.flatMap { listOf(it.start, it.end) } +
            shiftBounds.flatMap { listOf(it.start, it.end) } + listOf(lo, hi))
            .filter { it in lo..hi }
            .distinct().sorted()
        val bands = ArrayList<Band>(edges.size - 1)
        for (k in 0 until edges.size - 1) {
            val s = edges[k]
            val e = edges[k + 1]
            if (e <= s) continue
            val argb = colorBounds.firstOrNull { it.start <= s && e <= it.end }?.argb
            val font = fontBounds.firstOrNull { it.start <= s && e <= it.end }?.run
            val shiftEm = shiftBounds.firstOrNull { it.start <= s && e <= it.end }?.shiftEm ?: 0f
            bands.add(Band(s, e, argb, font, shiftEm))
        }
        return bands
    }

    private data class ColorBand(val start: Int, val end: Int, val argb: Int)
    private data class FontBand(val start: Int, val end: Int, val run: orilumn.reader.engine.css.FontRun)
    private data class ShiftBand(val start: Int, val end: Int, val shiftEm: Float)

    /** Marker 字形绘制（listMarker 的同一 paragraphStyle 整形，单行短文本，无需 JUSTIFY 尾行技巧）。 */
    private fun paintText(canvas: Canvas, style: ParagraphStyle, text: String, textX: Float, line: DrawLine, collection: FontCollection) {
        val paragraph = ParagraphBuilder(style, collection).addText(text).build()
        try {
            paragraph.layout(line.lineWidthPx.coerceAtLeast(1).toFloat())
            paragraph.paint(canvas, textX, line.yTop.toFloat())
        } finally {
            paragraph.close()
        }
    }

    /**
     * 矢量 marker 绘制（disc 实心圆 / circle 空心圈 / square 实心方），行中线居中，
     * 尺寸恒相对行字号（不随字体字形走）。[x] 为形状左缘，[midY] 为行中线。
     */
    private fun drawShapeMarker(canvas: Canvas, kind: ListMarkers.Kind, x: Float, midY: Float, size: Float, ink: Int) {
        if (size <= 0f) return
        val half = size / 2f
        when (kind) {
            ListMarkers.Kind.SQUARE -> {
                val paint = Paint().apply { color = ink }
                canvas.drawRect(Rect.makeLTRB(x, midY - half, x + size, midY + half), paint)
            }
            ListMarkers.Kind.CIRCLE -> {
                val stroke = (size / 7f).coerceAtLeast(1f)
                val paint = Paint().apply {
                    color = ink
                    mode = org.jetbrains.skia.PaintMode.STROKE
                    strokeWidth = stroke
                }
                canvas.drawCircle(x + half, midY, half - stroke / 2f, paint)
            }
            else -> { // DISC
                val paint = Paint().apply { color = ink }
                canvas.drawCircle(x + half, midY, half, paint)
            }
        }
    }

    /** 用同 [DrawLine] 的 paragraphStyle 实测 marker 文本宽度（SkParagraph，与平板 measureText 同标的）。 */
    private fun measureMarkerWidth(style: ParagraphStyle, marker: String, collection: FontCollection): Int {
        val paragraph = ParagraphBuilder(style, collection).addText(marker).build()
        try {
            paragraph.layout(Float.MAX_VALUE)
            val w = paragraph.lineMetrics.getOrNull(0)?.right ?: 0.0
            return w.roundToInt().coerceAtLeast(1)
        } finally {
            paragraph.close()
        }
    }
}
// ── 文本装饰（第三刀）的绘制常量 ──────────────────────────────────────────────
// 线宽由解析期定值（`auto`/初始值 = 字号/10；显式长度按声明；仅 `from-font` 落到字体度量），
// 这里只放兜底值，或本身即 UA 自定值（点线/虚线/波浪的节距、双线比例）。

/** 取装饰度量用的码本（拉丁大写 H）：字体度量与码本无关，固定一个即可命中缓存。 */
private const val DECORATION_METRIC_CP = 'H'.code

/** 三种线型的绘制顺序（集合本身无序，绘制要确定）。 */
private val DECORATION_PAINT_ORDER = listOf(DecorationLine.UNDERLINE, DecorationLine.OVERLINE, DecorationLine.LINE_THROUGH)

/**
 * 四舍五入到最近整数、**恰为 .5 时进位**：Chromium/C++ 的 `std::round` 口径。
 * Kotlin 的 `kotlin.math.round` 是「就近取偶」（`round(2.5) = 2`），照它算会与浏览器差一整个波长
 * （实测 20px 字号：浏览器波长 11px，取偶口径 9px）。
 */
private fun roundHalfUp(v: Float): Float = kotlin.math.floor(v + 0.5f)

/** `from-font` 线宽的下限（px）：细字体（宋体度量仅 0.02em）也要看得见。 */
private const val DECORATION_MIN_THICKNESS_PX = 1.0f

/** 字体度量缺失时的回退线宽（em，与旧下划线口径一致 = 0.06em）。 */
private const val DECORATION_AUTO_THICKNESS_EM = 0.1f

/** 字体度量缺失时的回退下划线位置（em，与旧下划线口径一致 = 0.12em）。 */
private const val DECORATION_UNDERLINE_FALLBACK_EM = 0.12f

/** 读者标注划线的默认粗细（em）：比书内下划线更粗，避免两线糊在一起。 */
private const val ANNOTATION_UNDERLINE_THICKNESS_EM = 0.08f

/** 读者标注划线的默认中心位置（em，基线向下）：比书内下划线更靠下。 */
private const val ANNOTATION_UNDERLINE_OFFSET_EM = 0.20f

/** 删除线中心相对基线的距离（em，向上）：穿字身中部（CJK 方块字居中略偏上）。 */
private const val ANNOTATION_STRIKE_OFFSET_EM = 0.28f

/** 虚线：实线段 / 空档 长度相对线粗的倍数。 */
private const val ANNOTATION_DASH_ON_RATIO = 3f
private const val ANNOTATION_DASH_OFF_RATIO = 2f

/** skip-ink 横带的纵向内缩（px）：Chromium `decoration_bounds.Inset(InsetsF::VH(0.5, 0))`。 */
private const val DECORATION_INK_BAND_INSET_PX = 0.5f

/** skip-ink 切口横向扩张上限（px）：Chromium `kDecorationClipMaxDilation`。 */
private const val DECORATION_CLIP_MAX_DILATION_PX = 13f

/**
 * `text-decoration-skip-ink: auto` 的**逐字参与判据**：true = 该字可以切墨
 * （其字形轮廓与装饰带求交、交点处断线）。逐条对齐 Chromium M134
 * `Character::CanTextDecorationSkipInk`（`platform/text/character.cc`），返回 false 的三类：
 *
 * 1. `/` `\` `_` —— 三个 ASCII 例外（它们本身就长在装饰带里，浏览器明确要求不断线）；
 * 2. `IsCJKIdeographOrSymbol` 覆盖的整片 CJK 表意/符号（含假名、注音、全角形式、
 *    算筹/女书/西夏等，逐段抄自 `character_property_data.h` 的
 *    `kIsCJKIdeographOrSymbolRanges` / `...Array`）；
 * 3. Hangul 各区块与 Linear B 表意文字（`CanTextDecorationSkipInk` 里单列的 switch 分支）。
 *
 * 不在上面的字才去 [inkCuts] 里取字形轮廓与装饰带的交点。
 */
private fun canSkipInk(cp: Int): Boolean {
    if (cp == 0x2F || cp == 0x5C || cp == 0x5F) return false
    if (cp < 0x2C7) return true
    if (isCjkIdeographOrSymbol(cp)) return false
    return cp !in 0x1100..0x11FF && cp !in 0x3130..0x318F && cp !in 0xAC00..0xD7A3 &&
        cp !in 0xA960..0xA97F && cp !in 0xD7B0..0xD7FF && cp !in 0x10080..0x100FF
}

private fun isCjkIdeographOrSymbol(cp: Int): Boolean {
    for (c in CJK_IDEOGRAPH_OR_SYMBOL_SINGLES) if (c == cp) return true
    var i = 0
    while (i + 1 < CJK_IDEOGRAPH_OR_SYMBOL_RANGES.size) {
        if (cp <= CJK_IDEOGRAPH_OR_SYMBOL_RANGES[i + 1]) return cp >= CJK_IDEOGRAPH_OR_SYMBOL_RANGES[i]
        i += 2
    }
    return false
}

/**
 * `IsCJKIdeographOrSymbol` 的码点区间（成对 start/end），抄自 Blink M134
 * `character_property_data.h` 的 `kIsCJKIdeographOrSymbolRanges`。**顺序照抄**
 * （非升序），故 [isCjkIdeographOrSymbol] 线性扫描：条目数十个，逐字调用可忽略。
 */
private val CJK_IDEOGRAPH_OR_SYMBOL_RANGES = intArrayOf(
    0x2695, 0x2696,
    // cjkIdeographRanges
    0x2E80, 0x2FDF,
    0x31C0, 0x31EF,
    0x3400, 0x4DBF,
    0x4E00, 0x9FFF,
    0xF900, 0xFAFF,
    0x20000, 0x2FFFF,
    // cjkSymbolRanges
    0x2156, 0x215A, 0x2160, 0x216B, 0x2170, 0x217B, 0x231A, 0x231B, 0x23E9, 0x23EC,
    0x23BE, 0x23CC, 0x2460, 0x2492, 0x249C, 0x24FF, 0x25CE, 0x25D3, 0x25E2, 0x25E6,
    0x25FD, 0x25FE, 0x2600, 0x2603, 0x2660, 0x266F,
    0x2614, 0x2615, 0x2648, 0x2653, 0x26AA, 0x26AB, 0x26C4, 0x26C5, 0x26F2, 0x26F3,
    0x2753, 0x2757, 0x2763, 0x2764, 0x2672, 0x267D, 0x2776, 0x277F, 0x2795, 0x2797,
    0x270A, 0x270D,
    // 表意描述符 + CJK 符号标点（除 0x3030）/ 假名 / 注音
    0x2FF0, 0x302D, 0x3031, 0x312F,
    0x3190, 0x31BF,
    0x3200, 0x33FF,
    0x4DC0, 0x4DFF,
    0xF860, 0xF862,
    0xFE30, 0xFE6F,
    0xFF00, 0xFF0C, 0xFF0E, 0xFF1A, 0xFF1F, 0xFFEF,
    0x16FE0, 0x16FFF,
    0x17000, 0x187FF,
    0x18800, 0x18AFF,
    0x1B000, 0x1B0FF,
    0x1B100, 0x1B12F,
    0x1B170, 0x1B2FF,
    0x1F110, 0x1F129, 0x1F130, 0x1F149, 0x1F150, 0x1F169, 0x1F170, 0x1F189,
    0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F200, 0x1F6FF,
    0x1F3FB, 0x1F3FF,
    0x1F6DC, 0x1F6DF,
    0x1F7E0, 0x1F7EB,
    0x1F7F0, 0x1F7F0,
    0x1F900, 0x1F90F,
    0x1F910, 0x1F918, 0x1F919, 0x1F97F, 0x1F980, 0x1F9BF, 0x1F9C0, 0x1F9FF,
    0x1FA70, 0x1FA7C,
    0x1FA80, 0x1FA88,
    0x1FA90, 0x1FABD,
    0x1FABF, 0x1FAC5,
    0x1FACE, 0x1FACF,
    0x1FAD0, 0x1FADB,
    0x1FAE0, 0x1FAE8,
    0x1FAF0, 0x1FAF8,
)

/** `kIsCJKIdeographOrSymbolArray`：单个码点。 */
private val CJK_IDEOGRAPH_OR_SYMBOL_SINGLES = intArrayOf(
    0x2C7, 0x2CA, 0x2CB, 0x2D9, 0x2020, 0x2021, 0x2030, 0x203B, 0x203C, 0x2042,
    0x2047, 0x2048, 0x2049, 0x2051, 0x20DD, 0x20DE, 0x2100, 0x2103, 0x2105, 0x2109,
    0x210A, 0x2113, 0x2116, 0x2121, 0x212B, 0x213B, 0x2150, 0x2151, 0x2152, 0x217F,
    0x2189, 0x2307, 0x23F0, 0x23F3, 0x2312, 0x23CE, 0x2423, 0x25A0, 0x25A1, 0x25A2,
    0x25AA, 0x25AB, 0x25B1, 0x25B2, 0x25B3, 0x25B6, 0x25B7, 0x25BC, 0x25BD, 0x25C0,
    0x25C1, 0x25C6, 0x25C7, 0x25C9, 0x25CB, 0x25CC, 0x25EF, 0x2605, 0x2606, 0x260E,
    0x2616, 0x2617, 0x261D, 0x2640, 0x2642, 0x267F, 0x2693, 0x26A0, 0x26A1, 0x26BD,
    0x26BE, 0x26CE, 0x26D4, 0x26EA, 0x26F5, 0x26F9, 0x26FA, 0x26FD, 0x2705, 0x2708,
    0x2713, 0x271A, 0x2728, 0x273F, 0x2740, 0x274C, 0x274E, 0x27B0, 0x27BF, 0x2B1A,
    0x2B1B, 0x2B1C, 0x2B50, 0x2B55, 0xFE10, 0xFE11, 0xFE12, 0xFE19, 0xFF1D,
    0x1F100, 0x1F004, 0x1F0CF, 0x1F18E,
)
