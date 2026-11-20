package orilumn.reader.ui.reader.curl

import kotlin.time.TimeSource
import orilumn.reader.io.Logger

/**
 * 一次翻页（拖动 + 结算）里卷曲层的**绘制开销**统计（用户层·诊断）。
 *
 * 只在一次会话结束时落一行日志，逐帧不打点：真机落盘一帧一行会把 IO 算进被测的
 * 主线程耗时里，测出来的数字反而不可信。和 `docs/卷曲3D实施计划.md` 里那套逐段探针
 * 同口径，只是常驻、只在翻完时汇总一次。
 *
 * 记三样：
 *  - `frames` / `avg` / `p50` / `p90` / `max`：本会话每帧 `Canvas` 绘制阶段的耗时
 *    （主线程录制绘制指令的时长，不含 GPU 填率）；
 *  - `bake`：整页烤图（[CurlPageBake]）重建次数与耗时 —— 这个数只该在会话第一帧出现，
 *    之后每帧都重建说明烤图缓存被逐帧判废（见 `CurlPageBake.textureOf` 的判据）；
 *  - `paint`：纹理画笔（`ImageShader`）重建次数，判据同上。
 */
internal class CurlPerf {
    private var frames = 0
    private var totalNanos = 0L
    private var maxNanos = 0L
    private val samples = FloatArray(MAX_SAMPLES)
    private var sampleCount = 0
    private var bakedFrames = 0
    private var bakeNanosTotal = 0L
    private var bakeNanosMax = 0L
    private var paintRebuilds = 0

    fun record(drawNanos: Long, bakeFrames: Int, bakeNanos: Long, paintRebuilds: Int) {
        frames++
        totalNanos += drawNanos
        if (drawNanos > maxNanos) maxNanos = drawNanos
        if (sampleCount < MAX_SAMPLES) {
            samples[sampleCount++] = drawNanos / 1_000_000f
        }
        if (bakeFrames > 0) {
            bakedFrames += bakeFrames
            bakeNanosTotal += bakeNanos
            if (bakeNanos > bakeNanosMax) bakeNanosMax = bakeNanos
        }
        this.paintRebuilds += paintRebuilds
    }

    /** 会话结束：把整场的汇总落一行，然后清零。 */
    fun flush() {
        if (frames == 0) return
        val sorted = samples.copyOf(sampleCount).apply { sort() }
        fun at(q: Float): Float =
            if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * q).toInt()]
        Logger.d(
            "Orilumn.Curl",
            "perf frames=$frames avg=${fmt(totalNanos / 1_000_000f / frames)} " +
                "p50=${fmt(at(0.5f))} p90=${fmt(at(0.9f))} max=${fmt(maxNanos / 1_000_000f)} " +
                "bake=$bakedFrames(" +
                (if (bakedFrames > 0) "avg=${fmt(bakeNanosTotal / 1_000_000f / bakedFrames)} " +
                    "max=${fmt(bakeNanosMax / 1_000_000f)}" else "-") +
                ") paint=$paintRebuilds",
        )
        frames = 0
        totalNanos = 0L
        maxNanos = 0L
        sampleCount = 0
        bakedFrames = 0
        bakeNanosTotal = 0L
        bakeNanosMax = 0L
        paintRebuilds = 0
    }

    private fun fmt(ms: Float): String = (kotlin.math.round(ms * 10f) / 10f).toString()

    private companion object {
        /** 抽样上限：60fps 下够 10 秒，翻页会话远用不到这么多帧。 */
        const val MAX_SAMPLES = 600
    }
}
