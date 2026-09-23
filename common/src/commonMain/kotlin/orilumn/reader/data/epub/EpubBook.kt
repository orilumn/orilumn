package orilumn.reader.data.epub

/**
 * The parsed e-book model, the core data structure of the reader.
 *
 * @property spine the list of reading-order chapters (book body).
 * @property toc the table-of-contents tree, whose nodes link to spine chapters via [TocItem.index].
 */
data class EpubBook(
    val title: String,
    val author: String?,
    val spine: List<SpineItem>,
    val toc: List<TocItem>,
    /** Full path of the cover image resource (relative to the OPF directory); null if there is no cover. */
    val cover: String? = null,
    /**
     * P2: OPF `unique-identifier` 对应的出版标识（去空白；IDPF 字体去混淆密钥源）。
     * 缺失即空串（无混淆字体时无影响）。
     */
    val uid: String = "",
    /**
     * P2: IDPF 混淆字体路径集（大小写保留，使用处归一化比对；OCF 包根优先、OPF 相对回退，
     * 见 `EpubParser.parseObfuscation`；`META-INF/encryption.xml`
     * 中 `Algorithm=http://www.idpf.org/2008/embedding` 的条目）。
     */
    val obfuscatedFonts: Set<String> = emptySet(),
) {
    /** An empty book body is treated as invalid. */
    val isEmpty: Boolean get() = spine.isEmpty()
}

/**
 * A chapter in the spine (i.e. one body xhtml).
 *
 * @property href the as-is path relative to the OPF directory (case preserved), used to read the body.
 * @property fragment anchor within the chapter (e.g. `#sec1`), null when absent.
 */
data class SpineItem(
    val index: Int,
    val href: String,
    val fragment: String?,
    val mediaType: String?,
)

/**
 * A table-of-contents node (nestable).
 *
 * @property index the spine chapter index matched; null when there is no matching chapter (e.g. only links to a resource).
 * @property fragment table-of-contents target anchor.
 */
data class TocItem(
    val label: String,
    val index: Int?,
    val fragment: String?,
    val children: List<TocItem> = emptyList(),
)

/** Thrown when the EPUB format is invalid. */
class EpubFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)