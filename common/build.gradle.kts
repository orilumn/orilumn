import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "orilumn.reader.common"
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
            implementation(libs.ksoup)
            // Okio：KMP 通用 I/O；data/epub 的 EPUB zip 读取（S15 引入，S16 随 data/epub 迁入 commonMain，app 不再自引）
            implementation(libs.okio)
            // kotlinx.serialization：KMP 跨平台 JSON（S17/S18：settings 由 org.json 迁移后随 data/settings 进 commonMain）
            implementation(libs.kotlinx.serialization.json)
            // SQLDelight（阶段 J：Room→SQLDelight）：跨平台 schema 运行时 + Flow 查询扩展
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // kotlinx.coroutines：LibraryDb 的 IO 调度与 Flow 书架流（S33 起数据层进 common）
            implementation(libs.kotlinx.coroutines.core)
            // Ktor server（S35：common/net 字体上传服务；core 路由 + CIO 引擎，android/jvm 双目标可编，
            // 未来 iOS 直接复用同一 commonMain 实现——手写 ServerSocket 在 commonMain 不可用）
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }

        // jvmLike 共享源集：S21 的 FontParser.parse(File/InputStream) JVM 便捷重载同时供 desktop(jvm) 与 android 使用
        val androidMain by getting
        val jvmMain by getting
        androidMain.dependsOn(jvmMain)

        jvmTest.dependencies {
            implementation(libs.junit)
            // JVM sqlite 驱动：LibraryDb 单测跑真实 SQLite（含 1→2 迁移验证）
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("OrilumnDb") {
            packageName.set("orilumn.reader.db")
        }
    }
}
