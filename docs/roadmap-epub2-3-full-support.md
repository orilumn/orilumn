# EPUB2/3 标签·属性·样式 全量支持路线图

> **目标**：本次改善完成后，引擎在 **标签（元素）、属性、CSS 样式** 三个维度完整满足 EPUB 2.0.1（OPS）与 EPUB 3.x（Content Documents）的阅读系统要求，并以可复验的测试矩阵固化。
>
> 本文件自包含：后续新会话按此执行，无需依赖原会话上下文。
> 承接：`docs/roadmap-epub2-3-html-css.md`（旧 P0–P2 路线图，绝大多数条目已完成）、`docs/engine-html-css-capability.md`（734ad47 期的旧审计，**以本文为准**）。
> 依赖总览：**P0 解析/标签与属性 → P1 样式计算层 → P2 字体与样式表（EPUB3 硬性 MUST）→ P3 绘制与生成内容 → P4 布局扩展 → P5 验收固化**。

---

## 0. 范围定义（"完全支持"的边界）

目标是**可重排（reflowable）文本阅读器**层面的完整支持。明确**不在范围内**（不构成 "完全支持" 缺口，文档写明即可）：

- 脚本（scripting）与表单（form）交互；
- 音视频、`canvas`、`iframe`、`object/embed` 的媒体渲染；
- 固定布局（fixed-layout / pre-paginated）；
- **实质性 RTL／垂直书写方向**（`-epub-writing-mode`、`direction` 的 LTR 之外）——单列里程碑，默认为后续；
- `flex`/`grid` 的真实布局算法（本引擎按 `display:block` 语义降级处理）。

范围内的"完全支持"定义（**完成判定**，见 §6）：
- EPUB2 全量标签、EPUB3 全量标签，每个都有语义节点 + UA 默认样式，无一被 de-shell；
- 属性白名单保留并在可能处消费（HTML4 表示型属性进级联）；
- CSS：OPS 2.0 必选属性集全部生效 + EPUB3 要求的 CSS2.1 基线、CSS3 模块、`@font-face`、`-epub-*` 前缀属性（缩写形式）。

---

## 1. 标准基线

| 维度 | EPUB 2.0.1（OPS） | EPUB 3.x（Content Documents） |
|---|---|---|
| 标签 | XHTML 1.1 子集（OPS 2.0 §4.5 元素集） | HTML5 子集（流内容 + 结构语义） |
| 属性 | HTML4 属性 + 少数扩展 | HTML5 全局属性 + `epub:type`/`role`/`aria-*`/`data-*` |
| CSS | OPS 2.0 §4.6 必选属性表（CSS 2.0 子集） | CSS 2.1 基线 + CSS3 模块；**MUST `@font-face`（ttf/otf/woff/woff2）**；SHOULD `-epub-*` 前缀属性 |
| 关键差异 | 表示型属性（`align`/`border`/`cellpadding`…）大量见于书内 | 语义优先，样式走 CSS |

EPUB2 是 EPUB3 的子集基础；引擎以"一张矩阵、两列标注"方式统一跟踪。

---

## 2. 标签（元素）审计矩阵

图例：**epub2**＝OPS 2.0 要求；**epub3**＝HTML5 Content Documents 要求；**现解析**＝`HtmlTreeConverter` 是否保留语义节点；**现效果**＝布局/绘制的实际行为。

### 2.1 已达标（保留语义 + 有渲染效果）

| 元素 | epub2 | epub3 | 现解析 | 现效果 |
|---|---|---|---|---|
| `p` `div` `h1–h6` `blockquote` `pre` `section` `article` `aside` `header` `footer` `nav` | p/div/h/NQ | 全 | 块 | box 块（margin/行距/heading 缩放） |
| `ul` `ol` `li` | ✓ | ✓ | 块 | box 块 + 标记（`ListMarkers`：disc/circle/square；十进制/罗马/字母 + `start/reversed/type`） |
| `dl` `dt` `dd` | ✓ | ✓ | 块 | box 块（UA dd 缩进） |
| `address` `hr` | ✓ | ✓ | 块 | box 块（hr 有 UA 样式预留） |
| `table` `thead` `tbody` `tfoot` `tr` `td` `th` `caption` | ✓ | ✓ | 保留结构 | 2D 网格 + `colspan/rowspan` 布局 |
| `strong` `b` `em` `i` `u` `a` `code` `kbd` `samp` `tt` `span` `sub` `sup` `q` `small` `big` | 全 | 全 | 行内 | 行内 run（粗/斜/下划线/等宽/上下标**字号**/small·big 缩放）；`a` 保留 `href`（导航待 P2-D） |
| `br` | ✓ | ✓ | 保留 | 换行 |
| `img` | ✓ | ✓ | 保留 | 可替换块（`replaceableHeight`，含 `width/height` 属性） |
| `figure` `figcaption` | EPUB2 无 | ✓ | 块 | box 块 |

### 2.2 已补齐（P0-A 已交付：零 de-shell，语义保留 + UA 兜底）

| 元素 | epub2 | epub3 | 现解析 | 现效果 |
|---|---|---|---|---|
| `s` `del` | ✓ | ✓ | 行内 | 行内 span + UA `text-decoration: line-through` |
| `ins` | ✓ | ✓ | 行内 | 行内 span + UA `text-decoration: underline` |
| `abbr` `dfn` | ✓ | ✓ | 行内 | 行内 span（`title` 已保留） |
| `cite` `var` | ✓ | ✓ | 行内 | 行内 span + UA `font-style: italic` |
| `q` | ✓ | ✓ | 行内 | 行内 span + 生成引号（P3-c `quotes`/`content` + UA `q::before/after` 补丁） |
| `acronym` | ✓ | ✗ | 行内 | 行内 span（同 abbr） |
| `mark` `time` `data` | EPUB2 无 | ✓ | 行内 | 行内 span + UA `mark` 高亮背景 |
| `ruby` `rt` `rp` | ✓ | ✓ | 行内 | 行内 + `rp{display:none}` + `rt` 0.6em 上标（P4-b；叠排后续） |
| `bdi` `bdo` `wbr` | EPUB2 无 | ✓ | 行内 | 行内 span（`wbr` 断行点；bidi 隔离已落，RTL 流后续） |
| `main` `hgroup` | EPUB2 无 | ✓ | 块 | box 块 |
| `colgroup` `col` | ✓ | ✓ | 表格列元数据 | 列宽参与（直通布局，绘制待列模型细化） |

> 规则（P0）：**任何 EPUB2/3 标签不得被 de-shell**；无法实现其语义的标签以最接近的块/行内语义降级 + UA 兜底，并写明降级原因。"de-shell" 仅保留给明确不在范围内的流内容（如 `script`/`style`/`head` 类）。

---

## 3. 属性审计矩阵

### 3.1 已保留（现状）

| 属性 | 应用于 | epub2 | epub3 | 现解析 | 现使用 |
|---|---|---|---|---|---|
| `id` `class` `style` | 全局 | ✓ | ✓ | 保留 | CSS 匹配/内联样式 |
| `lang`/`xml:lang` `dir` | 全局 | ✓ | ✓ | 保留（dir 未消费） | lang 供字体/排版（dir 待 RTL 里程碑） |
| `epub:type` `role` `aria-*` `data-*` | 全局 | — | ✓ | 保留 | 结构语义（拼音/旁注类 epub:type 后续消费） |
| `title` | 全局 | ✓ | ✓ | 保留 | 未用（abbr/dfn 提示后续） |
| `align` | 块/表格 | ✓ | EPUB3 弃 | 保留 | 未消费 → 进级联 `text-align`（P1） |
| `href` | `a` | ✓ | ✓ | 保留 | 链接/锚导航（P2-D） |
| `src` `alt` `width` `height` | `img` | ✓ | ✓ | 保留 | width/height→布局；src 待背景/位图；alt 备用 |
| `colspan` `rowspan` | `td/th` | ✓ | ✓ | 保留 | 2D 表格网格 |
| `start` `reversed` `type` | `ol` | ✓ | ✓ | 保留 | `ListMarkers` 编号 |

### 3.2 本次补齐（P0-B）

| 属性 | 应用于 | 目标处理 |
|---|---|---|
| `cite` | `blockquote`/`q` | 保留；暂不渲染 |
| `scope` `headers` | `th/td` | 保留（可访问性/语义） |
| `summary` | `table` | 保留 |
| `value` | `li` | 保留 → `ol` 起始改值（`ListMarkers`） |
| `datetime` | `time`/`ins`/`del` | 保留 |
| `srcset` `sizes` | `img`（EPUB3） | 保留（按版心取最适；可选） |
| `border` | `table`/`img` | 进级联 `border`（同 width/height 机制） |
| `cellpadding` `cellspacing` | `table` | 进级联（`padding`/`border-spacing`，P1 后） |
| `bgcolor` | 块/表格 | 进级联 `background-color` |
| `background` | 块/表格 | 仅保留；下沉为低层级 `background-image`（P3，复用 img url 解码） |
| `valign` | `td/th` | 进级联 `vertical-align`（P1 后） |
| `nowrap` | `td` | 进级联 `white-space: nowrap`（P1 后） |
| `clear` | `br` | 保留 + 消费：`br clear` 进级联 `clear`（P4-a1），块级 clear 越过悬浮（P4-a2h/a3） |
| `name` | `a` | 保留 + 消费：与 `id` 同为锚点（P4-c1），随链接导航闭环（P4-c2） |

> §3.2 清单全部已交付（P0-B 保留 + 表示属性进级联；`clear`/`background`/`name` 的消费分别在 P4-a/P3-b/P4-c 落定）。

> **关键改造**：`Cascade.htmlAttrOrigin`（`Cascade.kt:134`）目前只抬高 `width`/`height` 两个属性（且注释声称含 `border`）。**P0-B 把全部 HTML4 表示型属性做成"属性 → CSS 低层级声明"映射表**，一招覆盖 EPUB2 书里的大量 `align`/`border`/`cellpadding`/`bgcolor`/`valign`。
>
> ~~**风险提示（历史的坑）**：`Cascade.kt:98` 的注释宣称 `border` 属性被抬高，但代码并未实现~~ ✅ P0 已清理（`PRESENTATION_ATTRS` 真映射 `border`，注释同步）。

---

## 4. CSS 属性审计矩阵

图例：**T1**＝解析+消费（已生效）；**T2**＝解析了/部分生效（静默失效陷阱）；**T3**＝未解析（缺失）。

### 4.1 已达标（T1，勿动或仅微调）

| 属性 | epub2 | epub3 | 状态说明 |
|---|---|---|---|
| `font-size`（px/em/rem/%/unitless） | ✓ | ✓ | 行内 run 各自字号（FontRun.fontSizePx） |
| `font-family`（含栈）`font-weight` `font-style` | ✓ | ✓ | 家庭栈回退、monospace 标志 |
| `line-height`（比值/长度） | ✓ | ✓ | |
| `color` | ✓ | ✓ | 主题色继承 + 显式色 run |
| `text-decoration` | ✓ | ✓ | underline/line-through（bool） |
| `text-indent`（首行缩进） | ✓ | ✓ | 非继承 |
| `text-align` | ✓ | ✓ | LEFT/CENTER/RIGHT/JUSTIFY |
| `margin`/`padding`（简写+四边+逻辑） | ✓ | ✓ | 盒几何 + margin collapse |
| `background-color` `background`（仅色） | ✓ | ✓ | 盒填充（页裁剪） |
| `border`（宽度）`border-color`（单色） | ✓ | ✓ | 4 边实线带 |
| `display`（block/none/list-item/table*） | ✓ | ✓ | 块判定谓词 + `display:none` |
| `width`/`height`/`max-*`/`min-*` | ✓ | ✓ | px 与 % 分离，% 在布局期解 |
| `list-style-type` `list-style-position` | ✓ | ✓ | `ListMarkers` |
| `break-inside/after/before` | ✓(inside) | ✓ | 分页 BreakRule |
| CSS 逻辑属性（`margin-inline-*` 等） | EPUB3 常用 | ✓ | `Cascade.expandLogical` LTR |

### 4.2 解析但未消费 / 半成品（T2，本次必修，P1）

| 属性 | epub2 | epub3 | 现状缺口 |
|---|---|---|---|
| `border-width` `border-*-width` | ✓ | ✓ | ✅ P1：`border-{side}-width` > 边简写 > `border-width`（槽位+thin/medium/thick）全参与 |
| `border-style`（solid/dashed/dotted/none） | ✓ | ✓ | ✅ P1：`BorderStyleEdges` 数据模型，`BoxDrawer` 按边选路径（dashed/dotted 分段，none 跳过；未声明回退 solid） |
| `border-*-color`（四边异色） | ✓ | ✓ | ✅ P1：`BorderColorEdges` 四边独立 + currentColor 默认（文本色，无则 `#ff000000`） |
| `border-radius` | EPUB3 新 | ✓ | ✅ P1：几何落 `ComputedStyle.borderRadius`（px；`%` 盒相关延后）；绘制消费 P3 |
| `page-break-before/after/inside` | ✓ | ✓ | ✅ P1：别名 → 既有 `BreakRule`（`avoid` 含 `avoid-page`） |
| `list-style` 简写 | ✓ | ✓ | ✅ P1：简写拆 type/position（单属性优先） |

### 4.3 未解析（T3，按阶段补齐）

**P1（计算层 + 消费全交付 ✅）**
`letter-spacing`（作者 CSS 消费）· `word-spacing` · `text-transform`（P3-c 消费）· `white-space`（P1-2a 断行语义）· `vertical-align`（P1-2b 基线偏移：sub/super/middle/top/bottom）· `direction`/`unicode-bidi`（隔离已落，RTL 流后续）· `overflow-wrap`/`word-break`（断行）· `box-sizing` · `opacity`（P3-a 合成）· `visibility`（已计算，隐藏消费后续）· `overflow`（已计算，祖先裁剪后续）· `position:relative`（已计算；absolute/fixed 不做）· 表格族 `border-collapse`/`border-spacing`/`caption-side`/`empty-cells`/`table-layout`（P1-2c/d 消费）。

**P2（字体与样式表，全交付 ✅）**
`@font-face`（family/weight/src url+format + IDPF 去混淆 + 先行入池）· `@import`（防环限深 + 媒体条件）· `@media`（全视口求值 P2-c）· `font-variant`（small-caps，P3-c 消费）/`font-stretch`（已计算）。

**P3（绘制与生成内容，全交付 ✅）**
`text-shadow` · `box-shadow` · `text-emphasis`/`-epub-text-emphasis-*` · `background-image`（url + repeat/position + HTML background 下沉）· 生成内容 `content`/`counter-reset`/`counter-increment`/`quotes` + `::before/::after`（`counter-set`/非 li list-item/容器级生成/`background-size` 明确不做）。

**P4（布局扩展，全交付 ✅，v1 口径）**
`float`/`clear`（计算层全量；消费：img 悬浮 + 紧随文本叶环绕 + 块级 clear/容器包容，双路一致；文本浮动/跨容器环绕/并排双浮动/跨窗口悬浮后续）· `ruby`（`rt` 0.6em 上标 + `rp` 隐藏；叠排后续）· 链接锚点（`id`/`name` + `href` 解析 + 双端点按导航）。

**明确不做（写入本文 §0 例外清单）**
`flex`/`grid` 真实布局 · `@page` · `writing-mode` 垂直 · 脚本/交互 `cursor`。

---

## 5. 分阶段实施计划

> 每阶段通用出口（强制）：`./gradlew :app:assembleDebug` + 全量单测；装机验证对应 fixture；独立提交。
> 通用不变式见 §6，动到任何一项都必须过双路一致性测试。

### P0 — 解析与标签/属性（硬前置，低风险）

**P0-A 标签全覆盖**
- 目标：**EPUB2/3 标签零 de-shell**。
- `HtmlTreeConverter.INLINE_TAGS/BLOCK_TAGS` 扩充全量；新增标签按语义归类（块/行内/特殊）。
- 保底分类：`s/del/ins/abbr/dfn/cite/var/acronym/mark/time/data/bdi/bdo/wbr` → 行内；`main/hgroup` → 块；`colgroup/col` → 表格结构；`ruby/rt/rp` → 行内注音（先 rp 隐藏、rt 内联）。
- `ua.css` 为每个新标签写浏览器默认渲染（s/del 删除线、ins 下划线、mark 高亮、small/big 缩放、dfn/cite italic 等）。
- 验收：含全部新标签的 fixture 章节各出语义节点 + 预期 UA 效果；既有书"只增不减"。

**P0-B 属性全覆盖 + 表示型属性进级联**
- `attribs`/`htmlAttrOrigin` 补全 §3.2 清单；建立 `HTML_PRESENTATION_ATTRS → CSS 声明` 映射（align→text-align、border→border、cellpadding→padding、cellspacing→border-spacing、bgcolor→background-color、valign→vertical-align、nowrap→white-space）。**`clear`/`background`/`name` 在 P0 仅保留属性，消费移交后续阶段**（`clear`→CSS `clear` 与浮动同进 P4；`background`→`background-image` 进 P3；`name` 页内锚随链接导航进 P4）。
- 修正 `Cascade.kt:98/120` 注释与实现不符的问题。
- 验收：`<td bgcolor align>`、`<table border cellpadding>` 等 fixture 断言进级联并产生布局/绘制效果；`<br clear>`、`<a name>`、`<table background>` 断言属性保留（消费见对应阶段）。

### P1 — 样式计算层（纯数据处理，低风险，大头）

- 在 `StyleComputer.computeStyle`/`ComputedStyle` 新增 T3 文本类与盒类属性（§4.3 P1 清单）。**新增字段一律带"旧行为默认值"**，未消费前不改变任何既有渲染。
- 修 §4.2 全部 T2 陷阱：
  - `border-width/border-*-width` 完整参与宽度解析；
  - `border-style` 建数据模型（solid/dashed/dotted/none/hidden），`BoxDrawer` 按 style 选绘制路径；
  - 四边异色 `borderColor`（Edges 化）+ **currentColor 默认边框色**；
  - `border-radius`（几何圆角，消费点放 P3 绘制）；
  - `page-break-*` 别名 → 既有 `BreakRule`；
  - `list-style` 简写拆分。
- `white-space` 落地（**断行器接口** + `pre` 制表符展开 + 溢出/不折行语义）——此前已知缺口，随断行器一起闭环。⏳ 待 P1 第二批（计算值已落 `ComputedStyle.whiteSpace`）。
- 表格族属性（`border-collapse`/`border-spacing`/`caption-side`/`empty-cells`）落到 2D 表格布局与绘制。⏳ 待 P1 第二批（`cellspacing`→`border-spacing` 级联已通，消费未落）。
- 验收：新增属性各配 `jvmTest` 断言；`BoxPathConsistencyTest` 等双路一致性探针全绿。
>
> **P1 第一批已交付**（T2 全修 + T3 计算层全量 + 断言 + 双路一致性绿）：`border-width`/`border-*-width` 全源解析；`BorderStyleEdges` + `BoxDrawer` 分段绘制 + 未声明 solid 回退；`BorderColorEdges` + currentColor；`borderRadius` 几何；`page-break-*` 别名；`list-style` 简写；T3（white-space/spacing/transform/vertical-align/box-sizing/opacity/visibility/overflow/position/wrap/break/direction）计算值 + `P1StyleComputationTest`（18）/`BorderDrawTest`（9）。**第二批待做**：`white-space` 断行器语义、`vertical-align` 行内基线偏移、表格族 2D 消费。

### P2 — 字体与样式表（EPUB3 硬性 MUST）

- `@font-face`：`StyleSheet`/`Cascade` 记录 font-face 表（family + 权重/斜体 + src url/format）；章节内 `url(...)` 相对资源经现有 zip/增量解码管线取字体文件；**IDPF 字体混淆（obfuscation）去混淆**按资源 ID 处理。
- 装载时机：复用 `FontDemand.kt` 的"需求先行"扫描——`@font-face` 族名并入预装集合，整形前入池（Skia 池），避免开屏字体跳变。
- `@import`：作者 CSS 内联加载（防环、限制深度）。
- `@media`：仅处理版心宽度/高度查询（读者页宽注入），其余媒体类型安全忽略。
- 验收：内嵌字体的 EPUB fixture 在 Android/desktop 两端以书内字体渲染；混淆字体/woff2 各一例。

### P3 — 绘制与生成内容（拆 P3-a/b/c 独立交付；通用出口：`./gradlew :app:assembleDebug` + 全量单测 + 双路一致性绿 + 独立提交）

### P3-a — 绘制属性（纯绘制层，不动字符流/几何，低风险）

- `border-radius`：消费 P1 已算几何（`ComputedStyle.borderRadius`），盒背景/边框按圆角裁剪＋描边（`BoxDrawer` + 桌面/安卓背景窗）。
- `box-shadow`/`text-shadow`：盒阴影（偏移/模糊/颜色，绘制层偏移叠画）与行内字阴影（随段整形，量画同源——复用 P1-2b 基线位移的 run 通道模式）。
- `text-emphasis`（含 `-epub-text-emphasis-*` 前缀）：着重号绘制（圆点/实心，行内偏移量画一致）。
- `opacity`：盒/行透明合成（P1 计算值已落，消费点放绘制）。
- 验收：各属性 jvmTest 几何/像素断言（圆角裁剪、阴影偏移、着重号位置）；无位移行零行为变化。

### P3-b — 背景图（纯绘制层，不动字符流）

- `background-image`：`url()` 经现有 zip/增量解码管线（复用 `DecodedImage`）＋ `background-repeat/position` 平铺定位。
- HTML4 `background` 属性（body/table/td 等，P0 仅保留）：下沉为低层级 `background-image`（同路径，复用 img url 解码）。
- 验收：repeat/position fixture + `background` 属性 fixture 双端渲染一致。

### P3-c — 生成内容与大小写合成（字符流级改造，高风险，单独提交）

- **生成内容**：`::before/::after` + `content` + `counter-*` + `quotes`。生成的 run 进入叶文本吸收（`GlobalCharStarts` 同步 + `LAYOUT_VERSION` bump + 双路一致），不可做成纯绘制覆盖（否则列表编号/脚注定位错位）。
- **大小写合成**：`font-variant: small-caps` + `text-transform`（P1–P2 只落计算层）。消费为大写变换＋小字号行内段：字符数不变（大小写 1:1），与上下标同式走 run + 归一化文本；`capitalize` 按词首。
- 验收：`q` 引号、`ol` 计数样式、脚注标记（superscript + 计数器）fixture 双路一致。

### P4 — 布局扩展（拆 P4-a/b/c 独立交付；通用出口：`./gradlew :app:assembleDebug` + 全量单测 + 双路一致性绿 + 独立提交）

### P4-a — float/clear（拆 a1/a2 独立交付；通用出口：`./gradlew :app:assembleDebug` + 全量单测 + 双路一致性绿 + 独立提交）

### P4-a1 — 计算层（纯数据处理，零行为变化，已交付）

- `ComputedStyle.floatSide/clearSide`（初值 NONE = 旧块行为）+ `StyleComputer` 解析
  （`float: left/right/none`；`clear: left/right/both/none` + 逻辑别名 inline-start/end）。
- 表示型属性进级联：`br clear`（all→both）+ `img align`（left/right→float；center 无等价回 NONE，
  与通用 align→text-align 共存）；`HtmlTreeConverter` 保留 `img align`。
- 验收：`P4aComputeTest`（解析/默认值/不继承/级联映射/作者覆盖/解析保留）。

### P4-a2 — img 悬浮围排（布局消费，待做，设计如下）

- 范围：仅 `<img>` 悬浮（正文插图环绕，EPUB 主流情形）；其它元素 `float` 已计算但版式仍按块
  （内容无损）。跨容器环绕、相邻双浮动并排、浮动比单段高时的跨段延续均为后续（v1 保守取空隙、不重叠）。
- v1 规则（重/轻双路经同一 `floatLeadFor` helper，逐 fixture 校验）：
  悬浮 img 行流处理（二选一，实施时按 Paginator 零高行行为定：零高行保字符连续，或字符洞由页洞规则覆盖）+ 仅其**紧随同容器文本叶**
  收缩断行（首 K=ceil(H/lineH)+1 行窄 `contentW-floatW-gap`、左浮动 x 右移，逐行首行取宽重断，
  非悬浮叶单次原调用零回归）；首个非文本/非环绕兄弟触发 emit 期 clear（竖游标越过浮动底）；
  容器收尾向上取 max 包住浮动（悬浮不逃逸本容器）。
- 验收：左右浮动围排 fixture、clear 强制换行 fixture、窄图退块 fixture；双路一致；
  几何变化 → bump `LAYOUT_VERSION`。

### P4-b — ruby 注音（上标对齐，纯 run 层，不动字符流，低风险）

- 现状：`ruby/rt/rp` 保留语义（`HtmlTreeConverter.kt:222`），`rp{display:none}`（`ua.css:76`），`rt` 纯行内同字号顺排；`BaselineShifts` 未消费 `rt`。
- `rt` 收小（~0.6em）+ 上标位移（复用 `BaselineShift` run 通道，量画同源，与 P1-2b 上下标同式）；`rp` 保持 `display:none`；`ruby` 本体基线不动。
- 字符流不变（`rt` 文本仍 inline 进叶文本，`globalCharStarts` 不动）；行高若因 `rt` 上标增高则 bump `LAYOUT_VERSION`（与上下标同规则）。
- 验收：`漢<rt>kan</rt>` fixture（小字上标、基线/行高断言 + 双路一致）；`rp` 括号恒隐藏。

### P4-c — 链接锚点导航闭环（分两步，字符语义先行）

- 现状：`a href/name` + `id` 全保留（`HtmlTreeConverter.kt:106`），`LinkRanges` 行内区间已提（`LinkRanges.kt:25`，tap 接线缺失），`openTocItem(index,fragment)` 章内深锚已通（`BookDocumentController.kt:1905`），`FragmentAnchors` id↔char 互查已提但走**原始 markup 裸文本**（`FragmentAnchors.kt:18`，与排版后样式化字符流漂移——正是 TODO 目录高亮失配的同根）。
- P4-c1（本步，common 纯逻辑）：`href` 目标解析（`#frag` 页内／`chap.xhtml#frag` 跨章／相对路径归一，与 `EpubParser` 的 `normalizePath/joinPath` 同口径）+ 锚点统一（`id` 与 `a name` 同查）+ `LinkRanges`/`FragmentAnchors` 切样式化字符语义（`styledSegments` 单源，`display:none`/空白归一/生成内容同步，双路一致）。
- P4-c2（后步，平台接线）：点按命中（行 `charStart/charEnd` → `LinkRange` → 目标）+ 导航闭环（页内锚 `relayoutTo`，跨章 `openChapter+fragment`，经 `JumpGate` 单槽）+ 当前页 id 高亮复用同一语义。
- 验收：P4-c1 纯 jvmTest（href 解析／name 锚／样式化语义／双路一致）；P4-c2 真书点链回归 + TOC 高亮命中。

### P5 — 验收与固化（"完全支持"的证明）

- **Fixture 一致性套件**：按 §2/§3/§4 矩阵生成「标签 × 属性 × 样式」覆盖的 EPUB2 与 EPUB3 各一册 fixture 书；每行矩阵 = 一个渲染断言（布局几何 + 绘制几何）。
- 真书语料回归（现网书库子集）+ 双端（Android/桌面）截图差异比对。
- 产出：更新本文 §2/§3/§4 矩阵为全绿；`ua.css` 可发布基线；能力矩阵文档同步。

---

## 6. 关键工程不变式（每阶段必守）

1. **双路一致**：重路径（整章 cascade）与轻路径（lazy cascade）对**任何**标签/属性/样式改动必须产出相同块集与 `globalCharStarts`。所有 P0–P4 改动同步触及重/轻两路消费点，过 `BoxPathConsistencyTest`、扫序/回放/取消等探针套件。
2. **块判定单一谓词**：`defaultBlock(tag) || CSS displayBlock || replaceableBlock(img)`；解析白名单 ⊇ box 块集（P0-A 保证）。
3. **新增字段默认值 = 旧行为**：ComputedStyle/断行/绘制数据类加项时不为旧样本改变任何字节。
4. **字符流与磁盘缓存**：任何影响 `globalCharStarts`/块集/几何的改动必須 bump `PaginationCacheStore.LAYOUT_VERSION`（生成内容/float/上下标基线尤其）。
5. **字体需求先行**：任何新字体路径（`@font-face`）并入预装扫描，整形前就位。
6. **注释与实现同步**：本次顺手清理历史遗留的"注释声称已支持、实现没有"片段（如 `Cascade.htmlAttrOrigin` 的 border 注释）。
7. **每阶段独立提交**，遵守仓库语义化 commit 风格；每阶段装机验证。

---

## 8. P6 — 悬浮与注音收口（v1 缺口清零，不再留"后续"）

> P4-a 的"文本浮动/跨容器/并排/跨窗口后续"与 P4-b 的"叠排后续"、rbc/rtc 在此全部闭环。
> 本阶段结束后 capability §5 的悬浮/注音限制条目删除；`LAYOUT_VERSION` 24（悬浮几何 + 注音行高全变）。

### P6-a 悬浮完整版（规则 R1–R7，重/轻双路同式）

- **R1 双侧跨度**：`FlowState` 单悬浮 → L/R 独立底边；同侧纵向堆叠，异侧并存（并排双浮动）。
- **R2 文本悬浮注册**：emit 悬浮分支从"img 专用"泛化为"任何 `floatSide != NONE` 的叶盒"；
  悬浮宽 = 指定宽（px/% 解）否则 shrink-to-fit（`ParagraphBreaker.preferredWidth` 新接缝，
  默认 1em/char 保守估计，skia 真测；双路同 breaker 恒一致）。
- **R3 跨容器环绕**：build 期用"待环绕行数（per-side）"沿文档序透传（容器递归 + 匿名 run 同吃），
  首叶 build-time lead + 后叶延续消耗；不再要求"原文前兄弟同容器"。
- **R4 真 clear 语义**：`none` 不越（环绕不断），`left/right` 只越同侧，`both` 越双侧；
  不可环绕盒（table/img/新悬浮/窄列回退叶）越过重叠侧。v1 的"none 也越过"保守缺口关闭。
- **R5 窄列回退保留**：过窄仍退块式 + 越过，双路同退。
- **R6 窗口连续**：light/temp/canonical 镜像 R1–R5，窗口内局部跨度 + 有界前视 carry-in
  （跨窗口悬浮连续；"转正自愈"退役）。
- **R7 右悬浮绘制**：右悬浮盒右对齐 x，双端（skia + android canvas）同式；环绕行 xOff 沿用。

### P6-b 注音叠排

- **rbc/rtc 行内保留** + UA（透明容器，子文本顺排）。
- **叠排几何**：ruby run（base 文本 + rt 配对；rtc 多 rt 按序配 rbc bases，单 rt �跨整段 base 居中）；
  run 宽 = max(base 1em/char, rt 0.6em/char) 预留，行高 += rt 行高（0.6em 上取整），字符流不变；
  同 helper 双路一致。
- **叠排绘制**：rt 居中画于 base 上方，双端同式（`DrawLine.rubyRuns`/`ParagraphShape.rubyRuns` 新增，
  默认空 = 旧行为）；latin 注音预留偏宽（≤0.4em/char）为已文档化容差，非功能缺口。

### P6-c 验收固化

- P5-b 扩展：文本悬浮/双浮动并排/跨容器/跨窗口 + 叠排（mono/group/jukugo）fixture。
- capability §5 删悬浮/注音条，矩阵保持全绿；双端真书装机（含悬浮/注音章节）。

### P6 追踪

- [x] P6-a1 common 悬浮核心（L/R 跨度 + 文本注册 + 跨容器透传 + 真 clear + shrink 接缝）
- [x] P6-a2 light/temp/canonical 镜像 + 右悬浮绘制（双端；eager leads＋窗口 carry-in＋回填）
- [x] P6-b1 rbc/rtc 保留 + 叠排几何（run/行高/双路一致）
- [x] P6-b2 叠排双端绘制 + 版本 24
- [x] P6-c 验收（P5-b 扩展 + 文档刷绿 + 装机）

---

## 9. 追踪清单（P0–P5）

### P0 解析与标签/属性
- [x] P0-A 标签全覆盖（新增行内/块/表格/注音标签 + UA 默认）
- [x] P0-B 属性白名单全覆盖 + HTML4 表示型属性进级联 + 注释修正（clear/background/name 仅保留，消费移交 P3/P4）

### P1 样式计算层
- [x] T3 文本/盒属性**计算层**（letter/word-spacing、white-space、text-transform、vertical-align、box-sizing、opacity、visibility、overflow、position:relative、overflow-wrap/word-break、direction 隔离）
- [x] T2 陷阱修复（border-width/style/异色/radius/currentColor、page-break-*、list-style 简写）
- [x] P1 第二批：white-space 断行器语义（P1-2a） + vertical-align 行内基线偏移（P1-2b：原生位移＋strut 定基线＋双端绘制） + 表格族 CSS 2D 消费（P1-2c：spacing/collapse/caption/empty-cells；P1-2d：table-layout auto 内容分列＋fixed 均分）

### P2 字体与样式表
- [x] P2-a 解析/模型/计算层：`@font-face` 记录（族名/权重/斜体/src＋format）＋ `@import` 记录（url＋媒体条件）＋条件 `@media` 按视口内联（null 视口即历史丢弃）＋ `font-variant`/`font-stretch` 计算（消费后续）＋ `@font-face` 进需求扫描
- [x] P2-b 装载管线：`@font-face` url 解析装载（相对源 href）＋ IDPF 去混淆 ＋ 先行入池（双端池合并＋canvas 回退桥）＋ `@import` 内联加载（防环限深＋媒体条件）＋ `@media` 全视口求值（P2-c：contentH 穿参＋结构键含视口，重轻同值）＋ fixture（内嵌/混淆/woff2）单元验收；真机双端渲染验收待装机（P5）

### P3 绘制与生成内容
- [x] P3-a 绘制属性：border-radius（px/% 解算钳制＋均匀描边环）/ box-shadow / text-shadow / text-emphasis（含 -epub- 前缀）/ opacity（纯绘制层＋双端像素验收）
- [x] P3-b 背景图：background-image（url 单层＋repeat/position＋background 简写扫描）+ HTML background 属性下沉（body 保留补齐＋低层级 background-image）+ 平铺几何（BackgroundTiles：repeat 四模式＋规范定位＋跨页锚盒）+ 双端绘制（skia 单源平铺＋Android 直画＋Compose 异步解码管线）
- [x] P3-c 生成内容 + 大小写合成：`::before/::after` + content/counters/quotes（字符流级，bump 版本）+ small-caps/text-transform 消费

### P4 布局扩展
- [x] P4-a1 float/clear 计算层（解析 + br clear/img align 进级联 + 单测，零行为变化）
- [x] P4-a2h img 悬浮围排·重路径（紧随文本叶收缩断行 + 零高悬浮行 + emit clear/包容 + DrawLine 逐行 + 版本 23）
- [x] P4-a3 img 悬浮围排·轻路径镜像（floatLeadAt + 整形前导 + temp flow 镜像 + canonical 形状/回填/窗口补完 + 双路探针；跨窗口悬浮转正自愈，见注）
- [x] P4-b ruby 注音（rt 0.6em 上标 + rp 隐藏保持 + 双路一致，字符流不变）
- [x] P4-c1 链接锚点·字符语义与目标解析（href 解析 + id/name 同查 + 样式化语义，common 纯逻辑）
- [x] P4-c2e 链接锚点·控制器导航 API（char→LinkTarget + 页内/跨章 openLinkTarget + 探测单测）
- [x] P4-c2h 链接锚点·行命中基础（DrawLine.charBase 章内基址 + LineHitTest glyph 反查 + 单测）
- [x] P4-c2u 链接锚点·点按接线（宿主 linkAt/openLink + 阅读面链接优先 + 双端编译；真机已验 2026-09-21：internallinks 注脚跨章跳转；附带修伪类/下划线/增量窗基址/点按Y映射/跳转防抖）

### P5 验收固化
- [x] P5-a 文档基线（§2/§3/§4 矩阵刷绿 + 能力文档同步 + ua.css 基线冻结声明）
- [x] P5-b EPUB2/EPUB3 fixture 一致性套件（标签×属性×样式覆盖书 + 渲染断言；common `P5bEpub2FixtureTest` 14 + `P5bEpub3FixtureTest` 15，双路一致内锁）
- [x] P5-c 真书回归 + 双端截图比对（离屏全绿：11 本开页渲染＋混淆/明文双锁＋链接闭环；`P5cOffscreenCorpusTest` 4 项＋34 PNG；真机点链已验 2026-09-21）