# Project Status

> Keeps a running log of significant milestones for the Orilumn reader engine. Supersedes
> everything marked done; each section reflects a completed stage.

## 2026-10-01 — 分页缓存失效自动化：`LAYOUT_VERSION` 改为引擎源码指纹，人不再需要记得 bump

**Issue.** 分页磁盘表的 key 是 `LayoutParamKey.hash()`，只含**版面参数**、不含排版算法版本。
所以「改了断行/度量算法但版面参数没变」时旧表继续命中，读者看到的是**上一版算法的分页结果**——
本轮修好软连字符行尾不画 `-` 后，真机「看起来完全没生效」，就是因为忘了 bump `LAYOUT_VERSION`。
现成的 `appVersion` 兜底也救不了：`BuildConfig.VERSION_CODE`（写死 `= 20`）与桌面
`DISK_CACHE_VERSION`（`= 1`）都是**常量**，只在发版升级时触发，开发期一次都不触发。

**Resolution.** `LAYOUT_VERSION` 从手写常量（历史 18…31）改为**引擎源码指纹自动导出**：
根 `build.gradle.kts` 的 `generateLayoutGeometryStamp` 对已登记引擎模块（`common` + `engine-skia`）
所有主源集文件内容求 SHA-256，取前 4 字节生成 `LayoutGeometryStamp.VALUE`。
源码变 ⇒ 值变 ⇒ 旧表在唯一的 `decode` 关卡被拒 ⇒ 自动重排；源码没变 ⇒ 值不变 ⇒ 缓存跨构建存活。
指纹故意取粗（宁可多作废不可少作废，多作废的生产成本为 0，因为发版本来就被 `appVersion` 全量覆盖）。
另加 `:unregisteredEngineCode` 守卫：未登记模块里出现 `orilumn.reader.engine` 包下的 .kt 直接 **fail 构建**
（把「新增引擎模块忘了登记」从注释提醒升级成硬检查）。`decode` 的 miss 原因带 `DIGEST_PREFIX` 便于诊断。

**Locks (mutation-verified).** 内容改动 → 指纹变 / 还原 → 回到原值；未登记模块放引擎代码 → 守卫红；
删掉生成物后 `:app:assembleDebug` 仍通过（`:common` 全任务 dependsOn 指纹任务）。
顺带把 `PaginationCacheTest` 里写死 `layoutVersion = 0` 的几何关卡锁改成 `LAYOUT_VERSION + 1`
（不依赖「指纹恰好非 0」）。全量 `jvmTest` + `test`：tests=1031 failures=0 errors=0。

**Lessons.** ① Kotlin 块注释**可嵌套**——在 KDoc 里写 glob `src/*Main` 会让 `/*` 打开嵌套注释，
把整份构建脚本剩余部分吞掉，**脚本照样编译通过、构建照样成功、连故意写错都不报错**，
只表现为「任务找不到」。⇒ 构建脚本的说明一律用 `//` 行注释。
② 任务依赖不要按任务名过滤：KMP-AGP 的 android 目标编译任务叫 `compileAndroidMain`，
`startsWith("compile") && contains("Kotlin")` 会漏掉它。

## 2026-09-30 — 排版 R1：带 `text-indent` 的单行段「只差一两个字却不换行、尾部被裁」（LAYOUT_VERSION 30）

**Issue.** 一段本来只有一行的文字，在「版心宽 − 边距」恰好比它的整段宽少 1~2 个字时，不折行也不掉字，
而是尾部 1~2 个字被版心右缘切掉（读者看到「这段字被拦腰截断」）。真机参数 + 真书
（《每天都离现形更近一步》ch1，正文 44.4px、`p { text-align: justify; text-indent: 2em }`）复现：
版心 1288 时该段右缘 1376.43，**溢出 88.4px = 2 个字**。

**根因（排版层-下，`SkiaParagraphBreaker`）。** 断行侧用 SkParagraph 的 `TextIndent` 承担首行缩进，
而 `TextIndent` **只在首行真的折行时**扣减首行可用宽；「整段放得下一行」的快捷路径拿整段自然宽
直接比 layout 宽，**不扣 indent**。真机字体栈（`STSong, serif` @44.4px，26 字段 `nat=1287.60`，
`indent=88.80`）下「整段单行」区从 `ceil(nat)=1288` 起，对 indent 三个取值（0 / 44.4 / 88.8）
**完全相同**。于是窗口 `版心 − indent < 整段自然宽 ≤ 版心` 内断行侧判「放得下」不换行，绘制侧
（`LineWindowDrawer`，`paintX = textX + firstLineIndentPx`）仍把整行右移 indent → 右缘越出版心至多
indent。只在一行时发作：一旦折行，Skia 自己就把首行按 `版心 − indent` 排对了。

**修复。** `SkiaParagraphBreaker.breakLines` 抽出 `layoutOnce`（单次整形 + 区间归一化，配置同源），
命中窗口（单行 && `nat + indent > 版心`）时按首行真实可用宽 `版心 − indent` **整段重排**、
`firstLineIndentPx` 传 0 避免二次扣减。修后单行区从 `ceil(nat + indent)` 起——正是 CSS 语义。
整段都用窄宽仍与 CSS 等价：命中窗口时整段自然宽 ≤ 版心，贪心首行把窄宽填到「差一个字就溢出」，
剩余尾巴宽 ≤ indent + 一字，远窄于版心，故第 2 行起在两种宽下的断点必然相同。
重轻两路共用这一个断行器，一处修好两条路；`LAYOUT_VERSION 29→30` 让旧表作废自愈。

**机制证明（`FirstLineIndentSingleLineOverflowTest`，engine-skia jvmTest 6/6；修复前 2 条红）。**
① 命中窗口多字号扫描必须折行且首行不越版心（真机 88.4px / fs=16 时 32px 两档都复现）；
② 整段 + 缩进放得下时保持单行（不多折）；③ 无缩进路径逐值不变（不误伤）；
④ Latin × LEFT/JUSTIFY/CENTER 不溢出；⑤ 真书重路径全链路扫版心 1280~1640 × 两个字号，
用**绘制侧真实整形结果**（同配置同 `lineWidthPx`）断言「首行缩进 + 行宽 ≤ 版心」且不二次折行；
⑥ 重轻两路断行区间逐项相同。度量用 Skia 自己的 `lineMetrics[0].width`（行尾空白不计）而非
`maxIntrinsicWidth`（含行尾空格 advance，会虚报约一个空格宽的溢出）。

**教训（两处把我带沟里的探针错误，留档）。** ① 扫描上界必须远大于阈值：`nat+2` 恰好落在单行区内，
测出的「阈值 = nat」是扫描顶的假象；② 扫描起点必须向上取整：`nat.toInt()` 在自然宽为小数时落到
阈值下方，扫到的全是已折行的版心，单行失效区被整段跳过。两者都让回归在修复前后都绿。

**顺带排除一条伪 bug：开书「被调两次」是日志误读。** 10 次开书会话里 `onCreate` / `open ok` /
`open: chapters=` 各 10 次（1:1:1），`viewport changed` 0 次——`openBookEngine` 每次只调一次。那两行是同一次
开书的两个阶段（Activity 建容器 :271 → `push("open")` 触发 `controller.open()` :418），因
`ReaderActivity.kt:250` 把自己的 `TAG` 传进控制器导致两行同 tag 同前缀；`chapters=0` 则是
`chapterCount = book?.spine?.size ?: 0` 在 `book` 赋值前记的，**架构上恒为 0**。详见 TODO 对应条目。

**Pending.** 无代码待办。下一个排队 bug：目录跳转点击无响应。

## 2026-09-30 — 图盒比例修复：persist 加载路径补 bindChapterFor（LAYOUT_VERSION 29），真机三态闭环

**Issue.** 重开《摄影的艺术》ch44 persist 加载路径不调 `readChapter`（chapterHref 空），
`NormalFlowLayout.intrinsicSizeOf` 跳过图片二进制探测——传统/现代主题下 pos=100% 标签探不到内在比时
图盒被打成 `width/2` 高（795），真实复现：重开位置漂移（图页只显示一半图）、ch44 图盒比例错误。

**Fixes（排版层-上）。** `BookDocumentController` 15 处入口补 `bindChapterFor(unit)`（helper+绑点，
persist 与 read 双路径对照 :600-:689）；`PaginationCacheCodec.LAYOUT_VERSION 28→29` 保证旧表作废自愈。

**机制证明（引擎级回归，engine-skia jvmTest 6/6）。** 新增
`PhotoPersistFullWidthRegressionTest.kt` 钉死三态：传统+href 绑定=1590×1920（=历史正确）；
传统+空白 href=1590×795（=历史 bug 精确值）；原书设置=915×1105（设计行为，非 bug）。

**真机实证（平板落盘日志，口径见 `docs/调试日志与分页跟踪.md`）。** 原书设置（bug 基线）
PGAP `firstLineH=1105`；切传统模式触发 `whole-book relayout` paramHash 838300637→186714974；
删表→冷启动→persist 重排 ch44（bug 原始路径）PGAP `firstLineH=1920` ✓；再 force-stop 冷启动
persist 加载 ch44 仍 `1920` ✓（DISK-HIT 命中磁盘表）。1920 = 内容宽 1590 × 内在比 1.2077（915×1105）。

**Pending / to verify on device.** 无（真机闭环）。分支 `fix/photo-relayout-height` 待合 main。

## 2026-09-30 — release v0.2.1 (versionCode 19)

Merged `fix/scheduling` (27 commits) into `main` and shipped as **v0.2.1**: pagination persistence
(P0–P3), scheduling fixes (open 落位章同步独占), media 门收窄, and the R26–R29 open-book performance
work below. Version bumped `0.2.0 → 0.2.1` (android `versionCode 18 → 19`, `archivesName`
`orilumn-0.2.1`, desktopApp `packageVersion` synced); annotated tag `v0.2.1` pushed.

## 2026-09-29/30 — 开书性能实证整改（R26–R29）：totalChars 恒等式、两条全章扫描、暖机净亏关闭、cEdge −25%

**Scope.** GIMP 开书慢（旧包中位 15.1s，曾误报 21.4s——那是跨会话基线，作废）的实证整改收口。
四条定论全部来自平板落盘日志（口径见 `docs/调试日志与分页跟踪.md`），不靠读代码猜——此前累计
五次"读代码看出的浪费"（级联深度 / 通用字体兜底 / 重复查表 / 预编译 / 手工扫描）全被实测否证。
完整实证与原始表格：`docs/待分析-GIMP开书慢-结论清单.md` §3–§3b.5 / §4.1。

- **`totalChars` 计算属性（开书 `pg` 1252 → 4ms）** —— 根因：`totalChars` 写成计算属性，
  每次访问整章重算（真书 558 章量级 ≈ 9.7s）。改 `by lazy` 后开书总时长 2.70s → **1.07s**，
  锚页 `pg` 4–5ms，`DISK-HIT` 排版段 2161 → 505–550ms。
- **`block(i)` 的 `textLength` 白算一次 advance（227ms）** —— 每物化一块重跑
  `styledCharAdvance`（与 `totalChars` 同一个函数）。改走 `globalCharStarts` 差分：精确整数前缀和，
  与原口径逐值相同（`BlockTextLengthIdentityTest` 6 例锁死）。净省 78ms——省下的是非级联那部分
  文本测量（级联缓存热度转移 `sStyles` 3 → 140ms 是记账搬家，不是新增开销）。
- **`computeFloatLeads` 开头那次全章扫描（275ms）** —— `floatLeads` 的 `by lazy` 为回答
  "这章有没有 float" 遍历全章每个叶子的级联（Rust ch7 = 169 叶）。改在 `computeStructure` 那一趟
  顺手产 `anyFloat` 标志（口径与 `blockStyleFor` 逐值一致，含 `#text` 叶取父级），随叶表持久化
  （codec `VERSION` 2→3、`STRUCTURE_VERSION` 1→2，旧 bin 解码失败自动重算自愈）。
- **锚页 `shape` 584 → 260ms** —— 上面两条合计：`DISK-HIT shape` 775 → 431ms，
  `sBlk` 522 → 22ms；`openT` 1297 → 964ms。锚页真排版仍只占 `sStyles+sSkia ≈ 240ms`，
  其余在 `asm shape` 窗口外（prepareLight/结构缓存/表载入 ~170ms）未拆。
- **开书暖机 A/B 定论：净亏，已关** —— 曾断言 350ms 暖机是一次性固定成本；两条反证推倒：
  同进程内 ch4 vs ch0 暖机差 11 倍（346~419ms vs 30~37ms，R22–R25 逐次复现）、固定成本模型解出
  负单价（`F+3p=350`、`F+13p=304` → `p=−4.6ms/块`）。A/B（同锚页 3×3 交叉）：预热把锚页
  Skia 断行 85 → 45ms，但 3 块代价 367ms > 锚页 5 块本身 262ms，端到端 **+50ms**。
  `OPEN_WARMUP_BLOCKS` 置 **0**；日后只试更便宜的组合（1 块/换块），别直接开 3。
- **R28 · 测量基建（单装机运行期 A/B）** —— `AbSwitch` 从 engine-skia 移到 common（包名不变；
  `StyleComputer` 在 common，engine-skia 单向依赖 common，反向引用不成立）；`expected_describe()`
  归一化；`tools/ab_probe.py` / `ab_detail.py` / `ab_families.py`。修掉三个把数据带偏的坑：
  asm 配对错位、DISK-HIT 后台预排全 0 行混入、`warm=` 缺席即 0。
- **R29 · `cBuild` 三层拆分，`eCol`+`eSty` 命中（`cEdge` −25%）** —— `cBuild` → `cEdge`/`cFont`/
  其余；`cEdge` → 边六家族（`eBox`/`eWid`/`eCol`/`eSty`/`eRad`/`eBrd`）。前两层三条假设全否证：
  重复查表仅 14 处（8 槽 16 次哈希现算砍不下）、`Pattern.compile` 现场编译预编译臂零差（34/34ms）、
  `Pattern.split` 换手工字符扫描反而慢 8ms（8/8 全分离，已删）。命中：`parseBorderColors`/
  `parseBorderStyles` 是唯二无条件 `listOf(4)` + 4 次 `"border-$side-color"` 拼新字符串查表的函数，
  且实测 `eBrd=59/52`——多数元素只声明一两边。改显式四槽 + 4 侧 × 3 类 key 提为 companion 预建常量。
  **R39 交叉 8+8 实测**：`eCol` 5 → 1ms、`eSty` 8 → 5ms、`cEdge` 37.5 → 28ms（−25%）、
  `cBuild` 57.5 → 51ms、`openT` 731 → 708ms（−3.1%）；固化后 4 跑复核 `cEdge` 24~28ms。
  `regexHoist` 开关保留（默认关 = 原样，供换书/换锚点再量）。
- **顺带暴露的既有缺口（非本轮引入）** —— 容器 `float` 在轻路径不生效（`<div style="float:left">`
  的祖先 float 未注册、叶表里没有它）；`LightFloatLeadTest` 以 `KNOWN GAP` 命名锁定现状（断言全 null），
  真修复（前向透传注册祖先 float）是行为变更，单独立项。`anyFloat` 标志与缺口无关（07f6666 回退对照证明）。

**Verification.** 811 例 JVM 测试全绿（clean 后重跑）；0 崩溃；页数/区间无漂移（与基线逐值相同）；
设备装 R40 复核 `cEdge` 24~28ms。GIMP 按 7.3ms/块推测 `totalChars` 一项即 ~9.7s → 开书应掉到 ~5s 附近，
未实测（换书是为了脱离病态样本，不是 GIMP 不该修）。

## 2026-09-29 — 分页持久化 P1–P3 与调度收口（磁盘表零文本 IO）

**Scope.** `docs/分页计算集中化方案.md` 的 P0–P3 在 `fix/scheduling` 全部落地：磁盘分页表、章结构
（树+sheets）与派生产物持久化到应用私有存储，重开书免 epub 文本 IO。真书 557/557 验收通过。

- **磁盘分页表缀构建号 + 开书清旧表** —— 升级即失效（旧 `LAYOUT_VERSION` 之外的又一道守卫）。
- **P1 整章文件持久化** —— `_sheets.bin`（全书去重）+ `<ch>.bin` 存后处理树 + sheet hash 表；
  open 免 epub 文本。导入 `BuildStats(558/558/0/0)`；打开 45 章 `loaded from persist`、
  `readChapter` 零次、翻页正常。半路修过真 bug：`flowChildren` 合成匿名叶游离（parent 挂了但不在
  children 里）致整书 extract 全挂，现按"树成员走路径、合成节点走属主+文本重建"双轨（`LeafRef`）。
- **P2 打开时小项** —— spine map book 级 memo（`spineIndexCache`）、`demandCache` 按 css 指纹 memo、
  `linkRangeCache` 章级按 `paramHash` 归位；`anchorCharStart` 的 fragment 搜索与所查表同生同死、
  hoist 即 unsound，保持 per-jump（P2 "不 memo 项的真实理由"已记）。
- **P3 重路径吃现货** —— `prepare` 复用已持久化的 `genStrings`（绑定 gen 字符串，只在 CSS 无变化时
  有效，`PersistedStructureRelinkTest` 锁）。
- **`shapeGeometry` 一次分段五家共享** —— 6 遍子树分段 → 1，span 大书结构性受益。
- **media 门收窄到真视口条件** —— 裸 `screen` 不再挡住持久化（GIMP 29/29）；`@media` 仍门住读写两侧。
- **调度** —— open 落位章同步独占（prewarm/B2 跳过，删双端死调用）；`pg` 三段计时 + double-open TODO。
- **P4 取消（2026-09-29 实测推翻前提）** —— 重 `prepare` 8s/书中 cascade 仅占 ~1s（1.8ms/章），
  `layoutBoxes` 内含 skia 逐块断行占 ~7s，塑形归位正确不可移；bake 只省 10%，复杂度不值。
  塑形成本转 P5（advance 共享）。轻路径 `floatLeads` 全章扫描残留的 `sStyles` 见上一条目。

**Verification.** `:engine-skia:jvmTest` 全绿；真机 557/557 页无漂移。`genPhase1` 双路等价
（heavy/light 同一来源）由 `PersistedStructureRelinkTest` 等锁定。

## 2026-09-15/19 — KMP+CMP migration (S01–S35) + C-series platform convergence: whole project closed

**Scope.** The full Kotlin Multiplatform migration (`docs/KMP迁移-分步计划.md` S01–S35, atomic
one-commit-per-step) landed 2026-09-15/16, then the C-series orchestration convergence
(`docs/C1-实施步骤.md`, C1-0…C1-3) closed on 2026-09-19. Per `docs/平台一致性整改方案.md` §7 the
mother doc's rows **A/B/D/E/Q1/C are all closed**: one style source, one shaper/breaker, one font
pool, one image decode, one pagination table/store, one orchestration host shared by tablet and
desktop. Both long-deferred TODO items (阅读定位 D / 三线程优先级 F) were consumed in the same pass.

- **Module shape (final)** — `:common` (KMP: androidTarget+jvm; engine/css, paging, layout,
  laying, html, epub, settings, font, text + io/log expect/actual), `:engine-skia`
  (SkParagraph shaping, `SkiaParagraphBreaker` + `BookEngineSwitch` dual-engine switch,
  `LineWindowDrawer`), `:shared-ui` (CMP shelf/reader/settings+TOC, S26–S29), `:desktopApp`
  (JVM shell hosting `App()`, S32), `:app` (slimmed to a thin host, S31).
- **JVM-only deps replaced** — jsoup→fleeksoft Ksoup (S10–S12), `javax.xml`→self-built mini XML
  DOM with XXE guard (S13–S16), `org.json`→kotlinx.serialization (S17–S18),
  `java.util.zip`→okio (S15), Room→SQLDelight v4 with shared KMP shelf db (S33/S34b),
  `WifiFontServer`→Ktor in `common/net` (S35), `util/FileLogger`→common `Logger` (S31-cleanup).
- **C1-0 dual-source unification** — incremental/temp shaping now runs on Skia single-source;
  the Android `StaticLayout` shaping primitive is retired (remaining mentions are historical
  comments only; geometry is plain data on `ParagraphShape`).
- **C1-1 pagination single-source** — table + codec + okio `FileSystem` store into `common`;
  same bytes/filename/`LAYOUT_VERSION` invalidation for both hosts; `PaginationCacheTest` 7 cases.
- **C1-2 orchestration into common** — `TempNavigation`/`FragmentAnchors` pure logic, window
  state machine, save-order contract, and the **F>A>B1>B2>P priority contract with injectable
  dispatchers** (`BookDocumentController` KDoc `:66`, `injectedCanonicalDispatcher`); desktop
  consumes the identical contract. TODO D: `onSaveProgress` finalizes the temp table before
  persisting (canonical char authority) + seam/handoff canaries; TODO F: contract written, no
  longer "missing".
- **C1-3 desktop same host** — `DesktopReaderHost` write-through on the shared `PaginationCacheStore`
  (same `LayoutParamKey`, hit reports disk page count), background canonical/whole-book prefill
  **not yet enabled** on desktop (single-chapter lazy load covers the open mode); enabling it must
  reuse the same dispatcher split.
- **Verification** — `:app:testDebugUnitTest` 122/122 + `assembleDebug`, `:common:jvmTest` 347,
  `:engine-skia:jvmTest`, `:shared-ui:jvmTest`, `:desktopApp:test` 4/4 all green.
- **Deferred / next** — phase **L (iOS)** not started (no `iosApp`; needs iosMain actuals for
  mDNS/paths/fonts); device re-verification of TODO D (save-char vs restore-char) still unrecorded.

## 2026-09-18 — Layout Priority & Concurrency P-series (P1–P14): all 14 landed + P9 corrective fix

**Scope.** The responsiveness/priority/concurrency roadmap
(`docs/layout-priority-and-responsiveness-improvements.md` P1–P14, design spec
`docs/layout-priority-and-concurrency-design.md`) completed in one day: priority contract F>A>B1>B2>P,
unified miss flow (U0–U9), bounded contiguous sliding window ±1 with discard-and-restart, unified
promotion-on-leave, and the 4–5 core concurrency layout. All items verified by probes T1–T8 and both
regression suites (`:common:jvmTest`, `:app:testDebugUnitTest`).

- **P1 birth-window atomization (R1)** — the anchor page is shaped into locals, then the commit
  (bindInProgress + fill window + setCurrentTempPage) is atomic under `tempStateLock`; flips `await`
  the birth signal and re-read state. Probe T1 `TempBirthWaitRegressionTest`.
- **P2 whole-book epoch (R4)** — `prepareRelayout` bumps `layoutEpoch`; B1 carries it and skips stale
  segments; the params-settled point re-dispatches B2 with the current hash. Probe T3.
- **P3 per-chapter canonical slots (R3)** — `canonicalJobs[chapter]`: same-chapter new params cancel,
  cross-chapter queue-not-kill, so a just-tuned chapter's table always lands. Probe T4.
- **P6 jump gate (jump responsiveness)** — single-slot `JumpGate`: cancel/dedup/epoch-guard + gold
  top-edge load line; a seek-toc-burst converges on exactly the last landing. Probe T2.
- **P7 timely abandon** — `fullLayout` gains a per-block `checkpoint` (foreground default no-op);
  B1/B2 pass `ctx.ensureActive()`, cancellations surface as `CancellationException`, never a truncated
  table; the large-chapter anchor shape is skipped when the epoch went stale. Probes T5 + P13's.
- **P11 unified promotion on leave** — all three jump paths + flip-leave finalize the old chapter's
  temp session (`finalizeOnLeave`), closing the "re-entry lands on the old anchor" residue. Probe T8.
- **P10 bounded sliding window** — temp pagination is a contiguous ±1 window with far-end eviction and
  `WinUnit.{Fwd,Bwd,Pair}` torn-pair atomicity; window-outside = discard-and-restart; exposed two latent
  bugs (`locateTempPosition` (blockStart,charStart) key; `stepTempPrefill` neighbor liveness/trim).
  Probe T7 `WindowSlideDiscardProbeTest`.
- **P14 cache lifecycle + LRU** — `PaginationCacheStore` trims each book's directory to 32
  least-recently-used tables; `paramHash` completeness verified (useOriginalStyle + userCssHash slot).
- **P12 scan ordering** — B2 follows reading direction × distance (`orderRemainingChapters`).
- **P4/P5 preflight** — neighbor chapters parse + light-prepare off the flip thread; open-book first
  screen becomes a promote. Probes `CrossChapterPreflightProbeTest`/`OpenBookPreflightProbeTest`.
- **P13 chunked canonical (U6k)** — `fullLayoutChunked` slices shaping across a low-priority chunk pool,
  equal to sequential canonical by construction. Probe T6 `ChunkedCanonicalEquivalenceProbeTest`.
- **P8 head lift (R2)** — near-head anchors (≤ `HEAD_START_BLOCK_LIMIT`=100) re-anchor at the true
  chapter head so temp and canonical share the same head source. Probe `HeadLiftProbeTest`.
- **P9 R5 corrective fix (landed same day)** — temp sessions now shape from a
  **frozen** `ip.profileSnapshot` captured at `startAnchorStream`, never the live controller
  `profile`; a flip racing a typography tune can no longer flash one mixed-param frame. (Documented in
  the plan as "documented, fix deferred"; implemented once Phases 1/2/3 were live.)

**Full table below** (2026-09-14) covers the list-marker `liOf` generalization; the detailed per-item
logs live in `docs/incremental-vs-full-layout-investigation.md` ("Implementation Log").

## 2026-09-14 — List markers attach to `<li><p>…</p></li>` (first leaf per item only)

**Issue.** Books that wrap every list-item body in a `<p>` (Manning/calibre exports — the Kotlin in
Action book here is the concrete case: 405 of 476 `<li>` are `<li><p class="list">…</p></li>`)
rendered with no bullets and no hanging indent: only per-chapter summary lists, whose `<li>` holds
direct text, looked right. The book CSS is valid; a browser puts the marker on the `display:list-item`
`<li>` regardless of its children.

**Root cause.** `ListMarkers.liOf` only attributed two leaf shapes as marker carriers — a `li` text
leaf, or a nested `<li>`'s leading anonymous `#text` leaf. In `<li><p>…</p></li>` the layout leaf is
the `<p>` (a block child), so `liOf(p)` returned null, `listMarkerFor` yielded null, and
`ParagraphShape.drawListMarker` was a no-op: no bullet, no hanging gutter, just the raw `ul` padding.

**Fix.**
- **`liOf` generalizes** to the nearest `li` on the leaf's ancestor chain (itself included), so a
  `<p>` (or any block child) inside a `<li>` now belongs to that item.
- **New `ListMarkers.firstCarrierSet`**: for each `<li>` in a document-ordered leaf list, exactly the
  **first** leaf carries the marker. `<li><p>a</p><p>b</p></li>` draws one bullet (on `a`); the second
  `<p>` renders as a plain paragraph, matching CSS where the bullet belongs to the item box, not to
  each nested block. Nested `<ul>` leaves claim their own inner items, so inner lists keep their
  bullets.
- **Both paths gate on the carrier set**: heavy (`fullLayout` → `firstCarrierLeaves(prepare.leaves)`)
  and light (`LightPrepare.firstCarrierLeaves`, lazy over `markupLeaves`) pass it into `listMarkerFor`,
  which now returns null for non-carrier leaves. This also fixes a latent mis-attribution where stray
  trailing inline text after a `<p>` in an item would have claimed the bullet.

**Tests / build.** `ListMarkersTest` +3 cases (`liOf` ancestor walk, first-leaf-per-li gating,
nested-list carriers); full `:app:testDebugUnitTest` green; `compileDebugKotlin` green. Marker
placement on device pending (uikit).

## 2026-09-13 — Shaper: drop the trailing phantom blank line in pre/code blocks

**Issue.** The tablet's Rust book (`book_47`) ships a *different* stylesheet than the repo's
`rust_cn.epub`: `pre{margin:0.5rem 0; padding:0.5rem}` (a plain **leaf** — no `code{display:block}`),
`.filename{display:block; padding-top…; background}`, and `.filename + pre{margin-top:0}`. Because `pre`
is a leaf there, the container-margin fix (above) does not touch it — yet every code block rendered with a
large empty background **below** the last line ("大片空白"), confirmed by pixel measurement (~one line-height
≈ 53–67px) and by an on-device `PREGEOM` probe. Root cause: every `<pre><code>…\n</code></pre>` ends in a
trailing `\n`, and the *draw/light* shaper (`ParagraphShapes` → `StaticLayout`) emitted it as a final
**empty line** (`lineStart == lineEnd`, full line-height). The heavy measure shaper
(`StaticLayoutBreaker`) already skips such lines, so drawing/backgrounds were a line-height taller than
geometry.

**Fix.** `ParagraphShape` now trims lines **after the last non-empty one** (`trimmedLineCount`). Kept lines
are always a contiguous prefix `0..last`, so `getLineTop/Start/End(k)` still index the same StaticLayout —
no char loss; blank lines *between* code are preserved. Bumps `LAYOUT_VERSION` (10→11) so stale pagination
tables re-flow with the corrected geometry. Compiles; geometry suites green. On-device pixel confirmation
was partially blocked by a pre-existing `ReaderActivity.onContainerTouch` crash under programmatic input,
so the tablet should be re-checked by hand.

## 2026-09-13 — Box model: container margins + border/padding now follow CSS in vertical flow

**Issue.** In the rust book ch1, `blockquote`/`.note` boxes and `pre` code blocks carried a visible
`background-color`. Two stacked defects made every non-text block's vertical layout deviate from CSS:
(1) `pre > code { display:block }` turns `pre` into a **container** box, and the container branch of
`NormalFlowLayout.emit` never consumed its **own margin** (an "entered top-aligned" shortcut), so blocks
sat flush against the paragraph above; (2) containers also never advanced the cursor by their **own top
border+padding** (nor past their bottom border+padding), so a container's background box was derived as
`firstChildTop − edgesTop`, extending *above* the preceding block's content bottom and painting over the
text above, while a following sibling was pulled up into the container's padded bottom. Together they
read as "negative margin-top / overlap" plus a "large bottom blank" and generally scrambled margins.
Non-container leaves already consumed their own edges, so plain `p`/labels were unaffected — only
containers (`.note` with `<p>`, `pre` with a `display:block` `code`) showed it.

**Fix (single-source, both layout paths — CSS box model).**
- `NormalFlowLayout.emit` container branch now mirrors a leaf: it consumes its **own collapsed top margin**
  (`collapse(margin.top, prevBottomMargin)`), advances the cursor by `border.top + padding.top`, lays out
  children, then extends the cursor past `border.bottom + padding.bottom` and returns its **own bottom
  margin** for the next sibling to collapse with. Margins, borders and padding all now participate in the
  flow exactly as CSS specifies.
- `NormalFlowLayout.consecutiveLeafAdvance` (the light/incremental gap) reproduces that full gap: the LCA
  sibling margin collapse + top edges/margins of every container on the next-leaf path + bottom edges of
  every container on the prev-leaf path, so light spacing mirrors heavy byte-for-byte (held by
  `BoxSharedGeometryTest`).

**Tests / build.** Added `preContainerPaddingDoesNotOverlapPrecedingText` and
`followingSiblingDoesNotInvadeContainerBottomPadding`; the old `nextInsideContainerDropsPrecedingMargin`
was corrected to `nextInsideContainerCollapsesPrecedingMargin` (CSS collapse → 40). Geometry suites
(BoxShared/BoxLayoutMargin/BoxLayoutFlow/BoxNested) green; `:app:assembleDebug` + `installDebug` to tablet.

**Verified on device.** Rust book ch1: `pre` blocks and `.note` boxes now render with a clean CSS margin
gap to the text above, **no overlap**, and symmetric healthy padding (residual, minor bottom-heavy inside
code blocks traces to a trailing `\n` in the EPUB's `<code>` creating one extra line — a known, separate
minor quirk, not the reported bug).

> Heads-up: the 10 pre-existing test failures on `HEAD` (27522a3) were stale expectations after the
> color parser switched to `#AARRGGBB`; the engine was already correct. Updated CascadeTest /
> BoxNestedLayoutTest color assertions to `#AARRGGBB`; they now pass. The remaining
> `BoxPathConsistencyTest.img is a replaceable block` asserts img-as-block, but the engine deliberately
> treats `<img>` as default-inline — a separate stale expectation, deferred.

## 2026-09-13 — Incremental/light path: cache typography-independent structure (prepareLight ~900ms → ~3ms)

**Issue.** Tuning a typography slider (line spacing / font size / letter spacing) forced the whole *light*
prepare to re-run every time: device profile of `prepareLight` on the rust book ch4 showed
`enumerate=755ms + charStarts=131ms + styleEngine(CSS parse)=28ms ≈ 914ms`, and during a slider drag
`prepareLight` re-fires many times/sec. All of it is typography-invariant (depends only on DOM + CSS +
`display` classification), so it was pure re-computation.

**Fix (two layers).**
- **Block structure cache** (`ChapterStructureCache` on `ChapterUnit`): memoizes the light-path leaf set
  (`enumerateBlockLeaves`) + `globalCharStarts`, keyed by `cssBundle` + `useOriginalStyle`. Survival
  of `invalidateLayout` (which fires on every param change) is deliberate — the key self-validates only
  against structural inputs.
- **Parsed CSS cache**: the parsed author stylesheets (`LightCssParser` output) are stored on the same
  cache; `styleComputerFor` accepts pre-parsed sheets and the light path skips re-tokenizing CSS. The heavy
  `prepare()` path is unchanged (still parses when none are supplied).

**Result (measured on device).** `prepareLight` cache-hit: original ~914ms → Phase-1 (~40ms, leaves/charStarts
cached) → Phase-2 (`3–12ms`, parsed CSS reused). Rendering verified with no regression.

**Design doc.** `docs/incremental-layout-plan.md` §1–§5 (micro-profile + knob-invalidation table + two-layer
cache). Commits `0d75b0c` (BlockSkeleton) `9b5847a` (parsed CSS).

**Not yet done.** The interim *correctness* fix (blockquote/container backgrounds lost in the incremental
path because `PartialDrawableLayout` gets leaf boxes only) is documented in the plan §4.3 as pending a
decision; the two `drawPageSlice` implementations remain duplicated.

## 2026-09-12 — Browser-consistent list hanging: text at contentLeft, marker hangs left

`list-style-position: outside` now matches browsers: the item text starts exactly at `contentLeft`
(= the `ul/ol` `padding-left`), and the marker hangs in the gutter to its left (drawn at a negative
frame x) instead of pushing the text by marker-width. So `ul,ol{padding-left:2em}` yields text
indented 2em (≈2 CJK chars), nested `ul/ol` each add their padding via `contentLeft` accumulation
(2em per level). `inside` keeps the marker inline (first line indented, wrapped lines return to
contentLeft). Both draw paths widen their leaf clip (`listMarkerClipLeft`) so the hanging marker is
not clipped. Emphasizes the engine principle: box model + cascade mirror a normal browser; EPUB-only
defaults belong in an upper layer.

**Tests / build.** Pure-JVM suites green; `:app:installDebug` to tablet.

**Pending to verify on device.** `ul{padding-left:2em}` → text at 2em, marker hanging left; nested
lists indent 2em per level.

## 2026-09-12 — Box model: consume block-level horizontal offsets; list markers honored

**Issue.** `contentLeft` was never accumulated from block horizontal edges (padding/margin/border-left)
and text was drawn at x=0, so book CSS like `ul,ol{padding-left:2rem}` had no effect and list markers
sat tight against the text.

**Fix.**
- **Horizontal accumulation (single-source).** New `NormalFlowLayout.descendContentLeft` mirrors
  `descendContentWidth`: each box's border-box left = parent's + ancestor left border/padding + own
  left margin. `buildBoxTree` threads `childLeft` top-down; light `LightPrepare.block(i)` uses the same
  descent; `tableRowLayoutFor` bakes the row left into cell x. Only `contentLeft`/draw-x change —
  `contentWidth`, line breaking and `globalCharStarts` are untouched (char authority preserved).
- **Render at contentLeft.** Both `BoxDrawableLayout.drawLeaf` and `PartialDrawableLayout.drawLeaf`
  (incl. replaceable/img) x-translate by `contentLeft + own left edges`; page reading margin applied
  outside.
- **List refinements.** Sizeable vector-drawn bullets (disc/circle/square) instead of font glyphs
  (fixes "bullet looks like a period"); marker→text gutter = marker width + gap (0.6em) with marker at
  the item content start; li-level `list-style-type`/`list-style-position` override; `ol start/reversed`
  attrs preserved. Nested indentation is now fully CSS-driven via contentLeft.

**Tests / build.** Pure-JVM `BoxLayoutMarginTest`/`BoxNestedLayoutTest` assert `contentLeft` accumulates
(ul padding → li at that x; plain p stays 0) and existing suites stay green; `:app:installDebug` to tablet.

**Pending to verify on device.** Confirm `ul{padding-left}` now shifts whole lists right, markers gap
from text, nested lists indent per level, and table/images honor padding.

## 2026-09-12 — List markers: EPUB-standard ul/ol rendering

**Issue.** `ul`/`ol`/`li` were laid out as plain blocks with no markers, so lists rendered as
indistinguishable body paragraphs. Needs: bullets, ordered numbering, `start`/`reversed`, `inside`/
`outside`, per-level nesting reset, and list items must NOT receive the body first-line indent.

**Design (marker as overlay, char stream untouched).** Rather than prefixing marker text into the
character stream (which would disturb heavy/light `globalCharStarts`), markers are a visual overlay
drawn per `ParagraphShape` (the engine's `BulletSpan`): the `<li>` text reserves a gutter via a
`LeadingMarginSpan.Standard(first, rest)`, and the marker glyph is drawn in that gutter at the first
line's baseline. Char accounting (`visibleCharAdvance` / `textLength` / `globalCharStarts`) is
untouched, so pagination/selection/progress stay consistent.

- **`ListMarkers` (pure JVM)**: node attribution (`liOf`: `<li>` leaf, or a nested `<li>`'s leading
  anonymous `#text`), `list-style-type` → disc/circle/square + decimal/decimal-leading-zero/
  alpha/roman (default disc/decimal; `none` → no marker), `list-style-position` inside/outside,
  `start`/`reversed`, per-level `level`, bijective base-26 alpha, subtractive roman.
- **Two paths share** `shapeLeaf(listMarkerFor(...))`: heavy reads the whole-chapter style map, light
  the lazy resolver, so both see the same `ul/ol` list-style. Marker drawn in both `BoxDrawableLayout`
  and `PartialDrawableLayout`.
- **`HtmlTreeConverter`** now keeps `ol` `start`/`reversed`/`type` (boolean `reversed` via `hasAttr`).
- **`uiSheetFromProfile`**: first-line indent restricted to `p`; `li` keeps 段间距 but no text-indent.
- `ComputedStyle`/`StyleComputer` parse `list-style-type`/`list-style-position`.

**Tests / build.** New pure-JVM `ListMarkersTest` (attribution, numbering, formats, defaults) green
(Robolectric still unavailable in this env); `:app:compileDebugKotlin` green; `installDebug` OK.

**Pending to verify on device.** Bullet/ordered rendering, `start`/`reversed`, `inside`/`outside`,
nested indentation and renumbering, `list-style-type:none` (no marker/indent), and that `li` is no
longer first-line-indented.

## 2026-09-12 — Incremental / temporary pagination: line-based fill + stable window

**Issue.** Both the disk-incremental and anchor temporary pagination exposed two defects: (1) the page-break points were unstable while flipping (the same page's content changed between flips); and (2) with "whole block never torn" packing, a code block that did not fit was moved whole to the next page, leaving a large blank at the bottom of the page / a clipped bottom half-line.

**Fixes (S5 temp-table invariant relaxed: no longer "whole blocks never torn").**
- **Disk-incremental self-consistency**: `incrementalLayoutForPage` now tiles pages by `contentH` from its own `localLines` geometry (pages are continuous, neither leaking nor overflowing), instead of hard-applying the disk table's char boundaries; `ensurePageRangeShaped` records the window so flipping inside the window does not re-layout (stable page-break points).
- **Temp-table line fill**: `shapeTempPageForward` / `shapeAnchorPageForward` changed from whole-block to accumulating real per-line heights up to `contentH`, and may break lines inside a code block; added `ForwardedPage` and the line-level continuation cursor `forwardFromLine`. Only replaceable blocks (img / table rows) are kept whole.
- Keeping no mid-chapter disk-table switch (only at the chapter head / on leave) is preserved.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending to verify.** Forward whole-block tearing is resolved; backward is still whole-block, to be completed after forward is confirmed.

## 2026-09-12 — CSS `display:none` support (engine-level hide)

**Issue.** The engine only handled `display:block`; `display:none` was ignored, so `display:none` elements (e.g. a book's `<span class="boring">` "hide the boring code" spans) were still laid out and rendered, producing spurious blank lines / tall pages.

**Fix (single-source cascade, both layout paths).** `ComputedStyle` gains `displayNone`; `StyleComputer` sets it from `display:none` and adds `resolveHidden` (light path, mirrors heavy's `styleMap.displayNone`). `NormalFlowLayout` skips hidden subtrees in box building / text absorption / inline aggregation / leaf enumeration; `ParagraphShapes.emitText` skips them when rendering; the light path derives char starts via the shared `visibleCharAdvance` so heavy and light exclude display:none identically.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending / to verify on device.** Book `.boring` spans hidden; pages fill normally; no pagination drift between heavy and light.

## 2026-09-11 — Anonymous text leaf style fix at the engine layer (root cause)

**Issue.** In a heading like `<h1><span class="sec-num">Chapter 9 </span>Error Handling</h1>` the anonymous `#text` title (`Error Handling`) lost its block's **color and bold** — rendered black / thin — while the `sec-num` element leaf rendered correctly. Root cause was engine-level: an anonymous `#text` leaf is synthesized and has **no entry** in the styles map, so `emitText` never applied color/bold spans to it (element leaves have real style entries). This is the same mechanism failure behind both the color and the weight symptoms; earlier base-style alignment (`blockStyleFor`) only matched base styles, not the rendered inline spans.

**Fix (engine layer, shared by heavy+light).** In `ParagraphShapes.emitText`, an anonymous `#text` leaf falls back to the block's `rootStyle` (`styles[el] ?: rootStyle`), so it receives the same color/bold/spacing spans as any text run — correct HTML/CSS produces correct rendering, no paint-layer workaround (base-color / fake-bold hacks were tried and removed).

**Tests / build.** `:app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter big title now blue + bold; `sec-num` unchanged; no regression across body/heading/link styling.

## 2026-09-11 — Chapter-head page-flip dual render unification (head-flip double-render fix)

**Issue.** At a chapter's first page (e.g. after a TOC jump to a heading's `<h2><span class="sec-num">…</span> …</h2>`), a left page-flip showed two defects: (1) the page redrew **in place** instead of flipping / cross-chapter, and (2) the heading's weight/color changed because the heavy (`canonical`) and light (`temp`) renderings disagreed on the anonymous text leaf's style.

**Fixes.**
- **Single-source anonymous-leaf style (defect B).** The light path now resolves an anonymous `#text` leaf through its container block (`blockStyleFor`) and returns an empty inline-style map — matching the heavy path, which reuses the container style and holds no `styleMap` entry for synthesized leaves. Added a per-leaf `ComputedStyle` (fontSize/bold/color/textAlign/fontFamily/fontWeight) dual-path equality assertion to `BoxPathConsistencyTest`.
- **Chapter-head page-flip routing (defect A, S5).** `tempNav` no longer force-calls `bindCanonicalAndPage` to redraw the head page. At the chapter head, a backward flip is a boundary → the temp table is abandoned and the **disk (canonical) pagination is enabled on leave** (`finalizeTempOnLeave`). The mid-session canonical switch (`onBackgroundCanonicalReady`) was removed, so the current page is never re-rendered in place / can't flash while temp is live. Cross-chapter from the head now works on the first flip.

**Tests / build.** `BoxPathConsistencyTest` extended; `.gradlew :app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter-head left-flip now cross-chapters without in-place redraw or style jump; re-open after disk hit still lands on the same character; no regression across parameter relayout / cross-chapter jumps.

## 2026-09-11 — Dual-path single-sourcing (typesetting correctness, rectification A/B + 1.2)

**Issue.** The engine kept two layout paths — heavy `NormalFlowLayout` (canonical, writes the disk pagination table) and light `BoxChapterLayouter.LightPrepare` (lazy cascade, reads the table + whole-block temp pages). The same geometry was implemented twice in separate sessions; equivalences were not enough, and a divergence surfaced as a page's last line overflowing the content height (clipped bottom half).

**Fixes (single-source, light now calls heavy's shared functions).**
- **A · line-break width**: extracted `NormalFlowLayout.innerBreakWidth(style, borderBoxW)` and `descendContentWidth(el, rootW, edgesOf)`. `buildBoxTree` and light shaping both route through them; `LightPrepare.borderBoxWidth` deleted.
- **B · container margin collapsing**: added `NormalFlowLayout.consecutiveLeafAdvance(prev, next, styleOf)` that reproduces `emit()`'s tree-aware collapse (container bottom margins + the container/leaf-abutment rule). `rebuildLocalLines` now uses it instead of adjacent-leaf pairwise.
- **1.2 single-sourcing**: last-line height via `lineHeightPx` (shared by both rebuild paths); `globalCharStarts` via `NormalFlowLayout.accumulateCharStarts`; first-line top-align via `DrawableBookLayout.alignPageTop`.

**Tests.** Added `BoxSharedGeometryTest` locking the shared width/advance functions byte-for-byte to canonical emit geometry (nested containers, container margins, leaf→container abutting). Full `:app:testDebugUnitTest` is green.

**Pending / to verify on device.** Device regression (page last line no longer clipped; spacing matches pre-change; large-font / multi-margin chapters). The temporary `INCR-OVERFLOW` diagnostic log was already absent from the tree (nothing left to remove).