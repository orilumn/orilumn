# 补全 EPUB2/3 标签/属性/CSS 的完整路线图

> 本路线图自包含：后续新会话按此执行，无需依赖原会话上下文。
> 配套基线：`docs/engine-html-css-capability.md`（epub2/3 vs 引擎 标签/属性/CSS 对照审计表）。
> 依赖总览：**P0 解析层（硬前置）→ P1 box 层 → P2 渲染层（相对独立，可按迫切度并行）**。

---

## 依赖关系（一句话）
- **P0 是后续一切的硬前置**：解析白名单小于 box 块集，导致 `article/aside/nav/dl/dt/dd/address/hr` 在解析层就被
  de-shell，box 层根本见不到；`href/width/height/lang/epub:type` 属性解析时被丢弃。不先做 P0，P1/P2 对这些都无效。
- **P1 依赖 P0**（要能"看见"元素），做到 box 层接住"非文本可替换块 + display 动态化"。
- **P2 各渲染项相互独立**（text-align / 等宽 / 表格2D / 链接导航），无硬依赖，可按需求度先后。

---

## P0 — 解析层（低风险，先做，一次装机无回归）

### P0-A 解析白名单对齐
**目标**：`HtmlTreeConverter.BLOCK_TAGS` 至少等于 box 权威集 `NormalFlowLayout.BLOCK_TAGS`，使所有 box 已知块在解析层被保留为语义节点，不被 de-shell。
- 文件：`app/src/main/java/com/orilumns/engine/html/HtmlTreeConverter.kt`（`BLOCK_TAGS`，~L156-159）。
- 把 `article/aside/nav/dl/dt/dd/address/hr`（以及任何 box 需要的其它块）加入解析 block 白名单。
- 验收：含上述标签的章节，box 层能收到对应块标签并参与块布局（此前被剥壳成纯文本）。
- 回归：`DensityScaleTest`/既有 Epub 解析/布局测试不回归；普通书只在更少标签被剥壳，行为应只增不减。

### P0-B 属性保留
**目标**：把常被丢弃的关键属性保留进 `MarkupElement.attrs`，供 box/渲染/导航使用。
- 文件：`HtmlTreeConverter.kt` 的 `attribs(el, ...)` 白名单（~L102-105）。
- 需增加保留：`a` 的 `href`（现 INLINE_TAGS 分支只留 style/title/class/id）；`img` 的 `width/height`；
  全局 `lang/xml:lang`、`dir`；`epub:type`、`role`、`aria-*`、`data-*`（可仅捕获几个高价值或通用前缀）。
- 验收：`<a href="…">`/`<img width height …>` 解析后 attrs 存在；单测断言。
- 风险：href/width/height 是 P1 (img) 与 P2 (链接) 的前置。

---

## P1 — Box 模型层（核心、高风险，依赖 P0）

> 基本原则（历史事故教训）：**重路径（整章 cascade）与轻路径（lazy-cascade 廉价结构）必须产出完全相同的
> 块集合与 `globalCharStarts`**，否则分页/字符定位漂移。每个改动必须过双路一致性测试。

### P1-A display 解析 + 块级判定谓词（纯重构，行为中立）
- `ComputedStyle.kt` 增加 `val displayBlock: Boolean = false`（`display: block/list-item/flex/grid/table*` 为 true）。
- `StyleComputer.computeStyle`（~L91）加 `displayBlock = w["display"]?.let{ it in DISPLAY_BLOCK_VALUES } ?: false`。
  新增 `resolveDisplayOnly(el, cache)`（仅沿祖先 parse 赢的 `display`，跳过其它属性）+ `hasDisplayDeclaration()`
  （sheet 摄入时扫描任一 `display` 声明）。
- `NormalFlowLayout`：把 `isBlock` 从静态 `BLOCK_TAGS` 提升为注入谓词
  `isBlock(el) = defaultBlock(el) || (displayOf(el)?.displayBlock == true)`，其中
  `defaultBlock(el) = el.tag in BOX_BLOCK_TAGS || replaceableBlock(el)`，
  `BOX_BLOCK_TAGS =`当前权威 `NormalFlowLayout.BLOCK_TAGS`（**不要**与 CssLayouter/HtmlTreeConverter 标签集做并集，
  避免误判 de-shell 包装节点），`replaceableBlock(el) = el.tag == "img"`（P1-B 才启用）。
  `buildBoxTree`/`enumerateBlockLeaves`/`absorbedText` 签名加 `classify`；重路径由 `styleMap` 提供，轻路径传
  `{ el -> defaultBlock(el) || engine.resolveDisplayOnly(el,cache).displayBlock }`（**仅当 hasDisplayDeclaration() 时才传**，
  否则默认走`defaultBlock`，热路径零开销）。
- 新增 `BoxPathConsistencyTest`：对现行语料断言重/轻路径 `leaves` 序列 + `globalCharStarts` **完全相等**；
  对 `<span style="display:block">` fixture 断言双路块集一致。此提交必须对既有书行为字节级中立。

### P1-B img 可替换块（用户指定最迫切）
- `LayoutBox.kt` 增加 `val replaceableHeight: Int = 0`（img 仍是 leaf：无 childBoxes、ranges 空、无文本、带像素高）。
- **字符步长单点**：`NormalFlowLayout.leafCharAdvance(el): Long = if (replaceableBlock(el)) 1 else el.textLength`。
  重路径 img `textLength=1`；轻路径 `LightPrepare.block` 用它；两处 `accumulateCharStarts` 调用点（`buildPrepareResult`、
  `prepareLight`）都以同一函数为准 → img 占一格 `[k,k+1)`，`blockIndexForChar`/`backfillBlockRanges` 逻辑不变。
- **尺寸 layout 期定**：`contentWidth = innerBreakWidth(style, widthPx)`；`ratio` 取 style 的 width/height 或
  `img` attrs（P0-B 已保留 width/height），缺省常量（如 1.6）；`replaceableHeight = (contentWidth/ratio).roundToInt()`。
- **热区改写**（"假设叶必有文本"处；文本叶路径全不动）：
  - `NormalFlowLayout.emit`（~L83）：可替换分支产单条 `FlowedLine(st.char, st.char+1, contentY, contentY+H, paragraphStart=true)`。
  - `buildBoxTree`（~L143）叶分支：可替换分支直接构造 `textLength=1` 的 leaf，不调 breaker。
  - `absorbedText`（~L171）：块判定换谓词（img/display:block 被 skip）。
  - 行重建三处：`rebuildLinesFromShapes` / `rebuildLocalLines` / `shapeBlock+tempShape+blockHeight`（`BoxChapterLayouter`）
    各加 img 分支；`blockHeight(shape)` 对 img 返回 replaceableHeight。
  - `ParagraphShapes.shapeOf`/`ParagraphShape`：img 返回合成单行 shape（lineCount=1，bottom=replaceableHeight），
    或加 `isReplaceable`/`replaceableHeight` 标记。
  - 绘制：`BoxDrawableLayout`/`PartialDrawableLayout.drawPageSlice` 对 img 分支绘制（占位/位图），不走
    `staticLayout.draw`；加 `lineCount<=0`/null 守卫防 NPE。
- **分页**：img 贡献单行高度 H，`Paginator` 放不下整行则整行后移（不劈 img）。
- `BOX_BLOCK_TAGS += img`；`HtmlTreeConverter` img 保留 width/height attrs。
- **必须 bump `PaginationCacheStore.LAYOUT_VERSION`**（img 改变 globalCharStarts/block 布局，老磁盘表错位）。
- 测试：文本+img 交错章节断言重/轻 `textLength` 序列、`globalCharStarts` 相等、`backfillBlockRanges` 在 img 边界切页正确、
  `Paginator` 不劈 img、`emit` 的 `contentBottom` == `rebuildLocalLines`/`AnchorPagePacker.blockHeight` 的 img 底。

---

## P2 — 渲染层（相互独立，按迫切度）

### P2-A text-align（小、独立、修复审计"解析未生效"）
- `ParagraphShapes`（~L66）把硬编码 `setAlignment(Layout.Alignment.ALIGN_NORMAL)` 改为按 `rootStyle.textAlign`
  映射（LEFT/CENTER/RIGHT/JUSTIFY）。文件：`ParagraphShapes.kt`。

### P2-B 等宽字体 font-family（小、独立）
- `TypographicProfile` 已有 `fontBody/fontTitle/fontCode` 钩子；为 `pre/code`（以及 `font-family: monospace` 声明）
  选 `Typeface.MONOSPACE`。文件：`ParagraphShapes`/`StaticLayoutBreaker`/`TypographicProfile`。

### P2-C 表格 2D 布局（较大、独立）
- 现 `tableFallback` 拍平为 `<br>` 文本。要 2D 网格：改为对 `table/tr/td/th` 建表结构 box，按列宽布局单元格，
  `colspan/rowspan` 支持（可选）。独立于 P1，但依赖 P0-A（td/th 已保留？需解析白名单容纳）。

### P2-D a 超链接/锚点导航（依赖 P0-B 的 href + tap 命中）
- 解析已保留 `<a>` 为 inline，P0-B 补 `href` → 渲染可着色/下划线（已由计算样式支持）；
  tap 命中（`getOffsetForPosition`/`getLineForOffset`）+ href→目标章节锚点（`PageAnchor`）导航。独立实现。

---

## 每阶段出口（务必交付）
1. 编译 `./gradlew :app:assembleDebug` + 单测（新增测试 + 既有不回归）。
2. 装机验证：对应示例章节视觉/定位正确；普通书无回归。
3. 独立提交，遵守语义化 commit message 风格（见 `git log`）。

## 关键不变式（改动块分类/叶推进时必守）
- 重/轻路径 `globalCharStarts` 恒等（`BoxPathConsistencyTest`）。
- img/display:block 步长单一来源 `leafCharAdvance`。
- 磁盘分页表改动后 bump `PaginationCacheStore.LAYOUT_VERSION`。
- 解析白名单(box) / box 块集 / 渲染三者对块的定义一致（P0-A 兜底双向对齐）。

## 追踪清单（从 P0/P1/P2 逐项勾选）
- [x] P0-A 解析白名单对齐 box 块集
- [x] P0-B 属性保留（href/width/height/lang/epub:type…）
- [x] P1-A display 解析 + 块级判定谓词（+ BoxPathConsistencyTest）
- [x] P1-B img 可替换块（+ bump LAYOUT_VERSION）
- [x] P2-A text-align 应用
- [x] P2-B 等宽 font-family
- [x] P2-C 表格 2D
- [ ] P2-D a 链接/锚点导航

> 顶优先建议顺序：P0 一批 → P1-A → P1-B(img) → P2-A(text-align) / P2-B(monospace)。