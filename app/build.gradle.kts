// :app — wiring only (decision D9): NavHost over the feature entry points,
// the real stack (FfiDspiCore -> DspiBridgeImpl -> AndroidUsbSession), the
// explicit demo mode (FakeDspiCore.demo()), and the DataStore LinkStore.
// Owned by package APP (integration); packages do not edit it except through
// the route table in :ui-common.
plugins {
    id("dspi.android-app")
}

android {
    namespace = "se.bofr.dspi.app"
    defaultConfig {
        applicationId = "se.bofr.dspi"
        versionCode = 1
        versionName = "0.0.1-wave0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    // JVM tests touch android.util.Log / android.os only through code paths
    // they don't assert on; the stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":bridge"))
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
