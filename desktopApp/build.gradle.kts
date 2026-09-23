import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 跨平台地基层：纯逻辑（common）+ Skia 排版绘制（engine-skia）+ 共享 UI（shared-ui）。
    // S32 桌面壳是这三者的第一个真实 JVM 宿主（Android 壳仍走旧 StaticLayout 阅读管线）。
    implementation(project(":common"))
    implementation(project(":engine-skia"))
    implementation(project(":shared-ui"))

    // Compose Desktop 画布与 Material3（书架/阅读面/设置面板的桌面承载）。
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.components.resources)

    implementation(libs.kotlinx.coroutines.core)
    // FileKit 跨平台文件选择（桌面=原生对话框）：壳适配层消费 PlatformFile，
    // shared-ui 侧为 implementation 不透出，壳需显式依赖（与 :app 口径一致）。
    implementation(libs.mpfilepicker)
    // okio 通用 I/O（书库文件/JSON 落盘）与 kotlinx.serialization（书目索引 JSON）。
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    // SQLDelight JVM 驱动（阶段 J 数据层；schema/查询在 :common）。
    implementation(libs.sqldelight.sqlite.driver)

    // JNA：macOS CoreText 桥（F5 中文名方案A，`MacFamilyNames` 直调
    // CTFontCopyDisplayName 取本地化族名）；仅桌面 JVM 使用（KMP 模块不引）。
    implementation(libs.jna)

    // Skiko JVM 原生库（与 engine-skia jvmMain 同口径：macOS x64+arm64 dylib 同包；
    // 跨平台构建再统一处理）。
    runtimeOnly(libs.skiko.awt.runtime.macos)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "orilumn.reader.desktop.MainKt"
        nativeDistributions {
            packageName = "Orilumn"
            packageVersion = "0.1.1"
            description = "Orilumn"
            vendor = "Orilumn"
            targetFormats(TargetFormat.Dmg)
            // 打包图标：macOS 要 .icns，Linux 要 .png（均由根目录 icon.png 生成；
            // Windows 要 .ico，暂未生成故不配置，用默认图标）。
            macOS {
                bundleID = "orilumn.reader"
                iconFile.set(project.file("icons/icon.icns"))
                // macOS 打包插件要求版本号 MAJOR > 0（"0.1.1" 非法），此处单独覆写；
                // 与 Android versionName 0.1.1 对应，待发 1.x 后可删掉该覆写。
                packageVersion = "1.1"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            // jlink 默认按静态依赖推断模块，反射加载的 sqlite-jdbc 会被裁掉：
            // :desktopApp:suggestRuntimeModules 输出即此列表（含 java.sql，
            // 否则打包 .app 首帧查库报 NoClassDefFoundError: java/sql/DriverManager）。
            modules(
                "java.instrument",
                "java.management",
                "java.sql",
                "jdk.security.auth",
                "jdk.unsupported",
            )
        }
    }
}

// jpackage 打出的 dmg 宗卷图标默认是 Java Duke（见 stamp-dmg-icon.sh 头注）：
// 打包后把宗卷根 .VolumeIcon.icns 换成我们的图标。dmg 原地改写，
// up-to-date 判定不可靠故每次都执行；仅 macOS 生效。
tasks.register<Exec>("stampDmgVolumeIcon") {
    group = "distribution"
    description = "Replace the dmg volume icon (.VolumeIcon.icns) with icons/icon.icns."
    dependsOn("packageDistributionForCurrentOS")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    outputs.upToDateWhen { false }
    val dmg = layout.buildDirectory.file("compose/binaries/main/dmg/Orilumn-1.1.dmg")
    inputs.file(project.file("icons/icon.icns"))
    commandLine(
        "sh",
        project.file("stamp-dmg-icon.sh").absolutePath,
        dmg.get().asFile.absolutePath,
        project.file("icons/icon.icns").absolutePath,
    )
}
