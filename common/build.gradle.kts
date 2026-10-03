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

        // 行几何源码指纹（根 build.gradle.kts 的 generateLayoutGeometryStamp 生成）。
        // `PaginationCacheCodec.LAYOUT_VERSION` = 该指纹 ⇒ 引擎源码一变，旧分页磁盘表自动作废。
        // **不能靠人手动 bump 那个数字**——本轮就因为漏 bump 导致真机仍命中旧表（修复看起来没生效）。
        val layoutGeometryStampDir = rootProject.layout.buildDirectory.dir("generated/layoutGeometryStamp")

        // jvmLike 共享源集：S21 的 FontParser.parse(File/InputStream) JVM 便捷重载同时供 desktop(jvm) 与 android 使用
        val androidMain by getting
        val jvmMain by getting
        androidMain.dependsOn(jvmMain)

        jvmTest.dependencies {
            implementation(libs.junit)
            // JVM sqlite 驱动：LibraryDb 单测跑真实 SQLite（含 1→2 迁移验证）
            implementation(libs.sqldelight.sqlite.driver)
        }

        // 指纹常量目录进 commonMain（PaginationCacheCodec 读它）。
        // KMP 的 sourceSets DSL 下这一句就是给 commonMain 加一个编译源根。
        commonMain {
            kotlin.srcDir(layoutGeometryStampDir)
        }
    }
}

// 生成物必须在任何把 commonMain 编进去的任务之前就位。
//
// 这里对 **:common 的所有任务**都挂 dependsOn，而不是按任务名过滤：
//  - 按名字过滤漏得很惨：KMP-AGP 的 android 目标编译任务叫 `compileAndroidMain`
//    （既不以 compileKotlin 开头也不含 "Kotlin"），一漏就是「删掉生成物后编译不过」；
//  - 指纹任务带 inputs/outputs 上 Up-to-date 检查，没变时只 stat 不重算，
//    所以这个「过宽」的依赖在正确性上是零成本，在维护性上是零负担。
//
// 用**字符串任务路径**而不是 `rootProject.tasks.named(...)`：跨项目在配置期取引用
// 会在并行/求值顺序上踩坑；路径字符串由 Gradle 在**执行期**解析，稳定。
tasks.configureEach { dependsOn(":generateLayoutGeometryStamp") }

sqldelight {
    databases {
        create("OrilumnDb") {
            packageName.set("orilumn.reader.db")
        }
    }
}
