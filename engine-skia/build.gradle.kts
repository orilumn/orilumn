import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Skiko JVM 原生库按当前 OS 选择（jvmMain runtimeOnly 读 project.extra，
// 唯一映射见该文件头注）。
apply(from = "../gradle/skiko-jvm-runtime.gradle.kts")

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

        // jvmMain 的桌面 runtime（skiko-awt so/dylib）仅 JVM 可见：android 有独立
        // androidMain actual + skiko-android AAR，不再复用 jvmMain（旧
        // androidMain.dependsOn(jvmMain) 会把 awt jar 漏进 APK 造成 Duplicate class）。
        jvmMain.dependencies {
            // Skiko JVM 原生库按当前 OS 选择（唯一映射在
            // gradle/skiko-jvm-runtime.gradle.kts，desktopApp 同口径）。
            // 嵌套块里必须显式 project.extra（裸 extra 解析到脚本自身容器）。
            runtimeOnly(project.extra["skikoJvmRuntime"] as Any)
        }

        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}