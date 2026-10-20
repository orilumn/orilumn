# 断点：E23 系列（指定高消费）交付完毕 + 欠账表结清

时间：Tue Oct 20 2026 ｜ 分支：`fix/engine-inline-box`（本地领先 `bd15534` 共 11 个提交，**未推**）

## 一、本轮已提交（相对 `bd15534`）

| 提交 | 内容 |
|---|---|
| `0706296` | 装饰线线宽线型对齐 Chromium、行内盒高按盒首字取面、行内装饰增量路径补齐 |
| `fda6159` | 桌面版启动参数直接开书、删书顺带清排版缓存 |
| `db4ddc1` | `::first-letter` 真正支持；装饰线初始粗细口径；悬挂缩进恢复生效 |
| `d20b56c` | 验收样书第二轮对 Chromium：删除线度量面、块级 `width`、行内 `relative`、零行盒 |
| `d84e6e8` | **E23**：块级 / 浮动盒指定高生效（浮动方块 30/40 与 Chromium 同值） |
| `2febe91` | 记档 E23-b 的三条前提（空浮动盒闸门 / 行装箱 / 轻路径浮动画盒宽待核） |
| `72ed16c` | CHANGELOG 残差条目补记并排的三条前提 |
| `016c448` | **E23-c**：同侧浮动行装箱（并排）——四个方块回到同一行、容器底色带 166 → 54 |
| `0269b85` | **E23-b**：容器指定高生效（重 / 轻两路同式） |
| `4da9058` | 撤回两条实测不成立的「容器指定高边角」，改记 `min/max-height` 未消费 |
| `c8d437c` | 文档结账：E15/E16/E17/E23-c 归入「已补」；`max-height` 裁定不做；待补表立「净减」规矩 |

## 二、E23 系列的最终口径（一句话各自）

- **叶 / 浮动盒 `height`**：内容盒高 = `max(在流内容高, height)`，**不裁不缩、游标不回退**（行位置单调，利于分页）。
- **同侧浮动并排**：空浮动盒也进浮动闸门；`FloatPending` 由单列升级为行装箱（同行放得下就 x 累加、同顶、行高取 max），一律按**外边盒**宽记账；落点单源给出，重 / 轻两路同值。
- **容器 `height`**：与叶同一条「只多不少」；轻路径行流是**局部位移**累加、拿不到内容高 ⇒ 另立**块边界差额前缀表**（内层先算、内容一到指定高即早退），开销以「填满指定高所需的那几叶」为界。绘制带 / 裁剪带 / 后随内容起点三处同吃。`§8.3.1`：指定高**打断**父子底边距合并（与底边门同一处判据）。
- **`min-height`（竖向下限）**：与 `height` 合流成 `max(height, min-height)`，叶 / 浮动盒 / 容器同一条 `specifiedContentHeight`。底折叠**分两档**（Chromium 实测）：`height` 恒断链；`min-height` 只在**下限 > 自然内容高**（真的撑高）时断链，结论算一次带在盒上（重 `LayoutBox.verticalFloorBound`、轻 `LightPrepare.floorBoundContainers`），`lastChildBottomMargin` / `consecutiveLeafAdvance(floorBinds=…)` 读同一份。`max-height` 不做（D 表）。
- **浮动盒内容盒宽（E24）**：`width` 算**内容区**，border-box = 内容宽 + 自身横向 `padding`+`border`；单源 `floatBorderBoxWidth` 供重路径 build、轻路径浮动 eager 表（宽 / 落点 / 行装箱容量）、画盒与环绕前导四处，断行宽再由 `innerBreakWidth` 反算回内容宽。
- **总开关**：`chapterHasBoxHeight`（样式表里有能出 px 的 `height` / `min-height` 声明，或树上 inline `style` 提到 height —— `min-height` 含 "height" 子串自然命中）。命中才扫祖先链，普通书零扫描零塑形；百分比高被 `parseBoxSize` 挡在门外（`img{height:100%}` 零成本）。顺带修掉 `hasBoxHeightDeclaration` 把「非 height 属性的任意声明」当命中的旧笔误（此前常书门恒真、白扫一遍）。

## 三、验证状态

- 新锁 `FloatBorderBoxWidthTest`（5 例）：重 / 轻盒宽与落点、画盒宽、断行宽、环绕前导、章首页切片一致。
- 新锁 `BoxFixedHeightEquivalenceTest`（8 例）：重 / 轻两路**逐值一致** + 绘制带高锁；`min-height` 四例（撑高 / 低于内容不压扁 / 下限生效吞末子下边距 / 下限没生效照常外塌）。**变异验证**：抽掉差额表 ⇒ 两条 parity 变红；抽掉 `min-height` ⇒ 撑高例变红；抽掉「下限生效断链」⇒ 落差例变红。
- 全量绿：`:common:jvmTest`、`:engine-skia:jvmTest`、`:desktopApp:test`、`:app:testDebugUnitTest`、`:app:assembleDebug --rerun-tasks`。
- 验收样书 V6 无头像素：`.ovh` 外环 **110×50**（Chromium 110×50，改前 110×38）；`.cf` 底色带 h=54（Chromium 54）；四个方块同一行；两个 `.fl` 边框环并集 **68×32**、两个 `.fli` **88×42** 与 Chromium 逐值相同（改前 64×30 / 84×40）。
- **装机 V1–V8：未做**（平板不在线，见六）。

## 四、欠账表现状（`docs/引擎欠账-对齐浏览器语义.md` §2.1 B 表）

**待补表已清零**：E23 系列（E23 / E23-b / E23-c / E23-e）与 E24 已做并入 A 表；E25 实测不成立、
`max-height` 裁定不做，两条都在 D 表（各带再评估条件）。

## 五、开工顺序（已全部做完）

1. ~~**`min-height`**~~ **已完成**：Chromium 实测（`--headless=new`）证明**下限真的撑高时**才断 §8.3.1 底折叠（`min-height:120` ⇒ 盒高 120、后随内容贴盒底；`min-height:10` 低于内容 ⇒ 盒高 20、下边距照常外塌 20），`height` 则恒断链。落地 = 竖向下限单源 + 叶 / 浮动 / 容器同口径 + 结论带在盒上（重 `verticalFloorBound` / 轻 `floorBoundContainers`，`consecutiveLeafAdvance` 三处调用点同传）。
2. ~~**E25**~~ **已量不成立**：六种浮动形状含跨页样例，重 / 轻两路行数、字符区间、相对 y、页切片逐值相同（正典行流由每叶形状重建，不进 `emit` 的零高槽）⇒ 移入 D 表记档。
3. ~~**E24**~~ **已完成**：`width` 按内容盒口径（+ 自身横边），重 / 轻两路 + 画盒同源；V6 边框环 68×32 / 88×42 与 Chromium 同值。
4. **下一步 = 装机 V1–V8**，整个「对齐浏览器语义」里程碑收口。

## 六、装机验收（唯一剩下的 0.4.7 待办）

平板（vivo PA2353，无线 adb）连接与取日志流程见 `docs/调试日志与分页跟踪.md`：先 mDNS 自查端口
（`dns-sd -L "adb-400D91019H00000-rg2R7j" _adb-tls-connect._tcp local`），再 `adb connect`；定论只看
`run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt`。要跑的东西：

- §3.1 像素 A/B：**两个输入必须当次构建**，用 `shasum -a256` 确认是不同的包（AGENTS 发版段第 5 条的老坑）。
- V1–V8 样例逐项（V4–V8 抽样即可），含隐藏占位、裁剪、`relative` 只挪落墨、clearfix 包住浮动、高度类。
- 未完成的**排版参数改变 ⇒ 分页表作废**链路（旋转 / 调参关面板）也在真机复看一眼。

## 七、复现现场（工具与路径）

- Chromium 基准：`/tmp/gt/acc3-v6.png`（+ `.html`），生成方式 = `--headless=new --screenshot`、设备缩放 1、隐藏滚动条；Chrome 在 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`。**若 `/tmp` 被清**：用 `books/acceptance-v1v8.epub`（`/tmp/accsrc/OEBPS/*` 是它的解包）重新生成。
- 我方 PNG：同视口（760×1000）走**同一条离屏全管线**。本轮用的是**临时离屏探针**（已删）；重做时照 `desktopApp/src/test/.../P5cOffscreenCorpusTest.kt` 的骨架（`DesktopShelfStore` + `addBook` + `DesktopReaderHost`，页边距 30/40）另写一个临时测试即可（**提交前删掉**）。
- 像素工具：`/tmp/px/Rect.java`、`InkProfile`、`ExactColor`、`ColorBox`、`Ascii` 等，`java Rect <png> <RRGGBB> [tol]`。
- 离屏语料（防回归）：`desktopApp` 的离屏真书用例，像素**逐字节不变**是最低门槛。

## 八、本轮立的规矩（防止尾巴再长）

1. **待补表只收「已经量出可见差异」的条目**——猜的、没核过的不写进表，当轮量掉。
2. **每轮交付必须净减**：做掉（移入已补），或裁定**不做**并移入「范围外」写清理由与再评估条件。**不允许「未动」跨轮存活。**
3. **别用读代码代替测量来记残留**（E23-d 两条「边角」实测不成立，已撤回）。
4. 交付后**立刻把账结掉**（归位 + 写验证方式），不留「做完了还挂在待补」的假尾巴——本轮查出四条这样的假账（E15/E16/E17/E23-c）。

## 九、明确不做（已裁定，别再当新发现）

- `max-height`（D 表）：浏览器是「裁切 + 溢出」，还要决定内容被收掉后游标往哪走，与 `height` 的「只多不少」不是一套机理；手头语料零命中 ⇒ **不排期**。
- `::first-letter` 的 `float:left` 首字下沉绕排、`<hr>` 的 inset 立体明暗、`position: absolute` 后代不被祖先 `overflow` 裁、`float` 作用在行内元素、E20 的三条残留：均已在欠账文档记档，**本轮不动**。
- 真实 RTL / 竖排：按用户裁定与竖排模式合并为**单个里程碑**，不在本轮。
