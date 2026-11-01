// FakeDspiCore: DspiCoreApi without hardware, for demo mode and UI/bridge tests.
// Owned by package BRIDGE (docs/PACKAGES.md), which seeds it from a V32 fixture.
plugins {
    id("dspi.jvm-library")
}

dependencies {
    api(project(":core-api"))
}
