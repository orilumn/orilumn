package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 桌面版滚动翻页的判据：横滑翻页、**鼠标滚轮**一格翻一页、触控板上下滑不翻
 * （2026-11-04 用户定口）。
 *
 * 用例里的数字取自真机日志的形状：横滑时横向一条几十到几百个单位、纵向只剩一两个；
 * 竖滑时纵向一条涨到十几，横向只剩零点几的噪声。同一把横滑的惯性是平滑衰减的，
 * 两把之间则会出现「断层式掉下来、再涨回去」。
 */
class ScrollFlipArbiterTest {

    @Test
    fun `horizontal swipe flips once and its momentum does not chain pages`() {
        val a = ScrollFlipArbiter()
        // 手在滑：横向越滑越快。
        var t = 1_000L
        assertEquals(1, a.onScroll(t, 16.9f, 0f))
        // 抬手后的惯性：同向、量在衰减，一律不再翻。
        var dx = 16.1f
        while (dx > 2f) {
            t += 16L
            assertEquals(0, a.onScroll(t, dx, 0f))
            dx *= 0.93f
        }
    }

    @Test
    fun `a second swipe after the gesture ends flips again`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 30f, 0f))
        // 惯性尾巴。
        assertEquals(0, a.onScroll(1_016L, 12f, 0f))
        // 读者停手 100ms 再滑一把：断流超过会话间隔，重新上膛。
        assertEquals(1, a.onScroll(1_132L, 24f, 0f))
    }

    @Test
    fun `a same-direction second swipe is recognised even when the lull is only 54ms`() {
        val a = ScrollFlipArbiter()
        // 真机 2026-11-04 的形状：第一把甩完，惯性尾巴最后一条还有 4.6；
        // 只断 54ms 读者又滑一把——起手 0.9 / 1.0，然后涨到 5.6、18.2……
        assertEquals(1, a.onScroll(1_000L, 16.9f, 0f))
        var t = 1_000L
        var dx = 16.1f
        while (dx > 2f) {
            t += 16L
            assertEquals(0, a.onScroll(t, dx, 0f))
            dx *= 0.93f
        }
        t += 54L
        assertEquals(0, a.onScroll(t, 0f, 0.2f))
        assertEquals(0, a.onScroll(t, 0.9f, 0f))
        assertEquals(0, a.onScroll(t, 1.0f, 0f))
        t += 16L
        assertEquals(1, a.onScroll(t, 5.6f, 0f))
        t += 17L
        assertEquals(0, a.onScroll(t, 18.2f, 0f))
    }

    @Test
    fun `a fast repeated swipe each flips once (real machine shapes)`() {
        val a = ScrollFlipArbiter()
        var t = 1_000L
        // 真机 2026-11-04：起手 5.6，冲上 537，再平滑衰减到 157.7。
        assertEquals(1, a.onScroll(t, 5.6f, 0f))
        for (dx in listOf(
            8.4f, 23.8f, 78.3f, 197.9f, 322f, 537.1f, 471.6f, 422.7f,
            360f, 318.1f, 267.9f, 234.4f, 203.7f, 177.2f, 157.7f,
        )) {
            t += 17L
            assertEquals(0, a.onScroll(t, dx, 0f))
        }
        // 断 48ms，下一把从 53.1 起手、再涨到 312：这一把只翻一页。
        t += 48L
        assertEquals(0, a.onScroll(t, 53.1f, 0f))
        t += 16L
        assertEquals(0, a.onScroll(t, 74.7f, 0f))
        t += 17L
        assertEquals(1, a.onScroll(t, 312f, 0f))
        for (dx in listOf(529.3f, 522.6f, 455.6f, 424.5f, 390.3f)) {
            t += 17L
            assertEquals(0, a.onScroll(t, dx, 0f))
        }
    }

    @Test
    fun `momentum that stutters is not mistaken for a second swipe`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 40f, 0f))
        // 惯性尾巴一路衰减：中间断 50ms 也只是尾巴，量没有重新涨起来，不是新手势。
        var t = 1_000L
        var dx = 37f
        while (dx > 1f) {
            t += if (dx < 5f) 50L else 16L
            assertEquals(0, a.onScroll(t, dx, 0f))
            dx *= 0.9f
        }
    }

    @Test
    fun `a bump inside one swipe's momentum is not a second swipe`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 537.1f, 0f))
        // 真机 2026-11-04 同一把的惯性：一路衰减，中间有小起伏（218 → 276 → 321），
        // 起伏远没到峰值的四分之一，不会被当成「收尾又起手」。
        var t = 1_000L
        for (dx in listOf(
            471.6f, 422.7f, 360f, 318.1f, 267.9f, 234.4f, 218.2f,
            276.6f, 321f, 299.5f, 279.5f, 262.3f, 248f,
        )) {
            t += 17L
            assertEquals(0, a.onScroll(t, dx, 0f))
        }
    }

    @Test
    fun `swiping back mid gesture flips back immediately`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 40f, 0f))
        // 还在同一次手势里，往回滑一点点（不够一格）：不翻。
        assertEquals(0, a.onScroll(1_016L, -2f, 0f))
        // 往回滑够一格：掉头算新的一次，立刻翻回去。
        assertEquals(-1, a.onScroll(1_032L, -6f, 0f))
    }

    @Test
    fun `a back swipe is not blocked by the previous swipe's momentum`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 13.2f, 0f))
        // 惯性衰减到 2 上下（同方向，不翻）。
        var t = 1_000L
        var dx = 12.5f
        while (dx > 2f) {
            t += 17L
            assertEquals(0, a.onScroll(t, dx, 0f))
            dx *= 0.94f
        }
        // 读者往回滑：起手 -13.9 就该翻回去。旧实现要先把上把惯性累计的几百个单位
        // 全部抵消掉，于是「往回滑没反应」。
        t += 33L
        assertEquals(-1, a.onScroll(t, -13.9f, 0f))
    }

    @Test
    fun `two-finger vertical scroll never flips`() {
        val a = ScrollFlipArbiter()
        // 真机形状：纵向一条从 0.4 涨到 18，横向同期只有零点几到一两个的噪声。
        // （第一把真机竖滑起手的也是 0.4，不是整数格 —— 整数格才可能被当成滚轮。）
        val vertical = listOf(0.4f, 2.8f, 4.9f, 8.9f, 13f, 14.9f, 12.4f, 16f, 17.6f, 17.2f, 16.6f)
        var t = 1_000L
        for ((i, dy) in vertical.withIndex()) {
            assertEquals(0, a.onScroll(t, 0f, dy))
            t += 16L
            // 横向噪声与纵向反号，是竖滑的典型形状。
            assertEquals(0, a.onScroll(t, -0.1f * (i + 1), 0f))
            t += 1L
        }
    }

    @Test
    fun `small horizontal jitter at the start of a vertical swipe is not a flip`() {
        val a = ScrollFlipArbiter()
        // 竖滑刚起手：一条 1.7 的横向噪声先到，纵向还没起来。
        assertEquals(0, a.onScroll(1_000L, 1.7f, 0f))
        assertEquals(0, a.onScroll(1_016L, 0f, 2.8f))
        assertEquals(0, a.onScroll(1_032L, -0.7f, 0f))
        assertEquals(0, a.onScroll(1_048L, 0f, 14.9f))
    }

    @Test
    fun `a slightly diagonal horizontal swipe still flips`() {
        val a = ScrollFlipArbiter()
        // 横滑带一点上飘：横向 32、纵向 4.3，净横向远超一格。
        assertEquals(1, a.onScroll(1_000L, 32.2f, 0f))
        assertEquals(0, a.onScroll(1_000L, 0f, 4.3f))
    }

    @Test
    fun `a slow horizontal swipe accumulates to one page`() {
        val a = ScrollFlipArbiter()
        // 慢慢滑：每条只有 1 个单位，累计到门槛才翻，翻过之后同向不再翻。
        var t = 1_000L
        var fired = 0
        repeat(10) {
            val dir = a.onScroll(t, 1f, 0f)
            if (dir != 0) fired++
            t += 16L
        }
        assertEquals(1, fired)
    }

    @Test
    fun `axis split events inside one swipe do not fake a new gesture`() {
        val a = ScrollFlipArbiter()
        assertEquals(1, a.onScroll(1_000L, 16.9f, 0f))
        // 同一次横滑里两个轴分开报：横向被一条纵向事件隔开，27.2 之后又来 44.9。
        assertEquals(0, a.onScroll(1_016L, 27.2f, 0f))
        assertEquals(0, a.onScroll(1_024L, 0f, -0.1f))
        assertEquals(0, a.onScroll(1_032L, 44.9f, 0f))
    }

    // ---- 鼠标滚轮：拨一格翻一页（2026-11-04 用户定口，不能禁） ----

    @Test
    fun `a mouse wheel notch flips one page each`() {
        val a = ScrollFlipArbiter()
        // 一格 = ±1 个单位（系统没做平滑的滚轮）。同样要等这一串收尾才结算，
        // 否则触控板惯性正好落在 1.0 上会当场翻、紧跟着的横向事件又翻一次。
        assertEquals(0, a.onScroll(1_000L, 0f, 1f))
        assertEquals(1, a.finishWheelBurst())
        assertEquals(0, a.onScroll(1_040L, 0f, 1f))
        assertEquals(1, a.finishWheelBurst())
        assertEquals(0, a.onScroll(1_080L, 0f, -1f))
        assertEquals(-1, a.finishWheelBurst())
        // 一格滚三行的滚轮：仍然一格翻一页。
        assertEquals(0, a.onScroll(1_120L, 0f, 3f))
        assertEquals(1, a.finishWheelBurst())
    }

    @Test
    fun `two integer notches back to back each flip once`() {
        val a = ScrollFlipArbiter()
        // 40ms 一格：第二格起手时把第一格结掉，各翻一页。
        assertEquals(0, a.onScroll(1_000L, 0f, 1f))
        assertEquals(1, a.onScroll(1_040L, 0f, 1f))
        assertEquals(1, a.onScroll(1_080L, 0f, 1f))
        // 最后一格由计时器收尾。
        assertEquals(1, a.finishWheelBurst())
    }

    @Test
    fun `trackpad momentum landing on a whole number is not a wheel notch`() {
        val a = ScrollFlipArbiter()
        // 真机见过：竖滑惯性尾巴连着几条正好 1.0，但只隔 16-17ms —— 那是触控板，不翻。
        assertEquals(0, a.onScroll(1_000L, 0f, 0.6f))
        assertEquals(0, a.onScroll(1_016L, 0f, 1f))
        assertEquals(0, a.onScroll(1_033L, 0f, 1f))
    }

    @Test
    fun `a slow trackpad drag with fractional deltas is not a wheel notch`() {
        val a = ScrollFlipArbiter()
        assertEquals(0, a.onScroll(1_000L, 0f, 0.2f))
        assertEquals(0, a.onScroll(1_054L, 0f, 1.3f))
        assertEquals(0, a.onScroll(1_151L, 0f, 0.3f))
    }

    @Test
    fun `wheel and horizontal swipe do not disturb each other`() {
        val a = ScrollFlipArbiter()
        assertEquals(0, a.onScroll(1_000L, 0f, 1f))
        // 滚过一格马上横滑：横滑该独立判，不被滚轮留下的状态吞掉。
        assertEquals(1, a.onScroll(1_040L, 30f, 0f))
        // 断了 80ms 再往回横滑：新手势，能翻回去。
        assertEquals(-1, a.onScroll(1_120L, -30f, 0f))
        // 这一格滚轮夹着横向事件，按触控板的轴向拆分作废，不额外多翻一页。
        assertEquals(0, a.finishWheelBurst())
    }

    // ---- 滚轮被 macOS 平滑成的一串小数事件（真机 2026-11-04 11:24:22 的形状） ----

    /** 把一串竖向事件喂进去，返回最后一条之后的翻页方向（串尾由计时器结算）。 */
    private fun feedBurst(a: ScrollFlipArbiter, startMs: Long, dys: List<Float>, gapMs: Long): Int {
        var t = startMs
        for (dy in dys) {
            assertEquals(0, a.onScroll(t, 0f, dy))
            t += gapMs
        }
        return a.finishWheelBurst()
    }

    @Test
    fun `a smooth mouse wheel notch flips once when the stream ends`() {
        val a = ScrollFlipArbiter()
        // 真机日志 11:24:22.335 的实际形状：一格从 0.1 平滑涨到 7.71，随后断 311ms。
        val notch = listOf(0.1f, 0.84251404f, 3.383957f, 5.272293f, 6.1582184f,
            6.8637543f, 7.3439636f, 7.713974f)
        assertEquals(1, feedBurst(a, 1_000L, notch, 17L))
    }

    @Test
    fun `a smooth mouse wheel notch scrolling back flips back`() {
        val a = ScrollFlipArbiter()
        // 真机日志 11:24:22.824：反向一格从 -0.1 平滑跌到 -8.44。
        val notch = listOf(-0.1f, -1.000061f, -3.9942627f, -5.7342834f,
            -6.6009827f, -7.2774353f, -7.88591f, -8.442154f)
        assertEquals(-1, feedBurst(a, 1_000L, notch, 17L))
    }

    @Test
    fun `a slow wheel notch with wider gaps still flips once`() {
        val a = ScrollFlipArbiter()
        // 真机日志 11:24:20.416：慢拨一格，串内隔 40–72ms，整串 248ms。
        // 这些间隔都大于 60ms 的「横滑会话间隔」，串不能被它清掉。
        val notch = listOf(0.1f, 0.6934967f, 2.432434f, 3.8579865f, 5.063568f, 5.490082f)
        val gaps = listOf(0L, 48L, 48L, 40L, 40L, 72L)
        var t = 1_000L
        for ((i, dy) in notch.withIndex()) {
            t += gaps[i]
            assertEquals(0, a.onScroll(t, 0f, dy))
        }
        assertEquals(1, a.finishWheelBurst())
    }

    @Test
    fun `two wheel notches back to back flip twice`() {
        val a = ScrollFlipArbiter()
        var t = 1_000L
        // 第一格（断流后计时器结算）。
        for (dy in listOf(0.1f, 0.84f, 3.38f, 5.27f, 7.71f)) {
            assertEquals(0, a.onScroll(t, 0f, dy))
            t += 17L
        }
        assertEquals(1, a.finishWheelBurst())
        // 拨得很快，第二格直接接上（真机两格之间断 191–311ms，这里按更紧的 140ms 也算新的一格）。
        t += 140L
        for (dy in listOf(0.1f, 0.99f, 3.99f, 5.73f, 7.19f)) {
            assertEquals(0, a.onScroll(t, 0f, dy))
            t += 17L
        }
        assertEquals(1, a.finishWheelBurst())
    }

    @Test
    fun `a fast double notch that merges into one stream is cut at the dip`() {
        val a = ScrollFlipArbiter()
        var t = 1_000L
        var fired = 0
        // 拨得快，两格并成一条流：第一格收到 6.2 后掉回 0.1，再涨第二格。
        val stream = listOf(0.1f, 0.84f, 3.38f, 5.27f, 6.2f, 0.1f, 0.99f, 3.99f, 5.73f, 7.19f)
        for (dy in stream) {
            val dir = a.onScroll(t, 0f, dy)
            if (dir != 0) fired++
            t += 17L
        }
        assertEquals(1, fired)
        assertEquals(1, a.finishWheelBurst())
    }

    @Test
    fun `a two-finger vertical scroll with axis split dx is not a wheel`() {
        val a = ScrollFlipArbiter()
        // 真机日志 11:24:00.356：触控板竖向滑，每帧都是「竖一条 + 横一条」（同一时间戳）。
        val dys = listOf(0.3f, 0.8f, 1.1f, 1.3f, 1.9f, 4.0f, 4.0f, 4.1f, 3.6f, 2.3f, 0.9f)
        var t = 1_000L
        for (dy in dys) {
            assertEquals(0, a.onScroll(t, 0f, dy))
            assertEquals(0, a.onScroll(t, 0.6f, 0f))
            t += 17L
        }
        assertEquals(0, a.finishWheelBurst())
    }

    @Test
    fun `a horizontal swipe's vertical companion events are not a wheel`() {
        val a = ScrollFlipArbiter()
        // 真机日志 11:23:05.386：横滑时同一帧报一条大纵向（1.2 → 32.3），收在峰值上，
        // 但它每一帧都带一条横向事件 —— 不是滚轮，这一把只该由横滑翻一次。
        var t = 1_000L
        var flips = 0
        fun feed(dx: Float, dy: Float) {
            val d = a.onScroll(t, dx, dy)
            if (d != 0) flips++
        }
        feed(0f, 1.2f)
        feed(8.1f, 0f)
        t += 17L
        feed(0f, 2.3f)
        feed(6.9f, 0f)
        t += 16L
        feed(0f, 12.7f)
        feed(42.5f, 0f)
        t += 16L
        feed(0f, 32.3f)
        feed(144.2f, 0f)
        t += 17L
        feed(0f, 31.4f)
        feed(229.7f, 0f)
        assertEquals(1, flips)
        assertEquals(0, a.finishWheelBurst())
    }

    @Test
    fun `a pure vertical flick that decays is not a wheel`() {
        val a = ScrollFlipArbiter()
        // 触控板竖向快甩、没有任何横向事件（真机小概率）：涨到峰值后有惯性尾巴。
        val dys = listOf(0.4f, 2.8f, 4.9f, 8.9f, 13f, 14.9f, 12.4f, 16f, 17.6f,
            14f, 11f, 8.2f, 5.4f, 3.1f, 1.4f, 0.6f)
        assertEquals(0, feedBurst(a, 1_000L, dys, 16L))
    }
}

