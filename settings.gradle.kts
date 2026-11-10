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
        // NewPipe Extractor (встроенный онлайн-движок) публикуется через JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Lumen"
include(":app")
