package orilumn.reader.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * **应用级后台任务队列**（排版层（上），共享收口）—— 全应用唯一的 [TaskScheduler]。
 *
 * 为什么必须应用级（而非每书一个）：
 * - 字体扫描是**系统级**任务（不隶属任一本书），且「启动即扫」发生在开书之前——
 *   它需要一条不随 `BookDocumentController` 生灭的队列；
 * - 只有与排版任务（邻页 / B1 / B2 / 预热）**共处同一队列**，优先级抢占才有意义：
 *   翻页的 `cancelLowerThan(PRIO_FLIP)`、栅格的 `cancelForRaster` 才能一刀砍到字体扫描；
 *   面板打开时同键把 `fontscan` 从 45 提到 5，才能抢在投机预排之前。
 *
 * 生命周期：进程级单例，`SupervisorJob` 支撑的应用级 [CoroutineScope]，**永不主动取消**
 * （随进程结束回收）。单本书关闭走 [BookDocumentController.close] 的键域取消，
 * 不碰共享池里的他书/系统任务。
 *
 * 并行度：与 `BookDocumentController` 同口径 `max(1, min(2, cpuCount - 2))`（原则 §5）——
 * 单一并行度来源，字体扫描与排版共用同一槽预算，不叠加。
 */
object AppTaskScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全应用唯一队列。构造即定槽数（[TaskScheduler.maxSlots] 运行时可调，此处给默认）。 */
    val scheduler: TaskScheduler by lazy {
        TaskScheduler(
            scope = scope,
            workerDispatcher = Dispatchers.Default,
            maxSlots = shapingSlotsFor(platformCpuCount()),
        )
    }
}
