import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Skiko JVM 原生库按当前 OS 选择（唯一映射见该文件头注）。
apply(from = "../gradle/skiko-jvm-runtime.gradle.kts")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 与 Kotlin jvmTarget 对齐：JDK ≥18 的机器上 compileJava 默认目标跟随
// JDK 版本（21），与 compileKotlin(17) 不一致会直接构建失败。
tasks.withType<JavaCompile> {
    options.release.set(17)
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
    // 非 mac 平台 MacFamilyNames 内部有 isMac 门控（非 mac 恒空表），JNA 库本身跨平台。
    implementation(libs.jna)

    // Skiko JVM 原生库（与 engine-skia jvmMain 同口径）：按当前 OS 选择，
    // 唯一映射在 gradle/skiko-jvm-runtime.gradle.kts。
    // 嵌套块里必须显式 project.extra（裸 extra 解析到脚本自身容器）。
    runtimeOnly(project.extra["skikoJvmRuntime"] as Any)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "orilumn.reader.desktop.MainKt"
        nativeDistributions {
            packageName = "Orilumn"
            packageVersion = providers.gradleProperty("orilumn.versionName").get()
            description = "Orilumn"
            vendor = "Orilumn"
            // 打包格式按当前 OS 选：Dmg 仅 macOS 合法，Deb/Rpm 仅 Linux 合法，Msi 仅 Windows 合法
            // （jpackage 对不支持的格式会直接失败，故不能无条件列全）。
            //
            // 注意 TargetFormat.AppImage 不是 AppImage 单文件：它只是 jpackage --type app-image
            // 的便携目录别名（TargetFormat.outputDirName 对 AppImage 返回 "app"，fileExt 直接
            // check 失败抛异常），产物与 :desktopApp:createDistributable 完全相同，不含 type-2
            // runtime + squashfs 附加体。真 .AppImage 单文件由 CI 用 appimagetool 把该便携目录
            // 打成（见 desktopApp/make-appimage.sh 与 .github/workflows/build-release.yml），
            // 故此处不列 AppImage。
            val pkgOs = org.gradle.internal.os.OperatingSystem.current()
            targetFormats(
                *buildList {
                    if (pkgOs.isMacOsX) add(TargetFormat.Dmg)
                    if (pkgOs.isLinux) addAll(listOf(TargetFormat.Deb, TargetFormat.Rpm))
                    if (pkgOs.isWindows) add(TargetFormat.Msi)
                }.toTypedArray(),
            )
            // 打包图标：macOS 要 .icns，Linux 要 .png，Windows 要 .ico
            // （.icns/.ico 均由根目录 icon.png 生成后提交；改图需同步重生成两处）。
            macOS {
                bundleID = "orilumn.reader"
                iconFile.set(project.file("icons/icon.icns"))
                // macOS 打包插件要求版本号 MAJOR > 0（"0.1.2" 非法），此处单独覆写；
                // 与 Android versionName 对应，待发 1.x 后可删掉该覆写。
                packageVersion = "3.0"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                // RPM 的 License 字段（jpackage --linux-rpm-license-type，缺省时 jpackage 用
                // "unknown" 会被 rpmbuild 警告）：与本仓 LICENSE 一致。
                rpmLicenseType = "AGPL-3.0-or-later"
                // --linux-shortcut：deb/rpm 才会装 /usr/share/applications/<name>.desktop 并调
                // xdg-desktop-menu 注册。缺省是 false，jpackage 的 DesktopIntegration 只在
                // 「有文件关联」或「显式要快捷方式」时才生成 .desktop —— 不加这行，装完在
                // 应用菜单里根本找不到 Orilumn。
                shortcut = true
                // --linux-menu-group：.desktop 的 Categories 字段（freedesktop 主分类）。
                // 不填则落到 jpackage 的默认分类，多数桌面环境会归到「其他」里。
                menuGroup = "Office"
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                // --win-shortcut / --win-menu：装完后建桌面快捷方式与开始菜单项
                // （menuGroup 同时让 Compose 认为「要菜单」，并作为开始菜单里的分组名）。
                // 这两项只是「允许安装」；真正装不装由下面的 --win-shortcut-prompt 让用户勾选。
                shortcut = true
                menu = true
                menuGroup = "Orilumn"
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

// ---------------------------------------------------------------------------
// Windows MSI：让安装界面询问「要不要建快捷方式」
//
// Compose 1.11.1 的 windows{} 只有 shortcut / menu / menuGroup，对应 jpackage 的
// --win-shortcut / --win-menu / --win-menu-group：加了就装，不加就不装，没有中间态。
// 「装不装由用户在安装时勾选」靠的是 --win-shortcut-prompt，插件没暴露这个开关。
//
// 不重写打包任务，只往它的 freeArgs 里补这一个参数：Compose 的打包任务本来就把
// freeArgs 原样接在 jpackage 命令行前面（AbstractJvmToolOperationTask.makeArgs），
// 所以 jpackage 的其余参数、图标、UpgradeCode 派生、packageReleaseMsi 全都照旧，
// 只多了一个开关。--win-shortcut / --win-menu 已在上面 windows{} 里打开；
// jpackage 的安装向导会据此多一页，两个勾选框（开始菜单 / 桌面）默认都勾上，
// 用户取消勾选就不建。
// ---------------------------------------------------------------------------

tasks.withType<AbstractJPackageTask>().configureEach {
    // 只在 Msi 任务上加：Linux 的 deb/rpm 与 macOS 的 dmg 不认这个参数，jpackage 会直接报错。
    // （Msi 任务只在 Windows 上注册，这里按名字收口。）
    if (name.endsWith("Msi")) {
        freeArgs.add("--win-shortcut-prompt")
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
    // jpackage 输出的 dmg 文件名跟随 macOS packageVersion 变化：运行时在输出目录里找唯一的 .dmg，
    // 不硬编码文件名（之前写死的 Orilumn-2.0.dmg 与实际产物已对不上），以后改版本也不用管这里。
    val dmgFile = layout.buildDirectory.dir("compose/binaries/main/dmg").map { dir ->
        val dmgs = dir.asFile.listFiles { f -> f.isFile && f.extension == "dmg" }?.toList().orEmpty()
        require(dmgs.size == 1) { "Expected exactly 1 .dmg under ${dir.asFile} (found ${dmgs.size}): $dmgs" }
        dmgs.single()
    }
    inputs.file(project.file("icons/icon.icns"))
    commandLine(
        "sh",
        project.file("stamp-dmg-icon.sh").absolutePath,
        dmgFile.map { it.absolutePath },
        project.file("icons/icon.icns").absolutePath,
    )
}

