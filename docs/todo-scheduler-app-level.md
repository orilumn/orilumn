# TODO: 将 TaskScheduler 提升到应用级

用户反馈：字体任务应该是系统级任务，需要应用级队列。

## 当前状态（已完成）

- P0 已完成，P1 全链路已完成（见 `breakpoint-20261014.md`）。
- TaskScheduler 已提升为应用级单例：`engine-skia/.../engine/AppTaskScheduler.kt`
  （`AppTaskScheduler.scheduler`，应用级 `CoroutineScope`）。
- `BookDocumentController` 经构造函数注入 `sharedScheduler`（默认 null ⇒ 退回「每书一个池」，
  测试零改动）；`close()` 只按**键域前缀**取消自己域内的活
  （`TaskScheduler.cancelMatchingPrefixes` + `CONTROLLER_KEY_PREFIXES`），不误杀它书/系统任务。
- Android `ReaderActivity` / 桌面 `DesktopReaderHost` 均注入 `AppTaskScheduler.scheduler`。
- 字体扫描任务 `fontscan`（档 5/45）已在应用级队列上跑，编排见
  `engine-skia/.../engine/SystemFontScanner.kt`；宿主接线见 `AndroidFontScan` / `DesktopFontScan`。

## 目标（已达成）

将 TaskScheduler 提升为应用级单例，注入到 BookDocumentController，支持「启动即 45 扫描」的系统级字体任务。

## 改动清单

1. ✅ **宿主顶层**（`desktopApp/.../Main.kt` 及各入口）创建应用级 `TaskScheduler` 实例，使用应用级 `CoroutineScope`
   —— 收敛为 `AppTaskScheduler` 单例，宿主经 `DesktopReaderHost` 注入。
2. ✅ **Android 入口**（`ReaderActivity` 及宿主创建处）注入应用级 `TaskScheduler`。
3. ✅ **BookDocumentController**：`sharedScheduler: TaskScheduler? = null` 注入；null 时保留
   「每书一个池」默认以兼容测试。
4. ✅ **DesktopReaderHost**：注入应用级 scheduler。
5. ✅ **TabletReaderHost**：`ReaderActivity` 构造控制器时注入。
6. ✅ **所有测试**：`sharedScheduler` 有默认值，既有构造调用无需改（仅
   `ShapingSlotsProbeTest` 改为调顶层 `shapingSlotsFor`）。

## 注意事项（落实方式）

- 单一并行度来源：全应用共享同一个队列。
- 字体任务（key=`fontscan`，档 5/45）与排版任务共处同一队列：面板开提档到 5，
  翻页 `cancelLowerThan(0)` 能抢占（被抢占后 `SystemFontScanner` 在面板可见时自动重提续跑）。
- `close()` 语义：控制器关闭只撤自己键域（`b1:`/`b2:`/`edge:`/`pg:`/`pre:`），
  不取消其他书或系统级 `fontscan` 任务。
