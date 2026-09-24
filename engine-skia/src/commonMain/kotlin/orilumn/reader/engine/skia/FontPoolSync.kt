package orilumn.reader.engine.skia

import orilumn.reader.data.font.FontFace
import orilumn.reader.data.font.FontFaceMatcher
import orilumn.reader.data.font.SubfamilyMetric
import orilumn.reader.engine.css.BookFont
import orilumn.reader.engine.css.FontDemand
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F4b 用户字库装池共享核（平板/桌面同调，语义逐字沿旧 `ReaderActivity.syncSkiaPool`）：
 * 槽位族 + 按需族 → (族,字重,斜体) 笛卡尔 → [FontFaceMatcher] 选文件 → 读字节 →
 * 内嵌条目 + 书内条目（+ 可选系统衬线）。
 *
 * 两段式（翻页零 IO）：[select] 纯选择（签名用 id + 文件长度，长度由调用方探，
 * common 吃不到 `java.io.File`）；命中旧签名即池已是该集合，直接返回不读字节；
 * [assemble] 读字节装配（失败计数给调用方：有失败的不记名，下次重试）。
 * P2-b：无用户需求时仍保留书内字体（否则翻页回调把书内条目逐出池）。
 */
object FontPoolSync {

    /** [select] 结果：待装载面 + 预签名（含书内部分；调用方拼后与上次比对）。 */
    data class Selection(val selected: List<FontFace>, val sizes: Map<Long, Long>, val sig: String)

    /** (族,字重,斜体) 笛卡尔（槽位默认档 + 按需档共用构造）。 */
    fun triplesFor(families: Set<String>, weights: Set<Int>, italics: Set<Boolean>): Set<Triple<String, Int, Boolean>> =
        families.flatMap { f -> weights.flatMap { w -> italics.map { i -> Triple(f, w, i) } } }.toSet()

    /** [assemble] 结果：进池集合 + 加载失败数（>0 则调用方不记名）。 */
    data class Assembled(val embedded: List<SkiaFontPool.EmbeddedFont>, val failed: Int)

    fun select(
        faces: List<FontFace>,
        slotFamilies: Set<String>,
        demand: FontDemand,
        bookEntries: List<SkiaFontPool.EmbeddedFont>,
        fileSize: (String) -> Long?,
        /**
         * 按族字重锚点（族名 → CSS 字重）：该族只装此字重的面（直斜各一），
         * Skia 按内禀字重选面——池里多档并存时换 triple 也无用，必须整桶换掉。
         * 无匹配面回退自然选择。
         */
        anchors: Map<String, Int> = emptyMap(),
    ): Selection {
        val triples = triplesFor(slotFamilies, setOf(400, 700), setOf(false, true)) +
            triplesFor(demand.families, demand.weights + setOf(400, 700), if (demand.italic) setOf(false, true) else setOf(false))
        if (triples.isEmpty()) return Selection(emptyList(), emptyMap(), "#" + bookSig(bookEntries))
        val selected = triples.flatMap { (fam, w, i) ->
            val family = faces.filter { it.familyName == fam || it.displayName == fam }
            if (family.isEmpty()) return@flatMap emptyList<FontFace>()
            val bucket = anchors[fam]?.takeIf { it in 100..900 }?.let { a ->
                family.filter { SubfamilyMetric.weight(it.subfamily) == a }.ifEmpty { null }
            } ?: family
            // 有文件的优先：系统行无 path，与导入行同桶（displayName 中文 upward）时先选导入面；
            // 否则选到系统面后按 size 探不到文件，整桶落空（families=0，切换无效）。
            // 纯系统槽无文件可挑，回退原语义（选后面 size 照样过滤，渲染走系统回退）。
            val pathed = bucket.filter { it.path != null }
            listOfNotNull(FontFaceMatcher.choose(pathed.ifEmpty { bucket }, w, i))
        }.distinctBy { it.id }
        val sizes = selected.mapNotNull { f ->
            val p = f.path ?: return@mapNotNull null
            val len = fileSize(p) ?: return@mapNotNull null
            f.id to len
        }.toMap()
        val sig = (sizes.map { "${it.key}:${it.value}" }.sorted().joinToString("|")) + "#" + bookSig(bookEntries)
        // 缺文件/无 path（系统行）的面不进装载集：前者下次文件出现即签名变化自动重试
        // （沿旧语义），后者走系统字体集合、无需字节。
        return Selection(selected.filter { sizes.containsKey(it.id) }, sizes, sig)
    }

    fun assemble(
        selected: List<FontFace>,
        fontBytes: (Long) -> ByteArray?,
        bookEntries: List<SkiaFontPool.EmbeddedFont>,
        systemSerif: SkiaFontPool.EmbeddedFont? = null,
        logTag: String = "Orilumn.Font",
    ): Assembled {
        var failed = 0
        val embedded = selected.mapNotNull { f ->
            val bytes = fontBytes(f.id)
            if (bytes == null) {
                failed++
                Logger.w(logTag, "skia fonts skip unreadable id=${f.id} family=${f.familyName}")
                return@mapNotNull null
            }
            SkiaFontPool.EmbeddedFont.forFace(f.familyName, f.displayName, bytes)
        }
        return Assembled(embedded + bookEntries + listOfNotNull(systemSerif), failed)
    }

    /** 书内签名（用户/系统条目顺序无关，拼进总签名；调用方另拼用户部分）。 */
    fun bookSig(bookEntries: List<SkiaFontPool.EmbeddedFont>): String =
        bookEntries.map { "${it.familyName}:${it.bytes.size}" }.sorted().joinToString("|")

    /** [syncPool] 结果：池是否变化 + 调用方应记住的新签名（有失败时沿用旧签名，下次重试）。 */
    data class PoolSyncResult(val changed: Boolean, val sig: String?)

    /**
     * 跨宿主写互斥（公平锁）：桌面换字体重建宿主时，新旧控制器并发追装同一全局池；
     * 无锁即后完成者赢——旧宿主的大文件读完得晚就会盖掉新字，切换时好时坏。
     * 公平锁保证先发起者先落定（旧写完新覆盖），新宿主恒赢；等锁中被取消（旧宿主
     * dispose）即直接掉队不写。单宿主/平板路径无竞争，快照比对后才进锁，零开销。
     */
    private val poolWriteMutex = Mutex()

    /**
     * Q1-5 收敛：两壳装池编排单源（平板 `syncSkiaPool` 与桌面 `topUpSkiaFonts` 同义实现）。
     * 宿主只留 `lastSig`/`bookEntries` 状态与 IO 调度；孤儿自愈→选择→签名比对→装配→落池→日志全在此。
     */
    suspend fun syncPool(
        profile: TypographicProfile,
        demand: FontDemand,
        bookEntries: List<SkiaFontPool.EmbeddedFont>,
        lastSig: String?,
        loadFaces: suspend () -> List<FontFace>?,
        fileSize: (String) -> Long?,
        fontBytes: (Long) -> ByteArray?,
        systemSerif: SkiaFontPool.EmbeddedFont? = null,
        logTag: String = "Orilumn.Font",
    ): PoolSyncResult {
        val faces = loadFaces() ?: return PoolSyncResult(false, lastSig)
        val slotFams = setOf(profile.fontBody, profile.fontTitle, profile.fontCode)
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val sel = select(
            faces, slotFams, demand, bookEntries, fileSize,
            anchors = profile.fontWeightAnchors,
        )
        if (sel.sig == lastSig) return PoolSyncResult(false, lastSig)
        return poolWriteMutex.withLock {
            val asm = assemble(sel.selected, fontBytes, bookEntries, systemSerif)
            val changed = SkiaFontPool.setEmbedded(asm.embedded)
            val sig = if (asm.failed == 0) sel.sig else lastSig
            if (changed || asm.failed > 0) {
                val mb = asm.embedded.sumOf { it.bytes.size } / 1048576
                Logger.w(logTag, "skia fonts refreshed families=${asm.embedded.size} mb=$mb needed=$slotFams failed=${asm.failed}")
            }
            PoolSyncResult(changed, sig)
        }
    }

    /**
     * 书内字体合并（两壳 `syncBookFonts` 同义）：集合无变化回 null（调用方直接 false），
     * 有变化回新集合（调用方替换后走统一合并装载）。
     */
    fun mergeBookFonts(
        current: List<SkiaFontPool.EmbeddedFont>,
        fonts: List<BookFont>,
    ): List<SkiaFontPool.EmbeddedFont>? {
        val entries = fonts.map { SkiaFontPool.EmbeddedFont(it.family, it.bytes) }
        val sigOf: (List<SkiaFontPool.EmbeddedFont>) -> List<Pair<String, Int>> =
            { list -> list.map { it.familyName to it.bytes.size }.sortedBy { it.first } }
        return if (sigOf(entries) == sigOf(current)) null else entries
    }
}
