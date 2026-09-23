package orilumn.reader.engine.css

/**
 * A chapter's collected author stylesheets as raw CSS text, in cascade order.
 *
 * Assembled by the data layer (see `BookDocumentController`) from the chapter's embedded `<style>`
 * blocks followed by its linked `<link rel=stylesheet>` files (read through the epub reader), with
 * `@import`-style nesting already resolved only at parse time — here each entry is one stylesheet
 * source. The [LightCssParser] parses every entry and flattens the resulting rules into a single
 * [StyleSheet] for the cascade step.
 */
class CssBundle(
    /** Ordered raw CSS sources: embedded `<style>` text first, then linked stylesheets. */
    val cssTexts: List<String>,
    /**
     * P2: 每份源的基准 href（与 [cssTexts] 同序；嵌入源＝章节 href，链接源＝样式表 href；
     * 缺省空即全部按章节 href 解析）。`@font-face`/`@import` 相对 URL 的解析依据。
     */
    val baseHrefs: List<String> = emptyList(),
)