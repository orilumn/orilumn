package orilumn.reader.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import orilumn.reader.engine.css.FontDemand
import orilumn.reader.io.Logger

/**
 * 一章的字体需求（[FontDemand] 的落盘形状）。
 *
 * [cssHash] 是判据：同一章的作者 CSS 变了（换书版本/换书），这份需求就不作数——
 * 与章节结构缓存（[ChapterStructureCodec.cssHashOf]）同一个哈希口径，不另造一套。
 */
@Serializable
data class ChapterFontDemandEntry(
    val cssHash: Long,
    val families: List<String> = emptyList(),
    val weights: List<Int> = emptyList(),
    val italic: Boolean = false,
) {
    fun toDemand(): FontDemand = FontDemand(families.toSet(), weights.toSet(), italic)

    companion object {
        fun of(cssHash: Long, demand: FontDemand): ChapterFontDemandEntry =
            ChapterFontDemandEntry(
                cssHash = cssHash,
                families = demand.families.sorted(),
                weights = demand.weights.sorted(),
                italic = demand.italic,
            )
    }
}

/**
 * 一本书的**字体需求清单**：章号 → 该章作者 CSS 声明的族/字重/斜体。
 *
 * **为什么存**：这份清单是"整形之前先把命中的导入面装进池"的依据。它只由作者 CSS 决定
 * （与字号、版心、主题无关，[FontDemand] 的解析不看视口），但每次开书都要把每章 CSS
 * 重新轻量解析一遍才拿得到。清单落盘后，开章时直接查表，省掉这一笔重复解析；
 * 同时它让"这本书到底要用哪些字体"在不解析 CSS 的情况下随时可得（例如字体文件被删/换时
 * 判断本书该不该作废版面）。
 *
 * **存放在哪**：`<cache>/structure/book_<id>/fontdemand.json`——与章节结构缓存同一个按书目录，
 * 删书时整目录一并清掉，不产生第二套孤儿目录。
 *
 * **失效判据**：格式版本 + 构建号（整份）+ 逐章 [ChapterFontDemandEntry.cssHash]。
 * 任一不符就当"这章没存过"，现算并覆盖——清单是纯缓存，永远可以重算。
 */
class ChapterFontDemandStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    root: Path,
) {
    private val rootDir: Path = root

    fun file(bookId: Long): Path =
        rootDir.resolve("structure/book_$bookId").resolve(FILENAME)

    /** 读回清单；缺文件/坏文件/版本或构建号不符一律回空表（调用方当"全 miss"）。 */
    fun read(bookId: Long, appVersion: Int): Map<Int, ChapterFontDemandEntry> {
        if (bookId < 0) return emptyMap()
        val f = file(bookId)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return emptyMap()
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return emptyMap()
        val doc = runCatching {
            Json.decodeFromString(PersistedFontDemands.serializer(), bytes.decodeToString())
        }.onFailure {
            Logger.d(TAG, "font demand decode FAIL book=$bookId ${it::class.simpleName}")
        }.getOrNull() ?: return emptyMap()
        if (doc.version != VERSION || doc.appVersion != appVersion) return emptyMap()
        val out = HashMap<Int, ChapterFontDemandEntry>(doc.chapters.size)
        for ((k, v) in doc.chapters) {
            val idx = k.toIntOrNull() ?: continue
            out[idx] = v
        }
        return out
    }

    /** 覆盖写整份清单（临时文件 + 原子改名；写失败只当"没存"，调用方照常用内存结果）。 */
    fun write(bookId: Long, appVersion: Int, chapters: Map<Int, ChapterFontDemandEntry>) {
        if (bookId < 0 || chapters.isEmpty()) return
        val f = file(bookId)
        val dir = f.parent ?: return
        val doc = PersistedFontDemands(
            version = VERSION,
            appVersion = appVersion,
            chapters = chapters.mapKeys { it.key.toString() },
        )
        val bytes = runCatching {
            Json.encodeToString(PersistedFontDemands.serializer(), doc).encodeToByteArray()
        }.getOrNull() ?: return
        runCatching {
            fs.createDirectories(dir)
            val tmp = dir.resolve(TMP_FILENAME)
            fs.write(tmp) { write(bytes) }
            // 与开书骨架/页面快照同款：atomicMove 不可用时退一步"直接覆盖"，半份文件读回即空表。
            runCatching { fs.atomicMove(tmp, f) }
                .onFailure { fs.write(f) { write(bytes) }; runCatching { fs.delete(tmp) } }
        }.onFailure {
            Logger.w(TAG, "font demand write FAIL book=$bookId ${it::class.simpleName}")
        }
    }

    companion object {
        const val FILENAME = "fontdemand.json"
        private const val TMP_FILENAME = "fontdemand.tmp"
        private const val TAG = "Orilumn.DISK"

        /**
         * 清单格式版本：`FontDemand` 的**解析口径**变了（[orilumn.reader.engine.css.collectFontDemand]
         * 收集的范围/规则）就 +1，否则老清单会带着漏掉的族一直命中。
         * 新增字段有默认值可不加。
         */
        const val VERSION = 1
    }
}

@Serializable
private data class PersistedFontDemands(
    val version: Int,
    val appVersion: Int,
    val chapters: Map<String, ChapterFontDemandEntry> = emptyMap(),
)
