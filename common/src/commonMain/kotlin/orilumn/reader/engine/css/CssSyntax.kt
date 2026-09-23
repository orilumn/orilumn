package orilumn.reader.engine.css

/**
 * Own CSS model (pure logic, zero external dependency, JVM unit-testable).
 *
 * This is the engine's CSS syntax representation, deliberately separated from any third-party
 * parser library so the cascade/layout layers depend only on these types. A parser (e.g.
 * [LightCssParser]) fills this model from a CSS text; swapping the parser never touches the consumers.
 */
data class StyleSheet(
    val rules: List<StyleRule>,
    /** `@font-face` 记录（P2：族名＋字重/斜体＋src 列表；级联只消费族名，装载走字体管线）。 */
    val cssFontFaces: List<CssFontFace> = emptyList(),
    /** `@import` 记录（P2：url＋媒体条件原文；由宿主内联加载，防环＋限深）。 */
    val imports: List<CssImport> = emptyList(),
)

/** `@font-face` 的单个 `src` 源（`local(...)` 恒跳过，只记 `url(...)`）。 */
data class CssFontFaceSource(
    val url: String,
    /** `format(...)` 提示原文小写（`woff2`/`woff`/`truetype`/`opentype`…；缺省空）。 */
    val format: String = "",
)

/** `@font-face` 记录：族名＋字重/斜体描述＋按序 src。 */
data class CssFontFace(
    /** 去引号族名（原文大小写保留；匹配时与 `font-family` 栈同规则比对）。 */
    val family: String,
    /** 字重（100–900；`normal`=400/`bold`=700；范围取首值；缺省 null＝未声明）。 */
    val weight: Int? = null,
    /** 斜体（`italic`/`oblique`=true；缺省 null＝未声明）。 */
    val italic: Boolean? = null,
    val sources: List<CssFontFaceSource> = emptyList(),
)

/** `@import` 记录：url＋媒体条件原文（空＝无条件）。 */
data class CssImport(
    val url: String,
    val media: String = "",
)

/**
 * `@media` 求值视口（px）。P2 仅处理版心宽/高查询，其余媒体类型安全忽略。
 * 为 null 时 `@media` 块整体丢弃（历史行为；宿主传真实版心即按条件内联）。
 */
data class CssViewport(
    val widthPx: Int,
    val heightPx: Int,
)

/** A style rule: selectors + a declaration block. */
data class StyleRule(
    val selectors: List<String>,
    val declarations: List<Declaration>,
)

/** A single property/value declaration (kept as raw strings; resolved later by the cascade). */
data class Declaration(
    val property: String,
    val value: String,
    val important: Boolean = false,
)

/**
 * Parses a CSS text into the engine's own [StyleSheet] model.
 *
 * Only this interface is exposed to the rest of the engine; concrete parsers (e.g. [LightCssParser])
 * may throw or return a partial sheet on malformed input. At-rules the reader cannot honor
 * (`@page`, `@namespace`, `@keyframes`, `@supports`, `@charset`, ...) are skipped by the parser,
 * keeping the model to style rules plus the P2 records (`@font-face`/`@import`/条件 `@media`)。
 */
fun interface CssParser {
    fun parse(css: String): StyleSheet

    /** P2: 带视口求值的解析（`@media` 宽/高查询按视口内联；null 视口即历史行为：整体丢弃）。 */
    fun parse(css: String, viewport: CssViewport?): StyleSheet = parse(css)
}