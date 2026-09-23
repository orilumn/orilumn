package orilumn.reader.engine.paging

/**
 * Q2/R5：切片查询下沉 common paging（纯函数，无引擎依赖）。
 *
 * 章内字符所在页：首个包含该字符的切片；无内容（越界/空章）回末片，无片回 null。
 * 原桌面 `DesktopReaderHost.landAnchor` 的查找语义，收敛至此供控制器查询复用。
 */
fun pageSliceAtChar(slices: List<PageSlice>, char: Int): PageSlice? =
    slices.firstOrNull { char >= it.charStart && char < it.charEnd }
        ?: slices.lastOrNull()
