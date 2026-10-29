package orilumn.reader.data.settings

/**
 * 设置双层存储的共享实现（以平板逻辑为基准，双端共用）。
 *
 * 归属唯一来源是 [SettingsScope]（2026-10-29 定口）：
 * - **全局**（`settings/reader.json`）：亮度族、阅读主题、笔记族、续读/页码开关、
 *   字体列表隐藏过滤、持久化元数据 —— 与某本书无关的阅读环境与读者印记；
 * - **私有**（`settings/books/{bookId}.json`，稀疏覆盖）：字号/行距/字距/段距/页边距/
 *   字体族与字重/排版主题/翻页开关/首行缩进 —— 每本书默认原书样式。
 *
 * **私有项永不写全局**：本书改字号/行距/字体不传染给别的书，也不被别的书改掉。
 * 这条纪律就是本类存在的理由——两侧各自独立落盘，**没有任何交叉写入**。
 *
 * 读：[effectiveFor] = 全局记忆 + 本书覆盖层（只叠私有项）。
 * 写：[persist] 两条独立写——全局项只写全局，私有项只写本书（空层即删文件）。
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
     * 提交 next（调用方先把 next 作为生效值刷新 UI）。返回写后的全局记忆。
     *
     * 两条独立写，互不影响：
     * 1. **全局侧**：只把 [SettingsScope.GLOBAL] 里的字段写回全局文件；一个私有项都不带
     *    （`withGlobalFrom` 只拷全局字段），所以改一本书的排版永远不会改到别的书；
     * 2. **本书侧**：只把本书的私有层写进 `books/{id}.json`（预设差异 + 稀疏归一），
     *    全局项一个都不带；层为空即删文件（`BookSettingsStore.save`）。
     *
     * 两侧都先比对再写：内容没变就不落盘（拖动滑条回到原值、开关点两下等）。
     */
    fun persist(bookId: Long?, next: ReaderSettings): ReaderSettings {
        val g0 = globalStore.load()
        val g1 = g0.withGlobalFrom(next)
        if (g1 != g0) globalStore.save(g1)

        if (bookId != null && bookId >= 0) {
            val o0 = bookStore.load(bookId)
            val o1 = BookSettings.fromReaderSettings(next)
            if (o1 != o0) bookStore.save(bookId, o1)
        }
        return g1
    }
}
