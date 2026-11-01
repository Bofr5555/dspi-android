// :feature-firmware — Wave B package FIRMWARE (docs/PACKAGES.md).
// Ports: FirmwareUpdateView.swift, FirmwareInstallUI.swift, FirmwareInstaller.swift, FirmwareVersion.swift
// Depends on contracts only: :bridge (DspiBridge), :ui-common and :usb (BootloaderLocator).
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
    // Catalog library :app already uses (no new artifacts): SAF pickers (.uf2 file, the board's drive).
    implementation(libs.androidx.activity.compose)
    // @Previews (src/debug) run against FakeDspiCore, as PACKAGES.md asks; never in release.
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
