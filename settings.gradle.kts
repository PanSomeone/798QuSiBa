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
        // 扫码（CameraX + ML Kit）只发布在 JitPack 上。分组过滤把它的影响范围
        // 限制在那一个 artifact 里：否则 JitPack 会参与解析每一个依赖，而它是
        // 一个按需构建的代理，解析失败会以超时而不是「找不到」的形式出现。
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.T8RIN.*") }
        }
    }
}

rootProject.name = "KILLHuiShengHuo"
include(":app")
