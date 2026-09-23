# 整体升级方案：版本列车（Kotlin 2.0.20 → 2.4.10）

> 目标：全仓一次对齐到同一稳定列车，修 `Android skiko` 白屏，不留版本漂移。
> 原则：地基先行、一次只动一层、每步可编过可回滚；`bgfx` 与砍依赖另起 TODO，不在本列车内。

## 0. 基线（现在）

`Kotlin 2.0.20 / CMP 1.7.0 / skiko 0.9.2 / AGP 8.5.2 / Gradle 8.7 / JDK17`
`KSP 2.0.20-1.0.25 / SQLDelight 2.0.2 / Ktor 3.0.3 / coroutines 1.8.1 / serialization 1.7.3 / okio 3.9.0`
`FileKit(filekit-compose) 0.8.8 / ksoup 0.2.0 / BOM 2024.09.03 / compileSdk35/target35/minSdk23`

互锁（升级必须同组走）：
- `plugin.compose` 版本 == `Kotlin` 版本（与 `CMP` 版本无关）
- `KSP` 前缀 == `Kotlin` 版本；`Kotlin≥2.1` 用 `KSP2`（后缀 `-2.x`），`KSP1` 已废弃
- `CMP 1.9+` 要求 `Kotlin≥2.1`；`CMP 1.11/1.12` 工程已迁 `Kotlin 2.2`，`native/web` 要 `2.3`
- `CMP` 传递 `skiko`，不要手写 pin；稳定版 `skiko-android` 走 `MavenCentral`
- `compileSdk36` 要求 `AGP≥8.9.1`；`Play` 自 `2026-08-31` 起要求 `target36`
- `skiko` 要求 `minSdk≥24`；`FileKit` 新线跟 `CMP/Kotlin` 走

## 1. 目标列车（实际落子 2026-09-19）

```
Kotlin 2.4.10 / plugin.compose 2.4.10 / CMP 1.11.1（传递 skiko 0.144.x/m144）
AGP 9.0.1 / Gradle 9.1.0 / JDK17
KSP 删除（全仓仅 apply false、无人消费；新 KSP 最高只到 2.3.x，无 2.4 版）
SQLDelight 2.4.0 / Ktor 3.6.0
coroutines 1.11.0 / serialization 1.11.0（1.12.0-RC不用）/ okio 3.18.2 / ksoup 0.2.6
FileKit: filekit-dialogs-compose 0.15.0（对 Kotlin 2.4.10 + CMP 1.11.1；0.16.0要Kotlin2.4.20/compileSdk37，别贪）
BOM 2026.06.01 / core-ktx 1.18.0 / lifecycle 2.10.0 / activity 1.12.0 / fragment 1.8.9 / webkit 1.14.0
robolectric 4.15.1（留Java17）/ junit4不动
minSdk 24 / compileSdk 36 / targetSdk 36
```

定列时的关键转向（原计划是 CMP 1.12.0/skiko 0.150.1，落地时改到 1.11 系）：
- CMP 1.12 的 Jetpack 对齐（material 1.12.0）要求 compileSdk 37，本机无 SDK37 且 AGP 9.0.1
  最高推荐 36；FileKit 0.15.0 的 compose 依赖本身就是 1.11.1，与 CMP 1.11.1 恰好同列。
- `org.jetbrains.compose.material:material-icons-core` 在 1.7.3 后停发，pin 死 1.7.3（纯矢量，与新运行时兼容）。
- AGP 9 要点：`kotlin.android` 插件删除（AGP 内置 Kotlin）；`applicationVariants` 改名逻辑改走
  `base.archivesName`（CI 通配 `orilumn-*.apk` 不变）；KMP 库模块（common/engine-skia/shared-ui）迁
  `com.android.kotlin.multiplatform.library`（`android{}` 并入 `kotlin.android{}`，`androidTarget` 改 `android`）。
- skiko 要点：新编号主 jar 不含 native。JVM 单测 `:app testRuntimeOnly skiko-awt-runtime`；
  Android 的 .so 随 `skiko-android-runtime-{arm64,x64}` 分发，`:app` 经 `unzipSkiko*` 解压进
  build 产物 jniLibs（`ndk.abiFilters` 只留 arm64-v8a/x86_64，无 32 位）；engine-skia 拆掉
  `androidMain.dependsOn(jvmMain)`（会把 awt jar 漏进 APK 报 Duplicate class），建独立 androidMain actual。
- FileKit 0.15 API 迁移：`core.PlatformFile`→根包 `PlatformFile`；`PickerType/PickerMode`→
  `dialogs.FileKitType/FileKitMode`；`compose.rememberFilePickerLauncher`→`dialogs.compose.*`，
  参数 `title/mode/settings`→`dialogSettings`（title 是 JVM-only，commonMain 用 createDefault()，
  多选回调 `(List<PlatformFile>?) -> Unit`）；`readBytes/name/path` 由成员改顶层扩展，各调用方补 import。
- K2 变严一处：`override fun touchRead = delegate...`（Unit 预期）必须写显式 Unit 块。

## 2. 步骤拆细（按序，每步独立验证）

### S1 地基：Gradle/AGP/JDK/SDK
- 改：`gradle-wrapper.properties → 9.1.0`，`libs.versions.toml: agp → 9.0.1`，各模块 `compileSdk/target → 36`，`minSdk → 24`
- 验：`./gradlew help` + `:app:assembleDebug` 能编过（行为不变）
- 回滚：只回这三个文件

### S2 编译器：Kotlin + plugin.compose + KSP
- 改：`kotlin → 2.4.10`，`kotlin-compose → 2.4.10`，`ksp → 2.4.x-2.0.x`
- 验：全模块 `compileDebugKotlin` 过；`KSP` 生成源码存在
- 坑：`KSP1→KSP2`，`Room` 已移除，只剩 `SQLDelight` 生成，报错先看 `KSP` 前缀

### S3 UI底座：CMP 1.12.0 + 去 skiko pin
- 改：`composeMultiplatform → 1.12.0`，删 `libs.skiko/skiko-awt-runtime-macos` 手写 pin（`desktop` 按新文档用 `skiko-awt-runtime-<os>-<arch>` 由 `CMP` 对齐版本）
- 验：`:shared-ui:compileKotlinJvm` + `:desktopApp:run` 能起
- 坑：`HotReload/XCFramework` 要求 `Kotlin≥2.1.20/2.2`，已满足，忽略警告

### S4 AndroidX：BOM + SDK + 测试库
- 改：`composeBom → 2026.06系`，`core-ktx 1.18.0 / lifecycle 2.10.0 / activity 1.12.x / fragment 1.8.9 / webkit 1.14.0`，`robolectric 4.15.1`
- 验：`:app:testDebugUnitTest` 绿；`lifecycle-ktx` 空壳告警不管（API已迁 `lifecycle-runtime`）

### S5 KMP地基：coroutines/serialization/okio/ksoup
- 改：`1.11.0 / 1.11.0 / 3.18.2 / 0.2.6`
- 验：`:common:jvmTest` 绿（重点 `CjkLatinSpacing/LongStringBreaker` 现有用例）
- 坑：`serialization 1.9+` 的 `kotlin.time.Instant` 行为，按编译告警修

### S6 数据+网络：SQLDelight 2.4.0 + Ktor 3.6.0
- 改：`sqldelight → 2.4.0`（`Gradle≥8.2.1` 已满足，注意 `AGP variant` 新解析），`ktor → 3.6.0`
- 验：`:common:jvmTest` 的 `LibraryDb` 迁移用例 + 字体上传 `common/net` 用例
- 坑：`Ktor 3.6` 新 `Auth` 要 `context-parameters`，不用新 `Auth` 就不触发

### S7 文件选：FileKit 迁新坐标（放最后，API breaking 最多）
- 改：`io.github.vinceglb:filekit-compose:0.8.8` → `filekit-dialogs-compose:0.15.0`，跟官方迁移指南改 `import/API`，`0.14.2+` 已删 `iosX64`
- 验：书架导入在 `Android/Desktop` 各走一遍 `SAF/原生`
- 回滚：这步单独提交，炸了只回它

### S8 收尾：skiko-android 打包 + 回归（白屏终结）
- 改：`engine-skia` 加 `androidMain` 的 `skiko-android AAR + runtime arm64/x64 + jniLibs`，拆独立 `actual systemFonts()`，删 `androidMain.dependsOn(jvmMain)` 取巧
- 验：真机 `logcat` 无 `libskiko-android-arm64.so not found`；`SkiaPageTextWindow` 有像素；`JUSTIFY/URL/长词` 回归；`zipalign -c -P 16` 验 `16KB`
- 坑：`APK +10MB`、别忘 `Paragraph/Typeface.close()`

## 3. 后续 TODO（不在本列车）

- [ ] 砍依赖评估：`Ktor`（字体上传 server → 手写 `Socket`？）、`FileKit`（→ `expect/actual` 直调 `SAF/UIDocumentPicker`）、`SQLDelight`（schema v4 是否值得留）。每项单独 `spike + 体积/维护账`，不搭车升级。
- [ ] `bgfx` spike：与 `Kotlin` 版本无关（`C++/NDK` 编 `.so`，`JNI` 薄胶水隔离在 `CurlRenderer` 接口后）。只验静态双纹理卷曲帧率+跟手，成了再立项。
- [ ] `LongStringBreaker/CjkLatinSpacing` 接入 `SkiaParagraphBreaker`（`ZWSP` 物化 + 超宽硬切兜底），`Methionyl…` 级长词回归。
