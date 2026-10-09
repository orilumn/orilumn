package orilumn.reader.ui.reader

import kotlinx.coroutines.sync.Mutex
import orilumn.reader.io.Logger

/**
 * 锚页事件串行漏斗（用户层）：显示状态（`ReaderScreen.openPos`）的唯一写入通道。
 *
 * 规矩只有一条：每次锚页变更先 try-lock，看到锁就放弃当前动作（记 `BUSY-DROP`，不断链、不报错），
 * 解锁后才能执行新动作。不排队、不等帧——排队攒出延迟，帧门控攒出复杂度，冻死的风险一个不要。
 * 代价写在明处：边界跨章落地 ~1s 内后到的点按会被丢掉（日志可查）；解锁后的下一次点按按最新位置
 * 重取源，所以 10 次点按是 10→11→…→20 的链式推进，不会 10 次全从同一页出发。
 *
 * 约束：[run]/[commit] 内禁止重入本漏斗（Mutex 不可重入，自锁）。[commit] 只做状态赋值一类
 * 瞬时动作，重活一律在 [run] 里做完。
 */
class AnchorFunnel(private val logTag: String = "Orilumn.TAP") {
    private val mutex = Mutex()

    /**
     * **提交前的最后一刻**：等目标页像素就绪（等图），再让它显示。
     *
     * 收口在这里而不是散在各调用点，是因为漏斗是 `openPos` 的唯一写入通道：
     * `commit(dst)` 一执行，页面首帧就开始画，而插图位图靠
     * `rememberPageContent` 的 `LaunchedEffect` 异步补 ⇒ 首帧必是灰占位块，
     * 图到后再整页重栅格（`PageRasterFingerprint.images` 的 skia Image 按实例比，
     * 换了实例即缓存失效）——用户看到的就是「页面先出现、图再刷新」两跳。
     *
     * 早先只有 `jumpChapter` / `pageAtFraction` / `openLink` 三条路径各自
     * `.also { warmPageImages(it) }`，最常走的 `flip` 与 `external` 推送都漏了，
     * 于是闪烁时有时无。挂在这里，新增落位路径自动继承。
     *
     * 由 [ReaderScreen] 注入（它持有 `imgCache`/`bgCache`）；为 null 时退化为旧行为。
     * 解码真身在 `Dispatchers.IO`，不占主线程；纯文页无插图直接返回、零等待。
     */
    var beforeCommit: (suspend (ReaderPos) -> Unit)? = null

    /**
     * 需要源的导航（翻页/跳章/seek/开链接）：有锁直接放弃；无锁则取最新源 → 执行 → 提交。
     * 无源（首次打开前）不执行直接回 null；[run] 回 null（到边界/失败）则不提交。
     */
    suspend fun navigate(
        action: String,
        read: () -> ReaderPos?,
        commit: (ReaderPos) -> Unit,
        run: suspend (ReaderPos) -> ReaderPos?,
    ): ReaderPos? {
        if (!mutex.tryLock()) {
            Logger.d(logTag, "$action BUSY-DROP (another anchor event in flight)")
            return null
        }
        try {
            val src = read()
            if (src == null) {
                Logger.d(logTag, "$action DROPPED (no source)")
                return null
            }
            Logger.d(logTag, "$action start from ch=${src.chapter} char=${src.slice.charStart}")
            val dst = run(src)
            if (dst == null) {
                Logger.d(logTag, "$action NULL (no target)")
                return null
            }
            // 显示前把像素备齐（见 beforeCommit KDoc）。失败/超时按缺图放行，
            // 由渲染侧的异步补齐兜底——绝不能因为图没备好就不翻页。
            runCatching { beforeCommit?.invoke(dst) }
            commit(dst)
            Logger.d(logTag, "$action done → ch=${dst.chapter} char=${dst.slice.charStart}")
            return dst
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 无源推送（开书/外部落位）：同样 try-lock，看到锁就放弃。各事件的提交语义差异
     * （存档防抖/代际推进）由调用方的 [commit] 保留，漏斗只管互斥。
     */
    suspend fun push(
        action: String,
        commit: (ReaderPos) -> Unit,
        run: suspend () -> ReaderPos?,
    ): ReaderPos? {
        if (!mutex.tryLock()) {
            Logger.d(logTag, "$action BUSY-DROP (another anchor event in flight)")
            return null
        }
        try {
            val dst = run()
            if (dst == null) {
                Logger.d(logTag, "$action NULL")
                return null
            }
            runCatching { beforeCommit?.invoke(dst) }
            commit(dst)
            Logger.d(logTag, "$action done → ch=${dst.chapter} char=${dst.slice.charStart}")
            return dst
        } finally {
            mutex.unlock()
        }
    }
}
