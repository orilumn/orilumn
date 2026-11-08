package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 坐标换算单测：原书 ↔ 渲染，纯算术，不依赖排版。 */
class ChapterRevisionMapTest {

    private fun rev(start: Int, end: Int, text: String, id: Long = 0, group: Long = 0): TextRevision =
        TextRevision(
            id = id,
            bookId = 1,
            chapter = 1,
            charStart = start,
            charEnd = end,
            text = text,
            delta = text.length - (end - start),
            groupId = group,
        )

    @Test
    fun noRevisionsIsIdentity() {
        val map = ChapterRevisionMap.EMPTY
        assertEquals(0L, map.contentRev)
        assertEquals(5, map.toRendered(5))
        assertEquals(5, map.toOriginal(5))
        assertEquals(2 until 7, map.toRenderedRange(2, 7))
    }

    @Test
    fun replacementShiftsFollowingOffsets() {
        // 原书「甲乙丙丁」，把 [1,3)「乙丙」换成「X」（delta = -1）。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertEquals(0, map.toRendered(0))
        assertEquals(1, map.toRendered(1))
        assertEquals(2, map.toRendered(3))
        assertEquals(3, map.toRendered(4))
        assertEquals(3, map.renderedLength(4))

        assertEquals(0, map.toOriginal(0))
        assertEquals(1, map.toOriginal(1))
        assertEquals(3, map.toOriginal(2))
        assertEquals(4, map.toOriginal(3))
    }

    @Test
    fun insertionBoundarySemantics() {
        // 在位置 2 插入「XY」（delta = 2）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))

        // 单点默认越过插入。
        assertEquals(4, map.toRendered(2))

        // 区间起点越过插入、终点不越过：标注 [0,2) 仍是 [0,2)，新字不进旧标注。
        assertEquals(0 until 2, map.toRenderedRange(0, 2))
        // 区间起点在插入处：越过 → 从插入之后开始。
        assertEquals(4 until 5, map.toRenderedRange(2, 3))
        // 区间终点在插入处：不越过 → 停在插入之前。
        assertEquals(0 until 2, map.toRenderedRange(0, 2))

        // 反算：渲染 0..2 仍是原书 0..2，渲染 2..4 是插入本身。
        assertEquals(0 until 2, map.toOriginalRange(0, 2))
        assertEquals(2 until 2, map.toOriginalRange(2, 4))
    }

    @Test
    fun deletionCollapsesRange() {
        // 删掉原书 [1,3)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "")))
        assertEquals(1, map.toRendered(1))
        assertEquals(1, map.toRendered(3))
        assertEquals(2, map.renderedLength(4))
        // 原书 [1,3) 映射成空区间（标注被完全删掉时装饰消失、记录保留）。
        assertEquals(1 until 1, map.toRenderedRange(1, 3))
        assertEquals(1 until 1, map.toOriginalRange(1, 1))
    }

    @Test
    fun multipleRevisionsAccumulateShift() {
        val map = ChapterRevisionMap(
            listOf(
                rev(1, 2, "aa"), // +1
                rev(5, 6, ""), // -1
            ),
        )
        assertEquals(0, map.toRendered(0))
        assertEquals(1, map.toRendered(1))
        assertEquals(3, map.toRendered(2))
        assertEquals(6, map.toRendered(5))
        assertEquals(6, map.toRendered(6))
        assertEquals(7, map.toRendered(7))

        assertEquals(0, map.toOriginal(0))
        assertEquals(1, map.toOriginal(1))
        assertEquals(2, map.toOriginal(3))
        assertEquals(5, map.toOriginal(6))
        assertEquals(7, map.toOriginal(7))
    }

    @Test
    fun touchingRevisionsIncludeBoundaryInsertions() {
        val map = ChapterRevisionMap(
            listOf(
                rev(0, 2, "x", id = 1),
                rev(2, 2, "插入", id = 2),
                rev(4, 6, "y", id = 3),
            ),
        )
        assertEquals(listOf(2L), map.revisionsTouching(2, 2).map { it.id })
        assertEquals(listOf(1L), map.revisionsTouching(0, 2).map { it.id })
        assertEquals(listOf(3L), map.revisionsTouching(3, 5).map { it.id })
    }

    @Test
    fun reviewSpaceKeepsOriginalAndPutsNewTextAfterIt() {
        // 原书「甲乙丙丁戊」，把 [1,3)「乙丙」换成「XY」：审阅空间 = 甲乙丙XY丁戊。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        // 原文按原位排，之后的字整体后移。
        assertEquals(0, map.toReview(0))
        assertEquals(1, map.toReview(1))
        assertEquals(3, map.toReview(3, crossInsertions = false))
        // 单点默认越过新文字（新文字排在边界之前）。
        assertEquals(5, map.toReview(3))
        assertEquals(6, map.toReview(4))
        assertEquals(10, map.toReview(8, crossInsertions = false))

        // 删除痕迹 = 原文区间，不含新文字；新增痕迹 = 新文字本身。
        val r = map.revisions.single()
        assertEquals(1 until 3, map.reviewDeletedRange(r))
        assertEquals(3 until 5, map.reviewInsertedRange(r))

        // 审阅 → 原书：原文照回原位，落进新文字的夹回区间末尾。
        assertEquals(0, map.toOriginalFromReview(0))
        assertEquals(1, map.toOriginalFromReview(1))
        assertEquals(3, map.toOriginalFromReview(4))
        assertEquals(3, map.toOriginalFromReview(3))
        assertEquals(3, map.toOriginalFromReview(5))
        assertEquals(4, map.toOriginalFromReview(6))
    }

    @Test
    fun reviewSpacePureInsertionSitsBeforeOriginalStart() {
        // 位置 2 插入「XY」：审阅空间 = 甲乙XY丙…（新文字在原位置之前，与原书渲染一致）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        val r = map.revisions.single()
        assertEquals(2 until 2, map.reviewDeletedRange(r))
        assertEquals(2 until 4, map.reviewInsertedRange(r))
        assertEquals(2, map.toReview(2, crossInsertions = false))
        assertEquals(4, map.toReview(2))
        assertEquals(5, map.toReview(3))
    }

    @Test
    fun reviewRangeCoversOriginalPlusInnerInsertions() {
        val map = ChapterRevisionMap(listOf(rev(5, 6, "ZZZ"))) // 原文 [5,6) 之后插 3 字
        // 区间起点越过该处插入、终点不越过：整段原文 + 内嵌新文字。
        val s = map.toReview(2, crossInsertions = true)
        val e = map.toReview(8, crossInsertions = false)
        assertEquals(2 until 11, s until e)
        // 正好等于该修订的区间：只框住原文，不吞新文字。
        assertEquals(5 until 6, map.toReview(5) until map.toReview(6, crossInsertions = false))
    }

    @Test
    fun reviewIntervalCoversDeletedAndInsertedHalves() {
        // 原书「甲乙丙丁戊」→ 审阅「甲乙丙XY丁戊」：一条修订占 [1,5)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        val r = map.revisions.single()
        assertEquals(1 until 5, map.reviewInterval(r))
        // 纯插入：原区间为空，整段就是新文字。
        val ins = ChapterRevisionMap(listOf(rev(2, 2, "XY"))).revisions.single()
        assertEquals(2 until 4, ChapterRevisionMap(listOf(ins)).reviewInterval(ins))
    }

    @Test
    fun reviewSelectionMapsBackToOriginalRange() {
        // 审阅「甲乙丙XY丁戊」：选原文「乙丙」→ 原书 [1,3)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 3))
        // 选新文字「XY」（审阅 [3,5)）→ 抬起那条修订的原区间 [1,3)，否则等于原地加字。
        assertEquals(1 until 3, map.originalEditRangeFromReview(3, 5))
        // 跨过删掉的原文与新添的文字整段 → 同样是 [1,3)。
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 5))
        // 没碰到修订：原样换算。
        assertEquals(0 until 1, map.originalEditRangeFromReview(0, 1))
    }

    @Test
    fun reviewSelectionOfPureInsertionKeepsPoint() {
        // 审阅「甲乙XY丙」里选新文字「XY」（审阅 [2,4)）→ 原书区间是空的（纯插入）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        assertTrue(map.originalEditRangeFromReview(2, 4).isEmpty())
        assertEquals(1, map.revisionsTouchingReview(2, 4).size)
    }

    @Test
    fun originalTextFromReviewStripsInsertedText() {
        // 审阅「甲乙丙XY丁戊」剪掉新文字 ⇒ 原书「甲乙丙丁戊」。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        assertEquals("甲乙丙丁戊", map.originalTextFromReview("甲乙丙XY丁戊"))
        // 纯插入同样剪掉。
        val ins = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        assertEquals("甲乙丙丁", ins.originalTextFromReview("甲乙XY丙丁"))
        // 无修订原样返回。
        assertEquals("甲乙", ChapterRevisionMap.EMPTY.originalTextFromReview("甲乙"))
    }

    @Test
    fun structureOnlyRevisionTakesNoReviewCopy() {
        // 「甲乙丙丁」里把 [1,3)「乙丙」换回「乙丙」（只删段间换行）：审阅空间与渲染空间逐字相同。
        val r = rev(1, 3, "乙丙", id = 1)
        val map = ChapterRevisionMap(listOf(r), structureOnly = setOf(1L))
        // 原文原位排，后面不再多一份「新文字」副本。
        assertEquals(0, map.toReview(0))
        assertEquals(3, map.toReview(3, crossInsertions = false))
        assertEquals(4, map.toReview(4))
        assertEquals(4, map.toOriginalFromReview(4))
        // 没有红绿两半：删掉的是块边界，不是一个字。
        assertTrue(map.reviewDeletedRange(r).isEmpty())
        assertTrue(map.reviewInsertedRange(r).isEmpty())
        // 点按合并后的那一段仍要认得出这条修订（开卡改它 / 删它）。
        assertEquals(1 until 3, map.reviewInterval(r))
        assertEquals(listOf(1L), map.revisionsTouchingReview(1, 3).map { it.id })
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 3))
        // 审阅文本剪新文字这一步不受影响（本来就没副本）。
        assertEquals("甲乙丙丁", map.originalTextFromReview("甲乙丙丁"))
    }

    @Test
    fun deletedParagraphBreakLeavesMarkInReviewSpace() {
        // 原书两段「甲乙」「丙丁」，段界在原书偏移 2；删掉段间换行 ⇒ [0,4) 换回「甲乙丙丁」。
        val r = rev(0, 4, "甲乙丙丁", id = 1)
        val map = ChapterRevisionMap(
            listOf(r),
            structureOnly = setOf(1L),
            originalBlockEnds = intArrayOf(2),
        )
        // 审阅空间 = 「甲乙¶丙丁」：¶ 占原段界那一格。
        assertEquals(listOf(2), map.reviewBreakMarkers.toList())
        assertEquals(3, map.toReview(2))
        assertEquals(4, map.toReview(3, crossInsertions = false))
        // ¶ 自己不对应原书的字，落在它上面就夹回段界。
        assertEquals(2, map.toOriginalFromReview(2))
        assertEquals(2, map.toOriginalFromReview(3))
        assertEquals(3, map.toOriginalFromReview(4))
        // 取原书文字时痕迹要剪掉（修改卡 / 复制 / 摘录都走这条）。
        assertEquals("甲乙丙丁", map.originalTextFromReview("甲乙¶丙丁"))
        // 正常视图（渲染空间）不受影响：一个字都没变，也不多痕迹。
        assertTrue(map.reviewDeletedRange(r).isEmpty())
        assertTrue(map.reviewInsertedRange(r).isEmpty())
        assertEquals(4, map.toRendered(4))
    }

    @Test
    fun mergingThreeParagraphsLeavesTwoMarks() {
        // 「甲乙」「丙丁」「戊己」两处段界（2、4）都删掉 ⇒ [0,6) 换回一整段。
        val r = rev(0, 6, "甲乙丙丁戊己", id = 1)
        val map = ChapterRevisionMap(
            listOf(r),
            structureOnly = setOf(1L),
            originalBlockEnds = intArrayOf(2, 4),
        )
        assertEquals(listOf(2, 5), map.reviewBreakMarkers.toList())
        assertEquals("甲乙丙丁戊己", map.originalTextFromReview("甲乙¶丙丁¶戊己"))
        // 「戊」在审阅空间是 6、原书是 4；章尾 8 对回 6。
        assertEquals(4, map.toOriginalFromReview(6))
        assertEquals(6, map.toOriginalFromReview(8))
    }

    @Test
    fun insertedParagraphBreakInsideOneLeafLeavesGreenMark() {
        // 读者在一段之内敲回车：修订被裁成 [2, 2) 只留一个段界字符（原书这里没有块尾）。
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 2,
            charEnd = 2,
            text = RevisionBlocks.SEPARATOR.toString(),
            // 段界不占字：delta 为 0（提交侧按 renderedLength 算，就是这个值）。
            delta = 0,
        )
        val map = ChapterRevisionMap(
            listOf(r),
            structureOnly = setOf(1L),
            originalBlockEnds = intArrayOf(4),
        )
        // 审阅空间 = 「甲乙¶丙丁」：绿 ¶ 顶在断行处上一行末尾，占一格。
        assertEquals(listOf(2), map.reviewBreakMarkers.toList())
        assertEquals(listOf(2), map.reviewInsertedBreakMarkers.toList())
        assertTrue(map.reviewDeletedBreakMarkers.isEmpty())
        assertEquals(2, map.toReview(2, crossInsertions = false))
        assertEquals(3, map.toReview(2))
        // 原来 2 之后的字整体后移一格（¶ 占一格）。
        assertEquals(4, map.toReview(3, crossInsertions = false))
        // 零长度修订：点那个 ¶ 也要认得出来（开卡改它 / 删它）。
        assertEquals(2 until 3, map.reviewInterval(r))
        assertEquals(listOf(1L), map.revisionsTouchingReview(2, 3).map { it.id })
        // 取原书文字时绿 ¶ 一样要剪掉。
        assertEquals("甲乙丙丁", map.originalTextFromReview("甲乙¶丙丁"))
        // 正常视图不受痕迹影响：段界不占字，一个字都没变。
        assertEquals(2, map.toRendered(2))
        assertEquals(4, map.toRendered(4))
    }

    @Test
    fun keptParagraphBreakLeavesNoMark() {
        // 三段的选区里只删掉了第一处段界，第二处换行还留着（替换文字里还有 U+E000）。
        val r = rev(0, 6, "甲乙丙丁${RevisionBlocks.SEPARATOR}戊己", id = 1)
        val map = ChapterRevisionMap(
            listOf(r),
            structureOnly = setOf(1L),
            originalBlockEnds = intArrayOf(2, 4),
        )
        // 只有真被删掉的那一处留 ¶；留着的换行照旧分段，不留痕迹。
        assertEquals(listOf(2), map.reviewBreakMarkers.toList())
        assertEquals(5, map.toReview(4))
        assertEquals(6, map.toReview(5))
    }

    @Test
    fun insertedParagraphBreakCountsGreenMarkInReviewCopy() {
        // 在 2 处插入「甲[段界]乙」：审阅空间的副本是「甲¶乙」——段界排成真块边界（不占字符），
        // 断行处留一个绿 ¶（占一格），所以副本三个字。
        val r = rev(2, 2, "甲${RevisionBlocks.SEPARATOR}乙")
        val map = ChapterRevisionMap(listOf(r))
        assertEquals(2 until 5, map.reviewInsertedRange(r))
        // 副本里那 3 个字符都夹回插入点；副本之后紧挨着的原书字从 5 开始（一个段界只占一格）。
        assertEquals(2, map.toOriginalFromReview(2))
        assertEquals(2, map.toOriginalFromReview(4))
        assertEquals(2, map.toOriginalFromReview(5))
        assertEquals(5, map.toReview(2))
        // 剪掉副本又是原书那一串。
        assertEquals("甲乙", map.originalTextFromReview("甲乙甲¶乙"))
        // 副本里那个绿 ¶ 也要能被「取文字」跳过（摘录 / 复制 / 修改卡初值）。
        assertEquals(listOf(3), map.reviewMarkOffsets.toList())
    }

    @Test
    fun structureOnlyRevisionKeepsLaterReviewsInRenderedSpace() {
        // [1,3) 只挪段界（无副本），[3,4) 把「丁」换成「XY」：审阅空间 = 「甲乙丙丁XY」。
        val shape = rev(1, 3, "乙丙", id = 1, group = 1)
        val edit = rev(3, 4, "XY", id = 2, group = 2)
        val map = ChapterRevisionMap(listOf(shape, edit), structureOnly = setOf(1L))
        assertEquals(3, map.toReview(3, crossInsertions = false))
        assertEquals(6, map.toReview(4))
        assertEquals(4 until 6, map.reviewInsertedRange(edit))
        assertEquals(4, map.toOriginalFromReview(5))
        assertEquals("甲乙丙丁", map.originalTextFromReview("甲乙丙丁XY"))
    }

    @Test
    fun contentRevIsStableAndSensitive() {
        val a = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        val b = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertEquals(a.contentRev, b.contentRev)

        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(1, 3, "Y"))).contentRev)
        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(2, 4, "X"))).contentRev)
        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(1, 3, "X", group = 9))).contentRev)
        // 顺序不影响（内部按起点排序）。
        val x = rev(1, 2, "a", id = 1)
        val y = rev(5, 6, "b", id = 2)
        assertEquals(
            ChapterRevisionMap(listOf(x, y)).contentRev,
            ChapterRevisionMap(listOf(y, x)).contentRev,
        )
        // 无修订固定为 0（老缓存按 0 处理）。
        assertEquals(0L, ChapterRevisionMap(emptyList()).contentRev)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingRevisionsRejected() {
        ChapterRevisionMap(listOf(rev(1, 5, "a"), rev(4, 8, "b")))
    }

    @Test
    fun touchingInsertionAtSamePointIsAllowed() {
        // 同点纯插入本应被提交侧合并；表侧容忍（按 id 排先后），不当作重叠。
        val map = ChapterRevisionMap(
            listOf(rev(3, 3, "甲", id = 1), rev(3, 3, "乙", id = 2)),
        )
        assertTrue(map.contentRev != 0L)
        assertEquals(3, map.toRendered(3, crossInsertions = false))
        assertEquals(5, map.toRendered(3, crossInsertions = true))
    }

    // ─────────────────────────────────────────────────────────────────
    // 上色收窄：整段原文照排，但只有真正改动的那几个字标红 / 标绿
    // ─────────────────────────────────────────────────────────────────

    /** `[start0, end0, start1, end1, …]` → 便于断言的区间表。 */
    private fun pairs(spans: IntArray?): List<IntRange> {
        if (spans == null) return emptyList()
        val out = ArrayList<IntRange>(spans.size / 2)
        var i = 0
        while (i + 1 < spans.size) {
            out += spans[i] until spans[i + 1]
            i += 2
        }
        return out
    }

    @Test
    fun paintedSpansKeepUntouchedTextPlain() {
        // 原书「甲乙略微一攥，然后」，读者把 [2,7)「略微一攥，」改成「¶略微一攥，¶然后」：
        // 原文那五个字一个都没删，只是前后各插了一个段界、末尾加了「然后」。
        val r = rev(2, 7, "${RevisionBlocks.SEPARATOR}略微一攥，${RevisionBlocks.SEPARATOR}然后", id = 1)
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(1L to "略微一攥，"))
        // 一个红字都没有：原文里本来就有的字不该标红。
        assertTrue(map.paintedDeleted.isEmpty())
        // 副本 = 「¶略微一攥，¶然后」（7 起）：两个绿 ¶ + 绿「然后」，中间那五个字是原样文字。
        assertEquals(
            listOf(7 until 8, 13 until 14, 14 until 16),
            pairs(map.paintedInserted[1L]),
        )
        // 交互口径不动：整段（原文 + 新文字）仍然属于同一条修订。
        assertEquals(2 until 16, map.reviewInterval(r))
    }

    @Test
    fun paintedSpansMarkOnlyChangedChars() {
        // 「甲乙丙丁」把 [1,4)「乙丙丁」改成「乙X丁」：只有「丙」被删、只有「X」是新写的。
        val r = rev(1, 4, "乙X丁", id = 1)
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(1L to "乙丙丁"))
        assertEquals(listOf(2 until 3), pairs(map.paintedDeleted[1L]))
        // 副本从 4 起排：「乙X丁」，新写的只有中间那格。
        assertEquals(listOf(5 until 6), pairs(map.paintedInserted[1L]))
    }

    @Test
    fun paintedSpansForPlainReplacementAndDeletion() {
        val replace = rev(1, 3, "X", id = 1)
        val map = ChapterRevisionMap(listOf(replace), originalTexts = mapOf(1L to "乙丙"))
        assertEquals(listOf(1 until 3), pairs(map.paintedDeleted[1L]))
        assertEquals(listOf(3 until 4), pairs(map.paintedInserted[1L]))

        val deletion = rev(1, 3, "", id = 1)
        val del = ChapterRevisionMap(listOf(deletion), originalTexts = mapOf(1L to "乙丙"))
        assertEquals(listOf(1 until 3), pairs(del.paintedDeleted[1L]))
        assertTrue(del.paintedInserted.isEmpty())
    }

    @Test
    fun paintedSpansFallBackToWholeRangeWithoutOriginalText() {
        // 没给原文（老数据 / 取不到原书叶文本）时不上色收窄，宿主退回整段口径。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertTrue(map.paintedDeleted.isEmpty())
        assertTrue(map.paintedInserted.isEmpty())
    }
}
