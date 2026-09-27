package orilumn.reader.engine.paging

import orilumn.reader.io.Logger

/**
 * Q2/R5：切片查询下沉 common paging（纯函数，无引擎依赖）。
 *
 * 章内字符所在页：首个包含该字符的切片；无内容（越界/空章）回末片，无片回 null。
 * 原桌面 `DesktopReaderHost.landAnchor` 的查找语义，收敛至此供控制器查询复用。
 * 回末片是钳制（上游 anchor 算错也会落到这里）：保留语义，但记 w 让漂移可见。
 * TempNavigation.pageIndexForChar 是显式 clamp 版本，两处并存——隐式这处不许再加调用方。
 */
fun pageSliceAtChar(slices: List<PageSlice>, char: Int): PageSlice? {
    if (slices.isEmpty()) return null
    return slices.firstOrNull { char >= it.charStart && char < it.charEnd }
        ?: slices.last().also {
            Logger.w("Orilumn.PAGE", "pageSliceAtChar off-table char=$char pinned to last slice")
        }
}
