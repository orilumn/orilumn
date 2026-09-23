import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "orilumn.reader"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "orilumn.reader"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.1"

        // skiko 只发 arm64/x64 .so（无 32 位）：过滤后 32 位设备不再安装，避免运行时缺库崩溃
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release 签名材料由 GitHub Actions 在运行时从 Secrets 解码注入（不入库）。
    // 读取环境变量：KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD。
    // 本地未注入这些变量时，signingConfig 保持为空，release 回退用 debug 签名以便安装调试。
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // CI 注入密钥时用真实 release 签名；本地无密钥时回退 debug 签名，避免构建失败。
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric 需要打包 Android 资源/清单以运行真实 android.text.* 实现
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Z1/Z2: 阅读器样式单源在 common 的 KMP commonMain/resources（common/androidTarget 的
    // java resources 不会自动进 Android 消费方 classpath），这里把 app 资源目录直接指向
    // common 单源，使运行时与 Robolectric 单测都能从 classloader 读到 css/*。仍是单源——
    // 文件只存在于 common，app 不再有副本。
    sourceSets["main"].resources.srcDir("../common/src/commonMain/resources")

    // Release 变体产物统一命名为 orilumn-<version>-<buildType>.apk（AGP 9 已删旧 applicationVariants API，
    // 改由 Gradle base.archivesName 控制；CI 用 orilumn-*.apk 通配，供本地构建、artifact、Release 附件三处一致使用）。
    base {
        archivesName.set("orilumn-0.1.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// skiko-android .so 打包管线：runtime jar（根目录即 .so）解压到 build/generated/skikoJniLibs/<abi>/，
// 注册为 main.jniLibs 源并挂到 merge*JniLibFolders 之前。文件名保持 libskiko-android-<arch>.so
// 原样（skiko Library.load 按此名 dlopen，不改名）。
val skikoNativeArm64 by configurations.creating
val skikoNativeX64 by configurations.creating
val skikoJniDir = layout.buildDirectory.dir("generated/skikoJniLibs")
val unzipSkikoArm64 = tasks.register<Copy>("unzipSkikoArm64") {
    from(skikoNativeArm64.map { zipTree(it) })
    into(skikoJniDir.map { it.dir("arm64-v8a") })
    include("*.so")
}
val unzipSkikoX64 = tasks.register<Copy>("unzipSkikoX64") {
    from(skikoNativeX64.map { zipTree(it) })
    into(skikoJniDir.map { it.dir("x86_64") })
    include("*.so")
}
android.sourceSets["main"].jniLibs.srcDir("$buildDir/generated/skikoJniLibs")
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(unzipSkikoArm64, unzipSkikoX64)
    }
}

dependencies {
    // KMP 共享逻辑库（纯逻辑，无平台依赖）
    implementation(project(":common"))
    // SkParagraph 排版实现（G 阶段，双引擎开关用；Skia 原生库随 APK 打包）
    implementation(project(":engine-skia"))
    // CMP 共享 UI（H 阶段；S31 起书架经 shared-ui App() 承载，本壳只做薄宿主）
    implementation(project(":shared-ui"))
    // FileKit（mpfilepicker）：壳适配层消费 PlatformFile.readBytes()（shared-ui 经 implementation 声明，
    // 本壳需显式依赖以见其 API）；okio：S30 AppRoot.init(filesDir) 路径注入。
    implementation(libs.mpfilepicker)
    implementation(libs.okio)

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // SQLDelight Android 驱动（阶段 J 数据层；schema/查询在 :common；S34b 起 Room 已整体移除）
    implementation(libs.sqldelight.android.driver)

    // skiko-android .so：AAR 不含 .so（只含 classes.jar），随 runtime jar 分发，
    // 下面 unzipSkiko* 解压进 build 产物 jniLibs 随 APK 打包（不进 git）
    skikoNativeArm64(libs.skiko.android.runtime.arm64)
    skikoNativeX64(libs.skiko.android.runtime.x64)

    // WebView 资源走 https 虚拟域，满足 ES Module / fetch
    implementation(libs.androidx.webkit)

    // 单元测试
    testImplementation(libs.junit)
    // Skia 单测要加载 native：新编号 skiko 主 jar 不再内嵌 dylib（旧 0.9.2 是胖包），
    // engine-skia 只在自己 jvmMain 放了 macOS runtime，:app 单测需显式补（CI/其他 OS 换对应平台件）
    testRuntimeOnly(libs.skiko.awt.runtime.macos)
    // Robolectric：在 JVM 上跑真实 android.text.StaticLayout，验证段间距/行距是否泄漏到段内每行。
    testImplementation(libs.robolectric)
    // kotlinx-coroutines-test：P6 JumpGate 探针的虚拟时钟调度（TestScheduler/StandardTestDispatcher）。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("androidx.test:core:1.6.1")
}

// 说明：foliate-js 已高度定制（三窗口跨章、翻页吸附、四向独立页边距、分页切分等），
// 已纳入本仓库版本管理（app/src/main/assets/foliate-js/），不再从官方 npm 下载/覆盖。
// 升级 foliate-js 时，直接替换目录内文件并提交即可。