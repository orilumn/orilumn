package orilumn.reader.ui.shelf

import io.github.vinceglb.filekit.PlatformFile

/**
 * 书架导入编排（纯逻辑，可单测）：
 * 逐本 scan → 查重（书名/作者一致）→ 非重复直接导入并入计数；重复项聚合到最后统一向用户确认。
 * 对原 app MainActivity.onBooksPicked/onOverwriteConfirm 的语义逐字对齐。
 */
class ShelfImporter(
    private val repository: ShelfRepository,
    private val host: ShelfHost,
) {

    /** 一轮导入：处理一批文件，返回（成功数、失败数、聚合的重复项）。[onProgress] 逐本上报进度，默认无操作。 */
    suspend fun run(
        files: List<PlatformFile>,
        onProgress: (index: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportOutcome {
        var imported = 0
        var failed = 0
        val dups = mutableListOf<Pair<PlatformFile, String>>()
        val existing = repository.allBooks()
        files.forEachIndexed { i, file ->
            onProgress(i + 1, files.size)
            val scanned = host.scan(file)
            if (scanned != null && existing.any { it.sameBookAs(scanned.title, scanned.author) }) {
                dups += file to scanned.title
            } else if (host.import(file, overwrite = false)) {
                imported++
            } else {
                failed++
            }
        }
        return ImportOutcome(imported, failed, dups)
    }

    /** 「覆盖」确认：对每个重复项先删旧书再导入，返回（成功、失败）计数。[onProgress] 逐本上报进度，默认无操作。 */
    suspend fun overwrite(
        items: List<Pair<PlatformFile, String>>,
        onProgress: (index: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        items.forEachIndexed { i, (file, _) ->
            onProgress(i + 1, items.size)
            if (host.import(file, overwrite = true)) ok++ else fail++
        }
        return ok to fail
    }
}