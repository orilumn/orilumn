package orilumn.reader.engine.html

/**
 * C2-P2b-4: 块级标签集合（从 `:app CssLayouter.BLOCK_TAGS` 原样搬运，纯数据）。
 *
 * 判定"块 vs 行内"的单源：盒流 `heavyClassify`/轻路径 `lightClassify`/塑形 `isBlock`
 * 三路共用。`:app CssLayouter.BLOCK_TAGS` 保留作委托（兼容其单测）。
 */
val BLOCK_TAGS: Set<String> = setOf(
    "p", "div", "blockquote", "pre", "ul", "ol", "li",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "header", "footer", "figure", "figcaption",
    "main", "hgroup", "details", "summary",
)
