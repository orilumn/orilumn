# 未尽事宜 / Open Issues

- **项目更名 orilumn → orilumn（全局重命名，待立项）**：GitHub 已有 `orilumn` 用户、
  `orilumn.com` 域名已被注册，拟改项目名为 **orilumn**。同步波及面（改名清单）：
  - 包名：`orilumn.reader` → `orilumn.readern`——`app`（Android namespace/applicationId `orilumn.reader`）、
    `common`（namespace `orilumn.reader.common`；SQLDelight 包 `orilumn.reader.db` 见各 `.sq` 文件头
    `package` 与 `common/build.gradle.kts` 的 `packageName`）、`desktopApp`
    （mainClass `orilumn.reader.desktop.MainKt`、macOS bundleID `orilumn.reader`），以及
    shared-ui/engine-skia 各模块 Kotlin 包路径与测试包名；
  - 数据根目录：桌面 `~/.orilumn/`（`DesktopPaths`：`orilumn.db`、books/covers/progress/
    settings/fonts）与 Android `filesDir`——改名后是否迁老数据或保留旧目录兼容读取，首版可
    不搬数据只声明新名用途，并出「老库数据兼容」验证；
  - 新建 GitHub 同名组织与仓库并入（历史保留方式待定：新建仓库移植 or 转移）；
  - 立项后先出「改名验收清单」：全局 grep `orilumn` 归零 + 双端跑起 + 老数据兼容验证，再动手。

- **目录面板标题高亮定位**：目标是"仅高亮当前页内的标题、不在页内的不亮"（高亮下边框已去掉）。当前页内标题 id 集合逻辑已正确（有单测），但具体书籍上章内子标题仍常高亮不到——疑似 TOC 条目的 fragment 与正文标题元素 id 不一致，无法建立"目录项 ↔ 页内 char"的命中。**推迟到排版稳定后再处理**。

- **阅读定位不稳定（整改 D）**：**根因已在 C1-2 收编，契约落码，仅剩设备实测确认**。原症状："未翻页重开"会位移（保存当前页 locator.charStart，重开定位同 char 可能落到不同页），两个根因：(1) 大章临时表 vs 磁盘表 char 定位不一致；(2) 临时转正 `onBackgroundCanonicalReady` 与 `scheduleSave` 竞态存下旧临时页 char。
  - **已落地（2026-09-19，C1-2/C1-3）**：char 语义统一为 **canonical 磁盘表权威**——`onSaveProgress` 走存档顺序契约（先 `finalizeOnLeave` 临时表转正/作废，再读 displayed slice 落盘，见 [TabletReaderHost.kt:143](app/src/main/java/com/orilumn/ui/reader/TabletReaderHost.kt)）；`ensurePageRangeShaped` 的 seam 诊断转正为 canary（新窗口贴合旧窗口时共享边界必连续，[BookDocumentController.kt:494](app/src/main/java/com/orilumns/engine/BookDocumentController.kt)），temp→canonical 交接再加 handoff canary（`:1225`），回归会大声失败而非静默漂移。
  - **剩余待办**：设备实测脚本（记保存 char → 重开 restore char 及落页）**未见留痕**，需跑一次确认位移消失；顺带核对"章内翻页是否真走全量路线"（此前怀疑全量路线从未实行）。

- **增量分页的三条线程调度优先级（整改 F）**：**契约已落码（C1-2），不再是"缺契约"状态**。前台 temp shaping / 后台 `canonicalDispatcher` / 后台 `tempPrefillJob` 的 F>A>B1>B2>P 优先级契约已写入 [BookDocumentController.kt:66](app/src/main/java/com/orilumns/engine/BookDocumentController.kt) KDoc 并与桌面同契约（[DesktopReaderHost.kt:80](desktopApp/src/main/kotlin/com/orilumn/desktop/DesktopReaderHost.kt)）；dispatcher 可注入（`injectedCanonicalDispatcher`，默认私有单线程 executor，前台翻页永不与整章塑形争共享池）；canonical 阶段采样前台活动用既有 `ensureActive` checkpoint 让位。
  - **剩余待办**：桌面端后台 canonical/整书预排**暂未启用**（单章懒加载已覆盖桌面打开模式）——启用时须复用同一分发器划分，不另起炉灶。

- **表格 auto 列宽：算法与 intrinsic 输入均已与浏览器同解**：`TableGridModel.autoColumnLayout`
  的表用宽/分配规则已改成浏览器同解——三段式（`avail≥MAX` 不撑满、`avail≤MIN` 溢出、
  之间按 `(max−min)` 线性插值；CSS 2.2 §17.5.2.2 骨架，中间段规则由 Chrome 实测反推并单测固化），
  列 min/max 也已计单元格 `padding+border`（`cellPref`）。**输入层也已真测**：max-content 走
  断行器「不限宽塑形取最宽行」（`preferredWidth`）、min-content 走「最长不可断段」逐段真测取最大
  （`minContentSegments` 纯函数＋`minContentWidth`，Chrome `width: min-content` 68 串样本标定），
  经 `NormalFlowLayout.tableCellPref` 单源喂重/轻两路（含行内 face 段、`white-space: nowrap` 即
  min=max、空文本留边）。`internallinks` 表1･1 真书端到端（`InternallinksTableWidthTest`）：
  321/321/230/287/430 vs Chrome 306/313/253/282/434，各列 ±10% 内（余差来自系统字库不同，
  同输入同算法已锁定）。待办：单元格指定 `width`（§17.5.2.2 step 1）、列/colgroup `width`
  （step 2/4）尚未实现；`table-layout: fixed` 现为各列均分，而规范是按首行单元格定列宽
  （§17.5.2.1）。

- **表格单元格边框仍是合成 1px 单框**：`TableCellLines.emitCell` 无条件画 1px 实线框，**颜色已随
  CSS `border-color`**（未声明回退 `currentColor`，见 `borderArgbOf`），但仍未按 CSS
  `border-width`/`border-style` 门控，也表达不了四侧异宽/异色/虚线。浏览器在 `border-width:0`
  或 `border-style:none` 时完全不画——此差异待后续处理。

- OpenGL 翻页动画

- **系统字体中文名方案B（✅ 已在 JVM 端先行落地，`fix/font-panel-review`）**：中文名链现为
  「name 表直读（方案B）优先 → CoreText（方案A）补缺 → 族名本身回退」（公共字典已删——
  字体太多靠字典穷举不现实，是什么显示什么）。方案B 不依赖 CoreText/系统语言：
  `FontParser.familyNamesOf` 纯字节解析 OpenType name 表（按拉丁族名尾缀匹配变体语言——
  TC→zh-TW、HK→zh-HK、MO→zh-MO 优先，其余含 SC/无尾缀→zh-CN 优先；回退任意含 CJK 记录；
  **不筛简繁，name 表里是什么就显示什么**——日文假名照显示），桌面 `NameTableChineseNames`
  扫系统目录建「拉丁族名 → 中文族名」映射（RandomAccessFile 只读表目录 + name 表区间，不整读
  大 TTC，进程内缓存一次）。本机 326 族中 name 表命中 61 族，关键族全中（LXGW/Sarasa/
  Songti SC/Hiragino GB/思源黑宋/屏阅初夏明朝体；STHeiti 走 CoreText 兜底）。**将来断行引擎
  改 Rust 重写时**，同逻辑搬进引擎侧（原规划），JVM 端实现保持。切换前以 name 表直读 +
  CoreText 维持双链。

- 批注/修订模块，以及修订后的 epub 导出、批注导出

- **字体管理面板余项（F 系列收尾，2026-09-23 列表；除 6 外均已闭环）**：字重枚举 + 中文名
  方案A 已合入（`feat(F-F5)`），以下是目验/追问后发现的不算完结的事项：
  1. **霞鹜文楷仍显示英文名（已闭环，闭环方式变更）**：初版经字典补 LXGW/Sarasa/方正等
     开源中文族段（`feat/font-panel-remaining`）。**`fix/font-panel-review` 起字典已整删**
     （字体太多靠字典穷举不现实）——改由 name 表直读（方案B）覆盖：`NameTableChineseNames`
     现命中 61 族，LXGW 霞鹜文楷系（霞鹜文楷/等宽/GB/圆角）全由 name 表 zh-CN 直出；Sarasa
     更纱系、Fandol 方正系、Noto/Source Han 思源系同样由 name 表直出；真没中文记录的族
     （Hei/Kai 等 Apple 老式族）展示层回退族名本身（Hei→"Hei"），用户已确认该口径。
  2. **字体名与字重观感重叠**：预览行按真实字形渲染族名，粗/黑体系名称本身带字重，
     与副标题「语种 · 字重表」互相干扰，观感上「名」与「重」叠在一起。
     待办：评估预览改中性字重/统一字号的取舍（损失「所见即所得」语义），或调整
     名称行与字重行的排版/间距；定方案再动，不仓促改。
     ✅ **已办（`feat/font-panel-remaining`）**：保留所见即所得（真实字形度量已驱动行高，
     无框重叠）；名/重间隙按实际字形高度折算——`subtitleGapPx(nameH, 4dp, 10dp) =
     nameH*0.2 钳 [4,10]dp`，粗黑全方字形更高 → 气口更大，副标题不贴死名字底。
     单测 `FontPanelRowsTest.subtitleGapScalesWithGlyphHeightAndClamps`。
     ↳ **桌面目验追补（`fix/font-panel-review`，2026-09-23）**：一版按 `getRectsForRange
     (TIGHT)` 收紧间隙（0.25 钳 [6,12]dp）——但**它测不准**：326 族全量真栅格核出
     4 族墨迹真超出度量行高（Zapfino +32px、jpfont-nds +68px、SignPainter +3px、
     BM Hanna 11yrs Old +2px），且其中 2 族 skia rects 直接报等于行高（假阴性）——
     花体/手写/长尾字体墨迹远超字体度量，按度量行高画就会把字重副标题压穿。
     **修法改为栅格墨迹真值**：`FontPreviewText` 把要画的同一 paragraph 栅格到临时
     位图、逐行扫描（`inkBoxOfParagraph`，进程内按 族+展示名+字号 缓存），Canvas
     高度 = `max(度量行高, 真墨迹底)`——墨永不溢出名字行盒。新单测
     `FontInkMeasureTest`（已知溢墨族 ≥ 阈值 + 常规族不溢 + 确定性/缓存命中）。
     间隙仍 `nameH*0.25 钳 [6,12]dp` 由行模型驱动。
      ↳ **第二轮目验（2026-09-23）**：0.25/[6,12] 于高字形行（Zapfino 名行≈67dp → 收
      到 12dp）观感仍偏大 → 比率降 **0.15、钳 [6,8]dp**（常规行仍 6dp 兜底，高字形行
      只给到 8dp，名/重聚拢成一体；行盒 = 真墨迹，间距不缩放也不会叠墨）。
      ↳ **同展示名合族（2026-09-23）**：Source Han Sans/Serif VF+区域静态版（都落
      「思源黑体/宋体」，同字重面数并列时取 SC 先于 VF）此前各占一行、观感像字体重复
      → `buildFontRows` 改按展示名合并为一行，规范族 = 面数最多、并列取族名字典序小者，
      行首排规范族、槽位旧值（VF）仍能命中合并行。单测
      `FontPanelRowsTest.sourceHanVfMergesIntoRegionalStaticRow`。
      ↳ **Apple 老式族 Hei/Kai 修正（2026-09-23，用户 taxonomy 复核）**：Hei/Kai 是
      Apple 可下载的 Classic Mac 遗留字体（90 年代 Ikarus 简体字形，字体册默认需下载），
      **≠ Heiti SC（黑体-简）/ Kaiti SC（楷体-简）**，也非 Windows 拷贝——两套不同字面
      不能靠展示名合族。字典原区分名：Hei→苹果老黑体、Kai→苹果老楷体（**`fix/font-panel-review`
      起字典已删**，Hei/Kai 的 name 表仅存 Apple 远古 'Hei'/'Kai' 记录、无中文名 → 展示回退
      族名本身 "Hei"/"Kai"，用户已接受此口径）；同时明确中易
      （北京中易中标 ZhongYi）四兄弟：SimSun/NSimSun→新宋体、SimHei→黑体、SimKai/KaiTi→
      楷体、SimFang/FangSong→仿宋（Sim=简体，Sun=宋/Hei=黑/Kai=楷/Fang=仿）。
      单测 `FontPanelRowsTest.{appleLegacyHeiStaysDistinctFromHeitiSc,
      appleLegacyKaiStaysDistinctFromKaitiSc}` + `FontLibraryTest` 对应断言。
  3. **名称/字重溢出折行**：长族名（更纱终端书呆黑体-简、凌慧体-简）或多字重表
     （PingFang 6 项）会顶出/裁切在行末。待办：字体名行先不动；至少让字重行可折行
     （B0 层折行 + 行高），名称行另行评估。
     ✅ **已办（`feat/font-panel-remaining`）**：字重副标题去 `maxLines=1`（B0 层折行，
     `lineHeight=16sp` 与 12sp 字号匹配），行高 `IntrinsicSize.Min` 随折行撑开；名称行
     维持单行（名称行折行/裁切仍留待评估）。
  4. **安卓「跟随原书」放回列表首行**：`AndroidReaderSettingsPanel` 现把「跟随原书」做
     成独立选项行，与桌面版面不一致。待办：作为字体列表首行候选（选中态首行），
     数据源与桌面共用同一行形态。
     ✅ **已办（核实为过时条目）**：F4c（`8f1fe1a`）已删旧 `FontManagerPanel` 并切共享
     `FontLibraryPanel`；`buildFontRows` 两端同源，「跟随原书」本就是列表首行（选中态
     首行），与桌面同一行形态。无代码改动。
  5. **桌面 PgDn/PgUp 翻页焦点不跟随（bug）**：字体列表翻页后焦点行原地不动，箭头
     导航仍从旧焦点行起步，与翻页所见错位。待办：翻页时同步重算焦点行（页首可见行/
     保持相对偏移），与 ScrollState 保持一致。
     ✅ **已办（`feat/font-panel-remaining`）**：滚动跟随——`snapshotFlow{isScrollInProgress}`
     只在滚动静止后评估：高亮（activeIdx）行已被滚出视口即重算到首个可见可焦点行
     （FollowOriginal/Import/Entry，跳过分区头），箭头导航与所见一致；键盘
     `ensureListVisible` 自滚落点行恒可见，不误改；安卓无键盘 activeIdx 恒 null，不动作。
  6. **「既然可查中文名，DB 还有何用」（记录）**：DB 仍是唯一权源——(a) 隐藏态/
     字重行/导入行是多端持久状态，本地化名只是 `displayName` 列的一层缓存；
     (b) 渲染期不逐次走 JNA 桥（CoreText 只在同步校验时刻枚举喂 `syncSystemFaces` 一次，
     面板查询全在内存）；(c) 平板/英文系统无 CoreText，展示层无本地化名回退族名本身，
     两端口径一致；(d) 取字形/槽位/逻辑键永远用 ASCII 族名，「展示名 vs 逻辑键」分离正是靠
     DB 列承载。方案B 已落地（JVM 端 name 表直读，见上），由链路上游填 `displayName`，
     DB 角色不变。
   7. **入口行仍显示英文原族名（2026-09-23 桌面目验，`fix/font-panel-review` 已办）**：
      文字页正文/标题/代码三行直接显示槽位原族名。`TextPage` 加 `fontDisplayByFamily`
      （`fontEntries` 预建族→展示名表，缺席回退族名本身 + CSS 通用族标签）。
      安卓入口（`AndroidReaderSettingsPanel.TextPage`）同症，但外层面无 entries
      （列表由 TextFont 子页自持），提升状态机改动面大，留待安卓对齐时一并做。
   8. **管理行右侧贴边（2026-09-23 桌面目验，`fix/font-panel-review` 已办）**：
      `FontManageRow` 内容区只有左 16dp，右侧用满贴窗边。内容区改
      `horizontal=16dp`，选中金点去尾 16dp（行内缩已给）。

- 根据用户选择内容，弹出选择器列表供选择，并允许用户指定css样式和属性

- 完善传统和现代样式主题，并允许用户使用自定义样式主题

- **标准化遗留 L1（待办：parsed-only 补消费）**：`visibility/overflow/position:relative/direction/unicode-bidi/font-stretch` 目前只落 `ComputedStyle` 计算值（`ComputedStyle.kt` / `StyleComputer.kt`），laying/draw 零消费。后续补祖先裁剪、相对偏移、RTL 流、字形压缩消费，双路一致 + bump `LAYOUT_VERSION`。
- **标准化遗留 L2（待办：§0 例外收口）**：`flex/grid` 真实布局（现按 `block` 降级）、`list-style-image/@page/vertical writing/cursor`、脚本/表单/音视频/`canvas/iframe/object-embed`、固定版式（pre-paginated）。后续收口时先修范围定义与验收矩阵，按需逐项立项。
- **标准化遗留 L3（待办：近似实现转精确）**：`background-size` 恒 1:1、`background-image` 只取首层、`counter-set`/非 `li` 的 `list-item` 生成内容不做、窄列悬浮退块式、latin 注音偏宽容差。后续按需逐项闭环。