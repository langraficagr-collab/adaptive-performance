pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AdaptivePerformance"
include(":app", ":reddit-promo", ":liteapp")

// pythonapp is an independent prototype, not part of the primary build.

// nativeapp is an independent NDK prototype, not part of the primary build.
