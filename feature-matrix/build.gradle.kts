// :feature-matrix — Wave B package MATRIX (docs/PACKAGES.md).
// Ports: MatrixMixerView.swift
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.matrix"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    // @Previews (src/debug only) run against FakeDspiCore (PACKAGES.md Wave B DoD);
    // release builds never see it.
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
