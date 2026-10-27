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

    /** 两条线段是否真的穿过对方（端点相碰不算）。 */
    private fun segmentsCross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
        fun cross(o: Offset, p: Offset, q: Offset): Float =
            (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x)
        val d1 = cross(c, d, a)
        val d2 = cross(c, d, b)
        val d3 = cross(a, b, c)
        val d4 = cross(a, b, d)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
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
        otherRowLeft: Map<Int, Float> = emptyMap(),
        contentRight: Float = 600f,
    ) = noteCandidates(
        note = note,
        lines = lines,
        freeRects = freeRects,
        metrics = noteMetrics,
        otherRowLeft = otherRowLeft,
        contentRight = contentRight,
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
        arrowInk: List<Rect> = emptyList(),
        sizer: NoteTextSizer = EstimatedNoteTextSizer,
    ) = noteBubblePlacements(
        noteInk = notes,
        obstacles = NoteObstacles(
            textInk = textInk,
            figures = figures,
            tables = tables,
            strokes = strokes,
            textLines = textLines,
            arrowInk = arrowInk,
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
    fun `mid line selection with text after it stays near the selection`() {
        // 2026-10-27 用户定口：位置改为就近。所选文字后面还有字时，行尾右侧的空白
        // 离选区很远（要越过后面的字），不再为了「正右」把引线拉过去；就近落在下方。
        val anchor = rect(0f, 0f, 200f, 40f)
        val tail = rect(202f, 0f, 400f, 40f)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), textInk = listOf(anchor, tail))
        val b = bubbles.single()
        assertTrue("应就近落在选区下方：${b}", b.topPx >= anchor.bottom - 0.5f)
        assertTrue("不该越过后面的字贴到行尾：${b}", b.leftPx < tail.right)
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
    fun `near wrapped note beats a far single line footer`() {
        // 2026-10-26 规则 1 / 2：**环数先于折行数**——本行旁边折两行，也先于页脚那一行。
        // 页脚那一整块空白属于更远的环，只有本行 / 本段都放不下时才轮得到它。
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
        assertTrue("应留在本行附近的环里，实际 top=${b.topPx}", b.topPx < 200f)
        assertTrue("本行旁边放不下整句时会折行，实际 maxLines=${b.maxLines}", b.maxLines > 1)
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
    fun `taken right column yields to the other directions`() {
        // 行右侧已经被别的旁注占住：正右让到其他方向之后（2026-10-27 用户定口：
        // 上 / 下排在四个角、正右、正左之后，正右被占时更不该凭「更靠右」抢先）。
        // 2026-10-27 起先比就近，所以这里直接给有先后意义的距离：
        // 正右最近、正下其次、正左最远；右侧被占后先试正下，再试正左，正右最后。
        val row = rect(0f, 200f, 200f, 240f)
        fun candidate(
            side: NoteSide,
            left: Float,
            lines: Int = 1,
            rank: Int? = null,
            distance: Float = 0f,
        ) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 200f, left + 60f, 200f + 24f * lines),
            freeRect = rect(left, 0f, 700f, 800f),
            lines = lines,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            distance = distance,
            sideRank = rank ?: noteSideRank(side),
        )

        // 这一行右侧还剩 400（> 1em）：正右仍是最近的一侧。
        val right = candidate(NoteSide.RIGHT, 300f, rank = NOTE_SIDE_RANK_SIDE_FIRST, distance = 10f)
        val left = candidate(NoteSide.LEFT, -60f, distance = 200f)
        val below = candidate(NoteSide.BELOW, 0f, distance = 100f)

        val free = orderedNoteCandidates(listOf(left, below, right), rightTaken = false)
        assertEquals("右侧没被占时右侧最靠前", NoteSide.RIGHT, free.first().side)

        val taken = orderedNoteCandidates(listOf(left, below, right), rightTaken = true)
        assertEquals("右侧被占后先试正下（这一组里最近）", NoteSide.BELOW, taken[0].side)
        assertEquals("再试正左", NoteSide.LEFT, taken[1].side)
        assertEquals("正右让到最后", NoteSide.RIGHT, taken.last().side)
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

    /**
     * 同一行两条批注都想要右上。2026-10-25 用户定口：**后落位那条自己往外挪**，
     * 一次让出「自己 + 2 倍上下 margin」的空档；先落位那条不动（已经摆好的批注不该跳位置，
     * 否则读者刚看清的那条会自己跑掉）。
     */
    @Test
    fun `two notes on one row share the upper right by nudging`() {
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
        assertTrue(
            "两条都在这行上方：${l.topPx} / ${r.topPx}",
            l.topPx + l.maxLines * noteLine <= row.top + 2f &&
                r.topPx + r.maxLines * noteLine <= row.top + 2f,
        )
        assertTrue("两条不能重叠：${l.leftPx} / ${r.leftPx}", !boxOf(l).overlaps(boxOf(r)))
        // 让位一次让出「新批注 + 2 倍上下 margin」：不留这么大，两条会挤成一块分不出是两条。
        // 2026-10-26 起最右那条先落位，谁在上、谁在下不再固定，只比实际上下两条之间的缝。
        val upper = if (l.topPx <= r.topPx) l else r
        val lower = if (l.topPx <= r.topPx) r else l
        val gap = lower.topPx - (upper.topPx + upper.maxLines * noteLine)
        assertTrue(
            "两条之间要留出 2 倍 margin：gap=$gap 期望≥${noteMetrics.marginY * 2f}",
            gap >= noteMetrics.marginY * 2f - 0.5f,
        )
        assertTrue("都还在本行附近：${l.topPx} / ${r.topPx}", l.topPx >= row.top - 3 * noteLine)
    }

    @Test
    fun `corner box shifts right of the selection first character`() {
        // 真机第 3 条 + 2026-10-25 用户定口：斜向摆法的横向基准是**末行第一个字右移一个汉字**，
        // 不再额外加 1em——右下就是「往右一个字」，多出来那 1em 读者一眼就能看出偏右。
        // 规则 5 里右上在右下之前，这一页上方有空，所以落在右上（横向基准与右下完全相同）。
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
        val bottom = b.topPx + b.maxLines * noteLine
        assertTrue("应落在锚点上方：bottom=$bottom", bottom <= 100f + 0.5f)
        // 选区只有一个矩形，首字左缘就是 anchor.left；一个汉字按正文字号 20px 算。
        val expectedLeft = anchor.left + 20f
        assertTrue(
            "框左缘要落在首字右移一个汉字处：left=${b.leftPx} 期望≈$expectedLeft",
            b.leftPx >= expectedLeft - 0.5f,
        )
        assertTrue(
            "但也不能再让出 1em：left=${b.leftPx}",
            b.leftPx < expectedLeft + noteMetrics.preferredMargin,
        )
        assertTrue("框不该整条跑到选区右边太远：left=${b.leftPx}", b.leftPx < anchor.right + 3 * noteFont)
    }

    /**
     * 同一行多条批注时给左侧那条留引线空档（2026-10-24 用户定口）：
     * 落在这一行上方 / 下方的框，左缘不得越过左侧批注**第二个字的左缘**——
     * 也就是它第一个字的下方（或上方）要空出来，那条批注的画线才接得进去。
     */
    @Test
    fun `below and above boxes keep the left note first character clear`() {
        val row = rect(0f, 100f, 600f, 140f)
        // 右边这条很长：不加约束时「正下」会把框摊满整行、左缘一路压到页左。
        val ink = rect(300f, 100f, 400f, 140f)
        val longText = "长".repeat(42)
        val lines = listOf(NoteTextLine(rect = row, blockId = 0, index = 0))
        val band = rect(0f, 141f, 700f, 800f)
        // 左侧那条批注的首字在 100，第二个字左缘 = 100 + 一个汉字（正文 20px）。
        val reserve = 100f + 20f
        val unguarded = candidates(noteInk(1, listOf(ink), longText), listOf(band), lines)
        assertTrue(
            "没有左侧批注时本来可以摊到页左（对照组）：${unguarded.map { it.side to it.rect.left }}",
            unguarded.any {
                (it.side == NoteSide.BELOW || it.side == NoteSide.ABOVE) && it.rect.left < reserve
            },
        )
        val guarded = candidates(
            noteInk(1, listOf(ink), longText),
            listOf(band),
            lines,
            otherRowLeft = mapOf(0 to 100f),
        )
        val verticalSides = setOf(
            NoteSide.BELOW_RIGHT, NoteSide.ABOVE_RIGHT, NoteSide.BELOW, NoteSide.ABOVE,
        )
        assertTrue(
            "框左缘不得越过左侧批注第二个字的左缘：${guarded.filter { it.side in verticalSides }.map { it.side to it.rect.left }}",
            guarded.filter { it.side in verticalSides }.all { it.rect.left >= reserve - 0.5f },
        )
        assertTrue("约束不该让批注彻底没位置", guarded.isNotEmpty())
    }

    @Test
    fun `right side note keeps the left note first character clear`() {
        val row = rect(0f, 100f, 600f, 140f)
        val left = rect(100f, 100f, 120f, 140f)
        val right = rect(300f, 100f, 320f, 140f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(left), "左边"),
                // 右这条写得很长：两个斜向都放不下一行，只能落到「正下」；正是它会摊到页左。
                noteInk(2, listOf(right), "长".repeat(42)),
            ),
            textInk = listOf(row),
            contentRight = 600f,
            contentBottom = 800f,
            pageRight = 620f,
            pageBottom = 800f,
        )
        val r = bubbles.single { it.annotationId == 2L }
        assertTrue("右边那条不能退成小图标：$r", !r.compact)
        assertTrue(
            "正下的框不能让过左侧批注首字那一格：left=${r.leftPx}",
            r.leftPx >= 100f + 20f - 0.5f,
        )
        assertTrue("左边那条仍在这页上", bubbles.any { it.annotationId == 1L })
    }

    /**
     * 2026-10-24 用户补充：右侧优先级降低只针对**非段末行**；
     * 段末行（含只有一行的段落）右侧仍然是第一优先级。
     */
    @Test
    fun `full width line puts the page margin column after the four corners`() {
        // 规则 3 只在**这一行右侧还有 1em 以上空白**时才把正右提到最前。
        // 行本身就是满行（右边只剩页边距那一窄列，规则 6）时，正右按规则 6 排在四个角之后，
        // 但仍参与竞争（页边距那一列照样能用）。
        val row = rect(0f, 100f, 600f, 140f)
        val ink = rect(100f, 100f, 300f, 140f)
        val lines = listOf(NoteTextLine(rect = row, blockId = 0, index = 0))
        val column = rect(600f + noteMetrics.sideGap, 0f, 700f, 800f)
        val below = rect(0f, 141f, 700f, 800f)
        val list = candidates(noteInk(1, listOf(ink), "短"), listOf(column, below), lines)
        val ordered = orderedNoteCandidates(list, rightTaken = false)
        // 满行的页边距那一列排在四个角之后：同一块右侧页边距里，几何上算「右上 / 右下」
        // 的那条也按页边距档（正右 / 正左那一档）排，不能被当成斜向抢到前面（2026-10-27）。
        assertTrue("页边距那一列仍要参与竞争", ordered.any { it.side == NoteSide.RIGHT })
        assertTrue(
            "右侧页边距里的候选一律按页边距档",
            ordered.filter { it.rect.left >= 600f - 0.5f }.all { it.sideRank >= 10 },
        )
        assertTrue(
            "正右排在四个角之后",
            ordered.indexOfFirst { it.side == NoteSide.RIGHT } >
                ordered.indexOfFirst { it.side == NoteSide.BELOW_RIGHT || it.side == NoteSide.BELOW },
        )
    }

    @Test
    fun `page margin column folds at most two lines`() {
        // 规则第 6 条：左右两侧只剩页边距时最多折两行——那一列窄，再多折就成了竖条。
        // 上下都封死，只剩行右侧那一条页边距；这句话在那一列里本来能折五六行。
        val row = rect(0f, 100f, 600f, 140f)
        val ink = rect(100f, 100f, 300f, 140f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(ink), "这段批注相当长放进页边距会折很多行")),
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 700f, 100f),
                rect(0f, 140f, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        val b = bubbles.single()
        assertTrue("不该退成小图标：$b", !b.compact)
        assertTrue("页边距那一列最多折两行，实际=${b.maxLines}", b.maxLines <= 2)
    }

    /**
     * 2026-10-24 用户定口：正右看的是「这一行右边还剩多少空白」，不是段末行身份。
     * 左对齐的短行（代码行 / 标题行）右边本来就是空白区，段中行也要先把正右排在第一位。
     */
    @Test
    fun `mid paragraph line with blank to the right takes the right side first`() {
        // 两行同属一段（blockId 相同）：基准行是段中行，但它右边离版心右缘还有 400。
        val row = rect(0f, 100f, 200f, 140f)
        val next = rect(0f, 160f, 600f, 200f)
        val ink = rect(0f, 100f, 200f, 140f)
        val lines = listOf(
            NoteTextLine(rect = row, blockId = 0, index = 0),
            NoteTextLine(rect = next, blockId = 0, index = 1),
        )
        val column = rect(202f, 0f, 700f, 155f)
        val below = rect(0f, 210f, 700f, 800f)
        val list = candidates(noteInk(1, listOf(ink), "短"), listOf(column, below), lines)
        val ordered = orderedNoteCandidates(list, rightTaken = false)
        assertEquals("段中行右边有空白时正右也是第一优先级", NoteSide.RIGHT, ordered.first().side)
    }

    @Test
    fun `code line inside one block still lands on its right`() {
        // pre code：三行同属一个块（段中行），左对齐、右边大片空白 ⇒ 正右第一。
        val rows = (0..2).map { rect(0f, it * 22f, 180f, it * 22f + 20f) }
        val lines = rows.mapIndexed { i, r -> NoteTextLine(rect = r, blockId = 3, index = i) }
        val ink = rect(0f, 22f, 120f, 42f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(ink), "这里")),
            textInk = rows,
            textLines = lines,
            contentRight = 600f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        val b = bubbles.single()
        assertTrue("代码行右边有空白，旁注该落在右半边：left=${b.leftPx}", b.leftPx >= 180f)
        // 就近取位：右列里比所选行稍上 / 稍下的框可能更近，只要还贴着这组代码行即可。
        assertTrue("应贴在同一组代码行旁边：top=${b.topPx}", b.topPx < 66f)
    }

    @Test
    fun `full mid line can use its own right margin after the six directions`() {
        // 2026-10-27 用户定口：满行的左右页边距**可以用**，但名次排在
        // 右上 / 右下 / 左上 / 左下 / 上 / 下 之后。满行本身不再是「连候选都不生成」。
        val row = rect(0f, 100f, 600f, 140f)
        val next = rect(0f, 160f, 600f, 200f)
        val ink = rect(100f, 100f, 300f, 140f)
        val lines = listOf(
            NoteTextLine(rect = row, blockId = 0, index = 0),
            NoteTextLine(rect = next, blockId = 0, index = 1),
        )
        val list = candidates(
            noteInk(1, listOf(ink), "短"),
            listOf(rect(600f + noteMetrics.sideGap, 0f, 700f, 800f), rect(0f, 210f, 700f, 800f)),
            lines,
        )
        val besideRow = list.filter {
            it.side == NoteSide.RIGHT && it.ring == 0 &&
                it.rect.bottom > row.top && it.rect.top < row.bottom
        }
        assertTrue("满行自己的右页边距要能生成：$besideRow", besideRow.isNotEmpty())
        assertTrue(
            "满行右页边距排在四个角之后：${besideRow.map { it.sideRank }}",
            besideRow.all { it.sideRank == noteSideRank(NoteSide.RIGHT) },
        )
        assertTrue(
            "下一行的正右算本行相邻空间（对本行是右下）：${list.map { "${it.side}#${it.ring}" }}",
            list.any {
                it.side == NoteSide.BELOW_RIGHT && it.ring == 0 && it.rect.top >= row.bottom - 0.5f
            },
        )
    }

    @Test
    fun `same line notes are computed from left to right`() {
        // 规则第 2 条：同一行内**从左到右**计算。左边那条先取位，右边那条再按自己的阶梯找地方；
        // 左边那条的位置与它单独放置时完全一样（不会因为右边多了一条就被挤走）。
        val row = rect(0f, 0f, 600f, 40f)
        val leftInk = rect(0f, 0f, 300f, 40f)
        val rightInk = rect(300f, 0f, 600f, 40f)
        val figures = listOf(rect(600f, 40f, 700f, 800f))
        val solo = place(
            notes = listOf(noteInk(1, listOf(leftInk), "短")),
            textInk = listOf(row),
            figures = figures,
            contentBottom = 800f,
            pageBottom = 800f,
        ).single()
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(leftInk), "短"),
                noteInk(2, listOf(rightInk), "也短"),
            ),
            textInk = listOf(row),
            figures = figures,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val right = bubbles.single { it.annotationId == 2L }
        val left = bubbles.single { it.annotationId == 1L }
        assertEquals("左边那条先算，位置与单独放置一致", solo.leftPx, left.leftPx, 0.5f)
        assertEquals("左边那条先算，位置与单独放置一致", solo.topPx, left.topPx, 0.5f)
        assertTrue("右边那条自己另找地方，实际=$right", right.leftPx != left.leftPx || right.topPx != left.topPx)
        assertTrue("任何人都不能退成小图标：$bubbles", bubbles.none { it.compact })
        assertTrue("两条不能重叠", !boxOf(left).overlaps(boxOf(right)))
    }

    @Test
    fun `note that would cover the right text takes the left corner`() {
        // 规则第 8 条（2026-10-25 用户定口）：这一条贴着自己那一行摆会横着罩住右侧那条的选区时，
        // 先改用左侧——**左上角优先于右上角**，哪怕右上角更靠右、看起来更「顺手」。
        // 真机 boxes 那条（选区在行左半，批注很长）就是这个形状。
        val row = rect(0f, 100f, 600f, 140f)
        val belowLeft = rect(0f, 140f, 450f, 180f)
        val boxes = rect(300f, 100f, 360f, 140f)
        val popcorn = rect(400f, 100f, 430f, 140f)
        val of = rect(450f, 100f, 560f, 140f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(boxes), "再加一个批注，看看放在哪里。"),
                noteInk(2, listOf(popcorn), "这里也有批注，会放哪里呢？"),
                noteInk(3, listOf(of), "这是什么意思？"),
            ),
            textInk = listOf(row, belowLeft),
            contentBottom = 400f,
            pageBottom = 400f,
            pageRight = 700f,
        )
        val covering = bubbles.single { it.annotationId == 1L }
        assertTrue(
            "会罩住右侧文字时先走左上角（框右缘不越过选区最右缘往左一个字的基准）：$covering",
            boxOf(covering).right <= boxes.right - 20f + 0.5f,
        )
        assertTrue("左上角要在本行上方：$covering", boxOf(covering).bottom <= row.top + 0.5f)
        assertTrue("三条都不能退成小图标：$bubbles", bubbles.none { it.compact })
    }

    @Test
    fun `covering note still prefers the upper right when the row's last note is already on the right`() {
        // 规则第 8 条例外（2026-10-25 用户定口）：本条右侧只剩本行最右那条、且它已经
        // 放在本行正右（框在行右，不占这一带空白）⇒ 本条回到右上优先，不再先走左侧。
        val row = rect(0f, 100f, 560f, 140f)
        val boxes = rect(300f, 100f, 360f, 140f)
        val of = rect(450f, 100f, 520f, 140f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(boxes), "再加一个批注，看看放在哪里。"),
                noteInk(2, listOf(of), "什么"),
            ),
            textInk = listOf(row),
            contentBottom = 400f,
            pageBottom = 400f,
            pageRight = 700f,
        )
        val right = bubbles.single { it.annotationId == 2L }
        val covering = bubbles.single { it.annotationId == 1L }
        assertTrue("最右那条先放在本行正右：$right", right.leftPx >= of.right - 0.5f)
        assertTrue(
            "例外命中时回到右上角（框从首字右缘起，不越过左侧）：$covering",
            covering.leftPx >= boxes.left + 20f - 0.5f,
        )
        assertTrue("右上角仍在本行上方：$covering", boxOf(covering).bottom <= row.top + 0.5f)
    }

    @Test
    fun `note that would cover the right text yields instead of pushing it`() {
        // 规则第 8 条（2026-10-25 用户定口）：会罩住右侧文字的那条**自己是来让位的**，
        // 不能反过来把右侧那条挤走。真机「函数」那条（整句很宽、贴在本行下方会盖住
        // 右边两条的选区）以前就是这么把「用几章来」挤到下面的，现在它自己往下让。
        val row = rect(0f, 100f, 600f, 140f)
        val rowAbove = rect(0f, 0f, 600f, 100f)
        val rowsBelow = listOf(
            rect(0f, 180f, 600f, 220f),
            rect(0f, 260f, 600f, 300f),
            rect(0f, 340f, 600f, 380f),
        )
        val wide = rect(100f, 100f, 220f, 140f)
        val middle = rect(250f, 100f, 310f, 140f)
        val right = rect(330f, 100f, 400f, 140f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(wide), "这条批注会把右边两段文字都整个盖住"),
                noteInk(2, listOf(middle), "用几章来"),
                noteInk(3, listOf(right), "就是具体"),
            ),
            textInk = listOf(rowAbove, row) + rowsBelow,
            contentBottom = 600f,
            pageBottom = 600f,
            pageRight = 600f,
        )
        val covering = bubbles.single { it.annotationId == 1L }
        val middleBubble = bubbles.single { it.annotationId == 2L }
        val rightBubble = bubbles.single { it.annotationId == 3L }
        assertEquals(
            "右侧那条要留在本行下方最近的格子：$middleBubble",
            row.bottom + 2f,
            middleBubble.topPx,
            0.5f,
        )
        assertEquals(
            "右侧那条要留在本行下方最近的格子：$rightBubble",
            row.bottom + 2f,
            rightBubble.topPx,
            0.5f,
        )
        assertTrue(
            "会罩住右侧文字的那条自己让到左边或下方，不挤右边两条：$covering / $rightBubble",
            boxOf(covering).left + boxOf(covering).width <= boxOf(middleBubble).left + 0.5f ||
                boxOf(covering).top >= boxOf(rightBubble).top - 0.5f,
        )
        assertTrue("谁都不能退成小图标：$bubbles", bubbles.none { it.compact })
        assertTrue(
            "三条不能互相重叠",
            !boxOf(covering).overlaps(boxOf(middleBubble)) &&
                !boxOf(covering).overlaps(boxOf(rightBubble)) &&
                !boxOf(middleBubble).overlaps(boxOf(rightBubble)),
        )
    }

    @Test
    fun `multi line anchor uses the last line as its base`() {
        // 多行选区以**末行**为基准行：跨行批注和末行上的另一条各自独立取位。
        // 末行那条（锚点更靠右）先算，占行右；跨行批注的右侧已被占，改落在末行右下。
        val row0 = rect(0f, 0f, 500f, 40f)
        val row1 = rect(0f, 40f, 500f, 80f)
        val spans = rect(0f, 0f, 300f, 40f)
        val tail = rect(0f, 40f, 160f, 80f)
        val onLast = rect(300f, 40f, 480f, 80f)
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
        assertTrue("末行那条应在行右侧空白：$lastLine", lastLine.leftPx >= 500f)
        assertTrue("跨行批注以末行为基准、落在末行下方：$spanning", spanning.topPx >= row1.bottom - 0.5f)
        assertTrue("两条不能重叠", !boxOf(spanning).overlaps(boxOf(lastLine)))
        // 本行最右那条仍先占正右（例外一）；它落在右侧同一列，不要求与单独摆放时逐像素一致。
        assertTrue("末行那条应在右侧一列：$lastLine", lastLine.leftPx >= 480f)
    }

    @Test
    fun `multi line note falls back to the first line when the last line has no room`() {
        // 2026-10-25 用户定口：批注位置先按末行；末行那一圈放不下，才试首行那一圈。
        // 「上一行偏右选到下一行偏左」的长选区里，末行右侧 / 下方常常全是字，首行右侧才是空的。
        val above = rect(0f, 0f, 600f, 40f)   // 上一行满行：首行上方没有空白
        val row0 = rect(0f, 40f, 300f, 80f)   // 首行短：右侧空着
        val row1 = rect(0f, 80f, 600f, 120f)   // 末行满：右 / 下 / 左都没地方
        val firstSel = rect(100f, 40f, 300f, 80f)
        val lastSel = rect(0f, 80f, 200f, 120f)
        val b = place(
            notes = listOf(noteInk(1, listOf(firstSel, lastSel), "想法")),
            textInk = listOf(above, row0, row1),
            contentBottom = 120f,
            pageRight = 600f,
            pageBottom = 120f,
            textLines = listOf(
                NoteTextLine(above, blockId = 0, index = 0),
                NoteTextLine(row0, blockId = 0, index = 1),
                NoteTextLine(row1, blockId = 0, index = 2),
            ),
        ).single()
        assertTrue("末行放不下就该落到首行那一圈：$b", !b.compact && b.topPx < row1.top)
        // 2026-10-25 用户定口（引线规则第 10 条）：位置由首行那一圈定下来，但框离**选区末尾**更近
        // （末行右端），引线就指末尾那一行，不再跟着「决定落位的那一行」走。
        val tip = arrowPoints(b.arrow!!).last()
        assertEquals("引线指向选区末尾（x≈200）", 200f, tip.x, 0.5f)
        assertTrue("端点落在末行：$tip", tip.y >= row1.top - 0.5f)
    }

    @Test
    fun `multi line note settles on the last line when both lines have room`() {
        // 两行旁边都有空地、距离也一样时仍贴基准行（末行），引线也跟着指向末行。
        val row0 = rect(0f, 40f, 300f, 80f)
        val row1 = rect(0f, 80f, 200f, 120f)
        val firstSel = rect(0f, 40f, 300f, 80f)
        val lastSel = rect(0f, 80f, 200f, 120f)
        val b = place(
            notes = listOf(noteInk(1, listOf(firstSel, lastSel), "想法")),
            textInk = listOf(row0, row1),
            contentBottom = 120f,
            pageRight = 600f,
            pageBottom = 120f,
            textLines = listOf(
                NoteTextLine(row0, blockId = 0, index = 0),
                NoteTextLine(row1, blockId = 0, index = 1),
            ),
        ).single()
        assertTrue("末行右侧有空间就先贴末行：$b", !b.compact && b.topPx >= row1.top - 0.5f)
        assertTrue("应画得出引线：$b", b.arrow != null)
        val tip = arrowPoints(b.arrow!!).last()
        assertTrue("引线指向末行末端（x≈200），实际 $tip", tip.x in 150f..200.5f)
        assertTrue("端点落在末行内：$tip", tip.y >= row1.top - 0.5f)
    }

    @Test
    fun `arrow follows the row it is asked to point at`() {
        // routeNoteArrow 只认调用方给的那一行（哪一行由「离选区开头近还是末尾近」定）。
        // 框高跨两行时不再按「与框纵向最近的那一行」倒向首行（两行都是 0 距离，旧实现取到列表第一个）。
        val firstRow = rect(100f, 100f, 300f, 140f)
        val lastRow = rect(100f, 140f, 500f, 180f)
        val bubble = rect(600f, 80f, 700f, 200f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(firstRow, lastRow),
            side = NoteSide.RIGHT,
            targetInk = listOf(lastRow),
        )
        assertTrue("应画得出箭头", arrow != null)
        val tip = arrowPoints(arrow!!).last()
        assertEquals("引线指向末行末端", 500f, tip.x, 0.5f)
        assertTrue("端点落在末行内：$tip", tip.y in lastRow.top - 0.5f..lastRow.bottom + 0.5f)
    }

    @Test
    fun `multi line leader points at the nearer end of the selection`() {
        // 引线规则第 10 条（2026-10-25 用户定口）：位置定下来后，框离开头近就指首行、离末尾近就指末行。
        val lines = listOf(
            NoteTextLine(rect(100f, 0f, 500f, 40f), blockId = 0, index = 0),
            NoteTextLine(rect(100f, 40f, 300f, 80f), blockId = 0, index = 1),
        )
        val ink = listOf(rect(100f, 0f, 500f, 40f), rect(100f, 40f, 300f, 80f))
        // 框在左下：离选区开头（首字左上角 100,0）近 ⇒ 指首行。
        assertEquals(0, noteArrowRow(rect(0f, 40f, 90f, 80f), ink, lines))
        // 框在右下：离选区末尾（末字右下角 300,80）近 ⇒ 指末行。
        assertEquals(1, noteArrowRow(rect(320f, 40f, 400f, 80f), ink, lines))
        // 单行选区不用挑。
        assertEquals(0, noteArrowRow(rect(0f, 0f, 90f, 40f), listOf(ink[0]), lines))
    }

    @Test
    fun `indented line with the selection on its left puts the note on the left`() {
        // 2026-10-25 用户定口：左边有缩进、右边又不是满行时，按选区在这一行里**偏左还是偏右**定先后。
        // 两行都缩进 120，文字只排到 420（右边还剩 180）——选区偏左，正左第一。
        val rows = listOf(rect(160f, 0f, 460f, 20f), rect(160f, 22f, 420f, 42f))
        val lines = rows.mapIndexed { i, r -> NoteTextLine(rect = r, blockId = 0, index = i) }
        val b = place(
            notes = listOf(noteInk(1, rows, "短")),
            textInk = rows,
            textLines = lines,
            contentLeft = 40f,
            contentRight = 600f,
            pageRight = 640f,
            contentBottom = 800f,
            pageBottom = 800f,
        ).single()
        assertTrue("缩进让出的左边空档比右边小，应落在左侧：left=${b.leftPx}", b.leftPx < 160f)
        assertTrue("不该退成小图标：$b", !b.compact)
        // 落在末行左侧，但框离选区开头更近 ⇒ 引线指首行（引线规则第 10 条）。
        val tip = arrowPoints(b.arrow!!).last()
        assertTrue("引线应指首行起点（x≈160），实际 $tip", tip.x < 200f)
        assertTrue("端点落在首行内：$tip", tip.y <= rows[0].bottom + 0.5f)
    }

    @Test
    fun `indented line with the selection on its right puts the note on the right`() {
        // 同样两边都空，但文字压到 520（离版心右缘只剩 80）：选区偏右，正右第一。
        val row = rect(300f, 0f, 520f, 20f)
        val b = place(
            notes = listOf(noteInk(1, listOf(row), "短")),
            textInk = listOf(row),
            contentLeft = 40f,
            contentRight = 600f,
            pageRight = 640f,
            contentBottom = 800f,
            pageBottom = 800f,
        ).single()
        assertTrue("选区偏右，应落在右侧：left=${b.leftPx}", b.leftPx >= 520f)
        assertTrue("不该退成小图标：$b", !b.compact)
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
        // 行右边只有一条窄而高的空白，会把这句话折成好几行；行上方 / 下方有宽空白能一行放下。
        // 同一圈里不折行优先：宁可去上下，也不为了「更靠右」先折行。
        // 规则 5 里右上在右下之前，这一页上方有空，所以落点在上方。
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
        val bottom = b.topPx + b.maxLines * noteLine
        assertTrue(
            "应落在行上方或下方（不进那条窄列），实际 top=${b.topPx} left=${b.leftPx}",
            bottom <= 200f + 0.5f || b.topPx >= 240f - 0.5f,
        )
    }

    @Test
    fun `rightmost note still takes the right column on an indented line`() {
        // 2026-10-25 用户定口：左边有缩进时左侧虽是首选，但「本行最右那条先占正右」的例外不变——
        // 右侧那块空白离它最近，先归它；它占不到，本行其余批注才从左到右逐条取位。
        val row = rect(194f, 0f, 634f, 69f)
        val leftInk = rect(194f, 0f, 404f, 69f)
        val rightInk = rect(542f, 0f, 634f, 69f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(leftInk), "用 val 声明"),
                noteInk(2, listOf(rightInk), "只读的还能叫变量么？"),
            ),
            textInk = listOf(row),
            contentLeft = 125f,
            contentRight = 1715f,
            pageRight = 1840f,
            contentBottom = 2400f,
            pageBottom = 2400f,
        )
        val rightNote = bubbles.single { it.annotationId == 2L }
        val leftNote = bubbles.single { it.annotationId == 1L }
        assertTrue("最右那条应先拿到正右：left=${rightNote.leftPx}", rightNote.leftPx >= 634f)
        assertTrue(
            "两条不能重叠：${boxOf(leftNote)} / ${boxOf(rightNote)}",
            !boxOf(leftNote).overlaps(boxOf(rightNote)),
        )
    }

    @Test
    fun `rightmost note does not pre-empt the blank when the right column is unavailable`() {
        // 用户定口（2026-10-27）：最右那条的预排**只认正右**；正右放不下就作废，
        // 它回到正常顺序（从左到右）取位。不能借预排先挑走本行共享空白里最近的一格——
        // 否则最长的那条反而贴着正文（真机 book 39 第 2 章「函数将在几章中进行更详细的讨论」那页）。
        val above = rect(0f, 110f, 600f, 150f)
        val row = rect(0f, 170f, 600f, 210f)
        fun ink(id: Long, left: Float, right: Float, text: String) =
            noteInk(id, listOf(rect(left, 170f, right, 210f)), text)
        val bubbles = place(
            notes = listOf(
                ink(1, 60f, 110f, "函数就是功能呀！老外的词汇太贫乏了"),
                ink(2, 200f, 270f, "用几章来"),
                ink(3, 380f, 430f, "就是具体"),
                ink(4, 450f, 510f, "怎么讨论呢？讨论什么呢？以什么形式讨论呢？开个party可好？再长一点会怎样？折行呢？"),
            ),
            textInk = listOf(above, row),
            contentBottom = 600f,
            pageRight = 600f,
            pageBottom = 600f,
        )
        assertEquals(4, bubbles.size)
        assertTrue("四条都要能整句读完：$bubbles", bubbles.none { it.compact })
        val first = bubbles.single { it.annotationId == 2L }
        val longest = bubbles.single { it.annotationId == 4L }
        assertTrue(
            "本行最右那条不能先占共享空白：讨论 top=${longest.topPx}，最左那条 top=${first.topPx}",
            longest.topPx >= first.topPx - 0.5f,
        )
        assertEquals(
            "共享空白的第一格给正常顺序里最先排的那条：$bubbles",
            first.topPx,
            bubbles.minOf { it.topPx },
            0.5f,
        )
        for (i in bubbles.indices) {
            for (j in i + 1 until bubbles.size) {
                assertTrue(
                    "四条不能重叠：${boxOf(bubbles[i])} / ${boxOf(bubbles[j])}",
                    !boxOf(bubbles[i]).overlaps(boxOf(bubbles[j])),
                )
            }
        }
    }

    @Test
    fun `fewer lines wins even when the preferred side folds more`() {
        // 2026-10-26 规则 2：同环内**折行数先于方向**。
        // 正右是规则 3 的首选侧，但要折 3 行；正下只要 1 行，就取正下。
        fun candidate(side: NoteSide, lines: Int, distance: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(0f, 100f, 60f, 100f + 24f * lines),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = lines,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            distance = distance,
            sideRank = noteSideRank(side),
        )
        val rightFolded = candidate(NoteSide.RIGHT, lines = 3, distance = 1f)
        val belowOneLine = candidate(NoteSide.BELOW, lines = 1, distance = 50f)
        val ordered = orderedNoteCandidates(listOf(rightFolded, belowOneLine), rightTaken = false)
        assertEquals(
            "同环先比折行数：${ordered.map { it.side to it.lines }}",
            NoteSide.BELOW,
            ordered.first().side,
        )
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
        // 引线规则第 6 条：箭头末端不许落在选区的四个角点上，至少向内偏移 0.5em。
        // 起点字只有一个字宽，所以竖段落在它中心（左缘再往右半个字），不再压着左缘。
        assertEquals("竖段离选区左角 0.5em", ink.left + ink.height * 0.5f, pts[1].x, 0.01f)
        assertEquals("末端接住文字底边", ink.bottom, pts[2].y, 0.01f)
        assertTrue("第一段要比短竖线长得多：$pts", abs(pts[1].x - pts[0].x) > 2 * abs(pts[2].y - pts[1].y))
    }

    @Test
    fun `right side note stays a single segment when the box is a few pixels off`() {
        // 真机第 7 条：正右没阻挡，只因为框边按 0.5em 内缩后与字墨迹中心差几像素，
        // 就折成了三段。差得不多时按规则第 7 条后半句「距离不允许时取消内缩」，照样一条直线。
        val ink = rect(100f, 100f, 300f, 140f)
        for (dy in listOf(0f, 2f, 5f)) {
            val bubble = rect(340f, 100f + dy, 440f, 140f + dy)
            val arrow = routeNoteArrow(bubble, listOf(ink), lineH = 32f, side = NoteSide.RIGHT)
            val straight = arrow as? NoteArrow.Straight
            assertTrue("正右无阻挡必须是单段直线（dy=$dy）：$arrow", straight != null)
            assertEquals("横线两端要同高", straight!!.start.y, straight.end.y, 0.01f)
            assertEquals("起点落在框左边上", bubble.left, straight.start.x, 0.01f)
            assertEquals("终点落在文字右边上", ink.right, straight.end.x, 0.01f)
        }
    }

    @Test
    fun `one line high box still gets a single segment`() {
        // 框只有一行高时，「离角 0.5em」的可用带塌成一个点；字墨迹中心再差一两像素，
        // 两段区间就永远对不上。正确做法是取消内缩，而不是把直线折成三段。
        val ink = rect(100f, 100f, 300f, 140f)
        for (dy in listOf(0f, 2f, 4f)) {
            val bubble = rect(340f, 104f + dy, 430f, 130f + dy)
            val arrow = routeNoteArrow(bubble, listOf(ink), lineH = 34f, side = NoteSide.RIGHT)
            assertTrue("一行高的框也要一条直线（dy=$dy）：$arrow", arrow is NoteArrow.Straight)
        }
    }

    @Test
    fun `note directly below meets its text inside the horizontal overlap`() {
        // 真机第 5 条：正下方的批注要与选区**横向重叠的那一段**对齐，一条竖线解决，
        // 不该从框中心或某个字上随便拉一条再绕行。
        val ink = rect(100f, 100f, 500f, 140f)
        for (left in listOf(120f, 260f, 380f)) {
            val bubble = rect(left, 170f, left + 200f, 210f)
            val arrow = routeNoteArrow(bubble, listOf(ink), lineH = 32f, side = NoteSide.BELOW)
                as? NoteArrow.Straight
            assertTrue("正下方必须是一条竖线（left=$left）：$arrow", arrow != null)
            assertEquals("两端 x 要相同", arrow!!.start.x, arrow.end.x, 0.01f)
            assertEquals("起点在框上边", bubble.top, arrow.start.y, 0.01f)
            assertEquals("终点在文字下边", ink.bottom, arrow.end.y, 0.01f)
            val lo = max(bubble.left, ink.left)
            val hi = min(bubble.right, ink.right)
            assertTrue("竖线要落在横向重叠段里：x=${arrow.start.x}", arrow.start.x in lo..hi)
        }
    }

    @Test
    fun `note below right leaves through its top edge and never doubles back`() {
        // 真机第 4 条：右下批注先从左边出去、绕一圈再扎回文字右侧。
        // 框在文字右下时，正确走法是从框上边竖着出去、沿行间横穿、再竖着进文字。
        val ink = rect(150f, 64f, 230f, 84f)
        val rows = listOf(rect(0f, 64f, 600f, 84f), rect(0f, 96f, 600f, 116f))
        val bubble = rect(601f, 104f, 680f, 184f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            glyphs = rows,
            lineH = 32f,
            side = NoteSide.BELOW_RIGHT,
        )
        val pts = arrowPoints(arrow!!)
        assertEquals("第一段要竖着从框上边出去：$pts", bubble.top, pts.first().y, 0.01f)
        assertTrue("起点 x 要落在框的横向范围里：$pts", pts.first().x in bubble.left..bubble.right)
        for (i in 1 until pts.size) {
            assertTrue("引线必须横平竖直：$pts", abs(pts[i - 1].x - pts[i].x) < 0.01f || abs(pts[i - 1].y - pts[i].y) < 0.01f)
            assertTrue("不许折回自己的框：$pts", !entersInterior(pts[i - 1], pts[i], bubble))
        }
        val end = pts.last()
        val lastIsVertical = abs(pts[pts.size - 1].x - pts[pts.size - 2].x) < 0.01f
        if (lastIsVertical) {
            assertEquals("末段竖着落在文字底边", ink.bottom, end.y, 0.01f)
            assertTrue("终点落在文字的横向范围里", end.x in ink.left..ink.right)
        } else {
            // 规则第 10 条：这一段行间空档只有 12px，末折短到画不下箭头时省掉末折，
            // 箭头直接画在那条横线上、停在选区的近侧边线（2026-10-26 用户第 1 条）。
            assertEquals("省掉末折时停在选区近侧边线", ink.right, end.x, 0.01f)
            assertTrue("横线要落在两行之间：y=${end.y}", end.y in ink.bottom..rows[1].top)
        }
        // 文字在框的左边：第一段（竖线）之后第一段横线不许朝右（背离文字）跑。
        val firstHorizontal = pts.zipWithNext().first { (a, b) -> abs(a.y - b.y) < 0.01f }
        assertTrue("不许朝背离文字的方向起步：$pts", firstHorizontal.second.x <= firstHorizontal.first.x + 0.01f)
    }

    @Test
    fun `blocked side note goes over the line instead of squeezing past the word`() {
        // 真机第 8 + 2 条：左右两侧与文字之间有字阻隔时，规则第 2 条要「下横上 / 上横下」：
        // 竖着从框的上 / 下边出去、沿行间横穿、再竖着进目标字，而不是横出 → 竖走 → 横进的之字形。
        val ink = rect(100f, 100f, 300f, 140f)
        val blocker = rect(304f, 100f, 334f, 140f)
        val bubble = rect(340f, 100f, 440f, 140f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            glyphs = listOf(blocker),
            lineH = 32f,
            side = NoteSide.RIGHT,
        )
        val pts = arrowPoints(arrow!!)
        assertEquals("下横上正好三段、四个点：$pts", 4, pts.size)
        assertTrue("第一段要竖着从框的上 / 下边出去：$pts", abs(pts[0].x - pts[1].x) < 0.01f)
        assertTrue("最后一段要竖着进目标字：$pts", abs(pts[2].x - pts[3].x) < 0.01f)
        assertTrue(
            "第一段要从框边出发：$pts",
            abs(pts[0].y - bubble.top) < 0.01f || abs(pts[0].y - bubble.bottom) < 0.01f,
        )
        for (i in 1 until pts.size) {
            assertTrue(
                "引线不该压住中间的字：${pts[i - 1]} → ${pts[i]}",
                !segmentHits(pts[i - 1], pts[i], blocker),
            )
        }
    }

    @Test
    fun `leader takes the other side when another note blocks the middle of the gap`() {
        // 真机第 6 条 / 规则第 9 条：行间中线被别的批注占了，就走另一侧，不贴着字边抹过去。
        val anchorGlyph = rect(100f, 100f, 140f, 140f)
        val rowOne = listOf(anchorGlyph, rect(160f, 100f, 200f, 140f), rect(220f, 100f, 260f, 140f))
        val rowTwo = listOf(rect(100f, 172f, 140f, 212f), rect(160f, 172f, 200f, 212f))
        val bubble = rect(320f, 100f, 420f, 140f)
        // 另一条批注正好压在两行字之间（中线 y = 156）。
        val blocker = rect(270f, 136f, 400f, 158f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(anchorGlyph),
            glyphs = rowOne + rowTwo,
            otherBubbles = listOf(blocker),
            lineH = 32f,
            side = NoteSide.RIGHT,
        )
        val pts = arrowPoints(arrow!!)
        val crossing = pts.zipWithNext()
            .filter { (a, b) -> abs(a.y - b.y) < 0.01f && abs(a.x - b.x) > 1f }
            .map { (a, _) -> a.y }
        assertTrue(
            "中线被别的批注占了，要走另一侧：$pts",
            crossing.none { it > 140.5f && it < 171.5f },
        )
    }

    @Test
    fun `arrow head arms stay inside a short last segment`() {
        // 真机第 3 条：末段只剩几个像素时，箭头臂长还按死 5dp 画，臂根就退到上一段上，
        // 看上去像「横线末端长了个竖箭头」。臂长必须夹在末段以内，半宽随之等比缩小。
        val end = Offset(100f, 100f)
        val shortTail = Offset(100f, 97f)
        val (base, half) = noteArrowHead(end, shortTail, headPx = 12f, halfPx = 6f)
        assertTrue("臂根不能退到末段之外：$base", abs(base.y - end.y) <= 3.01f)
        assertEquals("臂根要落在末段方向上", 100f, base.x, 0.01f)
        assertTrue("末段短时箭头也要等比缩窄：half=$half", half > 0f && half < 6f)
        // 末段够长时照旧用满箭头。
        val (base2, half2) = noteArrowHead(end, Offset(60f, 100f), headPx = 12f, halfPx = 6f)
        assertEquals("横线箭头：臂根在末端左侧 12px", 88f, base2.x, 0.01f)
        assertEquals(6f, half2, 0.01f)
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

    /**
     * 引线规则第 9 条：**两个批注的引线不得交叉**。判据里交叉数排第一，
     * 所以哪怕多折一次、多绕一段，也要绕开已经画好的那条引线。
     */
    @Test
    fun `a leader goes around another leader instead of crossing it`() {
        // 框比文字高：出口除了正中对齐那三个点，还能从更上 / 更下的位置出去。
        val bubble = rect(400f, 60f, 500f, 180f)
        val ink = rect(100f, 100f, 300f, 140f)
        // 已经画好的一条竖引线，正挡在两者之间（把正对着的那几个出口全挡住）。
        val drawn = NoteArrow.Straight(Offset(350f, 105f), Offset(350f, 135f))
        // 下方一块图形：给折线一个「从下面绕」的落点（没有它，两折只能从正中间竖着穿过去）。
        val below = rect(340f, 160f, 380f, 180f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(ink),
            glyphs = listOf(below),
            drawnArrows = listOf(drawn),
            side = NoteSide.RIGHT,
        )
        assertTrue("应画得出箭头", arrow != null)
        val pts = arrowPoints(arrow!!)
        for (i in 1 until pts.size) {
            assertTrue(
                "引线不该和已画引线交叉：$pts",
                !segmentsCross(pts[i - 1], pts[i], drawn.start, drawn.end),
            )
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
        // 引线规则第 6 条：离选区右下角至少 0.5em（正好是末字中心），所以不再压到 300 的角上。
        assertTrue("终点要尽量靠末端（离角 0.5em），实际 x=${tip.x}", tip.x >= 280f)
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
        // 同上：右上角要避开，末端一侧最多到末字中心。
        assertTrue("终点要尽量靠末端（离角 0.5em），实际 x=${tip.x}", tip.x >= 280f)
    }

    @Test
    fun `single line corner note points at the near end of the line`() {
        // 2026-10-27 用户定口：右上角的默认位置是「首字右偏一个字」，框本来就贴着手边这几个字；
        // 引线要指「近哪头」那一头，不许横穿整行去找行尾（真机「以这种方式声明的变量」277px）。
        val glyphs = (0 until 5).map { rect(100f + it * 40f, 100f, 140f + it * 40f, 140f) }
        val bubble = rect(140f, 60f, 240f, 96f)
        val arrow = routeNoteArrow(bubble, glyphs, side = NoteSide.ABOVE_RIGHT)
        assertTrue("应画得出箭头", arrow != null)
        val points = arrowPoints(arrow!!)
        val tip = points.last()
        assertTrue("应指开头那个字，实际 x=${tip.x}", tip.x <= 170f)
        val len = points.zipWithNext().map { (a, b) -> abs(a.x - b.x) + abs(a.y - b.y) }.sum()
        assertTrue("引线要短，实际 $len：$points", len < 150f)
    }

    @Test
    fun `same corner offset gives the same leader for every note on the line`() {
        // 2026-10-27 用户定口：批注相对文字的缩进一样（都是首字右偏一个字），
        // 引线就该一样。旧实现把落点当成 0.5em 区间里的候选、按长短从几个比例里挑，
        // 同样的缩进会算出 2px / 8px / 14px 好几种横向。
        val glyphs = (0 until 5).map { rect(100f + it * 40f, 100f, 140f + it * 40f, 140f) }
        val cases = listOf(
            rect(140f, 60f, 240f, 96f),   // 上方，缝只有 4px
            rect(140f, 60f, 300f, 96f),   // 上方，框更宽（近端被框自己盖住）
            rect(140f, 144f, 240f, 184f), // 下方，缝同样只有 4px
        )
        val runs = cases.map { bubble ->
            val arrow = routeNoteArrow(
                bubble = bubble,
                inkRects = glyphs,
                side = if (bubble.top < 100f) NoteSide.ABOVE_RIGHT else NoteSide.BELOW_RIGHT,
            )
            assertTrue("应画得出箭头：$bubble", arrow != null)
            val points = arrowPoints(arrow!!)
            val tip = points.last()
            assertTrue("落点要在首字右偏 0.5em 处，实际 x=${tip.x}", abs(tip.x - 120f) < 0.5f)
            points.zipWithNext().map { (a, b) -> abs(a.x - b.x) }.sum()
        }
        assertEquals("同样缩进、同样一行，横向长度应一致：$runs", runs[0], runs[1], 0.5f)
        assertEquals("上方与下方摆法也应一致：$runs", runs[0], runs[2], 0.5f)
    }

    @Test
    fun `arrow stops at the line box edge instead of the glyph edge`() {
        // 引线规则第 4 条：引线和箭头画到**行盒边框**为止，不得侵入行盒内部。
        // 行盒上下一共比字多出 10px，箭头要停在行盒下边 150，而不是字的下边 140。
        val bubble = rect(140f, 220f, 420f, 260f)
        val glyph = rect(260f, 100f, 300f, 140f)
        val lineBox = rect(0f, 90f, 600f, 150f)
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(glyph),
            lineBoxes = listOf(lineBox),
            side = NoteSide.BELOW_RIGHT,
        )
        assertTrue("应画得出箭头", arrow != null)
        val tip = arrowPoints(arrow!!).last()
        assertEquals("箭头停在行盒下边", 150f, tip.y, 0.01f)
        assertTrue("横向仍落在末字附近，实际 x=${tip.x}", tip.x in 260f..300f)
    }

    @Test
    fun `a diagonal note gets an L shaped leader and a side note a single segment`() {
        // 引线规则第 1 / 2 条：上下左右摆法优先单线段，斜向摆法优先 L 形两段折线。
        val glyph = rect(200f, 140f, 240f, 180f)
        val diagonal = routeNoteArrow(
            bubble = rect(320f, 60f, 420f, 100f),
            inkRects = listOf(glyph),
            side = NoteSide.ABOVE_RIGHT,
        )
        assertTrue("斜向摆法应是 L 形：$diagonal", diagonal is NoteArrow.Elbow)
        assertEquals("L 形正好两段", 3, arrowPoints(diagonal!!).size)
        val straight = routeNoteArrow(
            bubble = rect(320f, 140f, 420f, 180f),
            inkRects = listOf(glyph),
            side = NoteSide.RIGHT,
        )
        assertTrue("正右摆法应是单线段：$straight", straight is NoteArrow.Straight)
    }

    @Test
    fun `a diagonal leader leaves from the box side even when the gap is wide`() {
        // 2026-10-27 用户定口：斜向画 L 是**约定**，不是「末段短到画不下才改道」的兜底。
        // 框离得远、末段够长时，路由器本来会从框底竖着出去再折（Z 形）；这里必须仍然
        // 从框的**侧边**横着出去，沿错开的那一个字走到选区角外侧，再竖着进字上。
        val glyph = rect(100f, 100f, 140f, 140f)
        val bubble = rect(300f, 20f, 400f, 60f)
        val arrow = routeNoteArrow(bubble, listOf(glyph), side = NoteSide.ABOVE_RIGHT)
        assertTrue("应画得出箭头", arrow != null)
        val pts = arrowPoints(arrow!!)
        assertEquals("斜向仍是两段 L：$pts", 3, pts.size)
        assertEquals("从框的侧边出去，不是框底：$pts", bubble.left, pts[0].x, 0.01f)
        assertTrue(
            "起点落在框边内部、避开角点：$pts",
            pts[0].y > bubble.top + 0.01f && pts[0].y < bubble.bottom - 0.01f,
        )
        assertEquals("竖段落在首字中心那一列", 120f, pts[1].x, 0.5f)
        assertEquals("末端接住字的上边", glyph.top, pts[2].y, 0.01f)
    }

    @Test
    fun `leader endpoints keep half an em away from the corners`() {
        // 引线规则第 6 条：起点与箭头末端不要画在框和文字选区的四个角点上，
        // 至少应向内偏移 0.5em。字高就是 1em，所以一个字的竖边只剩中点可用。
        val bubble = rect(400f, 100f, 500f, 200f)
        val glyph = rect(100f, 100f, 300f, 140f)
        val em = 40f
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = listOf(glyph),
            lineH = em,
            side = NoteSide.RIGHT,
        )
        assertTrue("应画得出箭头", arrow != null)
        val pts = arrowPoints(arrow!!)
        val start = pts.first()
        val end = pts.last()
        assertEquals("起点落在框的左边", bubble.left, start.x, 0.01f)
        assertEquals("末端落在选区的右边", glyph.right, end.x, 0.01f)
        assertTrue("起点不该贴着框的角：$start", !hugsCorner(start, bubble, em))
        assertTrue("末端不该贴着选区的角：$end", !hugsCorner(end, glyph, em))
    }

    /** 这个端点是不是「贴着某个角」（落在边上、且离最近的角不到 0.5em）。 */
    private fun hugsCorner(p: Offset, r: Rect, em: Float): Boolean {
        val half = em * 0.5f - 0.01f
        val onVerticalEdge = abs(p.x - r.left) < 0.01f || abs(p.x - r.right) < 0.01f
        val onHorizontalEdge = abs(p.y - r.top) < 0.01f || abs(p.y - r.bottom) < 0.01f
        val nearY = min(abs(p.y - r.top), abs(p.y - r.bottom)) < half
        val nearX = min(abs(p.x - r.left), abs(p.x - r.right)) < half
        return (onVerticalEdge && nearY) || (onHorizontalEdge && nearX)
    }

    @Test
    fun `above-right beats below within the same ring and line count`() {
        // 同一环、同样不折行时，右上压过正下（2026-10-27 方向顺序
        // 「右上 → 右下 → 左上 → 左下 → 正右 → 正左 → 正上 → 正下」）。
        fun candidate(side: NoteSide, ring: Int, left: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 0f, left + 60f, 24f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = ring,
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
        // 引线规则第 6 条：末端落在所选文字的边上。2026-10-25 用户定口后斜向只偏一个字，
        // 短选区的框会压住选区一段，此时引线就近从框的左边进来，落点仍在**选区范围内**、
        // 不再要求贴住右角——绕到右角反而是多走一截。
        assertTrue(
            "终点要落在所选文字下面：actual x=${end.x}，选区 ${anchor.left}..${anchor.right}",
            end.x >= anchor.left - 0.5f && end.x <= anchor.right + 0.5f,
        )
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
        // 规则 5 的顺序里右上在右下之前，所以这里拿到的是右上。
        val best = list.first { it.complete }
        assertEquals(NoteSide.ABOVE_RIGHT, best.side)
        assertTrue("应落在页边距，实际 ${best.rect}", best.rect.left >= 200f)
    }

    @Test
    fun `narrow slit keeps a folded candidate down to one character`() {
        // 规则第 11 条：空间不够就折叠，最多折到一个字 + 省略号；只有连一个字都放不下才没有候选。
        val anchor = rect(0f, 100f, 200f, 140f)
        val slit = rect(200f, 100f, 240f, 125f)
        val list = candidates(note = noteInk(1, listOf(anchor), text = "用几章来"), freeRects = listOf(slit))
        assertTrue("窄缝也要留着折叠候选，实际=$list", list.isNotEmpty())
        assertTrue("折叠候选一定不是整句", list.none { it.complete })
        assertTrue("折叠后至少留一个字", list.all { it.shownChars >= 1 })
        // 宽度连一个字都放不下时，才真的没有候选。
        val tooTight = candidates(
            note = noteInk(1, listOf(anchor), text = "用几章来"),
            freeRects = listOf(rect(200f, 100f, 210f, 125f)),
        )
        assertTrue("连一个字都放不下就不该有候选，实际=$tooTight", tooTight.isEmpty())
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

    /** 2026-10-27 用户定口：满行的左右页边距可以用，但排在四个角之后。 */
    @Test
    fun `right side of a full row is available in its own margin column`() {
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
            "满行自己的右页边距也要生成：${midLine.map { "${it.side}#${it.ring}" }}",
            midLine.any {
                it.side == NoteSide.RIGHT && it.ring == 0 &&
                    it.rect.bottom > row0.top && it.rect.top < row0.bottom
            },
        )
        assertTrue(
            "下一行的正右算相邻空间（对本行是右下）：${midLine.map { "${it.side}#${it.ring}" }}",
            midLine.any {
                it.side == NoteSide.BELOW_RIGHT && it.ring == 0 && it.rect.top >= row0.bottom - 0.5f
            },
        )
    }

    /** 方向名次：四个角 → 正右 → 正左 → 正上 → 正下（2026-10-27 用户定口）。 */
    @Test
    fun `direction order puts the four corners first and above below last`() {
        fun candidate(side: NoteSide) = NoteCandidate(
            annotationId = 1,
            rect = rect(100f, 100f, 160f, 124f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            distance = 5f,
        )
        val sides = listOf(
            NoteSide.LEFT, NoteSide.BELOW, NoteSide.ABOVE_LEFT,
            NoteSide.ABOVE_RIGHT, NoteSide.RIGHT, NoteSide.ABOVE, NoteSide.BELOW_LEFT,
            NoteSide.BELOW_RIGHT,
        )
        val ordered = orderedNoteCandidates(sides.map { candidate(it) }, rightTaken = false)
        assertEquals(
            "方向名次不对：${ordered.map { it.side }}",
            // 2026-10-27 用户定口：上 / 下压到四个角、正右、正左之后；
            // 它们要占 1em 的竖线空档，推不出空档时宁可不摆。
            listOf(
                NoteSide.ABOVE_RIGHT,
                NoteSide.BELOW_RIGHT,
                NoteSide.ABOVE_LEFT,
                NoteSide.BELOW_LEFT,
                NoteSide.RIGHT,
                NoteSide.LEFT,
                NoteSide.ABOVE,
                NoteSide.BELOW,
            ),
            ordered.map { it.side },
        )
    }

    /**
     * 满行的页边距那一列几何上算「右上」，名次却已被改写成正右（10）。
     * 规则第 8 条的「右侧只剩本行最右那条」例外只认**真斜向**（0..3），
     * 不能把它提回 0 号去抢四个角的位置（2026-10-27）。
     */
    @Test
    fun `page margin corner keeps its side rank under the corner first exception`() {
        fun candidate(side: NoteSide, rank: Int, left: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 100f, left + 60f, 124f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            distance = 5f,
            excessDistance = 0f,
            sideRank = rank,
        )
        val marginCorner = candidate(NoteSide.ABOVE_RIGHT, noteSideRank(NoteSide.RIGHT), 300f)
        val realCorner = candidate(NoteSide.ABOVE_RIGHT, noteSideRank(NoteSide.ABOVE_RIGHT), 400f)
        val ordered = listOf(marginCorner, realCorner)
            .sortedWith(noteCandidateOrder(rightTaken = false, rightCornerFirst = true))
        assertEquals(
            "页边距那一列不能被「斜向优先」例外提回 0：${ordered.map { it.rect.left }}",
            realCorner.rect.left,
            ordered.first().rect.left,
            0.01f,
        )
    }

    @Test
    fun `corner note prefers the one character offset over a farther higher blank`() {
        // 2026-10-27 用户定口（真机「以这种方式声明的变量」）：右上角的默认位置就是
        // 「首字右偏一个字」。上一行行尾形成的页边距列也能凑出右上（更靠右、框还更高），
        // 但那是被拉远的那个；就近距离相同时必须先比离基准的横向偏移，不能再被 rect.top 反超。
        fun candidate(left: Float, top: Float, offset: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, top, left + 129.6f, top + 36.8f),
            freeRect = rect(0f, 0f, 2000f, 2800f),
            lines = 1,
            complete = true,
            shownChars = 9,
            side = NoteSide.ABOVE_RIGHT,
            ring = 0,
            distance = 14.3f,
            excessDistance = 0f,
            cornerOffset = offset,
        )
        val baseline = candidate(1259.6f, 1288.2f, offset = 0f)
        val far = candidate(1510f, 1275.9f, offset = 250.4f)
        val ordered = orderedNoteCandidates(listOf(far, baseline), rightTaken = false)
        assertEquals(
            "要靠「首字右偏一个字」那个：${ordered.map { "${it.rect.left}@${it.rect.top}" }}",
            baseline.rect.left,
            ordered.first().rect.left,
            0.01f,
        )
    }

    /**
     * 2026-10-27 用户定口：四个斜向**贴着那一行、只留 2px**，不要推远。
     *
     * 横向错开一个字不是留白，而是给引线留的走廊：引线从框的侧边横着出去，
     * 沿走廊走到选区角外侧，再竖着进字下 / 字上，折成 L 形（`routeNoteArrow` 的收尾）。
     * 推到 1em 就把批注送离锚点，读者反而看不出它在说哪段字。
     */
    @Test
    fun `diagonal hugs the row to keep the L corridor`() {
        val row = rect(0f, 100f, 200f, 140f)
        val list = candidates(
            note = noteInk(1, listOf(row), "想法"),
            freeRects = listOf(rect(0f, 141.44f, 700f, 800f)),
        )
        val belowRight = list.first { it.side == NoteSide.BELOW_RIGHT }
        assertEquals(
            "近侧贴着行（2px 的 L 走廊）：top=${belowRight.rect.top}",
            140f + 2f,
            belowRight.rect.top,
            0.5f,
        )
        assertEquals(
            "横向基准是首字右移一个汉字：left=${belowRight.rect.left}",
            20f,
            belowRight.rect.left,
            0.5f,
        )
        assertTrue(
            "同一块空白里仍先给远侧 1em 的候选：${list.filter { it.side == NoteSide.BELOW_RIGHT }.map { it.marginTier }}",
            belowRight.marginTier == 0,
        )
    }

    /** 2026-10-27 用户定口：空间不够先收**远侧**的 1em，近侧那 2px 的走廊不动。 */
    @Test
    fun `diagonal gives up the far side before squeezing the box`() {
        val row = rect(0f, 100f, 200f, 140f)
        val oneLine = 4 * noteFont + notePad
        // 空白只够「偏一个字 + 整句一行 + 远侧 0.1em」：首选档（远侧 1em）折成两行。
        val tight = rect(0f, 141.44f, 20f + oneLine + noteMetrics.marginX + 2f, 800f)
        val list = candidates(
            note = noteInk(1, listOf(row), "想法想法"),
            freeRects = listOf(tight),
        )
        val nearSide = list.filter { it.side == NoteSide.BELOW_RIGHT }
        assertTrue("近侧一律贴着行：${nearSide.map { it.rect.top }}", nearSide.all { abs(it.rect.top - 142f) < 1f })
        assertEquals(
            "斜向的横向基准是首字右移一个汉字（收远侧时也不往左越界）",
            20f,
            nearSide.minOf { it.rect.left },
            0.5f,
        )
        val oneLinePick = nearSide.firstOrNull { it.lines == 1 }
        assertTrue("收远侧后能一行读完：$nearSide", oneLinePick != null)
        assertTrue(
            "一行读完的候选只收远侧、不再折行：tier=${oneLinePick!!.marginTier}",
            oneLinePick.marginTier <= 1,
        )

        // 下方只剩一条窄带：斜向仍然贴进去（近侧只要 2px，比正上 / 正下的 1em 门槛低）。
        val band = rect(0f, 140f + noteMetrics.marginY, 700f, 140f + noteMetrics.marginY + noteLine + 2f)
        val squeezedList = candidates(
            note = noteInk(1, listOf(row), "想法"),
            freeRects = listOf(band),
        )
        val squeezed = squeezedList.first { it.side == NoteSide.BELOW_RIGHT }
        assertEquals(
            "近侧压到空白上缘（规则 13 的 0.1em 下限）：top=${squeezed.rect.top}",
            140f + 2f,
            squeezed.rect.top,
            0.5f,
        )
    }

    /**
     * 2026-10-28 用户定口：斜向「错开一个字」量的是**选区正文右缘**（选区矩形），不是逐字墨迹。
     *
     * 斜体字后面那截后侧留白也属于选区（真机「使用 var」的选区右缘比 'r' 的墨迹宽 33px）。
     * 按墨迹量会把框往左白推一截；按选区量，框右缘正好落在选区右缘左一个汉字处。
     * 引线走廊不会因此变窄——布线侧也改成按选区矩形量 0.5em 收角（`cornerInk`），
     * 走廊 = 一个字 − 0.5em，L 形照常折得出来。
     */
    @Test
    fun `corner offset measures the selection box and the L corridor stays half an em`() {
        val row = rect(100f, 0f, 600f, 20f)
        // 逐字墨迹：10 个字各 20 宽，右缘 300。
        val glyphs = (0 until 10).map { rect(100f + it * 20f, 0f, 120f + it * 20f, 20f) }
        // 选区矩形右缘 330：末字（墨迹 280~300）后面 30px 是字的后侧留白（斜体就是这样）。
        val boxes = (0 until 9).map { rect(100f + it * 20f, 0f, 120f + it * 20f, 20f) } +
            rect(280f, 0f, 330f, 20f)
        val note = NoteInk(
            annotationId = 1,
            text = "一二三四五六七八九十甲乙丙丁",
            argb = 0xFF3366CC.toInt(),
            rects = boxes,
            arrowRects = glyphs,
        )
        val lines = listOf(NoteTextLine(rect = row, blockId = 0, index = 0))
        val cands = candidates(
            note = note,
            freeRects = listOf(rect(0f, 22f, 700f, 800f)),
            lines = lines,
        )
        val belowLeft = cands.filter { it.side == NoteSide.BELOW_LEFT && it.ring == 0 }
        assertTrue("左下没生成：${cands.map { "${it.side}#${it.ring}" }}", belowLeft.isNotEmpty())
        // 选区右缘 330 − 一个汉字 20 = 310（按墨迹会是 300 − 20 = 280，那是白往左推 30px）。
        assertEquals(
            "横向基准要按选区矩形量（选区右缘 330 − 一个汉字 20）：" +
                "right=${belowLeft.first().rect.right}",
            310f,
            belowLeft.first().rect.right,
            0.5f,
        )

        val bubble = belowLeft.first().rect
        val arrow = routeNoteArrow(
            bubble = bubble,
            inkRects = glyphs,
            glyphs = glyphs,
            lineH = noteLine,
            side = NoteSide.BELOW_LEFT,
            lineBoxes = listOf(row),
            targetInk = glyphs,
            cornerInk = boxes,
        )
        assertTrue("四角要折 L，不能退化成一点：$arrow", arrow is NoteArrow.Elbow)
        val pts = arrowPoints(arrow!!)
        // 走廊按选区矩形量：落点 = 选区右缘 − 0.5em = 320，框右缘 310，横段正好 10px（半个 em）。
        val legX = pts[pts.size - 1].x
        assertEquals("竖段要落在选区右缘左半个 em 处：$arrow", 320f, legX, 0.5f)
        assertEquals("横段就是半个 em 的走廊：$arrow", 10f, abs(legX - bubble.right), 0.5f)
        val tail = abs(pts[pts.size - 1].y - pts[pts.size - 2].y)
        assertTrue("末段竖线要够画箭头（≥8px）：$arrow", tail >= 8f)
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

    /**
     * 2026-10-24 用户定口：**让位优先于隔行**。本行右侧那一列只要挪一挪就能放下，
     * 就先请既有批注让位，而不是跑到上一行 / 下一行的同一列去。
     *
     * 版面：四条窄行，行间只够一行旁注的 0.85 倍（正下 / 正上 都放不下），
     * 右侧那一列**跨这四行**因此每一行都有候选（隔行也有地方可去）。
     */
    @Test
    fun `making room on this row beats moving to a neighbour row`() {
        val row0 = rect(0f, 100f, 300f, 140f)
        val anchorRow = rect(0f, 220f, 300f, 260f)
        val row1 = rect(0f, 300f, 300f, 340f)
        val rows = listOf(row0, anchorRow, row1)
        val lines = rows.mapIndexed { i, r -> NoteTextLine(rect = r, blockId = 0, index = i) }
        val notes = listOf(
            noteInk(1, listOf(rect(100f, 220f, 140f, 260f)), "想法"),
            noteInk(2, listOf(rect(0f, 220f, 40f, 260f)), "也短"),
        )
        fun page(ids: Set<Long>) = place(
            notes = notes.filter { it.annotationId in ids },
            textInk = rows,
            textLines = lines,
            figures = listOf(
                // 正文那一列除本行外全是「字」：正上 / 正下 放不下。
                rect(0f, 141f, 300f, 219f),
                rect(0f, 261f, 300f, 299f),
                // 右侧那一列只有本行上下这一段：正右放得下，右下 / 右上放不下。
                rect(300f, 0f, 700f, 200f),
                rect(300f, 282f, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        val alone = page(setOf(1L)).single()
        val both = page(setOf(1L, 2L))
        assertEquals("两条都要留住：$both", 2, both.size)
        assertTrue("两条都要整句读完：$both", both.none { it.compact })
        val first = both.first { it.annotationId == 1L }
        val second = both.first { it.annotationId == 2L }
        assertEquals("让位后仍在同一列：${both.map { it.leftPx }}", alone.leftPx, second.leftPx, 0.5f)
        assertTrue(
            "该留在本行右侧这一列，而不是隔行（隔行候选在 y≈120 / 320）：top=${second.topPx}",
            second.topPx > 200f && second.topPx < 280f,
        )
        assertTrue(
            "让位：先占那条必须挪开（单独放置时 top=${alone.topPx}，现在 top=${first.topPx}）",
            abs(first.topPx - alone.topPx) > 1f,
        )
    }

    /** 2026-10-24 用户定口：让位后新批注**折行**也要能放下（不是只认一行）。 */
    @Test
    fun `make room can place a note that needs several lines`() {
        val row = rect(0f, 200f, 600f, 240f)
        val notes = listOf(
            noteInk(1, listOf(rect(100f, 200f, 140f, 240f)), "想法"),
            noteInk(2, listOf(rect(0f, 200f, 40f, 240f)), "也短也短也短"),
        )
        // 整页只有右侧那一列空白，且这一列只有本行上下这一段（宽约 5 个字）：
        // 这一句只能折成两行，让位也必须认折行。
        val page = place(
            notes = notes,
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 700f, 200f),
                rect(0f, 242f, 600f, 282f),
                rect(0f, 282f, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        assertEquals("两条都要留住：$page", 2, page.size)
        assertTrue("两条都要整句读完：$page", page.none { it.compact })
        val first = page.first { it.annotationId == 1L }
        val second = page.first { it.annotationId == 2L }
        assertTrue("列窄，这句要折成多行：${second.maxLines}", second.maxLines >= 2)
        // 同一列叠放：正右从「行右 + 1em」起，斜向摆法贴住空白左缘，两者相差不超过一个 1em，
        // 但都在页边距那一列里（规则第 6 条），所以按「同一列」判，不按像素相等判。
        assertTrue(
            "让位后仍在同一列：${page.map { it.leftPx }}",
            abs(first.leftPx - second.leftPx) <= noteMetrics.sideGap + 0.5f,
        )
        val sorted = page.sortedBy { it.topPx }
        val gap = sorted[1].topPx - (sorted[0].topPx + sorted[0].maxLines * noteLine)
        assertTrue("上下叠放且留出 0.1em 间距：gap=$gap", gap >= noteMetrics.marginY - 0.5f)
    }

    /**
     * 规则第 8 条后半：上下让不动了（这一列只有一行高），就看**挡路框右侧**还有没有地方，
     * 让新批注贴着旧框右边站，而不是把新批注挤成小图标。
     */
    @Test
    fun `make room falls back to the space right of the blocking bubble`() {
        val row = rect(0f, 100f, 200f, 140f)
        val notes = listOf(
            noteInk(1, listOf(rect(0f, 100f, 40f, 140f)), "短"),
            noteInk(2, listOf(rect(60f, 100f, 100f, 140f)), "想法"),
        )
        // 右侧那一列只有本行这一行高（上下都被图挡住），横向却有整片空白：
        // 上下叠不下第二条，只能往第一条的右边找空。
        val page = place(
            notes = notes,
            textInk = listOf(row),
            figures = listOf(
                rect(0f, 0f, 700f, 100f),
                rect(0f, 141f, 700f, 800f),
            ),
            contentBottom = 800f,
            pageRight = 700f,
            pageBottom = 800f,
        )
        assertEquals("两条都要留住：$page", 2, page.size)
        assertTrue("两条都要整句读完：$page", page.none { it.compact })
        // 这一列只有一行高，叠不下第二条：必须左右并排（后到的那条贴在先到那条的右边）。
        val left = page.minBy { it.leftPx }
        val right = page.maxBy { it.leftPx }
        assertTrue(
            "两条要左右并排，不是叠在同一列：${page.map { it.leftPx to it.widthPx }}",
            right.leftPx >= left.leftPx + left.widthPx - 0.5f,
        )
        assertTrue(
            "并排还要贴在同一行：${page.map { it.topPx }}",
            abs(left.topPx - right.topPx) < noteLine,
        )
    }

    /**
     * 2026-10-24 用户定口：**上一行右侧那块空白，对本行来说就是右上角**——
     * 不能因为斜向基准点落在左边，就把正文右侧的大空白整块挤掉。
     */
    @Test
    fun `previous row right blank is an upper right for this row`() {
        val above = rect(0f, 60f, 300f, 100f)
        val full = rect(0f, 120f, 600f, 160f)
        val lines = listOf(
            NoteTextLine(rect = above, blockId = 0, index = 0),
            NoteTextLine(rect = full, blockId = 0, index = 1),
        )
        val list = candidates(
            note = noteInk(1, listOf(rect(0f, 120f, 80f, 160f)), "想法"),
            freeRects = listOf(rect(302f, 0f, 700f, 800f)),
            lines = lines,
        )
        // 规则第 5 条：上一行的左右属于本行相邻空间，所以它现在就落在第 0 圈；
        // 规则第 9 条：别的行的正右对本行就是斜向，所以侧别是「右上」而不是「正右」。
        val upperRight = list.filter { it.side == NoteSide.ABOVE_RIGHT && it.ring == 0 }
        assertTrue("上一行右侧该有候选：${list.map { "${it.side}#${it.ring}" }}", upperRight.isNotEmpty())
        assertTrue(
            "框落在上一行那一段：${upperRight.map { it.rect.top }}",
            upperRight.any { it.rect.bottom > 60f && it.rect.top < 100f },
        )
    }

    /**
     * 规则第 10 条：**跨越其他标注视同跨行**——同一圈里，要越过别人那条排到没越过的后面。
     */
    @Test
    fun `a candidate that crosses another note counts as one more row`() {
        val rows = (0 until 6).map { rect(0f, it * 40f, 300f, it * 40f + 40f) }
        val lines = rows.mapIndexed { i, r -> NoteTextLine(rect = r, blockId = 0, index = i) }
        val list = candidates(
            note = noteInk(1, listOf(rect(0f, 80f, 100f, 120f)), "想法"),
            freeRects = listOf(rect(300f + noteMetrics.sideGap, -200f, 700f, 1000f)),
            lines = lines,
            otherRowLeft = mapOf(1 to 100f),
        )
        val ring1 = list.filter { it.ring == 1 }
        val up = ring1.first { it.rect.bottom <= rows[0].bottom + 1f }
        val down = ring1.first { it.rect.top >= rows[4].top - 1f }
        assertEquals("上方那条要越过上一行的批注", 1, up.crossed)
        assertEquals("下方那条没越过任何批注", 0, down.crossed)
        assertEquals(
            "越过别人的那条排后面",
            down.rect.top,
            orderedNoteCandidates(ring1, rightTaken = false).first().rect.top,
        )
    }

    /**
     * 规则第 1 条（2026-10-27 用户定口）：**环数是绝对第一优先，不允许被整句突破**。
     * 折成省略号的环 0 也排在整句的环 4 之前；同一环里整句才优先。
     */
    @Test
    fun `a nearer folded candidate beats a farther complete one`() {
        fun candidate(ring: Int, complete: Boolean, lines: Int) = NoteCandidate(
            annotationId = 1,
            rect = rect(600f, ring * 40f, 700f, ring * 40f + 40f),
            freeRect = rect(600f, 0f, 700f, 800f),
            lines = lines,
            complete = complete,
            shownChars = if (complete) 6 else 1,
            side = NoteSide.RIGHT,
            ring = ring,
            distance = 10f + ring,
        )
        val foldedNear = candidate(ring = 0, complete = false, lines = 1)
        val completeFar = candidate(ring = 4, complete = true, lines = 3)
        val ordered = orderedNoteCandidates(listOf(foldedNear, completeFar), rightTaken = false)
        assertEquals("近环截断也要先排：$ordered", foldedNear, ordered.first())

        // 同一环里仍是整句优先：先换更宽的空白，再截断（规则第 7 条）。
        val foldedSameRing = candidate(ring = 0, complete = false, lines = 1)
            .copy(rect = rect(600f, 0f, 700f, 40f), distance = 10f)
        val completeSameRing = candidate(ring = 0, complete = true, lines = 2)
            .copy(rect = rect(600f, 40f, 700f, 80f), distance = 20f)
        assertEquals(
            "同一环里整句先排",
            completeSameRing,
            orderedNoteCandidates(listOf(foldedSameRing, completeSameRing), rightTaken = false).first(),
        )
    }

    /**
     * 2026-10-25 用户定口：**本段落 = 空白连成一片的区域**，占满整个版心的行把空白切断，
     * 页边距上下相通也不算连成一片——所以隔着一行顶满版心的字，就不许再往那边的短行上放。
     */
    @Test
    fun `a full width line cuts the usable range`() {
        val row0 = rect(0f, 0f, 600f, 40f)
        val full = rect(0f, 40f, 600f, 80f)
        val short = rect(0f, 80f, 300f, 120f)
        val lines = listOf(
            NoteTextLine(rect = row0, blockId = 0, index = 0),
            NoteTextLine(rect = full, blockId = 1, index = 1),
            NoteTextLine(rect = short, blockId = 2, index = 2),
        )
        val list = candidates(
            note = noteInk(1, listOf(rect(0f, 0f, 100f, 40f)), "想法"),
            freeRects = listOf(rect(600f + noteMetrics.sideGap, 0f, 700f, 800f)),
            lines = lines,
        )
        assertTrue(
            "隔着顶满版心的行，不该再往那边的短行上放：${list.map { it.rect.top }}",
            list.none { it.rect.top >= 80f },
        )
    }

    /** 反过来：中间那行没顶满版心，两侧空白就连成一片，那边也算本段落可用范围。 */
    @Test
    fun `a short line keeps the usable range connected`() {
        val row0 = rect(0f, 0f, 600f, 40f)
        val shortMid = rect(0f, 40f, 300f, 80f)
        val short = rect(0f, 80f, 300f, 120f)
        val lines = listOf(
            NoteTextLine(rect = row0, blockId = 0, index = 0),
            NoteTextLine(rect = shortMid, blockId = 1, index = 1),
            NoteTextLine(rect = short, blockId = 2, index = 2),
        )
        val list = candidates(
            note = noteInk(1, listOf(rect(0f, 0f, 100f, 40f)), "想法"),
            freeRects = listOf(rect(600f + noteMetrics.sideGap, 0f, 700f, 800f)),
            lines = lines,
        )
        assertTrue(
            "空白连成一片时那边可以用：${list.map { it.rect.top }}",
            list.any { it.rect.top >= 80f },
        )
    }

    /**
     * 2026-10-27 用户定口：行距那点净空（不到 1em）不再用来塞批注。
     *
     * 原先批注能贴进两行之间：近侧只留 0.1em，引线从框顶到字底只剩几像素，
     * 真机上箭头等于没画。现在宁可改走右侧页边距那一列。
     */
    @Test
    fun `a note no longer squeezes into a line gap narrower than one em`() {
        val row0 = rect(0f, 0f, 600f, 60f)
        val row1 = rect(0f, 60f, 600f, 120f)
        // 字墨迹只有 30 高：两行之间净空 30，扣掉近侧 1em（14.4）就放不下一行旁注（19.87）。
        val glyphs = listOf(rect(0f, 15f, 600f, 45f), rect(0f, 75f, 600f, 105f))
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 15f, 40f, 45f)), "想法")),
            textInk = listOf(row0, row1),
            textLines = listOf(
                NoteTextLine(rect = row0, blockId = 0, index = 0),
                NoteTextLine(rect = row1, blockId = 0, index = 1),
            ),
            arrowInk = glyphs,
            // 整行顶到版心右缘：版心内没有余地，只剩右侧页边距那一列。
            contentRight = 600f,
            pageRight = 700f,
        )
        val b = bubbles.single()
        assertTrue("不该把批注挤成小图标：$b", !b.compact)
        assertTrue(
            "该改走右侧页边距那一列：left=${b.leftPx}",
            b.leftPx >= 600f - 0.5f,
        )
    }

    /** 文字障碍只按**字墨迹**收纵向：横向保持整行，免得把词间小缝当成空白。 */
    @Test
    fun `text obstacles tighten to glyph ink vertically only`() {
        val line = rect(0f, 100f, 600f, 169f)
        val glyphs = listOf(rect(40f, 110f, 70f, 158f), rect(80f, 112f, 110f, 156f))
        val tight = tightTextInk(listOf(line), glyphs).single()
        assertEquals(0f, tight.left, 0.01f)
        assertEquals(600f, tight.right, 0.01f)
        assertEquals(110f, tight.top, 0.01f)
        assertEquals(158f, tight.bottom, 0.01f)
        assertEquals("没有逐字几何就原样保留行盒", listOf(line), tightTextInk(listOf(line), emptyList()))
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
    fun `sliver that fits one character stops folding`() {
        val m = NoteMetrics.forBody(20f)
        // 2026-10-26 规则 4：**折出来的行数不得超过单行字数**。
        // 一行只放得下一个字时就只排一行，不再往竖里叠成一条；能读一个字就还画框。
        val size = noteBoxFit("字".repeat(10), m, maxWidth = m.em + m.padTotal, maxHeight = m.lineH * 3.5f)
        assertEquals("行数不得超过单行字数", 1, size.lines)
        assertEquals("只排一行就只读得到一个字", 1, size.shownChars)
        assertTrue("能读一个字就该显示", size.readable)
        assertTrue(!size.complete)
        // 连一个字都放不下（宽度被内缩吃光）才算不可用。
        val none = noteBoxFit("字".repeat(10), m, maxWidth = m.padTotal, maxHeight = m.lineH * 3.5f)
        assertTrue("一个字都放不下就不该出框", !none.readable)
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

    /** 2026-10-26 规则 1：环数排在折行数之前——隔一行的单行候选不许压过本行折三行的。 */
    @Test
    fun `ring decides before line count`() {
        fun candidate(ring: Int, lines: Int) = NoteCandidate(
            annotationId = 1,
            rect = rect(0f, 100f + ring * 40f, 60f, 100f + ring * 40f + 24f * lines),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = lines,
            complete = true,
            shownChars = 4,
            side = NoteSide.RIGHT,
            ring = ring,
            distance = if (ring == 0) 2f else 1f,
            sideRank = noteSideRank(NoteSide.RIGHT),
        )
        val wrapped = candidate(ring = 0, lines = 3)
        val oneLine = candidate(ring = 1, lines = 1)
        val ordered = orderedNoteCandidates(listOf(oneLine, wrapped), rightTaken = false)
        assertEquals(
            "环数先于折行数：${ordered.map { it.ring to it.lines }}",
            0,
            ordered.first().ring,
        )
    }

    /** 2026-10-26 规则 3 例外二：右上 / 右下完整挡住右侧批注的出线通道时，先让到左上 / 左下。 */
    @Test
    fun `blocking the right note exit gives way to the left corner`() {
        val exit = rect(400f, 80f, 420f, 100f)
        fun candidate(side: NoteSide, left: Float) = NoteCandidate(
            annotationId = 1,
            rect = rect(left, 60f, left + 60f, 100f),
            freeRect = rect(0f, 0f, 700f, 800f),
            lines = 1,
            complete = true,
            shownChars = 4,
            side = side,
            ring = 0,
            distance = 10f,
            sideRank = noteSideRank(side),
        )
        // 右上框 380..440 完整盖住 400..420 的出线通道；左上的框在 260..320，不盖。
        val aboveRight = candidate(NoteSide.ABOVE_RIGHT, 380f)
        val aboveLeft = candidate(NoteSide.ABOVE_LEFT, 260f)
        val free = orderedNoteCandidates(listOf(aboveRight, aboveLeft), rightTaken = false)
        assertEquals("没有出线冲突时右上仍是首选", NoteSide.ABOVE_RIGHT, free.first().side)
        val blocked = orderedNoteCandidates(
            listOf(aboveRight, aboveLeft),
            rightTaken = false,
            rightExit = exit,
        )
        assertEquals("完整挡住右侧出线时先走左上", NoteSide.ABOVE_LEFT, blocked.first().side)
    }

    /** 2026-10-26 规则 3：左侧缩进时只有本行最左那条可以优先走左侧。 */
    @Test
    fun `only the leftmost note may prefer the left on an indented row`() {
        val row = rect(160f, 0f, 420f, 40f)
        val leftInk = rect(160f, 0f, 200f, 40f)
        val rightInk = rect(380f, 0f, 410f, 40f)
        val bubbles = place(
            notes = listOf(
                noteInk(1, listOf(leftInk), "左"),
                noteInk(2, listOf(rightInk), "右"),
            ),
            textInk = listOf(row),
            textLines = listOf(NoteTextLine(rect = row, blockId = 0, index = 0)),
            contentLeft = 40f,
            contentRight = 600f,
            pageRight = 640f,
            contentBottom = 800f,
            pageBottom = 800f,
        )
        assertEquals(2, bubbles.size)
        val left = bubbles.single { it.annotationId == 1L }
        val right = bubbles.single { it.annotationId == 2L }
        assertTrue("最左那条应优先落在左侧缩进里：left=${left.leftPx}", left.leftPx < 160f)
        assertTrue("最右那条仍拿正右：left=${right.leftPx}", right.leftPx >= 420f)
        assertTrue("两条不能重叠", !boxOf(left).overlaps(boxOf(right)))
    }

    /**
     * 规则 5（2026-10-27 用户定口）：同一块大空白里有多条批注时，要综合安排：
     * 长的放外侧、短的放内侧，把靠近正文的走廊让给短批注的引线。
     *
     * 这里直接给两条同一右侧空白、文字位置相邻的批注：长的先落在内侧（left=214），
     * 短的被挤到外侧（left=264）。综合安排后两条交换内外位置。
     */
    @Test
    fun `long note moves outside so the short note can keep the inner corridor`() {
        val blank = rect(200f, 0f, 500f, 80f)
        fun candidate(
            id: Long,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
        ) = NoteCandidate(
            annotationId = id,
            rect = rect(left, top, left + width, top + height),
            freeRect = blank,
            lines = if (height > noteLine * 1.5f) 3 else 1,
            complete = true,
            shownChars = 9,
            side = NoteSide.RIGHT,
            ring = 0,
            distance = left - 200f,
        )
        val longNote = noteInk(1, listOf(rect(0f, 0f, 60f, 40f)), "长批注长批注长批注")
        val shortNote = noteInk(2, listOf(rect(0f, 40f, 60f, 80f)), "短")
        val longChoice = NoteChoice(longNote, candidate(1, 214f, 0f, 50f, 60f))
        val shortChoice = NoteChoice(shortNote, candidate(2, 264f, 50f, 24f, 20f))
        val arranged = arrangeSharedBlankNotes(
            occupied = listOf(longChoice, shortChoice),
            marginX = noteMetrics.marginX,
            marginY = noteMetrics.marginY,
        )
        val long = arranged.single { it.note.annotationId == 1L }
        val short = arranged.single { it.note.annotationId == 2L }
        assertTrue(
            "长的应到外侧：long=${long.candidate.rect} short=${short.candidate.rect}",
            long.candidate.rect.left > short.candidate.rect.left + 1f,
        )
        assertTrue(
            "短的应回到内侧：long=${long.candidate.rect} short=${short.candidate.rect}",
            short.candidate.rect.left < 220f,
        )
        assertTrue(
            "两条不能重叠",
            !long.candidate.conflicts(short.candidate, noteMetrics.marginX, noteMetrics.marginY),
        )
    }

    /**
     * 规则 5 的口径（2026-10-27 用户定口）：收尾只看**同一个大的空白区**，
     * 不要求两条侧别相同，也不要求文字位置相邻。真机上「讨论」（正下，43 字）压在最上面，
     * 「函数」（右下，17 字）在它下面，两者选区的横向距离很远；按旧口径（同侧一族的过滤）
     * 它们永远不会被放到一起比较。按新口径应把长的换到外侧（下方）、短的换回内侧。
     */
    @Test
    fun `long note moves outside across sideways siblings in the same blank`() {
        val blank = rect(788f, 1689f, 1823f, 2020f)
        fun candidate(id: Long, side: NoteSide, r: Rect, lines: Int) = NoteCandidate(
            annotationId = id,
            rect = r,
            freeRect = blank,
            lines = lines,
            complete = true,
            shownChars = 9,
            side = side,
            ring = 0,
            distance = 0f,
        )
        val longNote = NoteChoice(
            noteInk(118L, listOf(rect(1341f, 1627f, 1434f, 1696f)), "讨论讨论讨论讨论讨论讨论讨论讨论讨论"),
            candidate(118L, NoteSide.BELOW, rect(788f, 1751f, 1823f, 1843f), 2),
        )
        val shortNote = NoteChoice(
            noteInk(119L, listOf(rect(733f, 1627f, 826f, 1696f)), "函数就是功能呀！老外的词汇太贫乏"),
            candidate(119L, NoteSide.BELOW_RIGHT, rect(920f, 1849f, 1528f, 1895f), 1),
        )
        val neighbor = NoteChoice(
            noteInk(112L, listOf(rect(874f, 1627f, 1060f, 1696f)), "用几章来"),
            candidate(112L, NoteSide.BELOW_RIGHT, rect(788f, 1698f, 950f, 1744f), 1),
        )
        val second = NoteChoice(
            noteInk(128L, listOf(rect(1201f, 1627f, 1294f, 1696f)), "详细"),
            candidate(128L, NoteSide.BELOW_RIGHT, rect(1247f, 1698f, 1410f, 1744f), 1),
        )
        val arranged = arrangeSharedBlankNotes(
            occupied = listOf(neighbor, second, longNote, shortNote),
            marginX = noteMetrics.marginX,
            marginY = noteMetrics.marginY,
        )
        val dbg = arranged.joinToString(" | ") { "${it.note.annotationId}:${it.candidate.rect}" }
        val long = arranged.single { it.note.annotationId == 118L }
        val short = arranged.single { it.note.annotationId == 119L }
        assertTrue(
            "长的应换到外侧（下方）：long=${long.candidate.rect} short=${short.candidate.rect}",
            long.candidate.rect.top > short.candidate.rect.top + 1f,
        )
        assertTrue(
            "短的应换回内侧（上方）：long=${long.candidate.rect} short=${short.candidate.rect}",
            short.candidate.rect.top < 1800f,
        )
        // 两条要贴在一起：中间只留 2 倍 margin（和让位一致），不能因为长框比短框高就空出一大截。
        val gap = long.candidate.rect.top - short.candidate.rect.bottom
        assertTrue(
            "两条之间只应留 2 倍 margin，实际 gap=$gap：$dbg",
            gap <= noteMetrics.marginY * 2f + 1f,
        )
        assertTrue(
            "换完仍不能重叠",
            !long.candidate.conflicts(neighbor.candidate, noteMetrics.marginX, noteMetrics.marginY) &&
                !short.candidate.conflicts(neighbor.candidate, noteMetrics.marginX, noteMetrics.marginY) &&
                !long.candidate.conflicts(short.candidate, noteMetrics.marginX, noteMetrics.marginY),
        )
    }
}
