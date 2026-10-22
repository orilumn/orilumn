package orilumn.reader.ui.reader

/**
 * 定位回抛装饰器：把宿主返回的每个 [ReaderPos] 同步给调用方状态（目录高亮/重排锚点/退出保存），
 * 其余全部透传。[onSaveProgress] 同样携带定位一并回抛。
 *
 * [onNavigated] 只报**用户真的换了位置**（开书/翻页/跳章/进度条/章首），供调用方松开改参锚点
 * （见 `RelayoutAnchor`）：重排落位不报（那是同一次交互内的落位），[onSaveProgress] 也不报
 * （500ms 防抖会把落位页再报一遍，松开就白钉了）。
 *
 * Q1-1 收敛：原平板 `ReaderActivity` 与桌面 `ReaderView` 各一份逐行同义实现，合入 shared-ui。
 */
class SnapshotReaderHost(
    val delegate: ReaderHost,
    private val onPos: (ReaderPos) -> Unit,
    /** 真的换了位置（不含重排落位与进度保存）；默认什么都不做。 */
    private val onNavigated: (ReaderPos) -> Unit = {},
) : ReaderHost by delegate {
    override suspend fun open(): ReaderPos? = delegate.open()?.also { onNavigated(it); onPos(it) }
    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? =
        delegate.adjacent(pos, direction)?.also { onNavigated(it); onPos(it) }
    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        delegate.neighborChapterStart(chapter, direction)?.also { onNavigated(it); onPos(it) }
    override suspend fun pageAtFraction(fraction: Double): ReaderPos? =
        delegate.pageAtFraction(fraction)?.also { onNavigated(it); onPos(it) }
    override suspend fun chapterStart(index: Int): ReaderPos? =
        delegate.chapterStart(index)?.also { onNavigated(it); onPos(it) }
    override fun onSaveProgress(pos: ReaderPos) {
        onPos(pos)
        delegate.onSaveProgress(pos)
    }
}
