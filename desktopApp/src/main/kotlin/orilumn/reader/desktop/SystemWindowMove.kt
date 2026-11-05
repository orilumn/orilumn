package orilumn.reader.desktop

import com.jetbrains.JBR
import java.awt.Window
import java.awt.event.MouseEvent
import orilumn.reader.io.Logger

/**
 * 把「拖动窗口」交给窗口管理器（**平台层·桌面壳**，仅 Linux/X11 生效）。
 *
 * ## 为什么必须交给 WM
 *
 * 客户端自己改窗口坐标（`window.location = …`）在 X11 上是一次 configure request，
 * Mutter 会把它标成 `META_MOVE_RESIZE_CONSTRAIN` 再套一遍屏幕约束：请求 `-200` 落 `0`、
 * 请求 `5000` 落 `2160`（本机实测，2560×1600）。Mutter 源码里只有「窗口管理器自己正在拖」这一条
 * 豁免（`meta_window_move_resize_internal`：Do not constrain if it is tied to an ongoing window drag），
 * 所以「拖出屏幕 / 贴边吸附」只能由 WM 来搬。
 *
 * JBR 的 `WindowMove` 就是这条路的现成封装（X11 WM-assisted window moving）：它替我们发
 * `_NET_WM_MOVERESIZE`，之后指针被 WM 抓住，窗口位置全由 WM 算——本类**不再写窗口坐标**，
 * 否则就是两个写入者抢同一扇窗（macOS 那条老坑，见 [draggableWindow] 的 KDoc）。
 *
 * ## 平台与运行时口径
 *
 * - 只有 JetBrains Runtime（JBR）有实现：`JBR.isAvailable()` 在 Temurin/OpenJDK 上为 `false`，
 *   `WindowMove` 走空实现，故 [start] 返回 `false`，调用方原样回落到自绘拖动——**不崩、不降级外观**；
 * - `WindowMove` 只在 X11（含 XWayland）可用：Windows / macOS 上 `isWindowMoveSupported()` 为
 *   `false`；Windows 本来就不钳程序化移动，macOS 是系统窗口（拖动归 AppKit），都不需要它。
 */
internal object SystemWindowMove {

    private const val TAG = "Orilumn.WindowMove"

    /**
     * 本机运行时能否把拖动交给 WM（JBR 且 WM 支持 `_NET_WM_MOVERESIZE`）。
     *
     * 只问一次：能力是进程级常量，且 `JBR` 类初始化在非 JBR 上会抛，用 [runCatching] 兜住后
     * 永久记住结果。非 JBR 上连 `JBR` 类都可能是 JDK 内置的空壳，故不能直接引用后再判断。
     */
    val supported: Boolean by lazy {
        runCatching { JBR.isAvailable() && JBR.isWindowMoveSupported() }.getOrDefault(false)
    }

    /** 调用失败只记一次，避免每次拖动都刷一行。 */
    @Volatile
    private var startFailureLogged = false

    /**
     * 启动时打一行运行时口径：读者反馈「拖不出屏幕」时，先看这行就知道跑的是不是 JBR。
     * 没有日志根目录时 [Logger] 自己静默跳过，这里不用判。
     */
    fun logRuntime() {
        Logger.i(
            TAG,
            "vendor=${System.getProperty("java.vendor")} version=${System.getProperty("java.runtime.version")} supported=$supported",
        )
    }

    /**
     * 从这一刻起把拖动交给 WM。
     *
     * @return `true` = 已交接，调用方**必须停止写窗口坐标**；`false` = 当前运行时/平台不支持，
     *   调用方继续用自绘拖动。
     */
    fun start(window: Window): Boolean {
        if (!supported) return false
        return runCatching {
            JBR.getWindowMove().startMovingTogetherWithMouse(window, MouseEvent.BUTTON1)
            // 每次拖动一行：读者反馈「拖出屏幕没效果」时，有这行说明交接本身成功了，
            // 问题在 WM 侧（Mutter 是否接受 _NET_WM_MOVERESIZE），不在我们这条路径。
            Logger.i(TAG, "delegated at ${window.location} size=${window.size}")
            true
        }.getOrElse { t ->
            if (!startFailureLogged) {
                startFailureLogged = true
                Logger.w(TAG, "startMovingTogetherWithMouse FAIL: $t")
            }
            false
        }
    }
}
