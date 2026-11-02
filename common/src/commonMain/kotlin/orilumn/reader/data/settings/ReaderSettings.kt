package orilumn.reader.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reading settings object: carries the **global memory** (the only cross-book layer) and, after
 * [applyOverlay], the **effective value for one book**.
 *
 * 两级归属见 [SettingsScope]（2026-10-29 定口）：
 * - **全局**（`reader.json`）：亮度族、阅读主题、笔记族、续读/页码开关、字体列表隐藏过滤、
 *   持久化元数据 —— 这些是「与某本书无关的阅读环境与读者印记」；
 * - **私有**（`books/{bookId}.json`，稀疏覆盖）：字号/行距/字距/段距/页边距/字体族与字重/
 *   排版主题/翻页开关/首行缩进 —— 每本书默认原书样式，读者没配的项一律不发声明。
 *
 * **私有项永不写全局**：本书改字号不会影响别的书，也不会被别的书改掉。
 * 阅读进度在 `BookReadingState`，不在本表。
 *
 * Dual preset mechanism:
 *  - **Default set**: [Companion.DEFAULT] (built-in constants, never persisted);
 *  - **User set**: stored by [ReaderSettingsStore] at `filesDir/settings/reader.json`（只含全局项）；
 *  - **One-tap reset**: delete the user file → fall back to [Companion.DEFAULT].
 *
 * Font replacement is gated by "element type" into three tiers: `fontBody` body / `fontTitle` headings /
 * `fontCode` code. A slot stores a font alias — all text of the corresponding type uses the selected
 * local font; empty string = that type is not replaced and keeps the original book. **按书私有**
 * （同一本书挑宋体不传染到别的书）。All layout themes apply uniformly.
 */
@Serializable
data class ReaderSettings(
    /** Reading background (built-in preset name: sepia / light / dark ...). */
    val theme: String = "sepia",
    /** Body font size (px). */
    val fontSize: Int = 18,
    /**
     * 行距（倍数，0.5..2.5）。**本书私有、可空**：`null` = 读者没配过 ⇒ UI 层不发行距声明，
     * 书自己的 `line-height` 生效（书没写时由引擎初值 1.5 兜底）；任何实数都照原样发（0 就是 0，
     * 不拿 0 当「没配」哨兵）。面板未配时显示原书实测行高（[probeLineSpacing]），没写显示 1.5。
     */
    val lineSpacing: Double? = null,
    /** [Style system] First-line indent (em, 0..10): `text-indent` on body paragraphs (p/li), applied by the UI layer so it overrides the book.
     *  **本书私有、可空**：`null` = 读者没设过（不发声明，书自己的缩进原样，含悬挂缩进）；
     *  `0` = 读者明确不要缩进（发 `text-indent:0em`，压掉书的缩进）；`N` = N em。
     *  打开设置面板时按当前章量一次书的缩进，只进 [probeIndent] 供面板显示，不写本字段。 */
    val firstLineIndent: Double? = null,
    /** 首行缩进的**只读面板读数**（本次会话，不落盘）：读者没设值时滑条显示它。
     *  它不参与渲染（渲染只看 [firstLineIndent]），所以量到了也不会改版式、不进分页键。 */
    @Transient
    val probeIndent: Double? = null,
    /** 行距的**只读面板读数**（本次会话，不落盘）：读者没设值时滑条显示原书实际行高。
     *  同 [probeIndent]：不参与渲染、不进分页键；打开面板时量一次（见 §3.3）。 */
    @Transient
    val probeLineSpacing: Double? = null,
    /**
     * [Style system] 段间距（**绝对值，em，0..2**，本书私有、可空）：`null` = 读者没配 ⇒ 引擎
     * 不覆盖，p/li 的纵边距原样（书 / 主题 / UA 自己的段距生效，面板显示原书实测值）；`N` =
     * p/li 的上下外边距**固定为 N em**，不随书放大或缩小（0 = 段落紧贴）。
     *
     * 2026-10-29 由百分比乘算改回绝对值：百分比是**乘书自己的 margin**，书里 0.3em 时
     * 「50%」= 0.15em、「200%」= 0.6em，与书里 1.5em 时的同样两档（0.75em / 3em）完全不是
     * 一个手感 —— 同一条滑块在不同书上代表不同的物理间距。绝对值下 N em 就是 N em。
     *
     * 旧档（百分比 0..1000）一次性迁移见 [BookSettings] / `BookSettingsStore` 的 legacy 读法
     * （100 → null，其余 ÷100 后 clamp 到 0..2）。
     */
    val paragraphSpacing: Double? = null,
    /** 段间距的**只读面板读数**（本次会话，不落盘）：读者没配时滑条显示原书实测段距
     *  （含 UA 基线 1em；书自己写了 margin 就按书）。同 [probeIndent]：不参与渲染、不进分页键。 */
    @Transient
    val probeParagraphSpacing: Double? = null,
    /** [Style system] 疏密 (% scale 0..1000, default 100): p/li 之外一切块级纵边距乘算
     *  (100 = 原书节奏，0 = 结构块边距清零；p/li 看段间距)。滑块映射同 [paragraphSpacing]。 */
    val paragraphGap: Double = 100.0,
    /**
     * [Style system] 字间距槽 -100..100，映射到 -0.2em..0.2em（每档 0.002em）；**本书私有、可空**。
     *
     * `null` = 读者没配 ⇒ 引擎**按书**：用正文段落自己的 `letter-spacing`（书没写就是 0），
     * 面板显示 [probeLetterSpacing] 实测值。`N` = 读者显式覆盖：全章按 N/500 em 排，
     * 书的字距不再生效（`0` 就是「明确不加字距」，与「没配」是两回事）。
     *
     * 旧档里 `0` 是默认值且等于「不加字距」⇒ 读档时按没配处理（[fromJson]），
     * 于是升级后书自带的字距立刻生效。
     */
    val letterSpacing: Double? = null,
    /** 字间距的**只读面板读数**（本次会话，不落盘）：读者没配时滑条显示原书正文实测字距
     *  （`letter-spacingPx / font-size`）。同 [probeIndent]：不参与渲染、不进分页键。 */
    @Transient
    val probeLetterSpacing: Double? = null,
    /**
     * [Style system] **混排字距** 0..100, mapped to 0..1.0em; 25 = 0.25em (CLREQ default).
     *
     * 用户可见名是「混排字距」（2026-10-03 由「中西字距」改名），但**内部标识符与 JSON 键
     * 仍然是 `cjkLatinSpacing`**，两者故意不同：改名会改掉持久化键、把刚写好的
     * [schemaVersion] v1 迁移作废（老存档里根本没有 `混排字距` 这个键）。
     *
     * 0 是**合法档位**，且**不是特例档**（产品口径 2026-10-03 改）：0 = 注入的间隙宽正好 0，
     * 中西之间**只**由 [letterSpacing]（字间距）分隔 —— 字面意义上的「距离 0」。
     * 边界照检、作者为了让中英分开而手打的那串半角空格**照吃**（画成零宽，
     * CLREQ 4.1「删除多余半角空格，注入固定间隙」的删除动作与间隙宽无关）。
     * 于是 `Rust 的所有权` 在 0 档排成 `Rust的所有权`；档位之间只有间隙宽这一个数在变。
     * 详细口径见 [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc。
     */
    val cjkLatinSpacing: Double = 25.0,

    /** Page margins: four directions independent (px). */
    val marginTop: Int = 100,
    val marginBottom: Int = 60,
    val marginLeft: Int = 50,
    val marginRight: Int = 50,
    /** Font replacement by element type, three tiers (alias, empty string = that type not replaced, keep the original book): body / heading / code. */
    val fontBody: String = "",
    val fontTitle: String = "",
    val fontCode: String = "",
    /** L2 user-imported stylesheet switch (M2). */
    val useUserScripts: Boolean = false,
    /** Page-turn animation (smooth swipe). */
    val pageAnim: Boolean = true,
    /** Page-turn animation mode: "slide" (smooth swipe) / "curl" (page curl). 默认「滑动」。 */
    val pageAnimationMode: String = "slide",
    /** Auto-continue reading on open (resume at last position). */
    val autoContinue: Boolean = true,
    /** Show page numbers. */
    val pageNum: Boolean = false,
    /** Body first-screen cover stretch switch: on = stretch to fill the whole screen (may distort); off = keep aspect ratio (no distortion, reading bg shown around). */
    val coverStretch: Boolean = true,
    /** [Style system] Font size relative to default: 0..100 slider, 50 = default 1.0 (see COMPANION mapping). */
    val fontScale: Double = DEFAULT_FONT_SCALE_PX18_5,
    /** [Style system] Day/night full color scheme: day | night. */
    val scheme: String = "day",
    /** [Style system] Independent background override (hex like "#f4f2ec"); empty string = follow [scheme] default background. */
    val bgOverride: String = "",
    /** [Style system] Independent body text-color override (hex like "#2b2b2b"); empty string = follow [scheme] default text color. */
    val fgOverride: String = "#000000",
    /** [Style system] Layout theme: original (book settings) | modern | traditional. */
    val layoutTheme: String = "original",
    /** Reader brightness level -50..100: 100 = follow system; 0..100 writes the physical system backlight; -50..0 dims to system darkest then overlays a mask to darken further. */
    val brightness: Int = 100,
    /** Follow-system brightness switch; on → disables the "brightness" level slider, adjusting via [brightnessOffset] around the system brightness instead. */
    val brightnessFollowSystem: Boolean = true,
    /** Offset around the system brightness when following it: -20..20 (0 = fully follow system with no adjustment). */
    val brightnessOffset: Int = 0,
    /** Eye protection (blue-light filter) amount 0..100 (warm mask opacity ratio); 0 = filter off. */
    val eyeProtectionLevel: Int = 40,
    /** Brightness gestures (multi-selectable, all only effective when not following system):
     *  [brightnessGestureLeft] single-finger vertical swipe on left side of screen | [brightnessGestureRight] single-finger vertical swipe on right side |
     *  [brightnessGestureTwo] two-finger vertical swipe anywhere. */
    val brightnessGestureLeft: Boolean = false,
    val brightnessGestureRight: Boolean = false,
    val brightnessGestureTwo: Boolean = false,
    /** 字体管理隐藏字体列出开关（纯全局，默认隐藏；永不进按书 overlay，见 [PerBookSettings]）。 */
    val showHiddenFonts: Boolean = false,
    /**
     * 用户在该族选的字重（族名 → CSS 字重 100..900）。**唯一消费者是 UI 层声明**：
     * `ReaderUiSheet.fontRules` 把它写成该槽规则的 `font-weight`（tier 44），在级联里赢过 UA/作者；
     * `FontPoolSync` 另用它决定池里要装哪几档面。空 = 未选 ⇒ 不发声明 ⇒ 原书 `font-weight` 原样生效。
     * **按书私有**（2026-10-29 定口）：与字族一起按书，旧全局锚点不搬运；[fontWeightAnchorsBySlot] 优先，本表仅作旧档兜底。
     */
    val fontWeightAnchors: Map<String, Int> = emptyMap(),
    /**
     * 按 (槽位,族) 的用户字重：key = `"fontTitle|族名"`。**槽位隔离**——
     * 同名字族在标题/正文/代码三个槽各选各的字重，互不串（`FontSlots.slotNameOf` 出这个前缀）。
     */
    val fontWeightAnchorsBySlot: Map<String, Int> = emptyMap(),

    /**
     * 标注色槽（ARGB，含透明度）：高亮 / 划线各一组，全局共享（D-13）。
     *
     * 新建标注只读取 [highlightDefaultSlot] / [underlineDefaultSlot] 指向的那一格，
     * 并把当时的颜色写进记录；之后改色槽**不回溯**已有标注（墨迹已干）。
     * 色槽本身不进 CSS 级联，也不影响分页；面板改色走无重排提交。
     */
    val highlightColorSlots: List<Int> = DEFAULT_HIGHLIGHT_COLOR_SLOTS,
    val underlineColorSlots: List<Int> = DEFAULT_UNDERLINE_COLOR_SLOTS,
    /** 新建高亮 / 划线默认使用的色槽下标（0 起，越界读取时钳到有效范围）。 */
    val highlightDefaultSlot: Int = 0,
    val underlineDefaultSlot: Int = 0,
    /**
     * 上次用于新建 / 改色标注的类别（`highlight` / `underline`，见 `annotationLastKind()`）。
     *
     * 浮动菜单因此**一步到位**：打开就停在这个类别的色槽行上，不必先点一次「高亮 / 划线」。
     * 纯读者偏好（纯 UI，不进版式比较、不影响分页）；未知值按高亮处理。
     */
    val annotationLastKind: String = "highlight",
    /**
     * 上次用于新建 / 改线型的划线线型（`straight` / `wavy` / `dashed` / `strike`，
     * 见 `annotationLastLineStyle()`）。纯读者偏好（不影响分页）；未知值按实线处理。
     */
    val annotationLastLineStyle: String = "straight",

    /**
     * 批注（旁注）字体：空串 = 跟随界面默认（现状）；否则是字族名，与正文 / 标题 / 代码槽同源
     * （系统字体或已导入字体都能选）。
     *
     * 只作用于旁注覆盖层的文字，**不碰正文排版**，所以不进版式比较（面板走无重排提交）。
     * **纯全局**（[SettingsScope.GLOBAL]）：批注是读者自己的印记，与某本书无关，换书沿用
     * 最近一次选择；本书文件里不会出现这一项。
     */
    val noteFont: String = "",
    /**
     * 批注（旁注）字号的**相对倍率**（1 = 默认：正文主字号 × 旁注比例）。
     *
     * 存倍率而不是绝对像素，正文字号一变旁注跟着等比变，不会出现「正文调大了旁注没动」。
     * 面板显示的是换算后的实际字号。同样只影响旁注覆盖层，不进版式比较；落盘口径同
     * [noteFont]（纯全局，换书沿用）。
     */
    val noteFontScale: Float = 1f,
    /**
     * **折叠所有批注**（默认关）：整页的批注都退成原文右下角的小图标，不显示文字框。
     *
     * 读者想安静读正文时打开。折叠态下旁注框的描边 / 底色 / 同色字三个开关都不起作用
     * （小图标不是文字，有自己的固定画法）。落盘口径同 [noteFont]（纯全局，换书沿用）。
     */
    val noteCollapseAll: Boolean = false,
    /**
     * 旁注框的**描边**开关（默认开）。只作用于旁注覆盖层，与 [noteFont] 同口径：
     * 纯全局、不进版式比较、面板走无重排提交。
     */
    val noteBorder: Boolean = true,
    /**
     * 旁注框的**底色**开关（默认开 = 标注色 16% 铺底）。关掉后只有文字与引线，直接压在正文上。
     * 落盘口径同 [noteBorder]。
     */
    val noteBackground: Boolean = true,
    /**
     * 旁注文字用**标注自身的颜色**（默认关 = 跟随正文墨色）。
     *
     * 标注色往往很浅（高亮 45% 透明度），压在白纸上是浅色字；读者要的就是「字也跟着那条
     * 高亮/划线一个颜色」时才打开。落盘口径同 [noteBorder]。
     */
    val noteTextSameColor: Boolean = false,

    /**
     * 持久化结构版本（**只增不改**）：`[fromJson]` 用它识别「这份存档是哪个版本写的」，
     * 从而对**只影响本版本**的字段做一次性迁移。纯全局（[BookSettings] 无此字段，
     * 故不参与 [applyOverlay] 的分层逻辑）。
     *
     * ## 为什么不按「值」判断而要一个版本号
     *
     * 值判据（典型如「`cjkLatinSpacing == 0` 就当成旧档重置成 25」）**挡不住用户真的想设 0**：
     * 混排字距的 0 是合法档位（= 关掉间隙），用户拖到 0 之后下一次启动会被强行改回 25，
     * 且**改多少遍都会被改回去**。版本号则只对「写档那一刻还没有的语义」动手一次。
     *
     * ## 版本号怎么加
     *
     * **只加在数据类的最后一个字段**：中间插字段会改变序列化后的字段顺序，
     * 而老存档按**键名**解析（[fromJson] 走 `SettingsJson` 的 jsonObject），
     * 顺序变不影响读；写出去的顺序变了也没有读方依赖（唯一读方就是 [fromJson]）。
     * 真正的约束是**默认值必须等于当前版本**：缺该键 = 读到默认值 = 旧档。
     */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {

    /** Serialize to a full [JsonObject] (all fields, including defaults). */
    fun toJsonObject(): JsonObject = SettingsJson.compact.encodeToJsonElement(serializer(), this).jsonObject

    /**
     * 全局文件序列化：**剔除私有键**（归属见 [SettingsScope]），只留全局项与元数据。
     *
     * 为什么不是「写全量再靠读时抹」：全量 `encodeDefaults=true` 会把 `fontSize`/`lineSpacing`
     * 这些私有键连默认值一起写进 `reader.json`，文件看上去像在管排版，下一处改预设的人
     * 就会以为「全局有个字号」。键根本不出现，归属才没有歧义。
     */
    @JvmOverloads
    fun toGlobalJson(indentFactor: Int = 0): String {
        val kept = toJsonObject().filterKeys { it !in SettingsScope.PRIVATE && it !in SettingsScope.SESSION_ONLY }
        val codec = if (indentFactor > 0) SettingsJson.pretty else SettingsJson.compact
        return codec.encodeToString(JsonObject.serializer(), JsonObject(kept))
    }

    /**
     * Overlay a book's private override layer → returns the effective value.
     *
     * **只叠加私有项**：全局项（亮度/主题色/笔记族/开关）只从全局记忆来，本书文件里即使
     * 残留旧键也不参与（见 [SettingsScope]）。私有项里 `null` = 本书没配 ⇒ 用预设
     * （不是「用全局」——全局已经没有私有项了）。
     *
     * Never modifies [this].
     */
    fun applyOverlay(overlay: BookSettings): ReaderSettings = copy(
        layoutTheme = overlay.layoutTheme ?: layoutTheme,
        fontSize = overlay.fontSize ?: fontSize,
        fontScale = overlay.fontScale ?: fontScale,
        lineSpacing = overlay.lineSpacing ?: lineSpacing,
        cjkLatinSpacing = overlay.cjkLatinSpacing ?: cjkLatinSpacing,
        // 首行缩进是**本书私有**：全局里即使有旧值也不参与（书没设过 = 不发声明，书自己生效）。
        firstLineIndent = overlay.firstLineIndent,
        paragraphSpacing = overlay.paragraphSpacing,
        paragraphGap = overlay.paragraphGap ?: paragraphGap,
        // 字间距同为本书私有：没配（null）= 引擎按书的正文段落实测值，不落回全局记忆。
        letterSpacing = overlay.letterSpacing,
        marginTop = overlay.marginTop ?: marginTop,
        marginBottom = overlay.marginBottom ?: marginBottom,
        marginLeft = overlay.marginLeft ?: marginLeft,
        marginRight = overlay.marginRight ?: marginRight,
        fontBody = overlay.fontBody ?: fontBody,
        fontTitle = overlay.fontTitle ?: fontTitle,
        fontCode = overlay.fontCode ?: fontCode,
        useUserScripts = overlay.useUserScripts ?: useUserScripts,
        pageAnim = overlay.pageAnim ?: pageAnim,
        pageAnimationMode = overlay.pageAnimationMode ?: pageAnimationMode,
        coverStretch = overlay.coverStretch ?: coverStretch,
        fontWeightAnchors = overlay.fontWeightAnchors ?: fontWeightAnchors,
        fontWeightAnchorsBySlot = overlay.fontWeightAnchorsBySlot ?: fontWeightAnchorsBySlot,
    )

    /**
     * 亮度族剥离（双端同规则）：纯亮度变化不进版式管线——调用方比对
     * `withoutLight()` 是否变化，决定是否向排版/宿主传播。
     * 亮度族 = brightness/brightnessFollowSystem/brightnessOffset/eyeProtectionLevel/
     * brightnessGestureLeft/Right/Two（纯全局，不出 overlay）。
     *
     * ⚠ 只含亮度族。**判断「这次设置变更要不要重排」一律用 [withoutNonLayout]**（整族），
     * 本函数保留给「只关心亮度族」的窄口径调用点。
     */
    fun withoutLight(): ReaderSettings = copy(
        brightness = 0,
        brightnessFollowSystem = false,
        brightnessOffset = 0,
        eyeProtectionLevel = 0,
        brightnessGestureLeft = false,
        brightnessGestureRight = false,
        brightnessGestureTwo = false,
    )

    /**
     * **不进版式管线**的整族（亮度族 + 纯 UI 开关）：两端壳共用的一条规则，单一真相源。
     *
     * 背景：共享面板已把设置分成两类提交——排版类走 `onCommitTypography`（触发重排），
     * 不影响排版的走 `onCommitLight`（只持久化 + 重组，见 `ReaderSettingsPanel` KDoc 语义条约）。
     * 平板壳照此分流（`ReaderActivity.commitSettings(typographyChanged=false)` 不重排）；
     * 桌面壳两条回调合流，只能自己比对判断，原先用的 [withoutLight] **只含亮度族**，
     * 于是切纯 UI 开关仍会走完「本章全量重排 + 整书重排请求 + 两次落位推送」白干一场
     * （`LayoutParamKey.fromProfile` 不含这些字段 ⇒ 磁盘表不会被误删，属浪费而非损坏）。
     *
     * 语义：把整族清成**中性值**，使相等比较只关心版式族（与 [withoutLight] 同构）。
     * 布尔清 `false`、字符串清 `""` —— 不能清成某个真值，否则基线本身就是那个值时比较失效。
     *
     * 维护纪律（`SettingsLayoutScopeTest` 锁）：
     *  1. 新增设置字段先判「是否进 [orilumn.reader.engine.text.LayoutParamKey]」——
     *     **不进就必须登记进本函数**，否则又变一次「切开关触发全量重排」；
     *  2. 登记后，面板那一行也必须走 `onCommitLight`（两处必须同改，`SettingsLayoutScopeTest`
     *     只锁字段族，改不到面板路由——面板路由由该测试的源码扫描断言兜底）。
     */
    fun withoutNonLayout(): ReaderSettings = copy(
        brightness = 0,
        brightnessFollowSystem = false,
        brightnessOffset = 0,
        eyeProtectionLevel = 0,
        brightnessGestureLeft = false,
        brightnessGestureRight = false,
        brightnessGestureTwo = false,
        // 纯 UI 开关族（f2571fa 起面板已走 onCommitLight，此处补桌面壳的判定侧）
        pageAnim = false,
        pageAnimationMode = "",
        autoContinue = false,
        pageNum = false,
        coverStretch = false,
        showHiddenFonts = false,
        // 标注色槽族：只改像素，不影响分页；清空后比较相等（面板走 onCommitLight）。
        highlightColorSlots = emptyList(),
        underlineColorSlots = emptyList(),
        highlightDefaultSlot = 0,
        underlineDefaultSlot = 0,
        annotationLastKind = "",
        annotationLastLineStyle = "",
        // 批注字体 / 字号：只改旁注覆盖层，不进版式（面板走 onCommitLight）。
        noteFont = "",
        noteFontScale = 1f,
        // 折叠所有批注：只改覆盖层怎么画（整页退成小图标），同样不进版式。
        noteCollapseAll = false,
        // 旁注框的描边 / 底色 / 文字用色：同样只改覆盖层怎么画。
        noteBorder = true,
        noteBackground = true,
        noteTextSameColor = false,
        // 开书探针的只读读数：只给面板显示，永不参与渲染。
        probeIndent = null,
        probeLineSpacing = null,
        probeParagraphSpacing = null,
        probeLetterSpacing = null,
    )

    companion object {
        /**
         * 当前持久化结构版本。每有一处**只对新语义成立**的一次性迁移就 +1，
         * 并在 [fromJson] 里补一段 `if (stored < N) …`。
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /** 标注色槽数量（高亮 / 划线各自一组）。 */
        const val ANNOTATION_COLOR_SLOT_COUNT = 5

        /**
         * 批注字号倍率的上下限（[noteFontScale] 与笔记页滑条共用同一区间）。
         *
         * 下限 0.5 保证再小也还能读，上限 2.0 防止旁注大过正文把页面挤满。
         */
        const val MIN_NOTE_FONT_SCALE = 0.5f
        const val MAX_NOTE_FONT_SCALE = 2.0f

        /**
         * 高亮默认色槽：琥珀荧光笔，约 45% 不透明度（`0x73`）。
         * 其余四格为常用荧光色，透明度同样取 45%，便于直接使用。
         */
        val DEFAULT_HIGHLIGHT_COLOR_SLOTS: List<Int> = listOf(
            0x73FFD54F, // 琥珀
            0x7381C784, // 绿
            0x7364B5F6, // 蓝
            0x73F48FB1, // 粉
            0x73FFB74D, // 橙
        )

        /** 划线默认色槽：不透明红；其余四格为常用深色，保证细线清晰。 */
        val DEFAULT_UNDERLINE_COLOR_SLOTS: List<Int> = listOf(
            0xFFE53935.toInt(), // 红
            0xFF1E88E5.toInt(), // 蓝
            0xFF43A047.toInt(), // 绿
            0xFF8E24AA.toInt(), // 紫
            0xFFFB8C00.toInt(), // 橙
        )

        /** Default set: factory values when no customization has been made. */
        val DEFAULT = ReaderSettings()

        /**
         * v1 迁移：**重置混排字距到默认值**（用户报「设置里看到 0、代码默认却是 25」）。
         *
         * ## 为什么重置是对的（而不是「保留 0」或「判 0 就改 25」）
         *
         * 这个字段在本次修复前是**整套死代码**：设置能存、滑块能拖、`fromJson` 照读，
         * 但渲染侧没有任何一处消费它（`bodyParagraphBreaker` 丢弃第 2 个实参、
         * `ParagraphBreaker.breakLines` 没有这个形参）。于是**在旧版里拖出来的任何值都是盲选** ——
         * 用户在「拖了没反应」的滑块上停留时随手拖到的 80，跟他认真调到 25，在落盘文件里
         * **完全同形、无法区分**。保留它 = 把一个无意义的数字当成用户的明确意图继承下来。
         *
         * ## 为什么不是「`stored == 0 → 25`」
         *
         * 0 是合法档位（= 关闭间隙）。判值会把**故意设 0 的用户**也改回 25，而且每次启动都改一次。
         * 版本号只对「写档那一刻还不存在的语义」生效一次，之后用户怎么拖都尊重。
         *
         * ## 代价（知情）
         *
         * 本次修复前拖过滑块的用户，升级后 [cjkLatinSpacing] 会**从他的旧值回到 25**。
         * 这是有意的一次性代价：那串旧值本来就没有生效过，保留它等于假装尊重一个从未存在的意图。
         */
        private fun migrateCjkLatinSpacing(stored: Double, storedVersion: Int): Double =
            if (storedVersion < 1) DEFAULT.cjkLatinSpacing else stored

        /** Default body font size (px), the anchor body for calibrating [fontScale] to 1.0 (=50). */
        const val BASE_BODY_PX = 18

        /** Factory font-size slot (default body font 18.5px): ratioToFontScale(18.5 / BASE_BODY_PX) ≈ 51.39. */
        const val DEFAULT_FONT_SCALE_PX18_5 = 51.39

        /**
         * Piecewise mapping relative to default: maps the 0..100 slot to a scale factor.
         *  - 0..50: 0.5 → 1.0 (`0.5 + v*0.01`)
         *  - 50..100: 1.0 → 2.0 (`v*0.02`)
         * Slot 50 = the element's own default (body font = [BASE_BODY_PX], headings etc. scale proportionally off its em base).
         */
        fun fontScaleToRatio(v: Double): Double {
            val x = v.coerceIn(0.0, 100.0)
            return if (x <= 50.0) 0.5 + x * 0.01 else x * 0.02
        }

        /** Inverse of [fontScaleToRatio] (takes the 0..50 half when <1.0; 50..100 when ≥1.0). Only used for old absolute-px migration. */
        fun ratioToFontScale(ratio: Double): Double {
            val r = ratio.coerceIn(0.5, 2.0)
            val v = if (r <= 1.0) (r - 0.5) / 0.01 else r / 0.02
            return v.coerceIn(0.0, 100.0)
        }

        /** Old absolute body font size (px) → relative-to-default slot. */
        fun fontSizePxToScale(px: Int): Double = ratioToFontScale(px.toDouble() / BASE_BODY_PX)

        /**
         * Parse from JSON; missing or invalid fields fall back to the corresponding default
         * (tolerates partial omissions / new-version compatibility). Old sets (only absolute `fontSize`
         * px, no `fontScale`) are auto-migrated to relative slots.
         */
        @JvmStatic
        fun fromJson(json: String?): ReaderSettings {
            if (json.isNullOrBlank()) return DEFAULT
            return try {
                val o = SettingsJson.compact.parseToJsonElement(json).jsonObject
                val d = DEFAULT
                // Old-set migration: no fontScale but with old absolute fontSize(px) → invert to a slot; otherwise use the new relative slot
                val fontScale = if (o.containsKey("fontScale")) {
                    SettingsJson.optDouble(o, "fontScale", d.fontScale)
                } else if (o.containsKey("fontSize")) {
                    fontSizePxToScale(SettingsJson.optInt(o, "fontSize", d.fontSize))
                } else {
                    d.fontScale
                }
                // Old-set migration: old theme(sepia/white/dark) has no scheme/bgOverride → map into the new color scheme
                //  dark(dark night)→night full set; white(light)/sepia(parchment)→day scheme + background override
                val hasScheme = o.containsKey("scheme")
                val hasBg = o.containsKey("bgOverride")
                val legacyTheme = SettingsJson.optString(o, "theme", "sepia")
                val scheme = if (hasScheme) SettingsJson.optString(o, "scheme", d.scheme)
                else if (legacyTheme == "dark") "night" else d.scheme
                val bgOverride = when {
                    hasBg -> SettingsJson.optString(o, "bgOverride", d.bgOverride)
                    legacyTheme == "white" -> "#ffffff"
                    legacyTheme == "sepia" -> "#f4f2ec"
                    else -> d.bgOverride
                }
                // Old-set migration: old margin(compact/normal/wide) line-width presets → new per-direction independent px (compact = large margin, wide = small margin)
                val hasMargin = o.containsKey("marginTop") || o.containsKey("marginBottom") || o.containsKey("marginLeft") || o.containsKey("marginRight")
                val legacyMargin = SettingsJson.optString(o, "margin", "")
                val marginTop = if (hasMargin) SettingsJson.optInt(o, "marginTop", d.marginTop)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginTop }
                val marginBottom = if (hasMargin) SettingsJson.optInt(o, "marginBottom", d.marginBottom)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginBottom }
                val marginLeft = if (hasMargin) SettingsJson.optInt(o, "marginLeft", d.marginLeft)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginLeft }
                val marginRight = if (hasMargin) SettingsJson.optInt(o, "marginRight", d.marginRight)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginRight }
                ReaderSettings(
                    theme = SettingsJson.optString(o, "theme", d.theme),
                    fontSize = SettingsJson.optInt(o, "fontSize", d.fontSize),
                    // 行距：旧档里必有值（旧默认 1.5），新模型「没配」= null；旧 1.5 视为没配（§5-3），
                    // 否则升级瞬间会把所有书的行高压回 1.5、读不到书自己的行高。
                    lineSpacing = SettingsJson.optDoubleOrNull(o, "lineSpacing")?.takeIf { it != 1.5 },
                    // 首行缩进是本书私有：全局档里即使残留旧键也不读（书没设过 = 不发声明）。
                    // 段间距：新模型存绝对值 em（0..2）；> 2 的值只可能是旧档百分比（0..1000，
                    // 100 = 原书节奏），就地按旧口径迁移（100 → null，其余 ÷100 夹到 0..2）。
                    paragraphSpacing = SettingsJson.optDoubleOrNull(o, "paragraphSpacing")?.let {
                        if (it > 2.0) legacyParagraphSpacingPercentToEm(it) else it.coerceIn(0.0, 2.0)
                    },
                    paragraphGap = SettingsJson.optDouble(o, "paragraphGap", d.paragraphGap),
                    // 字间距：旧档的 0 就是「不加字距」（当时的默认值）⇒ 按没配处理，
                    // 让书自带的 letter-spacing 生效；非 0 的显式档位原样保留。
                    letterSpacing = SettingsJson.optDoubleOrNull(o, "letterSpacing")?.takeIf { it != 0.0 },
                    marginTop = marginTop,
                    marginBottom = marginBottom,
                    marginLeft = marginLeft,
                    marginRight = marginRight,
                    fontBody = SettingsJson.optString(o, "fontBody", d.fontBody),
                    fontTitle = SettingsJson.optString(o, "fontTitle", d.fontTitle),
                    fontCode = SettingsJson.optString(o, "fontCode", d.fontCode),
                    useUserScripts = SettingsJson.optBoolean(o, "useUserScripts", d.useUserScripts),
                    pageAnim = SettingsJson.optBoolean(o, "pageAnim", d.pageAnim),
                    pageAnimationMode = SettingsJson.optString(o, "pageAnimationMode", d.pageAnimationMode),
                    autoContinue = SettingsJson.optBoolean(o, "autoContinue", d.autoContinue),
                    pageNum = SettingsJson.optBoolean(o, "pageNum", d.pageNum),
                    // Old-key migration: coverProportional(false=stretch) → coverStretch(true=stretch), inverted.
                    coverStretch = if (o.containsKey("coverStretch")) {
                        SettingsJson.optBoolean(o, "coverStretch", d.coverStretch)
                    } else if (o.containsKey("coverProportional")) {
                        !SettingsJson.optBoolean(o, "coverProportional", false)
                    } else {
                        d.coverStretch
                    },
                    fontScale = fontScale,
                    scheme = scheme,
                    bgOverride = bgOverride,
                    fgOverride = SettingsJson.optString(o, "fgOverride", d.fgOverride),
                    // `useOriginalStyle` 已退役（P2）：旧档 true 等价于「原书设置」，直接折进 layoutTheme。
                    layoutTheme = if (SettingsJson.optBoolean(o, "useOriginalStyle", false)) "original"
                    else SettingsJson.optString(o, "layoutTheme", d.layoutTheme),
                    brightness = SettingsJson.optInt(o, "brightness", d.brightness),
                    brightnessFollowSystem = SettingsJson.optBoolean(o, "brightnessFollowSystem", d.brightnessFollowSystem),
                    brightnessOffset = SettingsJson.optInt(o, "brightnessOffset", d.brightnessOffset),
                    eyeProtectionLevel = SettingsJson.optInt(o, "eyeProtectionLevel", d.eyeProtectionLevel),
                    brightnessGestureLeft = SettingsJson.optBoolean(o, "brightnessGestureLeft", d.brightnessGestureLeft),
                    brightnessGestureRight = SettingsJson.optBoolean(o, "brightnessGestureRight", d.brightnessGestureRight),
                    brightnessGestureTwo = SettingsJson.optBoolean(o, "brightnessGestureTwo", d.brightnessGestureTwo),
                    showHiddenFonts = SettingsJson.optBoolean(o, "showHiddenFonts", d.showHiddenFonts),
                    fontWeightAnchors = SettingsJson.optWeightAnchors(o, "fontWeightAnchors"),
                    fontWeightAnchorsBySlot = SettingsJson.optWeightAnchorsMap(o, "fontWeightAnchorsBySlot", d.fontWeightAnchorsBySlot),
                    highlightColorSlots = SettingsJson.optIntList(o, "highlightColorSlots", d.highlightColorSlots),
                    underlineColorSlots = SettingsJson.optIntList(o, "underlineColorSlots", d.underlineColorSlots),
                    highlightDefaultSlot = SettingsJson.optInt(o, "highlightDefaultSlot", d.highlightDefaultSlot)
                        .coerceIn(0, d.highlightColorSlots.lastIndex),
                    underlineDefaultSlot = SettingsJson.optInt(o, "underlineDefaultSlot", d.underlineDefaultSlot)
                        .coerceIn(0, d.underlineColorSlots.lastIndex),
                    annotationLastKind = SettingsJson.optString(o, "annotationLastKind", d.annotationLastKind),
                    annotationLastLineStyle = SettingsJson.optString(o, "annotationLastLineStyle", d.annotationLastLineStyle),
                    noteFont = SettingsJson.optString(o, "noteFont", d.noteFont),
                    noteFontScale = SettingsJson.optDouble(o, "noteFontScale", d.noteFontScale.toDouble())
                        .toFloat().coerceIn(MIN_NOTE_FONT_SCALE, MAX_NOTE_FONT_SCALE),
                    noteCollapseAll = SettingsJson.optBoolean(o, "noteCollapseAll", d.noteCollapseAll),
                    noteBorder = SettingsJson.optBoolean(o, "noteBorder", d.noteBorder),
                    noteBackground = SettingsJson.optBoolean(o, "noteBackground", d.noteBackground),
                    noteTextSameColor = SettingsJson.optBoolean(o, "noteTextSameColor", d.noteTextSameColor),
                    cjkLatinSpacing = migrateCjkLatinSpacing(
                        SettingsJson.optDouble(o, "cjkLatinSpacing", d.cjkLatinSpacing),
                        SettingsJson.optInt(o, "schemaVersion", 0),
                    ),
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}
/**
 * 段间距旧档（百分比 0..1000，100 = 原书节奏）→ 新模型绝对值 em（0..2）。
 *
 * 只在**一次性迁移**（`legacy` / `books.version` 升级那一次）调用；之后存档里的数就是 em。
 * 映射口径：100% 等于「不覆盖」⇒ `null`（书自己生效，面板显示原书实测值）；其余按 1% = 0.01em
 * 折算后夹到滑块范围 0..2（旧档 150% → 1.5em，50% → 0.5em，300% 以上一律 2em）。
 */
internal fun legacyParagraphSpacingPercentToEm(percent: Double?): Double? =
    percent?.let { if (it == 100.0) null else (it / 100.0).coerceIn(0.0, 2.0) }
