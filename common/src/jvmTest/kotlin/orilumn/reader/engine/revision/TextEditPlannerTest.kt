package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文修改提交计划单测：按叶拆段、旧修订合并、公共前后缀裁剪（方案 §8.4）。
 *
 * 审阅清单两行（原文 / 新文字）存的是**整段选区**，与裁剪后的最小修订无关。
 */
class TextEditPlannerTest {

    private fun rev(
        start: Int,
        end: Int,
        text: String,
        id: Long,
        chapter: Int = 1,
        group: Long = 0,
    ): TextRevision = TextRevision(
        id = id,
        bookId = 1,
        chapter = chapter,
        charStart = start,
        charEnd = end,
        text = text,
        delta = text.length - (end - start),
        groupId = group,
    )

    private fun plan(
        text: String,
        ends: IntArray?,
        start: Int,
        endExclusive: Int,
        replacement: String,
        revisions: List<TextRevision> = emptyList(),
    ): TextEditPlan = TextEditPlanner.plan(
        bookId = 1,
        chapter = 1,
        chapterText = text,
        blockEnds = ends,
        start = start,
        endExclusive = endExclusive,
        replacement = replacement,
        map = ChapterRevisionMap(revisions),
        groupId = 99,
        now = 1234,
    )

    private fun ready(p: TextEditPlan): TextEditPlan.Ready {
        assertTrue("expected Ready but was $p", p is TextEditPlan.Ready)
        return p as TextEditPlan.Ready
    }

    @Test
    fun trimsCommonPrefixAndSuffix() {
        // 「bcde」→「bXde」：只把中间的 c 换成 X，两端不动。
        val p = ready(plan("abcdef", intArrayOf(6), 1, 5, "bXde"))
        assertEquals(1, p.inserted.size)
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(3, r.charEnd)
        assertEquals("X", r.text)
        // 清单两行存整段选区：原文「bcde」、新文字「bXde」，不受裁剪影响。
        assertEquals("bcde", r.originalText)
        assertEquals("bXde", r.editedText)
        assertEquals(0, r.delta)
        assertEquals(99L, r.groupId)
        assertEquals(1234L, r.createdAt)
        // 重新选中的是**整段替换后的文字**（选区起点不变，长度按输入文字算）。
        assertEquals(1, p.renderedStart)
        assertEquals(5, p.renderedEndExclusive)
    }

    @Test
    fun identicalReplacementIsNoop() {
        val p = ready(plan("abcdef", intArrayOf(6), 1, 5, "bcde"))
        assertTrue(p.noop)
        assertTrue(p.inserted.isEmpty())
        assertTrue(p.deletedIds.isEmpty())
    }

    @Test
    fun deletionProducesEmptyRevision() {
        val p = ready(plan("abcdef", intArrayOf(6), 1, 4, ""))
        val r = p.inserted.single()
        assertEquals(1, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("", r.text)
        // 纯删除：原文行留下被删掉的整段文字，改文行为空。
        assertEquals("bcd", r.originalText)
        assertEquals("", r.editedText)
        assertEquals(-3, r.delta)
    }

    @Test
    fun insertionKeepsSelectedTextAsOriginalLine() {
        // 选中「c」改成「cX」：最小修订裁成纯插入，但清单原文行仍是选中的「c」。
        val p = ready(plan("abcdef", intArrayOf(6), 2, 3, "cX"))
        val r = p.inserted.single()
        assertTrue(r.isInsertion)
        assertEquals("X", r.text)
        assertEquals("c", r.originalText)
        assertEquals("cX", r.editedText)
    }

    @Test
    fun reeditingSameSpotShowsPreEditSelection() {
        // 原书「abcdef」把 [2,4)「cd」换成「XY」→ 渲染「abXYef」；再把「X」改成「Z」。
        // 清单两行是这次提交前后读者看到的字：「X」→「Z」，不是书上原文「cd」。
        val old = rev(2, 4, "XY", id = 7).copy(originalText = "cd", editedText = "XY")
        val p = ready(plan("abXYef", intArrayOf(6), 2, 3, "Z", listOf(old)))
        val r = p.inserted.single()
        assertEquals("ZY", r.text)
        assertEquals("X", r.originalText)
        assertEquals("Z", r.editedText)
    }

    @Test
    fun editingInsideExistingRevisionAbsorbsAndRewritesIt() {
        // 原书「abcdef」把 [2,4)「cd」换成「XY」→ 渲染「abXYef」。
        // 选中渲染里的「X」改成「Z」：旧修订整体吸收，新修订仍是 [2,4) → 「ZY」。
        val p = ready(plan("abXYef", intArrayOf(6), 2, 3, "Z", listOf(rev(2, 4, "XY", id = 7))))
        assertEquals(listOf(7L), p.deletedIds)
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("ZY", r.text)
    }

    @Test
    fun replacementNextToExistingRevisionStaysIndependent() {
        // 旧修订 [2,4)→「XY」；选中它后面的「ef」改成「ZZ」：旧修订不被吸收。
        val p = ready(plan("abXYef", intArrayOf(6), 4, 6, "ZZ", listOf(rev(2, 4, "XY", id = 7))))
        assertTrue(p.deletedIds.isEmpty())
        val r = p.inserted.single()
        assertEquals(4, r.charStart)
        assertEquals(6, r.charEnd)
        assertEquals("ZZ", r.text)
    }

    @Test
    fun crossLeafSplitsIntoSegmentsSharingGroup() {
        // 两个叶「abc」「def」；选区 [2,5)「cde」→ 输入「C\nD」。
        val p = ready(plan("abcdef", intArrayOf(3, 6), 2, 5, "C\nD"))
        assertEquals(2, p.inserted.size)
        assertEquals(listOf(99L, 99L), p.inserted.map { it.groupId })
        assertEquals(2, p.inserted[0].charStart)
        assertEquals(3, p.inserted[0].charEnd)
        assertEquals("C", p.inserted[0].text)
        assertEquals(3, p.inserted[1].charStart)
        assertEquals(5, p.inserted[1].charEnd)
        assertEquals("D", p.inserted[1].text)
        // 清单两行按叶各存一份：第一叶「c」→「C」，第二叶「de」→「D」。
        assertEquals("c", p.inserted[0].originalText)
        assertEquals("C", p.inserted[0].editedText)
        assertEquals("de", p.inserted[1].originalText)
        assertEquals("D", p.inserted[1].editedText)
        assertEquals(2, p.renderedStart)
        assertEquals(4, p.renderedEndExclusive)
    }

    @Test
    fun fewerLinesThanLeavesMergesBlocks() {
        // 两段：「正文保留1 正文修改或删除」「正文修改或删除 正文保留2」。
        // 选区 = 第一段的后半 + 第二段的前半，输入为空 → 并成一段（段数由换行符决定）。
        val text = "正文保留1 正文修改或删除" + "正文修改或删除 正文保留2"
        val p = ready(plan(text, intArrayOf(13, 26), 6, 20, ""))
        val r = p.inserted.single()
        // 跨段改段数不裁公共前后缀：一条修订覆盖整个选区（两侧的字留在原段里）。
        assertEquals(6, r.charStart)
        assertEquals(20, r.charEnd)
        assertEquals("", r.text)
        assertEquals(-14, r.delta)
        assertEquals("正文修改或删除\n正文修改或删除", r.originalText)
        assertEquals("", r.editedText)
        assertEquals(6, p.renderedStart)
        assertEquals(6, p.renderedEndExclusive)
    }

    @Test
    fun moreLinesThanLeavesSplitsBlocks() {
        // 两段「abc」「def」；选区 [2,5)「cde」输入 3 行 → 3 段。
        val p = ready(plan("abcdef", intArrayOf(3, 6), 2, 5, "X\nY\nZ"))
        val r = p.inserted.single()
        assertEquals("X${RevisionBlocks.SEPARATOR}Y${RevisionBlocks.SEPARATOR}Z", r.text)
        assertEquals(3, RevisionBlocks.split(r.text).size)
        // 段界不占字符：渲染长度 = 3，与原区间等长 → delta 0。
        assertEquals(0, r.delta)
        assertEquals(2, p.renderedStart)
        assertEquals(5, p.renderedEndExclusive)
    }

    @Test
    fun twoLinesOverThreeLeavesCollapsesMiddle() {
        // 三段「abc」「def」「ghi」；选区 [2,7)「cdefg」输入 2 行 → 2 段。
        val p = ready(plan("abcdefghi", intArrayOf(3, 6, 9), 2, 7, "X\nY"))
        val r = p.inserted.single()
        assertEquals("X${RevisionBlocks.SEPARATOR}Y", r.text)
        assertEquals(-3, r.delta)
        assertEquals(4, p.renderedEndExclusive)
    }

    @Test
    fun crossLeafSelectionContainingBreakIsAllowed() {
        // 选区里的叶内换行（<br>）现在可以一起替换：段界与 <br> 已经分得开了。
        val p = ready(plan("ab\ncd", intArrayOf(3, 5), 1, 4, "X"))
        val r = p.inserted.single()
        assertEquals("X", r.text)
        assertEquals(-2, r.delta)
    }

    @Test
    fun unchangedCrossLeafLineIsSkipped() {
        // 两叶「abc」「def」；选区 [1,5)「bcde」→ 输入「bc\nX」：第一叶没变，只落第二叶。
        val p = ready(plan("abcdef", intArrayOf(3, 6), 1, 5, "bc\nX"))
        assertEquals(1, p.inserted.size)
        val r = p.inserted.single()
        assertEquals(3, r.charStart)
        assertEquals(5, r.charEnd)
        assertEquals("X", r.text)
        // 第一叶没变、不落修订；落下的第二叶清单原文仍是选中的「de」。
        assertEquals("de", r.originalText)
        assertEquals("X", r.editedText)
    }

    @Test
    fun singleLeafTypedLineBreakSplitsTheBlock() {
        // 叶内**新敲**的换行是段界：落成真块（拆段），不是 `<br>`。
        val p = ready(plan("abcdef", intArrayOf(6), 2, 4, "X\nY"))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("X${RevisionBlocks.SEPARATOR}Y", r.text)
        // 段界不占字：2 个字换 2 个字，delta 为 0。
        assertEquals(0, r.delta)
    }

    @Test
    fun singleLeafKeptLineBreakStaysBr() {
        // 原文里本来就有的 `\n`（`<br>`）对齐后仍是 `<br>`：选区盖住它、输入区原样留着。
        val p = ready(plan("ab\ncd", intArrayOf(5), 1, 4, "B\nC"))
        val r = p.inserted.single()
        assertEquals(1, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("B\nC", r.text)
        assertEquals(0, r.delta)
    }

    @Test
    fun emptySelectionIsRejected() {
        val p = plan("abcdef", intArrayOf(6), 3, 3, "X")
        assertTrue(p is TextEditPlan.Rejected)
    }

    @Test
    fun planInsertionAddsTextWithoutReplacingAnything() {
        // 纯插入（v3.1）：原书那一点一个字都不替换——审阅视图里改「一条纯插入的修订」走这条，
        // 因为只选中新添的文字时换算回原书空间是空区间，按替换提交会被当成「没选字」。
        val p = ready(
            TextEditPlanner.planInsertion(
                bookId = 1,
                chapter = 1,
                chapterText = "abcdef",
                at = 3,
                replacement = "XY",
                blockEnds = intArrayOf(6),
                groupId = 99,
                now = 1234,
            ),
        )
        val r = p.inserted.single()
        assertEquals(3, r.charStart)
        assertEquals(3, r.charEnd)
        assertEquals("XY", r.text)
        assertEquals(2, r.delta)
        // 清单两行：原文行是空的（没有字被替换掉），新文字行是输入的内容。
        assertEquals("", r.originalText)
        assertEquals("XY", r.editedText)
        assertEquals(3, p.renderedStart)
        assertEquals(5, p.renderedEndExclusive)
    }

    @Test
    fun planInsertionOfBlankTextIsNoop() {
        // 新文字为空 = 旧插入被撤掉：本身不落修订，但也不算「什么都没改」（调用方要删旧行）。
        val p = ready(
            TextEditPlanner.planInsertion(
                bookId = 1,
                chapter = 1,
                chapterText = "abcdef",
                at = 3,
                replacement = "",
                blockEnds = intArrayOf(6),
                groupId = 99,
                now = 1234,
            ),
        )
        assertTrue(p.inserted.isEmpty())
        assertTrue(p.noop)
    }

    @Test
    fun outOfRangeIsRejected() {
        val p = plan("abcdef", intArrayOf(6), 2, 99, "X")
        assertTrue(p is TextEditPlan.Rejected)
    }

    @Test
    fun normalWhitespaceIsCollapsedBeforeStorage() {
        // 普通模式里连打两个空格只会留下一个：存进修订的文字与实测长度一致。
        val p = ready(plan("abcdef", intArrayOf(6), 1, 5, "b  Xde"))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(3, r.charEnd)
        // 连打的空格折成一个，但它是新加的字，所以留在修订里（结果「ab Xdef」）。
        assertEquals(" X", r.text)
        assertEquals(1, r.delta)
        // 重新选中整段新文字（归一后是 5 个字「b Xde」）。
        assertEquals(1, p.renderedStart)
        assertEquals(6, p.renderedEndExclusive)
    }

    @Test
    fun leafBoundarySpacesAreDropped() {
        // 整叶换字：两端空格在叶首尾会被 CSS 丢弃，存进去的修订也不该带着它们。
        val p = ready(plan("abcdef", intArrayOf(6), 0, 6, "  abcdef  "))
        assertTrue(p.noop)
        assertTrue(p.inserted.isEmpty())
    }

    @Test
    fun preformattedLeafKeepsSpaces() {
        val p = ready(
            TextEditPlanner.plan(
                bookId = 1,
                chapter = 1,
                chapterText = "abcdef",
                blockEnds = intArrayOf(6),
                start = 1,
                endExclusive = 5,
                replacement = "X  Y",
                map = ChapterRevisionMap.EMPTY,
                groupId = 99,
                now = 1234,
                wsOfLeaf = { orilumn.reader.engine.css.WhiteSpace.PRE },
            ),
        )
        val r = p.inserted.single()
        assertEquals(1, r.charStart)
        assertEquals(5, r.charEnd)
        assertEquals("X  Y", r.text)
        assertEquals(0, r.delta)
    }

    @Test
    fun lineBreakInReplacementSurvivesNormalization() {
        // 叶内新敲的 `\n` 是段界：空白归一不能把它吃掉，但它也不占字（`<br>` 才会占）。
        val p = ready(plan("abcdef", intArrayOf(6), 2, 4, "X  Y\nZ"))
        val r = p.inserted.single()
        assertEquals("X Y${RevisionBlocks.SEPARATOR}Z", r.text)
        assertEquals(2, r.charStart)
        assertEquals(4, r.charEnd)
    }
}
