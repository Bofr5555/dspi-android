// :feature-analysis — Wave B package ANALYSIS (docs/PACKAGES.md).
// Ports: TestSignalsView.swift, SpectrumAnalyser*.swift, StatsView.swift, InterruptMonitor.swift
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.analysis"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
