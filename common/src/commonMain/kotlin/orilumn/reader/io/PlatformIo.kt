package orilumn.reader.io

import okio.FileSystem
import okio.Path

/**
 * S30 io/log expect/actual —— 平台壳 + 横切（I 阶段）第 1 步，也是本仓库第一个 `expect/actual`。
 *
 * 与 S21 `FontParser` 的 jvmLike 共享接缝（`androidMain.dependsOn(jvmMain)`，common/build.gradle.kts:46）
 * 同源：`jvmMain actual` **一套 actual 同时服务 android 与桌面**，桌面侧由 `:common:jvmTest` 冒烟
 * （PlatformIoTest）证明——这正是 FontParserTest 对 FontParser 的证明方式，只是这台 seam 是跨切面的
 * **expect/actual**，而 FontParser 是 commonMain 纯类 + jvmMain 便利重载（不需要 expect 关键字的
 * 第二种接缝风格）。
 *
 * 横切面提供两件东西（按计划原文 S30「common 定义 `FileSystem.root` 路径、`Logger`」）：
 *  - [AppRoot]：各平台数据根目录的持有缝（android=壳注入 `context.filesDir`，桌面=壳或测试临时目录）。
 *  - [Logger]：跨平台文件日志（写 `<root>/logs/`，按日旋转 `.1`，单行≤4000B 截断），语义对齐
 *    `:app` 原 `orilumn.reader.util.FileLogger` 的磁盘输出（S31-cleanup 起 `:app` 直接调用本实际，
 *    `:app` 内 java.io 版已删除；文件名/行格式见 actual，落盘位置与轮转口径不变）。
 *
 * 原子性：S30 只新增 `:common` 的 commonMain expect + jvmMain actual + jvmTest 冒烟；不碰 `:app`，
 * 故 `:app:assembleDebug` 不受影响仍绿；`:common:jvmTest` 的 smoke 证明桌面路径可用。
 */
expect object AppRoot {

    /** 注入数据根（仅首次生效）；android 壳在 S31 注入 `context.filesDir`（配 `FileSystem.SYSTEM`），桌面壳/测试注入临时目录。 */
    fun init(root: Path, fileSystem: FileSystem)

    /** 已设置的数据根；未设置时为 null。 */
    val root: Path?

    /**
     * 测试专用：清空已注入的数据根（S30 单元测试在每用例前隔离状态——镜像 FontParserTest 的
     * FontParserTest 纯净函数无需 reset；而 AppRoot 是本仓库第一个**有状态** expect seam（AtomicRef
     * 注入缝），按仓库「有状态 seam 必有测试 reset」的既有接缝范式补齐，桌面冒烟在 `:common:jvmTest`
     * 的 LoggerSmokeTest 证明此 seam 桌面路径可用）。
     */
    fun cleanForTest()
}

/**
 * 跨平台文件日志（S30）。
 *
 * - 写 `<root>/logs/`（root 由 [AppRoot] 提供）；未设置 root 时静默跳过（不抛、不阻塞业务）。
 * - 按日一个文件（`sec_yyyyMMdd.txt`），超 [MAX_BYTES] 旋转为 `.1`；单行保留 [MAX_LINES_BYTES] 内，
 *   超长自动截断（Android 内核截断口径，与 `:app` FileLogger 一致）。
 * - 单线程队列 worker，调用线程不阻塞。
 */
expect object Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable)

    /** 批量落盘（高频 WebView console 等，避免逐行磁盘 flush，语义对齐 `:app` FileLogger.batched）。 */
    fun batched(tag: String, lines: List<String>)
}
