// :app — wiring only (decision D9): NavHost over the feature entry points,
// the real stack (FfiDspiCore -> DspiBridgeImpl -> AndroidUsbSession), the
// explicit demo mode (FakeDspiCore.demo()), and the DataStore LinkStore.
// Owned by package APP (integration); packages do not edit it except through
// the route table in :ui-common.
import java.util.Properties

plugins {
    id("dspi.android-app")
}

// Release (Google Play) signing: the UPLOAD key, never committed. Default
// location keystore/upload.properties (+ the keystore file it names), both
// gitignored; created by tools/upload-key.sh. -Pdspi.uploadKey=<path> points
// at another properties file (tools/ci.sh release uses a throwaway key in a
// temp dir). Keys: storeFile (relative to the properties file), storePassword,
// keyAlias, keyPassword. See docs/RELEASE.md.
val uploadKeyProps: File =
    providers.gradleProperty("dspi.uploadKey").map { file(it) }
        .getOrElse(rootProject.file("keystore/upload.properties"))
val uploadKey: Properties? =
    uploadKeyProps.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "se.bofr.dspi.app"
    defaultConfig {
        applicationId = "se.bofr.dspi_console_android"
        // The ONE place for the app version (runtime reads PackageInfo).
        // Every upload to Play needs a higher versionCode than any before it.
        versionCode = 8
        versionName = "0.9.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // The container's home (and with it ~/.android/debug.keystore) is thrown
    // away after every run, so debug builds would get a new signature each
    // time and could not update an installed copy. Use a persistent,
    // gitignored project keystore when it exists (tools/debug-keystore.sh).
    val projectDebugKeystore = rootProject.file("keystore/debug.keystore")
    if (projectDebugKeystore.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = projectDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    if (uploadKey != null) {
        signingConfigs.create("upload") {
            storeFile = uploadKeyProps.parentFile.resolve(uploadKey.getProperty("storeFile") ?: "upload.jks")
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // R8 off: UniFFI's Kotlin bindings reach libdspi_ffi through JNA
            // (reflection over Structure fields / Library interfaces); shrinking
            // would need keep rules for both and device testing, for little gain
            // (the native libraries and bundled assets dominate the size). :ffi always links the Rust release profile here.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    // JVM tests touch android.util.Log / android.os only through code paths
    // they don't assert on; the stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":bridge"))
    // E8: the one release-time :fake-core user (demo mode); tools/ci.sh release-deps allows only :app
    implementation(project(":fake-core"))
    implementation(project(":ffi"))
    implementation(project(":usb"))
    implementation(project(":ui-common"))
    implementation(project(":feature-home"))
    implementation(project(":feature-graph"))
    implementation(project(":feature-matrix"))
    implementation(project(":feature-tools"))
    implementation(project(":feature-presets"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-analysis"))
    implementation(project(":feature-firmware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Fail early and clearly instead of producing an unsigned / wrongly signed release.
val checkUploadKey by tasks.registering {
    description = "Fail the release build when the upload key (keystore/upload.properties) is missing."
    val props = uploadKeyProps
    val storeFile = uploadKey?.let { props.parentFile.resolve(it.getProperty("storeFile") ?: "upload.jks") }
    val missing =
        listOf("storePassword", "keyAlias", "keyPassword").filter { uploadKey?.getProperty(it).isNullOrEmpty() }
    doLast {
        if (!props.isFile) {
            throw GradleException(
                "Release signing: upload key properties not found at $props.\n" +
                    "Create the upload key with tools/upload-key.sh (see docs/RELEASE.md), " +
                    "or pass -Pdspi.uploadKey=<path/to/upload.properties>."
            )
        }
        if (missing.isNotEmpty()) throw GradleException("Release signing: $props lacks ${missing.joinToString()}.")
        if (storeFile == null || !storeFile.isFile) {
            throw GradleException("Release signing: keystore $storeFile (storeFile in $props) does not exist.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkUploadKey) }
