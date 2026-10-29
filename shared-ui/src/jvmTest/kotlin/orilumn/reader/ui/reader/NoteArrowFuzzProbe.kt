package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import org.junit.Test

/**
 * 引线路由的**随机差分探针**：同一颗种子下把几百组几何的引线顶点串成一行，
 * 重构路由时拿新旧两份输出逐行对比，确认选择结果一字不差。
 *
 * 默认不跑：`ORILUMN_FUZZ=1 ./gradlew :shared-ui:jvmTest --tests "...NoteArrowFuzzProbe"`。
 */
class NoteArrowFuzzProbe {

    private fun pt(o: Offset): String =
        (o.x * 100).toInt().toString() + "/" + (o.y * 100).toInt()

    private fun describe(arrow: NoteArrow?): String = when (arrow) {
        null -> "-"
        is NoteArrow.Straight -> "S(" + pt(arrow.start) + "->" + pt(arrow.end) + ")"
        is NoteArrow.Elbow -> arrow.points.joinToString(",", "E(", ")") { pt(it) }
    }

    private fun rowGlyphs(y: Float, left: Float, count: Int, w: Float, h: Float): List<Rect> =
        (0 until count).map { Rect(left + it * w, y, left + (it + 1) * w, y + h) }

    @Test
    fun fuzz() {
        assumeTrue("随机探针默认不跑：设 ORILUMN_FUZZ=1", System.getenv("ORILUMN_FUZZ") != null)
        val sb = StringBuilder()
        for (seed in 0 until 400) {
            val rnd = Random(seed)
            val glyphs = ArrayList<Rect>()
            var y = 40f + rnd.nextFloat() * 30f
            repeat(6) {
                val n = 6 + rnd.nextInt(20)
                glyphs += rowGlyphs(y, 72f, n, 40f, 48f)
                y += 69f + (if (rnd.nextInt(4) == 0) 22f else 0f)
            }
            val inkTop = glyphs[rnd.nextInt(glyphs.size)].top
            val inkLeft = 100f + rnd.nextFloat() * 700f
            val inkW = 40f + rnd.nextFloat() * 300f
            val ink = listOf(Rect(inkLeft, inkTop + 6f, inkLeft + inkW, inkTop + 54f))
            val bubbleW = 120f + rnd.nextFloat() * 380f
            val bubbleH = 60f + rnd.nextFloat() * 180f
            val bx = 40f + rnd.nextFloat() * 900f
            val by = 60f + rnd.nextFloat() * 900f
            val bubble = Rect(bx, by, bx + bubbleW, by + bubbleH)
            val solids = if (rnd.nextInt(3) == 0) {
                listOf(
                    Rect(
                        100f + rnd.nextFloat() * 400f, 100f + rnd.nextFloat() * 400f,
                        500f + rnd.nextFloat() * 300f, 300f + rnd.nextFloat() * 400f,
                    ),
                )
            } else {
                emptyList()
            }
            val others = if (rnd.nextInt(2) == 0) {
                listOf(
                    Rect(
                        100f + rnd.nextFloat() * 500f, 100f + rnd.nextFloat() * 500f,
                        400f + rnd.nextFloat() * 400f, 400f + rnd.nextFloat() * 400f,
                    ),
                )
            } else {
                emptyList()
            }
            val drawn = ArrayList<NoteArrow>()
            repeat(rnd.nextInt(3)) {
                val x0 = 80f + rnd.nextFloat() * 600f
                val y0 = 80f + rnd.nextFloat() * 800f
                drawn += if (rnd.nextBoolean()) {
                    NoteArrow.Straight(Offset(x0, y0), Offset(x0 + rnd.nextFloat() * 200f, y0))
                } else {
                    NoteArrow.Elbow(listOf(Offset(x0, y0), Offset(x0, y0 + 100f), Offset(x0 + 150f, y0 + 100f)))
                }
            }
            val side = listOf(
                null, NoteSide.BELOW, NoteSide.ABOVE, NoteSide.RIGHT, NoteSide.LEFT,
                NoteSide.BELOW_RIGHT, NoteSide.ABOVE_RIGHT, NoteSide.BELOW_LEFT, NoteSide.ABOVE_LEFT,
            )[rnd.nextInt(9)]
            val lineH = if (rnd.nextInt(4) == 0) 0f else 69f
            val rowBox = rnd.nextBoolean()
            noteArrowRouteTries = 0
            val a = routeNoteArrow(
                bubble = bubble,
                inkRects = ink,
                glyphs = glyphs,
                solids = solids,
                otherBubbles = others,
                drawnArrows = drawn,
                lineH = lineH,
                side = side,
                rowBoxEntry = rowBox,
            )
            sb.append(seed).append(' ').append(describe(a)).append(" t=").append(noteArrowRouteTries).append('\n')
        }
        println("FUZZ\n$sb")
    }
}
