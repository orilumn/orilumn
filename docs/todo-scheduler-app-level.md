# TODO: 将 TaskScheduler 提升到应用级

用户反馈：字体任务应该是系统级任务，需要应用级队列。

## 当前状态
- TaskScheduler 在 `BookDocumentController` 内部创建（每书一个），`bgScope` 由控制器生命周期管理。
- P0已完成，P1-1a/b已完成（`font_scan_state` 表+5.sqm 迁移）。

## 目标
将 TaskScheduler 提升为应用级单例，注入到 BookDocumentController，支持「启动即 45 扫描」的系统级字体任务。

## 改动清单
1. **宿主顶层**（`desktopApp/src/main/kotlin/orilumn/reader/desktop/Main.kt` 及其他入口）创建应用级 `TaskScheduler` 实例，使用应用级 `CoroutineScope`。
2. **Android 入口**（`app/src/main/java/orilumn/reader/ui/reader/ReaderActivity.kt` 及宿主创建处）创建应用级 `TaskScheduler` 实例。
3. **BookDocumentController**：将 `private val scheduler` 改为通过构造函数注入 `scheduler: TaskScheduler`。保留默认值以兼容测试：`scheduler: TaskScheduler = TaskScheduler(bgScope, backgroundDispatcher, maxSlots)`，但要注意 bgScope 生命周期与应用级调度器的关系。
4. **DesktopReaderHost**：注入应用级 scheduler。
5. **TabletReaderHost**：根据实际创建方式注入应用级 scheduler。
6. **所有测试**（约26处）更新构造函数调用。

## 注意事项
- 保持单一并行度来源：全应用共享同一个队列。
- 字体任务（key="fontscan"，档5/45）与排版任务（邻页/B1/B2/预热）共处同一队列才能实现正确抢占：面板开提档到5，翻页调用 `cancelLowerThan(0)` 能抢占。
- 考虑 `close()` 语义：控制器关闭不应取消应用级共享的其他书或系统任务。目前字体任务是"系统级"的，可以单独用 `cancelKey("fontscan")` 处理。
