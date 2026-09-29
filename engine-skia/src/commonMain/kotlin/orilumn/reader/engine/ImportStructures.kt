package orilumn.reader.engine

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.CssViewport
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.resolveCssImports
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.ParsedChapter
import orilumn.reader.engine.text.TypographicProfile

/**
 * Import-time chapter-structure build (all books): computes the viewport-independent block
 * structure once per chapter and persists it via [ChapterStructureStore], so open/flip/relayout
 * never re-runs the per-element display cascade for media-free chapters.
 *
 * Only media-free chapters (no `@media`, no media-conditioned `@import`) are persisted —
 * [ChapterStructurePersist.hasMediaRules] gates both writing here and relinking at open. Media
 * chapters always compute at open, exactly as before.
 *
 * Blocking pure logic: the caller runs it on IO (import post-step, open backfill happens
 * lazily through `prepareLight` instead). Fail-closed per chapter: one bad chapter never aborts
 * the book.
 */
object ImportStructures {

    /** Canonical profile for the import cascade: structure output is profile-independent
     *  (typography/reader sheets never set display/white-space/background/break-inside/
     *  visibility), so any fixed profile yields the open-time-identical payload. Locked by
     *  `structure payload is profile-independent` probe tests. */
    private val IMPORT_PROFILE: TypographicProfile by lazy {
        TypographicProfile.build(ReaderSettings.DEFAULT, 1f)
    }

    /**
     * Assembles a chapter's CSS sources: embedded `<style>` blocks first, then linked stylesheets
     * resolved relative to the chapter and read through the epub reader (missing/malformed ones are
     * skipped, so a bad author stylesheet never breaks layout).
     * `@import` media conditions evaluate against [viewport]; null inlines only unconditional
     * imports (import-time: media cannot be evaluated yet).
     */
    fun collectChapterCssTexts(
        reader: EpubResourceReader,
        spineHref: String,
        parsed: ParsedChapter,
        viewport: CssViewport?,
    ): CssBundle {
        val roots = ArrayList<Pair<String, String>>()
        for (style in parsed.styles) roots.add(spineHref to style)
        for (href in parsed.linkHrefs) {
            val resolved = reader.resolveRelative(spineHref, href)
            reader.readText(resolved)?.takeIf { it.isNotBlank() }?.let { roots.add(resolved to it) }
        }
        if (roots.isEmpty()) return CssBundle(emptyList())
        val flat = resolveCssImports(
            roots.map { it.second },
            roots.map { it.first },
            spineHref,
            reader::resolveRelative,
            { h -> runCatching { reader.readText(h) }.getOrNull() },
            viewport,
        )
        return CssBundle(flat.map { it.second }, flat.map { it.first })
    }

    data class BuildStats(val chapters: Int, val persisted: Int, val skippedMedia: Int, val failed: Int)

    /** Builds + persists every media-free chapter's structure. Returns per-book stats for logging. */
    fun buildAllChapterStructures(
        reader: EpubResourceReader,
        spineHrefs: List<String>,
        store: ChapterStructureStore,
        bookNamespace: String,
        onChapter: ((index: Int) -> Unit)? = null,
    ): BuildStats {
        val converter = HtmlTreeConverter()
        val layouter = BoxChapterLayouter()
        var persisted = 0
        var skippedMedia = 0
        var failed = 0
        spineHrefs.forEachIndexed { index, href ->
            try {
                onChapter?.invoke(index)
                val text = reader.readText(href) ?: run { failed++; return@forEachIndexed }
                val parsed = converter.convertWithStyles(text) ?: run { failed++; return@forEachIndexed }
                val bundle = collectChapterCssTexts(reader, href, parsed, null)
                if (ChapterStructurePersist.hasMediaRules(bundle.cssTexts)) {
                    skippedMedia++
                    return@forEachIndexed
                }
                val sheets = bundle.cssTexts.map { LightCssParser().parse(it, null) }
                val tree = ChapterPreprocessor.preprocess(parsed.tree, sheets)
                val cache = ChapterStructureCache()
                layouter.prepareLight(tree, bundle, IMPORT_PROFILE, 1600, cache, 2400)
                val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
                val payload = ChapterStructurePersist.extract(index, cssHash, tree, cache)
                    ?: run { failed++; return@forEachIndexed }
                store.write(bookNamespace, payload)
                persisted++
            } catch (_: Exception) {
                failed++
            }
        }
        return BuildStats(spineHrefs.size, persisted, skippedMedia, failed)
    }
}
