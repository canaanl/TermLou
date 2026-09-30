pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "TermLou"
include(":workspace")
include(":app")
// 一次性实验模块：验证"不挂窗口的 WebView 能不能用"。
// 与 :app 零依赖、可并排安装、测完整个删掉 —— 实验失败对正式版零影响。
include(":probe")
