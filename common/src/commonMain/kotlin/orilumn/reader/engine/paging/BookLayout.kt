package orilumn.reader.engine.paging

/**
 * Layout abstraction interface (pure geometry, the only seam between the Android rendering and
 * this layer).
 *
 * The paging algorithm ([Paginator]) relies only on this interface to cut pages by line and does
 * not care whether the backing is an Android StaticLayout or a fake JVM implementation, so the
 * paging logic can be unit-tested on the JVM independently of Android.
 *
 * Coordinate convention: line tops/bottoms are vertical coordinates within the content area (the
 * type area after removing margins); the page height `contentH` passed to [Paginator.paginate]
 * must use the same coordinate system.
 */
interface BookLayout {
    /** Total number of lines. */
    val lineCount: Int

    /** Total text length in chars (UTF-16 code-unit length). */
    val length: Int

    /** y (px) of the top of line [i] relative to the content-area top. */
    fun getLineTop(i: Int): Int

    /** y (px) of the bottom of line [i] relative to the content-area top. */
    fun getLineBottom(i: Int): Int

    /** Starting char offset of line [i]. */
    fun getLineStart(i: Int): Int

    /** Ending char offset of line [i] (exclusive, i.e. the start of the next line). */
    fun getLineEnd(i: Int): Int

    /**
     * Whether line [i] is a paragraph boundary (can serve as the paragraph-preserving line anchor
     * when cutting pages).
     * Determined by: the first line, the previous line ending in a newline, or an empty line.
     * The concrete implementation is computed by the materialization layer based on each Layout's
     * internal signals.
     */
    fun isParagraphBoundaryLine(i: Int): Boolean

    /**
     * **页面绘制帧上沿**：行 [i] 作为**页首行**时本页绘制帧的顶（与 [getLineTop] 同坐标系）。
     *
     * 口径（2026-10-15 拍板 v2「完整帧口径」）：页首元素的 `margin-top` 与页首块的 `border`/
     * `padding` 都画在**本页**（`margin-top` 不与页边距合并；首行是容器的首行时容器装饰也画全），
     * 即帧上沿 = 上一行的**帧底** [getPageFrameBottom]`(i-1)`（行 [i] 为章首行时无上文，退化为
     * [getLineTop]）。段内相邻行的行盒紧贴（段内行间差为 0），故中段开页不产生额外开销。
     *
     * **一条缺口只归一页（帧必须拼合）**：`frameBottom(i) == frameTop(i+1)` 才能让块边界缺口恰好
     * 被装它的那一页收一次。旧口径 `bottom(i)=lineTop(i+1)` 与 `top(i+1)=lineBottom(i)` 把同一段
     * 缺口**两页各收一次** ⇒ 每页白丢一行（真机症状：「明明还有空间，最后一行却被推到下一页」，
     * 且被推走的那行又把缺口带到下一页页首，缺口沿途重复累积）。
     *
     * **分页预算与绘制锚点同值**：阅读面 `ReaderPageCanvas` 的 shift 锚点（`ReaderHost.pageFrameLead`
     * = `getLineTop(首行) - getPageFrameTop(首行)`）与 [Paginator.fillWholeLines] 的预算取同一对帧，
     * 整页因此在栅格里恰好铺满（页首缺口真画在页顶、页尾装饰不越栅格）。
     */
    fun getPageFrameTop(i: Int): Int =
        if (i <= 0) getLineTop(i) else minOf(getLineTop(i), getPageFrameBottom(i - 1))

    /**
     * **页面绘制帧下沿**：行 [i] 作为**页尾行**时的帧底 = [getLineBottom]`(i)` + **页尾叶自身**
     * 的 `padding-bottom`/`border-bottom`（`margin-bottom` 属块边界缺口，归**下一页**页首缺口，见
     * [getPageFrameTop]；容器链的底边不计，理由见下）。
     *
     * 默认实现无盒信息（[lineCount] 之外只有行几何），退化为 [getLineBottom]；有盒树的实现
     * （[orilumn.reader.engine.laying.BoxBookLayout]、绘制侧 `WindowedBookLayout`）按**末行叶**
     * 自身边覆盖，重/轻两条路径各自拿**同一批叶与同一份样式**算 ⇒ 帧值逐字节相同（等价性测试锁着）。
     *
     * **刻意不算容器链（祖先容器）的 `padding/border`**：轻路径（临时页/增量窗）只有窗口内的扁平叶、
     * 看不见容器链，任何依赖容器链的口径都会让两条路径算出不同的帧——真机症状是「调参后的临时页
     * 与磁盘表页数对不上」。容器底边恰好落在页尾的少数情形，绘制侧仍按 border-box 画出、由栅格
     * 裁掉（绘制约定锁在 `BoxDrawerPageBandTest`），属**已知残留**，不是内容丢失。
     */
    fun getPageFrameBottom(i: Int): Int = getLineBottom(i)
}