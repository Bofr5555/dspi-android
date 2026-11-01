// :feature-settings — Wave B package SETTINGS (docs/PACKAGES.md).
// Ports: DSPi_ConsoleApp.swift SettingsView + *SettingsTab, SettingsSaveCoordinator; Windows PendingChangeTracker/PinNavigation
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.settings"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
