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
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
