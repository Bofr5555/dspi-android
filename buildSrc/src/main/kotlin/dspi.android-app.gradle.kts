import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dspi.ktlint")
}

android {
    compileSdk = libs.int("compileSdk")
    buildToolsVersion = libs.findVersion("buildTools").get().requiredVersion
    defaultConfig {
        minSdk = libs.int("minSdk")
        targetSdk = libs.int("targetSdk")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val bom = platform(libs.lib("compose-bom"))
    "implementation"(bom)
    "implementation"(libs.lib("compose-ui"))
    "implementation"(libs.lib("compose-foundation"))
    "implementation"(libs.lib("compose-material3"))
    "implementation"(libs.lib("compose-ui-tooling-preview"))
    "debugImplementation"(libs.lib("compose-ui-tooling"))
    "testImplementation"(libs.lib("junit"))
}
