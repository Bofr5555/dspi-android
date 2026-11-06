// :link — DSPi Link client on Android: hub discovery (NsdManager, manual
// addresses), the session engine (connect, PIN pairing, device choice,
// reconnect with backoff), Keystore-encrypted tokens, the Link settings.
// The protocol itself is Rust (rust/dspi-link via dspi-ffi); this module
// drives it through DspiCoreApi on the REMOTE stack's own core and bridge.
plugins {
    id("dspi.android-library")
}

android {
    namespace = "se.bofr.dspi.link"
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":bridge"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
