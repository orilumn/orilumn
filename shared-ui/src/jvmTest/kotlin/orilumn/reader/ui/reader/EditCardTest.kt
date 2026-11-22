package orilumn.reader.ui.reader

import orilumn.reader.data.revision.RevisionStyle
import orilumn.reader.data.revision.RevisionStyleSpan
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.revision.BlockKind
import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableRowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开旧修改时按**输入区全文结构**回显（`TextRevision.editedStyles`）。
 *
 * 背景：落库的最小修订被公共前后缀裁过，`styles` 只剩「改了一个字」那截，元素结构往往整个
 * 落在被裁掉的两端里。只按它回显会把 `<code>` 丢掉，读者再确认一次就真的删掉了。
 */
class EditCardTest {

    private val code = RevisionStyle("code", mapOf("class" to "code", "id" to "-nubh3s_43"))

    @Test
    fun cardKeepsStructureThatTheTrimmedRevisionLost() {
        // 「使用 var」→「用 var 声明」：最小修订只留下「使」，`var` 的 code 结构只剩输入区这一份。
        val edited = listOf(RevisionStyleSpan(2, 5, code))
        val card = editCardOf("用 var 声明", emptyList(), edited)
        assertEquals("用 `var` 声明", card.text)
        assertEquals(1, card.originalSpans?.size)
        assertEquals(InlineMarkup.StyleSig("code", code.attrs), card.originalSpans?.single()?.sig)
        assertTrue(card.ambientSpans.isEmpty())
    }

    @Test
    fun reConfirmsWithoutLosingOrDuplicatingStructure() {
        // 卡片回显 → 解析回来 → 按原件补回属性：结构与落库那一份逐字段相同（原样确认即 no-op）。
        val stored = listOf(RevisionStyleSpan(2, 5, code))
        val card = editCardOf("用 var 声明", emptyList(), stored)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals("用 var 声明", parsed.text)
        val restored = InlineMarkup.restore(card.originalSpans.orEmpty(), parsed.spans)
        assertEquals(InlineMarkup.fromStored(stored), restored)
    }

    @Test
    fun elementCoveringTheWholeEditedTextStaysHidden() {
        // 读者在元素内部改了一个字：整段仍是同一个元素，卡片里不显示标记（隐形套回）。
        val card = editCardOf("值", emptyList(), listOf(RevisionStyleSpan(0, 1, code)))
        assertEquals("值", card.text)
        assertEquals(1, card.ambientSpans.size)
    }

    @Test
    fun markupTheReaderJustAddedIsShownEvenIfItCoversTheWholeCard() {
        // 选中一段字整段加粗：字一个没改，只有标记是新的。早先「整段覆盖 = 隐形」把新写的
        // `**` 也藏了，卡片里只剩一段普通文字，与审阅视图那对绿 `**` 对不上（用户复报）。
        val strong = RevisionStyle("strong", emptyMap())
        val stored = listOf(RevisionStyleSpan(0, 2, strong))
        val card = editCardOf("本节", emptyList(), stored, oldSpans = emptyList())
        assertEquals("**本节**", card.text)
        assertTrue(card.ambientSpans.isEmpty())
        // 回显 → 解析 → 补回：原样确定仍是同一条结构。
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals("本节", parsed.text)
        assertEquals(
            InlineMarkup.fromStored(stored),
            InlineMarkup.restore(emptyList(), parsed.spans),
        )
    }

    @Test
    fun markupThatWasAlreadyInTheBookStaysHidden() {
        // 同一段字本来就整段在 `<code>` 里（只改了一个字）：那是书自己的元素，不是读者这次写的，
        // 仍旧隐形套回——与审阅视图里「没有痕迹」一致。
        val original = listOf(InlineMarkup.StyleSpan(0, 2, InlineMarkup.StyleSig("code", code.attrs)))
        val card = editCardOf(
            "本节", emptyList(), listOf(RevisionStyleSpan(0, 2, code)), oldSpans = original,
        )
        assertEquals("本节", card.text)
        assertEquals(1, card.ambientSpans.size)
    }

    @Test
    fun onlyTheMarkersTheReaderAddedAreShown() {
        // 原来的 `<code>` 留着，读者又给整段加了粗：只有粗体是新的（摊开显示），代码层隐形。
        val original = listOf(InlineMarkup.StyleSpan(0, 2, InlineMarkup.StyleSig("code", code.attrs)))
        val strong = RevisionStyle("strong", emptyMap())
        val card = editCardOf(
            "本节", emptyList(),
            listOf(RevisionStyleSpan(0, 2, code), RevisionStyleSpan(0, 2, strong)),
            oldSpans = original,
        )
        assertEquals("**本节**", card.text)
        assertEquals(1, card.ambientSpans.size)
        assertEquals(InlineMarkup.StyleSig("code", code.attrs), card.ambientSpans.single().sig)
    }

    @Test
    fun revisionOnlyCardUsesTheBookSpansToKeepUnchangedMarkupHidden() {
        // 重开一条「只加了粗」的修订：卡片带出 `**`，与审阅视图的绿 `**` 是同一件事。
        val strong = RevisionStyle("strong", emptyMap())
        val revision = TextRevision(
            id = 7, bookId = 1, chapter = 1, charStart = 100, charEnd = 102, text = "本节",
            styles = listOf(RevisionStyleSpan(0, 2, strong)),
            originalText = "本节", editedText = "本节",
        )
        assertEquals("**本节**", revisionCardOf(revision, emptyList()).card.text)
        // 取不到原书结构（老宿主 / 结构未就绪）时不猜，按老口径开卡。
        assertEquals("本节", revisionCardOf(revision, null).card.text)
        // 书里本来就整段在 `<code>` 里、这次也没动它：隐形。
        val original = listOf(InlineMarkup.StyleSpan(0, 2, InlineMarkup.StyleSig("code", code.attrs)))
        val unchanged = revision.copy(styles = listOf(RevisionStyleSpan(0, 2, code)))
        assertEquals("本节", revisionCardOf(unchanged, original).card.text)
    }

    @Test
    fun reviewModeRevisionCopyStillShowsTheMarkersTheReaderWrote() {
        // 审阅视图里宿主把区间换成「原文 + 痕迹 + 新字」，比替换文本长；卡片文字仍是替换文本。
        // 原书结构由宿主按落库那条筛过（等长才给），卡片这边不能再按区间长度二次筛，
        // 否则审阅视图里又只剩一段普通文字——那正是用户复报的地方。
        val strong = RevisionStyle("strong", emptyMap())
        val reviewCopy = TextRevision(
            id = 8, bookId = 1, chapter = 1, charStart = 4, charEnd = 10, text = "表格",
            styles = listOf(RevisionStyleSpan(0, 2, strong)),
            originalText = "表格", editedText = "表格",
        )
        assertEquals("**表格**", revisionCardOf(reviewCopy, emptyList()).card.text)
    }

    @Test
    fun oldRevisionWithoutEditedStylesOpensAsPlainText() {
        // 老数据没存过输入区结构：结构按纯文本开卡，一个标记都不凭空加（文字仍按原文转义）。
        val card = editCardOf("用 val 声明", emptyList(), null)
        assertEquals("用 val 声明", card.text)
        assertNull(card.originalSpans)
        assertTrue(card.ambientSpans.isEmpty())
    }

    @Test
    fun bookLiteralMarkdownIsEscapedSoConfirmChangesNothing() {
        // EPUB 不是 markdown：书里字面写着 `**加粗**` 是普通文字。进卡要原样带上、按普通文字转义，
        // 确认时反斜杠去掉，落进书里的还是那几个字，不会变成粗体（规格 §2.5 / §3.1）。
        val original = "**加粗** 与 10~20"
        val card = editCardOf(original, emptyList(), null)
        // 这一段里已经有真标记，整体转义模式下短横线一类的标记字符也一并加反斜杠（§2.5）。
        assertEquals("\\*\\*加粗\\*\\* 与 10\\~20", card.text)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals(original, parsed.text)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun linkAroundCodeShowsAsMarkdownInTheCard() {
        // 文档里常见的 `<a href="…"><code>println()</code></a>`：链接里套着代码，两者区间重合。
        // 以前解析把它们排成内层在前，卡片只好整段退回标签形式；现在照旧写成 `[文字](地址)`。
        val link = RevisionStyle("a", mapOf("href" to "https://a.b"))
        val spans = listOf(
            RevisionStyleSpan(2, 11, link),
            RevisionStyleSpan(2, 11, code),
        )
        val card = editCardOf("见 println() 用法", emptyList(), spans)
        assertEquals("见 [`println()`](https://a.b) 用法", card.text)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals("见 println() 用法", parsed.text)
        assertEquals(listOf("a", "code"), parsed.spans.map { it.sig.tag })
    }

    // ------------------------------------------------- 同段模板的取法

    @Test
    fun paragraphRangeIsTheBlockContainingTheSelectionStart() {
        val ends = intArrayOf(3, 6, 10)
        assertEquals(3 until 6, paragraphRangeOf(ends, 10, 4, 5))
        assertEquals(0 until 3, paragraphRangeOf(ends, 10, 0, 2))
        // 跨段选区只取首段：落笔那一段才是模板的来源。
        assertEquals(3 until 6, paragraphRangeOf(ends, 10, 4, 8))
        // 选到章尾（末尾可能是空段）时不瞎给区间。
        assertEquals(6 until 10, paragraphRangeOf(ends, 10, 9, 10))
    }

    @Test
    fun paragraphRangeFallsBackToTheSelectionWithoutBlockEnds() {
        assertEquals(4 until 5, paragraphRangeOf(null, 10, 4, 5))
        assertNull(paragraphRangeOf(intArrayOf(10), 10, 10, 10))
    }

    // ------------------------------------------------- 跨段时行内结构在卡里的坐标

    @Test
    fun repeatedBlockEndDoesNotShiftSpansInTheCard() {
        // 相邻两个叶在同一处结束（中间那个叶没有字，图 / 空叶常见）：切段只切一刀，
        // 偏移换算也只能加一个换行，否则后面每一段整体挪一格
        //（真机复报：「Tab」的「T」跑到 <span> 外面、「键」跑进标签里）。
        val text = "箭头三个字Tab键来隐藏"
        val ends = intArrayOf(5, 8, 8, text.length)
        val spans = listOf(InlineMarkup.StyleSpan(5, 8, InlineMarkup.StyleSig("span", mapOf("class" to "keycap"))))
        val pieces = selectionPiecesForEdit(text, ends, 0, text.length)
        // 空叶不出行：3 行（不是 4 行），行内文字正好是「箭头三个字」「Tab」「键来隐藏」。
        assertEquals(listOf("箭头三个字", "Tab", "键来隐藏"), pieces)
        val mapped = spansToEditText(spans, ends, start = 0, endExclusive = text.length)
        val cardText = pieces.joinToString("\n")
        assertEquals(listOf("Tab"), mapped.map { cardText.substring(it.start, it.endExclusive) })
    }

    @Test
    fun crossParagraphSpansLandOnTheRightCharactersInTheCard() {
        // 两段「他走」「他笑」，选区 1..3（跨段）。卡里文字 = "走\n他"，段界处多了一个 \n。
        val ends = intArrayOf(2, 4)
        val spans = listOf(
            InlineMarkup.StyleSpan(1, 3, InlineMarkup.StyleSig("em")),
            InlineMarkup.StyleSpan(2, 3, InlineMarkup.StyleSig("code")),
        )
        val mapped = spansToEditText(spans, ends, start = 1, endExclusive = 3)
        assertEquals("走\n他", selectionTextForEdit("他走他笑", ends, 1, 3))
        assertEquals(
            listOf(0 to 3 to "em", 2 to 3 to "code"),
            mapped.map { it.start to it.endExclusive to it.sig.tag },
        )
        // 映射完的区间必须落在卡里那段文字上（下标越界就会把标记画到别的字上）。
        for (s in mapped) {
            assertTrue("span out of card text: $s", s.endExclusive <= 3 && s.start >= 0)
        }
    }

    // ------------------------------------------------- 块级标记的回显

    @Test
    fun cardShowsBlockMarkersStoredWithTheRevision() {
        // 落库的每行形态：`## ` / 普通段落 / `> `：重开卡片要原样看见，读者才能继续改。
        val blocks = listOf("h2", "p", "quote")
        val card = editCardOf("标题\n正文\n引用", blocks, null)
        assertEquals("## 标题\n正文\n> 引用", card.text)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals(
            listOf(BlockKind.Heading2, BlockKind.Paragraph, BlockKind.Quote),
            parsed.segments.map { it.block ?: BlockKind.Paragraph },
        )
    }

    @Test
    fun cardShowsMarkersAndInlineMarkersOnEveryBlock() {
        // 一次改了好几条列表项：重开卡片时**每一行**都要带 `- `，行内代码写成反引号。
        // 回归：多块回显曾整体退回标签形式（第二块起显示成 `<code>`），读者看到的是一屏原始标签。
        val text = "fun 用于声明函数\nmain()函数是你的程序开始的地方\n{}内"
        val main = text.indexOf("main()")
        val braces = text.indexOf("{}")
        val spans = listOf(
            RevisionStyleSpan(0, 3, code),
            RevisionStyleSpan(main, main + 6, code),
            RevisionStyleSpan(braces, braces + 2, code),
        )
        val card = editCardOf(text, listOf("ul", "ul", "ul"), spans)
        assertEquals(
            "- `fun` 用于声明函数\n" +
                "- `main()`函数是你的程序开始的地方\n" +
                "- `{}`内",
            card.text,
        )
        // 卡片文字解析回来必须与原意一致（重新确认不会改掉正文）。
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(text, parsed.text)
    }

    @Test
    fun codeAfterALeadingSpaceStaysOnItsOwnCharsInTheCard() {
        // 真机复报：把「内 {}」挪成「 {}内」后再打开卡片，反引号整体前移一格，成了「` {`}内」。
        // 落库的 editedStyles 本来就按输入区原文记（`{}` 在 1..3），回显必须原样圈住两个大括号。
        val card = editCardOf(" {}内", emptyList(), listOf(RevisionStyleSpan(1, 3, code)))
        assertEquals(" `{}`内", card.text)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(" {}内", parsed.text)
        assertEquals(listOf(1 to 3), parsed.spans.map { it.start to it.endExclusive })
    }

    @Test
    fun continuationLinesAreJoinedBackIntoTheirBlock() {
        // 块内换行（`<br>` / 代码块）的续行并回上一块，不能当成新的一段。
        val blocks = listOf("pre", "-", "-")
        val card = editCardOf("一\n二\n三", blocks, null)
        assertEquals("```\n一\n二\n三\n```", card.text)
    }

    @Test
    fun oldRevisionWithoutBlockInfoShowsNoMarkers() {
        val card = editCardOf("## 书里本来就有的井号", emptyList(), null)
        // 只转义行首那一个 `#` 就够（`\##` 不是标记），读者确认后落进书里的还是原文。
        assertEquals("\\## 书里本来就有的井号", card.text)
        assertEquals("## 书里本来就有的井号", InlineMarkup.parse(card.text).text)
    }

    // ------------------------------------------------ 重开修订 = 改后整段，不是上次选区

    @Test
    fun revisionCardShowsFullCurrentResultNotJustTheLastSelection() {
        // 原书「abcdef」先改成「abXYef」，再只把「X」改成「Z」：最小修订 [2,4) → 「ZY」，
        // 但 `editedText` 只记了最后一次选中的「Z」。重开卡片必须带出整段「ZY」。
        val rev = TextRevision(
            bookId = 1,
            chapter = 0,
            charStart = 2,
            charEnd = 4,
            text = "ZY",
            originalText = "cd",
            editedText = "Z",
            blockKinds = listOf("p"),
            editedBlocks = listOf("p"),
        )
        val rc = revisionCardOf(rev)
        assertEquals("ZY", rc.plain)
        assertEquals("ZY", rc.card.text)
    }

    @Test
    fun revisionCardKeepsBlockKindsAndInlineStylesOnTheFullResult() {
        val text = "标题\uE000正文"
        val rev = TextRevision(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 5,
            text = text,
            blockKinds = listOf("h1", "p"),
            styles = listOf(RevisionStyleSpan(3, 5, code)),
        )
        val rc = revisionCardOf(rev)
        assertEquals("标题\n正文", rc.plain)
        assertEquals("# 标题\n`正文`", rc.card.text)
    }

    @Test
    fun revisionCardFallsBackToDisplayKindsWhenStoredBlockKindsAreEmpty() {
        // 只改了字、没改块形态的修订：落库的 blockKinds 是空表（语义是「没改」），不是「不知道」。
        // 重开卡片要按当前显示空间的块形态回显，不能凭空少掉 `- `（审阅视图与普通视图同一口径）。
        val rev = TextRevision(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 5,
            text = "用 val",
            editedText = "用 val",
            blockKinds = emptyList(),
        )
        val rc = revisionCardOf(rev, displayKinds = listOf("ul"))
        assertEquals("用 val", rc.plain)
        assertEquals("- 用 val", rc.card.text)
    }

    @Test
    fun revisionCardDropsTheMarkerOnAMidBlockFirstLine() {
        // 修订从段中间开始时是半截文字，承载不了块形态：显示空间给的空串照旧不写标记。
        val rev = TextRevision(
            bookId = 1,
            chapter = 0,
            charStart = 2,
            charEnd = 5,
            text = "半截",
            editedText = "半截",
            blockKinds = emptyList(),
        )
        val rc = revisionCardOf(rev, displayKinds = listOf(""))
        assertEquals("半截", rc.card.text)
    }

    @Test
    fun revisionCardKeepsTableRows() {
        val row = table(2, 2)
        val rev = TextRevision(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 8,
            text = "甲一乙一甲二乙二",
            editedText = "甲一乙一\n甲二乙二",
            editedBlocks = listOf(row.wire(wholeTable = true), row.wire()),
            rowSources = listOf(0, 1),
        )
        val rc = revisionCardOf(rev)
        assertEquals("甲一乙一\n甲二乙二", rc.plain)
        assertTrue(rc.card.text, rc.card.text.startsWith("| 甲一 | 乙一 |"))
    }

    // ------------------------------------------------ 特殊叶的形状校验（图片 / 表格）

    private fun table(vararg lengths: Int, colspan: Int = 1): TableRowInfo {
        var at = 0
        return TableRowInfo(
            lengths.mapIndexed { i, len ->
                TableCellInfo(at, at + len, header = false, colspan = if (i == 0) colspan else 1)
                    .also { at += len }
            },
        )
    }

    @Test
    fun imageCanOnlyBeDeletedOrKept() {
        val original = listOf<TableRowInfo?>(null)
        val image = listOf(true)
        assertNull(textEditShapeError(listOf(InlineMarkup.Segment("\uFFFC")), original, image))
        assertNull(textEditShapeError(listOf(InlineMarkup.Segment("")), original, image))
        assertEquals(
            "图片只能整张删除，不能改成文字",
            textEditShapeError(listOf(InlineMarkup.Segment("图")), original, image),
        )
    }

    @Test
    fun tableShapeChangesAreLeftToTheWholeTableGridCheck() {
        val original = table(2, 2)
        val tables = listOf(original)

        // 格里的字变短（「ab」→「a」）：允许。
        val shorter = TableRowInfo(
            listOf(TableCellInfo(0, 1, header = false), TableCellInfo(1, 3, header = false)),
        )
        assertNull(
            textEditShapeError(
                listOf(InlineMarkup.Segment("acd", null, shorter)),
                tables,
                emptyList(),
            ),
        )

        // 只选中几行时也可能改合并方式 / 列数：本层不拦，提交时把未选中的行一起拼回
        // 整张表再按网格校验（能铺成矩形就放行）。
        val merged = table(2, 1, colspan = 2)
        assertNull(
            textEditShapeError(
                listOf(InlineMarkup.Segment("aXc", null, merged)),
                tables,
                emptyList(),
            ),
        )
        assertNull(
            textEditShapeError(
                listOf(InlineMarkup.Segment("abcd", null, table(4))),
                tables,
                emptyList(),
            ),
        )
    }

    @Test
    fun deletingSelectedTableRowsIsLeftToTheWholeTableGridCheck() {
        // 空卡片 = 删掉选中的行；未选中的行原样保留，提交侧按整表网格校验。
        assertNull(textEditShapeError(emptyList(), listOf(table(2, 2)), emptyList()))
    }

    @Test
    fun tableMixedWithOtherParagraphsStillCannotChangeShape() {
        val original = table(2, 2)
        val merged = table(2, 1, colspan = 2)
        assertEquals(
            "要改列数或合并方式，请只选中表格的整行（不夹别的段落）",
            textEditShapeError(
                listOf(
                    InlineMarkup.Segment("aXc", null, merged),
                    InlineMarkup.Segment("段落"),
                ),
                listOf(original, null),
                emptyList(),
            ),
        )
    }

    @Test
    fun wholeTableAllowsRowChangesButChecksTheGrid() {
        // 整表选法：少一行可以（0 行 = 删整表）。
        assertNull(
            textEditShapeError(
                listOf(InlineMarkup.Segment("abcd", null, table(2, 2))),
                listOf(table(2, 2), table(2, 2)),
                emptyList(),
                wholeTable = true,
            ),
        )
        assertNull(
            textEditShapeError(
                listOf(InlineMarkup.Segment("")),
                listOf(table(2, 2), table(2, 2)),
                emptyList(),
                wholeTable = true,
            ),
        )
        // 整表选法：两行的列数对不上，拒绝。
        assertEquals(
            "表格的行列对不上：每一行的列数必须与整张表一致（合并格也不能重叠或留空）",
            textEditShapeError(
                listOf(
                    InlineMarkup.Segment("abcd", null, table(2, 2)),
                    InlineMarkup.Segment("ab", null, table(2)),
                ),
                listOf(table(2, 2), table(2, 2)),
                emptyList(),
                wholeTable = true,
            ),
        )
    }
}
