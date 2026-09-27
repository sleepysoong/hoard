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
        // termux-shared (TermuxConstants) is only published on JitPack; nothing else may come from it.
        maven("https://jitpack.io") {
            content { includeModule("com.github.termux.termux-app", "termux-shared") }
        }
    }
}

rootProject.name = "Hoard"
include(":app")
