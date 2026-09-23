# C2 控制器进 engine-skia 收敛方案（单编排宿主）

> 母文档：`docs/平台一致性整改方案.md` §5 C 系列、`docs/架构分层与共享边界.md`（原则：凡非"OS 强制 / 真系统能力"的逻辑与渲染语义一律共享；平台壳只留进程入口 + expect/actual 接缝 + 打包）。
> 系列编号：**C2**，C1 的续作。C1 收了契约（同表格式、同参数键、同优先级/存档顺序），控制器本体（2445 行）仍在 `:app`，
> 桌面因此另起 584 行简化管线 —— 本文件收的就是这条尾巴。
> 目的地是 **engine-skia** 而非 `:common`（依赖方向只允许单向：engine-skia `api(:common)`，
> `:common` 不能反向引用 skia 类型 `DrawLine`；C1 原文也是"common **或** engine-skia"）。
> 分支：`feat/controller-to-common`（基点 `37ca258`，分支名沿用早建的名字，实际目的地见上）；做完切回 `fix/pagination-continuity`。
> 本文件是**续接依据**：会话中断时按「当前状态 / 剩余步骤」可直接恢复，不必重新勘察。

---

## 0. 动因（一句话）

桌面实测分页正常，反查发现桌面根本没走增量编排（单章整塑形，无 temp/预填/交接），
有问题的路只在平板跑 —— 一个程序里含两套阅读管线，与 KMP 单源原则相悖。
C2 把 `BookDocumentController` 搬进 engine-skia，桌面切过去并删简化管线，
之后"分页连续性"类问题在桌面即可复现。

## 1. 勘察结论（事实，2026-09-22，勿重做）

- 控制器：`app/src/main/java/com/orilumn/engine/BookDocumentController.kt`，2445 行，
  import 共 36 个，其中平台相关仅 6 处（§2 表），其余（css/html/layout/paging/text、
  `DrawLine`、`EpubParser/EpubResourceReader`、协程、okio、common `Logger`）已在 `:common`/`:engine-skia`。
- 平板宿主：`app/src/main/java/com/orilumn/ui/reader/TabletReaderHost.kt` 207 行薄壳，
  持 controller（`:35`），翻页/跳读/存档全走控制器语义。
- 桌面宿主：`desktopApp/.../DesktopReaderHost.kt` 584 行，对 controller/temp/增量/预填**零引用**
 （grep 0 命中），自研简化管线：整章一次塑形（`DesktopReaderHost.kt:69` 无增量），
  磁盘表只做页数口径，绘制走新鲜塑形。
- 搬迁簇（P2 范围，2026-09-22 盘点）：控制器拖着 4 个同模块依赖，是否可搬逐个有结论 ——
  `BoxChapterLayouter.kt` 2429 行零 Android import（只剩 `IdentityHashMap`×3，见 §6）；
  `ChapterLayouter.kt` 42 行全 common import，直接搬；`ImageLoader.kt` 88 行零平台引用，直接搬；
  `FontPool`（50 行，Android `Typeface` 投影）**只画不量**（自证见 `ParagraphShapes.shapeOf`
  KDoc `@param pairing … draw-paint face only; never measures`，几何全走 skia），抽象安全；
  `EpubParser/EpubResourceReader`、`BookReadingState` 已在 `:common`，`DrawLine` 等已在 engine-skia。
- 卷曲路径休眠：`CurlCoordinator` 全仓零构造（grep 除自身无引用），`CurlView` 同理；
  封面 Bitmap 的唯一活读者是 `ReaderActivity` 一条日志 —— P1 移封面无真机回归面（卷曲接线时宿主自解）。
- 依赖方向（决定目的地）：engine-skia `api(project(":common"))`，反向不行；
  `:app` 已同时依赖 `:common` + `:engine-skia`，故 P2 搬完 `:app` 侧零 import 改动（同包同名）。

## 2. 六处纠缠 → 缝（全部有解，无需新技术）

| # | 纠缠 | 位置 | 缝（P0/P1 已按此落地，见状态） |
|---|---|---|---|
| 1 | 封面 `android.graphics.Bitmap` | `coverBitmap/hasCover`，读方仅休眠的 `CurlCoordinator` + `ReaderActivity` 一条日志 | 封面移出控制器，只留 `coverHref: String?`；`CurlCoordinator` 改宿主给图（未接线，默认 null）；分页不依赖封面 |
| 2 | `BookRepository`（:app，Room） | `open()` 内唯一一次 `repository.readingState(bookId)` | **复用现成** common `BookReadingState`（S34a 已迁，无新类型）：`open(bookId, saved: BookReadingState?)`，`:app` 薄壳先读后传；`parseLocator` 改 Regex 替 `org.json`（零新依赖，扁平 locator 语义不变：不可解析回 null，缺键默认 0） |
| 3 | `BoxPageRenderer`（:app，Android Canvas 渲染器，不可搬） | 控制器只 `as?` 调 4 个回读方法（行窗/表格线/图/背景，全是 skia/common 类型） | **engine-skia** 新增 `LayoutReadback` 接口；`BoxPageRenderer` 接上（4 方法加 `override`），控制器 4 处改认接口；桌面布局实现 P3 接同一接口 |
| 4 | `EngineLog`（:app）与 common `Logger` 混用 | 全文件 67 处（w×58/e×9） | 统一到 common `Logger`（签名同形，逐字替换） |
| 5 | `java.io.File`（`cacheRoot`） | `PaginationCacheStore` 本就要 okio Path | 字段改 okio `Path`，转换只留调用边界（10 测试 + `ReaderActivity` 共 11 处跟进） |
| 6 | `Executors.newSingleThreadExecutor`（JVM-only） | 仅默认分发器，且已有 `injectedCanonicalDispatcher` 缝（C1-2） | 默认 `Dispatchers.Default.limitedParallelism(1)`，删 Executors（桌面 JVM 照走；iOS 将来另起 expect/actual，不在本系列） |

`open(bookId, repository)` → `open(bookId, saved)`（12 测试调用点换 named-arg）；
`coverBitmap/hasCover` 删除；其余调用方（`TabletReaderHost` 薄壳）只改传参，无 import 变动。

## 3. 步骤（一步一 commit，每步全绿才走下一步）

- **P0 无行为缝**：#4 日志统一 + #6 分发器默认 + #5 `cacheRoot` 改 okio Path。
  验收：`:app:testDebugUnitTest` 全绿，行为零变（11 文件，调用边界转换 only）。
- **P1 API 缝**：#1 封面移出（控制器只留 `coverHref`，`CurlCoordinator` 改宿主给图，
  `ReaderActivity` 日志去 cover 口径）+ #2 `open()` 改吃 common `BookReadingState`
 （`TabletReaderHost` 先读后传，12 测试换 named-arg，`parseLocator` 改 Regex）+ #3 新增
  engine-skia `LayoutReadback` 接口并让 `BoxPageRenderer` 实现、控制器内 4 处改认接口。
  验收：`:engine-skia:jvmTest` + `:app:testDebugUnitTest`（157 例）全绿；卷曲路径休眠，无真机回归面。
- **P2 搬文件（真规模与分步见 §6）**：P2b-1 common 加法 → P2b-2 engine-skia 几何实现 →
  P2b-3 `:app` 退化包装 + 重定类型 → P2b-4 搬 4 文件（控制器 + `BoxChapterLayouter` +
  `ChapterLayouter` + `ChapterUnit`，同包同名，`:app` 侧删原文件，调用方零改动）。
  `FontPool`/`ParagraphShapes`/`ParagraphShape`/`BoxPageRenderer`/`DrawableBookLayout`/
  `CssLayouter` 留 `:app`（Android 绘制桥）。
  验收：每步 `:engine-skia:jvmTest` + `:app:testDebugUnitTest` 全绿 + 等价单测绿。
- **P3 桌面收编**：`DesktopReaderHost` 改持 engine-skia 控制器 + 布局实现接 `LayoutReadback`，
  删 584 行简化管线；按同一分发器划分打开后台 canonical/整书预排（TODO F 在此闭环）。
  验收：`:desktopApp:test` 全绿 + **字节级对等**：同书同设置，平板与桌面分页表字节一致；
  然后回 `fix/pagination-continuity`，在桌面全量管线上复现原连续性问题。

## 4. 非目标（本系列不碰）

- iOS（L 阶段）：`Dispatchers.IO/Default`、`limitedParallelism`、§6 的 `identityMap`/`SyncLock`/
  `platformNowMs` 各平台 actual 留给 L，不堵死（缝已留，JVM/Android/Desktop 全用 JVM actual 跑通）。
- Android 绘制桥不动：`BoxPageRenderer`、`ParagraphShapes`、`ParagraphShape`（`drawPaint: TextPaint`）、
  `FontPool`、`CurlView/CurlCoordinator` 留 `:app`。
- 桌面 UI/书架/打包不动。

## 5. 风险

- P3 删桌面管线前先做字节级对等，对不上不删（简化管线保留作比对基准直至对等通过）。
- `limitedParallelism` 需 coroutines 版本支持（已在用 1.11.0，有该 API）。
- §6 形状缝是全系列最大手术面：几何抽取必须逐字节等价，靠 157 例 + 字节级对等双保险；
  对不上就停在 P2a（纯机械项），不动形状。

## 6. P2 缝明细（2026-09-22 盘点，动手前先看完；P2b-1 前按本节最终版对齐过一次）

最终切法一句话：**同一对象、两种视图 + 几何单源**。形状对象不搬（画笔出不去），
几何接口与几何实现进 engine-skia，`:app` 的 `ParagraphShape` 只多实现 4 个成员；
`BoxChapterLayouter` 改认接口，几何只调共享实现，两端分页字节天然一致。
`DrawableBookLayout` 不用搬：控制器内用到它的 10 处全是行几何（`lineCount/getLine*/length/
isParagraphBoundaryLine`，`BookLayout` 全有）与 4 处回读（已有 `LayoutReadback`），
改认 `BookLayout`/`LayoutReadback` 即可，Canvas 绘制面完整留在 `:app`。

搬迁 4 文件：`BookDocumentController`（2445 行）+ `BoxChapterLayouter`（2429 行）+
`ChapterLayouter`（42 行）+ `ChapterUnit`（持有形状与分页状态；仅协程 import，可搬）。
`ImageLoader` 已在 P2a 搬走。`FontPool`/`ParagraphShapes`/`ParagraphShape`/
`BoxPageRenderer`/`DrawableBookLayout`/`CssLayouter` 留 `:app`（Android 绘制桥）。
`ChapterLayouter` 之所以能搬：签名里的 `DrawableBookLayout`/`FontPool` 随形状缝一并改认接口
（P2b-3），搬时已无 `:app` 类型。

- **锁**：`synchronized(tempStateLock)` ×7（控制器）+ `synchronized(structure)` ×1
  （`prepareLight` 按缓存实例加锁）。**注意：stdlib `kotlin.concurrent.SynchronizedObject`
  在本工程 JVM 模块与 common 源集均不可见（实测）**，改走自建缝
  `orilumn.reader.collections.SyncLock`（expect class + `withLock`，JVM actual 一行
  `synchronized`；iOS actual 留 L）。调用点写表达式风格（块里不 `return`），
  `prepareLight` 处锁对象不变（仍是缓存实例的同一性语义，由 `SyncLock` 承载）。
- **时钟**：`System.currentTimeMillis()` ×23（全是 profiling 计时）→
  新增 `platformNowMs()` expect/actual（JVM 一行 `currentTimeMillis`，iOS 留 L；
  `kotlin.time.Clock` 只在 JVM 可见，不用）。P2a 的 `nowMs()` 到时一起换掉。
- **同一性 Map**：（P2a 已落地，`identityMap`，见上）
- **形状缝（最大，P2b-2 落定几何，P2b-3 接线，P2b-4 搬家）**：`ParagraphShape.drawPaint: TextPaint?`
  把画笔 bake 进形状模型，且量体依赖它 —— 不抽象画笔就不断链。但**不新建大接口**：
  `:common` 的 `ParagraphShapeRef` 只差 4 个成员（P2b-1 已扩展；另加未来卷曲取画笔要的
  `shapeFontRequest`（tag/族名/粗斜体三件套，未来卷曲取画笔用；pairing 只画不量）；
  几何数据类 `ShapedGeometry` 已就位。engine-skia `shapeGeometry()` 与 `:app` `shapeOf`
  的 5 例等价锁已绿（P2b-2），几何单源成立。
  P2b-3 接线（全在 `:app` 内编译）：`shapeOf` 退化成"共享几何 + TextPaint 包装"
  （签名不动，单测不动）；`BoxChapterLayouter` 34 处形状持有改认 `ParagraphShapeRef`，
  `shapeLeaf/tempShape`/表格路径改调共享几何；`pairing: FontPool` 从搬走方法的签名里
  **删除**（控制器 ~26 处传参同步删，`fontPairing` 字段/构造参删除 —— pairing 退出编排层，
  只活在 `:app` 包装里）；产品类改认公共类型（`BoxDrawableLayout` 本体零 Android 引用，
  可搬；`PartialDrawableLayout` 随文件走；公共窗口实现抽 `WindowedBookLayout` 进
  engine-skia，`:app` 基类只剩休眠 canvas 桥，与 live 代码 ~60 行 dormant 重复已注记）；
  `DrawableBookLayout` 引用退成 `BookLayout`/`LayoutReadback`（控制器 10 处全是行几何 +
  4 处回读）。canvas 整页绘制（`PageRenderer.drawPageSlice`）唯一调用方是休眠的卷曲路径，
  P2b-3 后它对新产品直接 no-op（cast 失败即返），卷曲接线时宿主自理画笔。
  验收：157 例 + 等价单测绿；P2b-4 纯搬家（5 文件，同包同名）。

## 当前状态

- [x] 勘察（§1§2，2026-09-22）
- [x] P0（`9e0a9d7`，:app:testDebugUnitTest 全绿）
- [x] P1（API 缝 + `LayoutReadback`，:engine-skia:jvmTest + :app 157 例全绿；卷曲路径未接线，无真机回归面）
- [x] P2b-1（common 加法：接口扩展 + `ShapedGeometry` + `platformNowMs`）
- [x] P2b-2（engine-skia：`shapeGeometry` + 5 例等价单测绿）
- [x] P2b-3（:app 内接线：包装退化 + 重定类型 + 删 pairing + 锁/时钟，162 例绿）
- [x] P2b-4（`db445d3`：搬 5 文件 + 锁/时钟/流标识 + CssLayouter 纯函数下沉 + 探针钩提 public，五套单测全绿）
- [x] P3 收编（`04bc22a`：桌面改持共享控制器 + 删简化管线 + 单源取图 + 页行稳定排序；
  `:desktopApp:test` 8 例 + `:engine-skia:jvmTest` + `:app:testDebugUnitTest` 全绿；
  字节级对等：同控制器/同布局器/同 Store/同参数键，桌面无自有塑形，写穿回读 roundtrip 已锁）
- [ ] P3 收尾：回 `fix/pagination-continuity`，在桌面全量管线上复现原连续性问题
