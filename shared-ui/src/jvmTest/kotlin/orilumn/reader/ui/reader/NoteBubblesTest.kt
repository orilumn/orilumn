package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文旁批注的落位（D-26 / D-28）。
 *
 * 这些断言只谈**旁注自己的义务**：不压正文墨迹、不互相重叠、能放就放、放不下退到页边距；
 * 不谈「必须落在哪一行的哪个像素」以外的排版细节（旁注不进分页，也不与磁盘表对齐）。
 *
 * 行几何 → 页坐标的换算（章内 Y 平移、章内字符区间 → 段内区间）在调用方完成，
 * 这里只吃**已经落到页坐标的墨迹矩形**。
 */
class NoteBubblesTest {

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        Rect(left, top, right, bottom)

    private fun noteInk(
        id: Long,
        rects: List<Rect>,
        text: String = "想法",
        argb: Int = 0xFF3366CC.toInt(),
    ) = NoteInk(annotationId = id, text = text, argb = argb, rects = rects)

    private fun place(
        notes: List<NoteInk>,
        pageInk: List<Rect>,
        contentLeft: Float = 0f,
        contentRight: Float = 600f,
        contentBottom: Float = 800f,
        pageRight: Float = 700f,
    ) = noteBubblePlacements(
        noteInk = notes,
        pageInk = pageInk,
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
        val bubbles = place(notes = listOf(noteInk(1, listOf(anchor))), pageInk = listOf(anchor))
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertEquals(200f + 20f * 0.25f, b.leftPx, 0.01f)
        assertEquals(0f, b.topPx, 0.01f)
        assertEquals(1, b.maxLines)
        assertTrue(b.widthPx > 0f)
    }

    @Test
    fun `full line falls back to the gap below`() {
        // 满行右侧没地方，往下看：段落之间的空隙刚好够一行小字。
        val first = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first))),
            pageInk = listOf(first, rect(0f, 80f, 600f, 120f)),
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertEquals(40f + 5f, b.topPx, 0.01f)
        assertEquals(0f, b.leftPx, 0.01f)
        assertEquals(1, b.maxLines)
        assertTrue(b.topPx + b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO <= 80f)
    }

    @Test
    fun `tall gap fits more lines than it used to`() {
        // 空白够高就多放几行，不再死卡三行（D-28 第 3 部分）。
        val first = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(first))),
            pageInk = listOf(first, rect(0f, 400f, 600f, 440f)),
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
            pageInk = listOf(rect(0f, 0f, 200f, 40f)),
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `notes never overlap each other`() {
        // 两个锚点落在同一行：两张旁注不能叠在一起。
        val anchor = rect(0f, 0f, 600f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor)), noteInk(2, listOf(anchor))),
            pageInk = listOf(anchor),
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
    fun `full page falls back to the side margin`() {
        // 整页排满（行间无空隙、行满宽、页脚无空白）：退到右侧页边距，至少放得下一个字加省略号。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            pageInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
        )
        assertEquals(1, bubbles.size)
        val b = bubbles.single()
        assertEquals(600f + 5f, b.leftPx, 0.01f)
        assertEquals(700f - 600f - 10f, b.widthPx, 0.01f)
        assertTrue(b.widthPx >= b.fontPx * 1.05f * 1.9f)
    }

    @Test
    fun `no space at all still does not cover text`() {
        // 连页边距都没有：宁可不显示，也不把正文糊掉。
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(rect(0f, 0f, 600f, 40f)))),
            pageInk = listOf(rect(0f, 0f, 600f, 40f), rect(0f, 40f, 600f, 80f)),
            contentBottom = 80f,
            pageRight = 604f,
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `blank note is not a bubble`() {
        val anchor = rect(0f, 0f, 200f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), text = "   ")),
            pageInk = listOf(anchor),
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun `bubble keeps the annotation color`() {
        val anchor = rect(0f, 0f, 200f, 40f)
        val argb = 0xFF123456.toInt()
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor), argb = argb)),
            pageInk = listOf(anchor),
        )
        assertEquals(argb, bubbles.single().argb)
    }

    @Test
    fun `shifted ink lands on the page`() {
        // 章内 Y 平移由调用方完成：传进来已经是页坐标，旁注就按它放（不再自己减 shift）。
        val anchor = rect(20f, 0f, 220f, 40f)
        val bubbles = place(
            notes = listOf(noteInk(1, listOf(anchor))),
            pageInk = listOf(anchor),
        )
        assertEquals(0, bubbles.single().topPx.roundToInt())
    }
}
