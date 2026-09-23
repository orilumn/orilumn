package orilumn.reader.ui.shelf

import androidx.compose.ui.graphics.ImageBitmap
import io.github.vinceglb.filekit.PlatformFile

/** 扫描得到的书元数据（用于导入查重；失败返回 null）。 */
data class ScannedShelfBook(
    val title: String,
    val author: String?,
)

/** 一轮导入的结果：成功/失败计数 + 聚合的重复项（title 供提示）。 */
data class ImportOutcome(
    val imported: Int = 0,
    val failed: Int = 0,
    val duplicates: List<Pair<PlatformFile, String>> = emptyList(),
)

/**
 * 宿主能力抽象：shared-ui 只持有协议，实现在各宿主壳（Android/桌面）。
 * 导入/封面读取等平台绑定能力收敛在这里，UI 层其余部分全部通用。
 */
interface ShelfHost {
    /** 解析用户所选文件的元数据（标题/作者），解析失败返回 null（不落库、不拷贝）。 */
    suspend fun scan(file: PlatformFile): ScannedShelfBook?

    /**
     * 导入一本书。`overwrite=true` 时宿主先删去同名旧书再导入（覆盖语义）。
     * 返回是否成功。
     */
    suspend fun import(file: PlatformFile, overwrite: Boolean): Boolean

    /** 读取封面：按 [ShelfBook.coverRef] 解码成位图；null 表示无封面或解码失败（UI 回退占位符）。 */
    suspend fun loadCover(coverRef: String?): ImageBitmap?

    /** 打开一本书进入阅读面（宿主负责导航）。 */
    fun openBook(book: ShelfBook)
}