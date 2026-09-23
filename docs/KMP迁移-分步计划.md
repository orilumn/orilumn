# Orilumns KMP 迁移 · 分步计划（原子化、可中断续接）

> 定位：**唯一的分步实施依据**，取代 `KMP迁移-阶段0.md` 里的步骤清单。目标结构照 `KMP迁移-目标结构.md`，功能逻辑照 `KMP迁移-功能架构.md`，技术路线照 `KMP+CMP迁移方案.md`。
>
> **设计原则**（针对之前「大阶段含无数迁移点、中断难接续」的问题）：
> 1. **每个 Sxx 是原子步**：功能单一、只动一处、能独立跑验收。任何一步都能在**一次小会话**内独立完成。
> 2. **每步保持编译/测试绿**：做完一步要么 `./gradlew ...` 绿、要么有透明失败；**不引入半成品编译不过的中间态**。
> 3. **一步一 commit**：commit message 带 `Sxx`，如 `S11 html: jsoup→Ksoup`。**中断后看 git 历史即知做到哪**，从下一个 Sxx 续接即可，无需重读实现。
> 4. **顺序有依赖**：后步依赖前步产物，按编号顺序执行；同阶段内也可按需暂停，但不可跳号。

---

## 接续规则
- 开工前 `git log --oneline | head` 找最大的 Sxx；续接从 `S(N+1)` 开始。
- 结束前必须**已 commit**（防丢）且 `git status` 干净（无半成品）。
- 每步「完成后标志」明确到命令，跑绿才算完成该步。

---

## A. common 模块骨架（3 步）

- **S01 根 Gradle 聚合 :common**
  改 `settings.gradle.kts`（`include(":common")`）、根 `build.gradle.kts`（`kotlin.multiplatform apply false`）、`libs.versions.toml`（`kotlin-multiplatform` plugin）。
  完成后标志：`:app:assembleDebug` 仍绿。

- **S02 common 最小 KMP 库**
  新建 `common/build.gradle.kts`：`androidTarget()+jvm()`，jvmTarget 17，`jvmTest` 加 junit；建占位冒烟测试。
  完成后标志：`./gradlew :common:jvmTest` 绿。

- **S03 app 依赖 :common**
  `app/build.gradle.kts` 加 `implementation(project(":common"))`。
  完成后标志：`:app:assembleDebug` 绿。

---

## B. 纯逻辑分批平移（每步：一个包 + 测试随迁）

> 原则：包内无 Android/`java.*` 三方依赖才允许迁（判断标准见阶段0文档的「真纯 JVM」口径）。

- **S04 `engine/css/*` → commonMain**（+ jvmTest 随迁）
- **S05 `engine/paging/*` → commonMain**（+ 测试随迁：PaginatorTest/ProgressMapperTest 等）
- **S06 `engine/layout/{LinkRanges,ListMarkers}` → commonMain**（+ 测试）
- **S07 `data/read/Location` → commonMain**（+ 测试）
- **S08 `engine/laying/ParagraphBreaker` 拆 Android 依赖**：interface/BrokenLine/FlowedLine/`lineHeightPx` 进 commonMain；`uniformLineHeight(paint)` 留 androidMain；调用方改 `lineHeightPx(textSize, ratio)`。几何不变。
- **S09 `engine/laying/` 其余纯块 → commonMain**（NormalFlowLayout/盒子几何等，除 StaticLayoutBreaker）
  各步完成后标志：`./gradlew :common:jvmTest` 绿 且 `:app:assembleDebug` 绿 且 `git status` 干净。

---

## C. engine/html：jsoup → Ksoup（3 步）

- **S10 依赖调整（只加不迁）**：common 加 **`com.fleeksoft.ksoup:ksoup`**（jsoup 的 KMP 移植，**DOM/select/wholeText 语义对等；勿用 `com.mohamedrejeb.ksoup:ksoup-html`——那是 SAX 流解析、无 DOM 树**）；`libs.versions.toml` 加 `ksoup = com.fleeksoft.ksoup:ksoup`。仍不迁代码，先确认能编译。
  标志：`./gradlew :common:compileKotlinJvm` 绿。

- **S11 HtmlTreeConverter 内部 jsoup→Ksoup 薄适配**：对外 `convert/convertWithStyles` 契约与数据结构（MarkupElement/ParsedChapter）不变，只换内部 DOM 调用；`HtmlTreeConverterTest` 原样跑绿（仍在 app）。
  标志：`:app:testDebugUnitTest` 里的 `HtmlTreeConverterTest` 全绿。

- **S12 engine/html/* 整体迁 commonMain** + `HtmlTreeConverterTest` 随迁 jvmTest；app 删 jsoup。
  标志：`:common:jvmTest`（含 HtmlTreeConverterTest）绿、`:app:assembleDebug` 绿。

---

## D. data/epub：mini XML DOM + KMP zip（4 步）

- **S13 自研 mini XML DOM（commonMain 纯 Kotlin，零依赖）**
  实现并单测：`documentElement/firstChild/nextSibling/nodeName/getAttribute/textContent/getElementsByTagNameNS` 子集 + 实体解析 + XXE/DTD 防走网络。**独立可测，先行交付**。
  标志：mini XM 单测绿。

- **S14 EpubParser 改用 mini DOM**
  `EpubParser` 由 `javax.xml.parsers.DocumentBuilderFactory`/`org.w3c.dom` 换到 S13 的 DOM；解析 OPF/NCX/nav 行为不变，EpubParser 相关测试绿（仍 app）。
  标志：EpubParser 测试绿。

- **S15 ZipEpubResourceReader 换 zip**
  `java.util.zip.ZipFile` → okio/kotlinx.io 的 KMP zip；路径大小写容错、fragment 语义保持；书导入测试绿（仍 app）。
  标志：BookImporter/ZipEpub 相关测试绿。

- **S16 data/epub/* 迁 commonMain** + 测试随迁；app 删 javax.xml。（**归入 common 的关键一步，牵扯 d 组全部依赖**）
  标志：`:common:jvmTest` 绿、`:app:assembleDebug` 绿。

---

## E. data/settings：org.json → kotlinx.serialization（2 步）

- **S17 ReaderSettings/BookSettings 改 kotlinx.serialization**（数据类 `@Serializable`，读写 JSON），`settings` 存取测试绿（仍 app）。
- **S18 data/settings/* 迁 commonMain** + 测试随迁；app 删 org.json。

---

## F. 文本整形 / 字体公共化（3 步）

- **S19 `engine/text` 纯部分进 commonMain**：把 `TypographicProfile` 等纯排版参数拆出（`TextPaint` 相关留 app）。
- **S20 文本整形组件化**：「CJK↔西文间隙」「长串断行」抽成 commonMain 纯函数 + 单测（不插真实字符约束）。
- **S21 `data/font/FontParser` → commonMain** + 测试（TTF/OTF 二进制解析，java.nio 在 native 可用）。

---

## G. engine-skia：排版与绘制（4 步）

- **S22 engine-skia 模块骨架**（androidTarget+jvm，依赖 common），空但可编译。
- **S23 SkParagraph 单行整形冒烟**：`layout(Float.MAX_VALUE)` 单行、不开二次折行；`kJustify` 两端对齐验证测试。
- **S24 `SkiaParagraphBreaker` 接入**：实现 `ParagraphBreaker`，与 `StaticLayoutBreaker` 双实现开关（标志位切换），几何不漂移回归。
- **S25 行窗口绘制 `LineWindowDrawer`**：按行绝对 Y 绘制，分页/连续滚动共用接口。

---

## H. shared-ui（4 步）

- **S26 shared-ui 模块骨架**（androidTarget+jvm，CMP 依赖，依赖 engine-skia）+ `App()` 空壳。
- **S27 书架 UI 平移**：书目列表/删除/封面；导入接 **mpfilepicker**。
- **S28 阅读面 UI 平移**：画布/手势/进度/亮度遮罩/字体切换。
- **S29 设置面板 + Snackbar**：主题/间距等设置项；`Toast` → **CMP Snackbar**。

---

## I. 平台壳 + 横切（3 步）

- **S30 io/log expect/actual**：common 定义 `FileSystem.root` 路径、`Logger`；各平台 actual（路径/日志）。
- **S31 androidApp 接入 shared-ui 瘦身**：Activity 只 `setContent(App())`；移除已平移走的内在实现；SAF/亮度等壳内保留项收敛。
   ==**偏离说明：util/FileLogger 未删除——ReaderActivity/engine 仍经它落盘，待阅读面 Skia 化后统一切 common Logger 再删；MainActivity 新代码已用 common Logger。**==
- **S32 desktopApp 最小壳**：JVM `main` 起窗口挂 `shared-ui`，跑通导入/阅读/设置。

---

## J. 数据库（可再拆）

- **S33 SQLDelight 引入，替换首个 DAO**（书籍 Book 表 + 迁移 1→2）。
- **S34 其余 DAO/迁移逐个替换**（字体/阅读进度/每步一个会话，可拆为 S34a/b/…）。

---

## K. 网络（1 步 + 可拆）

- **S35 `WifiFontServer` 用 Ktor 重写进 `common/net`**：全项目唯一 HTTP server 移入 common；上传逻辑进通用层；iOS/桌面复用。

---

## L. iOS（后续，另行阶段化）

- 待 common/engine-skia/shared-ui 全部就位后，再立项：iosApp 壳 + iosMain actual（mDNS/NSNetService、UIDocumentPicker 已由 mpfilepicker 通用化、字体、路径）。届时按同样 Sxx 原子拆分。

---

## 依赖关系备查（不跨阶跳号）
- B 依赖 A（:common 存在）。
- C 依赖 B04（engine/css 先走，因 Ksoup 主要服务 html 树的 css 源收集不冲突）——实为独立，可并行小步。
- D/E 依赖 A + C12（html 已稳定，隔离 epub 改动面）。
- F 依赖 A/B。
- G 依赖 B09 + C（断行/绘制要消费 DOM/样式）。
- H 依赖 G + B/C/D/E。
- I 依赖 H + S30。
- J/K 可在 H 期间并行推进，不阻塞渲染主线。

## 推荐推进批次（供参考，非强制）
1. A（骨架）→ 2. B（纯逻辑）→ 3. C+D+E（解析三件套：jsoup/XML/JSON，正是历史卡点）→ 4. F → 5. G（渲染引擎）→ 6. H+I（UI+壳）→ 7. J+K（数据/网络）。
每批次末是天然检视点；批次内仍按 Sxx 单步执行，任一步中断都可从该 Sxx 续接。