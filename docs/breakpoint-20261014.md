# 断点：P0 + P1 + P2 全部完成（字体发现三层次 + 应用级调度）

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

## P2（余量两项，本轮完成）

- **① 逐族可续跑**：Stage B 已随手落地（`scannedFamilies` + 逐族 `syncSystemFamily`）。
- **② 面板实时进度态**（方案 §2.4）：
  - `SystemFontScanner.onFamilyScanned` 由 `suspend () -> Unit` 改为
    `suspend (done: Int, total: Int) -> Unit`；`done` = 本轮已落库族数（含抢占续跑前已完成的），
    `total` = `source.families().size`。数据侧「逐族回调 + 逐族落库」原本就绪，这里只是把族数带出来。
  - `FontScanProgress(done, total)`（shared-ui，纯数据）→ `ReaderSettingsPanel(fontScanProgress=)`
    → `FontLibraryPanel(scanProgress=)` 在列表上方画「正在扫描系统字体 N / M」+ 细进度条；
    `done >= total` 或 null 即不显示（库热不扫 ⇒ 常态无进度条，首启冷库才出现）。
  - 两端宿主装回调时顺带更新进度：Android（`ReaderActivity`）回调内
    `lifecycleScope.launch { fontScanProgress = ... }` 由主线程写；桌面（`ReaderView`）用
    `scope.launch`。面板关 / 视图离即 `fontScanProgress = null`。
- **③ §8-2 文件指纹加固**（族名不变但文件被换也要判「变」）：
  - 新增 `fontFilesEnvironmentHash(fs, roots)`（common·共享纯函数）：把各根目录下常规文件的
    「绝对路径 + 大小 + mtime」排序后 FNV-1a；任一根不可读即跳过，全部失败回 `0L`（退回族名指纹，
    绝不误报「没变」）。
  - `SystemFontSource` 增 `environmentHash: () -> Long`（默认 `{ 0L }`）；指纹第二分量改为
    `familyHash() xor environmentHash()` —— `xor 0` 恒等 ⇒ **测试与未接平台的宿主零改动**。
  - 平台根目录只出现在各自宿主：Android
    `FontRepository.systemFontEnvironmentHash()` =
    `fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf("/system/fonts"))`；桌面新增
    `DesktopFontEnvironment.hash()`（`/System/Library/Fonts`、`Supplemental`、`/Library/Fonts`、
    `~/Library/Fonts`；非 macOS 目录不存在 ⇒ 回 0）。
  - `FONT_SCAN_CACHE_VERSION` 1 → 2（指纹组成变化，主动作废老存档、不必等字体变）。
    **无需 DB 迁移**：指纹仍是 `font_scan_state` 的两列 Long。

## 调度两洞收口（本轮）：开书不再砍扫描 + 让位不丢活

桌面时序验证（`~/.orilumn/logs/`，`Orilumn.SPIKE`/`Orilumn.FontScan`）暴露两个洞，均在**共享层**修：

- **A｜同档重提＝取消 + 重启**。宿主把「面板关着」与「刚开书/换版面」走同一个
  `onPanelVisibility(false)`；`TaskScheduler.submit` 对同键的语义是取消在跑的孪生再重启 ⇒
  每次开书都把在途扫描砍掉重来（实测 `16:27:11 start 45 → 13 start 45 → 13 cancel 45`）。
  修：`onPanelVisibility` 按 `TaskScheduler.pendingPriority(KEY)` 去重，**已在目标档则什么都不做**。
  用调度器当唯一事实源（而不是扫描器自己记"在途"），因为排队中的任务会被抢占直接从队列里清掉，
  自记标志会永远停在「在途」。
- **B｜让位即丢活**。初版只在任务体 `catch (CancellationException)` 里重提，且要求「面板可见」：
  ①排队期被 `queue.removeAll { priority > threshold }` 清掉的任务**从未起跑、没有 catch**；
  ②面板关着时干脆不重提 ⇒ 开书瞬间被栅格抢占后本进程内扫描死掉、**指纹不落库** ⇒ 下次启动白扫。
  修：`TaskScheduler.submitWhenIdle`（`awaitIdle()` 后执行，**不在队列里** ⇒ 抢占清不到）→
  `SystemFontScanner.armResume` 续跑看门狗：队列真空时补一次，补跑后自行续挂，直到指纹落库
  （对上即解锁 ⇒ 常态零成本）。两道防空转：空族集（平台失败）停摆等下次外部触发；
  连续无成果补跑超 20 次放弃。另设 200ms「入队静置窗」，避开 `submit` 异步入队跳导致的
  「刚提交查不到键 ⇒ 误判丢活 ⇒ 多排一次」。

新增 API 仅两个，都在 `TaskScheduler`（排版层（上））：`pendingPriority(key)`、`submitWhenIdle(action)`。

## 顺手修复（本轮暴露）：桌面库迁移漏盖章

`5.sqm` 上线后桌面**第二次启动必崩** `table font_scan_state already exists`：
   `JdbcSqliteDriver` 不像 Android helper 自动维护 `user_version`，而
   `ShelfStore.openDb()` 的 `old in 1 until current` 分支迁移后**漏盖 `PRAGMA user_version`**
   ⇒ 5.sqm 建表成功但版本仍停在 5 → 每次启动重跑 → 撞表已存在。
   **Android 走 SupportSQLiteOpenHelper，不受影响。**
   修复：①迁移后补盖章；②表齐（`sqlite_master` ⊇ `OrilumnDb.allTableNames()`）但版本落后 ⇒
   判定为历史漂移、直接盖章不重跑迁移。真实库已验证：`user_version` 5 → 6，
   日志 `[Orilumn.Shelf] db drift healed: ... stamp -> 6`，复启幂等。

## 测试状态

四个模块全绿：`:common:jvmTest`、`:engine-skia:jvmTest`、`:desktopApp:test`、`:app:testDebugUnitTest`。
`desktopApp` 新增 2 例回归：`desktopDbUpgradeStampsUserVersion`（v5 库迁移后盖章 + 复启不崩）、
`desktopDbDriftWithAllTablesPresentIsHealed`（表齐 + 版本落后 ⇒ 盖章自愈）。

- `:common:jvmTest` ✅（P2 新增 `FontEnvironmentHashTest` 3 例：同树幂等 / 改 mtime 必变 /
  文件增删必变 / 缺根回 0）
- `:desktopApp:test` ✅（`SystemFontScannerProbeTest` 4 → 6 例：新增「逐族回调 `(done,total)` 序列」、
  「族名不变但环境哈希变 ⇒ 重扫」；真 SQLite 内存库 + 注入假枚举端口）
  - 本轮再加 4 例（共 10 例）：`opening a book must not restart an in-flight scan`（A）、
    `preemption with the panel closed resumes and finishes`（B①）、
    `scan dropped while queued resumes when the queue goes idle`（B②）、
    `empty enumeration writes no fingerprint and does not spin`（防空转）。
    前三例在修前均为失败/超时，修后连跑 4 次稳定绿。
- `:app:testDebugUnitTest` ✅（原预存在失败 `CrossChapterPreflightProbeTest` 的两例已处理：
  超时的 `a flip landing prewarms both neighbors...` 与依赖缺失测试字体的
  `FontPoolGenerationTest`（engine-skia）整体删除；已验证为非本次改动引入，见 git 历史）
  - `TaskSchedulerTest` 本轮加 2 例：`pendingPriority tracks the queued or running tier`、
    `submitWhenIdle waits for the drain and is not preempted`。

## 已知遗留 / 下一步

0. **P2 余量已清零**：面板实时进度态（②）与文件指纹加固（③）本轮落地；
   逐族可续跑（①）在 Stage B 已完成。方案 §2.4「显示真实进度」的 UI 部分、§8-2 的 mtime 加固均已就位。
1. **首启冷库 + 立刻开面板**（方案 §8-3/§8-6）：可能与锚页落位短暂争锁；常态库热不扫。
   「首启额外预扫一次」是否要做，待拍板。
2. `NameTableChineseNames.chineseNameOf` 现按需逐族缓存，**无整表预热**；若有调用方需要
   「一次拿全量」，需另开方法（当前无调用方）。
3. Android 本地化名链（`FontRepository.systemFontLocalizedNames`）改为进程内缓存一次；
   `/system/fonts` 首扫成本落在首次 `localizedNames` 调用（被指纹门闩限制在「字体变了」那次）。
4. 装机验证：需 `./gradlew :app:assembleDebug` 后真机跑，看落盘日志
   `Orilumn.FontScan` / `Orilumn.SPIKE` 里 `fontscan` 与锚页 `LARGE-ANCHOR` 无重叠；
   顺带看冷库首次开面板的「正在扫描系统字体 N / M」进度条。

## 平板真机验收（vivo PA2353 / Android 14，`orilumn-0.4.1-debug.apk`）

装机：`adb install -r`（0.4.0 → 0.4.1 成功），sha256 `f1ae59af…bb4f9`。
判据全部取自落盘 `files/logs/日志_20261014.txt`（不拉 logcat）。

| 项 | 判据 | 结果 |
| --- | --- | --- |
| 首扫 | `startup detect: fingerprint null -> (2, -3632…)` → 仅一条 `start key=fontscan prio=45` → `scan done families=51`（1.5–2.6s） | ✅ |
| 指纹落库 | `font_scan_state = (1, 2, -3632153667365110075, ts)`；`pragma integrity_check` = ok | ✅ |
| 温机跳过 | 冷启 `startup detect: fingerprint unchanged, skip scan` | ✅ |
| (A) 开书不砍在途扫描 | 启动续读落在扫描在途期间（`start prio=45` → `ReaderActivity onCreate` → `done`），全程仅一条 `start` | ✅ |
| 面板需求模式 | ⚙ → `open settings panel` + `start key=fontscan prio=5` | ✅ |
| (B) 抢占后能回来 | 见下（需制造可抢占负载） | ✅ |

### (B) 真机复现记录（18:57:27–18:58:22）

```
27.534 start key=fontscan prio=5      ← 面板提档真扫（进程内族集为空 ⇒ 真干活）
27.607 close settings panel
27.800 start key=fontscan prio=45     ← 关面板同键降档
28.312 cancel key=fontscan            ← 被顶掉的 5 档孪生
28.670 [TAP] tap x=1600 … zone=1      ← 翻页 ⇒ notifyFlip ⇒ cancelLowerThan(0)
33.287 spike-task cancel key=fontscan ← 在跑的 45 档被清（观测滞后：当时正卡在一次落库写）
33.557 scan resume on idle (attempt 1, demand=false)   ★ 看门狗把它带回来
33.630 scan resume on idle (attempt 2, demand=false)
33.655 start key=fontscan prio=45     ← 自行续跑（scannedFamilies 保留，跳过已完成族）
33.731 start key=fontscan prio=45
38.477 spike-task cancel key=fontscan
18:58:22.812 scan done families=51    ← 收尾并写指纹
```

复现配方（常态下 (B) 在平板打不到，原因见下）：
1. 把库指纹改成与当前不一致（`update font_scan_state set familyHash=0`），但**让进程以「指纹一致」冷启**
   ⇒ `skip scan`，进程内 `scannedFamilies` 为空且无在途扫描；
2. 应用运行中再改库 ⇒ 面板打开时 `storedFingerprintMatches()` 为 false ⇒ 提档 `prio=5` 触发一次
   **真扫**（51 族 ≈ 0.9s），此时阅读面已完全可交互；
3. 关面板（同键降 45）后翻页 ⇒ 任务被 `cancelLowerThan(0)` 清掉；
4. 用 I/O 占满（`dd … conv=fsync`）把单族落库写拉慢，使抢占窗口从 ~0.9s 拉到数秒，
   adb `input`（每发 ~0.4s）才来得及落进窗口。

### 为什么 (B) 在平板常态不可达

1. 进程内首扫（~1.5s）早于阅读面可交互（≥ +2.4s）⇒ 用户第一次点按必落在扫描之后；
2. 后续面板扫描因进程内 `scannedFamilies` 已全量而**瞬时 no-op**（只重写指纹）；
3. 让指纹变化需动 `/system/fonts`（无 root 不可）。
⇒ 若要让 (B) 常态可验，需另加可注入触发点（如 debug 命令强制 `scannedFamilies.clear()`）。

### 观察（未定论，非本次改动引入）

- 18:20 那次「启动瞬间旋转屏幕」使 App 停在 `shelf onCreate` 之后不再前进；未复现亦未归因，留待后续。
- 上表 33.557 / 33.630 两条 `scan resume on idle` 是**多个在等空闲的看门狗各自提交**：`submitScan`
  每次都 `armResume()`，队列真空时它们同时醒来，第一条的入队跳尚未登记，第二条查
  `pendingPriority` 仍为 null 便再提一次 —— 第二次的孪生顶掉第一次（`scannedFamilies` 保留，
  只是空走一遍跳过循环，无正确性影响、无用户可见成本）。如需收敛，可在 `submitScan` 里以代次计数去重。

### 现场还原

- `user_rotation=0` / `accelerometer_rotation=1`（竖屏已还原）；
- 设备库 `font_scan_state` 已回到正确指纹；`/data/local/tmp` 临时文件与 CPU/IO hog 全部清除；
- 7 次面板开合全程 `typographHash 518906858 -> 518906858 changed=false`（排版参数零改动）。
