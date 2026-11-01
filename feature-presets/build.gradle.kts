// :feature-presets — Wave B package PRESETS (docs/PACKAGES.md).
// Ports: ContentView preset menu, Unsaved Changes, PresetDocument*/FilterFile import-export (core JSON), AutoEQ/*
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.presets"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    // A catalog library :app already uses (no new artifacts): FileProvider for the share sheet.
    implementation(libs.androidx.core.ktx)
    // @Previews (src/debug) run against FakeDspiCore, as PACKAGES.md asks; never in release.
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
