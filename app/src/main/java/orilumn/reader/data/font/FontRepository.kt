package orilumn.reader.data.font

import android.content.Context
import android.net.Uri
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Font repository (Android thin shell over shared [FontLibrary], F3).
 *
 * 纯逻辑（导入解析/原子落盘/删除/孤儿自愈/统一列表）全在 `:common FontLibrary`；
 * 本类只剩 Android 接缝：SAF `Uri` 读字节、私有 `filesDir/fonts` 目录。
 * 调用方（设置面板/字体管理/阅读面）API 不变。
 *
 * S34b：字体池随 `font_faces` 表迁入 SQLDelight，本类只持 [LibraryDb]（Room [FontDao]
 * 已删除）；F3 起连文件逻辑也下沉，见 [FontLibrary]。
 */
class FontRepository(
    private val context: Context,
    db: LibraryDb,
) {
    private val library: FontLibrary

    init {
        library = FontLibrary(
            db = db,
            fs = FileSystem.SYSTEM,
            fontsDir = java.io.File(context.filesDir, "fonts").absolutePath.toPath(),
        )
    }

    /** All fonts (persistent,含隐藏；选择器候选经 [FontLibrary.visibleEntries] 过滤）. */
    suspend fun list(): List<FontFace> = library.list()

    /** F4c 统一列表（系统 + 导入，含隐藏；展示层分区/过滤）。 */
    suspend fun entries(): List<orilumn.reader.data.font.FontEntry> =
        library.allEntries(library.list())

    /** Load a font's bytes by id (key = `FontFace.id`); null when the file is deleted or the record missing. */
    fun fontBytes(id: Long): ByteArray? = library.fontBytes(id)

    /** Import a font file (SAF uri) → shared import core. Returns the updated full list. */
    suspend fun import(uri: Uri): List<FontFace> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        library.importBytes(bytes, uri.lastPathSegment ?: "font")
    }

    /** Import a local temp file (cache file landed after WIFI upload) → shared import core. */
    suspend fun importFile(file: java.io.File): List<FontFace> =
        library.importFile(file.absolutePath.toPath())

    /** Delete an imported font: clear the DB row + delete the file copy. */
    suspend fun delete(face: FontFace) = library.delete(face)

    /** Delete all weights of an imported family（同名系统行保留，见 [FontLibrary.deleteFamily]). */
    suspend fun deleteFamily(familyName: String) = library.deleteFamily(familyName)

    /** Re-register orphan font files (bytes on disk, DB row lost). */
    suspend fun reconcileOrphanFiles(): List<FontFace> = library.reconcileOrphanFiles()

    /** F 系列：用户隐藏/取消隐藏（系统/导入通用）. */
    suspend fun setHidden(id: Long, hidden: Boolean) = library.setHidden(id, hidden)

    /** F 系列：系统字形落行 + 返回全量表（调用方喂平台枚举，族 + 字重名）. */
    suspend fun syncSystemFaces(
        faces: List<SystemFontFace>,
        localizedNames: Map<String, String> = emptyMap(),
    ): List<FontFace> = library.syncSystemFaces(faces, localizedNames)

    /**
     * F 系列中文名链（方案B，桌面 `NameTableChineseNames` 同式，不含 CoreText 步）：
     * 扫 `/system/fonts`，用 name 表变体语言记录建「拉丁族名 → 中文族名」映射；
     * 解析失败的文件跳过（调用方回退族名本身）。
     */
    fun systemFontLocalizedNames(): Map<String, String> {
        val dir = java.io.File("/system/fonts")
        val files = runCatching { dir.listFiles { f -> f.isFile } }.getOrNull() ?: return emptyMap()
        val parser = FontParser()
        val out = LinkedHashMap<String, String>()
        for (f in files) {
            val names = runCatching { parser.familyNamesOf(f.readBytes()) }.getOrNull() ?: continue
            val latin = names.latin ?: continue
            val chinese = names.chinese ?: continue
            out.putIfAbsent(latin, chinese)
        }
        return out
    }
}
