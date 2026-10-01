import java.security.MessageDigest

// Orilumn 根构建脚本：插件声明 + 行几何源码指纹任务。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}

// ===========================================================================
// 行几何源码指纹（PaginationCacheCodec.LAYOUT_VERSION 的「自动」来源）
// ===========================================================================
//
// ## 警告：本文件的注释里绝对不能出现连续斜杠星号
//
// Kotlin 的块注释是**可嵌套**的（与 Java 不同）。所以在 `/** … */` 里写一句
// 「取 `common/src/*Main` 下的文件」就会让 `/*` 打开一层**嵌套**注释，结尾那个
// `*/` 只关掉内层 ⇒ 从此往后整份脚本都被当成注释吞掉。
// 后果极其阴险：**脚本照样编译通过、构建照样 BUILD SUCCESSFUL**，
// 只是后面的 `tasks.register` 一个都没生效（表现为「任务找不到」），
// 连故意写个类型错误都不报错。本项目已经在这上面栽过一次（2026-10-01）。
// ⇒ 所以下面所有说明一律用 `//` 行注释（行注释里写什么都安全），
//   实在要用块注释时，绝不把 glob 的 `/*` 写进去。
//
// ## 为什么不能靠人手动 bump LAYOUT_VERSION
//
// 分页磁盘表的 key 是 LayoutParamKey.hash()，只含**版面参数**、不含**排版算法版本**
// —— 所以「改了断行/度量算法但版面参数没变」时，旧表会被继续命中，读者看到的是
// **上一版算法的分页结果**。2026-10-01 本轮就踩了：贪心加「往回退」后行尾下标变了、
// 页切点变了，真机日志却仍是 `loaded from persist`，修复看起来完全没生效。
//
// 现成的 appVersion 兜底（Android versionCode / 桌面 DISK_CACHE_VERSION）**救不了**
// 这个场景：两者都是写死的常量（versionCode = 20 / = 1），同一个 versionCode 下的
// 所有构建共用一个值 ⇒ 只在**发版升级**时兜底，开发期 / 同一个 versionCode 内完全不触发。
//
// ⇒ 几何版本必须**由源码内容自动导出**，而不是靠人记得改一个数字。
//
// ## 取哪些文件
//
// 取「每个引擎模块 src 目录下所有以 Main 结尾的源集里的**全部文件**」——刻意取粗：
//  - 方向是**宁可多作废、不可少作废**。少作废 = 读者看到旧版面（本轮的真 bug）；
//    多作废 = 重排一次（每章 1~2s），而且发版时 appVersion 本来就会全量作废一次，
//    所以生产环境的多作废成本是 0，多作废只影响开发期。
//  - 不需要维护文件白名单 ⇒ 新加的引擎文件、新的主源集自动被覆盖。
//  - 只取 Main 结尾的源集（排除 jvmTest / androidTest 等）⇒ 改测试不会作废缓存。
//  - 只含 common + engine-skia 两个引擎模块。shared-ui 是纯 UI（包名全在
//    orilumn.reader.ui.reader，只喂 settings 值，而那些值本来就在 LayoutParamKey 里），
//    改它不该作废分页缓存；app / desktopApp 同理。
//  - **新增引擎模块时必须同步登记这里**——漏登记的后果就是该模块的几何改动不被感知。
//    光靠注释提醒不够，所以下面还有一个 `unregisteredEngineCode` 守卫任务直接 fail 构建。
val layoutGeometryModules = listOf("common", "engine-skia")

// 参与指纹的源集目录（每个引擎模块 src 下以 Main 结尾的目录），配置期枚举一次以确定「有哪些目录」。
val layoutGeometrySourceDirs: List<java.io.File> = layoutGeometryModules.flatMap { module ->
    val srcDir = java.io.File(rootDir, "$module/src")
    check(srcDir.isDirectory) {
        "layoutGeometryStamp: 模块 $module 没有 $srcDir —— 新增/重命名引擎模块时必须同步 layoutGeometryModules"
    }
    (srcDir.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.endsWith("Main") }
        .sortedBy { it.name }
}

// 用 fileTree 而不是配置期 walkTopDown：文件集合由 Gradle 跟踪，增量构建才判得出「没变」。
val layoutGeometryFiles = rootProject.files(
    layoutGeometrySourceDirs.map { dir -> rootProject.fileTree(dir) },
)

val generateLayoutGeometryStamp by tasks.registering {
    description = "按引擎源码内容算出 PaginationCacheCodec.LAYOUT_VERSION（不靠人手动 bump）"
    group = "orilumn"
    val outFile = rootProject.layout.buildDirectory
        .file("generated/layoutGeometryStamp/orilumn/reader/engine/LayoutGeometryStamp.kt")
    inputs.files(layoutGeometryFiles)
        .withPropertyName("geometrySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(outFile).withPropertyName("stampFile")
    doLast {
        val root = rootProject.projectDir
        // 排序后按「相对路径 \0 长度 \0 内容 \0」喂 SHA-256 ⇒ 与文件系统遍历顺序、绝对路径无关。
        val files = layoutGeometryFiles.files.sortedBy { it.relativeTo(root).path }
        check(files.isNotEmpty()) { "layoutGeometryStamp: 没找到任何几何源码，拒绝产出一个全零指纹" }
        val md = MessageDigest.getInstance("SHA-256")
        for (f in files) {
            md.update(f.relativeTo(root).path.toByteArray())
            md.update(0)
            md.update(f.length().toString().toByteArray())
            md.update(0)
            md.update(f.readBytes())
            md.update(0)
        }
        val digest = md.digest()
        val value = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            // 注意：下面这个三引号字符串里的 KDoc（/** … */）是**生成物的内容**，
            // 不是本文件的注释，所以嵌套无碍——但生成物里同样别写 `/*`（它会被再编译一次）。
            writeText(
                """
                |// 由 Gradle 任务 `generateLayoutGeometryStamp` 生成，**不要手改**（改了会被下次构建覆盖）。
                |// 生成方式见根 build.gradle.kts 同名任务。
                |package orilumn.reader.engine
                |
                |/**
                | * **行几何源码指纹** —— [PaginationCacheCodec.LAYOUT_VERSION] 的唯一来源。
                | *
                | * 引擎源码内容一变（已登记引擎模块的任一主源集文件），本值就变
                | * ⇒ 旧分页磁盘表在唯一的 [PaginationCacheCodec.decode] 关卡被拒 ⇒ 自动重排。
                | * 反之源码没变则值不变 ⇒ 缓存跨构建存活（不是「每次构建都作废」）。
                | *
                | * 人**不需要**（也不应该）手动 bump 它：靠记忆的纪律必然会漏，
                | * 而漏一次的后果是「修复上线后读者看到的还是旧版面」，且日志毫无异常。
                | */
                |internal object LayoutGeometryStamp {
                |    /** SHA-256 前 4 字节（大端）当 Int。 */
                |    const val VALUE: Int = $value
                |
                |    /** 指纹前 6 字节十六进制，只为**可诊断**（日志里能把 miss 对上具体源码状态）。 */
                |    const val DIGEST_PREFIX: String = "sha256:$hex"
                |
                |    /** 参与指纹的文件数。 */
                |    const val FILE_COUNT: Int = ${files.size}
                |}
                |
                """.trimMargin(),
            )
        }
        logger.lifecycle("layoutGeometryStamp: ${files.size} 个文件 → 0x%08x ($hex…)".format(value))
    }
}

// ===========================================================================
// 漏登记守卫：把「新增引擎模块忘了登记」从「注释提醒」升级成「构建失败」
// ===========================================================================
//
// 上面刻意不维护文件白名单（宁可多作废），但**模块**这一层还是有个登记表 —— 而登记表
// 天然会被漏：某天把断行器搬到新模块 engine-foo，几何改动就此不再被感知，
// 后果和「漏 bump LAYOUT_VERSION」一模一样：读者看到旧版面，日志毫无异常。
//
// 判据不靠人自觉，靠**包路径**：布局引擎的代码一律在 `orilumn/reader/engine` 包根下
// （架构约束里的排版层 / 渲染层都在这个包根下）。所以扫描所有模块的主源集，
// 凡是**未登记模块**里出现该包路径的 .kt ⇒ 直接 fail 构建。
val unregisteredEngineCode by tasks.registering {
    description = "守卫：未登记的模块里不得出现 orilumn.reader.engine 包下的排版/渲染代码"
    group = "orilumn"
    val registered = layoutGeometryModules.toSet()
    // 全仓库 src 下的引擎包代码，含未登记模块 —— 用 fileTree 让 Gradle 跟踪，增量构建才判得出变化。
    val allEngineCode: org.gradle.api.file.FileCollection = rootProject.files(
        rootProject.subprojects.map { p ->
            // ConfigurableFileTree 本身实现 PatternFilterable，直接 include 即可。
            // （`fileTree(dir) { }` 那个带配置闭包的重载只存在于 Groovy Closure 形式，Kotlin 里用不了。）
            p.fileTree(java.io.File(p.projectDir, "src")).apply {
                include("**/kotlin/orilumn/reader/engine/**")
            }
        },
    )
    inputs.files(allEngineCode).withPropertyName("engineCodeEverywhere").withPathSensitivity(PathSensitivity.RELATIVE)
    // 无输出物：纯校验任务，靠 inputs 变化决定是否重跑。
    doLast {
        val root = rootProject.projectDir
        // 相对路径形如 `<module>/src/<sourceSet>/kotlin/orilumn/reader/engine/...` ⇒ 首段即模块名。
        val offenders = allEngineCode.files
            .filter { it.extension == "kt" }
            .filter { f -> f.relativeTo(root).path.substringBefore("/src/") !in registered }
            .map { it.relativeTo(root).path }
            .sorted()
        check(offenders.isEmpty()) {
            buildString {
                appendLine("unregisteredEngineCode 失败：以下未登记模块里出现了排版/渲染引擎代码（包路径 orilumn.reader.engine 下）：")
                offenders.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("这些代码的几何改动**不会**作废分页磁盘表 ⇒ 读者会看到旧版面且日志无异常。")
                append("修法：把该模块名加进根 build.gradle.kts 的 layoutGeometryModules。")
            }
        }
    }
}

// 守卫先跑，再生成指纹。
generateLayoutGeometryStamp.configure { dependsOn(unregisteredEngineCode) }
