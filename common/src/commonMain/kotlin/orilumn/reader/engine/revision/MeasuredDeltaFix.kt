package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision

/**
 * 用**载入重放的实测长度**修正库里的修订 `delta`（方案 §8.10 最大风险）。
 *
 * 提交时的预归一尽量让「渲染文本 = 原文本按区间替换」严格成立，但节点边界的空白折叠
 * （两个文本节点相接处、生成内容、小型大写）仍可能让重放实测长度与库里的 `delta` 差一两个字。
 * 分页 / 选区 / 标注都按重放后的字符起点算，所以**内存换算必须用实测值**，否则越改越偏。
 *
 * 修正只发生在内存：库里的修订保持原样（不静默改库）。同一叶有多条修订、实测又对不上时，
 * 把差额记到该叶**最后一条**上——叶内每条修订自身的长度一般没错，错的是叶内的空白边界；
 * 把差额归到末条能保证叶尾之后的所有坐标与排版严格一致。
 *
 * 属于排版层（上）的纯逻辑：不碰数据库、不碰章树，可 JVM 单测。
 */
object MeasuredDeltaFix {

    /**
     * @param revisions 本章全部修订（与 `ChapterRevisionMap.revisions` 同序）。
     * @param revisionsByLeaf 叶下标 → 该叶上真正被应用的修订（跨叶被跳过的不要放进来）。
     * @param measuredLeafDeltas 重放实测的叶增量（见 `ChapterRevisionReplay.Result.measuredLeafDeltas`）。
     * @param measuredById 跨叶修订的实测增量（修订 id → 增量）；按 id 直接修正，不参与叶内归并。
     * @param onDrift 发现不一致时回调（叶下标、库里合计、实测合计、被修正的修订 id），供调用方记日志。
     * @return 修正后的新表；没有偏差时返回 null（调用方保持原表，零分配）。
     */
    fun apply(
        revisions: List<TextRevision>,
        revisionsByLeaf: Map<Int, List<TextRevision>>,
        measuredLeafDeltas: Map<Int, Int>,
        measuredById: Map<Long, Int> = emptyMap(),
        onDrift: (leaf: Int, stored: Int, measured: Int, revisionId: Long) -> Unit = { _, _, _, _ -> },
    ): List<TextRevision>? {
        if (revisions.isEmpty()) return null
        if (revisionsByLeaf.isEmpty() && measuredById.isEmpty()) return null
        var fixed: MutableList<TextRevision>? = null
        // 跨叶修订：实测增量按修订 id 一一对应，直接改。
        if (measuredById.isNotEmpty()) {
            for (i in revisions.indices) {
                val r = revisions[i]
                val measured = measuredById[r.id] ?: continue
                if (measured == r.delta) continue
                val out = fixed ?: revisions.toMutableList().also { fixed = it }
                out[i] = r.copy(delta = measured)
                onDrift(-1, r.delta, measured, r.id)
            }
        }
        if (measuredLeafDeltas.isEmpty()) return fixed
        for ((leaf, revs) in revisionsByLeaf) {
            if (revs.isEmpty()) continue
            val measured = measuredLeafDeltas[leaf] ?: continue
            val stored = revs.sumOf { it.delta }
            val drift = measured - stored
            if (drift == 0) continue
            val target = revs.last()
            val out = fixed ?: revisions.toMutableList().also { fixed = it }
            val at = out.indexOfFirst { it === target }
            if (at < 0) continue
            out[at] = target.copy(delta = target.delta + drift)
            onDrift(leaf, stored, measured, target.id)
        }
        return fixed
    }
}
