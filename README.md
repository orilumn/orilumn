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

### Desktop (macOS)

Trial run needs no packaging:

```bash
./gradlew :desktopApp:run
```

Packaging (`createDistributable`) requires a **full JDK 17** with `jpackage`/`jlink`
(Android Studio's bundled JBR lacks `jpackage` and fails `checkRuntime`):

```bash
brew install --cask temurin@17
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :desktopApp:createDistributable
# output: desktopApp/build/compose/binaries/main/app/desktopApp.app
```

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