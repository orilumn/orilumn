# 未尽事宜 / Open Issues

- **`text-align: justify` 有实现但**静默失效**（2026-09-30 记，R1 排版时核出来的，独立于该 bug 已修）**：
  绘制侧 `LineWindowDrawer.paintText:198` 有 `appendTrailingNewline = (alignment == JUSTIFY)`，
  本意是行尾追加 `'\n'`、把本行伪造成「双行段的首行」以获得铺满间距。**但 Skia 的 `kJustify`
  只对软断行产生的行拉伸**，追加硬 `'\n'` 后本行成了硬换行结尾的行 → 永远不被拉伸。
  实测（真机字体栈 @44.4px）：中部行（自然宽 1509.60 / 版心 1600 与 1560）追加 `'\n'` 前后
  首行宽**逐值相同 1509.60**，均不拉伸；只有「在绘制时自己又折了一次行」的才被拉伸（1820.40 → 1600.0），
  而正常断行出来的行本不该在绘制时再折。故正常情况零效果，与真机截图一致（非首行右缘参差 1695–1725，
  非齐平的 1720）。
  **与 R1 无关**：追加 `'\n'` 前后首行宽相同，故 R1 那 88.4px 溢出**全部来自缩进右移**，
  且 `FirstLineIndentSingleLineOverflowTest` 量的 `lineMetrics[0].width` 恰等于生产绘制侧真实宽度，测试有效。

  > **2026-09-30 更新：可行性已测完，本条的诊断已被推翻并重写。**
  > 详见 `docs/自建断行引擎-测试计划.md` 的 T3 / T3b / T4 / T4b / T4c / T4d / T5 / T5b。
  >
  > **旧诊断「Skia 无『让已放得下的行也拉伸』的开关」是错的。**
  > `ParagraphStyle.textAlign = TextAlign.JUSTIFY` **可用且质量完美**：
  > 真实中文 326 个非末行，右缘误差 **100% < 0.5px，最大 −0.0002px**。
  > 机制（T4b 实测定性）：Skia 把 slack **均摊到字形 advance**，
  > `ΣΔadvance = +37.386px`（= slack）、`ΣΔ字缝 = −0.0002px` —— 等价于「CJK 逐字分摊」策略。
  > 末行不拉。行末尾随空格时 `rect.right` 超版心是 Skia 正确行为（空格不可见），测量须剔除。
  >
  > **真正的根因只有一条**：`appendTrailingNewline` 那个 `'\n'` **本身就是多余的、且有害的**。
  > 它把软断行行变成硬换行结尾行，`kJustify` 遂不生效。
  > 正确的最小修法是**删掉 `appendTrailingNewline`**，让行以软断行身份进 Paragraph。
  >
  > **但这条路有硬天花板（T4c 实测）**：Skia 的 `JUSTIFY` 只能服务于 **Skia 自己选的断点**。
  > 往文本注入 ZWSP `U+200B` **只能加断点、不能减**（Skia 按行宽优化择优，多给候选它仍选原来那个）；
  > 注入 WJ `U+2060` **无效且非零宽**（插 1 个 WJ 使首行 36 字 → 35 字）。
  > → **Skia 原生 JUSTIFY 与自建断点不可兼得，必须二选一**：
  > - **路线 S**（原称「路线 J」，改名避免与 `Justify` 混淆）：Skia 断行 + Skia JUSTIFY。
  >   R3 白拿；但 R4 停在 99.0038%、**R2 断词结构性不可行**。
  > - **路线 B**：自建断行 + 自建 Aligner + 逐字 `drawString` 落位。R1–R4 全达标。
  >
  > **✅ 2026-09-30 已定：路线 B 是唯一解。** 四条需求无妥协余地 → S 出局（R2 对 S 结构性不可能：
  > 画 `-` 必须自己知道断在哪，而 S 的断行是 Skia 的；R4 对 S 永远停在 99.0038%）。
  > S 下 `appendTrailingNewline` + `Alignment.JUSTIFY` 一并废弃。
  >
  > **性能已测完（补测 4 项中的第 1 项「章级/整书级 prepare」是唯一可能翻盘的数）**：
  > 章级 prepare 里**断行占 83%**；路线 B 完整模型（量宽 + 生产禁则断点 + 输出分配）
  > **K3 = 0.25x，比 Skia 基线快 4 倍**，章级倍率 **0.370x**、整书**负增量**，
  > 判据第 1/3/4 条（断行 ≤2x、单章 ≤1.5x、整书 ≤1s）**全部达标且余量大**。
  > 此前 T5 报的「断行 4.46x」是 **941 字合成大段的最坏情况高估** ——
  > 真实 leaf 平均仅 32.8 字，`build+layout` 固定开销占比高，逐字相对占比低。
  > 采信前过了三道闸门：量宽正确性（误差 ≤0.000072%）、抗噪 min-of-15、行数公平性（525/539 = 0.974）。
  > **唯一仍不达标的是绘制 2.10x**（判据 ≤1.2x），而 prepare 阶段不绘制，两者不冲突。
  > → **不触发 Rust 的重新评估条件**（"性能不达标且优化手段用尽"）。
  > 详见 `docs/自建断行引擎-测试计划.md` T5e；剩余 3 项见该文档 T5「待补测」。

- **Q9 — 用户报的两个真机缺陷已修完（2026-10-01），剩下三项亚像素/未结**：
  详见 `docs/自建断行引擎-测试计划.md` 的「本轮：用户报的两个真机缺陷（结论落纸）」与教训㉛。
  - ✅ 缺陷①「有行溢出、末字只显示半个」：查出**五个**独立机制（缩进右移、簇位交界下标、
    RIGHT/CENTER 缩进算两遍、簇位轨采纳「放宽」、`CENTER` 缩进被吃掉），全部修完。
    全书 19257 行复测：**溢出 > 0.5px 的行 0 行**（改前 18 行，最大 6.116px）。
  - ✅ 缺陷②「`text-align: justify` 但右侧没对齐」：簇位轨是「未拉伸的自然轨」，
    改为**只嫁接 kerning 收紧增量** + JUSTIFY 间隙线性均摊补回右缘。
    9201 个 JUSTIFY 行里原 6085 行没铺满（最大缺口 1530.91px）→ 现 **0 行缺口 > 1px**。
  - ⬜ **`<pre>` / `nowrap` 的处置可以撤销了**：先前登记的「594 行溢出、最多 318.60px、
    属既有设计缺口、需产品决策」**是探针失真**（教训㉛：`fontRuns` 坐标系传错，
    代码行被按正文面量、按 mono 面画，逐字差 8.44px × 38 字 ≈ 321px）。
    真机数据：对齐器层 `<pre>` 最大右溢 **1.4492188px**，不构成需要产品决策的缺口。
    若日后仍见到 `<pre>` 明显出框，那是**另一个问题**，请先按教训㉛ 复核测量口径再立项。
  - ⬜ **862 行簇位 JUSTIFY 行右溢 ≤ 0.5px**：浮点累加残差（`per = deficit/lv` 累乘），
    亚像素、不裁字。若哪天阈值收紧到「0.0px」需改成末槽一次性定位（会牺牲间隙线性）。
  - ⬜ **3 行 `<pre>` 对齐器层右溢 1.4492188px**：版心 1556 装不下该行自然宽时的余量，
    非簇位路径、非缩进路径，属「装不下」退化场景。
  - ⬜ 连字符行**不参与** JUSTIFY 右缘补偿（两种形态的末槽目标差一个连字符宽，`Placement`
    不暴露是哪种）⇒ 这类行会短掉一个 kerning 总量。**与修复前行为一致，不是回归。**
    若要补齐，需在 `Placement` 上暴露「连字符是否在区间内」。

- **Q10 — 探针纪律（2026-10-01 立规）**：
  探针「跑完即删」，**删前结论必须落纸**；探针的每个实参都要回答「生产侧这里传的是什么」。
  同一份数据可以被两套坐标系相减造出**量级完美吻合的假象**（本轮 318.60px = 8.44×38）。
  ⇒ **数字对得太整齐本身就是可疑信号**，真实排版缺陷极少是「整 × 整」。
  新锁必须做变异验证，「改坏了会红」是判据，绿了不算数；**否定结论（某变异测不出红）
  也要落纸并写清分工**（端到端锁证不了 `min(0,·)`，由合成轨锁证）。

- **Q11 — 连字符丢失（2026-10-01 用户真机报「`dependen-cies`、`com-piled` 都不加 `-`」）**：
  详见 `docs/自建断行引擎-测试计划.md` 的「连字符丢失：本轮两个独立缺陷」。
  - ✅ **缺陷 A（渲染层 · DrawLineBuilder）**：`hyphenAtEnd = leaf.hyphenAtEnd.getOrElse(lineIdx)`
    用**章内全局行号**（`firstLineIndex + i`）索引**叶内局部**列表，只有首行号恰等于叶内行号的
    叶才对得上，其余 `getOrElse` 落空 → 恒 false。改用 `i`。
  - ✅ **缺陷 B（排版层·上 · greedy）**：单槽 `lastOpp`，`lastOppWidth > avail` 就落 R1 core。
    因 `hang <= avail` 恒成立，该判负**只可能是差一个连字符宽**（实测 gap ∈ [−23, −1]px），
    而 `brk = i` 恰等于 `lastOpp` ⇒ **同一位置却丢连字符**，词被无声硬切。
    改为定长历史环（`OPP_HISTORY = 4`）从最近往回退，取第一个装得下的断点。
  - 端到端复测（Rust 书 22 章 / 版心 1600）：正文拉丁词内断行 253 行
    → 丢连字符 253 行（100%）**→ 0 行**。
  - ✅ 新增 `HyphenAtEndEndToEndTest` 3 把锁，全部做过变异验证。
  - ⬜ **未结（有意接受）**：代码语境（`borrow_mut`/`worker.thread`）K-L 断词表不给断点，
    走 R1 硬切、不补连字符 —— 断标识符会产出 `parse_con-fig_file`，浏览器对代码同样用
    `overflow-wrap` 硬切。`docs` 侧未接 `lang` 通道（现按 `en` 兜底）也是既有设计。
- **Q12 — 跨章预热探针 `CrossChapterPreflightProbeTest` 一直红（2026-10-02 记，**既有缺陷，非本轮引入**）**：
  `./gradlew :app:testDebugUnitTest --tests "*CrossChapterPreflightProbeTest*"` 稳定失败（3/3，非 flaky），
  卡在 `awaitPrepared(0)` —— `isChapterPreflightReady(0)` 15s 内一直 false。
  同类的 `isChapterPreflightReady(2)` **是好的**（同文件另一条锁就靠它），所以不是「预热整体没跑」。
  - **已在 HEAD（不带本轮改动）复现**，`git stash` 对照确认 ⇒ 与连字符修复、与
    `LAYOUT_VERSION` 自动化都无关；之所以一直没暴露，是**平时只跑 `./gradlew jvmTest`，
    它不包含 app 的 `testDebugUnitTest` / desktopApp 的 `test`**。
  - 现状代码看着是「该跑的」：`preflightNeighbors` 确实 `preflightChapter(chapter - 1)` 和
    `preflightChapter(chapter + 1)` 都派了（`BookDocumentController.kt:2906-2909`）。
    **头号怀疑**：`preflightChapter` 的在途早退分支
    「`if (scheduler.runningKeys.contains("pre:$index")) return`」**不重派**——
    若章 0 的 `pre:0` 键被别的在途任务占着（`pre:<章>` 键空间是与邻居预热/其它后台 pass
    共用的，见 `:1960` 与 `:2965` 的注释），这次早退后就再没人补派 ⇒ 永远 ready 不了。
  - 待查：谁占着 `pre:0`、是否该改成「记在途、完成后按当前 paramHash 复查再派」。
  - ⬜ 未结（本轮不碰：属跨章预热子系统，与分页缓存失效机制无交集）。

- **Q13 — 代码块「横向滚动 / 独立窗口纵横向自由浏览」（2026-10-02 用户提出，**产品立项，未动手**）**：
  **动机来自本轮修的真机缺陷**：书里 `pre code { white-space: nowrap }`（合法值）导致代码块
  连成一段并横向溢出被裁。我们只能在级联层把它**降级成 `pre-wrap`**（`StyleComputer.resolveWhiteSpace`）
  —— 那是「在不能横滚的介质里退而求其次」的 compromises，不是正解。正解是**给代码块一个能横滚的容器**。
  规范侧的准确口径（CSS Text 3 §5.1 行为矩阵）：`pre` = 保留换行 + **不折行**，
  `nowrap` = 折叠换行 + 不折行，`pre-wrap` = 保留换行 + 折行。
  **用户要的能力只有 `pre` 那一档**（换行必须原样、不许引擎擅自折行），缺的正是那根横向滚动条。
  - **形态 A（轻）**：代码块在页内可横向滑动（跟手拖动 / 惯性），页宽不变。
    只是把「裁掉」换成「能滑到看不见的部分」。
  - **形态 B（重，用户原话点名）**：**像浏览器点图片放大那样**，允许**跨页的代码块**单独进
    独立窗口，在里面**纵向 + 横向**自由滚动浏览整块代码。语义上最正确（代码是连续整体，
    翻页本来就是在切碎它），但要解决下面一串问题。
  - **形态 B 的前置依赖（必须先量清楚，否则是空中楼阁）**：
    1. **「哪些代码块跨页」引擎现在答不出来**。分页表是按行/叶切的，
       没有「这个块从第几页到第几页」的连续性概念 ⇒ 需排版层新增块级跨页索引
       （`LayoutBox` 侧记录首末页与页内偏移）。**这是本条最硬的一块，属排版层。**
    2. **独立窗口里的重排**。整块代码不再受版心宽度约束 ⇒ 换行度量基准变了
       （现在按版心宽折，改成按块自身内容宽）⇒ 与本轮 `pre-wrap` 降级的结果**不一致**，
       两处口径要统一，否则「页内看到的」与「窗口里看到的」换行位置不同。
    3. **性能**。整块预排 + 一次建窗，跨页代码块可能上千行 ⇒ 是否复用现有临时表机制
       （见 AGENTS.md 分页调度总则）还是另开一条，**待定**。
    4. **双端一致**。KMP + CMP 共享约束：窗口/手势属用户层，滚动偏移要能下发给绘制层
       （`LineWindowDrawer` 目前按页内绝对坐标画），需一条「页内坐标 → 块内坐标」的变换通道。
  - **⬜ 未结（本轮明确不做）**。当前 `pre-wrap` 降级是**兜底**，不是本条的替代品：
    它保证「不丢内容」，但代价是引擎会在作者没要求的地方折行。
    形态 A/B 落地后，`resolveWhiteSpace` 的降级策略应重新评估
    （横滚可用 ⇒ `pre` 那档也能满足，不必再强制 `pre-wrap`）。

- **Q14 — `SkiaRunMeasurer` 的保底表缓存是**每实例**的，进程里至少新建过 214 个（2026-10-02 记，顺带查出，未动手）**：
  `SkiaRunMeasurer()` 在生产路径上是**四处默认实参**
  （`GlyphPainter` / `KerningClusterTable` / `InhouseParagraphBreaker` / `LineAligner`），
  寿命短于任何一次排版会话。而 `universalCache`（`HashMap<UniversalKey, Array<Typeface>>`）
  是**该类的实例字段** ⇒ **每个新实例都重扫一遍全部已装族**（平板 59 族，每族一次 `matchFamilyStyle` native），
  扫出来的结果对所有实例**完全相同**。
  - **量化（平板 vivo PA2353，2026-10-02）**：靠已删诊断 `logUniversalTableOnce` 的行数折算 ——
    一次运行 39 秒打出 641 行探针日志，而它每实例最多打 3 行 ⇒ **≥214 个实例 = ≥214 次全量重扫**。
    （真机 59 族 ⇒ 约 1.3 万次 `matchFamilyStyle`；JVM 宿主 326 族 ⇒ 同一缺陷在桌面端是它的 5.5 倍。）
  - **为什么现在不动**：修法看似显然（提到 `companion object`，照抄同文件已有的
    `systemFallbackCollection` 模式），但**有一个反向代价没排除** ——
    **进程级缓存对「已装字体集变化」是陈旧的**（热插字体 / 运行期切换字体配置），
    而每实例缓存天然规避了这一点。要改成进程级，得先答「运行期内字体集是否真不变」，
    否则是用一个**看不见的字体陈旧缺陷**换一个**看得见的 CPU 浪费**。
  - **⬜ 未结**。已在 `SkiaRunMeasurer.universalTypefaces` 的 KDoc 上标注（现场），
    结论与「每实例的 once 门等于没有门」写在 `自建断行引擎-测试计划.md` §教训 ⑰。
  - 附带删掉：`logUniversalTableOnce` 诊断（结论已落 §教训 ⑯，探针跑完即删）。

- **Q15 — `<td>` 里的块级内容（`<p>`/`<div>`/`<ul>`…）**整格丢弃**，表格大面积「显示不出来」（2026-10-02 用户报 GIMP 手册表格不显示，探针已定位）：
  **这是既有缺陷，与 Q6（表格侧度量）无关** —— 已用探针逐值证明 Q6 前后列宽完全相同（见下）。

  **根因（一行）**：`StyledText.styledSegments` 吸收叶文本时对块级子节点直接跳过
  （`common/src/commonMain/kotlin/orilumn/reader/engine/laying/StyledText.kt:107` `isBlock(c) -> Unit`），
  而 `"p"` 在 `NormalFlowLayout.BOX_BLOCK_TAGS`（`:1672`）里 ⇒
  **`<td><p>文字</p></td>` 归一化后 `text` 是空串**，该格既量不到宽、也画不出字。

  **探针实测（GIMP 手册 chap_0021 的真实表形，跑生产代码）**：
  | | 图标格 `rowspan=2` | `th 注意` | 正文格 `<td><p>…</p></td>` |
  | --- | --- | --- | --- |
  | `absorbStyled(...).text` | `«￼»`（1 字） | `«注意»`（2 字） | **`«»`（0 字）** |
  | `LayoutBox.textLength` | — | 两格合计 **3** | **0** |

  排版产出：`tr` 两行，一行 `textLength=3`、另一行 **`textLength=0`**；
  版心 800px 下 `columnWidths=[25, 32]`、表总宽 **57px** —— 即「图标 + 标题」在，正文整格凭空。

  **Q6 无关的证明**：同一张表用**旧桩**（`preferredWidth` 默认实现 `text.length × fontSizePx`）
  与**新真测量**各跑一遍 `tableCellPref` + `autoColumnLayout`，列宽 `[25, 32]` **逐值相同**。
  两条理由：① 图标格文本是 `«￼»` 非空，走真实测量，但 U+FFFC 在两侧都量到 16.0；
  ② 正文格 `text` 为空，`tableCellPref:1537` **早退**，根本不调 `preferredWidth`。

  **影响面（平板 35 本书里 7 本有表格，逐书扫 `<td>` 形状）**：
  | 书 | 块级包裹的 `td` / 总 `td` | 占比 |
  | --- | --- | --- |
  | `book_1790742729435` | 908 / 987 | **92%** |
  | `book_1790742665434` | 1058 / 1170 | **90%** |
  | `book_1790742662856`（GIMP 3.0 用户手册） | 591 / 1249 | 47% |
  | `book_1790742718646` | 1 / 10 | 10% |
  | `book_1790742700787` / `book_1790865097552` / `book_1790872978337` | 0 | 0%（安全） |
  ⇒ 设备书库合计 **2558 / 4309（59.4%）个单元格不可见**。
  GIMP 手册全书 539 张表，`<td>` 绝大多数是「图标格 + 标题格 + `<p>` 正文格」三格结构 ⇒ **每张表的正文格都丢**。

  **为什么既有测试没抓到**：`TableFamilyTest` / 新加的 `TableColumnWidthRealMeasureTest`
  用的都是**裸文本单元格**，没有一个带 `<p>` ⇒ 覆盖面漏了这一形状。
  ⇒ 教训：**接线/几何锁必须用真实语料的单元格形状**，合成形状会把最常见的形态整个漏掉。

  **✅ 已修（2026-10-02，与 Q20 同批）**。规格定案：格内容按 **CSS 2.1 §16.3**（单元格内容是块级流）
  拆成**块序列**纵向堆叠 —— 格内**块级**子节点各自成块，格的**直接内联内容**合成一个匿名块。
  落点（排版层·上 + 塑形侧，逐块共用 [stackTableCellBlocks] 一处定位式）：
  - [LayoutBox.kt]：单 `shape` ⇒ `blocks: List<TableCellBlock>`（`el`/`imageRoot`/`outsideSiblings`/
    `style`/`text`/`runs`/`gapBefore`/`edgeH`/`edgeV` ＋ 可写 `top`/`height`/`ranges`/`lineHeights`/`shape`）。
  - [NormalFlowLayout.kt]：新增 `cellFlowItems`/`walkCellBlocks`/`cellRunAfter`/`absorbCellBlock`；
    重路径 `buildTableRows` 与轻路径 `BoxChapterLayouter.cellBlockPlan` 共调 `cellBlocks`。
  - [TableCellLines.kt]：`emitCell`/`emitCellImages` 逐块发射（逐块 style/tag/`edgeH`/`top`，
    `charBase` 逐块推进）。
  - `tableCellPref` 跨块**取 max**（CSS 2.1 §10.5），轻路径经 `cellBlockPlan`/`cellStyle` 与重路径同源。

  **踩过的三个坑（都是先做成错的版本、被探针/既有红锁打回来的）**：
  ① **匿名块的 `el` 必须是所在容器本身，不能造合成 `#text`**：塑形侧 [shapeGeometry] 从**真实子树**
  重抽文本（配 `outsideSiblings` 排掉 run 外兄弟），合成空节点子树为空 ⇒ `<br>` 硬换行被
  `white-space:normal` 折成空格 ⇒ 实测 Kindle 跨行格 5 行掉成 3 行、格高 161→105，
  `SkiaDrawLineWindowCoherenceTest.rowspanTableSharesSpanningCellHeightAcrossRows` 新红
  （`5 yTop(rel) expected:<98> but was:<77>`）—— 这条红锁就是本轮重构的触发点。
  ② **行内 `<img>` 不拆块**（与 `flowChildren` 同式）：它是 run 里的一个 U+FFFC 占位，只按 §10.8
  抬行高。拆块会让塑形侧对 `img` 根早退、塑出零行；且 `<td><img></td>` 要与修复前**逐字节同式**。
  ③ **`appendInlineText` 必须与 `styledSegments` 的 `walk` 逐条同形**（`br`/`img` 都要产字符）：
  它只当「这一段 run 有没有内容」的判据（`sb.isNotBlank()`），判据与内容不同形 ⇒ run 被判空丢块 ——
  GIMP 图标格 `<td rowspan="2"><img/></td>` 就是这样整格零块（`sb` 空、实际文本是 `￼`）。
  顺带修好嵌套图（`<p>字<span><img/></span>字</p>`）此前被静默丢字符的分叉。

  **顺带修掉的重路径 char 基址漂移**：`rowChars` 对 `<td><p>…</p></td>` 得 0、轻路径
  `styledCharAdvance` 得 N ⇒ char 基址整表漂移（点按/选区落错章内字符）。

  **锁**：`common:TableCellBlocksTest`（16 把，正向：块枚举/`outsideSiblings`/叶状块判据/堆叠几何）
  ＋ 新增 `app:TableCellBlocksEndToEndTest`（5 把，走 `prepareLight`→`incrementalLayoutForPage`/
  `shapeTempPageForward`→`tableCellLines` 整条链路，含 GIMP 2×2 rowspan 真实表形）。
  逐把变异验证（6 个变异，全部应验）：M1 `shapeLineHyphenAtEnd→false`、M2 删 `appendInlineText`
  的 img 分支、M3 `cellFlowItems` 不拆块、M4 匿名块 `el` 换回合成 `#text`、M5 `charAcc += 0`、
  M6 `stackTableCellBlocks` 不累计前块高度。

  **注意它与 Q13 的关系**：Q13 是「代码块该有的横滚能力没有」，本条是「表格内容直接消失」，
  两者无关；但都在 DocBook 风格书（GIMP 手册的 `summary="Note"` / `align` / `valign` 就是 DocBook 产物）上暴露。

- **Q16 — `pre code` 块里的空行全部消失（2026-10-02 用户报）**：
  **根因（排版层·上）**：断行器丢空行，**两侧断行实现都丢**：
  - 自建侧 `InhouseParagraphBreaker.greedy` 行首遇 `'\n'` 即 `s++; continue`，**不产出任何 `BrokenLine`**
    ⇒ `"a\nb"` 得 2 行。
  - 回退阀 `SkiaParagraphBreaker.layoutOnce` 归一化后 `if (e > s)` 把变空的行丢掉 ⇒ **同样 2 行**。
    ⇒ **拨 `inhouseBreak=0` 回退也修不好**，这点必须写死，否则会有人往回退阀上找。

  **`StyleComputer.resolveWhiteSpace` 不是元凶，但是放大器**：它把 `pre` 子树里不可折行的值一律降级成
  `PRE_WRAP`，而 `WhiteSpaceBreak.breakLeafLines` 的 `wraps()==false` 分支（`PRE`/`NOWRAP`）
  **显式产出零宽空行**、`wraps()==true` 分支把空行判给断行器
  ⇒ **降级恰好把 `pre` 送进丢空行的那条路**。讽刺的是它是「为不横滚做的妥协」。

  **认知源头（本轮已翻案，见测试计划 §T1b ③ 与教训 ⑳）**：「纯换行空行丢弃」被当成 **Skia 的固有语义**
  写进了规格。**探针实测裸 `SkParagraph.lineMetrics`：Skia 本来就有空行** ——
  `a\n\nb` → `[0..1, **2..2**, 3..4]`、`\na` → `[**0..0**, 1..2]`；
  丢空行的是**本仓的归一化步骤**（`if (e > s)`）。两条单测把这个 bug 钉成了规格：
  `InhouseParagraphBreakerTest` 断言 `"a\n\nb"` 是 2 行；`SkiaParagraphBreakerTest.brNewlinesKeepInteriorLines`
  用例名叫 `KeepInteriorLines` 却断言 `listOf(0..0, 3..3)`。
  ⇒ 「需先决定以 CSS 为准还是以现状为准」这个待裁决项**不成立**：仓内**早有正确的那份实现**
  （`breakLeafLines` 不折行分支一直在产零宽空行），这是**两条分支口径不一致**，不是取舍。

  **渲染层无责**：`LineAligner:113` 对 `n<=0` 正常返回空 `Placement`，`DrawLineBuilder:87-97` 不丢零宽行
  ⇒ **零宽空行一旦被生产出来就能正常画**。修在断行器就够（端到端锁已证）。

  **✅ 已修（2026-10-02）**。规格：空行 = **连续硬换行之间的空段**，是**真实行盒**（零宽区间、占一整行高）；
  末尾**单个** `\n` 不产幻影行（EPUB 源码惯用 `<pre>…\n</pre>`），末尾**两个** `\n` 时靠前那个终止的是
  真实空行、必须保留。落点（排版层·上）：
  - `InhouseParagraphBreaker.greedy`：行首 `\n` 产出 `BrokenLine(s until s)`；并在 `s = brk` 处
    **跨过硬换行终止符**（否则 `a\n\nb` 会多产一行）；末尾换行用 `trailingTerminator` 门槛挡掉。
  - `SkiaParagraphBreaker.layoutOnce`：保留**归一化前就零宽**的指标（`blank = s == e`）——
    这正是「Skia 对空行给的就是零宽区间」与「纯换行幻影行非零宽」的唯一区分点；
    另在循环后**按字符补回最后一个空行**（Skia 对文本以 `\n\n` 结尾时把空行与幻影行**合并成同一条非零宽指标**，
    实测 `[0..1, 2..3, 2..3]`，归一化后那一行就没了）。
  - 两条分支的**区间口径不同**（nowrap 分支的段含终止 `\n`、断行器分支不含）**未动**：那是既有差异、
    两侧都画得对，本轮只锁「行数 + 逐行可见文本」一致。

  **锁**：新增 `engine-skia:WhiteSpaceBlankLineParityTest`（3 把，钉「两个断行器 × white-space 两个分支」
  四格两两一致 + 空行占满行高 + `normal` 语境 `<br><br>`）；新增 `app:PreBlankLineEndToEndTest`（4 把，
  走 `prepare`→`fullLayout`/`prepareLight`→`incrementalLayoutForPage` 整条链路，断言叶文本/区间/行几何/行窗）；
  改写两条把 bug 钉成规格的既有锁。
  **逐把变异验证（4 个，全部应验）**：M1 自建侧退回修复前（9 把红）、M2 只退 Skia 侧（6 把红，
  含 `brNewlinesKeepInteriorLines`）、M3 只去掉「按字符补回最后一个空行」那 5 行（4 把红）、
  M4 只去掉 `s = brk + 1` 的跨 terminator（9 把红，连既有的公平性锁也一并红）。

- **Q17 — 中英（中西）文之间**零自动间距**；`CjkLatinSpacing` 是死代码（2026-10-02 用户问）** ✅ 已接线（2026-10-03）**：

  > **2026-10-03 收口**：产品口径已裁决并接线完毕，用户可见名改为「**混排字距**」。
  > 修法 = **保留作者空格之外的替换语义**（CLREQ 4.1），且**原有空格一律删除**（含连续多空格）。
  > 滑块 0 = 纯不动点。完整实测数据、锁与变异记录见本条末尾「2026-10-03 接线记录」。

  用户的三个子问题，逐一实测回答（**接线前**的现状，保留作对照）：

  | 情形 | 现状 | 代码位置 |
  | --- | --- | --- |
  | **原文有空格** `中文 Eng` | 空格**保留**，按普通空格字形算宽（不定宽，约 0.25–0.33em），且**是无条件断点** | `WhiteSpaceNormalize` 折叠为单空格；`LineBreakSegments:40` `if (isDocumentSpace(prev) ‖ isDocumentSpace(next)) return true` |
  | **原文没空格** `中文Eng` | **不插任何间距（0）**。视觉间隙纯粹是两种字体各自侧边距的副产品 | 无 `text-autospace` 实现；`SkParagraphFactory` 的 `ParagraphStyle`/`TextStyle` 也没设该属性 |
  | **中间夹 HTML 标记** `中文<code>Eng</code>` | **既不产生间距、也不影响断行** —— 因为 `<code>` 在这条链路上**根本不是盒子** | 见下 |

  **「盒边界」这个概念在本项目这条路径上不存在**：`LayoutBox` 只装块级节点（`LayoutBox.kt:32-40`），
  内联元素被 `styledSegments` 递归吸收进**一个扁平字符串**，只留 `FontRun(start, endExclusive, …)` 区间
  （`CssValues.kt:92-105`）。于是 `中文<code>Eng</code>` 在引擎里是
  叶文本 `"中文Eng"`（5 字连续）+ `FontRun[2,5)` 表示「第 2..4 字用等宽面」。
  - 断行器签名里**没有盒列表参数**（`ParagraphBreaker.breakLines` 只有 `text` + `fontRuns`），
    断点判定 `isBreakOpportunity(text, i)` 只看相邻两字符 ⇒ `文|E` 因 `isWideBreakChar(文)` 为真**可断**。
  - 绘制层 `LineWindowDrawer.drawGlyphPass:657,685` 是 `paintX + xs[local]`，
    `xs` 由 `LineAligner` 按 advance 累加 ⇒ `<code>` 的 `E` 紧挨着 `文` 画，**零边界补偿**。
    `Band`（`:703`）只携带 color/font/shift/underline，**没有间距字段**。

  **死代码**：`common/.../text/preprocess/CjkLatinSpacing.kt` 已实现与 CSS `text-autospace` 等价的行为
  （固定 0.25em 间隙 + 吸收多余半角空格），但**全项目零生产消费**（只被自己的单测调用）。
  与 `LongStringBreaker`/`overflow-wrap`/`word-break` 是**同一批**未接线的件（见 Q3 的同类决策）。
  接线 TODO 另登记在 `docs/upgrade-train-plan.md:96`。
  **顺带**：`wordSpacingPx`（`ComputedStyle.kt:307`）也是同类死属性 —— 解析了，排版链路不读。

  **测试覆盖**：**没有**任何「CJK+Latin 无空格混排」的像素/宽度断言。
  `CjkLatinSpacingTest` 只测纯函数返回值；`LineAlignerTest:27` 的不变式写的是
  「`x[i+1] == x[i] + adv[i]`（逐字相接）」—— **将来接线 `CjkLatinSpacing` 必须改这条不变式**。

  ### 2026-10-03 接线记录（Q17 收口）

  **产品口径（用户裁决，2026-10-03）**：
  1. 用户可见名 `中西字距` → **`混排字距`**（内部标识符与 JSON 键**仍是** `cjkLatinSpacing` ——
     改名会改掉持久化键、把刚写好的 `schemaVersion` v1 迁移作废）。
  2. **原有空格一律删除**：中西边界上**连续的全部**分隔空格吃掉，只留一个注入的固定间隙。
     旧口径「只吃一个、多余的保留用户可见分隔」被删 —— `中  A` 在旧口径下**一个间隙都不发**，
     那本身就是一处「滑块完全不起作用」。
  3. **滑块 0 = 字距 0**：只由「字间距」分隔中英文字符，**不做多余动作**（连作者空格也不删）。

  **「有些起作用、有些不起作用」的量化答案（真书实测，不是推测）**：
  真书《Rust 程序设计语言》（平板上那本，25 章）全文抽出 659827 字，跑一遍探测器：

  | 边界形态 | 真书占比 | 默认档（25）下的净变化 | 用户观感 |
  | --- | --- | --- | --- |
  | 真相邻 `Rust的` | 4942（17.6%） | **+11.10px**（0.25em） | 明显生效 |
  | 作者打了空格 `Rust 的` | 23181（**82.4%**） | **+1.15px** | 「没反应」 |
  | 标点 / 假名等隔断 | 0 | — | — |

  根因是**真字体的空格宽度不是 0.25em**：正文族 `思源黑体` 的空格 advance = **0.2240em**
  （fs=44.4 时 9.9456px；对比 `STSong`/`serif` 恰为 0.25em，这也是接线期误以为「默认值在不动点上」的原因）。
  ⇒ 默认档的**不动点是滑块 22.4 而不是 25**；25 只比它高 0.026em = 1.15px，肉眼不可见。
  实测逐档净增（`思源黑体`，fs=44.4）：`0 → −9.95`、`10 → −5.51`、`20 → −1.07`、
  **`25 → +1.15`**、`30 → +3.37`、`40 → +7.81`、`100 → +34.45`（px/处）。

  ⇒ **这不是接线 bug，是「替换」语义与该字体空格宽度重合的必然结果**。滑块往上推（≥40）即可看到变化；
  若产品上希望「默认档就一眼看得出」，唯一杠杆是**改默认值**（`ReaderSettings.cjkLatinSpacing`，
  且需 `schemaVersion` 升到 2 才能让老用户跟上）—— **本轮未改**，留待用户裁决。

  **顺带查清并修掉的两个真缺陷**（都在探测器里，`CjkLatinSpacing.kt`）：
  - 旧实现在发出「吃空格」间隙后写 `i = after + cc(after); continue`，那一跳**跳掉了一个真边界**
    （`中 A中文` 的 `A`↔`中` 不加字距），并让产出**依赖遍历路径** ⇒ 行子串检出可能多于整段检出
    ⇒ **画比量宽 ⇒ 行右缘越出版心被裁**。改成唯一推进点 `i = iNext`（数空格的内层 `while` 是纯前瞻）。
  - 两处越界读（`i + 1 < n` 在代理对边界字上放行到 `text[i+2]`；段末分隔空格上读 `text[after]`）
    —— 接线前是死代码所以从未触发，接线即触发（`"中A "` 直接抛 `StringIndexOutOfBoundsException`）。

  **锁**：`engine-skia:CjkLatinSpacingWiringTest` 28 把（四个施加点 + 方向 + 字号档 + 一律删除 +
  端到端断行 + 跨行「画 ≤ 量 → 后于 Q19 ①/② 升级为**画 == 量**」+ 滑块 0 纯不动点）＋
  `common:CjkLatinSpacingTest` 16 把
  （探测器判据、连续空格、`中 A中文` 边界恢复、**任意子区间上「子串检出 = 整段检出的受限子集」全枚举**）
  ＋ `common:ReaderSettingsTest` 4 把（`schemaVersion` v1 迁移的两向 + 幂等）＋ `common:LayoutParamKeyTest`
  3 把（`paramHash` 非 0 换键 / 0 复现历史流）。逐把变异验证 7 组全部应验（记录见 `PROJECT_STATUS.md`）。
  **连带改写的金标准**：`app:InlineCodeIdentifierBreakEndToEndTest` 的 420 / 160 两档（该类用
  `ReaderSettings.DEFAULT` 排版 = 生产默认配置，混排字距默认 25 生效 ⇒ 混排行更宽 ⇒ 断点前移；
  其余七档逐值不变，行内代码「只在 `_` 处断」「行尾不进代码 run」两条不变量全绿）。

  **登记的已知代价**：`<pre>` 等宽块里靠多空格对齐的字符画（ASCII art）若含中文字符，
  中西两侧的连续空格会被折叠成一个间隙而错位。真书该形态出现 **0 次**，按散文口径执行；
  要保对齐得单独裁决（不在本轮范围）。

- **Q21 — 真机复验报出的两个缺陷（2026-10-03 用户在设备上实测）** ✅ 已修：

  > ### ① 标点与异种字符参与了间隙（用户原话：「中英文标点和异种字符之间不应该受影响！」）
  >
  > 根因：边界判据是「码位落在 CJK 段 **或** `0x0030..0x024F` 段」，两段里都混着大量标点/符号 ——
  > CJK 侧混着 `0x3000..0x303F`（CJK 标点 + 表意空格）与 `0xFF00..0xFFEF`（全角形态，含全角拉丁
  > `Ａ-Ｚ`），西文侧混着 `± × ÷ ° © « » ‹ › ¢ £ ¥ § ¶ · ¤`。
  > ⇒ 收紧为**两侧都必须是字母数字**：
  > - `isCjk` = **表意文字**（`0x3400..0x4DBF` / `0x4E00..0x9FFF` / `0xF900..0xFAFF` / `0x20000..0x2A6DF`），
  >   去掉 `0x3000..0x303F`（含 U+3000 表意空格 —— 它是作者有意留的版式空格，不该被动）与 `0xFF00..0xFFEF`；
  > - `isWestern` = `cp in 0x0030..0x024F && cp.toChar().isLetterOrDigit()`（杀掉上面那批符号；
  >   顺带**删除**了原先为 NBSP / SHY 单列的两条例外 —— 它们已落进「非字母数字」这一条里）。
  >
  > **未裁决项（刻意保持现状，登记在案）**：假名（Kana）与谚文（Hangul）判为 **NONE**，两侧都不参与。
  > 这是改动前的行为，本轮**没有**顺手扩大范围。
  >
  > 实测：真书《Rust 程序设计语言》段级间隙 **27086 → 21724（−19.8%）**；
  > **右邻不是字母数字的间隙 = 0 条**（改前有 5362 条）。

  > ### ② 每段最后一行的中英间距不受滑块控制（用户原话：「英文与中文之间距离不受滑块控制」）
  >
  > 根因**不是**末行特殊，而是 `KerningClusterTable.shiftTrackByCjkGaps` 把**行内局部**的
  > `gaps[gi].leftIndex` 拿去比**段内绝对**的 `abs = start + j`（坐标系混用）。段首 `start` 一旦是几百/几千，
  > 几乎每条间隙都在 `j == 0` 就被计入 ⇒ 整条簇位轨被**同一个常量**平移 ⇒
  > `cnat[i] − cnat[i−1]` 的**逐槽差分里根本没有间隙**（常量相减消掉）⇒
  > `graftKerningOnto` 的 `tighten = (cnat[i] − cnat[i−1]) − placement.advs[i−1]` 在每个间隙位恒为 `−gap`
  > ⇒ 被 `min(0, ·)` 全额采纳 ⇒ **注入的间隙在落墨侧被整条抹掉**。
  > 纯 CJK 行不走簇位轨（`needsClusters` 为 false）⇒ 用 `placement.xs` ⇒ 间隙正常 ——
  > 这正是「**有些**起作用、**有些**不起作用」的成因，而中西边界**必然**含 Latin，所以实际「几乎全灭」。
  > 末行更刺眼是因为 `graftKerningOnto` 的 `justifyRightEdge` 补偿块只对「JUSTIFY 且非末行」生效，
  > 末行没有那层补偿来掩盖。
  >
  > **为什么原有那把锁没抓到**：`簇位轨带上间隙且嫁接后不被抹掉` 用的是 `start = 0`，
  > 而 `leftIndex < start + j` 在 `start == 0` 时**恰好**退化成正确的 `leftIndex < j` —— **只在首行成立**。
  >
  > 实测（真书 8738 段 / 29151 行，走完整绘制管线 `LineAligner` + `clusterXs` + `graftKerningOnto`，
  > 滑块 0.25 → 1.0）：修复前 18990 个行内中英边界里 **18743 个（98.7%）墨位一动不动**（应为 32.81px）；
  > 修复后 **18990 / 18990 精确**。LEFT 对齐下逐位相等；JUSTIFY 末行 1683 / 1683 精确。
  >
  > 修法：`leftIndex < j`（局部比局部）。**顺带**把绘制侧从「按行子串检测」改成
  > 「整段检测 + `gapsForRange` 裁到本行」（`LineAligner` 与 `KerningClusterTable.clusterXs` 两处），
  > 于是跨行边界那条间隙**本行照画** ⇒ 逐行 **画 == 量**（实测 29151 / 29151 精确；
  > 改前是 2540 行不等：186 行 −10px、2354 行 −1px）。`KerningClusterTable.runsWithin` 随之删除（死代码）。

  **锁（本轮新增 / 改写）**：
  - `common:CjkLatinSpacingTest`：`punctuation and symbols never participate on either side`、
    `real letters and digits still get gaps on both sides`、`gapsForRange` 三把。
  - `engine-skia:CjkLatinSpacingWiringTest`：`簇位轨在非零段内偏移下仍逐槽带上间隙`（**用非零 `start`
    的回归锁**，逐槽断言那条阶跃函数）、`端到端 滑块一动墨位就动 相邻与空格分隔两种形状都响应`
    （**刻意避开 0.25em 不动点**，否则测的是不动点而不是响应）、
    `标点与符号两侧不插间隙 中西标点中西一处都不成边界`；两条「画 == 量」的锁由「画 ≤ 量」改写。
  - **逐把变异验证 3 组全部应验**：M8 簇位轨判据改回 `leftIndex < start + j`（**只有**那两把新锁红）、
    M9 `isCjk` 重新纳入 `0x3000..0x303F`（探测器 2 把 + 接线 1 把红）、M10 `isWestern` 去掉
    `isLetterOrDigit()`（探测器 1 把 + 接线 1 把红）。
  - **连带改写的金标准**：`app:InlineCodeIdentifierBreakEndToEndTest` 的 **640 一档**
    （`…如 ` ‖ `wrapping_add` → `…如 wrapping_` ‖ `add`）。原因是 ①：`wrapping` 的 `g` 与全角逗号
    `，` 之间那条间隙消失 ⇒ 断行侧少预留一个 gap ⇒ 第一行多装下一个 `wrapping_`。**其余九档逐值不变。**

  **连带查清并明确不修的一条**（避免后人误"补"）：簇位轨上**被吃掉的分隔空格**并没有同步抵消，
  于是该位的 `tighten` 恒为 `+空格宽`。因为 `graftKerningOnto` 只采纳 `min(0, ·)`（收紧），
  正值被直接忽略 ⇒ 对落墨**无影响**，故按「不做多余动作」不改。若将来 `graftKerningOnto` 改成
  采纳双向修正量，这一条必须同步补上。

- **Q18 — 行内 `<code>` 里的标识符被英语音节断词切开，而 `_` 断点不注入**（2026-10-02 用户报 Rust 书 `wrap(断行)ping_add`）**✅ 已修**：

  > 修复记录（2026-10-02）：①② 两条子决策**都做**（用户裁决，理由见下）。落点从
  > `InhouseParagraphBreaker.sourcesFor` 改为新增 `RegionScopedBreakSource`（`common`/分行控制）
  > + `InhouseParagraphBreaker.breakOpportunities` 三条路径接线。三把锁、7 把变异全部应验。
  > 完整实测表与教训见 `docs/自建断行引擎-测试计划.md`「2026-10-02 — Q18」一节。

  **先回答用户问的「断行优先级」（实测，不是推断）：先压缩空白，再断行。**
  压缩在**排版层·上**的文本吸收步骤完成，早于任何断点计算：
  `NormalFlowLayout.absorbStyled`（`:563`）→ `styledSegments` → `WhiteSpaceNormalize.normalizeNode`
  （逐文本节点折叠）→ `mergeBoundarySpaces`（跨行内边界合并）→ `finishSegments`/`finishLeaf`（叶级 trim）
  ⇒ 断行器拿到的已经是折叠后的串，**断行器自己明确不做折叠**（`InhouseParagraphBreaker` KDoc `:29-30`
  「无空白折叠/无行首裁剪」只描述断行器这一层，别误读成引擎整体不折叠）。
  `white-space: normal` 下 epub 源码的换行与缩进**不会**撑宽行，在上一步就压成单空格。

  **真源码（`book_1790865097552.epub` / `book_1790872978337.epub` 实测，设备上的两本《Rust 程序设计语言》）**：
  ```html
  <li>所有模式下都可以使用 <code>wrapping_*</code> 方法进行 wrapping，如 <code>wrapping_add</code></li>
  ```
  **书里没有「断」字、没有删除线、没有 `<wbr>`、没有 `<br>`** —— 用户看到的 `wrap(断行)ping_add`
  那个 `(断行)` 是**引擎自己断出来的**。（`<wbr>` 全仓只有 converter 保留节点、**无任何代码把它转成断点**，
  而 `docs/engine-html-css-capability.md:26` 却宣称支持 ⇒ **已存在的文档-实现偏差，仍未登记修复。**）

  **实测前提**：`NormalFlowLayout.leafFontRuns` 给出的 run **确实带 `tag`** ——
  `[11,21) tag=code wrapping_*`、`[38,50) tag=code wrapping_add`（Q18 整套机制的地基，已钉进端到端锁）。

  **真正的缺陷（项目自己的规则被自己违反）**：`CodeIdentifierBreakSource` 与
  `EnglishHyphenationSource` 各自的 KDoc 都明写「代码标识符不走音节断词」（否则
  `parse_config_file` → `parse_con-fig_file`），而接线只判**叶块 tag**（本例是 `<li>`）⇒
  **在行内 `<code>` 内部**断音节词并补连字符；同时本该标的 `_|*`、`_|a` 两个断点一个都没注入。
  ⇒ **同一个引擎对「整块 `<pre>`」和「段落里的行内 `<code>`」给出两套断点规则，后者违反自己的规则。**

  **修法（两条子决策都做，用户裁决）**：

  - **① 必须**：行内 code run 内**禁止**音节断词。
  - **② 也做**：行内 code run 内**注入** `_` 分隔符断点（`mark`，不补连字符）。
    用户明知 ② 在中宽版心（样本 A 的 360）是负收益仍选「都做」，理由是**行内 code 与整块 `<pre>`
    规则统一**。补测窄版心长标识符（样本 B）后确认 ② **净正收益**：不注入时 R1 会**硬切在字母中间**
    （`wrapping_add_w` ‖ `ith_capacity_c`），正是用户报的那一类难看。

  **落点**：`RegionScopedBreakSource`（`common/.../laying`，**按位置分区**的断点源）。
  **不用「清位 API」** —— 那会破坏 `BreakOpportunitySet`「只置位不清位 ⇒ 顺序无关」的冻结不变式；
  分区后 source 集合内部仍是纯并集，不变式原样保留。
  「整块代码」与「行内代码内区」**共用同一个 `codeSources()`** ⇒ 同一种代码只给一套规则。
  属**排版层·上 / 分行控制内部**改动，**不跨层**。

  **口径分叉（未动，先前既有）**：`minContentSegments`（`LineBreakSegments:140`）恒只用 ZH_EN，
  连音节断词都不算 —— 本改动**不改变** min-content 的任何输出，故那条分叉不由本次引入。

  **实测结果**：Rust 真源码版心 640 由 `…如 wrap` ‖ `ping_add` 变为 `…如 ` ‖ ` wrapping_add`（用户报的那行已修）；
  **420 / 480 / 520 三档与改动前逐值相同**（零回归的实测证据）。
  ⚠ **480 那档的 `wrap` ‖ `ping` 断在正文裸词 `wrapping` 上（不在任何 `code` run 内），是合法音节断词，不要去"修"它。**

  **判据分层（教训 ㉓）**：「哪些**字符**是代码」留在断行器（它有 `CODE_TAGS` 与 `FontRun`）；
  「哪些**位置**属内区」（`inCode[i-1] && inCode[i]`，边界归外区）搬进
  `RegionScopedBreakSource.maskOf` —— 因为这条判据**在生产版心下不可观测**（`&&` 与 `||` 只在
  min-content 单元内部有别，而生产恒有 `widthPx ≥ ceil(minContentWidth)`），只有断点集层能钉住它。

- **Q19 — 标题字重：①看不出粗 ②选粗细对标题恒无效**（2026-10-02 用户报 GIMP 手册标题用「方正粗金陵」）：**：

  **(b) 选粗细对标题恒无效 —— 根因一行（渲染层）**：
  用户字重**唯一**通路是 `SkParagraphFactory.anchoredWeight`
  （`engine-skia/.../skia/SkParagraphFactory.kt:54-61`），它第一行就 `if (italic || weight != 400) return weight`；
  而 `h1`–`h6` 的 UA 字重是 `bold`=700（`common/src/commonMain/resources/css/ua.css:18-23`，已核）
  ⇒ **对标题恒不触发**。这条语义还被 `WeightAnchorTest:17-18` 固化成断言。
  **用户层没有第二个出口**：`ReaderUiSheet.fontRules`（`common/.../css/ReaderUiSheet.kt:62-68`）只写
  `font-family`（含标题槽 `h1..h6`）与 `line-height`/`margin`/`text-indent`，**一个字重都没有**。

  **(a) 看不出粗 —— 三个叠加原因，前两个已核**：
  ① **全仓无合成粗体**：`fakeBold`/`embolden`/描边加粗**零命中**（已核），落墨就是
  `GlyphPainter.kt:80` 的 `drawString` ⇒ **`font-weight` 的唯一视觉效果是「能不能选到更粗的面」**，
  选不到就原样。单面族（如只嵌一份的「方正粗金陵」）请求 400/700 都返回同一张面。
  ② **书内 `@font-face` 的 `font-weight` 描述符一路被丢**：`LightCssParser` 解析了（`:234`）
  → `CssFontFace.weight` → `BookFontRef.weight` → **`BookFont(family, bytes)` 没有字重字段**（已核，`BookFonts.kt:29-31`）
  → `EmbeddedFont(familyName, bytes, aliases, faceIndex)` 也没有（已核，`SkiaFontPool.kt:19-24`）
  → `SkParagraphFactory` 只 `registerTypeface(tf, f.familyName)` ⇒ **只按族名注册，face 的 CSS 字重从不参与匹配**。
  ③ **用户设了同族锚点会把池收窄成单面**：`FontPoolSync.kt:48-62`（尤其 `:54-56`）只保留锚点字重那一张面；
  正文 400 被 `anchoredWeight` 改写后命中它，标题 700 **不改写**、Skia 在单面集合里也只能返回它
  ⇒ **标题字重被锚点间接锁死，而用户又无法纠正（＝就是 (b)）**。

  **顺带两个设置层坑**：`FontLibraryPanel:296/307` 只让**多字重族**进字重页（单面族进不去）；
  `:443-458` 的 `weightChoices()` 无「自动/跟随原书」项；`ReaderSettingsPanel:290` 的「跟随原书」只清字体槽、
  **不清 `fontWeightAnchors`**，残留锚点会继续收窄池。

  **无法从代码断定**：该字体文件内禀是 400 还是 700（字体在 EPUB 内，仓库无此资产）⇒ **(a) 的最后一环不下结论**。

  **☑ 部分处理（2026-10-02）**：(b) 已修（anchoredWeight 放开非400）；(a) 已补齐 @font-face 字重/斜体描述符透传字段，但合成粗体（fakeBold）与锚点收窄仍未处理。 `SkParagraphFactory`，用户层不需改 ⇒ **不跨层**。
  若要修 ②（`@font-face` 字重参与匹配），需给 `BookFont`/`EmbeddedFont` 加字重字段并透传到 `registerTypeface`，
  那是**排版层(上) → 渲染层**的数据形状变更；建议先在 `common/engine/css` 内定一个「face 描述符」模型，
  由渲染层单向消费，**别把 CSS 语义漏进渲染层**。

- **Q22 — 四级 JUSTIFY slack 优先级：级 3 无上限的代价（2026-10-03 本轮落地后登记）**：

  **额度与顺序是两个正交轴**：顺序管「先动谁」，额度管「这一类最多动多少」。
  顺带把用户问的「中英注入间隙为什么排在 CJK 字间之后」一并落纸 ——
  **答案是频次，不是额度**：瀑布的原理是「同一份 slack 摊得越薄越不显眼」。
  实测槽位占比 L0/L1/L2/L3 = 8.5% / 56.5% / 5.5% / 29.5%，同样 1em slack 摊到 ~10 个
  CJK 槽上每个 0.1em（看不出来），摊到 2 个注入槽上每个 0.5em（一眼看出那里有个空）。
  **反过来排会更显眼，不是更不显眼。** 且级 2 几乎轮不到（派上非零量的行 262 / 14722，
  级 1 容量 ~15 槽 × 0.25em ≈ 3.75em/行，绝大多数行的 slack 在级 1 就吃完了）。
  cap 不对称（级 2 = 0.5em > 级 1 = 0.25em）不是排名矛盾：cap 答的是「轮到这一类时它最多吃多少」——
  注入间隙的自然宽**就是**滑块值（0~0.5em），翻倍仍在「设计出来的缝」的认知内；
  CJK 字间加到 0.25em 就开始不像「中文」了 ⇒ 小额度、先吃、先饱和、交给下一级。

  **待裁决的两个可选项（都只是调参，不改架构，本轮按现状保留）**：
  - **(a) 级 3（词内字母缝）设上限。** 现状无上限，兜底收口靠
    `take = min(remain, n × ∞) = remain`，代价是纯西文行可能把单词拉散得很明显。
  - **(b) 级 1（CJK 字间）额度从 0.25em 上调。** 容量变大 ⇒ 级 2/3 更少轮得到；
    收益只在「级 1 已饱和仍差很多」的长行上。

- **Q23 — 级 3 无上限导致 2.9% 的槽超出 0.25em**（2026-10-03 登记）：
  超 0.25em 槽位占比 11.58%（均摊）→ **3.80%**（四级），max 单槽 691.241px → 648.987px。
  这是**改善而非退化**（词内缝总量 −92%），但绝对值仍高。收敛手段即 Q22(a)。
  已实测的分布：每槽(em) p50/p95 = L0 0.194/0.5（封顶）、L1 0.006/0.104、L3 0/0。

- **Q24 — 标点挤压（2026-10-03 落地并补齐锁；原登记为「零实现」，已改写）**：

  **已落地**：`engine-skia/PunctuationSqueeze.kt`，额度
  `S(i) = min(0.5em × size(i), (adv(i) − lsPx(i) − inkRight(i)) + inkLeft(i+1))`，
  候选表 `SQUEEZABLE_CLOSING_PUNCT`（22 字，只压**收尾类**，开括类不压）。
  用户三项裁决：**上限 0.5em**、**悬挂（hanging punctuation）不做**、
  **中英注入间隙参与拉伸/压缩**。
  关键性质：挤压是**上游供给侧**（造 slack），与优先级分配（花 slack）**串联不是并列** ——
  `natural' = natural − S ⇒ slack' = slack + S`；且**只减不增 ⇒ 不可能超出版心**。

  **本轮补的锁**：`PunctuationSqueezeLockTest` 6 把 + 12 项变异验证
  （M1 删 `inkLeft` 项 / M2 盒宽漏减 `lsPx` / M3 上限漏乘字号 / M4 `min` 两项对调 /
  M5 画侧 `advBase` 传错 / M6 断行器漏减 `squeezeW` / M7 max-content 方向反 / M8-M9 两侧上限传 0 /
  M10 画侧只减一半 / M8b 断行侧 `breakLines` 上限传 0 / M7 全仓复测 / M9 全仓复测）。
  **纪律要点**：额度是 `min(cap, headroom)`，headroom 绑定时 `inkLeft` 与 `lsPx` 两项
  才可观测，cap 绑定时被吃掉 ⇒ 锁必须**两支都取样**（锁 1 用生产 cap，锁 2/3/4 用
  测试专用 `bigEm = 1.5em`），否则写多少断言都抓不到变异。

  **实测（真书 25 篇 / 3958974 字 / 150 格 = 25 文件 × 3 版心 × 2 字距，fs43.75）**：
  行数 223072 → **219037（比值 0.98191）**，**146 / 150 格省行**；
  挤压槽 114456 个，命中 cap 112812 / 命中物理余量 1644，**超 cap 槽 = 0**，
  平均每槽 21.5972px（cap = 21.875px）。⇒ **挤压确实在省行，且额度没有被突破**。

- **Q25 — 四级分配留下 40 行缺口、单槽 >2em 的行从 2 涨到 15**（2026-10-03 登记）：
  真书 31898 行 / 14722 个 JUSTIFY 中部行里 **40 行（0.27%）四级全封顶仍有余量**
  ⇒ 留缺口。缺口 p50 1.75em、p95 5.5em、max 15.5em（≈680px）。
  单槽拉伸 >2em 的行：旧 2 → **新 15**。**这是四级分配的已知代价，不是 bug** ——
  旧均摊把量摊薄到全部槽上（每个都不到 2em），四级优先集中在少数槽上。
  **待裁决**：(a) 留缺口 vs (b) 允许级 3 在缺口行突破上限把右缘补齐。
  本轮按用户裁决**保留现状**（b 会让单词拉散，缺口的右缘参差在 0.27% 的行上更难察觉）。

- **Q26 — `CrossChapterPreflightProbeTest.a flip landing prewarms both neighbors…` 既有失败**（2026-10-03 登记）：
  `:89 awaitPrepared(0)` 超时。**A/B 已证明不是本轮引入**（`git stash -u` 后仍红），
  属既有问题，本轮不在范围内也未修。`app` 222 测试目前 1 红即此条。

- **Q20 — 断词连字符在「增量路径」和「表格格」两条路上 100% 画不出来**（2026-10-02 用户报 Rust 书 `wrap` 行尾无 `-`，并指「上次发现过、应该没彻底解决」——**确实没彻底解决**）：

  **上次那次修的是 `DrawLineBuilder` 的下标错位，锁叫 `HyphenAtEndEndToEndTest`；但 `DrawLine` 全仓有
  **三个**构造点，那次只覆盖了其中一个。**

  | # | 构造点 | 服务的路径 | `hyphenAtEnd` |
  | --- | --- | --- | --- |
  | 1 | `DrawLineBuilder.kt:97`（`:129` 传值） | **canonical / 重路径** | ✅ **已修**（上次就修的这里） |
  | 2 | `BoxChapterLayouter.kt:797`（`buildPartialSkiaWindow`） | **增量 / 磁盘表命中**（读者日常稳态走的就是这条；两个调用点 `:1010`、`:1958`） | ❌ **整段没传** ⇒ 取默认 `false` |
  | 3 | `TableCellLines.kt:172`（`emitCell`） | **全部路径的表格单元格**（构造点 1、2 都在 `leaf.table != null` 时 `continue`，表格一律走这里） | ❌ **整段没传** ⇒ 取默认 `false` |

  **掉链子的机制**：`DrawLine.hyphenAtEnd`（`LineWindowDrawer.kt:77`）默认 `false`
  ⇒ `LineWindowDrawer:317` 把它转给 `LineAligner.place`
  ⇒ `LineAligner:151/153` 的 `hyphenW` 恒 `0f` ⇒ `placement.hyphenWidth == 0f`
  ⇒ 落墨那段的守卫 `if (placement.hyphenWidth > 0f)`（`LineWindowDrawer.kt:559`）**整块跳过**
  ⇒ **既不画 `-`，也不为它留宽**（留宽由断行器在 `oppW` 里已经算过，故不会溢出，只是少一个字符）。
  副作用：JUSTIFY 行的 `gapCount`（`LineAligner:249`）少算一个，行内字缝与断行器的假设差一格。

  **为什么既有锁全绿**：`HyphenAtEndEndToEndTest`（3 把，KDoc 自己写了「本锁必须走完整链路
  `BoxLayouter` → `DrawLineBuilder`」）**三把全部只调 `DrawLineBuilder.build(...)`**，
  从没碰过 `buildPartialSkiaWindow`，也没碰过 `TableCellLines`
  ⇒ **它锁的是 3 条路里的 1 条**。⇒ 教训 ⑮（接线锁必须真走那条接线）的第二次应验：
  上次那条锁证明了「下标要对」，没证明「三条路都要对」。

  **修法（已按此落地，但载体换了个地方）**：
  - 构造点 ②：原计划的 `leaf.hyphenAtEnd` **不够用** —— 轻路径（增量/临时页）**根本没有可读的
    `LayoutBox.hyphenAtEnd`**：`BoxChapterLayouter` 那边的叶是按 `ranges = emptyList()` 建的，
    断行只发生在塑形那一步（`tempShape`/`shapeLeaf`）。⇒ 改挂 **shape**：
    `ParagraphShapeRef.shapeLineHyphenAtEnd(k)`（接口默认 `false`，哑实现零改动）
    ＋ `ShapedGeometry.lineHyphenAtEnd`，由 `shapeGeometry` 从 `broken.map { it.hyphenAtEnd }` 直接搬。
  - 构造点 ③：格内块两路都由 `fillTableRowCells` 塑形、**形状即单源** ⇒ 也读
    `shape.shapeLineHyphenAtEnd(k)`（轻路径不回填 `TableCellBlock.hyphenAtEnd`，读块会漏）。
  - 构造点 ①（canonical）仍读 `leaf.hyphenAtEnd`：`DrawLineBuilder` **不塑形**，手里只有盒流记录。
    两路同断行器同宽，值逐项一致 —— 与 `ranges` 的既有口径完全一样
    （canonical 读 `leaf.ranges`，轻路径读 `shape.lineStart/lineEnd`）。

**✅ 已修（2026-10-02，与 Q15 同批）**，三个构造点一次补齐（`DrawLineBuilder` /
`BoxChapterLayouter.buildPartialSkiaWindow` / `TableCellLines.emitCell`）。

**锁**：`app:SkiaDrawLineWindowCoherenceTest.hyphenAtEndFlagIsCarriedByIncrementalAndTempWindows` ——
6 档版心（180/240/300/380/460/560）逐档比 canonical ↔ 增量 ↔ 临时三窗的 `range`/`text`/`hyphenAtEnd`，
并显式断言增量窗与临时窗**存在**断词行（缺这条，锁会在「没逼出断词」时静默通过）。
变异验证：`shapeLineHyphenAtEnd` 改回恒 `false` ⇒ 红（`w=180 canon=4 incr=0`）。
既有 `HyphenAtEndEndToEndTest`（3 把）**没动**：它验的是构造点 ①，本条补的正是 ②③ 的覆盖面漏洞。

- **Q1 — 长串（超长不可断单元）症状用户无法复现（2026-09-30 记）**：
  真机曾见长串行溢出/参差，但用户侧无法稳定复现，缺可复现样本。
  自建断行引擎**必须不比 Skia 差** —— T1 等价性（99.0038%）已隐含覆盖该场景，
  但**不等价于「长串更好」**。本轮不作为 R3 的判据，仅留作悬念；
  若日后拿到可复现样本，需单独立项对比 Skia 与自建实现。

- **Q2 — 断词（hyphenation）方案已选型、待定案（2026-09-30 记）**：
  skiko 的 paragraph 包**零 hyphenation API**（javap 扫 3 个类命中 0）→ 只能自建。
  已实测选定 **Knuth-Liang + 新版 `hyph-en-us`（11125 patterns / 106KB，纯数据，KMP 安全）**：
  与权威参考实现 pyphen 0.18.1 在 **370105 词**上做断点集差分 → **100.0000% 一致，多 0 缺 0**；
  Kotlin 侧 **0.0011ms/词**，相对 `build+layout` 0.32ms 可忽略。
  **落地前提已解除**（2026-09-30）：路线 B 已定为唯一解，本条不再是"被路线卡死"，
  而是 B 路实现里的一个待办子项（软连字符绘制 + 断点接入正文断行单源）。本轮只做 en-US。
  步骤见 `docs/自建断行引擎-实施方案.md` S7。

- **Q3 — R1 长串：F11（`LongStringBreaker`）/ F12（`overflow-wrap`·`word-break`）零消费（2026-09-30 记）**：
  两处都已实现但**全项目零消费**（grep 只命中自身单测与 CSS 级联）：
  - F11 `LongStringBreaker.breakOpportunities`（`common/text/preprocess/LongStringBreaker.kt:26`）
  - F12 `ComputedStyle.overflowWrap` / `.wordBreak`（`ComputedStyle.kt:311/313`，由 `StyleComputer` 正常解析）

  **决策：先不接线**，理由三条 ——
  (1) R1 症状**用户无法复现**（见 Q1），为一个不可复现的症状扩 `ParagraphBreaker` 接缝不划算；
  (2) CSS `overflow-wrap: normal` 的语义本就是「只有在否则会溢出时才在任意点断开」，
      即 R1 core，与 `break-word` 的区别只在**是否影响 min-content 尺寸**——
      而 `minContentWidth` 已独立走 `minContentSegments` 计算，不经过断行器；
  (3) 接线要改 `breakLines` 3 个重载 × 2 个调用点，**接缝扩大后 S3 之后再改更贵**。

  落地为「独立后续项」，与 S3 主链路解耦。详见 `docs/自建断行引擎-实施方案.md` §2.2(c)。

- **Q4 — 多语言：其他语言的断词与禁则（2026-09-30 记，本轮不做）**：
  本轮范围 = **中文实现 + 英文断词 + 中英文禁则**；其他语言**只留数据形状上的口子**，不实现、不扩接缝。
  - **英文断词不需要 `lang` 参数**：适用范围按 **script 判定**（只对纯 ASCII 拉丁字母 token 跑 K-L），
    中英混排的书里只有英文词被断，中文一个字不动 → `ParagraphBreaker.breakLines` **零扩张**。
  - **已知局限（不修，写进锁）**：法/德/西也是拉丁 script，会误用 en-us 断在非音节处；
    拉丁变音字母（`ß é è`）不在 ASCII 白名单内天然排除，损害面限于「无变音的西/德/法词」。
  - **口子留在数据形状上（不是接缝上）**：① 禁则表收成 `KinsokuRules` 值对象；
    ② 每语言的 `lefthyphenmin`/`righthyphenmin` 随词典走（en = 2/3，德法不是，**且这两个参数不在 pattern 数据里**）；
    ③ `Hyphenator.hyphenate(text, lang)` 的 `lang` 是形参不是全局读；
    ④ `BreakOpportunitySet` 收「断点增强器」入参，不硬编码调 en-us。
  - **书级语言目前是零**：EPUB `dc:language` 未解析、`ComputedStyle` 无 `lang` 字段、`:lang()` 不支持
    （`HtmlTreeConverter.kt:190-193` 把 `lang`/`xml:lang` 存进 attrs，但断在属性层）。
    `font_faces.lang` 是**字体表**的一列（跟着 `FontFace` 走），**语义未查证**，但它**不是「书的语言」**。
  - 详见 `docs/自建断行引擎-实施方案.md` §0.1 / §0.2 / S7。

- **Q5 — 含拉丁字母的行失去 kerning 与 fi/fl 连字 ✅ 已解决并销案（2026-10-02 销；S5b 落地于 2026-10-01）**：
  > **销案结论**：`KerningClusterTable` **就是解法本身**，不是「未接管的缺口」。
  > kerning/连字信息只存在于字体 GPOS/GSUB 表里，取它必须有一个 shaper，而 shaper 就是 Skia（底层 HarfBuzz）。
  > `SkiaRunMeasurer` 刻意「逐码本量宽、**不整形**」是 S2 冻结的契约（段落是逐字形回退的），
  > 于是形成有意分工：**量宽侧不整形 + 落墨侧现算簇位**。
  > ⇒ 判据不是「自建要接管整形」，而是「**取簇位必须同源**」—— 现算簇位与量宽出自同一次 Skia 整形，
  > 两者**同源**，量画一致的前提仍然成立（`GraftKerningOntoTest` 的「绘制轨恒不宽于量出轨且单调」钉住）。
  > **成本**：`needsClusters` 先判 `isLatinish`，纯 CJK 行**不建 Paragraph**（零行为变化）；
  >   含 Latin 的行 `getRectsForRange(0, len)` 一次拿整段全部簇矩形，**比裸 cmap 还快**（0.047ms vs 0.0877ms / 段）。
  > **锁**：`KerningClusterTableTest` 3 把（kerning 对位置的簇位与裸 cmap 不等 = 证明生效 /
  >   纯 CJK 行不建 Paragraph / 行内换面分段各自取簇位不混面）、`GraftKerningOntoTest` 10 把
  >   （簇位轨放宽时落位一点不动、收紧时逐字采纳、混合时净偏移只算收紧、
  >   绘制轨恒不宽于量出轨且单调、JUSTIFY 补偿 6 条）。
  > 下面保留当时的量级数据与分析，作为「为什么这个缺口存在、后来靠什么填上」的记录。

  路线 B 的管线是「逐码本量宽 → 逐字 `drawString` 落位」，`Font.getWidths(glyphs)` 是裸 cmap 查表、
  **不整形**，而 skParagraph 走 HarfBuzz（带 `liga` 与 kern 表）。故两者在拉丁字母上不等宽。
  - **量级（实测，T8.4）**：连字只在 CJK 面（`STSong` 的 `fi` **−9.00px** @100px、`Songti SC` −4.10、
    `PingFang SC` −2.10）；kern 只在拉丁面成规模（Times New Roman **59 对**最大 **−3.52**、
    Helvetica 56 对最大 −1.76、**Georgia 0 对**）。CJK 侧**完全干净**：兰亭序 84 字长串 Δ=+0.000000%。
  - **暴露面（实测，`books/` 11 本 epub 正文）**：3 本中文网文 ≈ 0 处/万字符；
    英文书 25–32 处/万字符（moby-dick 32.24、gutenberg-84 31.12、gutenberg-1342 25.52）。
  - **【历史】当时的理由（本轮不修；已被 S5b 的「落墨现算簇位」推翻，保留以说明缺口为何存在）**：① skiko `Paragraph` 无任何字形/簇级位置查询（`javap` 确认），
    「整段整形一次 + 查每簇位置」这条路不存在；② kern 可以在 Aligner 里补
    （`w_j = adv_j + kern(cp_{j−1}, cp_j)`，两侧同表即自洽），但**连字补不了**（它改变字形个数），
    而连字恰是 CJK 面上更大的那个 —— 补一半要「每面 62×62=3844 次 Paragraph 测量建表」，
    覆盖小偏差、放过大偏差，投入产出不成立；③ 要真补必须连绘制侧的 `Paragraph` 通路一起改，超出本轮范围。
  - **为什么这是「自洽」而不是「缺陷」**：断行侧量 `Σ` 逐码本、S4 Aligner 的 `x_i = x_0 + Σ(w_j+delta_j)`
    用同一个 `advanceOf`、S5 逐字绘制 —— 三者同源即量画一致。全部整形缺口**都是负的**
    （15 个面 × 多组实测无一为正），所以任何「比量出的更窄」的绘制都不可能溢出版心。
  - **【2026-10-01 S5 已落地】本条从「预测」变成「已发生」**：S5 把 `paragraph.paint` 换成逐字
    `drawString` 后，**绘制侧也从 HarfBuzz（有 kern/liga）变成裸 cmap**，与量宽侧终于真正同源 ——
    这正是 S5 的设计前提。代价就是上面那组数字**已经在生产生效**：
    中文书无感知（Δ=+0.000000%），英文技术书每万字符 25–32 处字距略宽。
    ⇒ **真机复测时看到英文行「字距略宽」是预期行为，不是 bug**，别再当回归查。
    验证手段：`LineWindowDrawerTest` 21 把像素级锁全绿（它比的是像素位置，不是宽度）。
  - **【历史】当时设想的补法（未采用）**：在 `SkiaRunMeasurer` 里加一层「per-face kern 表」，
    按 (面, size) 懒建表（`para("ab") - adv(a) - adv(b)`，62×62 上界），
    让 `advanceOf(i)` 返回 `adv(cp_i) + kern(cp_{i−1}, cp_i)`。连字仍无解。
    **这条路与 S2 契约冲突**（量宽侧「不整形」是逐字形回退的地基），实际改走「落墨现算簇位」，**量宽侧一字未动**。
  - 详见 `docs/自建断行引擎-实施方案.md` §2.2(d) 与 `docs/自建断行引擎-测试计划.md` §T8.4。

- **Q6 — 表格侧：min 侧闲置 + 自建侧无真测量 ✅ 两个前提都已消解（2026-10-02 销）**：
  > **销案结论**：两个前提分别被两件事拆掉，且**都只改了一行**。
  >
  > ① **「自建侧无真测量」**：`InhouseParagraphBreaker` 补 `preferredWidth` 覆写，接
  >    `SkiaRunMeasurer.naturalWidth`（与断行**同一个 `advances` 单源**，只差「不施加版心宽、不找断点」）。
  >    `minContentWidth` **一个字都没写** —— 接口默认实现本身就是「按 `minContentSegments` 切段后
  >    逐段调 `preferredWidth` 取最大」，故随 ① 自动变真，实测与 Skia 侧**逐值相等**（181.6404）。
  > ② **「min 侧闲置」**：本来就不是缺陷而是**实测事实**（49 张表全落 `avail>=totalMax` 直通段），
  >    本轮只把它记成「不修也不影响」——它从来不需要修。
  >
  > **实测两侧差**（fs=16 与 44.4，`STSong,serif`，同宿主对照）：
  >
  > | 文本类别 | 差 |
  > |---|---|
  > | CJK / URL / code / 混排 / 无 kern 对的 Latin | **0.0000%** |
  > | `Office of the Future`（含 kern 对） | **+1.104%**（1.44px@16 / 3.996px@44.4） |
  > | `AVATAR Wayfinding WA` | **+0.838%**（同一个 kern 对） |
  > | min-content（Latin 37 字） | **0.0000%** |
  >
  > 差值恒为**一个 kern 对的像素量**（1.44→3.996，比值 2.775 = 44.4/16），方向**永不反向**
  > （整形缺口全为负，见 Q5）。对比原台账里的「桩高估 Latin **2.49x**」——
  > 那 2.49 倍**全是桩的错**，真测量只差 ~1%。
  >
  > **③ 顺手拆掉的两处钉扎**（这才是「表格的问题」的本体）：
  > `BoxChapterLayouter.tableBreaker`（恒 Skia）与 `heavyPathBreaker` /
  > `autoColumnMeasurePinnedToSkia`（把度量方法钉回 Skia 的包装）**一并删除**，约 -90 行。
  > 现在 `bodyParagraphBreaker` 是**唯一**断行器口，重路径 `BoxLayouter` 与轻路径
  > `LightPrepare`（表格测宽）**共用同一个实例**（`BoxChapterLayouter.breakerFor`，
  > 缓存键 `(profile, 变体)`）。
  > ⇒ 原原则「**变体只该改变行，不该改变列**」**作废**：它当初是用「把列宽钉回另一套实现」
  > 来维持的，而**那两处钉扎正是重轻分叉的真正来源**。现在一致性由「同一个对象」保证。
  >
  > **④ 拆钉扎时当场抓到的两个真 bug**（都是锁逼出来的，不是预演）：
  > - **两处钉扎本身就是缺陷的温床**：`tableBreaker` 只被**轻路径**消费，重路径压根不经过它 ⇒
  >   只走重路径的回归锁改 `tableBreaker` **照样绿**（MUT-I 两轮 BUILD SUCCESSFUL）。
  >   这就是「假绿锁形态⑤：锁压根没碰那条路径」。
  > - **`breakerFor` 缓存键漏了变体**：只按 `profile` 缓存，而实例的**类**由
  >   `AbSwitch.inhouseBreak()` 决定，`BoxChapterLayouter` 寿命是整本书 ⇒ 拨开关后仍发旧变体，
  >   **回退阀在缓存命中时静默失效**。已改成 `(profile, 变体)` 双分量。
  >
  > **锁**：`TableColumnWidthRealMeasureTest`（**正向**，取代前身 `TableBreakerStaysSkiaTest`
  > 那把守卫型锁——它的 KDoc 本就写明「一旦有人补上真测量，本锁故意失败，那时改写成两侧都跑的正向锁」）
  > 共 6 把，真测量 4 把（1 真测量 / 2 min-content 连带变真 / 3 无 kern 对文本两侧逐值相等 /
  > 4 含 kern 对 Latin 偏宽不超 3%）+ 接线 2 把（5 重路径 / 7 轻路径，两把缺一不可）。
  > **变异表（实测）**：MUT-I 改 `breakerFor` ⇒ 锁 7 红；MUT-J 删覆写 ⇒ 锁 1/2/3/4 红；
  > MUT-K ×1.05 ⇒ 锁 1/2/3/4 红；MUT-L ×0.95 ⇒ 锁 2/3/4 红。
  > 锁 5/7 在 MUT-J/K/L 下仍绿是**正确分工**（它们测接线不管测量）。
  > 下面保留当时的量级数据与「接线时踩到的坑」作为记录。

  auto 分列的 min 侧只有一个消费点（`NormalFlowLayout.tableCellPref` → `ParagraphBreaker.minContentWidth`）。
  真机 A/B 查明：13 本语料 49 张表**全部**落在 `autoColumnWidths` 三段式的 `avail >= totalMax` 段，
  该段 `w[i] = pref[i]`，**min-content 一次都没被读过**。故 T2d/T2e 的禁则补表在现实版心下不可观测。
  - **量级**（实测，`docs/自建断行引擎-测试计划.md` §T2f）：格级 7 处变化 → 列级 **1** 列 → live **0 列**。
    表要进插值段需版心 ≲ 0.8× 字号量级；即使挤进去，49 张表里也只有 1 列可观测。
  - **对 S3 的影响**：自建断行器的 R1（长串兜底）价值**不来自 min-content 这条路**，
    表格这条线已整条从 S3 验收判据里划掉。
  - **【历史，2026-10-01 记】自建侧根本没有真 `preferredWidth`/`minContentWidth`**：
    `InhouseParagraphBreaker` 未覆写这两个方法，用的是接口默认桩 `段长 x fontSizePx`
    （接口 KDoc 自称「CJK 精确、拉丁偏宽、永不窄于实需」——**永不窄于实需在表格语境下恰恰是坏事**）。
    实测 fs=44.4 / `STSong,serif`：`preferredWidth` CJK 1.000（汉字 advance 恰好 1em，巧合）、
    Latin **2.49x**、URL **2.09x**、混排 **1.95x**；`minContentWidth` Latin 2.61x / URL 2.03x。
    ⇒ **表格侧的正确解锁顺序是「先补真测量，再谈可观测」**；仅让表格落进插值段（fixed 列宽 /
    指定列宽 / 更多列 / 更窄版心）**不足以**安全切换。此前把这条写成「满足其一即可」是错的，已订正。
    **（该前提已于 2026-10-02 解除，见上方销案结论）**
  - **【历史】接线时踩到并已修的坑：重路径的表格度量并不走 `tableBreaker`**。
    重路径用的是 `NormalFlowLayout.buildTableRows` → `tableCellPref` 里**透传**下来的正文断行器
    （`NormalFlowLayout.kt:746`），所以正文一接线，重路径表格列宽就跟着变体走 —— 与轻路径分叉，
    且自建侧那个桩会让 Latin 列宽翻倍。当时的处置是加 `heavyPathBreaker` 把**度量方法**单独钉回 Skia
    （断行仍走变体），并用 `TableBreakerStaysSkiaTest` 双向钉住。
    当时的原则一句话「**变体只该改变行，不该改变列**」**今天已作废** —— 见上方销案结论 ③：
    那个原则是靠两处钉扎维持的，而两处钉扎恰恰是分叉的来源；真正的解法是补真测量 + 合实例。
  - **顺带查清的两处缓存陷阱**（不是 bug，但会让人量到假结论）：
    ① `LayoutParamKey` 不含禁则表身份 ⇒ 改断行规则不改 `paramHash`；
    ② `app/build.gradle.kts:18` 的 `versionCode = 20` 是写死常量、非单调递增构建号，
    故 `PaginationCacheCodec` KDoc 里「换构建号即全量作废」在本工程从未真正发生。
    ⇒ ~~**S3 接线时必须 bump `LAYOUT_VERSION`**，否则线上老用户会静默复用 Skia 断点算出的旧表。~~
    （2026-10-01 追加：整个「记得 bump」这条纪律本身已取消——`LAYOUT_VERSION` 改成引擎源码指纹自动导出。）
    **已消解（2026-10-01 S3 接线实测推翻）**：改为把断行器变体作为字段**进 `LayoutParamKey`**
    （`inhouseBreak`，默认读运行期开关），键即可精确区分两侧，`LAYOUT_VERSION` **不必 bump**。
    该字段用**变长喂入**（只在 true 时追加哨兵字节），于是 `inhouseBreak=false` 那一侧
    的 `paramHash` 与接线前**逐字节相同**（金标准 `908642712`，Python + Kotlin 参考实现 +
    生产 `Crc32` 三方独立算出同值）。原判断错在：以为「断行规则不属于排版参数」；
    实际上换断行器换断点换页切点，它**就是**一个排版参数，只是此前没人把它当成参数看待。

- **【2026-10-01 变更】断行器变体默认改成 on：自建断行器成为生产主路径，Skia 降级为回退阀**：
  - **动机**：替换 Skia 断行。个人项目、无既有用户，故「老用户缓存全量作废」这条成本归零。
  - **改动面**：只改 `AbSwitch` 里一处默认值（`DEFAULT_ON_SWITCHES = setOf("inhouseBreak")`）。
    `bodyParagraphBreaker` 与 `LayoutParamKey.fromProfile` **一行未动**——它们本来就读同一个开关，
    这正是当初收成单源工厂的收益：换默认只改一个常量，不可能出现「键按一侧算、断行按另一侧跑」。
  - **连带必须做的两件事**（不做会静默出错，均已补）：
    ① **`apply()` 补上「关」的方向**。原本具名开关只有「加」没有「减」（`=1` 才进集合）；
    默认 off 时「关」等于默认、不写就够用，默认 on 之后不补则 `ab="inhouseBreak=0"`
    会被**静默忽略** —— 回退阀当场变成**单向门**，而它存在的全部理由是「不用回滚安装包」。
    现改为三态表：`显式关 > 显式开 > 默认开`（`isOn` 里 `in off` 分支必须排在 `in on` 之前）。
    ② **测试夹具两侧都要写显式值**。`resetForTest()` 现在回的是**各自默认值**（= on），
    只靠 reset 取「Skia 那一臂」会拿到自建，于是 Parameterized 锁的 skia 臂
    静默退化成「自建跑两遍」（`DrawLineBuilderTest` 38 个用例实测仍 19+19 分两侧，
    靠的就是改成显式 `inhouseBreak=0/1`）。
  - **锁的语义整体翻转**（不是新增，是改守的东西）：
    `DefaultSideIsUnchangedTest` 原守「默认侧 = 接线前」，那条性质**整体消失**了
    （默认侧换引擎了）；改为守**回退侧**——`inhouseBreak=0` 那条路必须与接线前的裸
    `SkiaParagraphBreaker` 逐值相同。`LayoutParamKeyTest` 的金标准 `908642712`
    同样改为钉 `inhouseBreak=false` 那一腿，且构造点**显式写 `false`**：
    靠字段默认值的话，锁会跟着默认值一起漂，锁还在但守着的东西已经变了。
  - **未验证项（重要）**：三道闸门（行数公平性 ≥95%、总行数比值 ∈[0.95,1.05]、
    章级 prepare ≤1.5x）与真机 A/B **仍未实测**。当前「可上线」的依据是
    **1110 个单测全绿 + 回退侧逐值一致的锁 + 两侧表格列宽一致的锁**，不是闸门数据。
    真机一旦量到闸门不达标，先 `am start ... --es ab "inhouseBreak=0"` 退回。
  - **回退命令**：`adb shell am start -n orilumn.reader/.MainActivity --es ab "inhouseBreak=0"`

- ~~开书被调两次~~ **误判，非 bug（2026-09-29 记 → 2026-09-30 查清）**：原以为 `open ok chapters=0`
  后 140ms–900ms 又一次 `open: chapters=N` 是 `openBookEngine` 并发重入。**查清：每次开书只调一次**，
  那两行是同一次开书的两个阶段，且 `chapters=0` 恒为 0。
  - **计数证据**（10 次开书会话，`日志_20260930.1.txt` + `日志_20260930.txt`）：`ReaderActivity onCreate`
    10 次、`open ok` 10 行、`open: chapters=` 10 行，三者严格 1:1:1；`viewport changed` **0** 次。
    真重入必然出现某会话两行 `open ok`，一次都没有。且 10 行 `chapters=` **全是 0**。
  - **两行的真实来源**（都显示 `[Orilumn.Reader]`，因 `ReaderActivity.kt:250` 把自己的 `TAG` 当第 4 个
    位置参数传进 `BookDocumentController`，覆盖了默认 `Orilumn.Engine`；两行又都以 "open" 开头，故像两次）：
    ① `ReaderActivity.kt:271` `open ok` = 引擎容器建好；② `BookDocumentController.kt:418` `open: chapters=N`
    = 书真正解析，走 `ReaderScreen.kt:155` `push("open")` → `TabletReaderHost.kt:68` → `controller.open()`。
  - **`chapters=0` 不是"解析出空书"**：`chapterCount` 是 `book?.spine?.size ?: 0`（`BookDocumentController.kt:382`），
    `book` 要到 `open()` 内部 :405 才赋值 → 记这行时书还没解析，**架构上恒为 0**。文案有误导性，代码无问题。
  - **`external BUSY-DROP` 不是重入证据**：是 `LaunchedEffect(externalPos)`（`ReaderScreen.kt:251`）撞上
    在途的 `push("open")`（.019 持锁到 .539 `open done`）——`AnchorFunnel` 设计内行为（不排队、记 BUSY-DROP），
    `open` 侧另有 300ms 重试兜底；开书时 `externalPos` 本就是 null，丢了不丢东西。
  - **重入口子理论存在、实测未开**：`LaunchedEffect(pxW, pxH)` 的 guard 读 `engine == null`，而 `engine` 是
    `mutableStateOf`（`ReaderActivity.kt:102`）、赋值在 `withContext(IO)` 之后；IO 窗口最长 1.65s
    （12:32:33.486 → 12:32:35.137）内 `engine` 确为 null，若 `pxW/pxH` 变化会重入。但 `viewport changed` 为 0、
    `open ok` 数 = 会话数，**该窗口从未被触发**。若日后真要防，加 in-flight marker 即可（当前无证据支持改动）。

- **重路径级联约 1s/书待优化（2026-09-29 记，JVM 实测；拿设备数据再定做不做）**：重 `prepare` 全书
  ~8s 中级联（匹配+求值）占 ~1s（1.8ms/章），盒几何+塑形占 ~7s（归位正确，不动）。1s 不在翻页/跳转
  热路径（那两条走轻路径），出现位置：(1) 小章前台（开书/目录跳转/关面板重排的 SMALL-FULL，
  单章 100–300ms 含塑形）；(2) B2 整书后台（分摊，不阻塞）；(3) 同参数二次命中 `prepareResult`
  缓存，零成本。候选：规则索引（匹配加速，不动语义）、作者侧烘焙（`ComputedStyle` 血统拆分，
  大改，P4 已取消过一次）。**动工门槛**：先上设备量小章跳转/关面板两条真机耗时，1s 中有多大
  比例落在用户等待线上，再定做哪个；落盘口径见 `docs/调试日志与分页跟踪.md`（`relayout … SMALL-FULL`
  的 `fullLayout=` 分段）。

- **轻重双路等价实现收敛（2026-09-29 记，排版稳定后处理）**：轻 `computeStructure` 与重 `prepare` 各自贴各自的数据表示（`MarkupElement` vs `LayoutBox`），三处“同义双实现”并存：`hidden` 判定（懒 `resolveHidden` vs 表查 `displayNone`）、叶枚举与 charStarts（`styledCharAdvance` vs 盒 `textLength`，P1-2 称同式，`IncrementalReplayEquivalenceProbeTest` 锁等价）、属主映射（轻 map vs 重盒 flag）。phase-1 生成内容已收敛到 `genPhase1` 单源（`fix/scheduling`）。彻底统一须先统一表示层（`ComputedStyle` 按属性血统拆分：作者/UA 侧烘焙 + reader 侧 overlay），动级联表示层，单列大项；收敛前任何改动必须双路同改 + 等价测试。**已撞出的具体实例见下条「容器 float 在轻路径不生效」**（轻 `computeFloatLeads` 只对叶注册 float、重路径递归能处理容器 float）。

- **容器 float 在轻路径不生效（2026-09-29 记，R26 查 `anyFloat` 时撞出；既有缺口，非本轮引入）**：
  `<div style="float:left"><p>文字</p></div>` 在轻路径（临时表/增量）上环绕不生效，要等磁盘表就绪才正确。
  **取证**：新写 `LightFloatLeadTest`（6 例）时撞到；把 R26 的 `anyFloat` 改动（`07f6666` 之前）取回来重跑同一组测试，
  **6 例中同样 5 例过、同样 1 例挂** —— 与本轮改动无关，是原本就有的缺口。
  **根因**：`enumerateBlockLeaves` 只把**叶**放进 `leaves`，`<div>` 自己不是叶（它有块级子节点），
  叶表里根本没有这个 float；`computeFloatLeads` 的前向透传只对叶注册 float，祖先链只 `preClear(clearSide)`、
  不注册祖先自身的 float。重路径的递归能处理（`P6aFloatTest` 走的正是重路径），**故两路不一致**——
  属文首「轻重双路等价实现收敛」的同类实例。
  **与 `anyFloat` 无关**：标志的检测口径（`computeStructure` 里非 `#text` 叶取自身样式、`#text` 叶取父级）
  与原 `blockStyleFor` 扫描在非 `#text` 叶上是**同一个表达式**、在 `#text` 叶上都取父级，两者等价；
  它既没制造也没掩盖该缺口。改动前后 5 例同样通过，即 R26 对有 float 的书**行为中性**。
  **当前状态**：`LightFloatLeadTest` 以 `KNOWN GAP` 命名**锁定现状**（断言两表全 null），
  补上容器 float 时该测试会失败并提醒更新。
  **待办**（**行为变更，不在性能这轮范围**）：让前向透传在祖先链上注册 float（而非只 `preClear`），
  须同时确认与重路径递归同式 + 磁盘表/临时表两路等价（`LAYOUT_VERSION` 自 2026-10-01 起随源码自动变，不必手改）。

- **桌面真背光后续（2026-09-27，macOS 先行落地）**：macOS DDC/CI 已通（`desktopApp …/brightness/`：`DisplayBrightness` 接口 + `DdcPackets` + `MacDisplayBrightness` JNA，真机读写闭环；>0 下发硬件150ms防抖、≤0 纯遮罩、跟随系统不碰硬件；滑块按探测切量程 -50~100 / -50~0）。**Win/Linux 空实现位**：Windows 接 Dxva2（`GetPhysicalMonitors`→`SetMonitorBrightness`，JNA）、Linux 接 ddcutil（/dev/i2c，需 i2c 组权限），同接口各自实现；显示器插拔重探（当前启动探一次）后续补。

- **项目更名 orilumn → orilumn（✅ 已办，2026-09-27）**：包名 `orilumn.reader` 全量、数据根 `~/.orilumn/`，条目退役（原改名清单删除）。

- **目录面板标题高亮定位**：目标是"仅高亮当前页内的标题、不在页内的不亮"（高亮下边框已去掉）。当前页内标题 id 集合逻辑已正确（有单测），但具体书籍上章内子标题仍常高亮不到——疑似 TOC 条目的 fragment 与正文标题元素 id 不一致，无法建立"目录项 ↔ 页内 char"的命中。**推迟到排版稳定后再处理**。

- **阅读定位不稳定（整改 D）**：**根因已在 C1-2 收编，契约落码，仅剩设备实测确认**。原症状："未翻页重开"会位移（保存当前页 locator.charStart，重开定位同 char 可能落到不同页），两个根因：(1) 大章临时表 vs 磁盘表 char 定位不一致；(2) 临时转正 `onBackgroundCanonicalReady` 与 `scheduleSave` 竞态存下旧临时页 char。
  - **已落地（2026-09-19，C1-2/C1-3）**：char 语义统一为 **canonical 磁盘表权威**——`onSaveProgress` 读 displayed slice 落盘（2026-09-26 修正：存档不再先 `finalizeOnLeave`，存档不是离开，杀活 temp 会话会锁死大章；finalize 只属于离开路径）；`ensurePageRangeShaped` 的 seam 诊断转正为 canary（新窗口贴合旧窗口时共享边界必连续，[BookDocumentController.kt:494](app/src/main/java/com/orilumns/engine/BookDocumentController.kt)），temp→canonical 交接再加 handoff canary（`:1225`），回归会大声失败而非静默漂移。
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
     ⚠️ **旧“已办”结论错误（2026-09-24 目验推翻）**：此前记为过时条目（称 F4c 后已是
     列表首行），但平板上「跟随原书」仍被单独拎在面板顶部，不在字体列表内。待重查
     安卓入口装配（`AndroidReaderSettingsPanel.TextPage` 自持列表 vs 桌面行形态）。
     ✅ **已办（设置面板收敛，2026-09-24）**：`AndroidReaderSettingsPanel` 已删，平板改调共享 `ReaderSettingsPanel`，下钻与桌面同一 `buildFontRows`，首行恒为跟随原书；旧面板不复存在，无第二套列表可把跟随原书拎出。待真机复验一次确认。
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
- **状态栏按键焦点导航（桌面键盘，待办）**：状态栏打开时左右键不再翻页（`ReaderScreen` 已放行不消费，见代码 TODO），后续补栏按键之间的焦点切换（左/右移焦、回车触发、Esc 回阅读面），与面板内 `PanelNav` 口径对齐。

- **阅读目验问题清单（2026-09-24，待办；其中 1-3 已于 `fix/legacy-patches` 闭环）**：
  1. ✅ 打开目录时，应定位到当前阅读位置对应的目录项，同时作为桌面侧键盘移动的起始位置。
     （共享 `resolveTocCurrentRow`：当页标题 id 命中取文档序最末子标题、无命中回退章首；
     打开滚动到该行 + 桌面 `nav.activeIdx` 落该行；桌面侧补 `currentFragments` 接线，
     此前桌面一直空集只能定位章首。）
  2. ✅ 目录默认折叠，仅自动展开当前章的目录；用户展开新章目录时，其余所有章目录折叠。
     （共享 `initialTocCollapsed`/`toggleTocCollapsed`：每次打开重置为仅当前顶层子树展开，
     顶层展开手风琴、嵌套切换只管自己；"章"= depth 0 顶层节点。）
  3. ✅ 目录折叠三角形太小。（点击区 22dp→32dp、字号 11sp→14sp，字形 Box 居中；双端同改。）
  4. ✅ 平板侧「跟随原书」已随设置面板收敛闭环（单共享实现，首行恒为跟随原书；待真机复验）。
  5. ✅ 分区字样已删，改为行首来源竖标（2026-09-27）：导入/系统行按展示名归并单列，行首竖标“系统/导入/隐藏”；跨来源同名各占一行。
  6. ✅ 按最终显示的名字排序（2026-09-27）：展示名归并排序（两组各自有序归并）。
  7. ✅ 字重选择已落地（2026-09-27）：`WeightPicker` 按族选档（多字重行首入口进档位页）；连续滑块未做，如需再立项。
  8. 阅读主题中的缃色改掉：与象牙白接近，且不是真正的缃色 #F0C239（该色不适合阅读）。
  9. ✅ macOS 真背光已通（2026-09-27）：DDC/CI 下发，滑块 -50~100（>0 硬件，≤0 遮罩）；无 DDC 显示器钳 -50~0。
  10. 预设管理面板未完整实现。
   14. ✅ 已验证可行并落地（2026-09-27，见 9）：macOS 经 IOKit I2C 发 DDC/CI；Win（Dxva2）/Linux（ddcutil）空实现位见文首“桌面真背光后续”。
  11. 封面等比例缩放改为封面拉伸全屏。
  12. 两页内容不连续、翻页乱跳等问题。
  - 章内 TEMP 连翻跳页（2026-09-27 修，待合）：前向塑形从水位改以后继原点为准（`tempResumeAfter`），
    追加前相接校验（`tempPagesTile` ±3），失配按需修复收敛（`repairTempForwardGap`），窗口裁剪后水位
    倒回新尾。真机验证（`日志_20260927.txt` 02:32，`TempBurstFlipProbeTest`+全套 206 绿）：30 页连翻
    单调无跳，全文件零 `fwd-gap`/`fwd-repair`/`origin-drift`。
  - 跨章来回跳（✅ 已办，2026-09-27）：UI 侧 `AnchorFunnel` 已合入，引擎侧 in-flight 同目标去重已合入；显示状态就是
    `ReaderScreen.openPos` 一个变量，原先每次点按起独立协程调 `host.adjacent`，回来**不分新旧无条件
    覆盖**（旧 `ReaderScreen.kt:177`），谁后完成谁赢。边界处连点 → N 个跨章落地并发（各 ~1s）按完成
    顺序贴 UI，旧目标覆盖新位置。
    证据：11.411 七个 `landed ch=16` 扎堆，11.809 三个过期 `landed ch=15` 把显示拽回，
    11.968/12.209/12.937 无点按自动跳。附带真 bug：每次点按按发出瞬间的旧页算源，慢翻页时
    10 次点按全从同一页出发（全落同一页），不是 10→11→…→20。
    立规矩：`openPos` 只接受锚页事件（翻页/跳转/开书/改参/旋转/外部落位/开链接），过期结果是已死的
    锚页事件，无权覆盖。
    落地情况：`AnchorFunnel`（shared-ui 用户层）——6 个写入点收口为唯一通道，
    规矩只有一条：try-lock，锁占用期间后到的点按直接放弃（`BUSY-DROP` 落盘可查），不排队、不等帧、
    不计时——这次实测锁占了约 1s（落地构建实际耗时），没有任何时间阈值；
    解锁后的新动作按最新位置重取源，10 次连点即 10→11→…→20 的链式推进。代价写在明处：锁占用期间
    的点按按了白按。`AnchorFunnelTest` 6 绿、shared-ui 全套 76 绿、app 编译过。
    引擎侧同目标在途去重已合入（重复构建不再互相覆盖）。
  - 存档 500ms 批处理（2026-09-27 记，**待复核**）：`markPositionChanged` 每次提交取消旧 `saveJob`、
    500ms 后写一次 Room（复刻 legacy scheduleSave）。连翻只存落定页，显示层零影响；漏斗落地后存的
    必是显示过的位置。复核点：500ms 值是否合适、杀进程丢进度窗口、与跳转/旋转等重排路径的竞态。
  13. 目录跳转偏差已修（2026-09-27：桌面透传 fragment + 引擎排版字符流锚点）；点击无响应（失败静默吞）待办。
