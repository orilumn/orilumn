package orilumn.reader.data.settings

/**
 * 设置字段归属的**唯一来源**（用户层·纯数据，无平台代码）。
 *
 * 两级模型（2026-10-29 定口，见 `docs/设置模型整改计划.md`）：
 * - **全局**（`settings/reader.json`，唯一全局区）：与某本书无关的阅读环境与读者印记
 *   —— 亮度族、阅读主题（`scheme`/`bgOverride`/`fgOverride`）、笔记族（色槽/默认格/
 *   上次类别线型/批注字体字号）、续读开关、页码开关、字体列表隐藏过滤、持久化元数据；
 * - **私有**（`settings/books/{bookId}.json`，一本书一份）：读者能针对一本书配的一切
 *   —— 字号/行距/字距/段距/页边距/字体族与字重/排版主题/翻页开关/首行缩进。
 *
 * 两条铁律：
 * 1. **私有项永不写全局**。全局文件里出现私有键 = 旧档残留，`load()` 时被 [onlyGlobal] 抹回预设；
 * 2. **全局项永不写本书**。本书文件里出现全局键 = 旧档残留，`BookSettings.fromJson` 读不到（无字段）。
 *
 * 本表由 `SettingsPersistenceScopeTest` 用源码扫描锁死：`ReaderSettings` 的每个字段必须
 * **恰好**落在 [GLOBAL] / [PRIVATE] / [SESSION_ONLY] 之一，新增字段忘登记即红。
 *
 * @see PerBookSettings 读（合成生效值）与写（两侧独立落盘）
 */
object SettingsScope {

    /** 全局区字段（只写 `reader.json`）。 */
    val GLOBAL: Set<String> = setOf(
        // 旧档遗留的主题名（新语义用 scheme + bgOverride，见 ReaderSettings.fromJson）。
        "theme",
        // 阅读主题：白天/夜间、背景与墨色覆盖。
        "scheme", "bgOverride", "fgOverride",
        // 亮度族：物理背光 / 遮罩 / 手势，与书无关。
        "brightness", "brightnessFollowSystem", "brightnessOffset", "eyeProtectionLevel",
        "brightnessGestureLeft", "brightnessGestureRight", "brightnessGestureTwo",
        // 开关：启动时继续阅读、显示页码（页码尚无渲染实现，只改归属）。
        "autoContinue", "pageNum",
        // 字体管理列表的隐藏/显示过滤（与书无关，换书不复位）。
        "showHiddenFonts",
        // 笔记族：色槽、新建默认格、浮动菜单上次类别/线型、批注字体/字重与字号。
        "highlightColorSlots", "underlineColorSlots",
        "highlightDefaultSlot", "underlineDefaultSlot",
        "annotationLastKind", "annotationLastLineStyle",
        "noteFont", "noteFontWeight", "noteFontScale",
        // 旁注画法（折叠 / 描边 / 底色 / 文字用色）：只改覆盖层怎么画，不碰正文排版。
        "noteCollapseAll", "noteBorder", "noteBackground", "noteTextSameColor",
        // 持久化元数据（只增不改，见 ReaderSettings.schemaVersion KDoc）。
        "schemaVersion",
    )

    /** 本书私有字段（只写 `books/{bookId}.json`）。 */
    val PRIVATE: Set<String> = setOf(
        // 排版主题：原书 / 现代 / 传统。
        "layoutTheme",
        // 字号与字号槽。
        "fontSize", "fontScale",
        // 行距（`null` = 没配 = 不声明，书自己的行高生效）。
        "lineSpacing",
        // 首行缩进（`null` = 没配 = 不声明；`0` = 明确不缩进；N = N em）。
        "firstLineIndent",
        // 段间距 / 疏密 / 字距 / 混排字距。
        "paragraphSpacing", "paragraphGap", "letterSpacing", "cjkLatinSpacing",
        // 页边距四向。
        "marginTop", "marginBottom", "marginLeft", "marginRight",
        // 字体族三槽与按槽字重锚点。
        "fontBody", "fontTitle", "fontCode",
        "fontWeightAnchors", "fontWeightAnchorsBySlot",
        // 用户自定义样式表开关（当前无消费者，保留字段）。
        "useUserScripts",
        // 翻页动画与封面拉伸。
        "pageAnim", "pageAnimationMode", "coverStretch",
    )

    /** 只存在于本次会话的读数（不落盘、不是设置项）：面板打开时量出的原书缩进/行高/段距/字距。 */
    val SESSION_ONLY: Set<String> = setOf("probeIndent", "probeLineSpacing", "probeParagraphSpacing", "probeLetterSpacing")
}

/**
 * 把一份**生效值**收敛成「全局项保留、私有项回预设」——`ReaderSettingsStore` 读写都走它，
 * 全局文件里从此不会出现私有键（旧档残留读进来即被抹掉）。
 */
fun ReaderSettings.onlyGlobal(): ReaderSettings {
    val d = ReaderSettings.DEFAULT
    return copy(
        fontSize = d.fontSize,
        fontScale = d.fontScale,
        lineSpacing = d.lineSpacing,
        firstLineIndent = d.firstLineIndent,
        probeIndent = null,
        probeLineSpacing = null,
        probeParagraphSpacing = null,
        probeLetterSpacing = null,
        paragraphSpacing = d.paragraphSpacing,
        paragraphGap = d.paragraphGap,
        letterSpacing = d.letterSpacing,
        cjkLatinSpacing = d.cjkLatinSpacing,
        marginTop = d.marginTop,
        marginBottom = d.marginBottom,
        marginLeft = d.marginLeft,
        marginRight = d.marginRight,
        fontBody = d.fontBody,
        fontTitle = d.fontTitle,
        fontCode = d.fontCode,
        useUserScripts = d.useUserScripts,
        pageAnim = d.pageAnim,
        pageAnimationMode = d.pageAnimationMode,
        coverStretch = d.coverStretch,
        layoutTheme = d.layoutTheme,
        fontWeightAnchors = d.fontWeightAnchors,
        fontWeightAnchorsBySlot = d.fontWeightAnchorsBySlot,
    )
}

/**
 * 把 [next] 里的**全局项**写回本份全局记忆，私有项一概不动。
 *
 * `PerBookSettings.persist` 用它算「全局侧要不要落盘」：私有项再多也只影响本书，
 * 不会顺带把别的书的环境改掉。
 */
fun ReaderSettings.withGlobalFrom(next: ReaderSettings): ReaderSettings = copy(
    theme = next.theme,
    scheme = next.scheme,
    bgOverride = next.bgOverride,
    fgOverride = next.fgOverride,
    brightness = next.brightness,
    brightnessFollowSystem = next.brightnessFollowSystem,
    brightnessOffset = next.brightnessOffset,
    eyeProtectionLevel = next.eyeProtectionLevel,
    brightnessGestureLeft = next.brightnessGestureLeft,
    brightnessGestureRight = next.brightnessGestureRight,
    brightnessGestureTwo = next.brightnessGestureTwo,
    autoContinue = next.autoContinue,
    pageNum = next.pageNum,
    showHiddenFonts = next.showHiddenFonts,
    highlightColorSlots = next.highlightColorSlots,
    underlineColorSlots = next.underlineColorSlots,
    highlightDefaultSlot = next.highlightDefaultSlot,
    underlineDefaultSlot = next.underlineDefaultSlot,
    annotationLastKind = next.annotationLastKind,
    annotationLastLineStyle = next.annotationLastLineStyle,
    noteFont = next.noteFont,
    noteFontWeight = next.noteFontWeight,
    noteFontScale = next.noteFontScale,
    noteCollapseAll = next.noteCollapseAll,
    noteBorder = next.noteBorder,
    noteBackground = next.noteBackground,
    noteTextSameColor = next.noteTextSameColor,
    schemaVersion = next.schemaVersion,
)

/**
 * **稀疏归一**：只留「和本项目预设不同」的私有项。
 *
 * 这是「每本书默认原书设置」的落地表示：
 * - 与预设相同 ⇒ 该键不落盘（`null`）⇒ 以后改预设能到达这本书；
 * - 文件因此只剩读者真动过的项，空文件即删除（`BookSettingsStore.save` 负责）；
 * - 「原书设置」按钮 = 把本书覆盖层清空，语义唯一（文件不存在 ⇔ 完全听书）。
 */
fun BookSettings.normalized(): BookSettings {
    val d = ReaderSettings.DEFAULT
    return BookSettings(
        layoutTheme = layoutTheme.takeIf { it != d.layoutTheme },
        fontSize = fontSize.takeIf { it != d.fontSize },
        fontScale = fontScale.takeIf { it != d.fontScale },
        lineSpacing = lineSpacing.takeIf { it != d.lineSpacing },
        cjkLatinSpacing = cjkLatinSpacing.takeIf { it != d.cjkLatinSpacing },
        firstLineIndent = firstLineIndent.takeIf { it != d.firstLineIndent },
        paragraphSpacing = paragraphSpacing.takeIf { it != d.paragraphSpacing },
        paragraphGap = paragraphGap.takeIf { it != d.paragraphGap },
        letterSpacing = letterSpacing.takeIf { it != d.letterSpacing },
        marginTop = marginTop.takeIf { it != d.marginTop },
        marginBottom = marginBottom.takeIf { it != d.marginBottom },
        marginLeft = marginLeft.takeIf { it != d.marginLeft },
        marginRight = marginRight.takeIf { it != d.marginRight },
        fontBody = fontBody.takeIf { it != d.fontBody },
        fontTitle = fontTitle.takeIf { it != d.fontTitle },
        fontCode = fontCode.takeIf { it != d.fontCode },
        useUserScripts = useUserScripts.takeIf { it != d.useUserScripts },
        pageAnim = pageAnim.takeIf { it != d.pageAnim },
        pageAnimationMode = pageAnimationMode.takeIf { it != d.pageAnimationMode },
        coverStretch = coverStretch.takeIf { it != d.coverStretch },
        fontWeightAnchors = fontWeightAnchors.takeIf { it != d.fontWeightAnchors },
        fontWeightAnchorsBySlot = fontWeightAnchorsBySlot.takeIf { it != d.fontWeightAnchorsBySlot },
    )
}
