package orilumn.reader.desktop.brightness

/**
 * 显示器真背光控制（桌面 JVM 壳，平台相关实现各自负责）。
 *
 * 语义（与设置层对齐，见 `ReaderSettings.brightness`）：
 * - 亮度值域沿用 VCP 原生 max（本机 max=100 即与滑块 0..100 直通）；
 * - `brightness <= 0` 一律只画遮罩，绝不碰硬件（`set` 只在 >0 时调用）；
 * - 不支持真背光的显示器（内建屏走系统键、外接无 DDC）由调用方回落遮罩，
 *   本接口只如实报告 [probe] 结果。
 */
interface DisplayBrightness {
    /** 一台在线显示器。 */
    data class Display(
        /** 稳定标识（厂商:产品:序列号），探测缓存键。 */
        val stableId: String,
        val builtin: Boolean,
        /** DDC/CI 可读写（未探测到时 false）。 */
        val ddcCapable: Boolean,
        /** VCP 最大值（亮度滑块上限换算用；0 = 未知）。 */
        val max: Int,
    )

    /** 探测在线显示器（阻塞 IO；每次调用重探，调用方缓存）。 */
    fun probe(): List<Display>

    /** 任一外接显示器支持 DDC（滑块量程/应用路径的唯一开关）。 */
    fun ddcCapable(): Boolean = probe().any { !it.builtin && it.ddcCapable }

    /** 所有 DDC 外接屏当前亮度（0..max；无/失败回 null）。 */
    fun current(): Int?

    /** 置所有 DDC 外接屏亮度（0..max，钳制；至少一台成功回 true）。 */
    fun set(level: Int): Boolean
}
