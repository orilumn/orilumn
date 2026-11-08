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
