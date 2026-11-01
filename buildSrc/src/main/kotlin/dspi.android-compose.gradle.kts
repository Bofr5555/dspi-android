// Android library with Jetpack Compose (ui-common and the feature modules).
plugins {
    id("dspi.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform(libs.lib("compose-bom"))
    "implementation"(bom)
    "implementation"(libs.lib("compose-ui"))
    "implementation"(libs.lib("compose-ui-graphics"))
    "implementation"(libs.lib("compose-foundation"))
    "implementation"(libs.lib("compose-material3"))
    "implementation"(libs.lib("compose-ui-tooling-preview"))
    "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
    "debugImplementation"(libs.lib("compose-ui-tooling"))
}
