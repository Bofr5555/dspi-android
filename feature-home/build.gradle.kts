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
    // The one allowed feature->feature edge: the embeddable GraphView (GRAPH's contract).
    implementation(project(":feature-graph"))
    // Coordinator E2: @Previews run on FakeDspiCore (debug only; release never sees it).
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
