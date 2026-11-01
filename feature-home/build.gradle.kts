// :feature-home — Wave B package HOME (docs/PACKAGES.md).
// Ports: ContentView.swift (HomeScreen/ChannelScreen), Components.swift, DashboardView.swift, CustomSlider/ChannelPalette
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.home"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    // Allowed feature edges: the embeddable GraphView (GRAPH contract) …
    implementation(project(":feature-graph"))
    // Coordinator F1: the second allowed feature edge — PRESETS' preset flows
    // (PresetMenuActions, AlertHost, ConsoleAlertDialog) behind the sidebar picker.
    implementation(project(":feature-presets"))
    // E9: the preset controller and its alert slot live in a ViewModel.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Coordinator E2: @Previews run on FakeDspiCore (debug only; release never sees it).
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
