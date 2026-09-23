import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "orilumn.reader.engine.skia"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 排版/绘制底座：SkParagraph 断行、按行窗口绘制、字体集合
            api(libs.skiko)
            // 上层分页/盒子流逻辑与 ParagraphBreaker 接缝（common 纯逻辑库）
            api(project(":common"))
            // C2-P2b-4: 编排宿主进驻——协程（后台 canonical/预填）+ okio（分页表存储）
            // 经 :common 透传（均为其 api/implementation 依赖，此处显式声明防悬空）。
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }

        // jvmMain 的桌面 runtime（skiko-awt dylib）仅 JVM 可见：android 有独立 androidMain
        // actual + skiko-android AAR，不再复用 jvmMain（旧 androidMain.dependsOn(jvmMain)
        // 会把 awt jar 漏进 APK 造成 Duplicate class）。
        jvmMain.dependencies {
            // Skiko JVM 原生库（x64+arm64 dylib 同包）：仅 macOS 开发机。CI/其他 OS 需按平台补对应
            // skiko-awt-runtime-<os>-<arch> artifact（跨平台构建再统一处理，见 docs 修订记录）。
            runtimeOnly(libs.skiko.awt.runtime.macos)
        }

        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}