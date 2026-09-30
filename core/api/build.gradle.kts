plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.simpletavern.core.api"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    api(project(":core:model"))
    api(project(":core:data"))
    api(project(":core:import"))
    api(project(":core:conversation"))
    api(project(":core:memory"))
    api(project(":core:llm"))
    api(project(":core:tools"))
    api(project(":runtime:sandbox"))

}
