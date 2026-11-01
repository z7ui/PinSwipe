pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Xposed API 不在 Maven Central 上，历史上放在官方仓库
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "pinswipe"
include(":app")