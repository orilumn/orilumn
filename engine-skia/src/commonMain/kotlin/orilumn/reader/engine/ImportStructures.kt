package orilumn.reader.engine

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.CssViewport
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.resolveCssImports
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
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
    fun collectRawCssTexts(
        reader: EpubResourceReader,
        spineHref: String,
        parsed: ParsedChapter,
    ): Pair<List<String>, List<String>> {
        val texts = ArrayList<String>()
        val hrefs = ArrayList<String>()
        for (style in parsed.styles) {
            texts.add(style)
            hrefs.add(spineHref)
        }
        for (href in parsed.linkHrefs) {
            val resolved = reader.resolveRelative(spineHref, href)
            reader.readText(resolved)?.takeIf { it.isNotBlank() }?.let {
                texts.add(it)
                hrefs.add(resolved)
            }
        }
        return texts to hrefs
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
        val (texts, hrefs) = collectRawCssTexts(reader, spineHref, parsed)
        if (texts.isEmpty()) return CssBundle(emptyList())
        val flat = resolveCssImports(
            texts,
            hrefs,
            spineHref,
            reader::resolveRelative,
            { h -> runCatching { reader.readText(h) }.getOrNull() },
            viewport,
        )
        return CssBundle(flat.map { it.second }, flat.map { it.first })
    }

    data class BuildStats(val chapters: Int, val persisted: Int, val skippedMedia: Int, val failed: Int)

    /**
     * Persists one chapter's import/open artifact: post-process tree + deduped sheet refs +
     * structure. Shared by the import job and the open-time backfill (same bytes either way).
     * Returns false when there is nothing persistable (media-affected CSS or any inconsistency).
     */
    fun persistChapter(
        store: ChapterStructureStore,
        bookNamespace: String,
        index: Int,
        tree: MarkupElement,
        bundle: CssBundle,
        structure: ChapterStructureCache,
    ): Boolean {
        if (ChapterStructurePersist.hasMediaRules(bundle.cssTexts)) return false
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        val payload = ChapterStructurePersist.extract(index, cssHash, tree, structure) ?: return false
        val sheets = HashMap<Long, String>()
        val sheetHashes = bundle.cssTexts.map { text ->
            var h = cssHashOfText(text)
            // Hash collision across different texts would alias sheets: disambiguate deterministically.
            while (sheets[h] != null && sheets[h] != text) h++
            sheets[h] = text
            h
        }
        store.mergeSheets(bookNamespace, sheets)
        store.writeChapter(
            bookNamespace,
            PersistedChapterFile(index, cssHash, sheetHashes, bundle.baseHrefs, tree, payload),
        )
        return true
    }

    private fun cssHashOfText(text: String): Long {
        var h = 0L
        for (i in text.indices) h = h * 31 + text[i].code
        return h
    }

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
                // Media gating on RAW sources: conditional @imports vanish from the flattened list.
                val (rawTexts, _) = collectRawCssTexts(reader, href, parsed)
                if (ChapterStructurePersist.hasMediaRules(rawTexts)) {
                    skippedMedia++
                    return@forEachIndexed
                }
                val bundle = collectChapterCssTexts(reader, href, parsed, null)
                val sheets = bundle.cssTexts.map { LightCssParser().parse(it, null) }
                val tree = ChapterPreprocessor.preprocess(parsed.tree, sheets)
                val cache = ChapterStructureCache()
                layouter.prepareLight(tree, bundle, IMPORT_PROFILE, 1600, cache, 2400)
                if (persistChapter(store, bookNamespace, index, tree, bundle, cache)) persisted++
                else failed++
            } catch (_: Exception) {
                failed++
            }
        }
        return BuildStats(spineHrefs.size, persisted, skippedMedia, failed)
    }
}
