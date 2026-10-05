package orilumn.reader.data.settings

import kotlinx.serialization.json.JsonObject

/**
 * 设置双层存储的共享实现（以平板逻辑为基准，双端共用）。
 *
 * 两类划分（用户层契约）：
 * - 纯全局：亮度族（brightness/brightnessFollowSystem/brightnessOffset/eyeProtectionLevel/
 *   brightnessGestureLeft/Right/Two）、夜间 `scheme`、打开书自动阅读 `autoContinue`、
 *   字体管理隐藏字体开关 `showHiddenFonts`、用户按槽位选的字重
 *   `fontWeightAnchorsBySlot`/`fontWeightAnchors`——不出 overlay，直写全局；
 * - 其余（排版/字体/阅读主题覆盖等）按书私有：本书只钉住**本次实际改动的字段**
 *   （稀疏 overlay，没动过的字段永不快照）；改动项同时直写全局（传染给没有此项的书）。
 *   全量快照会把别处设的全局值冻进本书 → “这本书没调过但值变了”，以及
 *   “调了全局这本书不动”——两类标题/正文字体串扰即此（回归见 ReaderSettingsTest）。
 *   存量全量 overlay 在每次 persist 中自愈：与新全局一致、且本次没动的 pin 恢复跟随。
 *
 * 读：`effectiveFor`——有私用私、无私用全局（稀疏兼容：后加字段老书没有即走全局）。
 * 写：`persist`——bookOnly（原书设置）只写本书，不传染。
 *
 * 平台缝只有三处，本类一概不碰（壳负责）：①系统字体发现；②亮度系统接口；
 * ③键绑定。调用方保证 IO 线程（与两侧 Store 注释一致）。
 */
class PerBookSettings(
    private val globalStore: ReaderSettingsStore,
    private val bookStore: BookSettingsStore,
) {
    /** 打开某书时的生效值（bookId 为 null 或负数 → 纯全局）。 */
    fun effectiveFor(bookId: Long?): ReaderSettings {
        val global = globalStore.load()
        if (bookId == null || bookId < 0) return global
        return global.applyOverlay(bookStore.load(bookId))
    }

    /** 打开某书时的原始私有层（首次开书探针判“钉没钉过”用；无书即空层）。 */
    fun overlayFor(bookId: Long?): BookSettings =
        if (bookId == null || bookId < 0) BookSettings.EMPTY else bookStore.load(bookId)

    /**
     * 提交 next（调用方先把 next 作为生效值刷新 UI）。
     * 返回写后全局（壳刷新基线用）。
     */
    fun persist(bookId: Long?, next: ReaderSettings, bookOnly: Boolean = false): ReaderSettings {
        val storedGlobal = globalStore.load()
        val storedOverlay =
            if (bookId != null && bookId >= 0) bookStore.load(bookId) else BookSettings.EMPTY
        val baseline = storedGlobal.applyOverlay(storedOverlay)
        val changed = BookSettings.changedFrom(next, baseline)
        val global = if (bookOnly) {
            storedGlobal
        } else {
            storedGlobal.mergeFrom(changed).copy(
                scheme = next.scheme,
                brightness = next.brightness,
                brightnessFollowSystem = next.brightnessFollowSystem,
                brightnessOffset = next.brightnessOffset,
                eyeProtectionLevel = next.eyeProtectionLevel,
                brightnessGestureLeft = next.brightnessGestureLeft,
                brightnessGestureRight = next.brightnessGestureRight,
                brightnessGestureTwo = next.brightnessGestureTwo,
                autoContinue = next.autoContinue,
                showHiddenFonts = next.showHiddenFonts,
                fontWeightAnchors = next.fontWeightAnchors,
                fontWeightAnchorsBySlot = next.fontWeightAnchorsBySlot,
                cjkLatinSpacing = next.cjkLatinSpacing,
            )
        }
        globalStore.save(global)
        if (bookId != null && bookId >= 0) {
            // 稀疏 overlay：既有 pin + 本次改动（改动胜）；没动过的字段永不快照——全量快照
            // 会把别处设的全局值冻进本书（“没调过但值变了”），也让本书从此不再跟随全局
            // （“调了全局这本书不动”）。toJsonObject 只含非 null 键，changed 只含本次改动键。
            val merged = JsonObject(storedOverlay.toJsonObject() + changed.toJsonObject())
            // 自愈存量全量 overlay 的陈旧 pin：与新全局一致、且本次没动的字段恢复跟随
            // （视觉无变化；本次刚改的字段必然与全局一致，豁免——那是用户的明确意图）。
            val changedKeys = changed.toJsonObject().keys
            val globalJson = global.toJsonObject()
            val cleaned = JsonObject(
                merged.filter { (k, v) -> k in changedKeys || globalJson[k] != v },
            )
            bookStore.save(bookId, BookSettings.fromJson(cleaned.toString()))
        }
        return global
    }
}
