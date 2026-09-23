package orilumn.reader.ui.reader

/**
 * 定位回抛装饰器：把宿主返回的每个 [ReaderPos] 同步给调用方状态（目录高亮/重排锚点/退出保存），
 * 其余全部透传。[onSaveProgress] 同样携带定位一并回抛。
 *
 * Q1-1 收敛：原平板 `ReaderActivity` 与桌面 `ReaderView` 各一份逐行同义实现，合入 shared-ui。
 */
class SnapshotReaderHost(
    val delegate: ReaderHost,
    private val onPos: (ReaderPos) -> Unit,
) : ReaderHost by delegate {
    override suspend fun open(): ReaderPos? = delegate.open()?.also(onPos)
    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? =
        delegate.adjacent(pos, direction)?.also(onPos)
    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        delegate.neighborChapterStart(chapter, direction)?.also(onPos)
    override suspend fun pageAtFraction(fraction: Double): ReaderPos? =
        delegate.pageAtFraction(fraction)?.also(onPos)
    override suspend fun chapterStart(index: Int): ReaderPos? =
        delegate.chapterStart(index)?.also(onPos)
    override fun onSaveProgress(pos: ReaderPos) {
        onPos(pos)
        delegate.onSaveProgress(pos)
    }
}
