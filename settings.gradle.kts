pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Our pinned GPLv3 Commons variant only changes its inaccurate
        // Fossify-only authenticity warning for the independent Messages app.
        maven { url = uri("$rootDir/vendor/maven") }
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")
