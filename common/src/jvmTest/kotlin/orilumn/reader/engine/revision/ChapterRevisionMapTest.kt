package orilumn.reader.engine.revision

import orilumn.reader.data.revision.RevisionStyle
import orilumn.reader.data.revision.RevisionStyleSpan
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

    // ─────────────────────────────────────────────────────────────────
    // 审阅空间：原文照排 + 改动就地插入（ReviewOverlay）
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
    fun reviewSpaceKeepsOriginalAndInsertsNewTextInPlace() {
        // 原书「甲乙丙丁戊」，把 [1,3)「乙丙」换成「XY」：
        // 审阅空间 = 甲 乙 丙 X Y 丁 戊（原文一个字都不删，新写的字就地插在原文之后）。
        val r = rev(1, 3, "XY")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "乙丙"))
        assertEquals(0, map.toReview(0))
        assertEquals(1, map.toReview(1))
        // 区间终点（不越过该点插入）停在原文之后、新文字之前。
        assertEquals(3, map.toReview(3, crossInsertions = false))
        // 单点默认越过该点插入（= 新写的字算在这个边界之前）。
        assertEquals(5, map.toReview(3))
        assertEquals(6, map.toReview(4))
        assertEquals(7, map.toReview(5, crossInsertions = false))

        // 红绿：原文「乙丙」标红，就地插进去的「XY」标绿；整段 = 原文 + 插入。
        assertEquals(listOf(1 until 3), pairs(map.paintedDeleted[r]))
        assertEquals(listOf(3 until 5), pairs(map.paintedInserted[r]))
        assertEquals(1 until 5, map.reviewInterval(r))

        // 审阅 → 原书：落进新写的字里就夹回插入点（原区间末尾）。
        assertEquals(0, map.toOriginalFromReview(0))
        assertEquals(1, map.toOriginalFromReview(1))
        assertEquals(3, map.toOriginalFromReview(3))
        assertEquals(3, map.toOriginalFromReview(5))
        assertEquals(4, map.toOriginalFromReview(6))
    }

    @Test
    fun reviewSpacePureInsertionStaysAtThePoint() {
        // 位置 2 插入「XY」：审阅空间 = 甲乙XY丙…（原文不删，新写的字就地插在这一点）。
        val r = rev(2, 2, "XY")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to ""))
        assertEquals(2 until 4, map.reviewInterval(r))
        assertTrue(map.paintedDeleted.isEmpty())
        assertEquals(listOf(2 until 4), pairs(map.paintedInserted[r]))
        assertEquals(2, map.toReview(2, crossInsertions = false))
        assertEquals(4, map.toReview(2))
        assertEquals(5, map.toReview(3))
        // 落在新写的两个字上时夹回插入点；之后照旧是原文（丙 = 原书 2、丁 = 原书 3）。
        assertEquals(2, map.toOriginalFromReview(2))
        assertEquals(2, map.toOriginalFromReview(3))
        assertEquals(2, map.toOriginalFromReview(4))
        assertEquals(3, map.toOriginalFromReview(5))
    }

    @Test
    fun reviewSpaceKeepsRetainedTextOnlyOnce() {
        // 用户的原例：把「略微一攥，」改成「¶略微一攥，¶然后」——原文那五个字一个都没删，
        // 只在它前面插一个段界、后面插一个段界加「然后」。
        // 审阅空间 = 「取出一只玉佩，¶略微一攥，¶然后这玉佩缓缓融化」：原句只出现一次。
        val r = rev(7, 12, "${RevisionBlocks.SEPARATOR}略微一攥，${RevisionBlocks.SEPARATOR}然后")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "略微一攥，"))
        // 一个红字都没有：原文本就在，不该标红。
        assertTrue(map.paintedDeleted.isEmpty())
        // 只有两处 ¶ 与「然后」是绿字（第二个 ¶ 与「然后」挨着）。
        assertEquals(listOf(7 until 8, 13 until 16), pairs(map.paintedInserted[r]))
        // 整段 = 原书那 5 个字 + 就地插进去的 4 格（¶ ¶ 然后）。
        assertEquals(7 until 16, map.reviewInterval(r))
        // 取原书文字时把这些痕迹剪掉，还是原来那一段。
        assertEquals("取出一只玉佩，略微一攥，这玉佩缓缓融化", map.originalTextFromReview(
            "取出一只玉佩，¶略微一攥，¶然后这玉佩缓缓融化",
        ))
        // 段界要拆块：这一段在重放里得走整叶重塑。
        assertTrue(map.overlay(r)!!.needsBlocks)
    }

    @Test
    fun reviewRangeCoversOriginalPlusInnerInsertions() {
        val r = rev(5, 6, "ZZZ")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "己"))
        // 区间起点越过该处插入、终点不越过：整段原文 + 内嵌新文字。
        val s = map.toReview(2, crossInsertions = true)
        val e = map.toReview(8, crossInsertions = false)
        assertEquals(2 until 11, s until e)
        // 正好等于该修订的区间：原书那个字 + 就地插入的三个字。
        assertEquals(5 until 9, map.reviewInterval(r))
    }

    @Test
    fun reviewSelectionMapsBackToOriginalRange() {
        // 审阅「甲乙丙XY丁戊」：选原文「乙丙」→ 原书 [1,3)。
        val r = rev(1, 3, "XY")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "乙丙"))
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 3))
        // 只选中新写的「XY」（审阅 [3,5)）→ 抬起那条修订的原区间 [1,3)，否则等于原地加字。
        assertEquals(1 until 3, map.originalEditRangeFromReview(3, 5))
        // 跨过删掉的原文与新添的文字整段 → 同样是 [1,3)。
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 5))
        // 没碰到修订：原样换算。
        assertEquals(0 until 1, map.originalEditRangeFromReview(0, 1))
    }

    @Test
    fun reviewSelectionOfPureInsertionKeepsPoint() {
        val r = rev(2, 2, "XY")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to ""))
        assertTrue(map.originalEditRangeFromReview(2, 4).isEmpty())
        assertEquals(1, map.revisionsTouchingReview(2, 4).size)
    }

    @Test
    fun originalTextFromReviewStripsInsertedText() {
        val r = rev(1, 3, "XY")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "乙丙"))
        assertEquals("甲乙丙丁戊", map.originalTextFromReview("甲乙丙XY丁戊"))
        val ins = rev(2, 2, "XY")
        val inserted = ChapterRevisionMap(listOf(ins), originalTexts = mapOf(ins to ""))
        assertEquals("甲乙丙丁", inserted.originalTextFromReview("甲乙XY丙丁"))
        assertEquals("甲乙", ChapterRevisionMap.EMPTY.originalTextFromReview("甲乙"))
    }

    @Test
    fun formatOnlyRevisionShowsDeletedMarker() {
        // 用户的原例：`## 你好世界` 删掉 `## ` 变回正文——字一个没改，只改了块标记。
        // 审阅空间 = 红 `## ` + 原文照排：读者看得见被删掉的标记，正文一个字都不红。
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = 4,
            text = "你好世界",
            blockKinds = listOf("p"),
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to "你好世界"),
            originalKinds = mapOf(r to listOf(BlockKind.Heading2)),
        )
        assertEquals(0 until 7, map.reviewInterval(r))
        // 三个痕迹字符各自登记（取文字时逐位跳过），不是段界痕迹。
        assertEquals(listOf(0, 1, 2), map.reviewMarkOffsets.toList())
        assertTrue(map.reviewDeletedBreakMarkers.isEmpty())
        assertTrue(map.reviewInsertedBreakMarkers.isEmpty())
        assertEquals(listOf(0 until 3), pairs(map.paintedDeleted[r]))
        assertTrue(map.paintedInserted.isEmpty())
        // 痕迹自己不对应原书的字：落在上面就夹回段首。
        assertEquals(0, map.toOriginalFromReview(0))
        assertEquals(0, map.toOriginalFromReview(3))
        assertEquals(1, map.toOriginalFromReview(4))
        // 取文字（摘录 / 复制 / 修改卡初值）要跳过痕迹。
        assertEquals("你好世界", map.originalTextFromReview("## 你好世界"))
        // 正常视图（渲染空间）不受影响：这一段还是那四个字。
        assertEquals(4, map.toRendered(4))
    }

    @Test
    fun addedHeadingMarkerIsGreen() {
        // 反方向：正文改成标题，绿 `## ` 排在段首。
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = 4,
            text = "你好世界",
            blockKinds = listOf("h2"),
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to "你好世界"),
            originalKinds = mapOf(r to listOf(BlockKind.Paragraph)),
        )
        assertEquals(0 until 7, map.reviewInterval(r))
        assertEquals(listOf(0, 1, 2), map.reviewMarkOffsets.toList())
        assertTrue(map.paintedDeleted.isEmpty())
        assertEquals(listOf(0 until 3), pairs(map.paintedInserted[r]))
    }

    @Test
    fun markerTraceNeedsOriginalKind() {
        // 取不到原书形态时宁可不画：没有痕迹，审阅空间与原文等长。
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = 4,
            text = "你好世界",
            blockKinds = listOf("p"),
        )
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "你好世界"))
        assertEquals(0 until 4, map.reviewInterval(r))
        assertTrue(map.reviewMarkOffsets.isEmpty())
        assertTrue(map.paintedDeleted.isEmpty())
    }

    @Test
    fun markerTraceSkippedWhenTextChanged() {
        // 改了字的修订不画标记痕迹（红绿已经在字上）。
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = 2,
            text = "丙丁",
            blockKinds = listOf("p"),
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to "甲乙"),
            originalKinds = mapOf(r to listOf(BlockKind.Heading2)),
        )
        assertTrue(map.reviewMarkOffsets.isEmpty())
        assertEquals(listOf(0 until 2), pairs(map.paintedDeleted[r]))
        assertEquals(listOf(2 until 4), pairs(map.paintedInserted[r]))
    }

    @Test
    fun markerTraceCarriedAcrossPages() {
        // 前一条修订在别处、这一条在段首：痕迹的位置要跟着审阅空间的位移走。
        val before = rev(0, 1, "XY", id = 1)
        val r = TextRevision(
            id = 2,
            bookId = 1,
            chapter = 1,
            charStart = 4,
            charEnd = 8,
            text = "你好世界",
            blockKinds = listOf("p"),
        )
        val map = ChapterRevisionMap(
            listOf(before, r),
            originalTexts = mapOf(before to "甲", r to "你好世界"),
            originalKinds = mapOf(r to listOf(BlockKind.Heading2)),
        )
        // 前一条修订多出两个字（「甲」→「XY」：原文留在原位，新字插在后面），痕迹整体后移两格。
        assertEquals(listOf(6, 7, 8), map.reviewMarkOffsets.toList())
        assertEquals(listOf(6 until 9), pairs(map.paintedDeleted[r]))
    }

    @Test
    fun formatOnlyItalicShowsGreenStars() {
        // 字一个没改，只给整段加上了斜体：审阅空间在段首段尾各多一个绿 `*`。
        val text = "函数是执行特定任务的一组指令。"
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = text.length,
            text = text,
            styles = listOf(RevisionStyleSpan(0, text.length, RevisionStyle("em"))),
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to text),
            originalSpans = mapOf(r to emptyList()),
        )
        assertEquals(0 until text.length + 2, map.reviewInterval(r))
        assertEquals(listOf(0, text.length + 1), map.reviewMarkOffsets.toList())
        assertEquals(
            listOf(0 until 1, text.length + 1 until text.length + 2),
            pairs(map.paintedInserted[r]),
        )
        assertTrue(map.paintedDeleted.isEmpty())
        // 取文字时两个 `*` 都要剪掉。
        assertEquals(text, map.originalTextFromReview("*$text*"))
    }

    @Test
    fun formatOnlyItalicRemovalShowsRedStars() {
        val text = "函数是执行特定任务的一组指令。"
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = text.length,
            text = text,
            styles = emptyList(),
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to text),
            originalSpans = mapOf(
                r to listOf(InlineMarkup.StyleSpan(0, text.length, InlineMarkup.StyleSig("em"))),
            ),
        )
        assertEquals(listOf(0, text.length + 1), map.reviewMarkOffsets.toList())
        assertEquals(
            listOf(0 until 1, text.length + 1 until text.length + 2),
            pairs(map.paintedDeleted[r]),
        )
    }

    @Test
    fun inlineTraceNeedsOriginalSpans() {
        // 原书行内结构取不到（表里没有这一条）：行内痕迹不画，审阅空间与原文等长。
        val text = "函数是执行特定任务的一组指令。"
        val r = TextRevision(
            id = 1,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = text.length,
            text = text,
            styles = listOf(RevisionStyleSpan(0, text.length, RevisionStyle("em"))),
        )
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to text))
        assertEquals(0 until text.length, map.reviewInterval(r))
        assertTrue(map.reviewMarkOffsets.isEmpty())
    }

    @Test
    fun deletedParagraphBreakLeavesRedMark() {
        // 原书两段「甲乙」「丙丁」，段界在原书偏移 2；删掉段间换行 ⇒ [0,4) 换回「甲乙丙丁」。
        val r = rev(0, 4, "甲乙丙丁", id = 1)
        val map = ChapterRevisionMap(
            listOf(r),
            originalBlockEnds = intArrayOf(2),
            originalTexts = mapOf(r to "甲乙丙丁"),
        )
        // 审阅空间 = 「甲乙¶丙丁」：¶ 占原段界那一格，红字 + 删除线（一个字都没删，没有别的红字）。
        assertEquals(listOf(2), map.reviewMarkOffsets.toList())
        assertEquals(listOf(2), map.reviewDeletedBreakMarkers.toList())
        assertTrue(map.reviewInsertedBreakMarkers.isEmpty())
        assertEquals(listOf(2 until 3), pairs(map.paintedDeleted[r]))
        assertTrue(map.paintedInserted.isEmpty())
        assertEquals(0 until 5, map.reviewInterval(r))
        // 「越过该点」= 越过 ¶；不越过就停在 ¶ 之前。
        assertEquals(2, map.toReview(2, crossInsertions = false))
        assertEquals(3, map.toReview(2))
        // ¶ 自己不对应原书的字，落在它上面就夹回段界。
        assertEquals(2, map.toOriginalFromReview(2))
        assertEquals(2, map.toOriginalFromReview(3))
        assertEquals(3, map.toOriginalFromReview(4))
        assertEquals(4, map.toOriginalFromReview(5))
        // 取原书文字时痕迹要剪掉（修改卡 / 复制 / 摘录都走这条）。
        assertEquals("甲乙丙丁", map.originalTextFromReview("甲乙¶丙丁"))
        // 正常视图（渲染空间）不受影响：一个字都没变，也不多痕迹。
        assertEquals(4, map.toRendered(4))
    }

    @Test
    fun mergingThreeParagraphsLeavesTwoMarks() {
        // 「甲乙」「丙丁」「戊己」两处段界（2、4）都删掉 ⇒ [0,6) 换回一整段。
        val r = rev(0, 6, "甲乙丙丁戊己", id = 1)
        val map = ChapterRevisionMap(
            listOf(r),
            originalBlockEnds = intArrayOf(2, 4),
            originalTexts = mapOf(r to "甲乙丙丁戊己"),
        )
        assertEquals(listOf(2, 5), map.reviewMarkOffsets.toList())
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
            // 段界不占字（提交侧按 renderedLength 算的就是 0）。
            delta = 0,
        )
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to ""))
        // 审阅空间 = 「甲乙¶丙丁」：绿 ¶ 顶在断行处上一行末尾，占一格。
        assertEquals(listOf(2), map.reviewMarkOffsets.toList())
        assertEquals(listOf(2), map.reviewInsertedBreakMarkers.toList())
        assertTrue(map.reviewDeletedBreakMarkers.isEmpty())
        assertEquals(listOf(2 until 3), pairs(map.paintedInserted[r]))
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
            originalBlockEnds = intArrayOf(2, 4),
            originalTexts = mapOf(r to "甲乙丙丁戊己"),
        )
        // 只有真被删掉的那一处留 ¶；留着的换行照旧分段，不留痕迹。
        assertEquals(listOf(2), map.reviewMarkOffsets.toList())
        assertEquals(5, map.toReview(4))
        assertEquals(6, map.toReview(5))
        // 只删了一道段界 ⇒ 结果两段；段界要动，重放得整叶重塑。
        assertTrue(map.overlay(r)!!.needsBlocks)
    }

    @Test
    fun insertedParagraphBreakCountsOneGreenMark() {
        // 在 2 处插入「甲[段界]乙」：审阅空间 = 「甲¶乙」——段界排成真块边界（不占字符），
        // 断行处留一个绿 ¶（占一格），所以新写的两个字加一个痕迹共三格。
        val r = rev(2, 2, "甲${RevisionBlocks.SEPARATOR}乙")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to ""))
        assertEquals(2 until 5, map.reviewInterval(r))
        assertEquals(listOf(2 until 5), pairs(map.paintedInserted[r]))
        // 新写的三格都夹回插入点；副本之后紧挨着的原书字从 5 开始。
        assertEquals(2, map.toOriginalFromReview(2))
        assertEquals(2, map.toOriginalFromReview(4))
        assertEquals(2, map.toOriginalFromReview(5))
        assertEquals(5, map.toReview(2))
        // 剪掉新写的字又是原书那一串。
        assertEquals("甲乙", map.originalTextFromReview("甲乙甲¶乙"))
        // 痕迹也要能被「取文字」跳过（摘录 / 复制 / 修改卡初值）。
        assertEquals(listOf(3), map.reviewMarkOffsets.toList())
    }

    @Test
    fun structuralRevisionKeepsLaterReviewsInReviewSpace() {
        // [1,3) 只挪段界（无红绿），[3,4) 把「丁」换成「XY」：审阅空间 = 「甲乙丙丁XY戊」。
        val shape = rev(1, 3, "乙丙", id = 1, group = 1)
        val edit = rev(3, 4, "XY", id = 2, group = 2)
        val map = ChapterRevisionMap(
            listOf(shape, edit),
            originalBlockEnds = intArrayOf(3),
            originalTexts = mapOf(shape to "乙丙", edit to "丁"),
        )
        assertEquals(3, map.toReview(3, crossInsertions = false))
        assertEquals(6, map.toReview(4))
        assertEquals(3 until 6, map.reviewInterval(edit))
        assertEquals(4, map.toOriginalFromReview(5))
        assertEquals("甲乙丙丁戊", map.originalTextFromReview("甲乙丙丁XY戊"))
    }

    @Test
    fun adjacentRevisionsShareOneBoundary() {
        // 同一段里两处修改首尾相接（前一处结束的位置正好是后一处开始的位置）：
        // 审阅空间 = 「bcX」+「deY」，后一处的起点必须越过前一处的插入，否则红绿与命中都前移一格。
        val r1 = rev(1, 3, "X", id = 1)
        val r2 = rev(3, 5, "Y", id = 2)
        val map = ChapterRevisionMap(
            listOf(r1, r2),
            originalTexts = mapOf(r1 to "bc", r2 to "de"),
        )
        assertEquals(1 until 4, map.reviewInterval(r1))
        assertEquals(4 until 7, map.reviewInterval(r2))
        assertEquals(listOf(3 until 4), pairs(map.paintedInserted[r1]))
        assertEquals(listOf(6 until 7), pairs(map.paintedInserted[r2]))
        assertEquals("abcdef", map.originalTextFromReview("abcXdeYf"))
        // 后一处的选区仍能换回自己的原书区间，不会把前一处的插入算进去。
        assertEquals(3 until 5, map.originalEditRangeFromReview(4, 7))
        // 前一处的插入不算进「终点在这一处」的区间（§8.3）。
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 3))
    }

    @Test
    fun reviewSegmentIntervalCoversKeptOriginalForTrimmedAppend() {
        // 原书「关」改成「关系」：最小修订裁成在 3 处插「系」，快照两行仍是整段的「关」/「关系」。
        val r = rev(3, 3, "系", id = 1).copy(originalText = "关", editedText = "关系")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to ""))
        // 最小修订只盖绿色「系」；整段要连前面留着的原文「关」一起框住（点按命中也按它）。
        assertEquals(3 until 4, map.reviewInterval(r))
        assertEquals(2 until 4, map.reviewSegmentInterval(r))
        // 从整段换回原书区间，提交「系」才是「删掉原文的关」；只按最小修订换回去是空区间。
        assertEquals(2 until 3, map.originalEditRangeFromReview(2, 4))
        assertEquals(3 until 3, map.originalEditRangeFromReview(3, 4))
    }

    @Test
    fun reviewSegmentIntervalCoversKeptPrefixOfTrimmedReplacement() {
        // 原书「关系了」把 [1,2)「系」改成「联」：最小修订是「系」→「联」，快照整段是「关系」/「关联」。
        val r = rev(1, 2, "联", id = 1).copy(originalText = "关系", editedText = "关联")
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "系"))
        // 审阅空间 = 原书「关系了」+ 就地插「联」=「关系联了」，最小区间只是红「系」+ 绿「联」。
        assertEquals(1 until 3, map.reviewInterval(r))
        assertEquals(0 until 3, map.reviewSegmentInterval(r))
        assertEquals(0 until 2, map.originalEditRangeFromReview(0, 3))
    }

    @Test
    fun reviewSegmentIntervalFallsBackWhenKeptTextTouchesAnotherRevision() {
        // A 把「关」改成「关系」，B 又把同一处「关」改成「开」：A 的整段反推会盖住 B，
        // 只能退回最小修订，免得点开 A 的卡片把 B 也一起删掉。
        val a = rev(3, 3, "系", id = 1).copy(originalText = "关", editedText = "关系")
        val b = rev(2, 3, "开", id = 2).copy(originalText = "关", editedText = "开")
        val map = ChapterRevisionMap(listOf(a, b), originalTexts = mapOf(a to "", b to "关"))
        assertEquals(map.reviewInterval(a), map.reviewSegmentInterval(a))
        assertEquals(map.reviewInterval(b), map.reviewSegmentInterval(b))
    }

    @Test
    fun reviewSegmentIntervalWithoutSnapshotsStaysMinimal() {
        // 老数据没有 originalText / editedText：反推不出整段，行为和以前一样（最小修订）。
        val r = rev(1, 2, "X", id = 1)
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "b"))
        assertEquals(map.reviewInterval(r), map.reviewSegmentInterval(r))
    }

    @Test
    fun reviewIntervalAcceptsEqualInstanceFromAnotherRead() {
        // 宿主点按命中 / 打开修改卡拿到的修订是**另一次读库**的实例（值相等、引用不同）：
        // 起点必须按「排在它前面的修订」累加，不能因为认不出自己就把后面的插入也算进来，
        // 否则整段右移——点按落空、选区框错字。
        val r1 = rev(1, 3, "X", id = 1)
        val r2 = rev(5, 7, "Y", id = 2)
        val map = ChapterRevisionMap(
            listOf(r1, r2),
            originalTexts = mapOf(r1 to "bc", r2 to "fg"),
        )
        assertEquals(map.reviewInterval(r1), map.reviewInterval(r1.copy()))
        assertEquals(map.reviewInterval(r2), map.reviewInterval(r2.copy()))
        // 首条不欠任何前序插入；后一条只欠首条那一段的差（原文 2 字 + 新写 1 字 = +1）。
        assertEquals(1 until 4, map.reviewInterval(r1.copy()))
        assertEquals(6 until 9, map.reviewInterval(r2.copy()))
    }

    @Test
    fun samePointInsertionsStackInReviewSpace() {
        // 同一处两次加字（提交侧通常会合并，表侧也要按先后排好）：审阅空间 = 「abABcd」。
        val first = rev(2, 2, "A", id = 1)
        val second = rev(2, 2, "B", id = 2)
        val map = ChapterRevisionMap(
            listOf(first, second),
            originalTexts = mapOf(first to "", second to ""),
        )
        assertEquals(2 until 3, map.reviewInterval(first))
        assertEquals(3 until 4, map.reviewInterval(second))
        assertEquals(listOf(2 until 3), pairs(map.paintedInserted[first]))
        assertEquals(listOf(3 until 4), pairs(map.paintedInserted[second]))
        assertEquals("abcd", map.originalTextFromReview("abABcd"))
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

    @Test
    fun paintedSpansKeepUntouchedTextPlain() {
        // 原书「甲乙略微一攥，然后」，读者把 [2,7)「略微一攥，」改成「¶略微一攥，¶然后」：
        // 原文那五个字一个都没删，只是前后各插了一个段界、末尾加了「然后」。
        val r = rev(2, 7, "${RevisionBlocks.SEPARATOR}略微一攥，${RevisionBlocks.SEPARATOR}然后", id = 1)
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "略微一攥，"))
        // 一个红字都没有：原文里本来就有的字不该标红。
        assertTrue(map.paintedDeleted.isEmpty())
        // 只有两个 ¶ 与「然后」是绿字，中间那五个字是原样文字（原句只出现一次）。
        assertEquals(listOf(2 until 3, 8 until 11), pairs(map.paintedInserted[r]))
        // 交互口径不动：整段（原文 + 就地插入）仍然属于同一条修订。
        assertEquals(2 until 11, map.reviewInterval(r))
    }

    @Test
    fun paintedSpansMarkOnlyChangedChars() {
        // 「甲乙丙丁」把 [1,4)「乙丙丁」改成「乙X丁」：只有「丙」被删、只有「X」是新写的。
        val r = rev(1, 4, "乙X丁", id = 1)
        val map = ChapterRevisionMap(listOf(r), originalTexts = mapOf(r to "乙丙丁"))
        assertEquals(listOf(2 until 3), pairs(map.paintedDeleted[r]))
        // 审阅空间「甲乙丙X丁」：新写的只有中间那格（原书的「丁」照旧在它后面）。
        assertEquals(listOf(3 until 4), pairs(map.paintedInserted[r]))
    }

    @Test
    fun paintedSpansForPlainReplacementAndDeletion() {
        val replace = rev(1, 3, "X", id = 1)
        val map = ChapterRevisionMap(listOf(replace), originalTexts = mapOf(replace to "乙丙"))
        assertEquals(listOf(1 until 3), pairs(map.paintedDeleted[replace]))
        assertEquals(listOf(3 until 4), pairs(map.paintedInserted[replace]))

        val deletion = rev(1, 3, "", id = 1)
        val del = ChapterRevisionMap(listOf(deletion), originalTexts = mapOf(deletion to "乙丙"))
        assertEquals(listOf(1 until 3), pairs(del.paintedDeleted[deletion]))
        assertTrue(del.paintedInserted.isEmpty())
    }

    @Test
    fun structuralRevisionTakesResultLengthInReviewSpace() {
        // 整表结构修订（删掉两行）：审阅空间不展开「原文 + 插入」，直接按结果占长度。
        val r = rev(4, 12, "甲乙", id = 7)
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to "甲乙丙丁戊己庚辛"),
            structuralIds = setOf(7L),
        )
        assertTrue(map.overlay(r) == null)
        assertTrue(map.paintedDeleted.isEmpty())
        assertTrue(map.paintedInserted.isEmpty())
        // 审阅空间长度 = 新表长度 = 原区间长度 + delta（与渲染空间同一把尺）。
        assertEquals(6, map.reviewInterval(r).last + 1)
        assertEquals(4 until 6, map.reviewInterval(r))
        // 换回原书空间仍然落到这张表的起点。
        assertEquals(4, map.toOriginalFromReview(4))
        assertEquals(12, map.toOriginalFromReview(6))
    }

    @Test
    fun paintedSpansEmptyWithoutOriginalText() {
        // 没给原文（老数据 / 取不到原书叶文本）：这一段按正常视图落字，没有红绿可画，
        // 坐标换算也退回渲染口径（审阅空间 = 渲染空间）。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertTrue(map.paintedDeleted.isEmpty())
        assertTrue(map.paintedInserted.isEmpty())
        assertTrue(map.reviewMarkOffsets.isEmpty())
        assertEquals(2, map.reviewInterval(map.revisions.single()).last + 1)
    }
}
