package orilumn.reader.engine.revision

/**
 * 「字一个没改、只改了标记」的修订：把被删掉 / 新写上的标记排进审阅空间。
 *
 * 红 / 绿的字义是**标记**，不是正文（用户 2026-11-12 定口：这类修订不适合染字色）：
 * 删掉的 `## ` 排成红字 + 删除线，新写的排成绿字，与 ¶ 同一套「标记当普通字符」的口径。
 * 读者在卡片里删了什么，审阅视图里就看到什么。
 *
 * 只对**文字逐字未变**的修订生效：改了字的修订，红绿已经标在字上，不必再排标记。
 *
 * 属于排版层（上）：纯逻辑，不碰排版与渲染，可 JVM 单测。
 */
object FormatTraces {

    /**
     * 块标记痕迹：`## 你好世界` 删掉 `## ` 变回正文时，在段首留一个红 `## `。
     *
     * @param oldText 这一段的原文（已去掉段界）。
     * @param newText 这一段的修订文字（已去掉段界）。
     * @param oldKind 这一块**原来**的形态（从书里取）；null = 取不到，不画。
     * @param newKind 这一块**应该**长成的形态（修订里的块级形态）。
     * @return 一处或两处痕迹，都排在段首（[ReviewOverlay.Trace.at] = 0）；没变时为空。
     */
    fun block(
        oldText: String,
        newText: String,
        oldKind: BlockKind?,
        newKind: BlockKind?,
    ): List<ReviewOverlay.Trace> {
        if (oldText != newText) return emptyList()
        if (oldKind == null || newKind == null || oldKind == newKind) return emptyList()
        val out = ArrayList<ReviewOverlay.Trace>(2)
        // 删掉的那个标记（红字 + 删除线）在前，新写上的（绿字）在后，与卡片里的书写顺序一致。
        if (oldKind.marker.isNotEmpty()) out += ReviewOverlay.Trace(0, oldKind.marker, isNew = false)
        if (newKind.marker.isNotEmpty()) out += ReviewOverlay.Trace(0, newKind.marker, isNew = true)
        return out
    }

    /**
     * 行内标记痕迹：`**`、`` ` ``、`~~`、`[文字](地址)` 这类标记被删 / 被加上时，
     * 在样式区间的两端各排一个标记（开标记排在区间第一个字之前，闭标记排在最后一个字之后）。
     *
     * 判据是「同一段文字上多了 / 少了哪个行内元素」：按 (起, 止, 形状) 比，
     * 原书有、修订里没有 ⇒ 红；修订里有、原书没有 ⇒ 绿。
     *
     * @param text 这一段文字（与修订文字逐字相同）。
     * @param oldSpans 这一段**原来**的行内结构（从书里投影出来，已裁到这一段）。
     * @param newSpans 修订里声明的行内结构；null = 老修订没存过，**不画**（不知道读者改了什么，不猜）。
     */
    fun inline(
        text: String,
        oldSpans: List<InlineMarkup.StyleSpan>,
        newSpans: List<InlineMarkup.StyleSpan>?,
    ): List<ReviewOverlay.Trace> {
        if (newSpans == null) return emptyList()
        if (oldSpans.isEmpty() && newSpans.isEmpty()) return emptyList()
        val old = oldSpans.filter { it.inRange(text) }
        val added = newSpans.filter { it.inRange(text) }
        val oldKeys = old.map { it.key }.toHashSet()
        val newKeys = added.map { it.key }.toHashSet()
        // 只有属性变了（`class` / `style` 的写法被卡片归一过）不算换了标记：同区间同标签的一对
        // 红绿痕迹互相抵消，免得每次改样式都多出两个长得一模一样的 `*`。
        val removed = old.filter { it.key !in newKeys }.toMutableList()
        val fresh = added.filter { it.key !in oldKeys }.toMutableList()
        val sameTag = { a: InlineMarkup.StyleSpan, b: InlineMarkup.StyleSpan ->
            a.start == b.start && a.endExclusive == b.endExclusive && a.sig.tag == b.sig.tag
        }
        val it2 = fresh.iterator()
        while (it2.hasNext()) {
            val b = it2.next()
            val a = removed.firstOrNull { x -> sameTag(x, b) } ?: continue
            removed.remove(a)
            it2.remove()
        }
        val out = ArrayList<ReviewOverlay.Trace>(removed.size + fresh.size + 2)
        append(out, text, removed, isNew = false)
        append(out, text, fresh, isNew = true)
        return out
    }

    /**
     * 这一段的全部格式痕迹（块标记 + 行内标记），按原文位置排好。
     *
     * @param oldSpans 这一段**原来**的行内结构；null = 取不到（结构未就绪 / 老宿主），行内部分不画。
     * @return 空表 = 没什么可画的（改了字 / 什么都没变 / 取不到原书结构）。
     */
    fun traces(
        oldText: String,
        newText: String,
        oldKind: BlockKind?,
        newKind: BlockKind?,
        oldSpans: List<InlineMarkup.StyleSpan>?,
        newSpans: List<InlineMarkup.StyleSpan>?,
    ): List<ReviewOverlay.Trace> {
        if (oldText != newText) return emptyList()
        val out = ArrayList<ReviewOverlay.Trace>(8)
        out += block(oldText, newText, oldKind, newKind)
        if (oldSpans != null) out += inline(oldText, oldSpans, newSpans)
        // 稳定排序：同一个位置上的痕迹保持「块标记 → 开标记（外层在前）→ 闭标记（内层在前）」。
        return out.sortedBy { it.at }
    }

    /** 一端的标记排进痕迹表：开标记按「起点升序、长的在前」，闭标记按「终点升序、短的在前」。 */
    private fun append(
        out: MutableList<ReviewOverlay.Trace>,
        text: String,
        spans: List<InlineMarkup.StyleSpan>,
        isNew: Boolean,
    ) {
        if (spans.isEmpty()) return
        val opens = spans.sortedWith(compareBy({ it.start }, { -it.endExclusive }))
        for (s in opens) {
            val (open, _) = InlineMarkup.markerPair(s.sig, text.substring(s.start, s.endExclusive))
            out += ReviewOverlay.Trace(s.start, open, isNew)
        }
        val closes = spans.sortedWith(compareBy({ it.endExclusive }, { -it.start }))
        for (s in closes) {
            val (_, close) = InlineMarkup.markerPair(s.sig, text.substring(s.start, s.endExclusive))
            out += ReviewOverlay.Trace(s.endExclusive, close, isNew)
        }
    }

    private val InlineMarkup.StyleSpan.key: Triple<Int, Int, String>
        get() = Triple(start, endExclusive, sig.shapeKey)

    private fun InlineMarkup.StyleSpan.inRange(text: String): Boolean =
        start >= 0 && endExclusive <= text.length && endExclusive > start
}
