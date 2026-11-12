package orilumn.reader.engine.revision

/**
 * 审阅空间的对齐层：**原文照排 + 改动就地插入**（2026-11-08 用户定口）。
 *
 * 为什么不再排「新文字副本」：副本会把没改的字再排一遍（把「略微一攥，」改成
 * 「¶略微一攥，¶然后」时，同一句在版面上出现两次），读者看不出哪一份才是正文。
 * 现在原文一个字都不删、也不另排一份：没改的字留在原位，新写的字插在它对上的位置，
 * 删掉的字也留在原位（红字 + 删除线由宿主按区间上色）。于是「改完之后的正文」只有一份，
 * 差别全在颜色与 ¶ 上。
 *
 * 本类的输入只有三样：**原书那一段文字**、**修订文字**（含段界 [RevisionBlocks.SEPARATOR]）、
 * **原书块尾**（区间内有几道段界）。输出这一段在审阅空间的文字与红 / 绿区间、每个原文位置
 * 对应的审阅位置——排版（拆块）、上色、坐标换算都从这一份结果走，避免三处各算一遍。
 *
 * 属于排版层（上）：纯逻辑，不碰排版与渲染，可 JVM 单测。
 */
object ReviewOverlay {

    /** 一处就地插入：原书局部偏移 [at] 处插进 [text]（绿字；段界痕迹 ¶ 也在里面）。 */
    class Insertion(val at: Int, val text: String)

    /**
     * 一条修订在审阅空间里的形态。
     *
     * @property text 这一段在审阅空间的文字：原文各字原样在内，新写的字插在原位，
     *   段界仍是 [RevisionBlocks.SEPARATOR]（不占字符，排版时拆块用）。
     * @property insertions 就地插入的几处（局部偏移升序；[text] 里除原文与段界之外的字符都在这里）。
     * @property deleted 红字区间（审阅空间局部坐标，升序的 `[start0, end0, …]`），含被删段界的红 ¶。
     * @property inserted 绿字区间（同上），含新插段界的绿 ¶。
     * @property marks 段界痕迹 ¶ 的位置 → 是否新插的（true = 绿，false = 红）。
     * @property needsBlocks 这一段有没有块边界要动（有原书段界要并、或新插了段界要拆）：
     *   要动就得走「整叶重塑」的重放，不能只做叶内插入。
     */
    class Plan(
        val text: String,
        val insertions: List<Insertion>,
        private val before: IntArray,
        private val after: IntArray,
        private val reviewToOld: IntArray,
        val deleted: IntArray,
        val inserted: IntArray,
        val marks: Map<Int, Boolean>,
        val needsBlocks: Boolean,
        /** 这一段在审阅空间的长度（段界不占字符）。 */
        val length: Int,
        /** 修订文字（去掉段界后）第 i 个字在审阅空间的偏移；-1 = 兜底（不该出现）。 */
        private val newToReview: IntArray,
        /** 修订文字（去段界）第 i 个字在 [text] 里的下标（含 ¶ 与段界）；-1 = 兜底。 */
        private val newToOut: IntArray,
    ) {
        /**
         * 修订文字 [text] 上的行内样式区间 → 本段审阅空间的区间（相对 [text] 起点）。
         *
         * 样式区间按 [text]（含段界）量，段界不占审阅空间的位置，先折成「第几个正文文字」，
         * 再落到审阅偏移。落在被保留的字上的样式自然收窄，落在段界上的部分被丢掉。
         */
        fun stylesInReview(
            text: String,
            styles: List<InlineMarkup.StyleSpan>,
        ): List<InlineMarkup.StyleSpan> {
            if (styles.isEmpty()) return emptyList()
            val bodyOf = IntArray(text.length + 1)
            var k = 0
            for (i in text.indices) {
                bodyOf[i] = k
                if (text[i] != RevisionBlocks.SEPARATOR) k++
            }
            bodyOf[text.length] = k
            val out = ArrayList<InlineMarkup.StyleSpan>(styles.size)
            for (sp in styles) {
                val a = bodyOf[sp.start.coerceIn(0, text.length)]
                val b = bodyOf[sp.endExclusive.coerceIn(0, text.length)]
                if (b <= a) continue
                val from = newToReview.getOrElse(a) { -1 }
                val last = newToReview.getOrElse(b - 1) { -1 }
                if (from < 0 || last < from) continue
                out.add(InlineMarkup.StyleSpan(from, last + 1, sp.sig))
            }
            return out
        }

        /**
         * 修订文字上的行内样式区间 → **本段审阅文字**（[text] 那一串，含 ¶ 与段界）上的区间。
         *
         * 与 [stylesInReview] 的分工：那个只给「叶内就地插入」用（只有新写的字有位置、
         * 坐标不含段界）；这个给**整段重放**用 —— 保留字也有位置，偏移直接能在 [text] 上切块。
         * 提交侧的样式区间按块切好、不跨段界，所以段界不必进区间。
         */
        fun stylesInText(
            text: String,
            styles: List<InlineMarkup.StyleSpan>,
        ): List<InlineMarkup.StyleSpan> {
            if (styles.isEmpty()) return emptyList()
            val bodyOf = IntArray(text.length + 1)
            var k = 0
            for (i in text.indices) {
                bodyOf[i] = k
                if (text[i] != RevisionBlocks.SEPARATOR) k++
            }
            bodyOf[text.length] = k
            val out = ArrayList<InlineMarkup.StyleSpan>(styles.size)
            for (sp in styles) {
                val a = bodyOf[sp.start.coerceIn(0, text.length)]
                val b = bodyOf[sp.endExclusive.coerceIn(0, text.length)]
                if (b <= a) continue
                val from = newToOut.getOrElse(a) { -1 }
                val last = newToOut.getOrElse(b - 1) { -1 }
                if (from < 0 || last < from) continue
                out.add(InlineMarkup.StyleSpan(from, last + 1, sp.sig))
            }
            return out
        }

        /** 原书局部偏移 → 审阅空间局部偏移（该点插入**之前**）。 */
        fun beforeOf(oldLocal: Int): Int = before[oldLocal.coerceIn(0, before.size - 1)]

        /** 原书局部偏移 → 审阅空间局部偏移（该点插入**之后**）。 */
        fun afterOf(oldLocal: Int): Int = after[oldLocal.coerceIn(0, after.size - 1)]

        /** 审阅空间局部偏移 → 原书局部偏移（落在插入的字上时夹回插入点）。 */
        fun oldAt(reviewLocal: Int): Int = reviewToOld[reviewLocal.coerceIn(0, reviewToOld.size - 1)]
    }

    /**
     * 对齐一段修订。
     *
     * @param old 这条修订盖住的**原书**文字（不含段界；跨叶时各叶顺序相连）。
     * @param text 修订文字（可能含段界）。
     * @param blockEnds 原书段界落在**这一段内部**的局部偏移（0 < e < old.length；升序）。
     * @return null = 对不上（纯插入以外的空文本等退化情形），调用方按渲染空间落字。
     */
    fun plan(old: String, text: String, blockEnds: IntArray): Plan? {
        val new = RevisionBlocks.textOf(text)
        val ops = CharDiff.diff(old, new)
        // 修订文字里每个段界落在「第几个正文文字之前」（段界不占字符）。
        val seps = ArrayList<Int>(2)
        run {
            var p = 0
            for (c in text) {
                if (c == RevisionBlocks.SEPARATOR) seps += p else p++
            }
        }
        val ends = blockEnds.filter { it > 0 && it < old.length }.toHashSet()

        val out = StringBuilder(old.length + new.length / 2 + 8)
        /** 每个字符位属于谁：0 原文保留、1 原文被删、2 新写的字、3 被删段界的 ¶、4 新插段界的 ¶。 */
        val kinds = ArrayList<Int>(out.length + 8)
        val reviewToOld = ArrayList<Int>(old.length + 8)
        val before = IntArray(old.length + 1) { -1 }
        val after = IntArray(old.length + 1) { -1 }
        val insertions = ArrayList<Insertion>(2)
        val insAt = ArrayList<Int>(2)
        val insText = ArrayList<StringBuilder>(2)
        val kept = HashSet<Int>(2)
        val marks = LinkedHashMap<Int, Boolean>(2)
        // 修订文字（去段界）第几个字 → 审阅空间偏移：样式区间靠它换算（见 [Plan.stylesInReview]）。
        val newToReview = IntArray(new.length) { -1 }
        // 修订文字（去段界）第几个字 → [out] 里的下标（含 ¶ 与段界）：整段重放按它切块。
        val newToOut = IntArray(new.length) { -1 }

        var o = 0
        var n = 0
        var si = 0
        // 段界不占字符位：正文位置单独数，所有区间都用它。
        var at = 0

        fun open(pos: Int) {
            if (before[pos] < 0) before[pos] = at
        }

        fun push(c: Char, kind: Int) {
            out.append(c)
            kinds += kind
            reviewToOld += o
            at++
        }

        fun pushInserted(chars: CharSequence, kind: Int) {
            for (c in chars) {
                open(o)
                push(c, kind)
                // 「越过该点处的插入」= 停在插入之后、原文字之前。
                after[o] = at
                if (insAt.isNotEmpty() && insAt.last() == o) {
                    insText.last().append(c)
                } else {
                    insAt += o
                    insText += StringBuilder().append(c)
                }
            }
        }

        fun flushSeps() {
            while (si < seps.size && seps[si] == n) {
                open(o)
                if (o in ends && o !in kept) {
                    // 原书这里本来就有段界、读者也没动它：段界照旧，留块边界，不画 ¶。
                    kept += o
                    out.append(RevisionBlocks.SEPARATOR)
                } else {
                    // 读者新插的段界：¶ 落在断行处上一行末尾（绿字），再拆块。
                    marks[at] = true
                    pushInserted("${ChapterRevisionMap.BREAK_MARK_CHAR}", 4)
                    out.append(RevisionBlocks.SEPARATOR)
                }
                si++
            }
        }

        fun emitOrig(deletedChar: Boolean) {
            open(o)
            if (after[o] < 0) after[o] = at
            if (o in ends && o !in kept) {
                // 这个段界被删掉了：合并后的那一段在原来断行处留一个红 ¶。
                marks[at] = false
                pushInserted("${ChapterRevisionMap.BREAK_MARK_CHAR}", 3)
            }
            push(old[o], if (deletedChar) 1 else 0)
            o++
        }

        for (op in ops) {
            when (op) {
                is CharDiff.Op.Delete -> repeat(op.length) { emitOrig(deletedChar = true) }
                is CharDiff.Op.Keep -> repeat(op.length) {
                    flushSeps()
                    emitOrig(deletedChar = false)
                    if (n < newToOut.size) newToOut[n] = out.length - 1
                    n++
                }
                is CharDiff.Op.Insert -> for (c in op.text) {
                    flushSeps()
                    if (n < newToReview.size) newToReview[n] = at
                    pushInserted(c.toString(), 2)
                    if (n < newToOut.size) newToOut[n] = out.length - 1
                    n++
                }
            }
        }
        flushSeps()
        open(old.length)
        after[old.length] = at
        reviewToOld += old.length

        val deleted = ArrayList<Int>(4)
        val inserted = ArrayList<Int>(4)
        var delStart = -1
        var insStart = -1
        for (i in kinds.indices) {
            val k = kinds[i]
            val del = k == 1 || k == 3
            val ins = k == 2 || k == 4
            if (del && delStart < 0) delStart = i
            if (!del && delStart >= 0) {
                deleted += delStart
                deleted += i
                delStart = -1
            }
            if (ins && insStart < 0) insStart = i
            if (!ins && insStart >= 0) {
                inserted += insStart
                inserted += i
                insStart = -1
            }
        }
        if (delStart >= 0) {
            deleted += delStart
            deleted += kinds.size
        }
        if (insStart >= 0) {
            inserted += insStart
            inserted += kinds.size
        }

        val list = ArrayList<Insertion>(insAt.size)
        for (i in insAt.indices) list += Insertion(insAt[i], insText[i].toString())
        val beforeOut = IntArray(old.length + 1) { if (before[it] >= 0) before[it] else 0 }
        val afterOut = IntArray(old.length + 1) { if (after[it] >= 0) after[it] else beforeOut[it] }
        return Plan(
            text = out.toString(),
            insertions = list,
            before = beforeOut,
            after = afterOut,
            reviewToOld = reviewToOld.toIntArray(),
            deleted = deleted.toIntArray(),
            inserted = inserted.toIntArray(),
            marks = marks,
            needsBlocks = ends.isNotEmpty() || seps.isNotEmpty(),
            length = at,
            newToReview = newToReview,
            newToOut = newToOut,
        )
    }
}
