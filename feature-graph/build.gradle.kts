// :feature-graph — Wave B package GRAPH (docs/PACKAGES.md).
// Ports: GraphView.swift, GraphSpectrumOverlay.swift, PeqGraphEditor*.swift (logic from Windows PeqGraphEditor.cs), PeqBandHUD.swift
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.graph"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    // @Previews (src/debug only) run against FakeDspiCore; release builds never see it.
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
