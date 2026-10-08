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

    companion object {
        /**
         * 按当前 OS 选实现（唯一构造入口，平台差异收敛于此）：
         * macOS 走 IOKit DDC（[MacDisplayBrightness]）；其余平台暂无 DDC
         * 通路，回落 [UnsupportedDisplayBrightness]（ddcCapable=false ⇒
         * 调用方按接口契约回落遮罩，亮度滑块钳到纯遮罩档）。
         *
         * TODO(Linux DDC)：Linux 外接屏 DDC/CI 可经 /dev/i2c-N（i2c-dev
         * 模块 + 用户组权限）走同一 [DdcPackets] 组帧，接 [DisplayBrightness]
         * 同一契约即可，调用方零改动。
         */
        fun currentOs(): DisplayBrightness =
            if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
                MacDisplayBrightness()
            } else {
                UnsupportedDisplayBrightness
            }
    }
}

/** 无 DDC 通路平台的回落实现：probe 恒空（⇒ ddcCapable()=false），读写空转。 */
private object UnsupportedDisplayBrightness : DisplayBrightness {
    override fun probe(): List<DisplayBrightness.Display> = emptyList()
    override fun current(): Int? = null
    override fun set(level: Int): Boolean = false
}
