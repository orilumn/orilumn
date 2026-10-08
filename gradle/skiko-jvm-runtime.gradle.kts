// ===========================================================================
// Skiko JVM 原生库：按当前 OS 选 artifact（唯一映射，engine-skia jvmMain 与
// desktopApp 共用；新增平台只改这一处）。
//
// - macOS：x64+arm64 dylib 同包；
// - Linux / Windows：各取对应 arch artifact（MavenCentral 直发，与 macOS 同版本）；
// - Android 不走这里：androidMain 用 skiko-android AAR（jvmMain 不进 APK）。
//
// 用法：`apply(from = "../gradle/skiko-jvm-runtime.gradle.kts")`，
// 之后依赖块里写 `runtimeOnly(project.extra["skikoJvmRuntime"] as Any)`。
// （Kotlin DSL 嵌套块里裸 extra 解析到脚本自身容器，必须显式 project.extra；
//  值是 Provider<MinimalExternalModuleDependency>，runtimeOnly 原生支持。）
// ===========================================================================
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.the

private val libs = project.the<VersionCatalogsExtension>().named("libs")

private val os = org.gradle.internal.os.OperatingSystem.current()

/** 当前 OS 的 Skiko JVM 原生库（runtimeOnly 口径；Any 记法免类型解析坑）。 */
private val skikoJvmRuntime: Any = when {
    os.isMacOsX -> libs.findLibrary("skiko-awt-runtime-macos").get()
    os.isLinux -> libs.findLibrary("skiko-awt-runtime-linux").get()
    os.isWindows -> libs.findLibrary("skiko-awt-runtime-windows").get()
    else -> throw GradleException("Skiko JVM 原生库：当前 OS 无对应 artifact（$os）")
}

project.extra["skikoJvmRuntime"] = skikoJvmRuntime
