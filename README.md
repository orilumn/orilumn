# Orilumn

跨平台 EPUB 2 / EPUB 3 阅读器。**自研浏览器内核 + 自研断行引擎**：正文的解析、样式层叠、分行与绘制
全部由项目自己实现，不经过 WebView（Android 端也不再依赖 `StaticLayout`）。内核
（Kotlin Multiplatform）在 Android / 桌面之间共享，各平台只保留进程入口、系统能力接缝与打包。

## 平台支持

官方产物在 [Releases](https://github.com/orilumn/orilumn/releases) 里，由 `vX.Y.Z` tag 触发
GitHub Actions 构建。

| 平台 | 产物 | 实测 |
|---|---|---|
| Android 7.0+（手机 / 平板） | `orilumn-<版本>-android.apk` | ✅ 真机实测 |
| macOS **Intel** | `Orilumn-<版本>-macos-x64.dmg` | ✅ 实测 |
| Linux · Ubuntu GNOME | `Orilumn-<版本>-linux-x64.deb` / `.rpm` / `.AppImage` | ✅ 实测 |
| Linux · EndeavourOS GNOME | 同上 | ✅ 实测 |
| macOS Apple Silicon | `Orilumn-<版本>-macos-arm64.dmg` | ⚠️ 有产物，**未实测** |
| Windows | `Orilumn-<版本>-windows-x64.msi` | ⚠️ 有产物，**未实测** |
| 其他 Linux 发行版 / 桌面环境 | deb / rpm / AppImage | ⚠️ 未实测 |

> 「支持」= 能构建出该平台产物；「实测」= 本项目作者真正在上述四个平台上跑过。
> 其余组合可以跑，但没有验证过，不保证可用。

## 特性

### 自研浏览器内核（HTML / CSS）

书的内容不是丢给 WebView，而是由自研内核解析 HTML、做 CSS 层叠、再交给自研分行/排版管线绘制。

- **EPUB 2 / EPUB 3 内容文档涉及的 HTML 标签与 CSS 属性全量支持**：所有标签都有语义节点与 UA
  默认样式（无 de-shell 丢弃），HTML4 表示型属性按低层级进级联；CSS 覆盖 OPS 2.0 必选集、CSS2.1
  基线、CSS3 相关模块、`@font-face` 与 `-epub-` 前缀属性。
- 覆盖范围：`ul/ol/li` 悬挂列表、`table` 二维网格（`colspan/rowspan`、`border-collapse/spacing`、
  `caption-side`、`empty-cells`、`table-layout`）、`ruby` 注音叠排、`img` 悬浮与文本环绕、
  背景图平铺、`break-inside/after/before` 断页规则、`box-shadow`/`text-shadow`/`text-emphasis`
  等绘制属性。
- 支持 `display:none` 整子树隐藏、`display:block` 提升行内元素为块、跨章与页内锚点跳转。
- 边界（可重排阅读器之外，不做）：脚本与表单交互、音视频 / `canvas` / `iframe` 渲染、
  固定版式（fixed-layout）、实质性 RTL 与竖排、`flex`/`grid` 的真实布局算法（按 `display:block`
  语义降级）。
- 逐项能力矩阵见 [`docs/engine-html-css-capability.md`](docs/engine-html-css-capability.md) 与
  [`docs/roadmap-epub2-3-full-support.md`](docs/roadmap-epub2-3-full-support.md)。

### 自研断行引擎

分行不再依赖平台文本引擎（Android 端同一套，不再走 `StaticLayout`）：断点、逐字量宽与行内空隙
分配都在内核里自己算。

- **中英文标点禁则（避头尾）**：行首 / 行尾标点按禁则表处理（中英文合一表，含英文弯引号等）。
- **中英文断词**：英文按音节断词并补连字符（K-L 模式，与 `pyphen` 差分 100%，370,105 词）；
  中文不做音节断词，按禁则分行。
- **长串与 URL 合理断行**：没有可用断点（长 URL、长标识符、无空格的连续串）时也能在合适处断开，
  不再溢出或撑破版心。
- **两端对齐**：分配行内空隙时**不拉伸西文单词**（多余空间按策略分配、标点可挤压），末行不拉伸；
  非末行右缘误差小于 0.5px。

### 字体

- **系统字体**：启动后扫描系统字体族（面板内显示扫描进度），列表按族呈现字重，可隐藏 / 取消隐藏。
- **导入自定义字体**（Android 端；桌面端目前只列系统字体）：与系统字体同列展示，选择即按族写入
  槽位并落库。
- 可选**跟随原书字体**（用书自带的 `@font-face` 字体）。

### 排版主题与版式

- 内置三套排版主题：**原样 / 现代 / 传统**。原样 = 主题表整张不加载，完全由书作者的 CSS 决定
  （正文字体、对齐、缩进、段距节奏都是书里怎么写就怎么排）。
- 现代 / 传统两套各自给一套不可再调的基线预设（正文字体、正文两端对齐、首行缩进、段间距节奏）；
  读者可调的部分（字号、行距、段距、首行缩进开关）在其之上覆盖。
- 支持自定义阅读配色预设（保存 / 删除）。
- 版式可调：正文字号、行距、段间距（疏密百分比）、页边距等。

### 其他

- 书库：导入与管理 EPUB，书架入口与阅读面分离。
- 分页阅读、跨章翻页、阅读进度记忆与精确恢复。
- 翻页动画（可关）、亮度与护眼遮罩。
- 仓库 `books/` 下带若干 EPUB 测试书（公版 / 规范样例），可直接拿来试排版。

## 下载与安装

- **Android**：装 `orilumn-<版本>-android.apk`（需允许安装未知来源应用）。
- **Linux**
  - Debian / Ubuntu：`sudo apt install ./Orilumn-<版本>-linux-x64.deb`
  - Fedora / RHEL 系：`sudo dnf install ./Orilumn-<版本>-linux-x64.rpm`
  - 任意发行版（免安装）：`chmod +x Orilumn-<版本>-linux-x64.AppImage && ./Orilumn-<版本>-linux-x64.AppImage`
- **macOS**：打开 `.dmg`，把 Orilumn 拖进「应用程序」。产物**未签名/未公证**，首次打开被 Gatekeeper
  拦下时右键 →「打开」即可。
- **Windows**：装 `.msi`（未实测）。

## 构建与运行

要求：**JDK 17+**。桌面机构建还需要 Android SDK（`:common` / `:engine-skia` 含 Android 目标，
AGP 配置期即要 SDK；cmdline-tools + `platforms;android-36` 即可，不必装 IDE）。

```bash
export JAVA_HOME=<jdk-17+ 根目录>          # 如 ~/tools/jdk-21
export ANDROID_HOME=<Android SDK 根目录>    # 如 ~/Android/Sdk
```

### Android

```bash
./gradlew :app:installDebug     # 编译并装到已连接设备
./gradlew :app:assembleDebug    # 只出 APK
```

### 桌面（macOS / Linux / Windows）

```bash
./gradlew :desktopApp:run       # 直接跑，无需打包
```

打包格式按当前 OS 自动选（macOS=DMG，Linux=deb+rpm，Windows=MSI）。打包（`jpackage`/`jlink`）
需要完整 JDK——Android Studio 自带的 JBR 缺 `jpackage`：

```bash
./gradlew :desktopApp:packageDeb :desktopApp:packageRpm   # Linux
./gradlew :desktopApp:createDistributable                 # 便携目录（各平台）
```

Linux 的单文件 **AppImage** 不在 Gradle 里出（Compose 的 `TargetFormat.AppImage` 只是便携目录），
由 CI 用 appimagetool 打包；本地要复现可直接调脚本：

```bash
sh desktopApp/make-appimage.sh \
  desktopApp/build/compose/binaries/main/app \
  desktopApp/src/main/resources/icon.png \
  /tmp/Orilumn-9.9.9-linux-x64.AppImage \
  /path/to/appimagetool-x86_64.AppImage
```

### 测试

```bash
./gradlew :common:jvmTest :engine-skia:jvmTest :shared-ui:jvmTest \
          :app:testDebugUnitTest :desktopApp:test
```

## 仓库结构

| 模块 | 职责 |
|---|---|
| `common` | 跨平台地基层：分页算法（全量 / 增量）、断行引擎、章节与样式模型、调度 |
| `engine-skia` | Skia 排版与绘制（几何测量、字体、逐字绘制通路） |
| `shared-ui` | 两端共享的阅读面与面板（画布、手势、设置、字体管理） |
| `app` | Android 壳：进程入口、系统能力接缝、打包 |
| `desktopApp` | 桌面壳（JVM）：入口、文件系统与窗口接缝、打包 |

平台壳只留**进程入口 + expect/actual 接缝 + 打包**三件事，其余逻辑与渲染语义一律共享。

## License

- Orilumn 代码采用 **MIT License**，见 [LICENSE](LICENSE)。
- 第三方组件各自保留其许可证。
