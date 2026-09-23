package orilumn.reader.engine.css

import okio.Buffer

/**
 * P2-b: 书内字体管线纯逻辑（JVM 可测，无 IO）。
 *
 * - [collectBookFonts]：章节作者样式表的 `@font-face` → 可装载引用（族名＋归一化 href＋格式）。
 * - [resolveCssImports]：`@import` 递归内联（防环＋限深＋媒体条件），返回展平文本。
 * - [deobfuscateIdpf]：IDPF 字体混淆去混淆（密钥＝出版标识去空白的 SHA-1）。
 * - [sniffFontFormat]：字体魔数嗅探。
 *
 * 字节读取/池注册由宿主完成（Android `ReaderActivity` / 桌面 host）：本层只给引用与字节变换。
 */

/** 书内字体装载引用：族名＋归档根路径＋格式提示＋字重/斜体描述。 */
data class BookFontRef(
    val family: String,
    /** 归档根相对路径（读取壳已归一化；相对源 CSS 解析）。 */
    val href: String,
    /** `format(...)` 小写原文（空＝未声明，按扩展名/魔数判断）。 */
    val format: String = "",
    val weight: Int? = null,
    val italic: Boolean? = null,
)

/** 已解码书内字体：族名＋去混淆后字节（宿主直接入池）。 */
data class BookFont(
    val family: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BookFont && family == other.family && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * family.hashCode() + bytes.contentHashCode()
}

/**
 * 从章节作者样式表收集书内字体引用。
 *
 * @param sheets 与 [baseHrefs] 一一对应的已解析表（嵌入 `<style>` 在前，链接表在后，
 *   与 `CssBundle.cssTexts` 同序）。
 * @param baseHrefs 每份 CSS 源的基准 href（嵌入源＝章节 href，链接源＝样式表 href）。
 * @param chapterHref 章节 href（嵌入源基准缺省回退）。
 * @param resolve 相对解析（读取壳的 `resolveRelative` 语义：`..`/`.` 折叠）。
 */
fun collectBookFonts(
    sheets: List<StyleSheet>,
    baseHrefs: List<String>,
    chapterHref: String,
    resolve: (base: String, rel: String) -> String,
): List<BookFontRef> {
    val out = ArrayList<BookFontRef>()
    val seen = LinkedHashSet<String>()
    for ((i, sheet) in sheets.withIndex()) {
        val base = baseHrefs.getOrElse(i) { chapterHref }
        for (face in sheet.cssFontFaces) {
            if (face.family.isBlank()) continue
            for (src in face.sources) {
                if (!isLoadableFontUrl(src.url, src.format)) continue
                val href = resolve(base, src.url)
                val key = "${face.family}\u0000$href"
                if (seen.add(key)) {
                    out.add(BookFontRef(face.family, href, src.format, face.weight, face.italic))
                }
            }
        }
    }
    return out
}

/** 外部/数据 URL 不装载；格式未知且扩展名未知即跳过（魔数是第二道门，宿主侧判断）。 */
fun isLoadableFontUrl(url: String, format: String): Boolean {
    val u = url.trim()
    if (u.isEmpty()) return false
    val low = u.lowercase()
    if (low.startsWith("http://") || low.startsWith("https://") ||
        low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("ftp://")
    ) return false
    if (format.isNotBlank() && !isKnownFontFormat(format)) return false
    if (format.isBlank()) {
        val ext = low.substringAfterLast('.', "").substringBefore('?').substringBefore('#')
        if (ext.isNotEmpty() && ext !in KNOWN_FONT_EXTS) return false
    }
    return true
}

private val KNOWN_FONT_EXTS = setOf("ttf", "otf", "woff", "woff2", "ttc", "otc")

private fun isKnownFontFormat(format: String): Boolean = when (format.lowercase()) {
    "truetype", "opentype", "woff", "woff2", "truetype-collection", "opentype-collection",
    "ttf", "otf", "ttc", "otc" -> true
    else -> false
}

/**
 * `@import` 递归内联（pure＋loader 接缝）。
 *
 * @param texts 根 CSS 文本（嵌入在前、链接在后，与级联同序）。
 * @param baseHrefs 每份根文本的基准 href（与 [texts] 同序，缺省章节 href）。
 * @param chapterHref 章节 href（嵌入源基准回退）。
 * @param resolve 相对解析（读取壳语义；导入 url 相对导入者基准）。
 * @param load 按归一化 href 读文本（缺失即 null＝跳过，永不抛由调用方保证）。
 * @param viewport 媒体求值视口；null 时只内联无条件导入（无法求值即跳过）。
 * @param maxDepth 内联深度上限（防自举/深链）。
 * @return 展平后的 `(baseHref, text)` 序列：导入内容恒在其导入者之前（级联低优先级）。
 */
fun resolveCssImports(
    texts: List<String>,
    baseHrefs: List<String>,
    chapterHref: String,
    resolve: (base: String, rel: String) -> String,
    load: (href: String) -> String?,
    viewport: CssViewport?,
    maxDepth: Int = 5,
): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val visited = LinkedHashSet<String>()
    fun resolveOne(base: String, text: String, depth: Int) {
        val sheet = runCatching { LightCssParser().parse(text) }.getOrNull()
        if (sheet == null) {
            out.add(base to text)
            return
        }
        if (depth < maxDepth) {
            for (imp in sheet.imports) {
                if (imp.url.isBlank() || !isStylesheetUrl(imp.url)) continue
                if (imp.media.isNotBlank()) {
                    if (viewport == null) continue
                    if (!runCatching { LightCssParser().matchesMedia(imp.media, viewport) }.getOrDefault(false)) continue
                }
                val href = resolve(base, imp.url.trim())
                if (!visited.add(href)) continue
                val loaded = runCatching { load(href) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                resolveOne(href, loaded, depth + 1)
            }
        }
        out.add(base to text)
    }
    for ((i, text) in texts.withIndex()) {
        val base = baseHrefs.getOrElse(i) { chapterHref }
        // 文件源（非嵌入）若已作为导入项输出即跳过（自环/互引去重）；嵌入块共享章节
        // href 作基准，不在此守卫（多嵌入块恒保留）。
        if (base != chapterHref && !visited.add(base)) continue
        resolveOne(base, text, 0)
    }
    return out
}

/** `@import` 只装载相对/根样式表（外部/data 跳过）。 */
fun isStylesheetUrl(url: String): Boolean {
    val low = url.trim().lowercase()
    if (low.isEmpty()) return false
    if (low.startsWith("http://") || low.startsWith("https://") ||
        low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("ftp://")
    ) return false
    return true
}

/**
 * IDPF 字体混淆去混淆（EPUB3 §字体混淆）：密钥＝出版标识（去全部空白）UTF-8 的 SHA-1，
 * 前 1040 字节与密钥循环异或。uid 为空即原文返回。
 */
fun deobfuscateIdpf(bytes: ByteArray, uid: String): ByteArray {
    if (uid.isEmpty() || bytes.isEmpty()) return bytes
    val key = Buffer().write(uid.encodeToByteArray()).sha1().toByteArray()
    if (key.isEmpty()) return bytes
    val out = bytes.copyOf()
    val n = minOf(1040, out.size)
    for (i in 0 until n) {
        out[i] = (out[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
    }
    return out
}

/** 字体魔数嗅探：`ttf`/`otf`/`woff`/`woff2`/`ttc`，未知即空串（宿主跳过）。 */
fun sniffFontFormat(bytes: ByteArray): String {
    if (bytes.size < 4) return ""
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    if (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return "ttf"
    if (b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F) return "otf" // OTTO
    if (b0 == 0x77 && b1 == 0x4F && b2 == 0x46 && b3 == 0x46) return "woff" // wOFF
    if (b0 == 0x77 && b1 == 0x4F && b2 == 0x46 && b3 == 0x32) return "woff2" // wOF2
    if (b0 == 0x74 && b1 == 0x74 && b2 == 0x63 && b3 == 0x66) return "ttc" // ttcf
    if (b0 == 0x4F && b1 == 0x54 && b2 == 0x43 && b3 == 0x20) return "ttc" // OTC + space
    return ""
}
