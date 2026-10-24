package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.layout.ListMarkers

/**
 * 正文旁批注的落位与箭头（D-26 / D-28）。
 *
 * 这些断言只谈**旁注自己的义务**：不压四类障碍（文字 / 图形 / 表格 / 线条）、不互相重叠、
 * **整句可读优先**（放不下整句才允许截断，连一行都读不出就显示小图标）、页边距与版心内同一等候选；
 * 箭头按相对关系选直线 / 折线（最多三折）且必画（只有小图标形态与贴住墨迹时没有箭头）。
 *
 * 行几何 → 页坐标的换算（章内 Y 平移、章内字符区间 → 段内区间）在调用方完成，
 * 这里只吃**已经落到页坐标的墨迹矩形**。
 */
class NoteBubblesTest {

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        Rect(left, top, right, bottom)

    /** 线段是否穿过矩形内部（贴着边不算；用于断言引线不压障碍）。 */
    private fun segmentHits(a: Offset, b: Offset, r: Rect): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-6f) {
                if (q[i] <= 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return t0 < t1
    }

    /** 线段是否进入矩形**内部**（贴边与从边上出发不算；用于断言引线不拐回自己的框）。 */
    private fun entersInterior(a: Offset, b: Offset, r: Rect): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) return false
        val steps = max(1, (len / 2f).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = a.x + dx * t
            val y = a.y + dy * t
            if (x > r.left + 0.05f && x < r.right - 0.05f &&
                y > r.top + 0.05f && y < r.bottom - 0.05f
            ) {
                return true
            }
        }
        return false
    }

    /** 箭头折线化：直线与折线都按点列出来，供断言「不压字 / 两端接住」。 */
    private fun arrowPoints(arrow: NoteArrow): List<Offset> = when (arrow) {
        is NoteArrow.Straight -> listOf(arrow.start, arrow.end)
        is NoteArrow.Elbow -> arrow.points
    }

    private val noteMetrics = NoteMetrics.forBody(20f)
    private val noteFont = noteMetrics.em
    private val noteLine = noteMetrics.lineH
    private val notePad = noteMetrics.padTotal

    private fun candidates(
        note: NoteInk,
        freeRects: List<Rect>,
        lines: List<NoteTextLine> = note.rects
            .sortedBy { it.top }
            .mapIndexed { i, r -> NoteTextLine(rect = r, blockId = 0, index = i) },
    ) = noteCandidates(
        note = note,
        lines = lines,
        freeRects = freeRects,
        metrics = noteMetrics,
    )

    private fun noteInk(
        id: Long,
        rects: List<Rect>,
        text: String = "想法",
        argb: Int = 0xFF3366CC.toInt(),
    ) = NoteInk(annotationId = id, text = text, argb = argb, rects = rects)

    private fun place(
        notes: List<NoteInk>,
        textInk: List<Rect> = emptyList(),
        figures: List<Rect> = emptyList(),
        tables: List<Rect> = emptyList(),
        strokes: List<Rect> = emptyList(),
        contentLeft: Float = 0f,
        contentRight: Float = 600f,
        contentBottom: Float = 800f,
        pageRight: Float = 700f,
        pageBottom: Float = contentBottom,
        textLines: List<NoteTextLine> = emptyList(),
        sizer: NoteTextSizer = EstimatedNoteTextSizer,
    ) = noteBubblePlacements(
        noteInk = notes,
        obstacles = NoteObstacles(
            textInk = textInk,
            figures = figures,
            tables = tables,
            strokes = strokes,
            textLines = textLines,
        ),
        contentLeft = contentLeft,
        contentRight = contentRight,
        contentBottom = contentBottom,
        pageRight = pageRight,
        fontPx = 20f,
        pageBottom = pageBottom,
        noteTextSizer = sizer,
    )

    private fun boxOf(b: NoteBubble) = rect(
        b.leftPx,
        b.topPx,
        b.leftPx + b.widthPx,
        b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
    )

    /** 两个矩形之间的最短距离（相交为 0）。 */
    private fun gapBetween(a: Rect, b: Rect): Float {
        val dx = max(0f, max(a.left - b.right, b.left - a.right))
        val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `short line puts the note to its right`() {
        // 行只占 200 / 600：这句话后面正好空着一截，旁注就该躺在那儿。
        // 所选文字正好是行尾，引线只能从框左侧横着出去，要留出 1em 的箭头空档。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), textInk = listOf(anchor))
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        val m = NoteMetrics.forBody(20f)
        assertEquals("行尾摆法应留出箭头空档", 200f + m.sideGap, b.leftPx, 0.5f)
        assertTrue("应贴住锚点所在行，实际 top=${b.topPx}", b.topPx < 40f)
        assertEquals(1, b.maxLines)
        assertTrue(b.widthPx > 0f)
    }

    @Test
    fun `note beside a mid line selection keeps one em from the line`() {
        // 2026-10-24 用户定口：空间足够时上下左右一律先留 1em；所选文字后面还有字也一样，
        // 不再因为「引线要折线绕开后面的字」就只贴 0.1em 的细缝。
        val anchor = rect(0f, 0f, 200f, 40f)
        val tail = rect(202f, 0f, 400f, 40f)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), textInk = listOf(anchor, tail))
        val b = bubbles.single()
        val m = NoteMetrics.forBody(20f)
        assertEquals("选段中部的右侧也要先留 1em", 400f + m.preferredMargin, b.leftPx, 0.5f)
    }

    @Test
    fun `full line falls back to the gap below`() {
        // 满行右侧没地方（页面右缘就是版心右缘），往下看：段落之间的大空隙放得下整句。
        val first = rect(0f, 0f, 600f, 40f)
        val second = rect(0f, 200f, 600f, 240f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first), "用几章来等")),
            textInk = listOf(first, second),
            pageRight = 600f,
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertTrue("应落在行下方，实际 top=${b.topPx}", b.topPx >= 40f)
        assertTrue("应贴住下一行的上缘（留 margin），实际 top=${b.topPx}", b.topPx + b.maxLines * noteLine <= 200f)
        val box = boxOf(b)
        assertTrue("不该压住下一行：$box", !box.overlaps(second))
        val gap = gapBetween(box, second)
        assertTrue("框与下一行至少隔纵向 margin（障碍已外扩），实际 $gap", gap >= noteMetrics.marginY - 0.5f)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("行间空隙够放整句，不该截断", fit.complete)
    }

    @Test
    fun `long note prefers the roomier blank at the same distance`() {
        // 同等距离下，整句放得下的空白胜过只能显示一截的：行间那点缝装不下，大空白能放下。
        val anchor = rect(0f, 100f, 500f, 140f)
        val long = "为什么有时显示，有时不显示？".repeat(12)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor), text = long)), textInk = listOf(anchor))
        val b = bubbles.single()
        assertTrue("整句应能放下，实际 maxLines=${b.maxLines}", b.maxLines >= 4)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("大空白里不该截断，实际 maxLines=${b.maxLines}", fit.complete)
        val box = rect(
            b.leftPx,
            b.topPx,
            b.leftPx + b.widthPx,
            b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
        )
        assertTrue("旁注不该压住锚点墨迹：$box", !box.overlaps(anchor))
    }

    @Test
    fun `useless nearby sliver loses to a complete margin note`() {
        // 锚点右侧只有两行高、每行 3 个字的窄缝（7 个字读不全）；
        // 右侧页边距虽然远一点，但能整句放下。截断罚分 6u 让整句胜出。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), text = "为什么有时显示")),
            textInk = listOf(anchor),
            figures = listOf(rect(0f, 45f, 300f, 800f), rect(300f, 0f, 400f, 40f)),
            contentRight = 300f,
            pageRight = 400f,
        )
        val b = bubbles.single()
        assertTrue("应去右侧页边距整句显示，实际 left=${b.leftPx} top=${b.topPx}", b.leftPx >= 300f)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("整句应能读完，实际 maxLines=${b.maxLines}", fit.complete)
    }

    @Test
    fun `tall gap fits more lines than it used to`() {
        // 空白够高就多放几行，不再死卡三行（D-28 第 3 部分）。
        val first = rect(0f, 0f, 600f, 40f)
        val long = "这是一句很长的批注，用来占满整块空白。".repeat(20)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first), text = long)),
            textInk = listOf(first, rect(0f, 400f, 600f, 440f)),
            contentBottom = 800f,
        )
        val b = bubbles.single()
        assertTrue("空白区足够高时应放得下 4 行以上，实际 ${b.maxLines}", b.maxLines >= 4)
        assertTrue("框越过下一行上缘：$b", b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO <= 400f)
    }

    @Test
    fun `anchor on another page is skipped`() {
        // 本页没有它的墨迹（锚点在别的页上），也就没有「旁边」。
        val bubbles = place(
            notes = listOf(noteInk(1, emptyList())),
            textInk = listOf(rect(0f, 0f, 200f, 40f)),
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `notes never overlap each other`() {
        // 两个锚点落在同一行：两张旁注不能叠在一起。
        val anchor = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor)), noteInk(2, listOf(anchor))),
            textInk = listOf(anchor),
        )
        assertEquals(2, bubbles.size)
        val a = bubbles[0]
        val b = bubbles[1]
        val overlap = a.leftPx < b.leftPx + b.widthPx && b.leftPx < a.leftPx + a.widthPx &&
            a.topPx < b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO &&
            b.topPx < a.topPx + a.maxLines * a.fontPx * NOTE_BUBBLE_LINE_RATIO
        assertTrue("旁注互相重叠：$a / $b", !overlap)
    }

    @Test
    fun `four dense notes all read in full`() {
        // 整页排满、只有右侧页边距可用：四条短批注都要完整读出、互不重叠、按锚点顺序排开。
        val lines = (0 until 20).map { i -> rect(0f, i * 40f, 600f, i * 40f + 40f) }
        val anchors = listOf(2, 7, 12, 17).map { lines[it] }
        val texts = listOf("用几章来", "交换", "用 val 声明", "用 var 声明")
        val bubbles = place(
            notes = texts.mapIndexed { i, text -> noteInk((i + 1).toLong(), listOf(anchors[i]), text) },
            textInk = lines,
            contentBottom = 800f,
        )
        assertEquals("四条都应显示，实际=$bubbles", 4, bubbles.size)
        val boxes = bubbles.map { b ->
            rect(
                b.leftPx,
                b.topPx,
                b.leftPx + b.widthPx,
                b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
            )
        }
        for (i in bubbles.indices) {
            val b = bubbles[i]
            assertTrue("应落在右侧页边距，实际 left=${b.leftPx}", b.leftPx >= 600f - 0.5f)
            val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
            assertTrue("批注应整句读出：${b.text} / maxLines=${b.maxLines}", fit.complete)
            for (j in i + 1 until bubbles.size) {
                assertTrue("页边距里的旁注互相重叠：${boxes[i]} / ${boxes[j]}", !boxes[i].overlaps(boxes[j]))
            }
        }
        // 位置顺序与锚点顺序一致（引线最短、不交叉）。
        assertTrue(
            "旁注顺序应与锚点一致，实际 tops=${bubbles.map { it.topPx }}",
            bubbles.map { it.topPx }.zipWithNext().all { (a, b) -> a <= b },
        )
    }

    @Test
    fun `margin notes with the same anchor stack instead of hiding`() {
        // 同一行两条批注、内容区排满：两条都要在、且不重叠（谁排在哪一格由各自的阶梯决定，
        // 2026-10-24 用户定口：不再把同行批注收进同一列）。
        val lines = (0 until 20).map { i -> rect(0f, i * 40f, 600f, i * 40f + 40f) }
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(lines[7]), "第一条"),
                noteInk(2, listOf(lines[7]), "第二条"),
            ),
            textInk = lines,
            contentBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val boxes = bubbles.map { b -> boxOf(b) }
        assertTrue("两条页边距旁注不该重叠", !boxes[0].overlaps(boxes[1]))
        assertTrue("两条都不该退成小图标：$bubbles", bubbles.none { it.compact })
        for (b in bubbles) {
            assertTrue(
                "两条都该贴着这一行，实际 top=${b.topPx}",
                abs(b.topPx - 280f) <= 3 * 40f,
            )
        }
    }

    @Test
    fun `two notes on the same full line stay near it`() {
        // 整页排满、两条批注锚在同一行：一条占行右、一条占行左，都贴着这一行，谁都不退成小图标。
        val lines = (0 until 20).map { i -> rect(0f, i * 40f, 600f, i * 40f + 40f) }
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(lines[7]), "第一条"),
                noteInk(2, listOf(lines[7]), "第二条"),
            ),
            textInk = lines,
            contentBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        assertTrue("两条都该是正常旁注：$bubbles", bubbles.none { it.compact })
        assertTrue("两条不能重叠：$bubbles", !boxOf(bubbles[0]).overlaps(boxOf(bubbles[1])))
        val gap = gapBetween(boxOf(bubbles[0]), boxOf(bubbles[1]))
        assertTrue("两条至少隔纵向 margin（含外扩），实际 $gap", gap >= noteMetrics.marginY - 0.5f)
        // 两条各自贴着自己那一格，不重叠即可（2026-10-24 用户定口：取消同行收列）。
        for (b in bubbles) {
            assertTrue(
                "应贴着这一行（隔行不超过一行），实际 top=${b.topPx}",
                abs(b.topPx - 280f) <= 2 * 40f,
            )
        }
    }

    @Test
    fun `list marker gutter merges into the text line obstacle`() {
        // 圆点 + 沟槽与行框相接：合并后是一整块障碍，沟槽不会变成一条能塞旁注的假空白。
        val line = rect(120f, 100f, 300f, 140f)
        val marker = rect(100f, 100f, 120f, 140f)
        val merged = mergeTextLineRects(listOf(line, marker), 20f)
        assertEquals(1, merged.size)
        assertEquals(100f, merged.single().left, 0.01f)
        assertEquals(300f, merged.single().right, 0.01f)
    }

    @Test
    fun `list marker is an obstacle for the note box`() {
        // 页面只剩锚点左侧一条空白：圆点若不算障碍，旁注会正好压在圆点上。
        val line = rect(200f, 0f, 400f, 40f)
        val markerW = ListMarkers.shapeMarkerWidthPx(20f).toFloat()
        val markerGap = ListMarkers.markerGapPx(20f).toFloat()
        val marker = rect(200f - markerW - markerGap, 0f, 200f, 40f)

        fun placeWith(textInk: List<Rect>) = place(
            notes = listOf(noteInk(1, listOf(line), "交换")),
            textInk = textInk,
            contentLeft = 200f,
            contentRight = 400f,
            contentBottom = 40f,
            pageRight = 400f,
            pageBottom = 40f,
        ).single()

        val control = placeWith(listOf(line))
        assertTrue("对照：不算圆点时框会压住它，实际 ${boxOf(control)} vs $marker",
            boxOf(control).right > marker.left)

        val withMarker = placeWith(mergeTextLineRects(listOf(line, marker), 20f))
        assertTrue("圆点并入障碍后仍是正常旁注：$withMarker", !withMarker.compact)
        val box = boxOf(withMarker)
        assertTrue("框不该压住圆点：$box vs $marker", !box.overlaps(marker))
        val gap = gapBetween(box, marker)
        assertTrue("框与圆点至少隔横向 margin，实际 $gap", gap >= noteMetrics.marginX - 0.5f)
    }

    @Test
    fun `below blank beats a farther left blank`() {
        // 下方有完整空白、左侧页边距也能放下整句：下方罚分更低（2 对 6），先选下方。
        val anchor = rect(100f, 0f, 500f, 40f)
        val sameRowRight = rect(500f, 0f, 600f, 40f)
        val next = rect(100f, 100f, 600f, 140f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), "交换")),
            textInk = listOf(anchor, sameRowRight, next),
            contentLeft = 100f,
            pageRight = 600f,
        )
        val b = bubbles.single()
        assertTrue("应落在锚点下方，实际 top=${b.topPx}", b.topPx >= 40f)
        assertTrue("不该掉到左侧页边距，实际 left=${b.leftPx}", b.leftPx >= 100f)
    }

    @Test
    fun `above blank is used when there is no below blank`() {
        // 锚点在页面最后一行：下方没有空白，只能用上方，且贴空白底部（离正文 margin）。
        val anchor = rect(0f, 700f, 600f, 740f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), "交换")),
            textInk = listOf(anchor),
            contentBottom = 740f,
            pageRight = 600f,
            pageBottom = 740f,
        )
        val b = bubbles.single()
        assertTrue("应落在锚点上方，实际 top=${b.topPx}", b.topPx < 700f)
        assertTrue("框不该压到正文，实际 bottom=${b.topPx + b.maxLines * noteLine}", b.topPx + b.maxLines * noteLine <= 700f)
    }

    @Test
    fun `right blank clamps the box into its vertical range`() {
        // 右侧空白只有 160 高、且整行都在它的中段：垂直居中会被空白下缘挡住，框应 clamp 进空白。
        val anchor = rect(0f, 100f, 500f, 200f)
        val right = rect(510f, 0f, 600f, 160f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), "交换交换交换"),
            freeRects = listOf(right),
        )
        val best = list.first { it.complete }
        assertEquals(NoteSide.RIGHT, best.side)
        assertTrue("应落在右侧空白，实际 left=${best.rect.left}", best.rect.left >= 500f)
        assertTrue("框应被 clamp 进空白下缘，实际 bottom=${best.rect.bottom}", best.rect.bottom <= 160f + 0.5f)
        assertTrue("框不该高过空白顶部，实际 top=${best.rect.top}", best.rect.top >= 0f)
    }

    @Test
    fun `placement is deterministic`() {
        // 同一页同一输入：两次调用结果完全一致（翻页回来不闪、不跳位）。
        val lines = (0 until 20).map { i -> rect(0f, i * 40f, 600f, i * 40f + 40f) }
        val notes = listOf(
            noteInk(1, listOf(lines[3]), "用几章来"),
            noteInk(2, listOf(lines[9]), "交换"),
            noteInk(3, listOf(lines[14]), "用 val 声明"),
        )
        fun run() = place(notes = notes, textInk = lines, contentBottom = 800f)
        assertEquals(run(), run())
    }

    @Test
    fun `full page falls back to the side margin`() {
        // 整页排满（行间无空隙、行满宽、页脚无空白）：退到右侧页边距，整句都要读得出来。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            textInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertTrue("应落在右侧页边距，实际 left=${b.leftPx}", b.leftPx > 600f)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("页边距够宽时应整句显示，实际 maxLines=${b.maxLines}", fit.complete)
    }

    @Test
    fun `no space at all leaves a small icon instead of vanishing`() {
        // 连页边距都没有：不再让批注消失，改为锚点右下角的小省略号（新口径）。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            textInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
            pageRight = 604f,
        )
        val b = bubbles.single()
        assertTrue("整页没空白时应退成小图标，实际=$b", b.compact)
        assertTrue("小图标不该有箭头", b.arrow == null)
        assertTrue("小图标应贴在锚点右下角，实际 left=${b.leftPx} top=${b.topPx}",
            b.leftPx > 500f && b.topPx >= 39f)
    }

    @Test
    fun `one character wide slit becomes the small icon`() {
        // 右边距只有一个字宽：连「4 个字」都显示不了，退成小图标，不再产出省略号框。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)), "用几章来")),
            textInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
            pageRight = 620f,
        )
        val b = bubbles.single()
        assertTrue("一字宽的缝应退成小图标，实际=$b", b.compact)
        assertTrue("小图标应有实体尺寸", b.widthPx > 0f)
    }

    @Test
    fun `far footer blank wins only when it reads the whole note in one line`() {
        // 页脚有一整块能一行放下整句的空白，右侧页边距近得多但要折好几行。
        // 新口径：**折行数优先于远近**——宁可放远，也要整句一行读出，不折成窄条。
        val anchorLine = rect(0f, 0f, 300f, 30f)
        val sameRowRight = rect(380f, 0f, 600f, 30f)
        val body = (0 until 17).map { i -> rect(0f, 35f + i * 40f, 600f, 75f + i * 40f) }
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchorLine), "用几章来测试视线")),
            textInk = listOf(anchorLine, sameRowRight) + body,
            contentBottom = 720f,
            pageBottom = 800f,
        )
        val b = bubbles.single()
        assertEquals("能一行放下就不折行，实际 top=${b.topPx} lines=${b.maxLines}", 1, b.maxLines)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("必须读出整句，实际 maxLines=${b.maxLines}", fit.complete)
    }

    @Test
    fun `same line count keeps the nearer side margin`() {
        // 折行数相同时才比远近：页脚与页边距都是一行，页边距近，就该用页边距。
        val anchorLine = rect(0f, 0f, 300f, 30f)
        val sameRowRight = rect(380f, 0f, 600f, 30f)
        val body = (0 until 17).map { i -> rect(0f, 35f + i * 40f, 600f, 75f + i * 40f) }
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchorLine), "用几章")),
            textInk = listOf(anchorLine, sameRowRight) + body,
            contentBottom = 720f,
            pageBottom = 800f,
        )
        val b = bubbles.single()
        assertTrue("同是一行时应留在本行旁边（不跑到页脚），实际 left=${b.leftPx} top=${b.topPx}",
            b.leftPx > 300f && b.topPx < 200f)
    }

    @Test
    fun `one line is enough when four characters fit`() {
        // 行间空隙只够一行、页面没有右页边距：能读出 4 个字就成框。
        val anchor = rect(0f, 0f, 80f, 30f)
        val next = rect(0f, 80f, 100f, 110f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), "用几章来")),
            textInk = listOf(anchor, next),
            contentRight = 100f,
            contentBottom = 110f,
            pageRight = 100f,
            pageBottom = 110f,
        )
        val b = bubbles.single()
        assertTrue("一行也应有旁注，实际=$b", !b.compact)
        assertEquals(1, b.maxLines)
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, 1)
        assertTrue("这一行要读得出至少 4 个字，实际 ${fit.shownChars}", fit.shownChars >= 4)
    }

    @Test
    fun `small icon never covers its anchor ink`() {
        // 锚点就在页面最后一行：右下角放不下时也要绕开锚点墨迹，不能糊在字上。
        val anchor = rect(0f, 760f, 600f, 800f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = (0 until 20).map { i -> rect(0f, i * 40f, 600f, i * 40f + 40f) },
            contentBottom = 800f,
            pageRight = 604f,
        )
        val b = bubbles.single()
        assertTrue("整页排满应退成小图标，实际=$b", b.compact)
        val h = if (b.heightPx > 0f) b.heightPx else b.widthPx
        val box = rect(b.leftPx, b.topPx, b.leftPx + b.widthPx, b.topPx + h)
        assertTrue("小图标压到了锚点墨迹：box=$box ink=$anchor", !box.overlaps(anchor))
        assertTrue(
            "小图标应留在页面内：box=$box",
            box.left >= 0f && box.top >= 0f && box.right <= 604f && box.bottom <= 800f,
        )
    }

    @Test
    fun `first picker keeps its spot when a second note competes`() {
        // 两条批注抢同一块近处空白：先挑的拿走它，位置与单独放置时一模一样；后来者换到页边距。
        val anchor = rect(0f, 0f, 200f, 20f)
        val block = rect(0f, 220f, 600f, 800f)
        val alone = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = listOf(anchor, block),
        )
        val both = place(
            notes = listOf(noteInk(1, listOf(anchor)), noteInk(2, listOf(anchor))),
            textInk = listOf(anchor, block),
        )
        assertEquals(2, both.size)
        assertEquals("先挑者的位置不该被后来者挤走", alone.single().leftPx, both[0].leftPx)
        assertEquals(alone.single().topPx, both[0].topPx)
        assertTrue("两条不能重叠", !boxOf(both[0]).overlaps(boxOf(both[1])))
        assertTrue("后来者应换到别的位置", both[1].topPx != both[0].topPx || both[1].leftPx != both[0].leftPx)
    }

    @Test
    fun `same line notes each find their own spot`() {
        // 行只占 300 / 700：两条同行的批注各自独立取位——先算的那条占行右（留 1em），
        // 第二条不再被「统一在右边竖向排成一列」拽过去，而是自己落到行右下。
        val anchor = rect(0f, 0f, 300f, 40f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(anchor), "短"),
                noteInk(2, listOf(anchor), "也短"),
            ),
            textInk = listOf(anchor),
            pageRight = 700f,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val m = NoteMetrics.forBody(20f)
        assertEquals("先算那条占行右并留 1em", 300f + m.sideGap, bubbles[0].leftPx, 0.5f)
        assertTrue("两条都要能整句读完", bubbles.none { it.compact })
        assertTrue(
            "第二条独立取位，不再和右边那条叠在同一列：left=${bubbles.map { it.leftPx }}",
            bubbles[1].leftPx != bubbles[0].leftPx,
        )
        assertTrue("同行的两条不该重叠", !boxOf(bubbles[0]).overlaps(boxOf(bubbles[1])))
    }

    @Test
    fun `same row notes spread to both sides instead of one column`() {
        // 同一行两条长批注、右侧只放得下一列：先算的占行右，后算的去行左——
        // 不再为了「同一列」把它拉到同一侧（2026-10-24 用户定口：取消同行收列）。
        val row = rect(0f, 0f, 300f, 40f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(row), "第一条批注很长"),
                noteInk(2, listOf(row), "第二条批注很长"),
            ),
            textInk = listOf(row),
            figures = listOf(
                // 把 400..700 的下半段封死，留出宽而较矮的一列；300..400 仍是窄而很高的列。
                rect(400f, 250f, 700f, 800f),
            ),
            pageRight = 700f,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        assertTrue(
            "两条都要能整句读完：${bubbles.map { it.maxLines }}",
            bubbles.all { b ->
                noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines).complete
            },
        )
        assertTrue(
            "一条在行右、一条在行左，实际 left=${bubbles.map { it.leftPx }}",
            bubbles.any { it.leftPx >= 300f } && bubbles.any { it.leftPx < 100f },
        )
        assertTrue("两条不能重叠", !boxOf(bubbles[0]).overlaps(boxOf(bubbles[1])))
    }

    @Test
    fun `taken right column drops just before the left side`() {
        // 行右侧已经被别的旁注占住（2026-10-24 用户定口）：正右让到**左之前**（倒数第二位），
        // 顺序变成「右下 / 右上 / 左下 / 左上 / 下 / 上 / 右 / 左」。
        val row = rect(0f, 200f, 200f, 240f)
        fun candidate(side: NoteSide, left: Float, lines: Int = 1) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 200f, left + 60f, 200f + 24f * lines),
            freeRect = rect(left, 0f, 700f, 800f),
            lines = lines,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            stage = 0,
            distance = left - row.right,
        )

        val right = candidate(NoteSide.RIGHT, 300f)
        val left = candidate(NoteSide.LEFT, -60f)
        val below = candidate(NoteSide.BELOW, 0f)

        val free = orderedNoteCandidates(listOf(left, below, right), rightTaken = false)
        assertEquals("右侧没被占时右侧最靠前", NoteSide.RIGHT, free.first().side)

        val taken = orderedNoteCandidates(listOf(left, below, right), rightTaken = true)
        assertEquals("右侧被占后先试正下方", NoteSide.BELOW, taken[0].side)
        assertEquals("正右让到左之前", NoteSide.RIGHT, taken[1].side)
        assertEquals("左侧排到最后", NoteSide.LEFT, taken[2].side)
    }

    @Test
    fun `same row notes each stay next to their own anchor`() {
        // 同一行左右两条批注：各自贴着自己那一段文字摆，不再被「统一收进一列」拽到一起。
        val row = rect(0f, 0f, 200f, 40f)
        val left = rect(20f, 0f, 60f, 40f)
        val right = rect(120f, 0f, 160f, 40f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(left), "左边这条"),
                noteInk(2, listOf(right), "右边这条"),
            ),
            textInk = listOf(row),
            pageRight = 700f,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        assertTrue(
            "左边那条应落在锚点右侧 / 下方，不该被拉到行尾列：left=${bubbles[0].leftPx}",
            bubbles[0].leftPx < 120f,
        )
        assertTrue(
            "右边那条应落在行尾右侧：left=${bubbles[1].leftPx}",
            bubbles[1].leftPx >= 200f - 0.5f,
        )
        assertTrue(
            "两条都要贴着这一行：top=${bubbles.map { it.topPx }}",
            bubbles.all { abs(it.topPx - 40f) <= 60f || abs(it.topPx) <= 0.5f },
        )
        assertTrue("两条不能重叠", !boxOf(bubbles[0]).overlaps(boxOf(bubbles[1])))
    }

    @Test
    fun `band a few pixels short still holds the note next to its row`() {
        // 真机第 1 条：带子比一行只差几像素时，旧实现整条跳过，批注只好跑到三行以外。
        // 现在往下贴住锚点（少留这一侧的 margin），就在本行上方放下。
        val above = rect(0f, 40f, 100f, 80f)
        val row = rect(0f, 100f, 600f, 140f)
        val far = rect(0f, 0f, 600f, 20f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(row), "紧贴上方")),
            textInk = listOf(above, row),
            contentRight = 600f,
            contentBottom = 140f,
            pageRight = 620f,
            pageBottom = 140f,
        )
        val b = bubbles.single()
        assertTrue("不该退成小图标：$b", !b.compact)
        assertEquals(1, b.maxLines)
        val bottom = b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO
        assertTrue("应紧贴本行上方，实际 top=${b.topPx} bottom=$bottom", b.topPx > 60f && bottom <= 100.5f)
        assertTrue("不该跑到页顶那条空白：top=${b.topPx}", b.topPx > far.bottom)
    }

    @Test
    fun `two notes on one row split above and below the row`() {
        // 同一行两条批注：各自独立取位。右边那条先算，落在这行右下；左边那条的右下已被
        // 右边那条占住，就自己改落在这行右上——不再做「横向滑移 / 躲到旁边重新折行」。
        val row = rect(0f, 100f, 600f, 140f)
        val right = rect(300f, 100f, 400f, 140f)
        val left = rect(100f, 100f, 200f, 140f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(left), "左边这条批注写得比较长一些再多几个字"),
                noteInk(2, listOf(right), "右边这条也长"),
            ),
            textInk = listOf(row),
            contentBottom = 600f,
            pageBottom = 600f,
        )
        assertEquals(2, bubbles.size)
        val l = bubbles.first { it.text.startsWith("左边") }
        val r = bubbles.first { it.text.startsWith("右边") }
        assertTrue("两条都要能整句读完", !l.compact && !r.compact)
        assertTrue("右边那条落在这行下方：top=${r.topPx}", r.topPx >= row.bottom - 0.5f)
        assertTrue("左边那条改落在这行上方：top=${l.topPx}", l.topPx + l.maxLines * noteLine <= row.top + 2f)
        assertTrue("两条不能重叠：${l.leftPx} / ${r.leftPx}", !boxOf(l).overlaps(boxOf(r)))
    }

    @Test
    fun `below right box shifts right of the selection first character`() {
        // 真机第 3 条 + 2026-10-24 用户定口：右下、右上相对**末行第一个字右移一个汉字**，
        // 再加 1em 近侧留白——不再是「贴住整个选区右缘」。
        val row = rect(0f, 100f, 600f, 140f)
        val anchor = rect(300f, 100f, 400f, 140f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), "用几章来")),
            textInk = listOf(row),
            contentRight = 600f,
            contentBottom = 500f,
            pageRight = 620f,
            pageBottom = 500f,
        )
        val b = bubbles.single()
        assertTrue("应落在锚点下方：top=${b.topPx}", b.topPx >= 140f - 0.5f)
        // 选区只有一个矩形，首字左缘就是 anchor.left；一个汉字按正文字号 20px 算。
        val expectedLeft = anchor.left + 20f + noteMetrics.preferredMargin
        assertTrue(
            "框左缘要落在首字右移一个汉字再加 1em 处：left=${b.leftPx} 期望≈$expectedLeft",
            b.leftPx >= expectedLeft - 0.5f,
        )
        assertTrue("框不该整条跑到选区右边太远：left=${b.leftPx}", b.leftPx < anchor.right + 3 * noteFont)
    }

    @Test
    fun `same line notes are computed from right to left`() {
        // 同一行两条批注抢页边距里唯一那一格：最右的那条先算、先拿走，左边那条只能另找地方。
        // 行是满行（600 宽），页边距只剩行右侧上方一小块（下方用图形封死）。
        val row = rect(0f, 0f, 600f, 40f)
        val leftInk = rect(0f, 0f, 300f, 40f)
        val rightInk = rect(300f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(leftInk), "短"),
                noteInk(2, listOf(rightInk), "也短"),
            ),
            textInk = listOf(row),
            figures = listOf(rect(600f, 40f, 700f, 800f)),
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val right = bubbles.single { it.annotationId == 2L }
        val left = bubbles.single { it.annotationId == 1L }
        assertTrue("最右的批注应先拿到行右侧那一格，实际=$right", right.leftPx >= 600f)
        assertTrue("左边那条不该越过它去抢同一格，实际=$left", left.leftPx < 600f)
        assertTrue("任何人都不能退成小图标：$bubbles", bubbles.none { it.compact })
    }

    @Test
    fun `multi line anchor uses the last line as its base`() {
        // 多行选区以**末行**为基准行：跨行批注和末行上的另一条各自独立取位。
        // 末行那条（锚点更靠右）先算，占行右；跨行批注的右侧已被占，改落在末行右下。
        val row0 = rect(0f, 0f, 600f, 40f)
        val row1 = rect(0f, 40f, 600f, 80f)
        val spans = rect(0f, 0f, 300f, 40f)
        val tail = rect(0f, 40f, 160f, 80f)
        val onLast = rect(320f, 40f, 520f, 80f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(spans, tail), "跨行"),
                noteInk(2, listOf(onLast), "同一末行"),
            ),
            textInk = listOf(row0, row1),
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val spanning = bubbles.single { it.annotationId == 1L }
        val lastLine = bubbles.single { it.annotationId == 2L }
        assertTrue("末行那条应在右侧页边距：$lastLine", lastLine.leftPx >= 600f)
        assertTrue("跨行批注以末行为基准、落在末行下方：$spanning", spanning.topPx >= row1.bottom - 0.5f)
        assertTrue("两条不能重叠", !boxOf(spanning).overlaps(boxOf(lastLine)))
        // 先算的那条不因另一条而挪位（2026-10-24 用户定口：独立取位）。
        val solo = place(
            notes = listOf(noteInk(2, listOf(onLast), "同一末行")),
            textInk = listOf(row0, row1),
            contentBottom = 800f,
            pageBottom = 800f,
        ).single()
        assertEquals("右侧那条保持自己原来的位置", solo.topPx, lastLine.topPx, 0.5f)
        assertEquals("右侧那条保持自己原来的位置", solo.leftPx, lastLine.leftPx, 0.5f)
    }

    @Test
    fun `upper row note keeps its spot when a lower row packs a group`() {
        // 行自上而下是硬顺序：上面那行的单条先算好，下面两行的那组不能先把它挤走。
        // 上面这行不是所在段落的末行，按新口径不再试正右，它按自己的阶梯落位即可；
        // 关键是位置与「单独放置」时完全一致。
        val row0 = rect(0f, 0f, 600f, 40f)
        val row1 = rect(0f, 60f, 600f, 100f)
        val leftInk = rect(0f, 60f, 300f, 100f)
        val rightInk = rect(300f, 60f, 600f, 100f)
        val figures = listOf(rect(600f, 100f, 700f, 800f))
        val alone = place(
            notes = listOf(noteInk(1, listOf(row0), "上")),
            textInk = listOf(row0, row1),
            figures = figures,
            contentBottom = 800f,
            pageBottom = 800f,
        ).single()
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(row0), "上"),
                noteInk(2, listOf(leftInk), "下左"),
                noteInk(3, listOf(rightInk), "下右"),
            ),
            textInk = listOf(row0, row1),
            figures = figures,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(3, bubbles.size)
        val upper = bubbles.single { it.annotationId == 1L }
        assertTrue("上面那行不该退成小图标：$upper", !upper.compact)
        assertEquals("上面那行的位置不该被下面那组挤走", alone.leftPx, upper.leftPx, 0.5f)
        assertEquals("上面那行的位置不该被下面那组挤走", alone.topPx, upper.topPx, 0.5f)
        assertTrue("上面那行应先于下面那组落位：top=${upper.topPx}", upper.topPx < row1.top + 0.5f)
    }

    @Test
    fun `single line beats a nearer wrapped column`() {
        // 行右边只有一条窄而高的空白，会把这句话折成好几行；行下方有宽空白能一行放下。
        // 同一圈里不折行优先：宁可去下方，也不为了「更靠右」先折行。
        val row = rect(0f, 200f, 200f, 240f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(row), "这条批注不该折行")),
            textInk = listOf(row),
            figures = listOf(
                rect(260f, 0f, 700f, 800f), // 右边只留 200..260 这条窄列
                rect(0f, 400f, 260f, 800f), // 下方空白到 400 为止
            ),
            contentBottom = 400f,
            pageBottom = 400f,
        )
        val b = bubbles.single()
        assertEquals("能一行放下就不该折行", 1, b.maxLines)
        assertTrue("应落在行下方，实际 top=${b.topPx} left=${b.leftPx}", b.topPx >= 240f - 0.5f)
    }

    @Test
    fun `fewer wrapped lines win within the same ring`() {
        // 同一圈里行数少的优先：右边折 3 行、下方折 2 行时，取下方 2 行。
        val anchor = rect(0f, 100f, 200f, 140f)
        val text = "折行优先级实在太高"
        val right = rect(200f, 100f, 267.68f, 800f)
        val below = rect(0f, 141.44f, 110.88f, 800f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = text),
            freeRects = listOf(right, below),
        )
        val first = list.first { it.complete }
        assertEquals("两行应压过三行", 2, first.lines)
        // 这块空白同时能出「右下」（首字右移一个汉字之后）与「正下」，两者行数相同；
        // 2026-10-24 用户定口后方向名次把右下排在正下之前。
        assertEquals("行数相同时右下压过正下", NoteSide.BELOW_RIGHT, first.side)
    }

    @Test
    fun `figure is an obstacle too`() {
        // 插图、背景图都算图形：铺满其他空白时，旁注退成小图标，不压图。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = listOf(anchor),
            figures = listOf(rect(200f, 0f, 600f, 800f), rect(0f, 41f, 200f, 800f)),
            pageRight = 600f,
        )
        val b = bubbles.single()
        assertTrue("图形占满时应退成小图标，实际=$b", b.compact)
    }

    @Test
    fun `block color is blank but its border is not`() {
        // 纯底色 / 内边距 / 页边距算空白；边框线与背景图、插图一起当障碍。
        val plain = PageBackground(left = 0, yTop = 0, yBottom = 100, right = 600, argb = 0x22FF0000)
        val plainObstacles = noteObstacles(
            textInk = emptyList(), pageImages = null, pageBackgrounds = listOf(plain),
            shift = 0, contentLeft = 0f,
        )
        assertTrue("块底色不该挡旁注", plainObstacles.all.isEmpty())

        val bordered = plain.copy(border = true)
        val borderedObstacles = noteObstacles(
            textInk = emptyList(), pageImages = null, pageBackgrounds = listOf(bordered),
            shift = 0, contentLeft = 0f,
        )
        assertEquals(1, borderedObstacles.strokes.size)

        val backdrop = plain.copy(bgSrc = "paper.png", bgChapterHref = "ch1.xhtml")
        val backdropObstacles = noteObstacles(
            textInk = emptyList(), pageImages = null, pageBackgrounds = listOf(backdrop),
            shift = 0, contentLeft = 0f,
        )
        assertEquals(1, backdropObstacles.figures.size)
    }

    @Test
    fun `image geometry lands on the page`() {
        val image = PageImage(
            src = "a.png", chapterHref = "ch1.xhtml",
            xLeft = 10, yTop = 120, yBottom = 220, widthPx = 100, heightPx = 100,
        )
        val obstacles = noteObstacles(
            textInk = emptyList(), pageImages = listOf(image), pageBackgrounds = null,
            shift = 100, contentLeft = 20f,
        )
        val figure = obstacles.figures.single()
        assertEquals(30f, figure.left, 0.01f)
        assertEquals(20f, figure.top, 0.01f)
        assertEquals(130f, figure.right, 0.01f)
        assertEquals(120f, figure.bottom, 0.01f)
    }

    @Test
    fun `bubble keeps the annotation color`() {
        val anchor = rect(0f, 0f, 200f, 40f)
        val argb = 0xFF123456.toInt()
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), argb = argb)),
            textInk = listOf(anchor),
        )
        assertEquals(argb, bubbles.single().argb)
    }

    @Test
    fun `shifted ink lands on the page`() {
        // 章内 Y 平移由调用方完成：传进来已经是页坐标，旁注就按它放（不再自己减 shift）。
        val anchor = rect(20f, 0f, 220f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = listOf(anchor),
        )
        assertTrue(bubbles.single().topPx < 40f)
    }

    @Test
    fun `dense page still shows the note in the side margin`() {
        // 复现用户报的「有时不显示」：整页排满、行与行之间没有空隙时，
        // 旁注仍应出现在左右页边距里，而不是整条消失。
        val lines = (0 until 20).map { i ->
            rect(0f, i * 40f, 600f, i * 40f + 40f)
        }
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(lines[7]), text = "为什么有时显示，有时不显示？")),
            textInk = lines,
            contentBottom = 800f,
        )
        val b = bubbles.single()
        val box = rect(
            b.leftPx,
            b.topPx,
            b.leftPx + b.widthPx,
            b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
        )
        assertTrue("应落在右侧页边距，实际 left=${b.leftPx}", b.leftPx >= 600f - 0.5f)
        assertTrue("旁注不该压正文", lines.none { it.overlaps(box) })
        val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
        assertTrue("页边距里应整句显示，实际 maxLines=${b.maxLines}", fit.complete)
    }

    @Test
    fun `margin notes keep anchor order when anchors are five pixels apart`() {
        // 两个锚点只隔 5px、且所在行排满：页边距里的框按锚点顺序排开，互不重叠。
        val textInk = ArrayList<Rect>()
        var y = 0f
        while (y < 800f) textInk += rect(0f, y, 600f, min(y + 40f, 800f)).also { y += 40f }
        // 把中间两行换成相隔 5px 的短句行（仍排满整宽，视线到页边距不被挡）。
        val first = rect(0f, 600f, 600f, 640f)
        val second = rect(0f, 645f, 600f, 685f)
        textInk.removeAll { it.top >= 600f && it.bottom <= 690f }
        textInk += first
        textInk += second
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first), "第一条"), noteInk(2, listOf(second), "第二条")),
            textInk = textInk,
            contentBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val boxes = bubbles.map { b ->
            rect(
                b.leftPx,
                b.topPx,
                b.leftPx + b.widthPx,
                b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
            )
        }
        assertTrue("两条页边距旁注不该重叠", !boxes[0].overlaps(boxes[1]))
        assertTrue("上面的锚点应排在上面", bubbles[0].topPx <= bubbles[1].topPx)
        for (b in bubbles) {
            assertTrue("应落在右侧页边距，实际 left=${b.leftPx}", b.leftPx >= 600f - 0.5f)
            val fit = noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines)
            assertTrue("锚点很近时也要整句显示：${b.text}", fit.complete)
        }
    }

    @Test
    fun `placement does not mutate its inputs`() {
        // 纯函数只读输入：锚点墨迹、障碍列表都不该被旁注布点改写。
        val anchor = rect(0f, 0f, 200f, 40f)
        val inkRects = listOf(anchor)
        val note = noteInk(1, inkRects, "想法")
        val textInk = listOf(anchor, rect(0f, 80f, 600f, 120f))
        val inkCopy = inkRects.toList()
        val textCopy = textInk.toList()
        place(notes = listOf(note), textInk = textInk)
        assertEquals(inkCopy, note.rects)
        assertEquals(textCopy, textInk)
    }

    @Test
    fun `blank note is not a bubble`() {
        // 空批注不占页面：没写字就没有要显示的小字。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), text = "   ")),
            textInk = listOf(anchor),
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `right side note draws a horizontal straight line`() {
        val bubble = rect(300f, 0f, 400f, 40f)
        val ink = rect(0f, 0f, 100f, 40f)
        val straight = routeNoteArrow(bubble, listOf(ink)) as NoteArrow.Straight
        // 两端必须接住：起点在框左边中点，终点在文字右边中点（gap = 0）。
        assertEquals(Offset(300f, 20f), straight.start)
        assertEquals(Offset(100f, 20f), straight.end)
    }

    @Test
    fun `right side note bends when a word sits between`() {
        val bubble = rect(300f, 0f, 400f, 40f)
        val ink = rect(0f, 0f, 100f, 40f)
        val blocker = rect(150f, 10f, 250f, 60f)
        val elbow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            glyphs = listOf(blocker),
            lineH = 20f,
        ) as NoteArrow.Elbow
        // 两端仍接住：框左边中点 → 落在文字右边上（竖直进入文字右边，y 可在文字高度内挪动）。
        assertEquals(Offset(300f, 20f), elbow.start)
        val end = elbow.points.last()
        assertEquals("终点要落在文字右边", ink.right, end.x)
        assertTrue("终点 y 要落在文字高度里：$end", end.y in ink.top..ink.bottom)
        val pts = elbow.points
        for (i in 1 until pts.size) {
            assertTrue(
                "折线不该压住中间的字：${pts[i - 1]} → ${pts[i]}",
                !segmentHits(pts[i - 1], pts[i], blocker),
            )
        }
    }

    @Test
    fun `left side note draws a horizontal straight line`() {
        val bubble = rect(0f, 0f, 100f, 40f)
        val ink = rect(300f, 0f, 400f, 40f)
        val straight = routeNoteArrow(bubble, listOf(ink)) as NoteArrow.Straight
        assertEquals(Offset(100f, 20f), straight.start)
        assertEquals(Offset(300f, 20f), straight.end)
    }

    @Test
    fun `left side note bends when the line is blocked`() {
        val bubble = rect(0f, 0f, 100f, 40f)
        val ink = rect(300f, 0f, 400f, 40f)
        val blocker = rect(150f, 10f, 250f, 60f)
        val elbow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            glyphs = listOf(blocker),
            lineH = 20f,
        ) as NoteArrow.Elbow
        assertEquals(Offset(100f, 20f), elbow.start)
        val end = elbow.points.last()
        assertEquals("终点要落在文字左边", ink.left, end.x)
        assertTrue("终点 y 要落在文字高度里：$end", end.y in ink.top..ink.bottom)
        val pts = elbow.points
        for (i in 1 until pts.size) {
            assertTrue(
                "折线不该压住中间的字：${pts[i - 1]} → ${pts[i]}",
                !segmentHits(pts[i - 1], pts[i], blocker),
            )
        }
    }

    @Test
    fun `note above its text drops a vertical line from the bottom edge`() {
        val bubble = rect(0f, 0f, 100f, 40f)
        val ink = rect(0f, 100f, 100f, 140f)
        val straight = routeNoteArrow(bubble, listOf(ink)) as NoteArrow.Straight
        assertEquals(Offset(50f, 40f), straight.start)
        assertEquals(Offset(50f, 100f), straight.end)
    }

    @Test
    fun `note below its text rises from the top edge`() {
        val bubble = rect(0f, 200f, 100f, 240f)
        val ink = rect(0f, 100f, 100f, 140f)
        val straight = routeNoteArrow(bubble, listOf(ink)) as NoteArrow.Straight
        assertEquals(Offset(50f, 200f), straight.start)
        assertEquals(Offset(50f, 140f), straight.end)
    }

    @Test
    fun `narrow overlap still routes orthogonally and lands perpendicular`() {
        // 水平投影只重叠 0.5px（不足 1px），属于斜靠：不许画斜线，
        // 仍要「横平竖直 + 第一段 ⊥ 框边 + 最后一段 ⊥ 文字框边」。
        val bubble = rect(100.2f, 60f, 200f, 100f)
        val ink = rect(0f, 0f, 100.7f, 40f)
        val arrow = routeNoteArrow(bubble, listOf(ink))
        val points = arrowPoints(arrow!!)
        assertTrue("至少要接住两端：$points", points.size >= 2)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            assertTrue("引线必须横平竖直：$a → $b", abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f)
        }
        // 起点在框上边（竖着出去），终点落在文字底边（竖着进来）。
        assertEquals("起点要在框的上边", bubble.top, points.first().y)
        assertTrue("起点 x 要落在框的横向范围里", points.first().x in bubble.left..bubble.right)
        assertEquals("终点要落在文字下边", ink.bottom, points.last().y)
        assertTrue("终点 x 要落在文字的横向范围里", points.last().x in ink.left..ink.right)
    }

    @Test
    fun `squeezed vertical leader turns into a left side L`() {
        // 真机第 2 条：框贴着文字、中间只剩几像素时，竖线几乎看不见（「讨论」那条 18px）。
        // 改为从框左边横着出去、到锚点起点字的左缘再竖下去——L 形至少看得见。
        val ink = rect(1000f, 80f, 1160f, 116f)
        val bubble = rect(1100f, 120f, 1500f, 160f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            lineH = 20f,
            side = NoteSide.BELOW_RIGHT,
        )
        val pts = arrowPoints(arrow!!)
        assertEquals("挤在一起的短竖线要改走 L 形：$pts", 3, pts.size)
        assertEquals("第一段从框左边横着出去", bubble.left, pts[0].x, 0.01f)
        assertTrue("第一段必须是横线：$pts", abs(pts[0].y - pts[1].y) < 0.01f)
        assertEquals("竖段贴着锚点起点字的左缘", ink.left, pts[1].x, 0.01f)
        assertEquals("末端接住文字底边", ink.bottom, pts[2].y, 0.01f)
        assertTrue("第一段要比短竖线长得多：$pts", abs(pts[1].x - pts[0].x) > 4 * abs(pts[2].y - pts[1].y))
    }

    @Test
    fun `leader crossing two lines goes through the middle of the gap`() {
        // 真机第 6 条：从两行字之间横穿时要走**正中间**，不贴着任何一边。
        val anchorGlyph = rect(0f, 100f, 40f, 140f)
        val rowOne = listOf(
            anchorGlyph,
            rect(60f, 100f, 100f, 140f),
            rect(120f, 100f, 160f, 140f),
            rect(180f, 100f, 220f, 140f),
        )
        val rowTwo = listOf(
            rect(0f, 152f, 40f, 192f),
            rect(60f, 152f, 100f, 192f),
            rect(120f, 152f, 160f, 192f),
        )
        val bubble = rect(260f, 100f, 360f, 140f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(anchorGlyph),
            glyphs = rowOne + rowTwo,
            lineH = 20f,
            side = NoteSide.RIGHT,
        )
        val pts = arrowPoints(arrow!!)
        for (i in 1 until pts.size) {
            for (g in rowOne + rowTwo) {
                assertTrue("引线不该压字：${pts[i - 1]} → ${pts[i]} 撞 $g", !segmentHits(pts[i - 1], pts[i], g))
            }
        }
        val crossing = pts.zipWithNext()
            .filter { (a, b) -> abs(a.y - b.y) < 0.01f && abs(a.x - b.x) > 1f }
            .map { (a, _) -> a.y }
            .firstOrNull { it > 140.5f && it < 151.5f }
        assertTrue("应在两行之间横穿，实际 $pts", crossing != null)
        assertEquals("横穿要走两行墨迹的正中间", 146f, crossing!!, 1.5f)
    }

    @Test
    fun `three bend route has four segments`() {
        // 两折无法避开时，三折（4 段、3 个拐点）也要能给出横平竖直的候选。
        val routes = noteElbowRoutes(
            start = Offset(0f, 0f),
            end = Offset(200f, 100f),
            obstacles = listOf(rect(80f, 30f, 120f, 70f)),
            bends = 3,
        )
        val first = routes.first()
        assertEquals("三折必须正好 4 段：$first", 5, first.size)
        for (i in 1 until first.size) {
            val a = first[i - 1]
            val b = first[i]
            assertTrue("折线必须横平竖直：$a → $b", abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f)
            assertTrue("折点不该退化：$a → $b", abs(a.x - b.x) > 0.01f || abs(a.y - b.y) > 0.01f)
        }
    }

    @Test
    fun `arrow takes one bend around a blocked straight line`() {
        // 直线被中间的字挡住；一折（横 + 竖）能绕开，就不该退到两折。
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 60f),
            inkRects = listOf(rect(300f, 100f, 400f, 130f)),
            glyphs = listOf(rect(180f, 35f, 220f, 160f)),
        )
        val elbow = arrow as NoteArrow.Elbow
        assertEquals("一折就够，不该用两折：${elbow.points}", 3, elbow.points.size)
        val corner = elbow.points[1]
        val orthogonalCorner = (abs(corner.x - elbow.start.x) < 0.01f && abs(corner.y - elbow.end.y) < 0.01f) ||
            (abs(corner.y - elbow.start.y) < 0.01f && abs(corner.x - elbow.end.x) < 0.01f)
        assertTrue("拐点必须与两端正交对齐：${elbow.points}", orthogonalCorner)
        for (i in 1 until elbow.points.size) {
            assertTrue(
                "引线不该压住别的字：${elbow.points}",
                !segmentHits(elbow.points[i - 1], elbow.points[i], rect(180f, 35f, 220f, 160f)),
            )
            assertTrue(
                "引线不该拐回自己的框：${elbow.points}",
                !entersInterior(elbow.points[i - 1], elbow.points[i], rect(0f, 0f, 100f, 60f)),
            )
        }
    }

    @Test
    fun `arrow takes two bends when both one bend shapes are blocked`() {
        // 三面围住的开口框：一折怎么走都要横穿左墙，只能先竖走、横穿、再竖下来。
        val walls = listOf(
            rect(150f, -20f, 250f, 10f),
            rect(150f, 45f, 250f, 70f),
            rect(150f, -20f, 165f, 70f),
        )
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 20f, 400f, 35f)),
            solids = walls,
            lineH = 20f,
        )
        val elbow = arrow as NoteArrow.Elbow
        assertTrue("绕开三面墙至少要一个拐点：${elbow.points}", elbow.points.size >= 3)
        // 首末段必须各自垂直于框边，绕过一整条竖带要 4 折（5 段）。
        assertTrue("最多 4 折（5 段）：${elbow.points}", elbow.points.size <= 6)
        assertTrue(
            "得从墙外绕过去：${elbow.points}",
            elbow.points.any { it.y <= -20f || it.y >= 70f },
        )
        for (i in 1 until elbow.points.size) {
            val a = elbow.points[i - 1]
            val b = elbow.points[i]
            assertTrue("引线必须横平竖直：$a → $b", abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f)
            for (w in walls) {
                assertTrue("引线不该压住图形：$a → $b", !segmentHits(a, b, w))
            }
        }
    }

    @Test
    fun `arrow slips between two neighbouring characters`() {
        // 同一行两个字之间留出空隙：竖着落下来的引线可以从缝里穿过（行框会把它整行堵死）。
        val left = rect(150f, 100f, 170f, 120f)
        val right = rect(178f, 100f, 198f, 120f)
        val arrow = routeNoteArrow(
            bubble = rect(160f, -40f, 180f, 0f),
            inkRects = listOf(rect(160f, 200f, 180f, 220f)),
            glyphs = listOf(left, right),
        )
        val straight = arrow as NoteArrow.Straight
        assertTrue("引线不该压住左边的字", !segmentHits(straight.start, straight.end, left))
        assertTrue("引线不该压住右边的字", !segmentHits(straight.start, straight.end, right))
        // 穿过字行那一段必须落在两字的缝里（170..178）。
        fun xAt(y: Float) = straight.start.x +
            (straight.end.x - straight.start.x) * (y - straight.start.y) / (straight.end.y - straight.start.y)
        val top = xAt(100f)
        val bottom = xAt(120f)
        assertTrue(
            "引线应从两字之间穿过：x(100)=$top x(120)=$bottom，实际=$straight",
            top in 170f..178f && bottom in 170f..178f,
        )
    }

    @Test
    fun `arrow routes around a punctuation mark without touching it`() {
        // 标点只占字位的一小截：线从它外侧绕过去，不把整行当黑块。
        val char = rect(150f, 100f, 170f, 120f)
        val comma = rect(176f, 112f, 180f, 122f)
        val arrow = routeNoteArrow(
            bubble = rect(168f, -40f, 188f, 0f),
            inkRects = listOf(rect(168f, 200f, 188f, 220f)),
            glyphs = listOf(char, comma),
        )
        assertTrue("必须画箭头，实际=$arrow", arrow != null)
        val points = arrowPoints(arrow!!)
        for (i in 1 until points.size) {
            assertTrue("引线不该压住标点墨迹", !segmentHits(points[i - 1], points[i], comma))
            assertTrue("引线不该压住正文墨迹", !segmentHits(points[i - 1], points[i], char))
        }
        // 终点必须接住目标墨迹的边。
        val end = points.last()
        assertTrue("终点要落在墨迹边上：$end", abs(end.y - 200f) < 0.01f && end.x in 168f..188f)
    }

    @Test
    fun `note bubble text always carries an arrow`() {
        // 除小图标形态外「旁注有、箭头无」不允许出现。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), textInk = listOf(anchor))
        val b = bubbles.single()
        assertTrue("文字框必须有箭头，实际=$b", !b.compact && b.arrow != null)
    }

    @Test
    fun `right side arrow lands on the end of the selection`() {
        // 标注在右侧：箭头指向所选文字的**末端**（最右那个字），不再指向起点。
        val bubble = rect(320f, 100f, 420f, 140f)
        val start = rect(100f, 100f, 140f, 140f)
        val end = rect(260f, 100f, 300f, 140f)
        val arrow = routeNoteArrow(bubble, listOf(start, end), side = NoteSide.RIGHT)
        assertTrue("应画得出箭头", arrow != null)
        val tip = arrowPoints(arrow!!).last()
        assertEquals("终点落在文字边上", 300f, tip.x, 0.5f)
    }

    @Test
    fun `below right arrow lands on the bottom edge near the end`() {
        // 标注在右下方：终点在文字**下边靠末端**，而不是绕到最左那个字的下边。
        val bubble = rect(140f, 150f, 420f, 190f)
        val start = rect(100f, 100f, 140f, 140f)
        val end = rect(260f, 100f, 300f, 140f)
        val arrow = routeNoteArrow(bubble, listOf(start, end), side = NoteSide.BELOW_RIGHT)
        assertTrue("应画得出箭头", arrow != null)
        val tip = arrowPoints(arrow!!).last()
        assertEquals("终点落在文字下边", 140f, tip.y, 0.5f)
        assertTrue("终点要靠末端，实际 x=${tip.x}", tip.x >= 290f)
    }

    @Test
    fun `above right arrow lands on the top edge near the end`() {
        // 标注在右上方：终点在文字**上边靠末端**。
        val bubble = rect(140f, 50f, 420f, 90f)
        val start = rect(100f, 100f, 140f, 140f)
        val end = rect(260f, 100f, 300f, 140f)
        val arrow = routeNoteArrow(bubble, listOf(start, end), side = NoteSide.ABOVE_RIGHT)
        assertTrue("应画得出箭头", arrow != null)
        val tip = arrowPoints(arrow!!).last()
        assertEquals("终点落在文字上边", 100f, tip.y, 0.5f)
        assertTrue("终点要靠末端，实际 x=${tip.x}", tip.x >= 290f)
    }

    @Test
    fun `above-right beats below within the same ring and line count`() {
        // 同一环、同样不折行时，右上压过正下（2026-10-24 用户定口的新方向顺序
        // 「右 → 右下 → 右上 → 左下 → 左上 → 下 → 上 → 左」）。
        fun candidate(side: NoteSide, ring: Int, left: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 0f, left + 60f, 24f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = ring,
            stage = 0,
            distance = 0f,
        )
        val aboveRight = candidate(NoteSide.ABOVE_RIGHT, 1, 100f)
        val below = candidate(NoteSide.BELOW, 1, 40f)
        val ordered = orderedNoteCandidates(listOf(aboveRight, below), rightTaken = false)
        assertEquals("右上应排在正下之前", NoteSide.ABOVE_RIGHT, ordered.first().side)
    }

    @Test
    fun `arrow is still drawn when every route is blocked`() {
        // 一堵墙横在中间：三种折线全撞上，也要按「穿得最少」照样画，不能留空。
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 0f, 400f, 40f)),
            solids = listOf(rect(150f, -100f, 160f, 300f)),
        )
        assertTrue("除小图标外箭头必画，实际=$arrow", arrow != null)
    }

    @Test
    fun `arrow does not treat its own ink as an obstacle`() {
        // 锚点自己的墨迹当然不能挡住指向它的箭头。
        val ink = rect(300f, 0f, 400f, 40f)
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(ink),
            glyphs = listOf(ink),
        )
        assertTrue(arrow is NoteArrow.Straight)
    }

    @Test
    fun `no arrow when the bubble already touches the ink`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(100.4f, 0f, 200f, 40f)),
        )
        assertTrue("贴着墨迹时没有可画的箭头", arrow == null)
    }

    @Test
    fun `arrow routing is deterministic`() {
        val glyphs = listOf(rect(150f, 10f, 250f, 30f), rect(150f, 110f, 250f, 130f))
        fun run() = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 100f, 400f, 140f)),
            glyphs = glyphs,
        )
        assertEquals(run(), run())
    }

    @Test
    fun `tall narrow blank is tried when the wide one is only one line high`() {
        // 右侧同时有「宽但只有一行高」与「窄而很高」两块空白：宽的那块只能截断，
        // 窄的能折行读出整句。两个候选都要进列表，分配时整句优先选中窄的那块。
        val anchor = rect(0f, 100f, 200f, 140f)
        val text = "这条批注需要折成好几行才能完整读出而且字数还要更多一些"
        val wide = rect(200f, 100f, 600f, 120f)
        val tall = rect(220f, 100f, 320f, 400f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = text),
            freeRects = listOf(wide, tall),
        )
        assertTrue("宽的空白应产出截断候选，实际=$list", list.any { !it.complete })
        val full = list.firstOrNull { it.complete }
        assertTrue("窄而高的空白应产出整句候选，实际=$list", full != null)
        assertTrue("整句候选应落在窄空白里，实际=${full!!.rect}", full.rect.width <= 101f)
        assertTrue("整句候选应有多行，实际 lines=${full.lines}", full.lines > 1)
    }

    @Test
    fun `long note below its text points at the end of the selection`() {
        // 批注比所选文字长、又紧贴下方：右下角不用避让，贴着本行放；
        // 引线最短优先——能直线就直线，终点落在所选文字**末端**的下边（不是左端的起点）。
        val row0 = rect(0f, 0f, 600f, 20f)
        val anchor = rect(40f, 0f, 80f, 20f)
        val row1 = rect(0f, 100f, 600f, 120f)
        val lines = listOf(
            NoteTextLine(rect = row0, blockId = 0, index = 0),
            NoteTextLine(rect = row1, blockId = 0, index = 1),
        )
        val b = place(
            notes = listOf(noteInk(1, listOf(anchor), "这条批注明显比选中的文字长很多")),
            textInk = listOf(row0, row1),
            textLines = lines,
            contentRight = 600f,
            pageRight = 600f,
            contentBottom = 400f,
            pageBottom = 400f,
        ).single()
        assertEquals("能一行放下就贴着本行放", 1, b.maxLines)
        assertTrue("应贴着第 1 行，实际 top=${b.topPx}", b.topPx < 100f)
        val arrow = b.arrow
        assertTrue("应画得出箭头", arrow != null)
        val pts = arrowPoints(arrow!!)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val c = pts[i]
            assertTrue("引线必须横平竖直：$a → $c", abs(a.x - c.x) < 0.01f || abs(a.y - c.y) < 0.01f)
        }
        val end = pts.last()
        assertEquals("末段要落在文字下边", anchor.bottom, end.y)
        assertTrue("终点要靠近所选文字的末端，实际 x=${end.x}", end.x >= anchor.right - noteFont * 0.5f)
    }

    @Test
    fun `complete margin candidate beats a nearby truncated sliver`() {
        // 锚点右侧只有一条能读出 5 个字的窄缝；远处页边距能放下整句。
        // 新口径：整句可读压过就近。
        val anchor = rect(0f, 100f, 200f, 140f)
        val text = "为什么有时显示"
        val slit = rect(200f, 100f, 296f, 125f)
        // 页边距在 2u 外（参考 §1.1 的 6u 交界）：整句仍该压过近处截断。
        val margin = rect(230f, 0f, 330f, 800f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = text),
            freeRects = listOf(slit, margin),
        )
        assertTrue("窄缝的截断候选也应在列表里，实际=$list", list.any { !it.complete })
        // 排序里近处截断排在前面，但分配时先跑完整句档：整句候选必须存在且落在页边距。
        val best = list.first { it.complete }
        assertEquals(NoteSide.BELOW_RIGHT, best.side)
        assertTrue("应落在页边距，实际 ${best.rect}", best.rect.left >= 200f)
    }

    @Test
    fun `candidates with only an ellipsis or two characters are dropped`() {
        // 一行高、只放得下两个字的窄缝：宁可没有候选，也不显示「用…」。
        val anchor = rect(0f, 100f, 200f, 140f)
        val slit = rect(200f, 100f, 240f, 125f)
        val list = candidates(note = noteInk(1, listOf(anchor), text = "用几章来"), freeRects = listOf(slit))
        assertTrue("窄缝不该产出候选，实际=$list", list.isEmpty())
    }

    @Test
    fun `short note is not truncated when the blank fits it`() {
        val anchor = rect(0f, 0f, 200f, 40f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = "交换"),
            freeRects = listOf(rect(0f, 40f, 600f, 120f)),
        )
        val best = list.first()
        assertTrue(best.complete)
        assertEquals(1, best.lines)
        assertTrue("短批注不该带省略号，实际 width=${best.rect.width}", best.rect.width >= noteFont * 2f)
    }


    @Test
    fun `next line below beats the left margin`() {
        // 本段下一行的下方能放下，左侧页边距也能放下：阶梯里「下一行」在本段左侧之前。
        val first = rect(100f, 0f, 400f, 40f)
        val second = rect(100f, 100f, 400f, 140f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first), "交换")),
            textInk = listOf(first, second),
            contentLeft = 100f,
            contentRight = 400f,
            pageRight = 400f,
        )
        val b = bubbles.single()
        assertTrue("应先在本段下一行找位置，实际 left=${b.leftPx} top=${b.topPx}", b.leftPx >= 100f && b.topPx < 100f)
    }

    @Test
    fun `same paragraph left beats the adjacent paragraph`() {
        // 本段右侧 / 下方都被图挡住，只剩本段左侧；下一段下方虽然也有空白，但那是第 9 步。
        val row1 = rect(100f, 0f, 300f, 40f)
        val row2 = rect(100f, 40f, 300f, 80f)
        val nextBlock = rect(100f, 205f, 300f, 245f)
        val figure = rect(0f, 85f, 400f, 200f)
        val lines = listOf(
            NoteTextLine(row1, blockId = 0, index = 0),
            NoteTextLine(row2, blockId = 0, index = 1),
            NoteTextLine(nextBlock, blockId = 1, index = 2),
        )
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(row2), "交换")),
            textInk = listOf(row1, row2, nextBlock),
            figures = listOf(figure),
            contentLeft = 100f,
            contentRight = 300f,
            pageRight = 300f,
            textLines = lines,
        )
        val b = bubbles.single()
        assertTrue("本段左侧应压过相邻段：$b", !b.compact && b.leftPx < 100f)
    }

    @Test
    fun `small icon keeps the small ellipse size`() {
        // 兜底小椭圆：宽 0.5em、高 0.32em（热区另行放大，不改视觉尺寸）。
        val anchor = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = listOf(anchor, rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
            pageRight = 604f,
        )
        val b = bubbles.single()
        assertTrue("应退成小椭圆：$b", b.compact)
        assertEquals(noteFont * 0.5f, b.widthPx, 0.01f)
        assertEquals(noteFont * 0.32f, b.heightPx, 0.01f)
    }

    @Test
    fun `note text fit reports complete and truncated capacities`() {
        val width = noteFont * 4f
        val complete = noteTextFit("四个字呢", width, noteFont, maxLines = 1)
        assertTrue(complete.complete)
        assertEquals(1, complete.lines)

        val cut = noteTextFit("四个字呢还有后续", width, noteFont, maxLines = 1)
        assertTrue(!cut.complete)
        assertEquals(4, cut.shownChars)
    }

    @Test
    fun `nearer in-content blank beats the farther page margin`() {
        // 同为完整候选：版心内下方的距离更近（侧别 2 + 距离 0），压过远处页边距（侧别 0 + 距离）。
        val anchor = rect(0f, 0f, 200f, 40f)
        val inContent = rect(0f, 40f, 600f, 100f)
        val margin = rect(600f, 0f, 700f, 100f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = "交换"),
            freeRects = listOf(inContent, margin),
        )
        val best = list.first()
        assertEquals(NoteSide.BELOW_RIGHT, best.side)
        assertTrue("页边距只是偏好，不该被截断档压住", list.all { it.complete })
    }

    @Test
    fun `maximal free rects keep the blank that spans staggered cuts`() {
        // 两个错位障碍：四块互斥切分会让跨过切口的大空白消失，MaxRects 仍应保留它。
        val bounds = rect(0f, 0f, 100f, 100f)
        val free = maximalFreeRects(
            bounds = bounds,
            obstacles = listOf(rect(0f, 40f, 60f, 60f), rect(40f, 0f, 100f, 20f)),
            minW = 2f,
            minH = 2f,
        )
        assertTrue("整页下半仍是空白，实际=$free", free.any { it.width >= 99f && it.top >= 59f })
        assertTrue("被两个障碍切过的地方不该丢干净，实际=$free", free.isNotEmpty())
    }

    @Test
    fun `maximal free rects contain no other free rect`() {
        val free = maximalFreeRects(
            bounds = rect(0f, 0f, 200f, 200f),
            obstacles = listOf(
                rect(20f, 30f, 80f, 90f),
                rect(120f, 40f, 180f, 70f),
                rect(30f, 130f, 90f, 170f),
            ),
            minW = 1f,
            minH = 1f,
        )
        for (a in free) {
            for (b in free) {
                if (a == b) continue
                val inside = b.left <= a.left + 0.5f && b.top <= a.top + 0.5f &&
                    b.right >= a.right - 0.5f && b.bottom >= a.bottom - 0.5f
                assertTrue("空闲矩形不该有包含关系：$a 被 $b 包含", !inside)
            }
        }
    }

    @Test
    fun `maximal free rects drop duplicates and keep the page blank`() {
        val free = maximalFreeRects(
            bounds = rect(0f, 0f, 100f, 100f),
            obstacles = listOf(rect(10f, 10f, 20f, 20f), rect(10f, 10f, 20f, 20f)),
            minW = 1f,
            minH = 1f,
        )
        assertEquals(free.size, free.distinct().size)
        // 这个障碍只切出四块极大的空白，不会因为重复障碍再翻倍。
        assertEquals(4, free.size)
    }

    @Test
    fun `maximal free rects drop fragments below the minimum`() {
        val free = maximalFreeRects(
            bounds = rect(0f, 0f, 100f, 100f),
            obstacles = listOf(rect(0f, 0f, 99f, 99f)),
            minW = 5f,
            minH = 5f,
        )
        assertTrue("装不下一个字的碎片应被丢掉，实际=$free", free.all { it.width >= 5f && it.height >= 5f })
    }


    @Test
    fun `width estimate counts wide characters wider`() {
        val wide = estimatedNoteLines("为什么有时显示", widthPx = 100f, fontPx = 14.4f)
        val narrow = estimatedNoteLines("whynot", widthPx = 100f, fontPx = 14.4f)
        assertTrue("中文应比同字数的西文更宽，wide=$wide narrow=$narrow", wide > narrow)
        assertTrue(abs(wide - 2) <= 1)
    }

    /** 旁注字号跟**正文**走：本页行窗口以标题开头时（章首 h1 / 页顶 h2 约正文 2 倍），
     * 取首行字号会把旁注放大到比正文还大（真机：窄页边距只塞得下「用 / va…」）。 */
    @Test
    fun `note font follows body text not the heading line`() {
        fun line(text: String, size: Float) = DrawLine(
            text = text,
            range = 0 until text.length,
            yTop = 0,
            yBottom = 40,
            alignment = TextAlign.LEFT,
            fontSizePx = size,
            lineHeightRatio = 1.5f,
            tag = null,
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = 300,
        )
        val page = listOf(
            line("你好世界", 90f),
            line("这是一个打印 Hello world 的简单程序：", 45f),
            line("所有程序都需要能够存储数据，而变量可以帮助您做到这一点。", 45f),
        )
        assertEquals(45f, noteBodyFontPx(page), 0.01f)
        // 整页只有一行（都是标题）时退回那一行的字号，不能返回 0。
        assertEquals(90f, noteBodyFontPx(listOf(line("你好世界", 90f))), 0.01f)
        assertEquals(0f, noteBodyFontPx(emptyList()), 0.01f)
    }

    /** 旁注样式可脱离正文单独设置字号、字体与留白（批注样式落地时只换这里）。 */
    @Test
    fun `note style overrides font size family and spacing`() {
        val style = NoteStyle(
            fontPx = 28f,
            fontFamily = "serif",
            marginXEm = 0.5f,
            marginYEm = 0.25f,
            sideGapEm = 1f,
            edgeEm = 0.25f,
        )
        val m = NoteMetrics(style)
        assertEquals(28f, m.em, 0.01f)
        assertEquals(28f * style.lineHeightRatio, m.lineH, 0.01f)
        assertEquals(14f, m.marginX, 0.01f)
        assertEquals(7f, m.marginY, 0.01f)
        assertEquals(28f, m.sideGap, 0.01f)
        assertEquals(7f, m.edge, 0.01f)
        // 同一块空白：字号变大后可用宽变窄，尺寸跟着变（不是写死的比例）。
        val text = "字".repeat(12)
        val def = noteBoxFit(text, NoteMetrics.forBody(20f), maxWidth = 200f, maxHeight = 100f)
        val big = noteBoxFit(text, m, maxWidth = 200f, maxHeight = 100f)
        assertTrue("大字号的框应更宽/更多行：def=${def.width}x${def.lines} big=${big.width}x${big.lines}",
            big.width != def.width || big.lines != def.lines)
    }

    /** 旁注度量：字号、行高、内缩、margin 都从正文主字号推出来。 */
    @Test
    fun `note metrics derive every size from the body font`() {
        val m = NoteMetrics.forBody(20f)
        assertEquals(14.4f, m.em, 0.01f)
        assertEquals(19.872f, m.lineH, 0.01f)
        assertEquals(5.04f, m.padX, 0.01f)
        assertEquals(10.08f, m.padTotal, 0.01f)
        assertEquals(1.44f, m.marginX, 0.01f)
        assertEquals(1.44f, m.marginY, 0.01f)
        assertEquals(14.4f, m.sideGap, 0.01f)
        assertEquals(7.2f, m.edge, 0.01f)
    }

    /** 整句一行放得下：框宽 = 文字宽 + 两侧内缩，不多占空白。 */
    @Test
    fun `short note shrinks to one line inside a wide blank`() {
        val m = NoteMetrics.forBody(20f)
        val size = noteBoxFit("用几章来", m, maxWidth = 200f, maxHeight = 100f)
        assertEquals(1, size.lines)
        // 4 个全角字 = 4 × em；再加两侧内缩。
        assertEquals(57.6f + m.padTotal, size.width, 0.01f)
        assertEquals(m.lineH, size.height, 0.01f)
        assertEquals(4, size.shownChars)
        assertTrue(size.complete)
        assertTrue(size.readable)
    }

    /** 2026-10-24 用户定口：正右只对段末行生成（段落只有一行时它天然是末行）。 */
    @Test
    fun `right side is only tried on the last line of a paragraph`() {
        val row0 = rect(0f, 0f, 600f, 40f)
        val row1 = rect(0f, 40f, 600f, 80f)
        val column = rect(600f, 0f, 700f, 800f)
        val below = rect(0f, 81.44f, 700f, 800f)
        val lines = listOf(
            NoteTextLine(rect = row0, blockId = 0, index = 0),
            NoteTextLine(rect = row1, blockId = 0, index = 1),
        )
        val midLine = candidates(
            note = noteInk(1, listOf(rect(0f, 0f, 200f, 40f)), "想法"),
            freeRects = listOf(column, below),
            lines = lines,
        )
        assertTrue(
            "段中行不该试正右：${midLine.filter { it.side == NoteSide.RIGHT }}",
            midLine.none { it.side == NoteSide.RIGHT },
        )
        val lastLine = candidates(
            note = noteInk(1, listOf(rect(0f, 40f, 200f, 80f)), "想法"),
            freeRects = listOf(column, below),
            lines = lines,
        )
        assertTrue("段末行要试正右", lastLine.any { it.side == NoteSide.RIGHT })
    }

    /** 方向名次：右 → 右下 → 右上 → 左下 → 左上 → 下 → 上 → 左（2026-10-24 用户定口）。 */
    @Test
    fun `direction order runs right diagonals below above left`() {
        fun candidate(side: NoteSide) = NoteCandidate(
            annotationId = 1,
            rect = rect(100f, 100f, 160f, 124f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            stage = 0,
            distance = 5f,
        )
        val sides = listOf(
            NoteSide.LEFT, NoteSide.BELOW, NoteSide.ABOVE_LEFT,
            NoteSide.ABOVE_RIGHT, NoteSide.RIGHT, NoteSide.ABOVE, NoteSide.BELOW_LEFT,
        )
        val ordered = orderedNoteCandidates(sides.map { candidate(it) }, rightTaken = false)
        assertEquals(
            "方向名次不对：${ordered.map { it.side }}",
            listOf(
                NoteSide.RIGHT,
                NoteSide.ABOVE_RIGHT,
                NoteSide.BELOW_LEFT,
                NoteSide.ABOVE_LEFT,
                NoteSide.BELOW,
                NoteSide.ABOVE,
                NoteSide.LEFT,
            ),
            ordered.map { it.side },
        )
    }

    /** 2026-10-24 用户定口：空间足够时上下左右一律先留 1em。 */
    @Test
    fun `diagonal keeps one em from the row on both sides`() {
        val row = rect(0f, 100f, 200f, 140f)
        val list = candidates(
            note = noteInk(1, listOf(row), "想法"),
            freeRects = listOf(rect(0f, 141.44f, 700f, 800f)),
        )
        val belowRight = list.first { it.side == NoteSide.BELOW_RIGHT }
        assertEquals("空间够时用首选留白档", 0, belowRight.marginTier)
        assertEquals(
            "近侧（下方）也要 1em：top=${belowRight.rect.top}",
            140f + noteMetrics.preferredMargin,
            belowRight.rect.top,
            0.5f,
        )
        assertEquals(
            "横向基准是首字右移一个汉字再加 1em：left=${belowRight.rect.left}",
            20f + noteMetrics.preferredMargin,
            belowRight.rect.left,
            0.5f,
        )
        assertTrue(
            "框右侧也要留出 1em：right=${belowRight.rect.right}",
            belowRight.rect.right <= 700f - noteMetrics.marginX - noteMetrics.preferredMargin + 0.5f,
        )
    }

    /** 2026-10-24 用户定口：空间不够先收**远侧**，近侧（要画引线那一侧）的 1em 尽量保留。 */
    @Test
    fun `diagonal gives up the far side before the near side`() {
        val row = rect(0f, 100f, 200f, 140f)
        val oneLine = 4 * noteFont + notePad
        // 空白右边只差「远侧那 0.9em」：首选档折成两行，收到 0.1em 就能一行读完。
        val left = 20f + noteMetrics.preferredMargin
        val tight = rect(0f, 141.44f, left + oneLine + noteMetrics.marginX + 2f, 800f)
        val list = candidates(
            note = noteInk(1, listOf(row), "想法想法"),
            freeRects = listOf(tight),
        )
        val oneLinePick = list.first { it.side == NoteSide.BELOW_RIGHT && it.lines == 1 }
        assertEquals("先收远侧那一档", 1, oneLinePick.marginTier)
        assertEquals("近侧仍要 1em", 140f + noteMetrics.preferredMargin, oneLinePick.rect.top, 0.5f)
        assertEquals("横向基准不变", left, oneLinePick.rect.left, 0.5f)

        // 下方只剩 1em 的近侧空档：前两档都放不下，最后一档把近侧也收到 0.1em 才放得下。
        val band = rect(0f, 140f + noteMetrics.marginY, 700f, 140f + noteMetrics.marginY + noteLine + 2f)
        val squeezedList = candidates(
            note = noteInk(1, listOf(row), "想法"),
            freeRects = listOf(band),
        )
        val squeezed = squeezedList.first { it.side == NoteSide.BELOW_RIGHT }
        assertEquals("最后一档才收近侧", 2, squeezed.marginTier)
        assertEquals(
            "近侧收到 0.1em：top=${squeezed.rect.top}",
            140f + noteMetrics.marginY,
            squeezed.rect.top,
            0.5f,
        )
    }

    /**
     * 2026-10-24 用户定口：别的空白都放不下时，可以请已有批注在垂直方向让一让（同一列叠放）。
     *
     * 版面上只有一列空白（页面右侧），刚好容得下两条：先占那条按自己的位置放好，
     * 后来的那条挤不进去时，请先占那条往上让一格。
     */
    @Test
    fun `a later note pushes the placed one down the same column`() {
        val row = rect(0f, 200f, 600f, 240f)
        val notes = listOf(
            noteInk(1, listOf(rect(0f, 200f, 40f, 240f)), "想法"),
            noteInk(2, listOf(rect(0f, 200f, 40f, 240f)), "也短"),
        )
        fun page(bottomBlockerTop: Float) = place(
            notes = notes,
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 600f, 198f),
                rect(0f, 241f, 600f, 800f),
                rect(600f, 0f, 700f, 186f),
                rect(600f, bottomBlockerTop, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        val alone = place(
            notes = listOf(notes[0]),
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 600f, 198f),
                rect(0f, 241f, 600f, 800f),
                rect(600f, 0f, 700f, 186f),
                rect(600f, 254f, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        ).single()

        val both = page(254f)
        assertEquals("两条都要留住：$both", 2, both.size)
        assertTrue("两条都要整句读完：$both", both.none { it.compact })
        assertEquals(
            "同一列叠放：left=${both.map { it.leftPx }}",
            1,
            both.map { it.leftPx }.distinct().size,
        )
        val sorted = both.sortedBy { it.topPx }
        assertTrue(
            "上下叠放且留出纵向间距：${sorted.map { it.topPx }}",
            sorted[1].topPx - (sorted[0].topPx + noteLine) >= noteMetrics.marginY - 0.5f,
        )
        assertTrue(
            "先占那条要让位（单独放置时 top=${alone.topPx}）",
            sorted.none { abs(it.topPx - alone.topPx) < 0.5f && it.annotationId == 1L },
        )
    }

    /** 让位只能「刚好够」：移完留不下，就谁也别动（2026-10-24 用户定口）。 */
    @Test
    fun `no move when the column cannot hold both`() {
        val row = rect(0f, 200f, 600f, 240f)
        val notes = listOf(
            noteInk(1, listOf(rect(0f, 200f, 40f, 240f)), "想法"),
            noteInk(2, listOf(rect(0f, 200f, 40f, 240f)), "也短"),
        )
        fun page(picked: List<NoteInk>, bottomBlockerTop: Float) = place(
            notes = picked,
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 600f, 198f),
                rect(0f, 241f, 600f, 800f),
                rect(600f, 0f, 700f, 205f),
                rect(600f, bottomBlockerTop, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        val alone = page(listOf(notes[0]), 231f).single()
        val both = page(notes, 231f)
        val first = both.first { it.annotationId == 1L }
        assertEquals("让不出空间就不该动先占那条", alone.leftPx, first.leftPx, 0.5f)
        assertEquals("让不出空间就不该动先占那条", alone.topPx, first.topPx, 0.5f)
    }

    /** 整句宽以实测为准：粗估偏窄时框必须跟着变宽，而不是画出来再被省略号截掉。 */
    @Test
    fun `measured sentence width drives the bubble width`() {
        val anchor = rect(0f, 0f, 200f, 40f)
        val measured = object : NoteTextSizer {
            override fun naturalWidth(text: String, fontPx: Float) =
                estimatedNoteTextWidth(text, fontPx) + 4f

            override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int) =
                noteTextFit(text, widthPx, fontPx, maxLines)
        }
        val b = place(
            notes = listOf(noteInk(1, listOf(anchor), text = "掌握基础")),
            textInk = listOf(anchor),
            sizer = measured,
        ).single()
        assertEquals(1, b.maxLines)
        assertEquals(4 * noteFont + 4f + notePad, b.widthPx, 0.01f)
        assertTrue(
            "实测宽已计入框宽，整句应完整显示",
            noteTextFit(b.text, b.widthPx - notePad, b.fontPx, b.maxLines).complete,
        )
    }

    /** 整句一行放不下：按可用宽折行，行数与整句字数都对得上。 */
    @Test
    fun `long note wraps to the usable width`() {
        val m = NoteMetrics.forBody(20f)
        val text = "字".repeat(30)
        val size = noteBoxFit(text, m, maxWidth = 200f, maxHeight = 100f)
        assertEquals(200f, size.width, 0.01f)
        assertEquals(3, size.lines)
        assertEquals(3 * m.lineH, size.height, 0.01f)
        assertTrue("30 个字 3 行应放得下，实际 ${size.shownChars}", size.complete)
    }

    /** 放在文字右侧要让出 sideGap（画水平箭头），可用宽比普通空白窄。 */
    @Test
    fun `right side keeps room for a horizontal arrow`() {
        val m = NoteMetrics.forBody(20f)
        val text = "字".repeat(14)
        // 右侧候选的可用宽已经在调用方扣掉 (sideGap − marginX)。
        val normal = noteBoxFit(text, m, maxWidth = 200f, maxHeight = 100f)
        val beside = noteBoxFit(text, m, maxWidth = 200f - (m.sideGap - m.marginX), maxHeight = 100f)
        assertTrue("右侧可用宽应更窄：normal=${normal.width} beside=${beside.width}", beside.width < normal.width)
        assertEquals(normal.lines, beside.lines)
    }

    /** 空白太窄，只能读 3 个字：不算可读，交给下一位或小图标。 */
    @Test
    fun `sliver narrower than four characters is not readable`() {
        val m = NoteMetrics.forBody(20f)
        // 一行只放得下一个字、最多三行：一共只能读三个字。
        val size = noteBoxFit("字".repeat(10), m, maxWidth = m.em + m.padTotal, maxHeight = m.lineH * 3.5f)
        assertEquals(3, size.lines)
        assertEquals(3, size.shownChars)
        assertTrue(!size.readable)
        assertTrue(!size.complete)
    }

    /** 空白矮到放不下整句：按高度截断，但能读就先显示。 */
    @Test
    fun `blank height limits the lines and reports truncation`() {
        val m = NoteMetrics.forBody(20f)
        val size = noteBoxFit("字".repeat(30), m, maxWidth = 200f, maxHeight = m.lineH * 1.5f)
        assertEquals(1, size.lines)
        assertEquals(m.lineH, size.height, 0.01f)
        assertTrue(size.shownChars in 1..29)
        assertTrue(!size.complete)
        assertTrue(size.readable)
    }

    /** 高／宽不够划出一行时返回空尺寸，不产生候选。 */
    @Test
    fun `blank smaller than one line yields no box`() {
        val m = NoteMetrics.forBody(20f)
        val short = noteBoxFit("用几章来", m, maxWidth = 200f, maxHeight = m.lineH * 0.5f)
        assertEquals(0, short.lines)
        assertEquals(0f, short.width, 0.01f)
        val narrow = noteBoxFit("用几章来", m, maxWidth = m.padTotal, maxHeight = 100f)
        assertEquals(0, narrow.lines)
        assertEquals(0f, narrow.width, 0.01f)
        val empty = noteBoxFit("  ", m, maxWidth = 200f, maxHeight = 100f)
        assertEquals(0, empty.lines)
        assertEquals(0, empty.totalChars)
    }
}
