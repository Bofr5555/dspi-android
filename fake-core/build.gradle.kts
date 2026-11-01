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
// Review N2 / decision E8: only the `dspstate_*.json` fixtures go on the
// classpath (and hence into the app's APK, which ships demo mode);
// capture.json, bulk_v32.bin, platform.bin, firmware_req_opcodes.txt and
// the README stay FFI-only.
sourceSets.main {
    resources.srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
    resources.include("dspstate_*.json")
}
