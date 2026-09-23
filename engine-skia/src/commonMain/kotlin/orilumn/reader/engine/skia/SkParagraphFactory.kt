package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontWeight
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextIndent
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider

/**
 * Single source for engine-skia's SkParagraph shaping configuration.
 *
 * G 阶段铁律（`docs/KMP+CMP迁移方案.md` §5 阶段1）：**度量（[SkiaParagraphBreaker]）与绘制
 * （S25 [LineWindowDrawer]）必须使用完全相同的 FontCollection / 字体 Family / OpenType 配置**，否则
 * 断行几何与绘制字形错位。本对象是这套配置的唯一出处：断行与绘制都从这里构造 [ParagraphStyle]。
 *
 * 与 Android 旧管线（静态 Android `StaticLayout` + FontPairing，Q1 已退役）的对应关系：
 *  - `letterSpacingEm`：Android `TextPaint.letterSpacing` 是 em 倍率，Skia 的 `TextStyle.letterSpacing`
 *    是像素值，故按 `em × fontSizePx` 折算（绘制/度量一致，几何不漂移）。

 *  - `families`：与普通浏览器一致的整栈级联语义 — 吃下 CSS `font-family` 全栈（作者顺序），把
 *    通用关键字（serif/sans-serif/monospace）展开成平台可栅格化的实族候选（含 CJK 衬线/无衬线；
 *    serif 只走候选链，见 [resolveFamilies]），然后整体交给 SkParagraph 的 FontCollection 按字形
 *    逐族回退（未安装/无字形的具名族被跳过）。等价于 Android FontPairing.SYSTEM 的整栈回退；
 *    只取首名会让首族未装的书籍（如"思源宋体 VF"）退化成默认无衬线，偏离浏览器结果。
 *    code-like → "monospace"。
 *  - 行高：`TextStyle.setHeight(lineHeightRatio)` 把每行自然行高固定为 `ratio × fontSizePx`，与
 *    common 的统一行框 `lineHeightPx(fontSizePx, ratio)` 同一语义（四舍五入后的整数由几何持有，
 *    绘制按几何绝对 Y 落位，浮点差不会累计漂移）。
 */
object SkParagraphFactory {

    /** 平台默认字体管理器：即 [systemFonts] 接缝（母文档 §3 系统字体集合边界）。 */
    fun defaultFontMgr(): FontMgr = systemFonts()

    /** 平台默认字体集合（与 [systemFonts] 同一来源，度量/绘制共用）。 */
    fun defaultCollection(): FontCollection =
        FontCollection().setDefaultFontManager(systemFonts())

    /**
     * 唯一字体池入口（D1）：平台系统字体 + 内嵌/用户字体二进制，构造成单个 [FontCollection]。
     * 条目类型收敛到 [SkiaFontPool.EmbeddedFont]（本方法只做“二进制→集合”装配， refresh/复用语义归池）。
     * resolve/fallback 全走 [resolveFamilies] 语义 —— 整条 CSS 栈交给 FontCollection 按字形逐族回退，
     * 具名族命中系统或内嵌注册即保留、未装即跳过。断行与绘制必须复用**同一实例**（G 阶段铁律），
     * 宿主把它同时喂给 [SkiaParagraphBreaker] 与 [LineWindowDrawer]。
     */
    fun embeddedFontCollection(fonts: List<SkiaFontPool.EmbeddedFont>): FontCollection {
        val collection = defaultCollection()
        if (fonts.isEmpty()) return collection
        val provider = TypefaceFontProvider()
        val loader = systemFonts()
        for (f in fonts) {
            runCatching { checkNotNull(loader.makeFromData(Data.makeFromBytes(f.bytes), f.faceIndex)) }
                .onSuccess { tf ->
                    provider.registerTypeface(tf, f.familyName)
                    for (a in f.aliases) {
                        if (a.isNotBlank() && a != f.familyName) {
                            runCatching { provider.registerTypeface(tf, a) }
                        }
                    }
                }
        }
        return collection.setAssetFontManager(provider)
    }

    /** CSS [TextAlign] → SkParagraph [Alignment]。JUSTIFY 即 `kJustify` 两端对齐。 */
    fun toSkiaAlignment(alignment: TextAlign): Alignment = when (alignment) {
        TextAlign.LEFT -> Alignment.LEFT
        TextAlign.CENTER -> Alignment.CENTER
        TextAlign.RIGHT -> Alignment.RIGHT
        TextAlign.JUSTIFY -> Alignment.JUSTIFY
    }

    /**
     * 由一场文本 run 的 CSS 参数构造单一 [ParagraphStyle]。断行与绘制经同一路径获得，保证配置唯一。
     *
     * @param lineHeightRatio CSS line-height 倍率（相对 font-size）；>0 才固定行高，否则交给字体度量。
     * @param families 级联结果里该 run 的整条 CSS `font-family` 栈（作者顺序，含通用关键字；空则默认）：
     *   与普通浏览器一致，未安装的具名族被 FontCollection 按字形跳过、通用关键字展开成实族候选回落。
     * @param fontManager 与 collection 同源的 FontMgr：桌面 JVM 的 CoreText 管理器不认 `sans-serif` 这类
     *   通用名，直接传入会让字形无法绘制（度量却走 fallback 显正常，属隐蔽坑），故必须落成实族。
     */
    fun paragraphStyle(
        alignment: TextAlign,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        letterSpacingEm: Float,
        maxLines: Int = Int.MAX_VALUE,
        fontManager: FontMgr = defaultFontMgr(),
        /** 文本墨色（ARGB Int，与 TypographicProfile.fgColor 同一值）：主题换色即换墨。 */
        inkColor: Int = 0xFF000000.toInt(),
        /**
         * 首行缩进（px，仅度量侧用：首行可用宽减之；绘制侧不用——绘制按行子串重排，
         * 每行都会被当成“首行”，传了即 double 缩进，绘制侧走 [orilumn.reader.engine.skia.DrawLine]
         * 的 x 偏移）。
         */
        firstLineIndentPx: Float = 0f,
        /**
         * P1-2: 行内存在基线位移时固定行盒基线（CSS strut 语义）。无位移行恒 false
         * （零行为变化）；有位移行两端（度量/绘制）同开，基线不浮动、只动被移 run。
         */
        forceStrut: Boolean = false,
    ): ParagraphStyle {
        val style = FontStyle(
            if (weight >= FontWeight.SEMI_BOLD) FontWeight.BOLD else FontWeight.NORMAL,
            FontWidth.NORMAL,
            if (italic) FontSlant.ITALIC else FontSlant.UPRIGHT,
        )
        val resolved = resolveFamilies(tag, families, monospace, fontManager)
        return ParagraphStyle().apply {
            this.alignment = toSkiaAlignment(alignment)
            maxLinesCount = maxLines
            if (firstLineIndentPx > 0f) textIndent = TextIndent(firstLineIndentPx, 0f)
            if (forceStrut && lineHeightRatio > 0f) {
                strutStyle = org.jetbrains.skia.paragraph.StrutStyle()
                    .setFontFamilies(resolved)
                    .setFontStyle(style)
                    .setFontSize(fontSizePx.coerceAtLeast(1f))
                    .setHeight(lineHeightRatio)
                    // 半行距居中（与下方 TextStyle 同一语义）：StrutStyle.setTopRatio 在 skiko 0.144.6
                    // 无 native 绑定（调用即 UnsatisfiedLinkError，桌面/Android 同源同缺），故此处用
                    // 仍在绑定内的 setHalfLeading(true)（≡ topRatio=0.5）。
                    .setHalfLeading(true)
                    .setLeading(0f)
                    .setEnabled(true)
                    .setHeightForced(true)
                    .setHeightOverridden(false)
            }
            textStyle = TextStyle().apply {
                // 必须显式给前景色：未设 foreground 时经 skiko-awt 的 Paragraph#_nPaint 栅格化会
                // 产出全透明字形（度量正常、绘制空白，属实测坑）。墨色跟随阅读主题（默认黑字）。
                foreground = Paint().apply { color = inkColor }
                setFontSize(fontSizePx.coerceAtLeast(1f))
                setFontStyle(style)
                setFontFamilies(resolved)
                if (letterSpacingEm != 0f) setLetterSpacing(letterSpacingEm * fontSizePx)
                if (lineHeightRatio > 0f) {
                    setHeight(lineHeightRatio)
                    // 半行距居中（CSS 2.2 §10.8.1）：Skia 的 setHeight 默认走「按比例缩放 ascent/descent」
                    // 的 metrics 模型（ascent' = ascent × lineHeight/fontHeight），基线比浏览器偏低；
                    // setHalfLeading(true)（≡ topRatio=0.5，见 strut 侧注释为何不用 setTopRatio）改成浏览器
                    // 语义：ascent' = ascent + (lineHeight − fontHeight)/2，即 leading 上下各分一半，
                    // 行盒内文字垂直居中，与 Chrome 逐像素一致。
                    setHalfLeading(true)
                }
            }
        }
    }

    /**
     * Builds the [TextStyle] of a single inline [orilumn.reader.engine.css.FontRun] segment — same config
     * as [paragraphStyle] but under the run's own face (families/tag/weight/italic/mono, plus an
     * optional ink override for the draw-side color merge). Metric-only ([SkiaParagraphBreaker]) and
     * draw ([LineWindowDrawer]) both shape the same runs from this single source, so measure and paint
     * can never drift on inline faces.
     *
     * @param baselineShiftPx P1-2 行内基线位移（px；Skia 约定正值下移；调用方按
     *   `-shiftEm × 叶基底字号` 换算，0 = 基线）。
     */
    fun runTextStyle(
        alignment: TextAlign,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        letterSpacingEm: Float,
        inkColor: Int = 0xFF000000.toInt(),
        baselineShiftPx: Float = 0f,
    ): TextStyle =
        paragraphStyle(alignment, fontSizePx, lineHeightRatio, tag, families, weight, italic, monospace, letterSpacingEm, inkColor = inkColor).textStyle.apply {
            if (baselineShiftPx != 0f) setBaselineShift(baselineShiftPx)
        }

    /**
     * 与普通浏览器一致的 `font-family` 解析：把 CSS 整栈规范化为 FontCollection 可用的家族名列表，
     * 保持作者顺序 — 级联结果（[orilumn.reader.engine.css.ComputedStyle.fontFamilies]）与这里一一对应。
     *
     *  - 具名族原样保留：是否安装/是否覆盖所需字形交给 SkParagraph 的 FontCollection 按字形回退判断
     *    （浏览器语义——首族未装就试下一族，而不是直接退化到默认无衬线）。
     *  - 通用关键字（serif/sans-serif/monospace）**恒展开成实族候选**：这份候选列表就是浏览器
     *    内核的通用族映射（serif→Times…/Noto Serif CJK/SimSun/Songti…，sans-serif→Helvetica…/
     *    Noto Sans CJK/PingFang…，monospace→Menlo/MS Gothic…），中文按字形沿候选落到宋体/黑体。
     *    `serif` **不**把平台自有通用族置首——skiko-Android 的 FontMgr 认 `serif` 却无 Minikin
     *    `serif→NotoSerifCJK` 链，置首会让中文被它内部回退的黑体吃掉（回归旧作宋体）；平台系统
     *    有宋体（如 `/system/fonts/NotoSerifCJK-Regular.ttc`）时，宿主把它以候选里的真名
     *    （Noto Serif CJK SC 等）补装进共用池（见 [SkiaFontPool] / 宿主 SystemCjkSerif），内核
     *    映射即命中。sans-serif/monospace 保留平台通用族置首（内部回退即黑体，现代模式/代码期望）。
     *  - 空栈（章节没写 font-family）：回到默认族第一个可落实的实族，保持既有的默认几何基线
     *    （Helvetica 等）不漂移。code-like → monospace。
     */
    fun resolveFamilies(tag: String?, families: List<String>, monospace: Boolean, fontManager: FontMgr = defaultFontMgr()): Array<String> =
        resolveFamiliesFor(tag, families, monospace) { name ->
            fontManager.matchFamilyStyle(name, FontStyle.NORMAL) != null
        }

    /**
     * 纯决策核（匹配谓词可注入，便于在 JVM 上复现 Android 等平台的 FontMgr 行为做回归单测）：
     * 与 [resolveFamilies] 同一逻辑，只把「平台能否匹配某族名」抽象成 [canMatch]。
     */
    internal fun resolveFamiliesFor(
        tag: String?,
        families: List<String>,
        monospace: Boolean,
        canMatch: (String) -> Boolean,
    ): Array<String> {
        val codeLike = monospace || tag in CODE_TAGS
        // 代码族：栈非空即 honor（书内 code 字体与用户代码槽都走这里）；空栈才回 monospace 默认。
        // 此前恒取 monospace，用户代码槽与书内 code 字体双双被吞（“无法修改字体”之其一）。
        val stack = if (codeLike && families.isEmpty()) listOf(MONO_FAMILY) else families
        val out = ArrayList<String>()
        for (raw in stack) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val candidates = GENERIC_CANDIDATES[name.lowercase()]
            if (candidates != null) {
                // 通用关键字恒展开成实族候选（浏览器内核映射，单源）：skiko-Android 的 FontMgr 认
                // `serif` 却无 Minikin `serif→NotoSerifCJK` 链，把平台自有通用族置首会先吃内部回退
                // （vivo 实测 serif→黑体），中文永远够不到候选里的宋体。故 serif 只走候选链
                // （Times… 拉丁 → Noto Serif CJK/SimSun/Songti… 中文宋体）；sans-serif/monospace
                // 保留平台通用族置首——其内部回退即黑体，恰是现代模式/代码期望。
                if (name != "serif" && canMatch(name) && name !in out) out.add(name)
                for (c in candidates) if (c !in out) out.add(c)
            } else if (name !in out) {
                // 具名族原样保留：是否安装交给 FontCollection 按字形回退。
                out.add(name)
            }
        }
        if (out.isEmpty()) out.add(canonicalFamily(canMatch, DEFAULT_FAMILY))
        return out.toTypedArray()
    }

    /** 把通用家族名（或具名族）落成 [canMatch] 可匹配的实族：命中即原样，否则逐个候选取首个可匹配。 */
    private fun canonicalFamily(canMatch: (String) -> Boolean, css: String): String {
        if (canMatch(css)) return css
        for (candidate in GENERIC_CANDIDATES[css] ?: emptyList()) {
            if (canMatch(candidate)) return candidate
        }
        return css
    }

    private const val DEFAULT_FAMILY = "sans-serif"
    private const val MONO_FAMILY = "monospace"
    private val CODE_TAGS = setOf("pre", "code", "kbd", "samp")
    private val GENERIC_CANDIDATES = mapOf(
        // 拉丁实族打头（浏览器顺序，Windows/macOS 的拉丁渲染与此前完全一致），随后是各平台
        // CJK 同类候选：Android Noto CJK（TC 优先照顾繁体）、Windows 明/黑体系（PMingLiU/
        // 微軟正黑/Meiryo）、macOS 宋体/苹方。本机不存在的逐字形跳过，只加不减。
// 通用关键字恒缀这份候选列表（见 resolveFamiliesFor）。这就是浏览器内核的通用族映射单源：
        // serif→宋体（Noto Serif CJK/SimSun/Songti…），sans-serif→黑体，monospace→等宽。
        // Android 认裸 serif 却无 Minikin 链，故 serif 不置首平台通用族、纯走候选链；平台系统
        // 有宋体时宿主以候选里的真名补装进池（NotoSerifCJK-Regular.ttc 的 SC/TC 面），映射即命中。
        "sans-serif" to listOf("Helvetica", "Helvetica Neue", "Arial", "Noto Sans CJK TC", "Noto Sans CJK SC", "Noto Sans CJK JP", "Noto Sans CJK KR", "Microsoft YaHei", "Microsoft JhengHei", "Meiryo", "Yu Gothic", "PingFang SC", "Hiragino Sans GB", "DejaVu Sans", "Roboto"),
        "serif" to listOf("Times New Roman", "Times", "Georgia", "Noto Serif CJK TC", "Noto Serif CJK SC", "Noto Serif CJK JP", "Noto Serif CJK KR", "PMingLiU", "MingLiU", "MS Mincho", "Yu Mincho", "SimSun", "NSimSun", "Songti SC", "STSong", "DejaVu Serif"),
        "monospace" to listOf("Menlo", "Courier New", "Courier", "Noto Sans Mono CJK TC", "Noto Sans Mono CJK SC", "MS Gothic", "NSimSun", "DejaVu Sans Mono", "Droid Sans Mono"),
    )
}