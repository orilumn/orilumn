package orilumn.reader.data.epub

import orilumn.reader.xml.XmlDocument
import orilumn.reader.xml.XmlElement

/**
 * Self-built EPUB parser (pure logic, JVM unit-testable, no Readium/Android dependency).
 *
 * Flow:
 * 1. read `META-INF/container.xml` → locate the full OPF path;
 * 2. read OPF → metadata / manifest / spine;
 * 3. read the table of contents (EPUB3 nav preferred, EPUB2 NCX fallback) → map back to spine indices.
 *
 * Unified principle: all `href` values keep the as-is path from the OPF (case/prefix unchanged),
 * because [EpubResourceReader] reads bodies with those as-is paths; only "relative to the OPF dir"
 * joining is done. Matching comparisons for TOC/chapters use normalized (lowercase) paths, to
 * tolerate case drift in dirty books.
 */
class EpubParser {

    /** XML parsing uses [orilumn.reader.xml.XmlParser] (mini DOM, commonMain, XXE-safe). */
    private val xmlParser = orilumn.reader.xml.XmlParser()

    fun parse(reader: EpubResourceReader): EpubBook {
        val opfPath = resolveOpfPath(reader)
            ?: throw EpubFormatException("未找到 META-INF/container.xml 或其中不含 rootfile")
        val opfXml = reader.readText(opfPath)
            ?: throw EpubFormatException("读取 OPF 失败：$opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val opf = parseXml(opfXml).documentElement
        val (title, author) = parseMetadata(opf)
        val manifest = parseManifest(opf)
        val spineIds = parseSpine(opf)

        if (spineIds.isEmpty()) throw EpubFormatException("OPF 中 spine 为空")

        val cover = resolveCover(opf, opfDir, manifest)

        val spine = spineIds.mapIndexed { index, id ->
            val item = manifest[id] ?: throw EpubFormatException("spine 引用不存在的 manifest id：$id")
            val (href, fragment) = splitFragment(item.href)
            SpineItem(index, joinPath(opfDir, href), fragment, item.mediaType)
        }
        val toc = resolveToc(reader, opfDir, spine, manifest.values)
        val uid = resolveUid(opf)
        val obfuscated = parseObfuscation(reader, opfDir)

        return EpubBook(title ?: "未知书名", author, spine, toc, cover, uid, obfuscated)
    }

    // ---- container.xml / OPF location ----

    /** Resolve the full path of the cover image resource: prioritize manifest `properties=cover-image`,
     *  else the id referenced by meta[name=cover]. Returns the full path relative to the OPF directory;
     *  null when not found. */
    private fun resolveCover(opf: XmlElement, opfDir: String, manifest: Map<String, ManifestItem>): String? {
        // 1) manifest item with properties containing cover-image
        val coverItem = manifest.values.firstOrNull { it.properties.contains("cover-image") }
        if (coverItem != null) return joinPath(opfDir, coverItem.href)
        // 2) metadata <meta name="cover" content="cover-id">
        val metaId = opf.elementChildren("metadata").firstOrNull()
            ?.elementChildren("meta")
            ?.firstOrNull { it.getAttribute("name") == "cover" }
            ?.getAttribute("content")
            ?.takeIf { it.isNotBlank() }
        val byId = metaId?.let { manifest[it] }
        if (byId != null && (byId.mediaType ?: "").startsWith("image")) return joinPath(opfDir, byId.href)
        return null
    }

    private fun resolveOpfPath(reader: EpubResourceReader): String? {
        val xml = reader.readText("META-INF/container.xml") ?: return null
        val doc = parseXml(xml)
        for (el in doc.getElementsByTagNameNS("*", "rootfile")) {
            if (el.getAttribute("media-type") == "application/oebps-package+xml") {
                return el.getAttribute("full-path").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ---- XML parsing ----

    private fun parseXml(xml: String): XmlDocument {
        // Handle BOM and stray whitespace: ensure the `<?xml` declaration sits at the very start
        val cleaned = xml.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        return xmlParser.parse(cleaned)
    }

    // ---- OPF: metadata / manifest / spine ----

    private fun parseMetadata(opf: XmlElement): Pair<String?, String?> {
        val metadata = opf.elementChildren("metadata").firstOrNull() ?: return null to null
        val title = metadata.firstDescendant("title")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val author = metadata.firstDescendant("creator")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return title to author
    }

    private fun parseManifest(opf: XmlElement): Map<String, ManifestItem> {
        val manifest = opf.elementChildren("manifest").firstOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ManifestItem>()
        for (el in manifest.elementChildren("item")) {
            val id = el.getAttribute("id")
            if (id.isNotEmpty()) {
                out[id] = ManifestItem(
                    href = el.getAttribute("href"),
                    mediaType = el.getAttribute("media-type").takeIf { it.isNotEmpty() },
                    properties = el.getAttribute("properties").split(' ').filter { it.isNotEmpty() },
                )
            }
        }
        return out
    }

    private fun parseSpine(opf: XmlElement): List<String> {
        val spine = opf.elementChildren("spine").firstOrNull() ?: return emptyList()
        return spine.elementChildren("itemref").mapNotNull { itemref ->
            itemref.getAttribute("idref").takeIf { it.isNotEmpty() }
        }
    }

    // ---- P2: publication UID + IDPF font obfuscation ----

    /**
     * OPF `unique-identifier` 指向的 `dc:identifier` 文本（去全部空白；IDPF 去混淆密钥源）。
     * 缺失即空串。
     */
    private fun resolveUid(opf: XmlElement): String {
        val uidId = opf.getAttribute("unique-identifier").takeIf { it.isNotBlank() } ?: return ""
        val metadata = opf.elementChildren("metadata").firstOrNull() ?: return ""
        // dc:identifier 按本地名匹配，id 属性对上 unique-identifier。
        val ident = metadata.elementChildren("identifier").firstOrNull { el ->
            el.getAttribute("id") == uidId
        } ?: return ""
        return ident.textContent.filterNot { it.isWhitespace() }
    }

    /**
     * `META-INF/encryption.xml` 中 IDPF 嵌入算法的混淆字体路径集。
     * OCF 规定 CipherReference URI 相对**包根**（如 `EPUB/font.otf`），直接可用；
     * 个别脏包写 OPF 相对路径时才拼 `opfDir`（以包内真实存在为准二选一，永不抛）。
     * 无文件/无条目即空集。
     */
    private fun parseObfuscation(reader: EpubResourceReader, opfDir: String): Set<String> {
        val xml = reader.readText("META-INF/encryption.xml") ?: return emptySet()
        val root = runCatching { parseXml(xml).documentElement }.getOrNull() ?: return emptySet()
        val entries = runCatching { reader.entries().toSet() }.getOrNull() ?: emptySet()
        val out = LinkedHashSet<String>()
        fun walk(el: XmlElement) {
            // 本地名 EncryptedData（前缀容忍经 localName）。
            if (el.localName == "EncryptedData") {
                val method = el.firstDescendant("EncryptionMethod")?.getAttribute("Algorithm") ?: ""
                if (method == "http://www.idpf.org/2008/embedding") {
                    val uri = el.firstDescendant("CipherReference")?.getAttribute("URI")
                        ?: el.firstDescendant("CipherReference")?.getAttribute("uri")
                    if (!uri.isNullOrBlank()) {
                        // 包根优先（合规包），OPF 相对回退（脏包）；归一化比对，
                        // 入集保留原大小写（调用方 `bookFontsFor` 比对时再归一化）。
                        val asIs = uri.trimStart('/')
                        val joined = joinPath(opfDir, uri)
                        val cands = listOf(asIs, joined).distinct()
                        out.add(cands.firstOrNull { normalizePath(it) in entries } ?: joined)
                    }
                }
            }
            for (c in el.children) if (c is XmlElement) walk(c)
        }
        walk(root)
        return out
    }

    // ---- TOC: EPUB3 nav preferred, EPUB2 NCX fallback ----

    private fun resolveToc(
        reader: EpubResourceReader,
        opfDir: String,
        spine: List<SpineItem>,
        manifestItems: Collection<ManifestItem>,
    ): List<TocItem> {
        val indexByHref = indexSpineByNormalizedHref(spine)

        // 1) EPUB3 nav: manifest item with properties containing "nav"
        val navItem = manifestItems.firstOrNull { it.properties.contains("nav") }
        if (navItem != null) {
            val href = joinPath(opfDir, navItem.href)
            val xhtml = reader.readText(href)?.let { runCatching { parseXml(it) }.getOrNull() }
            if (xhtml != null) {
                // nav is usually within body; recursively locate the first <nav>; fall back to the document root if not found
                val nav = xhtml.documentElement.firstDescendant("nav") ?: xhtml.documentElement
                val toc = parseNavOl(nav, opfDir, indexByHref)
                if (toc.isNotEmpty()) return toc
            }
        }

        // 2) EPUB2 NCX: media-type is application/x-dtbncx+xml
        val ncxItem = manifestItems.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val href = joinPath(opfDir, ncxItem.href)
            val ncxXml = reader.readText(href)?.let { runCatching { parseXml(it) }.getOrNull() }
            if (ncxXml != null) {
                val toc = parseNcx(ncxXml.documentElement, opfDir, indexByHref)
                if (toc.isNotEmpty()) return toc
            }
        }

        return emptyList()
    }

    /** Parse the `<ol>` tree inside EPUB3 `<nav epub:type="toc">`. */
    private fun parseNavOl(
        scope: XmlElement,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        val ol = scope.elementChildren("ol").firstOrNull() ?: return emptyList()
        return ol.elementChildren("li").mapNotNull { li ->
            val a = li.elementChildren("a").firstOrNull() ?: return@mapNotNull null
            val label = a.textContent.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val (path, fragment) = splitFragment(a.getAttribute("href"))
            val tocItem = TocItem(
                label = label,
                index = indexByHref[normalizePath(joinPath(opfDir, path))],
                fragment = fragment, // anchor is usually #sec, not a #p= label
                children = parseNavOl(li, opfDir, indexByHref),
            )
            tocItem
        }
    }

    /** Parse the EPUB2 NCX `<navMap>` tree. */
    private fun parseNcx(
        root: XmlElement,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        val navMap = root.elementChildren("navMap").firstOrNull() ?: return emptyList()
        return parseNavPoints(navMap, opfDir, indexByHref)
    }

    private fun parseNavPoints(
        scope: XmlElement,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        return scope.elementChildren("navPoint").mapNotNull { point ->
            val label = point.firstDescendant("text")?.textContent?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val src = point.firstDescendant("content")?.getAttribute("src") ?: return@mapNotNull null
            val (path, fragment) = splitFragment(src)
            TocItem(
                label = label,
                index = indexByHref[normalizePath(joinPath(opfDir, path))],
                fragment = fragment,
                children = parseNavPoints(point, opfDir, indexByHref),
            )
        }
    }

    // ---- Helpers ----

    /** Build a normalized (lowercase, fragment-stripped) spine href → chapter index map. */
    private fun indexSpineByNormalizedHref(spine: List<SpineItem>): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (item in spine) {
            // item.href already contains the OPF dir prefix; just normalize it
            val (path, _) = splitFragment(item.href)
            map[normalizePath(path)] = item.index
        }
        return map
    }

    /** Split `a#b` → (a, b); fragment is null when there is no `#`. */
    private fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx >= 0) href.substring(0, idx) to href.substring(idx + 1).ifEmpty { null }
        else href to null
    }

    /** Relative-path join: `dir + "/" + href`; returns href directly when dir is empty. */
    private fun joinPath(dir: String, href: String): String =
        if (dir.isEmpty() || href.startsWith("/")) href else "$dir/$href"

    private fun normalizePath(p: String): String = p.replace('\\', '/').trim('/').lowercase()

    private data class ManifestItem(
        val href: String,
        val mediaType: String?,
        val properties: List<String> = emptyList(),
    )
}