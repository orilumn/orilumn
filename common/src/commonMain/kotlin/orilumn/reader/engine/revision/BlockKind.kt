package orilumn.reader.engine.revision

import orilumn.reader.engine.html.MarkupElement

/**
 * 一块正文的**块级形态**（markdown 块标记 ↔ 章树里的块标签）。
 *
 * 正文修改卡里，每一段（一个排版叶）的第一行可以带一个块标记：`# `…`###### ` / `> ` / `- ` /
 * `1. ` / ```` ``` ````。标记只活在卡片里：提交时它变成「这一块应该长成什么」落进修订，
 * 重放时按它换叶子标签 / 增删 `blockquote` / `ul` / `ol` 包裹层，正文里永不出现标记字符
 * （与行内标记同一条铁律，见 [InlineMarkup]）。
 *
 * 只认 markdown 有的块：段落、一至六级标题、引用、无序 / 有序列表项、代码块。
 * 表格、图片、水平线不在其中（表格行叶与整块图片仍按 D-33 拒绝编辑）。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
enum class BlockKind(val wire: String) {
    /** 普通段落：树里就是 `p`，没有包裹层。 */
    Paragraph("p"),
    Heading1("h1"),
    Heading2("h2"),
    Heading3("h3"),
    Heading4("h4"),
    Heading5("h5"),
    Heading6("h6"),

    /** 引用：`p` 套一层 `blockquote`。 */
    Quote("quote"),

    /** 无序列表项：`li` 套一层 `ul`。 */
    Bullet("ul"),

    /** 有序列表项：`li` 套一层 `ol`（序号由排版自己数，卡片里一律写 `1. `）。 */
    Ordered("ol"),

    /** 代码块：`pre`，内容里的换行是书里的真换行，不是段界。 */
    Pre("pre"),
    ;

    /** 卡片里的块标记（块首第一行的前缀，行内写法，不含换行）。 */
    val marker: String
        get() = when (this) {
            Paragraph -> ""
            Quote -> "> "
            Bullet -> "- "
            Ordered -> "1. "
            Pre -> "```"
            else -> "#".repeat(headingLevel) + " "
        }

    /** 标题级别；不是标题返回 0。 */
    val headingLevel: Int
        get() = when (this) {
            Heading1 -> 1
            Heading2 -> 2
            Heading3 -> 3
            Heading4 -> 4
            Heading5 -> 5
            Heading6 -> 6
            else -> 0
        }

    /** 这一块在树里应该用的元素标签。 */
    val leafTag: String
        get() = when (this) {
            Paragraph, Quote -> "p"
            Bullet, Ordered -> "li"
            Pre -> "pre"
            else -> wire
        }

    /** 这一块要套的包裹层标签；null = 不套。 */
    val containerTag: String?
        get() = when (this) {
            Quote -> "blockquote"
            Bullet -> "ul"
            Ordered -> "ol"
            else -> null
        }

    /** 是不是「要包裹层」的块（引用 / 列表项）。 */
    val hasContainer: Boolean get() = containerTag != null

    companion object {

        /** 包裹层标签集合（多了 `li`：它在某些书里也可能包着段落）。 */
        val CONTAINER_TAGS = setOf("blockquote", "ul", "ol", "li")

        /** 落库 / 修订文字里的短码 → 形态；不认识（含空串）返回 null。 */
        fun fromWire(wire: String): BlockKind? =
            entries.firstOrNull { it.wire == wire && wire.isNotEmpty() }

        /** 标签 → 形态（不含容器判断；`li` / `p` 要看祖先）。 */
        fun ofTag(tag: String): BlockKind? = when (tag) {
            "p" -> Paragraph
            "li" -> Bullet
            "pre" -> Pre
            "h1" -> Heading1
            "h2" -> Heading2
            "h3" -> Heading3
            "h4" -> Heading4
            "h5" -> Heading5
            "h6" -> Heading6
            else -> null
        }

        /**
         * 一个排版叶当前的块形态；null = 这一块不归 markdown 管（`div` / `td` / 表格行之类），
         * 卡片里不显示块标记，也不许改它的块级形态。
         */
        fun ofElement(el: MarkupElement): BlockKind? {
            val own = ofTag(el.tag) ?: return null
            return when (el.tag) {
                // 标题 / 代码块：自己是什么就是什么（里面不会再有引用 / 列表层）。
                "p" -> when (nearestContainer(el)) {
                    "blockquote" -> Quote
                    "ul" -> Bullet
                    "ol" -> Ordered
                    else -> Paragraph
                }
                "li" -> if (nearestContainer(el) == "ol") Ordered else Bullet
                else -> own
            }
        }

        /**
         * 最近的容器祖先标签（`blockquote` / `ul` / `ol`）；没有返回 null。
         *
         * `li` 只是「列表项」这层壳，不是形态：`ul > li > p` 里那个 `p` 要看 `li` 外面的 `ul`，
         * 所以沿父链跳过 `li` 继续找。
         */
        fun nearestContainer(el: MarkupElement): String? {
            var p = el.parent
            while (p != null) {
                if (p.tag in CONTAINER_TAGS && p.tag != "li") return p.tag
                p = p.parent
            }
            return null
        }
    }
}

/**
 * 正文修改卡里「一行 = 一块」的块信息（渲染空间，由引擎按排版叶给出）。
 *
 * @property kind 这一块当前的块形态；null = 不归 markdown 管（`div` / 表格行之类），
 *   卡片里不写块标记，也不许改它的块级形态。
 * @property startsAtBlockStart 选区起点是否正好落在这块的开头：只有块首那一行能写块标记
 *   （第一行从段中间开始时，读者看到的半截文字承载不了「这一块该长成什么样」）。
 */
data class TextEditBlock(
    val kind: BlockKind?,
    val startsAtBlockStart: Boolean,
)
