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
    }
}

rootProject.name = "SimpleTavern-Android"

include(
    ":app",
    ":core:model",
    ":core:data",
    ":core:import",
    ":core:conversation",
    ":core:memory",
    ":core:llm",
    ":core:tools",
    ":core:api",
    ":runtime:sandbox",
    ":runtime:host",
)
