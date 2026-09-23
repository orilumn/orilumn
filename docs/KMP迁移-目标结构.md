# Orilumns 迁移后目标项目结构（KMP + CMP）

> 定位：**最终形态的模块层级与归属**，是后续所有阶段实施的落点参照。本文件不排时间计划、不定义分步顺序——只把每个现状模块/文件归位到迁移后的层级，并给出依赖方向、expect/actual 接缝与「通用方案优先」约束。
>
> 依据：`KMP+CMP迁移方案.md`（主方案）、`KMP迁移-阶段0.md`（阶段0细化）、`docs/flow-engine-plan.md`（引擎架构基线）。

## 0. 结构总览（示意图）

```
orilumns/                                  根 Gradle：settings 聚合 5 个模块
│
├─ common/                KMP 纯逻辑库 —— 无任何平台 UI / OS / 网络绑定
│   ├─ src/commonMain/    一套通用源码（可被 Android / Desktop / iOS 共用）
│   ├─ src/jvmMain/       JVM 专属（Android + Desktop 共享的小块）
│   ├─ src/androidMain/   expect/actual：平台路径、日志
│   ├─ src/desktopMain/
│   ├─ src/iosMain/       expect/actual：平台路径、日志、mDNS
│   └─ src/{commonTest,jvmTest,iosTest}/   单元测试随源码迁
│
├─ engine-skia/           Skiko / SkParagraph 排版与绘制实现 —— 依赖 common
│   ├─ src/commonMain/    SkParagraph 断行、按行窗口绘制、字体集合、图片解码
│   ├─ src/androidMain/   （字体/图片加载 actual 视需要）
│   ├─ src/desktopMain/
│   └─ src/iosMain/
│
├─ shared-ui/             Compose Multiplatform 共享 UI —— 依赖 engine-skia
│   ├─ src/commonMain/    书架 / 阅读面 / 设置面板 / Snackbar / 主题
│   ├─ src/androidMain/
│   └─ src/desktopMain|iosMain/
│
├─ androidApp/            极薄宿主（仅进程入口 + 少量真系统级）—— 依赖 shared-ui
│   └─ src/main/          MainActivity(宿主 App composable) / mDNS或IP actual / 卷曲可选GL宿主
│
├─ desktopApp/            极薄宿主 —— 依赖 shared-ui
│   └─ src/main/          JVM main(起窗口) / mDNS或IP actual
│
└─ iosApp/                （后续）极薄宿主 —— 依赖 shared-ui
    └─ Xcode 壳 + iosMain actual（mDNS/NSNetService 等系统级）
```

> **通用方案优先原则在这里的体现**：文件选择（mpfilepicker）、WiFi 上传（Ktor server）、亮度（遮罩绘制）、字体加载（Skia FontCollection + okio）**都是通用方案，全部落在 common / shared-ui，不属于 app 壳**。三个壳只保留两样非共享物：① 各平台的**进程入口**（Activity / JVM main / AppDelegate，每平台几行，OS 强制无法共用）；② 真·系统级能力（mDNS / 本地 IP 探测）。

**依赖方向（单向，禁止回跳）**：
`androidApp / desktopApp / iosApp → shared-ui → engine-skia → common`

---

## 1. 模块职责与现状文件归位

### 1.1 `common` —— 纯逻辑 + 跨平台抽象

| 现状（`app/src/main/...`） | 迁入 | 说明 / 依赖改造 |
|---|---|---|
| `engine/css/*` 全部 | `engine/css/` | 纯逻辑，直接平移 |
| `engine/html/HtmlTreeConverter` | `engine/html/` | **jsoup → Ksoup**（KMP）+ 内部薄适配 |
| `engine/html/{MarkupElement,ParsedChapter,CssInlineResolver}` | `engine/html/` | 纯数据/纯逻辑，随迁 |
| `engine/paging/*` | `engine/paging/` | BookLayout/Paginator/PageSlice/BreakAware/ProgressMapper 平移 |
| `engine/laying/`（除 StaticLayoutBreaker） | `engine/laying/` | `ParagraphBreaker` interface + 盒子几何，纯 |
| `engine/layout/{LinkRanges,ListMarkers}` | `engine/layout/` | 平移 |
| `data/read/Location` | `data/read/` | 平移 |
| `data/epub/*` | `data/epub/` | **javax.xml → 自研 mini XML DOM**；`java.util.zip → KMP zip` |
| `data/settings/*` | `data/settings/` | **org.json → kotlinx.serialization** |
| `data/book/*`（纯模型） | `data/book/` | 数据类平移（DAO 归数据库层） |
| `data/font/FontParser` | `data/font/` | 纯 TTF/OTF 二进制解析（java.nio 在 native 可用） |
| `engine/text/TypographicProfile`（纯部分） | `engine/text/` | 拆出纯排版参数；`TextPaint` 相关留引擎侧 |
| 新建 | `engine/text/Run` | Run 模型（源偏移+展示样式+修订状态三元组） |
| 新建 | `engine/text/preprocess/` | 预处理层：CJK↔西文间隙、长串断行、标点禁则 |
| 新建 | `io/` | okio/kotlinx-io 文件 IO 抽象（`expect` 平台路径） |
| 新建 | `net/` | `WifiFontServer` 重写（Ktor server + 通用 IO） |
| 新建 | `log/` | `expect/actual` 日志 |

### 1.2 `engine-skia` —— 排版与绘制实现（依赖 common）

| 组件 | 说明 |
|---|---|
| `SkiaParagraphBreaker : ParagraphBreaker` | SkParagraph 断行 + `kJustify`；`layout(Float.MAX_VALUE)` 单行整形，禁二次折行 |
| `LineWindowDrawer` | 按行窗口绝对 Y 坐标绘制，连续滚动/分页共用 |
| `FontCollectionBuilder / FontMgr` | 跨平台字体集合；`FontParser` 产物注入 |
| `ImageDecoder` | Skia Image 解码（封面/插图，替代 BitmapFactory） |

### 1.3 `shared-ui` —— CMP 共享 UI（依赖 engine-skia）

| 现状 | 迁移后 |
|---|---|
| 书架（MainActivity 的 Compose 内容） | `ui/shelf`（导入入口接 mpfilepicker） |
| 阅读面（ReaderActivity 的 Compose） | `ui/reader`（页面视图、手势、进度） |
| 设置面板 / 字体管理 | `ui/settings`（`WifiFontServer` 入口 + 上传） |
| `Toast` | `ui/Snackbar`（通用提示，替代 OS Toast） |
| 主题 / 预设 | `ui/theme`、预设模型 |

### 1.4 平台应用（thin host）

三个 app 壳均为**极薄宿主**，只包含真正的平台边界：

- **androidApp**：`MainActivity`/`ReaderActivity` 仅 CMP 宿主（把 `App()` composable 挂进 `setContent`）；`mDNS/本地IP` actual；**OpenGL 卷曲**若做则在此（Android 专属纹理宿主）。文件选择、WiFi 导入、亮度遮罩、字体加载**均不在壳内**（见通用方案说明）。
- **desktopApp**：JVM `main()` 起窗口挂 `shared-ui`；`mDNS/本地IP` actual。
- **iosApp**（后续）：Xcode 壳 + AppDelegate 挂 `shared-ui`；`mDNS/NSNetService` actual。

---

## 2. expect/actual 接缝清单（系统级或平台级能力）

| 接缝 | Android actual | iOS actual | Desktop actual | 备注 |
|---|---|---|---|---|
| `FileSystem.root` / 路径 | Context filesDir | NSFileManager | 用户目录 | 通用 IO 在 common（okio），仅路径平台化 |
| `Logger` | `android.util.Log` | `os_log`/print | JVM print | |
| `localIpv4()` | `NetworkInterface`（现有） | `getifaddrs` 或 **mDNS/NSNetService** | `NetworkInterface` | iOS 优先 mDNS，免输 IP |

**已泛化、非接缝**（不再 expect/actual，走通用方案）：
- 文件选择 → **mpfilepicker**（Android=SAF / iOS=UIDocumentPicker / Desktop=原生），shared-ui 单选文件即可。
- 亮度 → shared-ui 默认用**半透明遮罩**（纯绘制）；仅当需要改系统亮度才为 `Brightness` 加 expect/actual。
- 字体加载 → **engine-skia FontCollection + okio**（字节→Skia，自管字体）；仅当依赖 **OS 系统字体名回退**时才需要平台字体枚举。
- WiFi 上传 → **common:net WifiFontServer（Ktor）**。

---

## 3. 现状文件 → 目标模块 一览（全部移植点）

**进 `common`（commonMain）**：
`engine/css/*` · `engine/html/*`（Ksoup）· `engine/paging/*` · `engine/laying/*`（除 StaticLayoutBreaker）· `engine/layout/{LinkRanges,ListMarkers}` · `engine/text/{TypographicProfile纯部,Run,preprocess}` · `data/epub/*`（mini-XML+KMP zip）· `data/settings/*`（kotlinx）· `data/read/Location` · `data/font/FontParser` · 新增 `io` / `net`(WifiFontServer) / `log`

**进 `engine-skia`**：
`engine/laying/StaticLayoutBreaker`（→ SkParagraphBreaker）· `engine/layout/{CssLayouter,ParagraphShapes,DrawableBookLayout} 的绘制实现` · `engine/text/{ParagraphSpans,BoxPageRenderer,FontPairing,WeightedFontResolver} 的绘制/字体部分` · 图片解码

**进 `shared-ui`**：
书架 / 阅读面 / 设置面板（现有 `ui/reader/*` 的 Compose 部分与 `ui/*`）· Toast→Snackbar · 文件选择入口（mpfilepicker）· 亮度遮罩

**进 `common` 附加**：
`WifiFontServer`（→ `common:net`，Ktor）· `io`（okio）· `log`（expect/actual 日志，见 §2）

**留 `androidApp`（仅 thin host）**：
`MainActivity` / `ReaderActivity`（CMP 宿主壳）· `ui/reader/curl/*`（**OpenGL 卷曲**，Android 专属，可选）· `localIpv4`/mDNS actual · 平台路径 actual

---

## 4. 约束与说明

1. **依赖单向**：app → shared-ui → engine-skia → common；common 不得反向引用任何平台。
2. **算法/模型带测试**：进 commonMain 的每个包把对应 JVM 单测迁入 `common/src/{commonTest,jvmTest}`；Robolectric / StaticLayout 相关测试留在 androidApp。
3. **单一来源**：同一逻辑只写一份；“半通用”（zip、并发）补 KMP 通用方案，不长期保留双实现。
4. 数据库迁移（Room→SQLDelight）属数据层，本结构未单列模块——落在 `common:data`（模型 + SQLDelight 驱动在 `engine-*` 或 app 侧投影），实现时再定。
5. 本文件只管**目标形态**；分步实施方案与阶段顺序另行维护。