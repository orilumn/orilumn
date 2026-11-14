package orilumn.reader.data.revision

/**
 * 正文修订：把原书章内区间 [charStart, charEnd) 换成 [text]。
 *
 * 坐标与标注（`Annotation`）/ `PageSlice` / `ReadingLocator` 同源，都是**原书空间**：
 * 不含任何已经应用的修订。渲染空间（应用修订之后的文字）由排版层换算，落盘的锚点一律用本空间
 * （方案 D-32）。
 *
 * - [charStart] == [charEnd] 表示纯插入；[text] 为空串表示删除；
 * - [originalText] / [editedText] 是**清单那一行摘要的两端**：前者 = 提交时选区的原文，
 *   后者 = 读者输入的新文字（都是渲染空间、按段各存一份）。它们只用于显示，
 *   与 [charStart]/[charEnd]/[text] 这套「最小修订」互不影响——裁剪掉的公共前后缀
 *   仍会出现在这两行里，读者才能看出「改之前是什么、改之后是什么」；
 *   宿主给得出审阅空间那一段时（`ChapterRevisionMap.reviewExcerpt`，与正文审阅视图
 *   同一份数据）清单优先用它，这两行只是退路——只改了标记的修订两端一模一样，
 *   按它俩做差异一条颜色都出不来；
 * - [text] 里可能含段界字符 `U+E000`（`RevisionBlocks.SEPARATOR`）：一条跨叶修订按它切块，
 *   结果段数 = 段界数 + 1，段界本身不占字符；`\n` 仍只表示叶内 `<br>`（方案 D-34 修订版）；
 * - [delta] 是提交时实测的「渲染后长度 − 原区间长度」，用于不载入章树也能换算坐标；
 *   载入重放时会再量一次，不一致以重放值为准（只记日志，不静默改库）；
 * - [groupId] 让同一次提交产生的多条修订（段数不变时一段一条）归为一组，审阅视图以后按组显示。
 *
 * 同章内区间互不重叠、按起点排序，由排版层在提交时保证（`ChapterRevisionMap`）。
 */
data class TextRevision(
    val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    /** 审阅清单「原文」行：提交时这段选区的文字（渲染空间）。 */
    val originalText: String = "",
    /** 审阅清单「新文字」行：提交时这段输入的文字（渲染空间）。 */
    val editedText: String = "",
    /**
     * 提交时读者**拖出的整段区间**（原书空间，半开）：两端没改的公共字也算在里面。
     *
     * [charStart]/[charEnd] 是最小修订（公共前后缀被裁掉），重开修改卡时却要按**整段**定位：
     * 只给最小修订，读者看到的就是「拖好的选区下次变小 / 偏了」。整段以前靠
     * `originalText` / `editedText` 两行反推，跨段（原文按段插 `\n`、修订文字用段界字符）、
     * 空白归一、吸收过旧修订时都对不上，一退回最小修订就漂——所以提交时直接记下来。
     *
     * `null` = 老数据（仍按反推兜底），或整段本来就等于最小修订（没有裁掉公共字）。
     */
    val editRange: IntRange? = null,
    /**
     * 显式行内样式区间（作用于 [text]，含段界字符）：`null` = 老修订 / 没有样式信息，
     * 重放沿用「新字挂左邻」的旧规则；非 null（可以是空表）= 读者明确声明过这段文字的行内结构。
     */
    val styles: List<RevisionStyleSpan>? = null,
    /**
     * 显式行内样式区间（作用于 [editedText]，含输入区里的 `\n`）：审阅视图打开旧修改时
     * 按它回显标记、补回属性。`null` = 老修订没存过（按纯文本开卡，重放仍只看 [styles]）。
     *
     * 与 [styles] 的差别只在**公共前后缀**：落库的最小修订裁掉的两端，这一段结构只在这里。
     */
    val editedStyles: List<RevisionStyleSpan>? = null,
    /**
     * 块级形态（markdown 块标记的落库形态，见 `BlockKind`）：**按段**对齐，
     * 段 = [text] 用 [RevisionBlocks.SEPARATOR] 切开后的每一块。
     *
     * - 空表 = 这条修订没带块级信息（老修订）：重放只按文字走，块形态沿用原样；
     * - `""` 表示这一段不改块形态；
     * - `"p"` / `"h1"`…`"h6"` / `"quote"` / `"ul"` / `"ol"` / `"pre"` = 这一段应该长成的块。
     *
     * 重放时若与这一块现在的形态不同，才换标签 / 增删包裹层（相同就一个字节都不动）。
     */
    val blockKinds: List<String> = emptyList(),
    /**
     * 卡片输入区**每一行**的块形态（重开卡片时按它把行拼回块）。
     *
     * 与 [blockKinds] 的区别：这是「读者当时看到的输入区」，含被公共前后缀裁掉的两端；
     * 行序对齐 [editedText] 按 `\n` 切开的每一行，块内换行的后续行记 [RevisionBlocks.CONTINUATION]。
     * 空表 = 老修订（重开时没有块标记）。
     */
    val editedBlocks: List<String> = emptyList(),
    /**
     * 整表结构修订的「新行来自旧表哪一行」，与 [editedBlocks] 里的表格行按序对齐：
     * 0 起下标；`-1` = 读者新加的行。`null` = 老修订 / 非整表修订（重放按行内容自己对齐）。
     *
     * 重放靠它把**没选中的行原样留下**（连行 / 格属性、格内行内元素都不动），
     * 删掉一行后同一位置的行也不会错拿前一行的样式。
     */
    val rowSources: List<Int>? = null,
    val delta: Int = 0,
    val groupId: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    init {
        require(charStart >= 0) { "charStart 不能为负：$charStart" }
        require(charEnd >= charStart) { "charEnd 不能小于 charStart：[$charStart, $charEnd)" }
        editRange?.let { r ->
            require(r.first >= 0) { "editRange 不能为负：$r" }
            require(r.first <= charStart && r.last + 1 >= charEnd) {
                "editRange 必须包含最小修订 [$charStart, $charEnd)：$r"
            }
        }
    }

    /** 纯插入（区间为空）。 */
    val isInsertion: Boolean get() = charStart == charEnd

    /** 原书区间长度（被替换掉的字符数）。 */
    val originalLength: Int get() = charEnd - charStart

    /** 渲染后的长度（替换文本长度）。 */
    val renderedLength: Int get() = text.length

    /** 落在本修订之后的偏移量修正量（换算用，见 `ChapterRevisionMap`）。 */
    val shift: Int get() = renderedLength - originalLength
}
