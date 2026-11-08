package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.styledSegments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 章级重放：按叶顺序、修正后续叶起点、匿名 run 兜底、不写回（纯内存）。 */
class ChapterRevisionReplayTest {

    private fun txt(s: String) = MarkupElement("#text", text = s)
    private fun el(tag: String, vararg c: MarkupElement) = MarkupElement(tag, emptyMap(), c.toList())

    private fun seg(root: MarkupElement) = styledSegments(
        root = root,
        wsOf = { WhiteSpace.NORMAL },
        isExcluded = { false },
        isBlock = { false },
        leafWs = WhiteSpace.NORMAL,
    )

    private fun body(): MarkupElement = el("body", el("p", txt("abc")), el("p", txt("def")))

    @Test
    fun editsShiftFollowingLeafStarts() {
        val root = body()
        val leaves = listOf(root.children[0], root.children[1])
        // 第一叶 [0,3) 的 "b" → "XYZ"：delta = +2。
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = leaves,
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { i, _ -> if (i == 0) listOf(LeafTextEditor.Edit(1, 2, "XYZ")) else emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertFalse(r.needsRecompute)
        assertEquals(0, r.degradedLeaves)
        assertEquals("aXYZc", seg(r.leaves[0]).text)
        assertEquals("def", seg(r.leaves[1]).text)
        assertArrayEquals(longArrayOf(0, 5), r.charStarts)
        assertEquals("aXYZc", seg(r.root.children[0]).text)
    }

    @Test
    fun untouchedLeavesKeepIdentity() {
        val root = body()
        val p1 = root.children[1]
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], p1),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { i, _ -> if (i == 0) listOf(LeafTextEditor.Edit(0, 1, "Z")) else emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertSame(p1, r.leaves[1])
    }

    @Test
    fun anonymousRunNeedsStructureRecompute() {
        val root = body()
        val synthetic = txt("abc")
        synthetic.parent = root
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], synthetic),
            leafPaths = listOf(intArrayOf(0), null),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { i, _ -> if (i == 1) listOf(LeafTextEditor.Edit(0, 1, "x")) else emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertTrue(r.needsRecompute)
        assertEquals(3L, r.charStarts[1])
    }

    @Test
    fun mergeCollapsesMiddleBlocks() {
        // 两段「abc」「def」；把 [1,4)（"bc" + "d"）换成一段 "X" → 只剩一段「aXef」。
        val root = body()
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(ChapterRevisionReplay.Merge(0, 1, 1, 4, "X", 7L)),
        )
        assertFalse(r.needsRecompute)
        assertEquals(1, r.leaves.size)
        assertEquals("aXef", seg(r.leaves[0]).text)
        assertEquals(1, r.root.children.size)
        assertArrayEquals(longArrayOf(0), r.charStarts)
        assertEquals(-2, r.measuredRevisionDeltas[7L])
        // 合并出来的块继承首叶的装饰。
        assertArrayEquals(intArrayOf(0), r.leafOrigin)
    }

    @Test
    fun mergeThatOnlyDropsParagraphBreakKeepsTextOnce() {
        // 真机回归：「论心细，我不如你。」+「以后还是要…」两段，读者把段间换行删掉。
        // 修订文字与它盖住的原书文字逐字相同（只少了块边界）⇒ 必须并成一段，且这段字只出现一次。
        val p1 = el("p", txt("“论心细，我不如你。"))
        val p2 = el("p", txt("以后还是要将这些心思。"))
        val root = el("body", p1, p2)
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(p1, p2),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 10),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            // 选区从「如」到「以后还是要」（跨两叶），替换文字就是原书那两个半截拼起来的一串。
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 1, 7, 15, "如你。以后还是要", 7L),
            ),
        )
        assertFalse(r.needsRecompute)
        assertEquals(1, r.leaves.size)
        assertEquals("“论心细，我不如你。以后还是要将这些心思。", seg(r.leaves[0]).text)
        assertEquals(1, r.root.children.size)
        // 一个字没多、一个字没少：实测增量必须为 0。
        assertEquals(0, r.measuredRevisionDeltas[7L])
    }

    /**
     * 合并段**后面的叶**只能按增量平移，不能按合并段自身长度再推一遍。
     *
     * 真机回归（ch=173 并段后选区整体错位）：`shift` 当时累加的是每个新块的长度，
     * 于是并段之后所有叶的字符起点都多算了整整一段（53 字），排版偏移与正文投影错位，
     * 长按/点按取到的是后面几十个字——编辑卡里的文字自然与屏幕上选中的不是一处。
     */
    @Test
    fun mergeShiftsFollowingLeavesByDeltaOnly() {
        // 并段前后字数相同（delta = 0）：后面那叶必须原地不动。
        val root = el("body", el("p", txt("ab")), el("p", txt("cd")), el("p", txt("ef")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1], root.children[2]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2)),
            charStarts = longArrayOf(0, 2, 4),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(ChapterRevisionReplay.Merge(0, 1, 1, 3, "bc", 7L)),
        )
        assertEquals(listOf("abcd", "ef"), r.leaves.map { seg(it).text })
        assertArrayEquals(longArrayOf(0, 4), r.charStarts)
        assertStartsMatchLeafTexts(r)
        assertEquals(0, r.measuredRevisionDeltas[7L])

        // 并段顺带删字（delta = -2）：后面那叶跟着前移 2，不多不少。
        val root2 = el("body", el("p", txt("abcd")), el("p", txt("efgh")), el("p", txt("ij")))
        val r2 = ChapterRevisionReplay.replay(
            root = root2,
            leaves = listOf(root2.children[0], root2.children[1], root2.children[2]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2)),
            charStarts = longArrayOf(0, 4, 8),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(ChapterRevisionReplay.Merge(0, 1, 2, 6, "XY", 7L)),
        )
        assertEquals(listOf("abXYgh", "ij"), r2.leaves.map { seg(it).text })
        assertArrayEquals(longArrayOf(0, 6), r2.charStarts)
        assertStartsMatchLeafTexts(r2)
        assertEquals(-2, r2.measuredRevisionDeltas[7L])
    }

    /** 叶起点必须与叶文本首尾相接：差一个字，之后所有偏移都会取到别的段落。 */
    private fun assertStartsMatchLeafTexts(r: ChapterRevisionReplay.Result) {
        var expected = 0L
        for ((i, leaf) in r.leaves.withIndex()) {
            assertEquals("叶 $i 的起点错位", expected, r.charStarts[i])
            expected += seg(leaf).text.length
        }
    }

    @Test
    fun mergeWithTwoLinesKeepsBothParagraphs() {
        val root = body()
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 1, 1, 4, "X${RevisionBlocks.SEPARATOR}Y", 7L),
            ),
        )
        assertEquals(2, r.leaves.size)
        assertEquals("aX", seg(r.leaves[0]).text)
        assertEquals("Yef", seg(r.leaves[1]).text)
        assertArrayEquals(longArrayOf(0, 2), r.charStarts)
    }

    @Test
    fun mergeCanSplitIntoMoreBlocksThanLeaves() {
        val root = body()
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(
                    firstLeaf = 0,
                    lastLeaf = 1,
                    start = 1,
                    endExclusive = 4,
                    text = "X${RevisionBlocks.SEPARATOR}Y${RevisionBlocks.SEPARATOR}Z",
                    revisionId = 7L,
                ),
            ),
        )
        assertEquals(3, r.leaves.size)
        assertEquals("aX", seg(r.leaves[0]).text)
        assertEquals("Y", seg(r.leaves[1]).text)
        assertEquals("Zef", seg(r.leaves[2]).text)
        assertArrayEquals(longArrayOf(0, 2, 3), r.charStarts)
    }

    @Test
    fun longReplacementKeepsInsertedTailAtEnd() {
        // 单叶内「尾段整体重写 + 末尾再添几个字」：新文字必须按原位置落在叶里，
        // 不能把最后一截插到最前面（真机审阅改字后正常视图错位的回归用例）。
        val para = "还好，自家师父没起疑，还颇为关切的问李长寿何时有了这般病症，并握住李长寿的手腕细细查看了下。"
        val root = el("body", el("p", txt(para)))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ ->
                listOf(
                    LeafTextEditor.Edit(
                        17,
                        46,
                        "地问李长寿何时有了这般病症，并握住李长寿的手腕细细查看了下。ABC",
                    ),
                )
            },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertEquals(
            "还好，自家师父没起疑，还颇为关切的" +
                "地问李长寿何时有了这般病症，并握住李长寿的手腕细细查看了下。ABC",
            seg(r.leaves[0]).text,
        )
    }

    @Test
    fun replaceAtKeepsSiblingsAndAttributes() {
        val p = MarkupElement("p", mapOf("class" to "x"), listOf(txt("a"), txt("b")))
        val root = el("body", p, txt("tail"))
        val replacement = txt("B")
        val out = ChapterRevisionReplay.replaceAt(root, intArrayOf(0, 1), replacement)

        assertEquals(mapOf("class" to "x"), out.children[0].attrs)
        assertSame(root.children[1], out.children[1])
        assertSame(replacement, out.children[0].children[1])
        // 原树不被改动。
        assertEquals("b", root.children[0].children[1].text)
    }
}
