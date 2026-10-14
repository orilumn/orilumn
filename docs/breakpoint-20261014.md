# 断点：P0 + P1（字体发现三层次 + 应用级调度）已完成

时间：Wed Oct 14 2026 ｜ 分支：`fix/platform-consistency`

## 已提交

- P0-1 `9c6f826` prepareRelayout/prepareRelayoutLight 判空提前到副作用之前
- P0-2 `1873205` 从开书路径摘掉字体扫描（ReaderView / ReaderActivity）
- P0-3 `5920ca5` TaskScheduler 新增 `PRIO_FONT_DEMAND=5`、`PRIO_FONT_SCAN=45`
- P1-1a `14c238b` `font_scan_state` 表
- P1-1b `e9371e2` 5.sqm 迁移
- P1-2 `72210d8` SystemFonts 新增 `FONT_SCAN_CACHE_VERSION` + `systemFontFamilyHash`
- P1-3a `38aa1af` LibraryDb 指纹读写
- P1-3b `211f0de` FontLibrary 指纹代理- Stage A `c01d63b` TaskScheduler 提升为应用级（`AppTaskScheduler`、`sharedScheduler` 注入、
  `close()` 键域取消 `cancelMatchingPrefixes`、`shapingSlotsFor` 顶层化）
- **Stage B `79a64aa`** 扫描编排 `SystemFontScanner` + 逐族落库 + 枚举注入端口 + 桌面
  `NameTableChineseNames` 逐族增量发布
- **Stage C `6cb03b9`** 宿主接线：启动检测 + 面板需求模式（Android / 桌面）

## 实现落点（相对方案 §5 表的两处偏差，均同层）

1. 扫描编排**未塞进 `BookDocumentController`**，另建
   `engine-skia/.../engine/SystemFontScanner.kt`（同在排版层·上）。控制器是「每书」对象，
   字体扫描是「应用级」任务，混在一起会让共享池的注入语义更绕；独立类也让注入端口干净。
2. 触发点是 `detected` 门闩 + 面板可见性，**未新增 Application 类**（工程无自定义
   `Application`；Android 侧挂在 `MainActivity`/`ReaderActivity` `onCreate`，桌面侧挂
   `Main.kt` 的 `store.load()` 之后）。

## 关键语义（已落地，均见代码注释）

- **开书零字体任务**：桌面 `ReaderView` 开书只 `fontLibrary.list()` DB 直读；Android
  `loadPanelFonts` 只读 DB。扫描改由「启动检测 / 面板可见性」驱动。
- **指纹** = `(FONT_SCAN_CACHE_VERSION, hash(sorted(systemFontFamilies())))`；启动只枚举族名比指纹。
- **需求模式** = 面板可见性 → 同键 `fontscan` 提/降档 5/45（同键重提＝改优先级，无需调度器新 API）。
- **抗抢占续跑**：逐族落库 + 进程内 `scannedFamilies`；面板可见时被 `cancelLowerThan(0)`
  （翻页）/`cancelForRaster(0)`（栅格）砍掉会**自动重提**续跑（代次门 `generation` 防提/降档互踢）。
  面板不可见时不自动重提，等下一次触发（面板打开 / 下次启动）。
- **面板增量刷新**：可更换的 `SystemFontScanner.onFamilyScanned`（打开时装、关闭/离视图时卸），
  每族落库后台线程调用，壳负责切主线程重读 DB。
- **单例理由**：`detected` 与 `scannedFamilies` 必须跨 Activity/视图切换保留（`AndroidFontScan`
  / `DesktopFontScan`，均用 `applicationContext` / 进程 store，不持 UI 引用）。

## 测试状态

- `:common:jvmTest` ✅
- `:desktopApp:test` ✅（新增 `SystemFontScannerProbeTest` 4 例：启动检测跳过/需求模式实扫/
  抢占续跑不重扫已完成族/收尾清孤儿；真 SQLite 内存库 + 注入假枚举端口）
- `:app:testDebugUnitTest` ✅（原预存在失败 `CrossChapterPreflightProbeTest` 的两例已处理：
  超时的 `a flip landing prewarms both neighbors...` 与依赖缺失测试字体的
  `FontPoolGenerationTest`（engine-skia）整体删除；已验证为非本次改动引入，见 git 历史）

## 已知遗留 / 下一步

1. **只认族名指纹**（方案 §8-2）：同族名换文件（改 name 表、族名不变）检测不到，P2 可加文件 mtime 加固。
2. **首启冷库 + 立刻开面板**（方案 §8-3/§8-6）：可能与锚页落位短暂争锁；常态库热不扫。
   「首启额外预扫一次」是否要做，待拍板。
3. `NameTableChineseNames.chineseNameOf` 现按需逐族缓存，**无整表预热**；若有调用方需要
   「一次拿全量」，需另开方法（当前无调用方）。
4. Android 本地化名链（`FontRepository.systemFontLocalizedNames`）改为进程内缓存一次；
   `/system/fonts` 首扫成本落在首次 `localizedNames` 调用（被指纹门闩限制在「字体变了」那次）。
4b. 扫描任务的**进度态**（已 N / 共 M 族）尚未接到 UI——方案 §2.4「显示真实进度」的 UI 部分未做，
   数据侧（逐族回调 + 逐族落库）已就绪。
5. 装机验证：需 `./gradlew :app:assembleDebug` 后真机跑，看落盘日志
   `Orilumn.FontScan` / `Orilumn.SPIKE` 里 `fontscan` 与锚页 `LARGE-ANCHOR` 无重叠。
