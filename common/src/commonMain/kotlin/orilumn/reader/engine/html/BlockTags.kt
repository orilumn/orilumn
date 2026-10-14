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

/**
 * 标题标签集合（`h1`–`h6`）：章节名提取（阅读顶栏 / 宿主 `unitTitle`）的单源。
 *
 * 章节名只认标题元素自身的文本——正文段落一律不算（详见
 * `BookDocumentController.chapterTitle`）。
 */
val HEADING_TAGS: Set<String> = setOf("h1", "h2", "h3", "h4", "h5", "h6")

/**
 * Q1-3 单源：code-like 标签集合（代码字体槽路由）。
 *
 * 原三处同义定义（`shared-ui ReaderMath` / `engine-skia SkParagraphFactory` /
 * `:app ParagraphShapes`，字面全同）收敛至此；`engine-skia` 字体候选与
 * `shared-ui` 槽位路由都以此为准，不再各自复刻。
 */
val CODE_TAGS: Set<String> = setOf("pre", "code", "kbd", "samp")
