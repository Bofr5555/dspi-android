// FakeDspiCore: DspiCoreApi without hardware, for demo mode and UI/bridge tests.
// Owned by package BRIDGE (docs/PACKAGES.md), which seeds it from a V32 fixture.
plugins {
    id("dspi.jvm-library")
}

dependencies {
    api(project(":core-api"))
}

// D10: the repo-root fixtures/ (V32 capture from package FFI) are classpath
// resources of fake-core (main, hence also its tests and every consumer's
// tests): `FakeDspiCore.seeded()` reads `/dspstate_v32.json`. Gradle skips a
// missing srcDir, so this is harmless before fixtures/ exists.
sourceSets.main {
    resources.srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
    resources.exclude("README.md")
}
