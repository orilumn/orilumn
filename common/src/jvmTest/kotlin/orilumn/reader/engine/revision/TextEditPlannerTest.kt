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

    private fun planStyled(
        text: String,
        ends: IntArray?,
        start: Int,
        endExclusive: Int,
        replacement: String,
        styles: List<InlineMarkup.StyleSpan>,
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
        replacementStyles = styles,
    )

    private fun span(s: Int, e: Int, tag: String) =
        InlineMarkup.StyleSpan(s, e, InlineMarkup.StyleSig(tag))

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
    fun recordsTheWholeSelectionWhenTrimming() {
        // 整段「bcde」→「bXde」，最小修订只剩「X」；重开卡片的选区要回到读者拖的整段，
        // 所以提交时把整段（原书空间）一并记下，不能靠两行快照去反推。
        val p = ready(plan("abcdef", intArrayOf(6), 1, 5, "bXde"))
        assertEquals(1 until 5, p.inserted.single().editRange)
    }

    @Test
    fun noWholeRangeWhenNothingIsTrimmed() {
        // 两端都被改到，整段就是最小修订：不用重复记。
        val p = ready(plan("abcdef", intArrayOf(6), 2, 3, "Y"))
        assertEquals(null, p.inserted.single().editRange)
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
    fun trimmedAppendReconfirmsWithoutDuplicatingKeptText() {
        // 库里已落成「在 3 处插『系』」；按整段（原书「关」）原样再提交「关系」，还是裁回同一条插入，
        // 不会变成「关关系」（打开旧修订直接按确定曾会这样）。
        val p = ready(plan("ab关cd", intArrayOf(5), 2, 3, "关系"))
        val r = p.inserted.single()
        assertTrue(r.isInsertion)
        assertEquals(3, r.charStart)
        assertEquals("系", r.text)
        assertEquals("关系", r.editedText)
    }

    @Test
    fun deletingKeptOriginalOfTrimmedAppendReplacesIt() {
        // 同上，但读者把卡片里保留的原文「关」删掉、只剩「系」：应把原书「关」换成「系」，
        // 不能因为改动落在最小修订之外就当成空操作。
        val p = ready(plan("ab关cd", intArrayOf(5), 2, 3, "系"))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(3, r.charEnd)
        assertEquals("系", r.text)
        assertEquals("关", r.originalText)
        assertEquals("系", r.editedText)
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
    fun selectionCoveringRevisionAndOtherContentAbsorbsIntoOneRecord() {
        // 旧修订 [2,4)「cd」→「XY」；这次选中渲染里的「XYef」改成「ZZ」：选区还带着别的内容，
        // 保留新记录并把旧记录吸收进去，最终只有一条 [2,6) → 「ZZ」。
        val p = ready(plan("abXYef", intArrayOf(6), 2, 6, "ZZ", listOf(rev(2, 4, "XY", id = 7))))
        assertEquals(listOf(7L), p.deletedIds)
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(6, r.charEnd)
        assertEquals("ZZ", r.text)
        assertEquals("XYef", r.originalText)
        assertEquals("ZZ", r.editedText)
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

    @Test
    fun stylesRideAlongWithTrimmedRevision() {
        // 「abcdef」→「ab*X*ef」：标记只圈住新写的 X，裁剪后区间要跟着平移。
        val p = ready(planStyled("abcdef", intArrayOf(6), 0, 6, "abXef", listOf(span(2, 3, "em"))))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("X", r.text)
        val stored = r.styles
        assertEquals(1, stored?.size)
        assertEquals(0 to 1, stored!!.single().start to stored.single().endExclusive)
        assertEquals("em", stored.single().style.tag)
    }

    @Test
    fun clippedSpanIsDroppedWhenItsCharsAreTrimmedAway() {
        // 标记圈住的是与原文相同的字（会被当成公共前后缀裁掉）：区间跟着消失，不留空修订片段。
        val p = ready(planStyled("abcdef", intArrayOf(6), 0, 6, "abXef", listOf(span(0, 2, "em"))))
        assertEquals(null, p.inserted.single().styles?.firstOrNull())
    }

    @Test
    fun styleOnlyChangeStillWritesARevision() {
        // 一个字都没改、只加了粗：文字不变也要落一条（否则「选中加粗」会被当成没改）。
        val p = ready(planStyled("abcdef", intArrayOf(6), 1, 4, "bcd", listOf(span(0, 3, "strong"))))
        val r = p.inserted.single()
        assertEquals(1, r.charStart)
        assertEquals(4, r.charEnd)
        assertEquals("bcd", r.text)
        assertEquals(0, r.delta)
        assertEquals("strong", r.styles?.single()?.style?.tag)
    }

    @Test
    fun crossParagraphStylesLandOnTheirOwnLine() {
        // 两段各改一个字，标记只圈第一段：第二段的修订不带样式，第一段带。
        val text = "甲乙丙丁"
        val ends = intArrayOf(2, 4)
        val p = ready(
            planStyled(text, ends, 0, 4, "甲X\n丙Y", listOf(span(0, 2, "em"))),
        )
        assertEquals(2, p.inserted.size)
        val first = p.inserted[0]
        val second = p.inserted[1]
        assertEquals("X", first.text)
        assertEquals("em", first.styles?.single()?.style?.tag)
        assertEquals(0 to 1, first.styles!!.single().start to first.styles.single().endExclusive)
        assertEquals("Y", second.text)
        assertEquals(null, second.styles?.firstOrNull())
    }

    @Test
    fun editingUpToSeamSpaceAbsorbsItWhenItWouldBecomeVisible() {
        // 书里 `使用 <code>val</code> 的只读变量`：`val` 与 `的` 之间那个空格按中西接缝吃成零宽。
        // 把「使用 val」改成「用 val 声明」后，它夹在「明」与「的」之间会现形 ⇒ 并进替换区间。
        val text = "使用 val 的只读变量"
        val p = ready(plan(text, intArrayOf(text.length), 0, 6, "用 val 声明"))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        // 6 个字 + 被吃掉的那个空格：落库的区间确实把它删掉了。
        assertEquals(7, r.charEnd)
        assertEquals("用 val 声明", r.text)
        assertEquals("使用 val ", r.originalText)
        assertEquals(1, r.delta)
        assertEquals(0, p.renderedStart)
        assertEquals(8, p.renderedEndExclusive)
    }

    @Test
    fun seamSpaceStaysWhenNewSeamStillEatsIt() {
        // 「使用 val」→「使用 values」：新接缝仍是「s|的」（西文贴中文），那个空格照样零宽，
        // 一个字节都不该动（否则平白多一条「删空格」修订）。
        val text = "使用 val 的只读变量"
        val p = ready(plan(text, intArrayOf(text.length), 0, 6, "使用 values"))
        val r = p.inserted.single()
        assertEquals(6, r.charStart)
        assertEquals(6, r.charEnd)
        assertEquals("ues", r.text)
        // 重新选中的是「使用 values」这 9 个字，空格仍在区间外。
        assertEquals(9, p.renderedEndExclusive)
    }

    @Test
    fun seamSpacesOnBothSidesAreAbsorbed() {
        // 选中中间的「val」改成「值」：两侧各有一个被吃掉的接缝空格，改完都会现形 ⇒ 一起吃掉。
        val text = "使用 val 的只读变量"
        val p = ready(plan(text, intArrayOf(text.length), 3, 6, "值"))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(7, r.charEnd)
        assertEquals("值", r.text)
        // 结果就是「使用值的只读变量」：两侧都不再夹空格（重新选中的只有新写的「值」）。
        assertEquals(1, p.renderedEndExclusive - p.renderedStart)
    }

    @Test
    fun styleOnlyEditDoesNotTouchSeamSpace() {
        // 只给「val」加代码样式、一个字没改：不写「删空格」修订，也不凭空多出一条。
        val text = "使用 val 的只读变量"
        val p = ready(planStyled(text, intArrayOf(text.length), 0, 6, "使用 val", listOf(span(3, 6, "code"))))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(6, r.charEnd)
        assertEquals("使用 val", r.text)
        assertEquals("code", r.styles?.single()?.style?.tag)
    }

    @Test
    fun codeSpanAfterALeadingSpaceStaysOnItsOwnChars() {
        // 真机复报：列表项「函数体写在大括号内 {}」把 `{}` 挪到「内」前面，输入区是
        // 「 `{}`内」（段首空格 + 代码 + 内）。段首空格前面是段内的「号」，不是叶边界，
        // 归一不会吃掉它；样式区间必须照旧圈住「{}」，不能整体前移一格盖住空格与 `{`。
        val text = "函数体写在大括号内 {}"
        val from = text.indexOf("内")
        val p = ready(
            planStyled(
                text, intArrayOf(text.length), from, text.length, " {}内",
                listOf(span(1, 3, "code")),
            ),
        )
        val r = p.inserted.single()
        assertEquals(" {}内", r.text)
        val rendered = r.styles?.single()
        assertEquals(1 to 3, rendered!!.start to rendered.endExclusive)
        // 卡片回显的那一份按输入区原文（含段首空格）记，重开卡才不会把标记挪位。
        val card = r.editedStyles?.single()
        assertEquals(1 to 3, card!!.start to card.endExclusive)
    }

    @Test
    fun ordinarySpaceNextToSelectionIsLeftAlone() {
        // 西文之间的普通空格不是接缝空白（本来就看得见），不归本规则管。
        val text = "abc def ghi"
        val p = ready(plan(text, intArrayOf(text.length), 4, 7, "xyz"))
        val r = p.inserted.single()
        assertEquals(4, r.charStart)
        assertEquals(7, r.charEnd)
        assertEquals("xyz", r.text)
    }

    @Test
    fun selectionCoveringAnImageSlotIsAllowed() {
        // 行内图片在正文里是一个 U+FFFC 占位：读者选中它一起删，图就跟着删（重放侧按占位去留决定，
        // 见 LeafTextEditorTest）。以前这里整次拒绝，读者只能干看着「这里不能改」。
        val p = plan("前\uFFFC后", intArrayOf(3), 0, 3, "改")
        val r = ready(p).inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(3, r.charEnd)
        assertEquals("改", r.text)
    }

    @Test
    fun selectionAfterAnImageSlotIsStillEditable() {
        // 只碰到图片**之外**的字照常能改。
        val p = plan("前\uFFFC后", intArrayOf(3), 2, 3, "尾")
        assertTrue("expected Ready but was $p", p is TextEditPlan.Ready)
    }

    @Test
    fun selectionAcrossAWholeImageBlockIsRejected() {
        // 整块图片（`img` 单独成叶）属于块结构，选区跨过去仍是拒绝。
        val text = "前\uFFFC后"
        val p = TextEditPlanner.plan(
            bookId = 1,
            chapter = 2,
            chapterText = text,
            blockEnds = intArrayOf(1, 2, 3),
            start = 0,
            endExclusive = 3,
            replacement = "改",
            map = ChapterRevisionMap.EMPTY,
            groupId = 1L,
            mergeableLeaf = { it != 1 },
        )
        assertTrue("expected Rejected but was $p", p is TextEditPlan.Rejected)
    }

    // ---------------------------------------------------------------- 块级形态

    private fun planBlocks(
        text: String,
        ends: IntArray?,
        start: Int,
        endExclusive: Int,
        blocks: List<InlineMarkup.Segment>,
        current: (Int) -> BlockKind? = { null },
    ): TextEditPlan = TextEditPlanner.plan(
        bookId = 1,
        chapter = 1,
        chapterText = text,
        blockEnds = ends,
        start = start,
        endExclusive = endExclusive,
        replacement = InlineMarkup.joinSegments(blocks),
        map = ChapterRevisionMap.EMPTY,
        groupId = 99,
        now = 1234,
        blocks = blocks,
        currentKindOf = current,
    )

    @Test
    fun cardBlockMarkerBecomesItsOwnRevision() {
        // 卡片第一行写了 `## `：文字一个字没改，也要落一条修订把这一段改成二级标题。
        val r = ready(
            planBlocks(
                text = "标题",
                ends = intArrayOf(2),
                start = 0,
                endExclusive = 2,
                blocks = listOf(InlineMarkup.Segment("标题", BlockKind.Heading2)),
                current = { BlockKind.Paragraph },
            ),
        ).inserted.single()
        assertEquals("标题", r.text)
        assertEquals(listOf("h2"), r.blockKinds)
        // 卡片那一行带的是**绝对**形态：重开卡片还要看见 `## `。
        assertEquals(listOf("h2"), r.editedBlocks)
    }

    @Test
    fun sameBlockKindWritesNothing() {
        // 目标形态与现在相同：卡片每行都带形态，不能因此白落一条「什么也没改」的修订。
        val p = planBlocks(
            text = "正文",
            ends = intArrayOf(2),
            start = 0,
            endExclusive = 2,
            blocks = listOf(InlineMarkup.Segment("正文", BlockKind.Paragraph)),
            current = { BlockKind.Paragraph },
        )
        assertTrue("expected noop but was $p", (p as TextEditPlan.Ready).noop)
    }

    @Test
    fun removingTheMarkerTurnsHeadingBackIntoParagraph() {
        val r = ready(
            planBlocks(
                text = "标题",
                ends = intArrayOf(2),
                start = 0,
                endExclusive = 2,
                blocks = listOf(InlineMarkup.Segment("标题", BlockKind.Paragraph)),
                current = { BlockKind.Heading3 },
            ),
        ).inserted.single()
        assertEquals(listOf("p"), r.blockKinds)
        assertEquals("标题", r.text)
    }

    @Test
    fun mergeKeepsPerLineBlockKinds() {
        // 两段并成两段（文字变了、段数没变）：形态按行各带一份。
        val r = ready(
            planBlocks(
                text = "abcdef",
                ends = intArrayOf(3, 6),
                start = 0,
                endExclusive = 6,
                blocks = listOf(
                    InlineMarkup.Segment("aXc", BlockKind.Quote),
                    InlineMarkup.Segment("dYf", BlockKind.Bullet),
                ),
                current = { BlockKind.Paragraph },
            ),
        )
        assertEquals(2, r.inserted.size)
        assertEquals(listOf("quote"), r.inserted[0].blockKinds)
        assertEquals(listOf("ul"), r.inserted[1].blockKinds)
        assertEquals(listOf("quote"), r.inserted[0].editedBlocks)
    }

    @Test
    fun splitIntoMoreBlocksCarriesKindsPerLine() {
        val r = ready(
            planBlocks(
                text = "abcdef",
                ends = intArrayOf(6),
                start = 0,
                endExclusive = 6,
                blocks = listOf(
                    InlineMarkup.Segment("ab", BlockKind.Heading1),
                    InlineMarkup.Segment("cd", BlockKind.Quote),
                    InlineMarkup.Segment("ef", BlockKind.Bullet),
                ),
                current = { BlockKind.Paragraph },
            ),
        ).inserted.single()
        assertEquals(listOf("h1", "quote", "ul"), r.blockKinds)
        assertEquals(
            listOf("h1", "quote", "ul"),
            r.editedBlocks,
        )
        assertEquals("ab" + RevisionBlocks.SEPARATOR + "cd" + RevisionBlocks.SEPARATOR + "ef", r.text)
    }

    // ------------------------------------------------ 整块图片 / 表格行（特殊叶）

    private fun tableOf(vararg lengths: Int, colspan: Int = 1): TableRowInfo {
        var at = 0
        return TableRowInfo(
            lengths.mapIndexed { i, len ->
                TableCellInfo(at, at + len, header = false, colspan = if (i == 0) colspan else 1)
                    .also { at += len }
            },
        )
    }

    private fun planShapes(
        text: String,
        ends: IntArray,
        start: Int,
        endExclusive: Int,
        blocks: List<InlineMarkup.Segment>,
        imageLeaf: (Int) -> Boolean = { false },
        tableLeaf: (Int) -> TableRowInfo? = { null },
    ): TextEditPlan = TextEditPlanner.plan(
        bookId = 1,
        chapter = 1,
        chapterText = text,
        blockEnds = ends,
        start = start,
        endExclusive = endExclusive,
        replacement = InlineMarkup.joinSegments(blocks),
        map = ChapterRevisionMap.EMPTY,
        groupId = 99,
        now = 1234,
        blocks = blocks,
        imageLeaf = imageLeaf,
        tableOfLeaf = tableLeaf,
    )

    @Test
    fun deletingAWholeBlockImageIsAllowed() {
        // 章文字「▯abc」：第一叶是整块图片（只有一个占位符），读者把占位删掉 = 整张删除。
        val p = ready(
            planShapes(
                text = "\uFFFCabc",
                ends = intArrayOf(1, 4),
                start = 0,
                endExclusive = 1,
                blocks = listOf(InlineMarkup.Segment("")),
                imageLeaf = { it == 0 },
            ),
        )
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(1, r.charEnd)
        assertEquals("", r.text)
    }

    @Test
    fun turningAnImageIntoTextIsRejected() {
        // 图片不能被「改写成文字」：那等于把一张图悄悄换成正文。
        val p = planShapes(
            text = "\uFFFCabc",
            ends = intArrayOf(1, 4),
            start = 0,
            endExclusive = 1,
            blocks = listOf(InlineMarkup.Segment("图")),
            imageLeaf = { it == 0 },
        )
        assertTrue("expected Rejected but was $p", p is TextEditPlan.Rejected)
    }

    @Test
    fun editingTextInsideACellKeepsTheTableShape() {
        // 「abcd」是两格「ab」「cd」；读者把 b 改成 X：格数、合并方式都没变，允许。
        val original = tableOf(2, 2)
        val edited = TableRowInfo(
            listOf(TableCellInfo(0, 2, header = false), TableCellInfo(2, 4, header = false)),
        )
        val p = ready(
            planShapes(
                text = "abcd",
                ends = intArrayOf(4),
                start = 0,
                endExclusive = 4,
                blocks = listOf(InlineMarkup.Segment("aXcd", null, edited)),
                tableLeaf = { original },
            ),
        )
        val r = p.inserted.single()
        assertEquals(1, r.charStart)
        assertEquals(2, r.charEnd)
        assertEquals("X", r.text)
        val shape = TableRowInfo.fromWire(r.editedBlocks.single())
        assertEquals(2, shape!!.cells.size)
        assertEquals(2, shape.cells[1].start)
    }

    @Test
    fun addingAColumnToTheTableIsRejected() {
        val p = planShapes(
            text = "abcd",
            ends = intArrayOf(4),
            start = 0,
            endExclusive = 4,
            blocks = listOf(InlineMarkup.Segment("aXcd", null, tableOf(2, 2))),
            tableLeaf = { tableOf(2, 2) },
        )
        // 格数一样、合并方式不一样（第一格 colspan 2）：不是这张卡能做的事。
        val changed = planShapes(
            text = "abcd",
            ends = intArrayOf(4),
            start = 0,
            endExclusive = 4,
            blocks = listOf(InlineMarkup.Segment("aXc", null, tableOf(2, 1, colspan = 2))),
            tableLeaf = { tableOf(2, 2) },
        )
        assertTrue("格数不变也要拒绝合并方式的改动", changed is TextEditPlan.Rejected)
        val fewer = planShapes(
            text = "abcd",
            ends = intArrayOf(4),
            start = 0,
            endExclusive = 4,
            blocks = listOf(InlineMarkup.Segment("abcd", null, tableOf(4))),
            tableLeaf = { tableOf(2, 2) },
        )
        assertTrue("改列数要拒绝", fewer is TextEditPlan.Rejected)
        assertTrue("原样改字不被拒绝", p is TextEditPlan.Ready)
    }
}
