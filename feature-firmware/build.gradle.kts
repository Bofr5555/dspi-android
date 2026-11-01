// :feature-firmware — Wave B package FIRMWARE (docs/PACKAGES.md).
// Ports: FirmwareUpdateView.swift, FirmwareInstallUI.swift, FirmwareInstaller.swift, FirmwareVersion.swift
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.firmware"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    implementation(project(":usb"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
