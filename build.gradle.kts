plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

tasks.register("assembleBackendRelease") {
    group = "build"
    description = "Builds all backend Release AARs and the unsigned diagnostic host."
    dependsOn(
        ":core:model:assembleRelease",
        ":core:data:assembleRelease",
        ":core:import:assembleRelease",
        ":core:conversation:assembleRelease",
        ":core:memory:assembleRelease",
        ":core:llm:assembleRelease",
        ":core:tools:assembleRelease",
        ":core:api:assembleRelease",
        ":runtime:sandbox:assembleRelease",
        ":runtime:host:assembleRelease",
        ":app:assembleRelease",
    )
}
