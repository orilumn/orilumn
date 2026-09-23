package orilumn.reader.data.font

import orilumn.reader.data.db.LibraryDb
import orilumn.reader.io.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 统一字体列表项：系统族（平台枚举落行）+ 导入族同列展示（F 系列，
 * 见 `docs/F-系统字体统一方案.md`）。排序留给展示层（沿用拼音 Collator 口径），
 * 这里只做合并 + 隐藏过滤。
 */
sealed interface FontEntry {
    val family: String
    val hidden: Boolean

    /**
     * 展示名（渲染层统一入口）：系统行优先落库的本地化名——中文名链「name 表直读（方案B）
     * 优先 → macOS CoreText（方案A）补缺」（仅当非空且异于族名，否则回退族名本身，
     * name 表/CoreText 都没有本地化名时是什么显示什么）；导入行走族名（导入时族名已按
     * name 表中文口径命名）。逻辑键取字形/槽位仍用 [family]。
     */
    val display: String
        get() = when (this) {
            is System ->
                displayName.trim().takeIf { it.isNotEmpty() && it != family } ?: family
            is Imported -> family
        }

    data class System(
        override val family: String,
        val id: Long,
        /** 字重/风格名（platform `systemFontFaces` 枚举落行；空 = 该族无细分字重）。 */
        val subfamily: String = "",
        override val hidden: Boolean,
        /** 落库本地化族名（中文名链：name 表直读优先，macOS 再补 CoreText；空 = 未取得，展示回退族名）。 */
        val displayName: String = "",
    ) : FontEntry

    data class Imported(val face: FontFace) : FontEntry {
        override val family: String get() = face.familyName
        override val hidden: Boolean get() = face.hidden
    }
}

/**
 * F3 共享字体库（`:common`）：`:app FontRepository` 的全部纯逻辑搬运
 * （导入解析/原子落盘/删除/孤儿自愈/统一列表），平台只剩三样注入：
 * 字节来源（SAF Uri / FileKit 文件 / WiFi 落盘）、私有 `fontsDir`、中文名来源
 * （name 表直读方案B + macOS CoreText 方案A，都在平台层，见 `FontEntry.display`）。
 * 文件 IO 经 okio（commonMain 可用），与 SQLDelight 同一虚拟文件抽象。
 */
class FontLibrary(
    private val db: LibraryDb,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val fontsDir: Path,
    private val logTag: String = "Orilumn.Font",
) {
    /** 全量字体行（持久化；含隐藏）。 */
    suspend fun list(): List<FontFace> = db.allFonts()

    /** 按 id 取字节（key = [FontFace.id]）；缺文件只报空不断行，残留行由管理页显式删除，阅读侧永不写库。 */
    fun fontBytes(id: Long): ByteArray? = runCatching {
        val face = db.fontByIdBlocking(id) ?: return null
        val path = face.path ?: return null
        val bytes = runCatching { fs.read(path.toPath()) { readByteArray() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Logger.w(logTag, "font file unreadable id=$id family=${face.familyName} path=$path")
            return null
        }
        bytes
    }.getOrNull()

    /**
     * 导入核心：读失败回全量表；空/不可解析/不可用语种字节驳回。
     * 同 (family, subfamily, source) 重导覆盖（唯一索引语义）。
     */
    suspend fun importBytes(bytes: ByteArray?, name: String): List<FontFace> = withContext(Dispatchers.IO) {
        if (bytes == null || bytes.isEmpty()) {
            Logger.w(logTag, "import failed: cannot read $name")
            return@withContext db.allFonts()
        }
        val res = runCatching { FontParser().parse(bytes) }.getOrNull()
        if (res == null || !res.valid) {
            Logger.w(logTag, "import rejected: not a parseable font $name")
            return@withContext db.allFonts()
        }
        val lang = FontClassifier.classify(res)
        if (!FontClassifier.isUsable(lang)) {
            Logger.w(logTag, "import rejected: unusable lang=$lang $name")
            return@withContext db.allFonts()
        }
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in FONT_EXTS } ?: "ttf"
        val family = pickCnFamilyName(res.familyName)
        val sub = res.subfamily?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val safe = if (sub.isEmpty()) family else "$family-$sub"
        val dispName = "$safe.$ext"
        val path = resolveFontTarget(dispName, bytes)
            ?: run { Logger.w(logTag, "import rejected: cannot write fonts $name"); return@withContext db.allFonts() }
        db.upsertFonts(
            listOf(
                FontFace(
                    familyName = family,
                    displayName = safe,
                    subfamily = sub,
                    source = FontFace.SOURCE_IMPORTED,
                    path = path,
                    lang = lang,
                ),
            ),
        )
        Logger.i(logTag, "imported family=$family subfamily=${if (sub.isEmpty()) "(none)" else sub} lang=$lang -> $dispName")
        return@withContext db.allFonts()
    }

    /** 经已落盘文件导入（WiFi 上传落盘 / 桌面文件选择后都走这里）。 */
    suspend fun importFile(path: Path): List<FontFace> = withContext(Dispatchers.IO) {
        importBytes(runCatching { fs.read(path) { readByteArray() } }.getOrNull(), path.name)
    }

    /** 删除一条导入字体：清 DB 行 + 删私有副本（系统行无 path，天然跳过）。 */
    suspend fun delete(face: FontFace) = withContext(Dispatchers.IO) {
        deleteFileBytes(face.path)
        db.deleteFont(face.id)
        Logger.i(logTag, "deleted font id=${face.id} family=${face.familyName}")
    }

    /**
     * 按家族删除：**仅导入行**（删文件 + 逐行删；同名系统行保留——展示层系统行只给隐藏按钮，
     * 不会调到这里；防御性过滤保证调错也不丢系统行）。
     */
    suspend fun deleteFamily(familyName: String) = withContext(Dispatchers.IO) {
        val faces = db.allFonts().filter { it.familyName == familyName && it.source == FontFace.SOURCE_IMPORTED }
        faces.forEach { deleteFileBytes(it.path) }
        faces.forEach { db.deleteFont(it.id) }
        Logger.i(logTag, "deleted family=$familyName (${faces.size} weight(s))")
    }

    /** F 系列：用户隐藏/取消隐藏（系统/导入通用）。 */
    suspend fun setHidden(id: Long, hidden: Boolean) = db.setFontHidden(id, hidden)

    /**
     * 系统字形落行（UPSERT：冲突只刷 displayName，hidden 保留）+ 返回全量表。
     * 调用方喂平台枚举（engine-skia `systemFontFaces`，族 + 字重名）；
     * [localizedNames] 为中文名链结果——name 表直读（方案B，平台无关）
     * + macOS CoreText（方案A）补缺，同键 name 表胜出；非 mac 平台留空即写回族名，
     * 展示层 [FontEntry.display] 无本地化名时回退族名本身。
     */
    suspend fun syncSystemFaces(
        faces: List<SystemFontFace>,
        localizedNames: Map<String, String> = emptyMap(),
    ): List<FontFace> = withContext(Dispatchers.IO) {
        db.syncSystemFonts(faces, localizedNames)
        db.allFonts()
    }

    /** 统一列表（全量，含隐藏；展示层按 hidden 分区/标态）。 */
    fun allEntries(faces: List<FontFace>): List<FontEntry> = faces.map { it.toEntry() }

    /** 选择器候选（隐藏过滤）。 */
    fun visibleEntries(faces: List<FontFace>): List<FontEntry> =
        faces.filter { !it.hidden }.map { it.toEntry() }

    private fun FontFace.toEntry(): FontEntry =
        if (source == FontFace.SOURCE_SYSTEM) {
            FontEntry.System(familyName, id, subfamily, hidden, displayName)
        } else {
            FontEntry.Imported(this)
        }

    /**
     * 孤儿文件自愈（有字节无记录：重解析 + upsert，同名快路不重写；`.tmp` 残留与非字体跳过）。
     */
    suspend fun reconcileOrphanFiles(): List<FontFace> = withContext(Dispatchers.IO) {
        val files = runCatching { fs.list(fontsDir) }.getOrNull() ?: return@withContext db.allFonts()
        val known = db.allFonts().mapNotNull { it.path }.toSet()
        var healed = 0
        for (file in files) {
            if (file.name.endsWith(".tmp")) continue
            if (file.name.substringAfterLast('.', "").lowercase() !in FONT_EXTS) continue
            if (file.toString() in known) continue
            val bytes = runCatching { fs.read(file) { readByteArray() } }.getOrNull()
            if (bytes == null || bytes.isEmpty()) continue
            runCatching { importBytes(bytes, file.name) }
            healed++
        }
        if (healed > 0) Logger.i(logTag, "reconciled $healed orphan font file(s)")
        return@withContext db.allFonts()
    }

    /**
     * 写私有 `fontsDir` 并返回绝对路径；失败回 null。原子写入：先写 `.tmp` 同胞再 rename 覆盖——
     * UI 预览按 path 懒建 Typeface（mmap 式），并发截断/原地重写会让 FreeType 读到半截数据直接
     * SIGBUS（native 崩溃，runCatching 抓不住）；rename 已完成文件只会看到旧/新完整 inode。
     */
    private fun resolveFontTarget(dispName: String, bytes: ByteArray): String? = runCatching {
        fs.createDirectories(fontsDir)
        val final = fontsDir.resolve(dispName)
        val sameSize = runCatching { fs.metadata(final).size == bytes.size.toLong() }.getOrDefault(false)
        if (!sameSize) {
            val tmp = fontsDir.resolve("$dispName.tmp")
            fs.write(tmp) { write(bytes) }
            runCatching { fs.atomicMove(tmp, final) }.getOrElse {
                fs.write(final) { write(bytes) }
                runCatching { fs.delete(tmp) }
            }
        }
        final.toString()
    }.getOrNull()

    /** 按绝对路径读字节；缺失回 null。 */
    private fun deleteFileBytes(path: String?) {
        val s = path ?: return
        runCatching { fs.delete(s.toPath()) }
    }

    /**
     * 导入族名：name 表读出的族名原样用（自带 CJK 字形名保持繁体；纯拉丁名保持拉丁，
     * 不再查字典——字典已删，是什么显示什么）。空/空白回 "font" 占位。
     */
    private fun pickCnFamilyName(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotBlank() } ?: "font"

    companion object {
        val FONT_EXTS = setOf("ttf", "otf", "ttc", "otc")
    }
}
