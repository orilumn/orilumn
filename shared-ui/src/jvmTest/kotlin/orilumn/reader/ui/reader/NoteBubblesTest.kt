package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 正文旁批注的落位与箭头（D-26 / D-28）。
 *
 * 这些断言只谈**旁注自己的义务**：不压四类障碍（文字 / 图形 / 表格 / 线条）、不互相重叠、
 * **整句可读优先**（放不下整句才允许截断，连一行都读不出就不显示）、页边距与版心内同一等候选；
 * 箭头只在可见图与兜底都不通时才缺席。
 *
 * 行几何 → 页坐标的换算（章内 Y 平移、章内字符区间 → 段内区间）在调用方完成，
 * 这里只吃**已经落到页坐标的墨迹矩形**。
 */
class NoteBubblesTest {

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        Rect(left, top, right, bottom)

    private val noteFont = 20f * NOTE_BUBBLE_FONT_RATIO
    private val noteLine = noteFont * NOTE_BUBBLE_LINE_RATIO
    private val notePad = noteFont * NOTE_BUBBLE_PAD_RATIO * 2f

    private fun candidates(
        note: NoteInk,
        freeRects: List<Rect>,
        contentLeft: Float = 0f,
        contentRight: Float = 600f,
        contentBottom: Float = 800f,
    ) = noteCandidates(
        note = note,
        freeRects = freeRects,
        contentLeft = contentLeft,
        contentRight = contentRight,
        contentBottom = contentBottom,
        fontPx = noteFont,
        lineH = noteLine,
        padX = notePad,
        gap = 5f,
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
    ) = noteBubblePlacements(
        noteInk = notes,
        obstacles = NoteObstacles(
            textInk = textInk,
            figures = figures,
            tables = tables,
            strokes = strokes,
        ),
        contentLeft = contentLeft,
        contentRight = contentRight,
        contentBottom = contentBottom,
        pageRight = pageRight,
        fontPx = 20f,
    )

    @Test
    fun `short line puts the note to its right`() {
        // 行只占 200 / 600：这句话后面正好空着一截，旁注就该躺在那儿。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), textInk = listOf(anchor))
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertTrue("应放在锚点右侧，实际 left=${b.leftPx}", b.leftPx > 200f)
        assertTrue("应贴住锚点所在行，实际 top=${b.topPx}", b.topPx < 40f)
        assertEquals(1, b.maxLines)
        assertTrue(b.widthPx > 0f)
    }

    @Test
    fun `full line falls back to the gap below`() {
        // 满行右侧没地方，往下看：段落之间的空隙刚好够一行小字。
        val first = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first))),
            textInk = listOf(first, rect(0f, 80f, 600f, 120f)),
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertTrue("应落在行下方，实际 top=${b.topPx}", b.topPx >= 40f)
        assertEquals(1, b.maxLines)
        assertTrue(b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO <= 80f)
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
        // 锚点右侧只有两行高的窄缝（放了也只能读一小截）；右侧页边距能放下整句。
        // 新口径：整句可读压过就近，哪怕页边距离得远。
        val anchor = rect(0f, 0f, 200f, 40f)
        val long = "为什么有时显示，有时不显示？".repeat(6)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), text = long)),
            textInk = listOf(anchor),
            figures = listOf(rect(0f, 45f, 600f, 800f)),
        )
        val b = bubbles.single()
        assertTrue("应去页边距整句显示，实际 left=${b.leftPx} top=${b.topPx}", b.leftPx >= 600f)
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
        assertTrue(b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO <= 400f)
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
        // 页边距一维排布：位置顺序与锚点顺序一致（引线最短、不交叉）。
        assertTrue(
            "旁注顺序应与锚点一致，实际 tops=${bubbles.map { it.topPx }}",
            bubbles.map { it.topPx }.zipWithNext().all { (a, b) -> a <= b },
        )
    }

    @Test
    fun `margin notes with the same anchor stack instead of hiding`() {
        // 同一行两条批注，内容区排满：页边距按锚点纵序前推，两条都要在、且不重叠。
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
        val boxes = bubbles.map { b ->
            rect(
                b.leftPx,
                b.topPx,
                b.leftPx + b.widthPx,
                b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO,
            )
        }
        assertTrue("两条页边距旁注不该重叠", !boxes[0].overlaps(boxes[1]))
        assertTrue("第二条应被推到下面", bubbles[1].topPx > bubbles[0].topPx)
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
        // 整页排满（行间无空隙、行满宽、页脚无空白）：退到右侧页边距，至少放得下一个字加省略号。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            textInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertTrue("应落在右侧页边距，实际 left=${b.leftPx}", b.leftPx > 600f)
        assertTrue(b.widthPx >= b.fontPx * 1.05f * 1.9f)
    }

    @Test
    fun `no space at all still does not cover text`() {
        // 连页边距都没有：宁可不显示，也不把正文糊掉。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            textInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
            pageRight = 604f,
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `figure is an obstacle too`() {
        // 插图、背景图都算图形：铺满右侧与页脚时，旁注宁可不显示也不压图。
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            textInk = listOf(anchor),
            figures = listOf(rect(200f, 0f, 600f, 800f), rect(0f, 41f, 200f, 800f)),
            pageRight = 600f,
        )
        assertTrue(bubbles.isEmpty())
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
    fun `arrow is a straight line when nothing blocks it`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 0f, 400f, 40f)),
            obstacles = emptyList(),
            otherBubbles = emptyList(),
        )
        val straight = arrow as NoteArrow.Straight
        assertEquals(100f, straight.start.x, 0.01f)
        assertEquals(20f, straight.start.y, 0.01f)
        assertEquals(297.5f, straight.end.x, 0.01f)
        assertEquals(20f, straight.end.y, 0.01f)
    }

    @Test
    fun `arrow bends around an obstacle`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 60f),
            inkRects = listOf(rect(300f, 100f, 400f, 130f)),
            obstacles = listOf(rect(150f, 70f, 250f, 110f)),
            otherBubbles = emptyList(),
        )
        val elbow = arrow as NoteArrow.Elbow
        assertEquals(3, elbow.points.size)
        // 先横后竖（先竖后横会穿过障碍）：拐点与起点同高、与终点同列。
        assertEquals(elbow.start.y, elbow.points[1].y, 0.01f)
        assertEquals(elbow.end.x, elbow.points[1].x, 0.01f)
    }

    @Test
    fun `arrow arcs away when straight and elbows are blocked`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 0f, 400f, 40f)),
            obstacles = listOf(rect(150f, 10f, 250f, 30f)),
            otherBubbles = emptyList(),
        )
        val arc = arrow as NoteArrow.Arc
        assertTrue("弧线应鼓出障碍之外，control=${arc.control}", arc.control.y > 30f)
    }

    @Test
    fun `no arrow when the bubble already touches the ink`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(102.5f, 0f, 200f, 40f)),
            obstacles = emptyList(),
            otherBubbles = emptyList(),
        )
        assertTrue("贴着墨迹时没有可画的箭头", arrow == null)
    }

    @Test
    fun `arrow is omitted when every route is blocked`() {
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(rect(300f, 0f, 400f, 40f)),
            obstacles = listOf(rect(150f, -100f, 160f, 300f)),
            otherBubbles = emptyList(),
        )
        assertTrue("三种路由都不通时应只画旁注", arrow == null)
    }

    @Test
    fun `arrow does not treat its own ink as an obstacle`() {
        // 锚点自己的墨迹当然不能挡住指向它的箭头。
        val ink = rect(300f, 0f, 400f, 40f)
        val arrow = routeNoteArrow(
            bubble = rect(0f, 0f, 100f, 40f),
            inkRects = listOf(ink),
            obstacles = listOf(ink),
            otherBubbles = emptyList(),
        )
        assertTrue(arrow is NoteArrow.Straight)
    }

    @Test
    fun `complete margin candidate beats a nearby truncated sliver`() {
        // 锚点右侧只有一条能读出 5 个字的窄缝；远处页边距能放下整句。
        // 新口径：整句可读压过就近。
        val anchor = rect(0f, 100f, 200f, 140f)
        val text = "为什么有时显示"
        val slit = rect(200f, 100f, 296f, 125f)
        val margin = rect(600f, 0f, 700f, 800f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = text),
            freeRects = listOf(slit, margin),
        )
        assertTrue("窄缝的截断候选也应在列表里，实际=$list", list.any { !it.complete })
        val best = list.first()
        assertTrue("应整句显示，实际 best=$best", best.complete)
        assertEquals(MarginSide.RIGHT, best.marginSide)
        assertTrue("应落在页边距，实际 ${best.rect}", best.rect.left >= 600f)
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
    fun `margin note gets a small preference penalty not a last place`() {
        // 同为完整候选：版心内近处略胜页边距；但页边距不会被排到截断档之后。
        val anchor = rect(0f, 0f, 200f, 40f)
        val inContent = rect(0f, 40f, 600f, 100f)
        val margin = rect(600f, 0f, 700f, 100f)
        val list = candidates(
            note = noteInk(1, listOf(anchor), text = "交换"),
            freeRects = listOf(inContent, margin),
        )
        val best = list.first()
        assertTrue(best.inContent)
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
}
