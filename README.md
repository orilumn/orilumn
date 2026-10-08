# Orilumn

Orilumn is an EPUB reader for Android tablets and phones, built with a self-developed native rendering engine.

> The original foliate-js / WebView rendering path has been fully replaced by a native Kotlin `StaticLayout`-based engine.

## Highlights

- **Native rendering engine** — EPUB text is laid out with Android `StaticLayout` (no WebView for book content).
- **Faithful original styles** — default preserves the publisher's typography; one-tap switch between `Original` / `Modern` / `Traditional` layout themes.
- **Full-featured book management** — dual-view shelf, import with dedup, font management, per-book settings.
- **Reading ergonomics** — paginated reading, cross-chapter paging, curl page-turn (WIP), precise progress restore, brightness/eye-care controls.

## Tech stack

| Layer | Choice |
|---|---|
| UI | Kotlin · Jetpack Compose · Material3 |
| Rendering | Custom Kotlin engine on Android `StaticLayout` |
| Data | Room (library / progress / fonts) |
| Platform | minSdk 23 · targetSdk 35 · compileSdk 35 |

## Build & run

```bash
# Build and install a debug build on a connected device
./gradlew :app:installDebug
```

### Desktop (macOS / Linux)

Trial run needs no packaging:

```bash
./gradlew :desktopApp:run
```

桌面壳是纯 JVM 模块，但 `:common` / `:engine-skia` 含 Android 目标
（AGP 配置期即要 SDK），故**任何桌面机构建都需 JDK 17+ 与 Android SDK**
（后者只需 cmdline-tools + `platforms;android-36`，不必装 IDE）：

```bash
export JAVA_HOME=<jdk-17+ 根目录>          # 如 ~/tools/jdk-21
export ANDROID_HOME=<Android SDK 根目录>    # 如 ~/Android/Sdk
./gradlew :desktopApp:run
```

打包格式按当前 OS 自动选（macOS=Dmg，Linux=Deb）。Packaging
（`createDistributable`）需要带 `jpackage`/`jlink` 的完整 JDK
（Android Studio 的 JBR 缺 `jpackage`）：

```bash
brew install --cask temurin@17        # macOS
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :desktopApp:createDistributable
# macOS 输出: desktopApp/build/compose/binaries/main/app/desktopApp.app
```

**Linux 已知缺口**（桌面壳其余功能与 macOS 同源同行为）：

- 外接显示器真背光（DDC/CI）：macOS 走 IOKit；Linux 暂未接
  （`DisplayBrightness.currentOs()` 回落无 DDC 实现，亮度滑块只画遮罩）。
- 书内嵌 **woff2** 字体：Linux skiko 构建不解 woff2（TTF/TTC 正常），
  内嵌字体回退系统字体（`BookFontPoolTest` 按此门控）。

Tagging `v*` triggers the GitHub Actions workflow to build a signed APK and attach it to a Release. Signing keys are injected via GitHub Secrets and never committed.

## Project layout

```
app/src/main/java/com/orilumn/
  MainActivity.kt                 # Bookshelf
  ui/reader/                      # ReaderActivity, gestures, curl coordinator
  engine/                         # Parsing, layout, pagination, rendering engine
  data/                           # EPUB parsing, library, fonts, progress, settings
  util/FileLogger.kt              # In-app file logging (bypasses OEM logcat throttling)
```

## License

- **Orilumn code** is licensed under the **MIT License**. See [LICENSE](LICENSE).
- Third-party components retain their own licenses.