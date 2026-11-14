package orilumn.reader.engine.revision

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
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
    fun deletingAWholeBlockImageRemovesTheLeafAndPullsStartsBack() {
        // 整块图片在字符流里只占一个 U+FFFC：占位删掉 = 整张图删掉，后面的叶起点回退。
        val img = MarkupElement("img", mapOf("src" to "a.png"))
        val p = el("p", txt("abc"))
        val root = el("body", img, p)
        fun segOf(e: MarkupElement) = if (e.tag == "img") {
            styledSegments(
                root = txt("\uFFFC"),
                wsOf = { WhiteSpace.NORMAL },
                isExcluded = { false },
                isBlock = { false },
                leafWs = WhiteSpace.NORMAL,
            )
        } else {
            seg(e)
        }
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(img, p),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 1),
            editsForLeaf = { i, _ ->
                if (i == 0) listOf(LeafTextEditor.Edit(0, 1, "")) else emptyList()
            },
            segmentsOf = ::segOf,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertFalse(r.needsRecompute)
        assertEquals(1, r.leaves.size)
        assertEquals("p", r.leaves.single().tag)
        assertEquals(listOf(0L), r.charStarts.toList())
        assertTrue("整张图删掉后树里不能还挂着 img", r.root.children.none { it.tag == "img" })
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
     * 长按/点按取到的是后面几十个字——修订卡里的文字自然与屏幕上选中的不是一处。
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
        // 章树里只剩这两块：组里的其余叶必须撤掉，否则原文会在后面再显示一遍。
        assertEquals(2, r.root.children.size)
        assertEquals("p", r.root.children[1].tag)
        assertEquals("Yef", seg(r.root.children[1]).text)
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
    fun singleLeafSplitCreatesSiblingBlockWithSameTag() {
        // 一段之内新插一个段界（读者在段内敲回车）：原叶承载前缀 + 第一行，后半截复制一份做末块。
        val root = el("body", el("p", txt("abcdef")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 0, 3, 3, RevisionBlocks.SEPARATOR.toString(), 7L),
            ),
        )
        assertEquals(2, r.leaves.size)
        assertEquals("abc", seg(r.leaves[0]).text)
        assertEquals("def", seg(r.leaves[1]).text)
        // 拆出来的块与原块同 tag / 同属性（补的就是「插入点所在的标签」）。
        assertEquals("p", r.leaves[0].tag)
        assertEquals("p", r.leaves[1].tag)
        assertArrayEquals(longArrayOf(0, 3), r.charStarts)
        assertEquals(2, r.root.children.size)
    }

    @Test
    fun singleListItemSplitYieldsTwoListItems() {
        // 读者只改一个列表项、在卡片里敲回车补一行 `- …`：拆出来的两块要各占一个列表项，
        // 不能把第二块塞进同一个 `li`（那样屏幕上是一项里两段字）。
        fun item(text: String) = MarkupElement("li", mapOf("class" to "list__item"), listOf(el("p", txt(text))))
        val root = el("body", MarkupElement("ul", emptyMap(), listOf(item("abcdef"))))
        MarkupPaths.assignParents(root)
        val leaf = root.children[0].children[0].children[0]
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(leaf),
            leafPaths = listOf(intArrayOf(0, 0, 0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(
                    firstLeaf = 0,
                    lastLeaf = 0,
                    start = 0,
                    endExclusive = 6,
                    text = "abc${RevisionBlocks.SEPARATOR}def",
                    revisionId = 7L,
                    kinds = listOf(BlockKind.Bullet, BlockKind.Bullet),
                ),
            ),
        )
        val ul = r.root.children.single()
        assertEquals("ul", ul.tag)
        assertEquals(listOf("li", "li"), ul.children.map { it.tag })
        assertEquals(listOf("abc", "def"), ul.children.map { seg(it).text })
    }

    @Test
    fun reviewCopyWithBreaksSplitsIntoRealBlocks() {
        // 审阅视图：原文照排，带段界的新文字副本接在选区末尾。副本里的是**真段界**，
        // 要落成真块（上一行末尾带绿 ¶），不能退化成 `¶` + `<br>`。
        val root = el("body", el("p", txt("abcdef")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                // 在 3 处插入副本「XY¶<段界>Z」。
                ChapterRevisionReplay.Merge(
                    0,
                    0,
                    3,
                    3,
                    "XY${ChapterRevisionMap.BREAK_MARK_CHAR}${RevisionBlocks.SEPARATOR}Z",
                    7L,
                ),
            ),
        )
        assertEquals(2, r.leaves.size)
        assertEquals("abcXY${ChapterRevisionMap.BREAK_MARK_CHAR}", seg(r.leaves[0]).text)
        assertEquals("Zdef", seg(r.leaves[1]).text)
        // 副本拆出来的块与插入点所在的块同 tag。
        assertEquals("p", r.leaves[1].tag)
        assertArrayEquals(longArrayOf(0, 6), r.charStarts)
        assertEquals(2, r.root.children.size)
    }

    @Test
    fun twoSplitsInOneLeafBothLand() {
        // 同一段里分两次插段界：两处都要落成块，不能后一条把前一条顶掉。
        val root = el("body", el("p", txt("abcdef")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 0, 2, 2, RevisionBlocks.SEPARATOR.toString(), 7L),
                ChapterRevisionReplay.Merge(0, 0, 4, 4, RevisionBlocks.SEPARATOR.toString(), 8L),
            ),
        )
        assertEquals(3, r.leaves.size)
        assertEquals(listOf("ab", "cd", "ef"), r.leaves.map { seg(it).text })
        assertArrayEquals(longArrayOf(0, 2, 4), r.charStarts)
        assertEquals(3, r.root.children.size)
    }

    @Test
    fun singleLeafSplitKeepsInlineMarkupInSuffix() {
        // 后缀里的行内元素要跟着走：`<p>a<em>bc</em>def</p>` 在 2 处拆开 ⇒ 末块仍是 `<em>c</em>def`。
        val root = el("body", el("p", txt("a"), el("em", txt("bc")), txt("def")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 0, 2, 2, RevisionBlocks.SEPARATOR.toString(), 7L),
            ),
        )
        assertEquals(2, r.leaves.size)
        assertEquals("ab", seg(r.leaves[0]).text)
        assertEquals("cdef", seg(r.leaves[1]).text)
        assertTrue(r.leaves[1].children.any { it.tag == "em" })
    }

    @Test
    fun splitAndEditOnOneLeafBothLand() {
        // 同一段里一处敲回车、另一处改字：两条修订必须一起拼进重塑，不能后一条把前一条顶掉。
        val root = el("body", el("p", txt("abcdef")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0]),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            merges = listOf(
                ChapterRevisionReplay.Merge(0, 0, 2, 2, RevisionBlocks.SEPARATOR.toString(), 7L),
                ChapterRevisionReplay.Merge(0, 0, 4, 5, "E", 8L),
            ),
        )
        assertEquals(listOf("ab", "cdEf"), r.leaves.map { seg(it).text })
        assertArrayEquals(longArrayOf(0, 2), r.charStarts)
        assertEquals(2, r.root.children.size)
    }

    @Test
    fun crossLeafMergeAndTailEditShareOneGroup() {
        // 跨叶并段 + 末叶后缀里另有一处改字：两处都在同一组里按自己的起点拼回整段文字。
        val root = el("body", el("p", txt("abc")), el("p", txt("def")))
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
                // [1,5)「bcde」换成「X」；末叶那个「f」还在，另外把 [5,6) 的 f 改成 F。
                // 同组共用首叶 0，两处起点都按组的首叶算（调用方的约定）。
                ChapterRevisionReplay.Merge(0, 1, 1, 5, "X", 7L),
                ChapterRevisionReplay.Merge(0, 1, 5, 6, "F", 8L),
            ),
        )
        assertEquals(listOf("aXF"), r.leaves.map { seg(it).text })
        assertArrayEquals(longArrayOf(0), r.charStarts)
        assertEquals(1, r.root.children.size)
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

    @Test
    fun replayDropsInlineImageWhenItsSlotIsDeleted() {
        // 一段夹着行内图（`U+FFFC` 占位）的文字整段改写：重放后这张图要跟着删掉，
        // 不能留下「字换了、图还挂在那儿」。
        val p = el("p", txt("前"), MarkupElement("img", mapOf("src" to "a.png")), txt("后"))
        val root = el("body", p)
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(p),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> listOf(LeafTextEditor.Edit(0, 3, "改")) },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertEquals("改", seg(r.leaves[0]).text)
        assertTrue("图片要跟着删", r.leaves[0].children.none { it.tag == "img" })
    }

    @Test
    fun replayKeepsInlineImageWhenItsSlotSurvives() {
        // 反过来：占位字还在，图就留在前后两截字中间。
        val p = el("p", txt("前"), MarkupElement("img", mapOf("src" to "a.png")), txt("后"))
        val root = el("body", p)
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(p),
            leafPaths = listOf(intArrayOf(0)),
            charStarts = longArrayOf(0),
            editsForLeaf = { _, _ -> listOf(LeafTextEditor.Edit(0, 3, "先\uFFFC尾")) },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
        )
        assertEquals("先\uFFFC尾", seg(r.leaves[0]).text)
        assertEquals(1, r.leaves[0].children.count { it.tag == "img" })
    }

    // ---------------------------------------------------------------- 块级形态

    @Test
    fun blockKindTurnsParagraphIntoHeading() {
        val root = body()
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { i, _ -> if (i == 0) listOf(LeafTextEditor.Edit(0, 3, "ABC")) else emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            blockOfLeaf = { i -> if (i == 0) BlockKind.Heading2 else null },
        )
        assertEquals("h2", r.root.children[0].tag)
        assertEquals("h2", r.leaves[0].tag)
        assertEquals("ABC", seg(r.leaves[0]).text)
        // 没改块形态的那一段原样保留。
        assertSame(root.children[1], r.root.children[1])
    }

    @Test
    fun unchangedBlockKindTouchesNothing() {
        val root = body()
        val p = root.children[0]
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(p, root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { i, _ -> if (i == 0) listOf(LeafTextEditor.Edit(0, 3, "ABC")) else emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            blockOfLeaf = { BlockKind.Paragraph },
        )
        // 目标形态 = 现在形态：一个块容器都不该多出来。
        assertEquals("body", r.root.tag)
        assertEquals("p", r.root.children[0].tag)
        assertEquals(2, r.root.children.size)
    }

    /**
     * 审阅视图里「只改块形态、文字一字未动」的修订：文字那一侧没有任何编辑（只有新字才产生编辑），
     * 主循环会整片叶跳过——块形态不能跟着一起丢掉。
     */
    @Test
    fun blockKindAppliesEvenWithoutTextEdits() {
        val root = el("body", el("h1", txt("abc")), el("p", txt("def")))
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = listOf(root.children[0], root.children[1]),
            leafPaths = listOf(intArrayOf(0), intArrayOf(1)),
            charStarts = longArrayOf(0, 3),
            editsForLeaf = { _, _ -> emptyList() },
            segmentsOf = ::seg,
            wsOf = { WhiteSpace.NORMAL },
            styleOf = null,
            blockOfLeaf = { i -> if (i == 0) BlockKind.Paragraph else null },
        )
        assertEquals("p", r.root.children[0].tag)
        assertEquals("p", r.leaves[0].tag)
        assertEquals("abc", seg(r.leaves[0]).text)
        // 没要求改形态的那一段原样留着。
        assertSame(root.children[1], r.root.children[1])
    }

    @Test
    fun mergeKindsWrapBothLinesIntoOneBlockquote() {
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
                    text = "X${RevisionBlocks.SEPARATOR}Y",
                    revisionId = 7L,
                    kinds = listOf(BlockKind.Quote, BlockKind.Quote),
                ),
            ),
        )
        assertEquals(2, r.leaves.size)
        assertEquals("aX", seg(r.leaves[0]).text)
        assertEquals("Yef", seg(r.leaves[1]).text)
        val quote = r.root.children.single()
        assertEquals("blockquote", quote.tag)
        assertEquals(listOf("p", "p"), quote.children.map { it.tag })
        assertEquals("aX", seg(quote.children[0]).text)
    }

    /**
     * 跨叶合并时，叶各自住在一层列表项里（`ul > li > p`）：拆出来的每一块都必须各占一个列表项。
     *
     * 回归：只换第一片叶时，新块全塞进第一个 `li`，其余 `li` 被撤掉内容，读者补一行列表
     * 就看到「第一项还在、后面几项变成普通段落」。
     */
    @Test
    fun mergeAcrossListItemLeavesKeepsEachBlockAListItem() {
        fun item(text: String, id: String) = MarkupElement(
            "li",
            mapOf("class" to "list__item", "id" to id),
            listOf(el("p", txt(text))),
        )
        val root = el(
            "body",
            MarkupElement(
                "ul",
                mapOf("class" to "list _bullet"),
                listOf(item("abc", "l1"), item("def", "l2")),
            ),
        )
        MarkupPaths.assignParents(root)
        val leaves = listOf(
            root.children[0].children[0].children[0],
            root.children[0].children[1].children[0],
        )
        val r = ChapterRevisionReplay.replay(
            root = root,
            leaves = leaves,
            leafPaths = listOf(intArrayOf(0, 0, 0), intArrayOf(0, 1, 0)),
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
                    kinds = listOf(BlockKind.Bullet, BlockKind.Bullet, BlockKind.Bullet),
                ),
            ),
        )
        val ul = r.root.children.single()
        assertEquals("ul", ul.tag)
        assertEquals("list _bullet", ul.attrs["class"])
        // 三项 = 三个列表项，不是一个列表项里塞三段。
        assertEquals(listOf("li", "li", "li"), ul.children.map { it.tag })
        assertEquals(listOf("aX", "Y", "Zef"), ul.children.map { seg(it).text })
        assertTrue(ul.children.all { it.children.size == 1 && it.children[0].tag == "p" })
        // 复用的列表项保住自己的属性；多出来的那一项抄样式但不抄 `id`。
        assertEquals("l1", ul.children[0].attrs["id"])
        assertEquals("l2", ul.children[1].attrs["id"])
        assertEquals("list__item", ul.children[2].attrs["class"])
        assertFalse(ul.children[2].attrs.containsKey("id"))
    }

    @Test
    fun mergeKindsWithShortListFallBackToNoBlockChange() {
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
                    text = "X${RevisionBlocks.SEPARATOR}Y",
                    revisionId = 7L,
                    kinds = listOf(BlockKind.Quote),
                ),
            ),
        )
        // 数量对不上：宁可不动结构，也不能猜着套一半。
        assertEquals(2, r.root.children.size)
        assertEquals(listOf("p", "p"), r.root.children.map { it.tag })
    }
}
