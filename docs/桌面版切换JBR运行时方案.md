# 桌面版切换 JBR 运行时（原生窗口拖动已撤，方案 B）

> 依据：`AGENTS.md`（层级边界 / 发版流程 / CHANGELOG 规则）、`docs/平台一致性整改方案.md`
> （平台壳边界）、`docs/架构分层与共享边界.md`。
>
> 层级：全部改动在 **平台层·桌面壳**（`desktopApp/`）与 CI 工作流；不碰 `common` /
> `engine-skia` / `shared-ui`，Android 壳零改动（ART 运行时，与桌面 JVM 无关）。不涉及样式/级联。
>
> 本文是**续接依据**：中断后先看 §0 进度表与 §8 接手命令，再继续。

> **2026-11-06 变更**：桌面壳改回**系统默认窗口装饰**（标题栏、圆角、阴影、缩放热区、
> 缩放光标、贴边吸附、全屏全归窗口管理器 / AppKit），自绘无边框壳整体退役。
> 随之退掉的还有 JBR `WindowMove`（它只为自绘壳的拖动服务）：`SystemWindowMove`、
> `jbr-api` 依赖与本文件 §P2 的交接代码均已删除。**JBR 运行时本身保留**（CI 仍打包 JBR，
> 包内运行时断言照旧），但理由只剩「JetBrains 的渲染/字体补丁」，与窗口拖动无关。
> 下文 §P2 / §11 中涉及 `WindowMove`、自绘标题栏、透明圆角投影的内容仅作历史记录，
> 不再是当前实现。

## 0. 进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 采集基线（字体列表、窗口行为、启动/渲染日志、包体积） | ⬜ 未开始（三端字体回归仍缺） |
| P1 | 只换运行时（CI 与本地都改 JBR），业务代码一行不动 | ✅ CI 三个桌面 job 已改完并加断言；本机用 JBR 21 跑通 |
| P2 | ~~Linux 拖动交给窗口管理器（JBR `WindowMove`）~~ | ❌ 已撤（2026-11-06 改回系统默认窗口装饰，`WindowMove` 不再需要） |
| P3 | 收尾（KDoc / 文档 / CHANGELOG / 发版） | 🔄 KDoc 与文档已更新，发版待做 |
| P4 | 可选：JBR 17 ↔ 21 的版本回退或升级 | ⬜ |

- 当前分支/提交：`fix/font-scan-and-open-latency`（未提交）
- 最近更新：2026-11-06，补「进全屏打掉窗口半透明」的文档依据与 A/B 实测（见 §11.8），
  以及还原底色后内容层停在整屏尺寸的连锁问题与修法（见 §11.9）

## 1. 为什么是 B（结论 + 证据）

改前现状：

- 发布包里的运行时 = **Temurin 17**（CI 的 `setup-java` 装的 JDK 被 jpackage 打进安装包）；
  开发机 = Arch 的 OpenJDK 25。**两边都不是 JBR**。
- 标题栏拖动 = 自己改窗口坐标（`WindowChrome.kt` 的 `draggableWindow`：
  `window.location = 起点 + 屏幕指针差`）。

实测事实（2026-11-05，GNOME/Wayland + XWayland，屏幕 2560×1600）：

1. **客户端**发起的窗口定位被 Mutter 钳在屏内：AWT `setLocation(-200, 300)` 实际落在 `0, 300`；
   绕开 AWT 用裸 Xlib `XMoveWindow(-200, 300)` 同样落在 `0, 300`；右侧请求 5000 落在 `2160`。
2. Mutter 源码：configure request 被标 `META_MOVE_RESIZE_CONSTRAIN`；
   `meta_window_move_resize_internal` 里写着「Do not constrain if it is tied to an ongoing
   window drag」—— 只有**窗口管理器自己搬窗口**时才允许移出屏幕。
3. JBR 的 `WindowMove` 文档原文是 “X11 WM-assisted window moving facility”，前置条件：
   WM 支持 `_NET_WM_MOVERESIZE`、指针在窗口内、按键按下。
4. 所以「任意部分移出屏幕 + 贴边吸附」只有「把拖动交给 WM」这一条路；JBR 上这是现成 API。

不选 A（自己写 JNA 调 `_NET_WM_MOVERESIZE`）的原因：还要自己处理和 Compose 指针状态机的交接，
而 JBR 已经把这块封装成官方 API；且 JetBrains 本身就建议 Compose Multiplatform 桌面用 JBR
（YouTrack CMP-8320），Hot Reload 也要求 JBR。

## 2. 方案边界

**做**：

- 三端桌面包的运行时：Temurin 17 → **JBR 21**（LTS；退路见 P4）。
- Linux 拖动：超过阈值后调 `JBR.getWindowMove().startMovingTogetherWithMouse(window, BUTTON1)`；
  不可用或抛异常时**原样回落**到现有自绘拖动。
- CI 增加断言：包内运行时必须是 JBR（防止哪次悄悄退回 Temurin）。

**不做（本轮）**：

- 阴影留边导致的「程序化定位贴边差 5dp」「透明边吃点击」——那要 `_GTK_FRAME_EXTENTS` +
  输入形状，属于另一条线（A 的前半），不在 B 里。
- 双击最大化仍由我们自己的手势处理（`WindowMove` 不管这个）。
- 不升 `jvmTarget`（保持 17）、不动版本号（发版时才 bump）。
- Windows / macOS 的拖动行为刻意不变（`WindowMove` 只在 X11 可用，那里自动回落）。

## 3. 已核查事实（勿重做）

| 事实 | 结论 |
|---|---|
| JBR 是什么 | JetBrains 维护的 OpenJDK 分支（IDEA 自带的那个），GPLv2+CE，三端有构建，可用 jpackage / jlink 打包 |
| 当前依赖里的 jbr-api | `org.jetbrains.runtime:jbr-api:1.9.0` 已由 compose 传递进 **runtimeClasspath**，但**不在 compileClasspath**；要直接调 API 得在 `desktopApp` 显式加依赖 |
| 非 JBR 上会不会崩 | 不会：`JBR.isAvailable()` 为 false，`WindowMove` 走空实现/空 Holder；Compose 的 `WindowDraggableArea` 就是这么探测的 |
| `WindowMove` 适用平台 | 仅 Linux / X11（XWayland 也算）；Windows / macOS 拿不到实现 |
| 前置条件 | WM 支持 `_NET_WM_MOVERESIZE`、指针在窗口内、按键按下——在拖动阈值触发时三条都满足 |
| JBR 版本与下载 | 21.0.11 / 25.0.4.1 均在发；JBRSDK 包名 `jbrsdk-<ver>-<os>-<arch>-b<build>.tar.gz`，从 `cache-redirector.jetbrains.com/intellij-jbr/` 下载 |
| CI 怎么装 JBR | `actions/setup-java@v6` 支持 `distribution: jetbrains`（只出 LTS：11/17/21/25），`java-package: jdk` = JBRSDK（含 jmods，jpackage 要用） |
| 打包用的 JDK | Compose 插件默认用**跑 Gradle 的那个 JDK** 做 jpackage；CI 里换 `setup-java` 就等于换包内运行时 |

## 4. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 字体枚举 / 族名 / 字重变化（**最大**） | 三端字体列表、显示名、字重归位可能变 | P0 先存基线；P1 逐端 diff；差异逐条解释或修 |
| AWT 细节（输入法、文件对话框、HiDPI、托盘） | 交互回归 | P1 冒烟清单逐项过 |
| 渲染后端变化 | 帧率 / 花屏 | 看启动日志的 skiko renderApi；真机翻页、拖动 |
| WM 接管后的指针事件归属未知 | 拖完第一次点击失效、手势卡住 | P2 先做实验（§5 P2.4），两种收尾都试，日志确认后留一种 |
| 老 JBR 没有 `WindowMove` | 静默回落（不崩） | 启动日志打印 `JBR available=… windowMove=…` |
| 开发机没装 JBR | 开发看到的拖动 ≠ 用户看到的 | §8 固定跑法；文档写清 |
| 安装包体积 / 最低系统版本 | 发布体积、老系统装不上 | P1 量一次，对照 JBR 支持矩阵 |

## 5. 分阶段计划

### P0 基线（不改代码）

**动作**：

1. Linux 记录（截图 + 文字）：窗口拖到屏幕右缘停在哪、能否推出屏幕、双击是否最大化、
   点标题栏是否翻页。
2. 字体基线：临时探针（跑完删，不提交）打印 `DesktopFontScan.fontSource()` 的 `families`
   与每族 `faces`（排序后写文件），三端各存一份。建议放
   `desktopApp/src/test/kotlin/.../FontListDumpProbe.kt`，用 `--tests` 单跑。
   没有 Windows / macOS 机器时，退而求其次：字体面板截图 diff。
3. 启动日志：skiko renderApi、首帧耗时、开书耗时。
4. 记录当前安装包体积（Temurin 版）。

**验收**：三份基线存在（Linux 本机 + Windows / macOS 各一份）。
**回滚**：无（不改代码）。

### P1 只换运行时（业务代码一行不动）

**动作**：

1. `.github/workflows/build-release.yml`：四个桌面 job（`build-macos` / `build-linux` /
   `build-windows`）的 `distribution: temurin` → `jetbrains`，`java-version: '17'` → `'21'`，
   加 `java-package: jdk`。`build-android` 不动。
2. 每个桌面 job 的打包步骤后加断言（路径以实际产物为准，先 `ls` 确认）：

   ```bash
   RT="desktopApp/build/compose/binaries/main/app/Orilumn/lib/runtime"
   "$RT/bin/java" -version 2>&1 | grep -q "JetBrains" \
     || { echo "::error::包内运行时不是 JBR"; exit 1; }
   ```

3. 本地：下载 JBRSDK 21 解压到 `~/opt/jbr-21`，用
   `JAVA_HOME=~/opt/jbr-21 ./gradlew --stop && JAVA_HOME=~/opt/jbr-21 ./gradlew :desktopApp:run`
   跑一版（命令见 §8）。

**验收**：

- `runtime/bin/java -version` 含 “JetBrains”；
- 三端包能启动、开书、翻页、设置面板，字体列表与 P0 diff 为空（有差异必须解释）；
- Linux 拖动行为与 P0 **完全一致**（此时还没接 `WindowMove`）；
- `workflow_dispatch` 跑一次，四个桌面 job 绿。

**回滚**：`git revert` 该提交，运行时回到 Temurin 17。

> P1 与 P2 分开，是为了让「字体/渲染回归」和「拖动改动」不混在一个提交里，出问题能立刻定位。

### P2 Linux 拖动交给 WM

**动作**：

1. `gradle/libs.versions.toml` 加 `jbr-api = "1.9.0"` 与
   `jbr-api = { group = "org.jetbrains.runtime", name = "jbr-api", version.ref = "jbr-api" }`
   （与 compose 传递的版本一致，避免两套 API）；`desktopApp/build.gradle.kts` 加
   `implementation(libs.jbr.api)`。
2. 新增 `desktopApp/.../SystemWindowMove.kt`（平台层·桌面壳）：

   ```kotlin
   internal object SystemWindowMove {
       fun start(window: java.awt.Window): Boolean = runCatching {
           if (!com.jetbrains.JBR.isAvailable()) return false
           if (!com.jetbrains.JBR.isWindowMoveSupported()) return false
           com.jetbrains.JBR.getWindowMove()
               .startMovingTogetherWithMouse(window, java.awt.event.MouseEvent.BUTTON1)
           true
       }.getOrDefault(false)
   }
   ```

3. `WindowChrome.kt` 的 `draggableWindow`：第一次超过 4px 阈值时调
   `SystemWindowMove.start(window)`；**成功就再也不写 `window.location`**（两个写入者抢同一扇窗
   就是 macOS 那条老坑）。失败沿用现有自绘拖动。
4. 实验决定接管后的收尾（两种都试，日志确认后留一种）：
   - **甲**：调用后立刻 `return@awaitEachGesture`；
   - **乙**：继续消费事件但不动窗口，直到 `!change.pressed`。
   判据：拖完松手后紧接着的单击/双击是否正常；点标题栏是否仍被吃掉（不翻页）。
   **结论：留乙**（甲会让手势在松手前就结束，而 WM 抓走指针后松手事件未必回得来，
   手势状态更难收；乙在真机连续拖动中未出现按下状态残留，见 §11.5）。
5. 启动时打一行日志：`JBR available=… windowMove=…`（只打一次）。

**验收（Linux，人工）**：

- 能把窗口任意部分拖出屏幕（上 / 下 / 左 / 右）；
- 推到屏幕顶最大化、左右半屏吸附（GNOME）正常；
- 双击标题栏仍最大化 / 还原；单击不拖动；点标题栏不翻页；
- 从最大化还原、全屏进出位置正常；
- 把 `JAVA_HOME` 换回 Temurin 再跑一次：行为与改前一致，不崩（回落生效）。

**验收（Windows / macOS）**：行为与 P1 一致（`WindowMove` 不可用 → 走老路）。

**回滚**：`git revert` P2 提交（去掉依赖 + 还原 `draggableWindow`）；运行时仍可留在 JBR
（P1 独立成立）。

### P3 收尾

- `WindowChrome.kt` 的 KDoc「拖动」一节改成：Win/Linux 优先交 WM（JBR），不支持则自绘；
- 本文件 §0 全绿，补「实测记录」小节；
- `CHANGELOG.md`：用户可见的两条（内置运行时换成 JetBrains Runtime；Linux 拖窗口可以拖出屏幕、
  贴边吸附），内部改动按规则用自然语言写；
- 发版时按 `AGENTS.md` bump `versionName` / `versionCode`（tag 与 versionName 必须一致）。

### P4（可选）版本回退 / 升级

- 若 P1 出现疑似「Java 版本」而非「厂商」的回归：把 `java-version` 改回 `'17'`（JBR 17），
  同一套验收再跑一遍；
- 若一切正常且想跟 IDEA 主线：评估 JBR 25。

## 6. 全量验收清单

- [ ] Linux：部分移出屏幕、贴边吸附、最大化 / 还原、全屏进出
- [ ] Linux：双击最大化、单击不拖、点标题栏不翻页
- [ ] 非 JBR 回落：换 `JAVA_HOME` 跑一次，行为与改前一致
- [ ] Windows：启动、开书、翻页、设置、字体列表、窗口拖动（行为不变）
- [ ] macOS：同上（系统标题栏拖动、红绿灯、全屏）
- [ ] 三端字体列表与基线 diff 为空或已解释
- [ ] 包内运行时断言在 CI 生效
- [ ] 安装包体积记录

## 7. 发版注意

- `orilumn.versionName` 不动；tag 仍必须与它一致（`build-release.yml` 硬门禁）。
- 包内运行时变了，属于用户可见变化 → CHANGELOG 必须写。
- 三端签名流程不变（仍然不签名）。

## 8. 接手命令

```bash
# 0) 看进度
sed -n '1,40p' docs/桌面版切换JBR运行时方案.md
git log --oneline -8 && git status --short

# 1) 本地 JBR：官方 CDN 在本网络被重置，改从 IDEA 安装包取 jbr（详见 §11.1）
curl -fL -o /tmp/ideaIC.tar.gz https://download.jetbrains.com/idea/ideaIC-2024.2.5.tar.gz
mkdir -p ~/opt/jbr-21 && tar -xzf /tmp/ideaIC.tar.gz -C ~/opt/jbr-21 \
  --wildcards 'idea-IC-*/jbr/*' --strip-components=1
export JAVA_HOME=~/opt/jbr-21/jbr

# 2) 用 JBR 跑 Gradle（daemon 缓存 JAVA_HOME，先 stop）
./gradlew --stop
JAVA_HOME="$JAVA_HOME" ./gradlew :desktopApp:run

# 3) 确认跑的是不是 JBR
"$JAVA_HOME/bin/java" -version        # 应含 "JetBrains"
# 应用启动日志里的 Orilumn.Desktop 行会打 jvm vendor=... version=...（应含 JetBrains）

# 4) 构建便携目录并确认包内运行时
JAVA_HOME="$JAVA_HOME" ./gradlew :desktopApp:createDistributable
desktopApp/build/compose/binaries/main/app/Orilumn/lib/runtime/bin/java -version

# 5) 测试
./gradlew :desktopApp:test
```

## 9. 断点记录（中断时填）

- 当前阶段：P3 收尾（发版 / 三端字体回归）未做；P2 的 `WindowMove` 已随自绘窗口壳一起退役
- 已完成：`build-release.yml` 三个桌面 job 换 `jetbrains`/21 + 包内运行时断言；
  桌面壳改回系统默认窗口装饰，`SystemWindowMove` / `jbr-api` / 自绘标题栏与透明圆角投影全部删除
  （JBR 运行时保留，理由改为渲染/字体补丁）
- 未完成 / 下一步：拖到屏幕外的人工确认；Windows / macOS 冒烟；三端字体回归
- 已知异常：见 §11 的实测结论

## 10. 待确认

1. JBR 版本：默认按 **21**（LTS、IDEA 主线、API 齐全）；若想更保守可用 17。
2. Windows / macOS 的人工冒烟由谁跑（CI 只能证明构建成功、包内运行时正确）。
3. 安装包体积若明显变化，是否接受。

## 11. 本机实测补充（2026-11-05 晚）

1. **官方下载通道在本网络不可用**：`cache-redirector.jetbrains.com` 一律 307 到
   `d2xrhe97vsfxuc.cloudfront.net`，该域名连接被重置（重试多次 `code=000`）。华为云
   镜像 `mirrors.huaweicloud.com/jetbrains/…` 对任意路径都回 200（实为前端页面），不可用。
   **可行替代**：`download.jetbrains.com` 走国内 CDN 可通，从 IDEA 安装包里取 `jbr/`：
   ```bash
   curl -fL -o ideaIC.tar.gz https://download.jetbrains.com/idea/ideaIC-2024.2.5.tar.gz   # 841MB
   tar -xzf ideaIC.tar.gz -C jbr --wildcards 'idea-IC-*/jbr/*' --strip-components=1
   jbr/jbr/bin/java -version    # JBR-21.0.5+13-509.30-jcef
   ```
   这是**运行时**（无 jmods），只够 `:desktopApp:run`，够不到 `jpackage`（打安装包仍需
   `jbrsdk`，交给 CI 的 `setup-java`）。
2. **JBR API 在本机可用**：用上面那份 JBR 跑探针得到
   `java.vendor=JetBrains s.r.o. / JBR.isAvailable=true / windowMoveSupported=true`，
   `startMovingTogetherWithMouse(frame, BUTTON1)` 正常返回、不抛异常。
3. **本机无法自动化验证「拖动」**：Wayland 会话下 `java.awt.Robot` 的按键/移动是 XTest，
   注入的事件只活在 XWayland 进程内，**合成器（Mutter）看不到**——所以
   `_NET_WM_MOVERESIZE` 与 JBR `WindowMove` 都表现为「调用成功、窗口不动」
   （探针里 X11 窗口能收到 `PRESS`，证明注入对 X 客户端有效，但对 WM 无效）。
   ⇒ 拖拽是否真能出屏，只能由用户真鼠标拖一次确认；任何自动化结论都不成立。

4. **实现已落地，本机 JBR 实跑通过**（2026-11-05 晚）：
   - `SystemWindowMove`（平台层·桌面壳，`desktopApp`）把拖动交给 WM；
     `gradle/libs.versions.toml` 加 `jbr-api`、`desktopApp/build.gradle.kts` 加依赖；
     `draggableWindow` 越过 4px 门限时交接，成功即停止写 `window.location`。
   - 收尾方式取 P2 的**乙**：交接后仍消费指针事件直到松手（甲会立刻结束手势，
     松手事件可能永远不来，手势状态反而更难收）。
   - 启动日志实测：`vendor=JetBrains s.r.o. version=21.0.5+13-b509.30 supported=true`。

5. **真鼠标连续拖动实测**（同一晚，读 `~/.orilumn/logs/`）：
   4 次交接全部成功，窗口位置依次报 `304,50 → 325,30 → 0,66 → 270,44`（交给 WM 后
   AWT 拿到的就是 WM 搬完的位置），每次拖完紧接着的单击、翻页、设置面板点击都正常 ——
   **没有出现「拖一次之后点不动」的按下状态残留**。这条比任何自动注入都可靠，因为
   输入来自合成器本身。

6. **Mutter 源码核对（为什么 WM 搬就不受「钳在屏内」限制）**：
   `src/core/window.c`（main）`meta_window_move_resize_internal` 里
   `if ((flags & META_MOVE_RESIZE_CONSTRAIN) && window->monitor && !meta_window_is_tied_to_drag (window))`
   —— 正在被拖动的窗口跳过 `meta_window_constrain`。自己写 `window.location` 走的是
   普通的 configure request（不是拖动中），照样被钳；交给 WM 才落进这条豁免。
   **仍留一项人工确认**：把窗口拖到屏幕边缘之外，看是否真的有过半出屏（Mutter 对
   「整窗移出可视区」另有兜底，实测以眼睛为准）。

7. **第二次自动化注入尝试（记录结论，避免重走）**：
   `org.gnome.Mutter.RemoteDesktop` 在本机会话总线上存在，`CreateSession` / `Start` /
   `NotifyPointerMotionRelative` 曾成功过一次（XWayland 收到 motion 事件）；
   但 `NotifyPointerButton` 之后一律不生效（调用返回成功、合成器不转成按键），
   `NotifyPointerMotionAbsolute` 需要活跃的 ScreenCast 流（没有消费者时报
   `No screen cast active`）。⇒ 这条路在本机不可靠，验收仍靠真鼠标。

8. **进全屏会打掉窗口半透明，JDK 明文写着「不还」**（2026-11-06 补，症状：退出全屏后
   投影留边变成一圈黑边）。`GraphicsDevice.setFullScreenWindow` 的 javadoc：
   > When entering full-screen mode, all the translucency effects are reset for the window.
   > Its shape is set to null, the opacity value is set to 1.0f, and the background color
   > alpha is set to 255 (completely opaque). **These values are not restored when returning
   > to windowed mode.**

   实测（同一台机器，X11）：进全屏后 AWT 把窗口换成不透明 graphics configuration，
   X window 被重建成 `depth=24 / visual=0x23`；退出全屏后仍是 `depth=24`——于是留给投影的
   那圈留边露出的就是窗口底色（黑）。修复走 `Window.setBackground`：alpha < 255 时它会
   换回支持逐像素透明的 graphics configuration（该方法 javadoc 明说「may change the
   graphics configuration of this window」），所以进全屏前记住底色、退出后写回即可；
   外部（WM / 系统快捷键）退全屏不经过应用自己的开关，由 `componentResized` 回读兜住。

   A/B（两次独立构建，`shasum -a256` 确认不同，同一窗口同一位置取像素）：
   | | 全屏后 X depth | 退出后 X depth | 留边像素 (0,0) |
   |---|---|---|---|
   | 不还原 | 24 | 24 | `(0,0,0,255)` 黑 |
   | 还原 | 24 | **32** | `(0,0,0,0)` 透 |

   复现手法（无 xdotool 也可）：AWT `Robot` 发 `VK_F11` 进出全屏，`XGetImage` 转存窗口像素
   看 depth 与 alpha（工具见 `/tmp/opencode/winraw.c`、`KeyPress.java`）。

9. **还原透明底的副作用：窗口重建，内容层尺寸停在整屏**（2026-11-06 补，接 §11.8）。
   补上 §11.8 的还原后，读者看到的是「只有左上角是圆角，其余三个角变直角，正文切在右边」——
   又是文档规定的连锁反应：`setBackground` 换回逐像素透明时 **AWT 会重建本地窗口**
   （X window id 每次都变，`depth 24 → 32`）。
   实测证据（日志里同时打窗口与 `JRootPane` 尺寸）：

   | 时刻 | 窗口 | 根面板 |
   |---|---|---|
   | 还原那一刻 | 873×716 | **1280×800**（整屏逻辑尺寸） |
   | 700 ms 后 | 873×716 | 873×716 |

   即新窗口的尺寸已经回来了，但 JFrame 的内容面板没有跟着重新布局，Compose 照整屏版面出图、
   被窗口裁在右下，投影留边也只重画了左上那一块。拖动窗口恢复，是因为拖动带来了一次真正的
   尺寸事件。
   修法：还原底色的同时 `invalidate()` / `validate()` / `repaint()` 一次，把尺寸事件补齐。
   验收（同一窗口取像素）：修复后四角 alpha 全部为 0，12px 处左右对称（13/13）、上下一致
   （13/41），正文换行完整、不再被切。

   该路径的代价：退出全屏时 JDK 侧要重走一遍全屏切换，本机实测从按键到窗口落定约 4 s。若将来
   要消掉这几秒，正解是**不走 `setFullScreenWindow`**、改用窗口管理器自己的全屏状态
   （`_NET_WM_STATE_FULLSCREEN`），窗口不必重建，透明底也不会被清掉。

