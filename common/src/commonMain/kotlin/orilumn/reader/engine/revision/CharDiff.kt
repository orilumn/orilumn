package orilumn.reader.engine.revision

/**
 * 字符级对齐（提交与重放共用同一实现、同一版本号，方案 D-33 / §8.5）。
 *
 * 为什么要有它：选区里的文字改完后，新字不是「整段套一个样式」，而是按**逐字归属**落回原来的
 * 文本节点——`<em>` 里的字改完仍在 `<em>` 里、`<strong>` 里的仍在 `<strong>` 里。
 * 只有把「哪些字没变」对齐出来，才能做到逐段保留类与样式。
 *
 * 算法：Myers 最短编辑脚本（O((N+M)·D)），前后缀先裁掉。并列解按固定规则取（删在前、插在后），
 * 保证同一对输入永远得到同一结果——重放两次必须逐字一致，否则同一份修订会排出不同分页。
 *
 * 退路：编辑距离过大（整段换新字）或文本过长时直接退化为「全删 + 全插」，
 * 与设计里「整段换新字时全部落在中段起点元素」一致；**不改变可见文字**，只影响样式归属。
 */
object CharDiff {
    /** 对齐算法版本：进 `contentRev`。改了并列解规则或退路阈值必须 +1（旧修订要重放）。 */
    const val VERSION = 1

    /** 编辑距离上限（超过即退化；段落级文本远达不到）。 */
    private const val MAX_COST = 4000

    /** 参与对齐的最长文本（防止病态输入把编辑距离撑爆）。 */
    private const val MAX_LENGTH = 20000

    /** 一条对齐操作，按输出顺序排列。 */
    sealed interface Op {
        /** [length] 个原文字符原样保留（起点 [oldStart]）。 */
        data class Keep(val oldStart: Int, val length: Int) : Op

        /** 删除原文 [oldStart, oldStart + length)。 */
        data class Delete(val oldStart: Int, val length: Int) : Op

        /** 插入新文本 [text]。 */
        data class Insert(val text: String) : Op
    }

    /** 对齐 [old] 与 [new]：返回 Keep / Delete / Insert 的有序序列。 */
    fun diff(old: String, new: String): List<Op> {
        val n = old.length
        val m = new.length
        if (n == 0) return if (m == 0) emptyList() else listOf(Op.Insert(new))
        if (m == 0) return listOf(Op.Delete(0, n))
        if (n + m > MAX_LENGTH) return degenerate(old, new)

        // 先裁公共前缀 / 后缀：绝大多数修改只动中间一小段。
        var prefix = 0
        val maxPrefix = minOf(n, m)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < maxPrefix - prefix &&
            old[n - 1 - suffix] == new[m - 1 - suffix]
        ) {
            suffix++
        }
        val a = old.substring(prefix, n - suffix)
        val b = new.substring(prefix, m - suffix)

        val out = ArrayList<Op>()
        if (prefix > 0) out.add(Op.Keep(0, prefix))
        out.addAll(myers(a, b, prefix))
        if (suffix > 0) out.add(Op.Keep(n - suffix, suffix))
        return merge(out)
    }

    private fun degenerate(old: String, new: String): List<Op> =
        listOf(Op.Delete(0, old.length), Op.Insert(new))

    /** 经典 Myers；[oldBase] 把局部下标换回 [old] 的绝对下标。 */
    private fun myers(a: String, b: String, oldBase: Int): List<Op> {
        val n = a.length
        val m = b.length
        if (n == 0) return if (m == 0) emptyList() else listOf(Op.Insert(b))
        if (m == 0) return listOf(Op.Delete(oldBase, n))
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>(minOf(max, MAX_COST) + 2)
        var found = -1
        val limit = minOf(max, MAX_COST)
        for (d in 0..limit) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                val idx = k + offset
                var x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    v[idx + 1]
                } else {
                    v[idx - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[idx] = x
                if (x >= n && y >= m) {
                    found = d
                    break
                }
                k += 2
            }
            if (found >= 0) break
        }
        if (found < 0) return listOf(Op.Delete(oldBase, n), Op.Insert(b))
        return backtrack(trace, found, a, b, offset, oldBase)
    }

    private fun backtrack(
        trace: List<IntArray>,
        dLast: Int,
        a: String,
        b: String,
        offset: Int,
        oldBase: Int,
    ): List<Op> {
        var x = a.length
        var y = b.length
        val ops = ArrayList<Op>()
        var d = dLast
        while (d > 0) {
            val v = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                k + 1
            } else {
                k - 1
            }
            val prevX = v[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
            }
            if (x == prevX) {
                // 插入：b[prevY, y)
                if (y > prevY) ops.add(Op.Insert(b.substring(prevY, y)))
                y = prevY
            } else {
                // 删除：a[prevX, x)
                if (x > prevX) ops.add(Op.Delete(oldBase + prevX, x - prevX))
                x = prevX
            }
            d--
        }
        // d == 0：剩下的全是公共前缀。
        if (x > 0) ops.add(Op.Keep(oldBase, x))
        ops.reverse()
        return merge(ops)
    }

    /** 合并相邻同类操作，保证输出规整（调用方按顺序消费）。 */
    private fun merge(ops: List<Op>): List<Op> {
        if (ops.size < 2) return ops
        val out = ArrayList<Op>(ops.size)
        for (op in ops) {
            val last = out.lastOrNull()
            when {
                op is Op.Keep && last is Op.Keep && last.oldStart + last.length == op.oldStart ->
                    out[out.size - 1] = Op.Keep(last.oldStart, last.length + op.length)
                op is Op.Delete && last is Op.Delete && last.oldStart + last.length == op.oldStart ->
                    out[out.size - 1] = Op.Delete(last.oldStart, last.length + op.length)
                op is Op.Insert && last is Op.Insert ->
                    out[out.size - 1] = Op.Insert(last.text + op.text)
                else -> out.add(op)
            }
        }
        return out
    }
}
