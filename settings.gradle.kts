// Orilumn 工程仓库配置
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JetBrains compose-dev：skiko-android（S8 的 Android .so）只发这里，不在 MavenCentral
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
        // 国内镜像（npmmirror 需 https，这里补一个备用）
        // maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}

rootProject.name = "orilumn"

include(":app")
include(":common")
include(":engine-skia")
include(":shared-ui")
include(":desktopApp")