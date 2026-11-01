// DspiBridge: the ViewModel-facing API (port of DSPi-Console-Linux
// qt/src/DSPiBridge.* and its controllers). Pure JVM + coroutines.
// The interfaces in this module are a CONTRACT (Wave 0); the implementation
// is owned by package BRIDGE (docs/PACKAGES.md).
plugins {
    id("dspi.jvm-library")
}

dependencies {
    api(project(":core-api"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
