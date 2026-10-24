package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * 布点性能探针：不含断言，只打印各阶段耗时、文字测量次数与候选规模。
 *
 * 默认**不跑**（CI 与日常回归不受影响）：`ORILUMN_PERF=1 ./gradlew :shared-ui:jvmTest \
 * --tests "orilumn.reader.ui.reader.NoteBubblesPerfProbe"`。
 *
 * 基线（2026-10-24，10 条批注 / 30 行 / 723 个逐字墨迹）：单页 3886 次文字测量；
 * 目标见 `docs/新版批注规则-改造方案.md` §4.7（≤ 40 次、JVM ≤ 10ms）。
 */
class NoteBubblesPerfProbe {

    private class CountingSizer(
        private val delegate: NoteTextSizer = EstimatedNoteTextSizer,
        /** 每次测量额外消耗的纳秒：模拟 Compose TextMeasurer 的真实成本。 */
        private val fakeCostNanos: Long = 0L,
    ) : NoteTextSizer {
        var natural = 0
        var fits = 0
        override fun naturalWidth(text: String, fontPx: Float): Float {
            natural++
            burn(fakeCostNanos)
            return delegate.naturalWidth(text, fontPx)
        }

        override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit {
            fits++
            burn(fakeCostNanos)
            return delegate.fit(text, fontPx, widthPx, maxLines)
        }

        val calls: Int get() = natural + fits

        private fun burn(nanos: Long) {
            if (nanos <= 0L) return
            val end = System.nanoTime() + nanos
            while (System.nanoTime() < end) {
                // busy wait
            }
        }
    }

    private class CountingFront(private val inner: NoteTextSizer) : NoteTextSizer {
        var queries = 0
        override fun naturalWidth(text: String, fontPx: Float): Float {
            queries++
            return inner.naturalWidth(text, fontPx)
        }

        override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit {
            queries++
            return inner.fit(text, fontPx, widthPx, maxLines)
        }
    }

    private class Page(
        val lines: List<Rect>,
        val glyphs: List<Rect>,
        val textLines: List<NoteTextLine>,
    )

    private fun page(rows: Int = 30, chars: Int = 26): Page {
        val glyphs = ArrayList<Rect>()
        val lines = ArrayList<Rect>()
        val textLines = ArrayList<NoteTextLine>()
        val left = 72f
        val charW = 40f
        val lineH = 69f
        val inkH = 48f
        var y = 60f
        var block = 0
        var inBlock = 0
        for (row in 0 until rows) {
            val n = when (row % 9) {
                8 -> 14
                5 -> 19
                else -> chars
            }
            val top = y + (lineH - inkH) / 2f
            for (c in 0 until n) {
                glyphs += Rect(left + c * charW, top, left + (c + 1) * charW, top + inkH)
            }
            val lineRect = Rect(left, y, left + n * charW, y + lineH)
            lines += lineRect
            textLines += NoteTextLine(rect = lineRect, blockId = block, index = row)
            inBlock++
            if (inBlock >= 3 + (row % 5)) {
                block++
                inBlock = 0
                y += lineH + 22f
            } else {
                y += lineH
            }
        }
        return Page(lines, glyphs, textLines)
    }

    private fun notes(page: Page, n: Int, short: Boolean = false): List<NoteInk> {
        // 每条批注用不同文字：真实页面上一页十条批注不会都是同一句话，
        // 缓存命中率必须按「每条各自不同」来量，否则数字会虚高。
        val base = if (short) "短批注第" else "这一段批注要读完才看得明白，不能只剩几个字。第"
        return (0 until n).map { i ->
            val row = (i * 3 + 2) % page.textLines.size
            val line = page.textLines[row].rect
            val startX = line.left + 80f + (i % 4) * 120f
            val ink = listOf(Rect(startX, line.top + 10f, startX + 160f, line.top + 58f))
            NoteInk(
                annotationId = i + 1L,
                text = "$base${i + 1}条",
                argb = 0xFF3366CC.toInt(),
                rects = ink,
                arrowRects = page.glyphs.filter { g -> ink.any { g.overlaps(it) } },
            )
        }
    }

    private fun run(noteCount: Int, fakeMeasureNanos: Long, short: Boolean = false) {
        val page = page()
        val notes = notes(page, noteCount, short)
        val metrics = NoteMetrics.forBody(48f)
        val contentRight = 1128f

        var freeRects: List<Rect> = emptyList()
        val freeTime = measureNanoTime {
            freeRects = maximalFreeRects(
                bounds = Rect(metrics.edge, metrics.edge, 1200f - metrics.edge, 1800f - metrics.edge),
                obstacles = page.lines.map {
                    Rect(it.left - metrics.marginX, it.top - metrics.marginY, it.right + metrics.marginX, it.bottom + metrics.marginY)
                },
                minW = metrics.em * 1.2f,
                minH = metrics.lineH,
            )
        }

        val counter = CountingSizer(fakeCostNanos = fakeMeasureNanos)
        val sizer = CountingFront(CachingNoteTextSizer(counter))
        val candTime = measureNanoTime {
            for (n in notes) {
                noteCandidates(
                    note = n,
                    lines = page.textLines,
                    freeRects = freeRects,
                    metrics = metrics,
                    sizer = sizer,
                    charWidthPx = 48f,
                    contentRight = contentRight,
                )
            }
        }
        val candQueries = sizer.queries
        val candCalls = counter.calls
        sizer.queries = 0
        counter.natural = 0
        counter.fits = 0

        fun place(): List<NoteBubble> = noteBubblePlacements(
            noteInk = notes,
            obstacles = NoteObstacles(textInk = page.lines, textLines = page.textLines, arrowInk = page.glyphs),
            contentLeft = 72f,
            contentRight = contentRight,
            contentBottom = 1700f,
            pageRight = 1200f,
            fontPx = 48f,
            pageBottom = 1800f,
            noteTextSizer = sizer,
        )
        // 先跑一次热身（JIT 未预热时单次数字没有参考价值），再取 3 次里最快的一次。
        var out: List<NoteBubble> = place()
        sizer.queries = 0
        counter.natural = 0
        counter.fits = 0
        var placeTime = Long.MAX_VALUE
        var round = 0
        while (round < 3) {
            val start = System.nanoTime()
            out = place()
            val spent = System.nanoTime() - start
            if (spent < placeTime) placeTime = spent
            round++
        }

        val bubbleRects = out.filterNot { it.compact }.associate { b ->
            b.annotationId to Rect(b.leftPx, b.topPx, b.leftPx + b.widthPx, b.topPx + b.heightPx)
        }
        var arrows = 0
        fun routeAll() {
            arrows = 0
            for (b in out) {
                if (b.compact) continue
                val ink = notes.first { it.annotationId == b.annotationId }.arrowInk
                val self = bubbleRects.getValue(b.annotationId)
                val others = bubbleRects.filterKeys { it != b.annotationId }.values.toList()
                val a = routeNoteArrow(
                    bubble = self,
                    inkRects = ink,
                    glyphs = page.glyphs,
                    solids = emptyList(),
                    otherBubbles = others,
                    drawnArrows = emptyList(),
                    lineH = metrics.lineH,
                    side = null,
                )
                if (a != null) arrows++
            }
        }
        routeAll()
        var routeTime = Long.MAX_VALUE
        var routeRound = 0
        while (routeRound < 3) {
            val start = System.nanoTime()
            routeAll()
            val spent = System.nanoTime() - start
            if (spent < routeTime) routeTime = spent
            routeRound++
        }
        println(
            "PERF notes=$noteCount kind=${if (short) "短" else "长"} fake=${fakeMeasureNanos / 1000}us " +
                "free=${freeTime / 1_000_000}ms " +
                "candidates=${candTime / 1_000_000}ms candQueries=$candQueries candReal=$candCalls " +
                "place=${placeTime / 1_000_000}ms placeQueries=${sizer.queries} " +
                "placeReal=${counter.calls}(${counter.natural}/${counter.fits}) " +
                "route=${routeTime / 1_000_000}ms arrows=$arrows bubbles=${out.size} " +
                "compact=${out.count { it.compact }} freeRects=${freeRects.size} glyphs=${page.glyphs.size}",
        )
    }

    @Test
    fun probe() {
        assumeTrue("性能探针默认不跑：设 ORILUMN_PERF=1", System.getenv("ORILUMN_PERF") != null)
        for (n in listOf(1, 10)) {
            for (short in listOf(false, true)) {
                run(n, fakeMeasureNanos = 0L, short = short)
                run(n, fakeMeasureNanos = 300_000L, short = short)
            }
        }
    }
}
