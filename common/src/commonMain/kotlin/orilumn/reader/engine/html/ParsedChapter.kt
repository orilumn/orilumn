package orilumn.reader.engine.html

/**
 * Parsed chapter: the semantic [MarkupElement] body tree plus the CSS sources collected alongside it
 * (pure logic, zero Android dependency, JVM unit-testable).
 *
 * For a reflowable reader the chapter's author CSS is split into embedded `<style>` blocks and
 * linked stylesheets (`<link rel="stylesheet" href>`). Both are surfaced raw here; linked `href`s are
 * resolved relative to the chapter by the caller (e.g. [orilumn.reader.engine.BookDocumentController])
 * through the epub resource reader. The body [tree] is identical to what [HtmlTreeConverter.convert]
 * returns, so rendering code that only needs the tree can keep using the legacy method untouched.
 */
class ParsedChapter(
    val tree: MarkupElement,
    val styles: List<String>,
    val linkHrefs: List<String>,
)