# Orilumns 跨平台排版引擎迁移方案（审阅定稿版）

> 配套文档：`KMP+CMP迁移评估.md`（选型论证）、`docs/flow-engine-plan.md`（当前架构基线，阶段 0–3 的移植依据）、`docs/incremental-layout-plan.md`（既有增量分页设计基线）
> 核心原则：**增量实施、可插拔接缝、延迟重大决策；Rust/竖排不作为主线，仅预留扩展接口，真正需要时才触发**

## 1. 背景与问题

现有排版引擎基于 `ParagraphBreaker` 抽象，底层依赖 Android `StaticLayout` 完成排版与绘制。现存三大核心缺陷：

1. **两端对齐效果缺陷**：`StaticLayout.JUSTIFICATION_MODE_INTER_WORD` 仅拉伸单词间距，中文、中英混排场景无法实现书籍级两端对齐，难以修复。
2. **平台绑定**：`StaticLayout` 属于 Android Framework API，引擎锁定 Android，无法移植到 Desktop / iOS。
3. **分页与布局稳定性问题**：存在分页溢出bug；行距、段间距存在漂移，过往只能依靠 `StaticLayoutBreaker` + `UniformLineHeightSpan` 补丁式修复。

项目目标：**主线优先解决跨平台可移植性 + 中文两端对齐**；选词、批注、侧弧修订、OpenGL卷曲翻页、竖排等能力，在架构层面预留扩展接缝，但不阻塞主线开发。

## 2. 功能需求

### 2.1 本轮主线目标（必须完成，验收基准）

| 需求 | 说明 |
| --- | --- |
| 引擎级跨平台可移植 | 同一本书在 Android / Desktop 分页、排版结果一致；支持JVM目标独立开发与排版回归测试 |
| 中文/中英混排两端对齐 | 核心痛点，依托 Skia SkParagraph `kJustify` 实现 |
| 中英字符固定间隙 | 预处理层实现：CJK ↔ 西文边界，删除多余半角空格，采用 `trailing_gap` 标记注入固定间隙（0.25em），**不修改原始文本字符流** |
| 超长英文/数字串断行 | 预处理层处理URL、UUID等长串，在安全位置内部断行，避免单行文本溢出容器 |
| 基础中文标点禁则 | 复用 Skia/ICU 内置规则；精细出版级标点规则延后实现 |

### 2.2 后续增量需求（主线完成后按需追加）

| 需求 | 实现难度 | 接入位置 |
| --- | --- | --- |
| 全书垂直滚动（无分页翻页） | 高 | 增量行窗口绘制管线 |
| 文本选词、点击查词 | 中 | 源锚点 + 字符到字形坐标映射 |
| 文本划线、高亮 | 高 | 样式Run扩展 |
| 批注 | 中~高 | 源锚点 + 侧视图展示 |
| Word式侧弧修订审阅 | 高 | 展示构建器，排版引擎无改动；视图切换控制Run发射开关 |
| OpenGL卷曲翻页 | 高 | 废弃WebView截图旧方案；引擎输出页面纹理交由GL做形变 |
| 竖排排版 | 低（工作量大） | 可插拔断行接缝；触发式评估，不纳入主线 |

## 3. 整体技术路线

> 主线方案：**KMP + CMP + Skiko/SkParagraph，前置文本预处理层；Rust自研断行引擎暂缓立项**

### 3.1 分层选型结论

1. **KMP / CMP**：跨平台工程骨架 + UI外壳，生产就绪。只用来承载界面、手势、目录、设置；正文渲染画布使用Skiko自绘，不依赖CMP高层`Text`。
2. **SkParagraph + Skiko**：承担文本度量、HarfBuzz字形整形、底层绘制；原生支持`kJustify`两端对齐、多语言混排、U+00AD软连字符渲染。
3. **前置预处理层（common纯Kotlin，平台无关）**：中英间隙、超长串断行；未来承载侧弧修订投影、标点禁则。执行时机：**断行计算之前**。
4. **可插拔断行接缝 ParagraphBreaker**：横排场景使用SkParagraph作为度量/断行实现；未来如需竖排，仅替换该接缝，上层分页、盒子流逻辑保持不变。

### 3.2 与评估文档的承接

采用评估文档的混合基座方案：**断行决策受控，SkParagraph仅负责单行整形+绘制，禁止SkParagraph二次自动折行，杜绝两套排版引擎打架**。
核心铁律：**度量阶段与绘制阶段使用完全一致字体、字体集合、OpenType配置，保证几何无漂移**。
迁移采用增量迭代，每个阶段独立可验收，不一次性全量重写。

### 3.3 触发式决策规则（不在当前开发范围）

只有当**正式需要竖排**时，才评估是否替换断行/度量接缝（自研Kotlin断行或Rust原生断行）。当前架构仅预留接口，不投入开发。

## 4. 目标项目KMP架构

### 4.1 模块划分

```
orilumns
├─ common                # 跨平台核心业务与排版抽象
│  ├─ 文档模型、EPUB解析、CSS/HTML盒子流、分页器
│  ├─ 预处理层、TextRun模型、排版几何、ParagraphBreaker接口
├─ engine-skia           # Skiko/Skia排版实现
│  ├─ SkiaParagraphBreaker（ParagraphBreaker实现）
│  ├─ 单行文本绘制叶子、字体抽象层 FontMgr / FontCollection
├─ androidApp
│  ├─ 平台绑定：SAF文件访问、Android特有存储、CMP UI、OpenGL卷曲
└─ desktopApp
   ├─ Desktop平台绑定、CMP UI、文件访问、字体加载适配
```

### 4.2 保留的核心接缝（最小改动，维持原有抽象）

| 接缝 | 现状 | 迁移后实现 | 可移植 |
| --- | --- | --- | --- |
| `ParagraphBreaker.breakLines(): List<BrokenLine>` | 纯接口 | SkParagraph实现 | ✅ 已有抽象 |
| `BookLayout` 行几何 + Paginator | 纯业务逻辑 | 代码平移common | ✅ |
| `NormalFlowLayout/BoxLayouter/CSS盒子流` | JVM代码 | 平移common | ✅ |
| ParagraphShapes 文本整形绘制 | Android绑定 | SkParagraph单行绘制 | 🔄重写 |
| FontPairing / WeightedFontResolver | 绑定Android Typeface | Skia FontCollection抽象 | 🔄重写 |
| 持久化、图片、文件IO、日志 | Android平台API | expect/actual跨平台封装 | 🔄平台适配 |

### 4.3 两条底层地基约束（架构必须固化）

1. **行窗口流式绘制为主，页面纹理仅作为上层缓存**
 阅读管线统一暴露「按行窗口绘制绝对Y坐标」接口，**连续滚动、分页翻页复用同一套绘制管线**。
 卷曲翻页：将行窗口渲染缓存为页面纹理供给GL；纯垂直滚动模式，直接绘制行窗口，不生成页面纹理。

> 禁止将接口固化为`renderPage -> PageSurface`，否则垂直滚动无法落地。

2. **Run模型：源偏移 + 展示样式 + 修订状态三元组**
 选词、批注、修订全部锚定原始文档源偏移；屏幕坐标仅为可计算投影。
 展示构建器是唯一入口：原文+修订侧弧 → 生成展示Run列表；视图切换只是控制Run是否输出，不改动底层文档模型。

### 4.4 预处理层职责（断行前执行，common模块）

- CJK ↔ 西文边界：删除多余U+0020半角空格，添加`trailing_gap: 0.25em`标记，**不插入真实Unicode字符**
- 超长ASCII/数字串（URL/UUID）安全内部断行
- 基础中文标点禁则（后续迭代）
- 预留：侧弧修订投影（删除线、插入文本，通过Run开关控制）

## 5. 分步迁移路线（阶段可独立验收）

> 前序阶段产物作为下一阶段依赖；每阶段可灰度、可回退。

### 阶段0：工程骨架 + 纯业务逻辑平移

1. 搭建KMP工程结构：`commonMain/androidMain/desktopMain`，引入CMP + Skiko依赖。
2. 将纯JVM无平台依赖代码平移至`commonMain`：CSS/HTML解析、盒子流、分页逻辑、EPUB解析、阅读设置、阅读状态；**StaticLayoutBreaker保留在androidMain，暂不动**。
3. 验收标准：同一章节文本，JVM target分页结果与原Android版本保持一致。

### 阶段1：SkParagraph断行实现，落地两端对齐

1. 新增`SkiaParagraphBreaker : ParagraphBreaker`，使用SkParagraph完成文本度量与断行；开启`kJustify`实现两端对齐。
2. 强制约束：度量回调、绘制使用完全相同`FontCollection`、OpenType特性；**所有行构造时调用`layout(Float.MAX_VALUE)`，SkParagraph仅做单行整形，禁止内部二次折行**。
3. 保留旧`StaticLayoutBreaker`作为Android回退实现，双引擎开关。
4. 验收标准：Android+Desktop两端对齐效果一致，分页位置完全匹配。

### 阶段2：绘制层替换，落地行窗口接缝

1. 使用SkParagraph单行绘制替换旧`ParagraphShapes`/StaticLayout绘制逻辑；实现行窗口流式绘制接口。
2. 字体解析、字体回退逻辑抽象为Skia FontMgr/FontCollection跨平台层。
3. 验收标准：绘制几何与行数据完全对齐；分页渲染、连续滚动渲染共用同一管线。

### 阶段3：预处理层上线 + Run模型升级

1. 实现预处理层：中英`trailing_gap`间隙、超长串断行；升级Run模型为三元组（源偏移、样式、修订状态）。
2. 验收标准：中英混排间隙效果稳定；长URL不再撑爆行；锚点信息稳定，为选词批注打下基础。

### 阶段4：增量功能迭代（主线完成后）

- 垂直滚动：增量窗口布局 + 虚拟化绘制
- 选词、划线高亮、批注：源锚点 + 字形坐标命中
- Word侧弧修订审阅：展示构建器层实现，排版引擎无改动

### 阶段5：平台层全面剥离（全App跨平台，可选）

1. 存储迁移：Room → SQLDelight跨平台数据库
2. 文件、图片、字体、日志等平台API全部封装expect/actual
3. 验收标准：Desktop端完整跑通书架、书籍导入、阅读、设置全套流程

### 阶段6：CMP UI迁移 + OpenGL卷曲翻页

1. Android原生Compose UI迁移为CMP共享UI。
2. 重写卷曲翻页：引擎输出页面纹理，OpenGL只做形变；滚动模式不启用卷曲。

## 6. 竖排：触发式决策（延后）

> 竖排不纳入主线开发，仅预留可插拔断行接缝。

### 6.1 竖排为什么需要自研断行接缝

竖排变更断行轴与度量轴：按可用高度列布局，纵向advance、标点直立、tate-chu-yoko短西文横置。SkParagraph不支持CJK直书管线，黑盒断行器无法适配竖排，**一旦需要竖排，必须替换断行接缝**。

### 6.2 竖排与Rust的关系

横排方案「Rust回调Skia获取行宽」的模式在竖排场景不再适用，竖排需要纵向度量（vhea/vmtx纵向bearing）。

- 竖排**不强制Rust**：Kotlin + Skia/SkShaper可实现自研竖排断行。
- Rust适用场景：横竖并存、高性能、长期维护独立原生排版子系统（内存安全、独立单元测试）。

| 场景 | 需要自研断行接缝 | 必须Rust |
| --- | --- | --- |
| 仅横排（两端对齐、中英间隙、长串断行） | ❌ | ❌ |
| 横竖双支持 | ✅ | ❌ |
| 横竖并存 + 高性能长期原生排版库 | ✅ | ✅ |

### 6.3 处置策略

架构预留横/竖可插拔断行接缝；**只有正式收到竖排EPUB需求，才单独做Spike验证，再评估Kotlin自研vs Rust原生库方案**。

## 7. 非目标（明确延后，不做主线）

1. 竖排排版引擎
2. Rust自研断行引擎（仅在竖排/性能瓶颈出现后重新评估）
3. 复杂CSS浮动、表格、Ruby注音等出版级高级排版

## 8. 核心风险清单

1. **性能与Native内存**：SkParagraph段落测量开销高于StaticLayout；解决方案：后台预分页+页面缓存；Skiko原生对象`Paragraph/Typeface`必须手动`.close()`，防止内存泄漏、OOM。
2. **绘制重写风险**：ParagraphShapes、卷曲绘制属于隐藏高风险模块；行窗口改造后必须做像素级回归测试。
3. **跨平台字体一致性**：Android与Desktop系统字体名、字形存在差异；推荐优先打包内嵌字体，否则分页位置会错位。
4. **分层交付边界**：阶段0~3实现**引擎级跨平台渲染（程度一）**；阶段5~6才完成完整App跨平台（程度二），两者工作量独立，不要混淆。

## 9. 验证策略

1. 每个阶段配备自动化回归基准用例，固定字体、文本，校验Android/Desktop分页、每行排版对齐。
2. 竖排、侧弧修订等特性，先单独Spike验证接缝可行性，再立项。
3. 卷曲翻页：增加像素对齐回归测试。

## 附录 A：iOS 全量迁移障碍与通用方案（硬约束）

> **通用方案优先原则**：凡存在跨平台通用库的能力，一律用通用方案重写，禁止在平台绑定代码里长期维护两份实现；仅「⚠️/❌」的系统级能力才走 expect/actual 或降级。

| 能力 | 现状（Android） | 判定 | 迁移方案 |
|---|---|---|---|
| 文件选择（书籍导入） | `ActivityResultContracts`/SAF | ✅ | **mpfilepicker**（CMP：Android=SAF / iOS=UIDocumentPicker / Desktop=原生） |
| Toast | `android.widget.Toast` | ✅ | 改 **CMP Snackbar**（UI 内提示，跨平台） |
| 图片解码（封面/插图） | `BitmapFactory`/`Bitmap` | ✅ | 统一用 **Skia Image 解码（skiko）**，与正文绘制同栈 |
| 字体解析 | `FontParser`（java.io/java.nio，纯逻辑） | ✅ | **FontParser 迁 commonMain**；加载换 **Skia FontMgr/FontCollection**；IO 换 okio/kotlinx-io |
| 字体 WiFi 导入 | `WifiFontServer`（手写 `ServerSocket`，仅字体，与 WebView 无关） | ✅ | 换 **Ktor 网络 + 通用文件 IO** 重写，逻辑进 common |
| 分页缓存 / 日志 / 设置存储 | `java.io.File` + `DataInput/Output` | ✅ | 换 **okio / kotlinx-io**（+ kotlinx.serialization） |
| EPUB zip 解压 | `java.util.zip.ZipFile` | ⚠️ 半通用 | 需要 KMP zip 方案（归 `data/epub` 组，与 javax.xml 一并处理） |
| 并发 | `java.util.concurrent.Executors/AtomicBoolean` | ⚠️ 半通用 | 统一换 **kotlinx.coroutines** |
| 屏幕亮度 | `Settings` + 遮罩 View | ❌ 无通用库（OS 级） | 阅读亮度用**半透明遮罩（纯绘制，跨平台）**；需系统亮度再 expect/actual |
| 联网接口探测 | `NetworkInterface`/手写 `localIpv4` | ❌ 无通用库 | expect/actual `localIpv4()`；iOS 用 **mDNS / NSNetService** 发布，免手动输 IP |
| 数据库 Room | `androidx.room`（含 1→2→3 migration） | ✅ | **SQLDelight**（阶段 5） |

**版本确认**：全项目唯一 HTTP server 是 `FontManagerPanel.WifiFontServer`（LAN 上传字体），与 WebView/foliate 无关；`MainActivity` 的 `ServerSocket/Inet4Address/NetworkInterface` 为未使用残留 import。文本整形管线（StaticLayout→SkParagraph）与绘制层（阶段 1–2）为唯一"重建"型障碍，其余按上表通用方案改写。