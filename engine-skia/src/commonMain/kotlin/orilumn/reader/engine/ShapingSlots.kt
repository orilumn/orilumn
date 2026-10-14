package orilumn.reader.engine

/**
 * 后台塑形槽预算（原则 §5）：`max(1, min(2, cpuCount - 2))` —— 留两份核给 UI / 目标页 / 系统。
 *
 * 上限 2 是 P0 真机实测的拐点（平板：轻页在 0/1/2 并发下耗持平，重页由内容主导、与槽数无关），
 * 既然加槽买不到速度就不必给更多核去抢。
 *
 * 抽成**顶层纯函数**（不属任何类）的原因：排版（[BookDocumentController]）与系统级字体扫描
 * （[AppTaskScheduler]）必须共用同一并行度来源，且 3 核一类设备上 `-1`/`-2` 的差别只有本函数
 * 能表达——写在内联/类成员里时，共享池无法在类外引用。
 */
fun shapingSlotsFor(cpuCount: Int): Int = maxOf(1, minOf(2, cpuCount - 2))
