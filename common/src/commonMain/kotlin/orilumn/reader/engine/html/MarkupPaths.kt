package orilumn.reader.engine.html

/**
 * 树路径工具（纯逻辑，排版层与持久化共用）。
 *
 * 节点身份跨进程/跨副本只能用「从章根出发的子下标路径」表达；**块叶元素的路径不受叶内文字
 * 变化影响**（叶内文本节点被拆开、`<br>` 增删都只改叶自己的 children），所以正文修订重放后
 * 仍能按原路径把结构缓存重链回新树（方案 §8.5）。
 *
 * 与 `ChapterStructurePersist` 里的同名逻辑是同一份实现（那边直接转调本对象），
 * 避免「两处路径规则漂移」。
 */
object MarkupPaths {

    /** 重链整棵树的 parent 指针（根为 null）。 */
    fun assignParents(root: MarkupElement) {
        for (child in root.children) {
            child.parent = root
            assignParents(child)
        }
    }

    /** [node] 相对 [root] 的子下标路径；不在 [root] 下返回 null。 */
    fun nodePath(root: MarkupElement, node: MarkupElement): IntArray? {
        val rev = ArrayList<Int>()
        var cur: MarkupElement? = node
        while (cur != null && cur !== root) {
            val p = cur.parent ?: return null
            val idx = p.children.indexOf(cur)
            if (idx < 0) return null
            rev.add(idx)
            cur = p
        }
        if (cur !== root) return null
        rev.reverse()
        return rev.toIntArray()
    }

    /** 从 [root] 按 [path] 走；任一步越界返回 null。 */
    fun resolvePath(root: MarkupElement, path: IntArray): MarkupElement? {
        var cur = root
        for (idx in path) {
            if (idx < 0 || idx >= cur.children.size) return null
            cur = cur.children[idx]
        }
        return cur
    }
}
