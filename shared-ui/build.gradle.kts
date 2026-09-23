import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "orilumn.reader.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // CMP components-resources：共享书架图标等 drawable，需要 Android 资源管线
        androidResources {
            enable = true
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // CMP：跨平台 UI 底座（H 阶段，S26 起 shared-ui 是唯一 UI 承载模块）
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.compose.material.icons.core)
            // kotlinx.coroutines：书架数据流（Flow/StateFlow）与导入编排（S27）
            implementation(libs.kotlinx.coroutines.core)
            // 排版/绘制底座：shared-ui 依赖 engine-skia（SkParagraph 断行、行窗口绘制、字体集合）
            implementation(project(":engine-skia"))
            // mpfilepicker：CMP 跨平台文件选择（Android=SAF / iOS=UIDocumentPicker / Desktop=原生）——S27 书架导入入口
            implementation(libs.mpfilepicker)
        }

        // 注意：不声明 androidMain.dependsOn(jvmMain)——CMP components-resources 会给每个目标生成
        // ActualResourceCollectors，jvmLike 共享会让 jvmMain 的 actual 在 android 目标上重复冲突。
        // shared-ui 的 jvmMain/androidMain 平台代码将来按需分别编写，不共享 JVM 专门逻辑。

        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

compose.resources {
    // 资源包名固定，避免依赖默认（模块名带下划线）生成的包名；S27 起共享书架图标等 drawable 资源
    packageOfResClass = "orilumn.reader.ui.generated.resources"
}