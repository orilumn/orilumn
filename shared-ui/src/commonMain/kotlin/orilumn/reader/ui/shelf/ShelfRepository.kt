package orilumn.reader.ui.shelf

import kotlinx.coroutines.flow.Flow

/**
 * 书架数据源抽象：shared-ui 不依赖宿主数据库实现（Android/桌面均由 SQLDelight 的 LibraryDb 按此契约提供）。
 */
interface ShelfRepository {
    /** 书架书目流（按给定排序）。 */
    fun books(sort: ShelfSort): Flow<List<ShelfBook>>

    /** 一次性返回全库（用于导入查重等非流式场景）。 */
    suspend fun allBooks(): List<ShelfBook>

    /** 从书架删除一本书（含其阅读进度）。 */
    suspend fun delete(id: Long)

    /** 记录一次「打开阅读」时间戳（刷新「最近阅读优先」排序）。 */
    suspend fun touchRead(id: Long)
}