package orilumn.reader.data.settings

/**
 * 设置双层存储的共享实现（以平板逻辑为基准，双端共用）。
 *
 * 两类划分（用户层契约）：
 * - 纯全局：亮度族（brightness/brightnessFollowSystem/brightnessOffset/eyeProtectionLevel/
 *   brightnessGestureLeft/Right/Two）、夜间 `scheme`、打开书自动阅读 `autoContinue`、
 *   字体管理隐藏字体开关 `showHiddenFonts`——不出 overlay，直写全局；
 * - 其余（排版/字体/阅读主题覆盖等）按书私有：本书一动，全量快照进本书 overlay，
 *   修改项同时直写全局（传染给没有此项的书）。
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
            )
        }
        globalStore.save(global)
        if (bookId != null && bookId >= 0) {
            val o = if (changed.isEmpty) storedOverlay else BookSettings.fromReaderSettings(next)
            bookStore.save(bookId, o)
        }
        return global
    }
}
