package orilumn.reader.engine.revision

import orilumn.reader.engine.html.MarkupElement

/**
 * 块级重塑：按修订里的块级形态（[BlockKind]）改叶标签、增删 `blockquote` / `ul` / `ol` 包裹层。
 *
 * 只在重放时对**内存副本**动手（章树形状会变，磁盘上的原树一个字节都不动，见
 * [ChapterRevisionReplay]）。口径：
 *
 * - **只处理「变了」的叶**：调用方只把「目标形态 ≠ 当前形态」的叶交进来，没动的块一个节点都不换；
 * - **包裹层只做一层**：引用与列表各自一层，嵌套结构里只改最近的那一层容器（更深的保持原样）；
 * - **相邻同形态合并**：`> a` 与 `> b` 挨着就是同一个 `blockquote`，不会排成两个带间隔的引用块；
 * - **属性沿用**：新包裹层沿用原包裹层的属性（`class` / `style` 是书的排版），叶换标签时保留属性、
 *   只去掉 `id`（同一本书里 `id` 唯一，复制出两个就重复了）；
 * - **结构容器不拆**：`section` / `div` / 表格这类结构层只往下递归，不被摊平；
 *   `li` 也不摊平（`li > p` 这种多段列表项拆开会把兄弟段落弄丢缩进）。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
object BlockRestructure {

    /**
     * @property root 重塑后的章树（没改动时就是传进来的那棵）
     * @property nodeMap 被换掉的节点：老节点 → 新节点（换标签的叶、复制过的容器；
     *   没换的不在表里）。调用方拿它把叶表 / 装饰属主 / 生成内容搬到新树上。
     * @property changed 有没有真的动过结构
     */
    class Result(
        val root: MarkupElement,
        val nodeMap: Map<MarkupElement, MarkupElement>,
        val changed: Boolean,
    )

    /** 一个「块单位」：一个块元素 + 它该待在哪种容器里。 */
    private class Unit(
        val element: MarkupElement,
        val kind: BlockKind?,
        /** 元素本身就是一个**完整的容器**（`blockquote` / `ul` / `ol`）：可以整块保留或摊开复用。 */
        val container: Boolean,
    )

    /**
     * @param want 叶 → 目标形态（只含**与当前不同**的叶；相同的不放进来）
     */
    fun apply(root: MarkupElement, want: Map<MarkupElement, BlockKind>): Result {
        if (want.isEmpty()) return Result(root, emptyMap(), false)
        val ctx = Ctx(want)
        val kids = ctx.rebuildSequence(root.children, containerKindOf(root.tag))
        val newRoot = ctx.copyWith(root, kids)
        return Result(newRoot, ctx.nodeMap, newRoot !== root)
    }

    private class Ctx(val want: Map<MarkupElement, BlockKind>) {

        val nodeMap = HashMap<MarkupElement, MarkupElement>()
        private val containerAttrs = HashMap<BlockKind, Map<String, String>>()

        /** 需要改的叶的祖先：只有这些节点下面才可能有结构变动。 */
        private val affected = HashSet<MarkupElement>().also { set ->
            for (leaf in want.keys) {
                var p = leaf.parent
                while (p != null) {
                    if (!set.add(p)) break
                    p = p.parent
                }
            }
        }

        fun copyWith(node: MarkupElement, kids: List<MarkupElement>): MarkupElement {
            if (kids.size == node.children.size && kids.indices.all { kids[it] === node.children[it] }) return node
            val copy = MarkupElement(node.tag, node.attrs, kids, node.text)
            nodeMap[node] = copy
            return copy
        }

        private fun applyTag(leaf: MarkupElement, kind: BlockKind): MarkupElement {
            val tag = kind.leafTag
            if (leaf.tag == tag) return leaf
            val copy = MarkupElement(tag, leaf.attrs - "id", leaf.children, leaf.text)
            copy.parent = leaf.parent
            nodeMap[leaf] = copy
            return copy
        }

        fun rebuildSequence(nodes: List<MarkupElement>, inherited: BlockKind?): List<MarkupElement> {
            if (nodes.isEmpty()) return nodes
            val anyAffected = nodes.any { it in affected || want.containsKey(it) }
            if (!anyAffected) return nodes
            val units = ArrayList<Unit>(nodes.size)
            for (n in nodes) units.addAll(flatten(n, inherited))
            return emit(units)
        }

        private fun flatten(node: MarkupElement, inherited: BlockKind?): List<Unit> {
            val kind = want[node]
            if (kind != null) return listOf(Unit(applyTag(node, kind), kind, container = false))
            val self = containerKindOf(node.tag)
            if (self != null) {
                // 容器属性（书的 `class` / `style`）无论拆不拆都先记下：拆开后重新成组时沿用它。
                if (self != BlockKind.Bullet || node.tag != "li") containerAttrs.putIfAbsent(self, node.attrs)
                // 块容器：要改的叶在下面时摊平成一串单位（同形态的相邻单位稍后重新成组），
                // 否则整块原样保留（它已经是一个「就位的容器」）。
                if (node !in affected) return listOf(Unit(node, self, container = true))
                if (node.tag == "li") {
                    // `li` 不摊平：多段列表项里的兄弟段落属于这个列表项，拆开就丢了缩进。
                    // 里面是行内内容，不继承形态 —— 否则一段裸文字会被当成列表项包起来。
                    return listOf(Unit(copyWith(node, rebuildSequence(node.children, null)), self, false))
                }
                return node.children.flatMap { flatten(it, self) }
            }
            if (node !in affected) return listOf(Unit(node, inherited, container = false))
            // 结构容器（`section` / `div` / 表格…）：只在它内部重组，整体仍是一个单位。
            val kids = rebuildSequence(node.children, inherited)
            return listOf(Unit(copyWith(node, kids), inherited, container = false))
        }

        private fun emit(units: List<Unit>): List<MarkupElement> {
            val out = ArrayList<MarkupElement>(units.size)
            var i = 0
            while (i < units.size) {
                val kind = units[i].kind
                // 不要容器的块（段落 / 标题 / 代码块）：原样排。
                if (kind == null || !kind.hasContainer) {
                    out.add(units[i].element)
                    i++
                    continue
                }
                val containerTag = kind.containerTag ?: kind.leafTag
                // 相邻同形态的块归到同一个容器里；中间夹了别的形态就断开。
                var j = i
                while (j < units.size && units[j].kind == kind) j++
                val run = units.subList(i, j)
                if (run.size == 1 && run[0].container && run[0].element.tag == containerTag) {
                    // 原本就是一个完整的容器、前后也不挨着同形态：原样保留（属性与身份都不动）。
                    out.add(run[0].element)
                } else {
                    val children = ArrayList<MarkupElement>(run.size)
                    for (u in run) {
                        if (u.container && u.element.tag == containerTag) {
                            children.addAll(u.element.children)
                        } else {
                            children.add(u.element)
                        }
                    }
                    out.add(MarkupElement(containerTag, containerAttrs[kind] ?: emptyMap(), children))
                }
                i = j
            }
            return out
        }
    }

    /** 容器标签 → 容器形态（`li` 也认：列表项的形态由它决定）。 */
    fun containerKindOf(tag: String): BlockKind? = when (tag) {
        "blockquote" -> BlockKind.Quote
        "ul" -> BlockKind.Bullet
        "ol" -> BlockKind.Ordered
        "li" -> BlockKind.Bullet
        else -> null
    }
}
