# 阶段 0：KMP `common` 模块 + 纯逻辑层抽取

> ⚠️ **已被 `KMP迁移-分步计划.md` 取代**。本文档留作「真纯 JVM / JVM-only 判断口径」与包归类的历史依据；**实施一律以分步计划的 Sxx 为准**。后续若需改坐标/步骤，先改分步计划，不再单独维护本文档内部细节。

> 配套：`KMP+CMP迁移方案.md` §5 阶段 0。目标：在**不破坏现有 Android app 构建**的前提下，新增一个 KMP 库模块 `common`，把引擎里真正纯 JVM 的逻辑抽进去，作为后续各阶段（SkParagraph 换行 / 绘制 / 预处理）的地基，并验证 `app → common` 的依赖方向成立。

## Context / 为什么

迁移方案把阶段 0 定义为"平移到 commonMain 的纯逻辑层"。实际调研发现纯逻辑层**比方案边界更小**，需先在计划里校正，避免误移导致 commonMain 编译失败：

- **真纯 JVM、可进 commonMain（无论后续是否加 iOS）**：`engine/css/*`、`engine/paging/*`、`engine/laying/`（除 `StaticLayoutBreaker`）、`engine/layout/LinkRanges`+`ListMarkers`、`data/read/Location`。
- **经 Ksoup 替换后净化为纯逻辑、进 commonMain**：`engine/html/*` —— 原先因 `HtmlTreeConverter` 依赖 JVM-only 的 jsoup 被列为"长期留 JVM"。本轮以 KMP 版 jsoup 移植 **Ksoup** 替换，jsoup 调用全部收敛在 `HtmlTreeConverter` 内部（对外 `convert/convertWithStyles` 契约不变，单测零改动），`engine/html` 即可随迁 commonMain。
- **仍 JVM-only、本轮不进 commonMain**（若将来加 iOS native 需另行替换）：
  - `data/epub/*` 用 **javax.xml**
  - `data/settings/*` 用 **org.json**
  这两组留在 `app`/androidMain，后续按阶段 5 处理（expect/actual 或平台源集）。

产出：一个可编译的 KMP `common` 库（`androidTarget()` + `jvm()`，**本轮不加 iOS**），`app` 依赖它，原有纯 JVM 单测随包迁入 `common` 的 `jvmTest` 并保持绿。

## JVM-only 三方库处理策略（jsoup / javax.xml / org.json）

三者原均为 JVM-only，iOS/Kotlin-Native 无产物。本轮**先用 Ksoup 解除 jsoup 阻塞**，剩下 javax.xml / org.json 仍不能进 commonMain：

| 依赖 | 用途 | 可行性 | 处理 |
|---|---|---|---|
| `jsoup` | HTML 容错解析 | ✅ **Ksoup**（jsoup 的 KMP 移植，`com.fleeksoft.ksoup:ksoup`，支持 Android/JVM/iOS/JS/Wasm） | **本轮替换为 Ksoup**，`engine/html` 进 commonMain |
| `org.json` | settings 序列化 | ✅ kotlinx.serialization 官方 KMP，模型为纯数据 | 择机替换，settings 即可进 commonMain |
| `javax.xml` | EPUB 解析 | ⚠️ 第三方 KMP XML（XmlUtil 等）非官方 | 本轮留 JVM，上 iOS 再替换 |

- **jsoup → Ksoup 替换要点**：jsoup 依赖仅存在于 `HtmlTreeConverter.kt`，且只用了 `Jsoup.parse` / `Document.body` / `Element.tagName/children/childNodes/attr/attributes/hasAttr` / `doc.select` / `TextNode.wholeText`。Ksoup 是对 jsoup 的移植，语义对等但 **DOM 方法名并非逐字节一致**，需在 `HtmlTreeConverter` 内部包一层薄适配（把 Ksoup 节点转成现有 `MarkupElement` 遍历逻辑）；**对外 `convert / convertWithStyles` 契约与数据结构（MarkupElement/ParsedChapter）不变**，未改动的 `HtmlTreeConverterTest` 即回归安全网。适配中注意两点与 jsoup 的差异：嵌套 `td/th/caption` 属姓名解析、`wholeText` 空格保留语义——保证原测试全绿即视为几何/语义无漂移。
- **javax.xml / org.json 的归属：仅这两者放共享 JVM 源集（jvmMain）**，被 Android + Desktop 两个 JVM 目标共用 → 代码只写一份。本轮 app 是 Android-only，二者先留在 `app`；待加入 desktop target 时移入 common 的 `jvmMain`。
- **iOS target 可提前加入**：原先 `engine/html` 是 iOS 的 JVM-only 阻塞；Ksoup 提供 iOS native 产物，故 HTML 层不再挡 iOS。但 `data/epub`（javax.xml）与 `data/settings`（org.json）仍是 JVM-only，**iOS 的 commonMain 落地仍受这两组阻塞**，本轮维持不加 iOS target 的产出不变；若想单独验证 HtmlTreeConverter 的 iOS 编译，可加 `iosArm64()/iosSimulatorArm64()` 用 `iosTest` 冒烟（可选，不见必选）。
- **org.json → kotlinx.serialization** 是让 settings 进 commonMain 的廉价前置，可独立小步做，不阻碍阶段 0。

## 实施步骤

### 1. Gradle 骨架（根级）
- `settings.gradle.kts`：`include(":app")` 后新增 `include(":common")`。
- 根 `build.gradle.kts`：新增 `alias(libs.plugins.kotlin.multiplatform) apply false`。
- `gradle/libs.versions.toml` [plugins]：新增 `kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }`。
- `gradle/libs.versions.toml` [versions]/[libraries]：新增 `ksoup = "…"` / `ksoup = { group = "com.fleeksoft.ksoup", name = "ksoup", version.ref = "ksoup" }`（artifact 名是 `ksoup`，**不是** `ksoup-html`；版本以 Maven Central 最新为准）。
- `gradle.properties`：无需改动。
- 已知：Kotlin 2.0 的 default hierarchy 在两个 target 下自动生成 `commonMain/androidMain/jvmMain`，无需手动 create hierarchy 模板。

### 2. 新建 `common/build.gradle.kts`
- plugins：仅 `alias(libs.plugins.kotlin.multiplatform)`（无 android plugin / 无 ksp / 阶段 0 无 compose）。
- `kotlin { androidTarget(); jvm() }`；`androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }`。
- 依赖：`commonMain` 加 `implementation(libs.ksoup.html)`（替代 app 里的 jsoup）。
- 测试依赖：`commonTest/jvmTest` 加 `libs.junit`，纯 JVM 测试放 `common/src/jvmTest`。
- 目录：`common/src/{commonMain,jvmTest}/kotlin/com/orilumns/...`。

### 3. 移动纯源码到 `common/src/commonMain/kotlin/com/orilumns/`
由 `app/src/main/java/com/orilumns/` 迁入：
- `engine/html/*`（全部，jsoup → Ksoup，见上文替换要点）
- `engine/css/*`（全部）
- `engine/paging/*`（BookLayout/Paginator/PageSlice/BreakAwareBookLayout/ProgressMapper）
- `engine/laying/`：除 `StaticLayoutBreaker.kt` 外全部（含经步骤 4 拆分后的 `ParagraphBreaker.kt`）
- `engine/layout/LinkRanges.kt` + `ListMarkers.kt`
- `data/read/Location.kt`

留在 `app`/androidMain（**不迁移**）：`data/epub/*`、`data/settings/*`、`engine/laying/StaticLayoutBreaker.kt`、其余 `engine/layout`（CssLayouter/ParagraphShapes/ParagraphShape/DrawableBookLayout）、`engine/text/*`、`engine/render/*`、`engine/BoxChapterLayouter`/`ChapterLayouter`/`BookDocumentController`/`BoxBookLayout` 等 Android 绑定。

### 4. 拆分 `engine/laying/ParagraphBreaker.kt`
同一文件里 `uniformLineHeight(paint: android.graphics.Paint, …)` 是 android 依赖，`interface ParagraphBreaker`/`BrokenLine`/`FlowedLine`/`lineHeightPx(var)` 是纯的。
- commonMain `ParagraphBreaker.kt`：保留 interface + `BrokenLine` + `FlowedLine` + 顶层 `lineHeightPx(fontSizePx, ratio)`。
- androidMain：`fun uniformLineHeight(paint: android.graphics.Paint, ratio) = lineHeightPx(paint.textSize, ratio)`（放 android 侧）。
- 调用方（均留 app）改调 `lineHeightPx(paint.textSize, ratio)`：
  - `engine/laying/StaticLayoutBreaker.kt` L35/L43
  - `engine/layout/ParagraphShapes.kt` L19/L86
  - 公式与拆分前严格一致（`paint.textSize × ratio` 取整），**几何值不变**，不破坏 heavy/light 一致性。

### 5. `app` 依赖 + import 修正
- `app/build.gradle.kts` dependencies 加 `implementation(project(":common"))`；**删除 `implementation(libs.jsoup)`**（jsoup 已由 common 的 Ksoup 取代，app 不再直接依赖）。
- 包名不变（仍 `orilumn.readers.*`），留在 app 的文件（CssInlineResolver/StaticLayoutBreaker/BoxChapterLayouter/BookDocumentController/ReaderSettings…）import common 类型时**无需改写 import**，只要工程依赖成立即编译通过。若编译发现个别 `orilumn.readers.engine.layout` 或 `engine.text` 误引 Android 别名的漏网，按同样口径就地修。

### 6. 测试迁移
- 纯 JVM 测试随包迁 `common/src/jvmTest`：`engine/html/HtmlTreeConverterTest`（已不依赖 jsoup，仅 junit + 对外 converter API）、`ListMarkersTest`、`LinkRangesTest`、`PaginatorTest`、`ProgressMapperTest`、`LocationTest`、`TableGridModelTest`、`engine/css/*Test` 等仅依赖纯 css/html/paging 的。
- 凡依赖 `android.text.StaticLayout` 的测试（BoxLaying/PreContainerMargin/PageBackgroundClip/BoxSharedGeometry 等 Robolectric）留在 app，继续走 `isIncludeAndroidResources=true`。`SelectorCombinatorTest` 若不触发 android 类型则随 css 迁 jvmTest，否则留 app。
- 每迁一个测试先在 common:jvmTest 编译验证；迁移后 `:app:testDebugUnitTest` 里对应该包的用例移除，避免两处重复。

### 7. iOS 说明（非本期）
本轮不加 iOS target。HTML 层已由 Ksoup 提供 iOS native 产物，不再挡 iOS；剩余 `data/epub`（javax.xml）与 `data/settings`（org.json）仍 JVM-only，将来上 iOS 时需替换（expect/actual 或 Kotlin 跨平台 XML/JSON 方案），记为阶段 5 事项，不阻塞本轮。

## 验收 / 验证
- `./gradlew :common:jvmTest` 通过（抽取的纯逻辑测试绿，含 `engine/html/HtmlTreeConverterTest` —— 这是 Ksoup 适配的回归基准）。
- `./gradlew :app:assembleDebug` 成功，`git status` 干净（可提交）。
- 装机（平板 `:app:installDebug`）：翻页/排版/分页几何与迁移前**完全一致**（几何公式未变、jsoup→Ksoup 仅 HTML 解析层替换、纯逻辑语义未改）。
- 现有 app 的 Robolectric 相关测试保留并仍绿（验证仅挪包不改行为）。

## 风险
- 多模块 Gradle 首次构建需联网解析 kotlin-multiplatform 插件。
- **Ksoup 与 jsoup 的 DOM API 非逐字节一致**：适配层是主要风险点（`childNodes`/`attributes`/`wholeText`/嵌套表格属姓名等）。以未改动的 `HtmlTreeConverterTest` 为回归安全网；出现用例失败时优先按原 jsoup 语义在适配层补偿，而非改测试。
- 移动后个别文件对 androidMain 的隐性引用首次编不过 → 按步骤 3 口径就地修正，避免反向把 android 拉进 common。
- jvmTarget 两模块一致（17），避免 ABI 告警。